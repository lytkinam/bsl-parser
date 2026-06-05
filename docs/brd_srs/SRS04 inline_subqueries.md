# SRS04: Извлечение inline-подзапросов из не-FROM контекстов

## 1. Введение

### 1.1 Цель

Определить технические требования к подсистеме извлечения inline-подзапросов, расположенных вне секции `FROM` (WHERE, виртуальные таблицы, SELECT-выражения, JOIN-условия). Подсистема обеспечивает структурированное представление этих подзапросов и их зависимостей в конвейере SDBL_PARS → LINE_PARS → FULL_PARS.

### 1.2 Область применения

Модуль SDQL. Разработка в ветке `develop`, коммиты содержат `Refs Redmine #646`.

### 1.3 Термины

| Термин | Описание |
|--------|----------|
| **Inline-подзапрос** | Подзапрос, расположенный вне секции `FROM`: в `WHERE`, параметрах вирт. таблицы, `SELECT`-выражении, `JOIN`-условии |
| **FROM-подзапрос** | Подзапрос в скобках в секции `FROM` — уже поддерживается (BRD01–BRD03) |
| **Context** | Контекст расположения inline-подзапроса: `where`, `virtualTable`, `select`, `joinCondition` |
| **Reference name** | Имя-ссылка, заменяющее подзапрос в исходном тексте: `<parent_name>_INLINE_<N>` |
| **SUB-нода** | Нода типа `sub_query` в `LINE_PARS`, созданная из inline-подзапроса |

### 1.4 Ссылки

- `docs/brd_srs/BRD04 inline_subqueries.md`
- `docs/brd_srs/SRS01 full_pars.md`
- `docs/brd_srs/SRS02 query_reconstruction.md`
- `docs/brd_srs/SRS03 query_extraction_and_verification.md`

---

## 2. Общее описание

### 2.1 Контекст

ANTLR4-грамматика SDBL допускает подзапросы в нескольких контекстах:
- `inPredicate` (WHERE, HAVING, JOIN-условия, параметры вирт. таблиц)
- `bracketExpression` (SELECT-выражения)
- `dataSource` (FROM — уже поддерживается)

Текущий `QueryPackageVisitor` извлекает подзапросы только из `dataSource`. Настоящий SRS определяет требования к извлечению из остальных контекстов.

### 2.2 Пользователи

Разработчики 1С, аналитики запросов. Используют для:
- Построения полной карты зависимостей (включая скрытые в тексте подзапросы)
- Восстановления SQL с корректной структурой
- Анализа lineage полей, проходящих через inline-подзапросы

### 2.3 Ограничения

- **Формат первичен** — выходной JSON определяется примером
- **Пример первее кода** — `example_4` генерируется до коммита кода
- **Не менять грамматику** — `SDBLParser.g4` остаётся без изменений
- **Минимальные изменения** — не ломать существующие модули BRD01–BRD03

---

## 3. Функциональные требования

### 3.1 ANTLR Visitor — QueryPackageVisitor

#### FR-3.1.1 Извлечение из inPredicate
При посещении `SDBLParser.InPredicateContext`:
- Если `ctx.subquery() != null` — вызвать `visitSubquery(ctx.subquery())`
- Сохранить результат в список inline-подзапросов текущего `QueryAst`
- Установить `context = "where"` (или определять по родительскому контексту)

#### FR-3.1.2 Извлечение из bracketExpression
При посещении `SDBLParser.ExpressionFieldContext` (или `ExpressionContext`):
- Если `expression` содержит `bracketExpression` с `subquery()` — вызвать `visitSubquery()`
- Сохранить результат с `context = "select"`

#### FR-3.1.3 Извлечение из virtualTableParameter
При посещении `SDBLParser.VirtualTableContext`:
- Для каждого `virtualTableParameter()`:
  - Если `logicalExpression()` содержит `inPredicate` с `subquery()` — вызвать `visitSubquery()`
  - Сохранить результат с `context = "virtualTable"`

#### FR-3.1.4 Извлечение из joinPart.condition
При посещении `SDBLParser.JoinPartContext`:
- Если `condition` (`logicalExpression`) содержит `inPredicate` с `subquery()` — вызвать `visitSubquery()`
- Сохранить результат с `context = "joinCondition"`

#### FR-3.1.5 Унификация алгоритма
Все 4 контекста используют **единый** метод извлечения:
```java
private InlineSubquery extractInlineSubquery(SDBLParser.SubqueryContext ctx, String context)
```
Метод:
1. Вызывает `visitSubquery(ctx)` для получения `QueryAst`
2. Формирует `referenceName` = `<current_query_name>_INLINE_<N>`
3. Возвращает объект `InlineSubquery` с полями: `context`, `name`, `query`

### 3.2 Модель данных

#### FR-3.2.1 InlineSubquery
Новый класс модели:
```java
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InlineSubquery {
  private String context;   // "where", "virtualTable", "select", "joinCondition"
  private String name;      // "Результат_3_INLINE_1"
  private QueryAst query;   // AST подзапроса
}
```

#### FR-3.2.2 Расширение QueryAst
В `QueryAst` добавляется поле:
```java
private List<InlineSubquery> inlineSubqueries;
```
Сериализуется как массив объектов. Если пусто — не выводится (`NON_NULL`).

#### FR-3.2.3 Расширение SelectField (опционально)
Если inline-подзапрос находится в `select[].text` — в `SelectField` может добавляться `inlineSubqueryName` для упрощения поиска при восстановлении.

### 3.3 LINE_PARS — LineParsModelBuilder

#### FR-3.3.1 Создание SUB-нод для inline-подзапросов
`processAst()` должен обрабатывать `inlineSubqueries` аналогично `from[].subquery`:
- Для каждого `InlineSubquery` создать `LineParsNode`:
  - `id` = `idCounter++`
  - `name` = `inlineSubquery.getName()`
  - `type` = `"sub_query"`
  - `upqueryId` = `parent.getId()`
- Добавить в `parent.getSubqueryIds()`
- Вызвать `processAst(subNode, inlineSubquery.getQuery())` рекурсивно

#### FR-3.3.2 Замена в тексте родителя
После создания SUB-ноды текст родителя должен содержать ссылку вместо подзапроса.
Реализация: замена выполняется на этапе visitor (текст подзапроса заменяется на `name` при формировании `where`, `virtualTable` и т.д.).

#### FR-3.3.3 Именование
Формат имени inline-SUB-ноды:
```
<parent_name>_INLINE_<N>
```
где `<parent_name>` — имя родительской ноды (`LineParsNode.name`), `<N>` — порядковый номер (1-based) в рамках родителя.

Счётчик `inlineSubqueryCounters` (аналог `subQueryCounters`) — `Map<Integer, Integer>` по `parent.getId()`.

### 3.4 FULL_PARS — FullParsModelBuilder

#### FR-3.4.1 child_fields для inline-подзапросов
При построении `child_fields` для текстовых полей (`where`, `having`, `joins[].condition`, `select[].text`, `virtualTable`):
- Если текст содержит ссылку на inline-подзапрос (`_INLINE_\d+`), `FullParsModelBuilder` должен:
  - Найти соответствующую SUB-ноду по имени
  - Либо добавить `child_fields` из этой SUB-ноды (рекурсивно один уровень)
  - Либо добавить запись с `node_id` = `id` SUB-ноды

#### FR-3.4.2 where_fields с inline-подзапросами
Если `where` содержит ссылку на inline-подзапрос, `where_fields` должен содержать `FullParsConditionField` с `child_fields`, указывающими на inline-SUB-ноду.

### 3.5 Иерархия — LineParsHierarchyBuilder

#### FR-3.5.1 Inline-SUB-ноды в иерархии
`extractDataSource()` уже обрабатывает `ds.getSubquery() != null`. Для inline-подзапросов:
- Inline-SUB-ноды должны добавляться в `table_hierarchy` родителя как ноды типа `subquery`
- Поиск по `nodeByName` (inline-SUB-нода доступна по имени-ссылке)

### 3.6 Восстановление SQL — QueryReconstructor

#### FR-3.6.1 Inline-вставка подзапросов
`SqlGenerator` при форматировании текста, содержащего ссылку на inline-подзапрос:
- Должен заменять `<name>_INLINE_<N>` на `(ВЫБРАТЬ ...)` — SQL inline-подзапроса
- Поиск inline-подзапроса: по `subquery_ids` родителя (как для FROM-подзапросов)

#### FR-3.6.2 Топологическая сортировка
`TopologicalSorter` должен учитывать зависимости inline-подзапросов:
- Inline-SUB-ноды не входят в топологический порядок (inline-ятся)
- Их зависимости проксируются к родителю (как для FROM-SUB-нод)

### 3.7 Тестирование

#### FR-3.7.1 Тест наличия inline-SUB-нод
`testInlineSubqueries()` — проверяет, что для `example_4` в `LINE_PARS_model` присутствуют ноды `_INLINE_1` (WHERE) и `_INLINE_2` (virtualTable).

#### FR-3.7.2 Тест child_fields
Проверяет, что `where_fields` и/или `join_conditions` содержат `child_fields` с `node_id` inline-SUB-ноды.

#### FR-3.7.3 Тест восстановления SQL
Проверяет, что восстановленный SQL содержит подзапросы в скобках (а не `_INLINE_N`).

---

## 4. Структуры данных

### 4.1 InlineSubquery

```java
package com.github._1c_syntax.bsl.parser.sdql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InlineSubquery {
  private String context;
  private String name;
  private QueryAst query;
}
```

### 4.2 Расширение QueryAst

```java
// В QueryAst.java добавить:
private List<InlineSubquery> inlineSubqueries;
```

### 4.3 Пример JSON (SDBL_PARS)

```json
{
  "type": "select",
  "select": [...],
  "from": [...],
  "where": "Контрагенты.Ссылка В Результат_3_INLINE_1",
  "inlineSubqueries": [
    {
      "context": "where",
      "name": "Результат_3_INLINE_1",
      "query": {
        "type": "select",
        "select": [{"text": "НашиКонтрагенты.Ссылка", "alias": "Ссылка"}],
        "from": [{"table": "Справочник.Контрагенты", "alias": "НашиКонтрагенты"}]
      }
    }
  ]
}
```

---

## 5. Алгоритмы

### 5.1 Извлечение inline-подзапросов в Visitor

```
function visitQuery(ctx):
    ast = new QueryAst()
    ast.inlineSubqueries = []
    
    // Собираем inline-подзапросы из всех контекстов
    for each inPredicate in ctx.where:
        if inPredicate.subquery != null:
            ast.inlineSubqueries.add(extractInline(inPredicate.subquery, "where"))
    
    for each vtParam in ctx.virtualTableParameters:
        for each inPredicate in vtParam.logicalExpression:
            if inPredicate.subquery != null:
                ast.inlineSubqueries.add(extractInline(inPredicate.subquery, "virtualTable"))
    
    for each selectField in ctx.select:
        if selectField.expression contains bracketExpression.subquery:
            ast.inlineSubqueries.add(extractInline(bracketExpression.subquery, "select"))
    
    for each joinPart in ctx.joins:
        for each inPredicate in joinPart.condition:
            if inPredicate.subquery != null:
                ast.inlineSubqueries.add(extractInline(inPredicate.subquery, "joinCondition"))
    
    return ast

function extractInline(subqueryCtx, context):
    queryAst = visitSubquery(subqueryCtx)
    name = generateInlineName(context)
    return InlineSubquery(context, name, queryAst)
```

### 5.2 Замена текста в Visitor

При формировании текстовых полей (`where`, `virtualTable`, `select[].text`, `condition`):
- Вместо `textOf(ctx)` для контекста, содержащего inline-подзапрос, формировать текст с заменой:
  - Найти позицию подзапроса в исходном тексте
  - Заменить на `name`

### 5.3 Создание SUB-нод в LineParsModelBuilder

```
function processAst(parent, ast):
    // Существующая обработка UNION и FROM-подзапросов
    ...
    
    // Новая: обработка inline-подзапросов
    if ast.inlineSubqueries != null:
        for inline in ast.inlineSubqueries:
            subNode = createSubNode(parent, inline)
            nodes.add(subNode)
            parent.subqueryIds.add(subNode.id)
            processAst(subNode, inline.query)
```

---

## 6. Каскадное обновление форматов (последствия для downstream)

Добавление `inlineSubqueries` в `QueryAst` требует переписывания **всех** downstream-форматов и builder-ов. Порядок обновления:

### 6.1 SDBL_PARS (итерация 0)
**Изменение:** `QueryAst` + новый класс `InlineSubquery`.
**Что обновить:**
- `QueryPackageVisitor` — извлечение inline-подзапросов, замена текста
- `QueryAst.java` — поле `inlineSubqueries`
- `InlineSubquery.java` — новый класс

**Обратная совместимость:** поле опциональное (`@JsonInclude(NON_NULL)`), существующие JSON без него читаются корректно.

### 6.2 LINE_PARS (итерация 1)
**Изменение:** появление новых нод типа `sub_query` (inline).
**Что обновить:**
- `LineParsModelBuilder.processAst()` — обработка `ast.getInlineSubqueries()`
- `LineParsModelBuilder` — новый счётчик `inlineSubqueryCounters`
- `LineParsHierarchyBuilder` — inline-SUB-ноды в `table_hierarchy`
- `LineParsFieldExtractor` — `child_fields` через inline-SUB-ноды

**Обратная совместимость:** ноды без `inlineSubqueries` обрабатываются как раньше.

### 6.3 FULL_PARS (итерация 3)
**Изменение:** `child_fields` для текстовых полей (`where`, `having`, `condition`, `virtualTable`, `select[].text`) теперь может содержать `node_id` inline-SUB-ноды.
**Что обновить:**
- `FullParsModelBuilder.extractChildFields()` — распознавание ссылок `_INLINE_\d+` в тексте
- `FullParsModelBuilder.extractConditionFields()` — то же для where/join/group_by/having
- `FullParsModelBuilder.resolveChildField()` — поиск inline-SUB-ноды по имени в `nodeByName`

**Важно:** `FullParsChildField` не меняется — добавляется только новый `node_id` (уже существующее поле).

### 6.4 full_field_lineage (FFL)
**Изменение:** в FFL попадают inline-SUB-ноды как обычные `sub_query` ноды.
**Что обновить:**
- `FullFieldLineageBuilder.buildLineage()` — inline-SUB-ноды обрабатываются через `child_fields[].node_id` как обычные SUB-ноды
- `FullFieldLineageBuilder.createNode()` — копирует `subqueryIds`, `unionNodesIds` как раньше

**Обратная совместимость:** FFL-структура не меняется, меняется только наполнение.

### 6.5 RESTORED_QUERIES (восстановление SQL)
**Изменение:** inline-подзапросы должны inline-иться обратно в скобки.
**Что обновить:**
- `QueryNodeBuilder.build()` — обработка `inlineSubqueries` (поиск по `subquery_ids`)
- `SqlGenerator.formatDataSource()` — уже поддерживает inline subquery из `from[].subquery`
- `SqlGenerator` — добавить обработку ссылок `_INLINE_\d+` в `where`, `virtualTable`, `select[].text`, `condition`

**Альтернатива:** вместо замены в тексте можно хранить `inlineSubqueries` в `RestoredQueryNode` и подставлять при генерации.

### 6.6 Markdown-отчёты
**Что обновить:**
- `SdqlModelMdBuilder` — вывод `inlineSubqueries` в разделе запроса
- `LineParsModelMdBuilder` — inline-SUB-ноды в таблице узлов
- `HierarchyMdBuilder` — тип `subquery` для inline-нод

### 6.7 Тесты
**Что обновить:**
- `SdqlQueryPackageAnalyzerTest` — проверка наличия `inlineSubqueries` в AST
- Новый тест `testInlineSubqueries()` — проверка LINE_PARS, FULL_PARS, RESTORED_QUERIES

---

## 7. Нефункциональные требования

### 7.1 Производительность
Дополнительный проход по AST для inline-подзапросов не должен увеличивать время парсинга более чем на 10%.

### 7.2 Обратная совместимость
- Поле `inlineSubqueries` в `QueryAst` — опциональное (`@JsonInclude(Include.NON_NULL)`)
- Существующие примеры (`example`, `example_258`, `middle_example`) без inline-подзапросов работают без изменений
- Все downstream-builder-ы должны корректно обрабатывать `null` для `inlineSubqueries`

---

## 8. История изменений

| Версия | Дата | Автор | Изменения |
|--------|------|-------|-----------|
| 1.0 | 2026-06-05 | Kimi AI Agent | Первоначальная версия |

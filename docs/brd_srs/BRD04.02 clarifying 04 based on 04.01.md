# BRD04.02: Уточнение BRD04 — блоковая структура where/having и извлечение inline-подзапросов

## 1. Цель

Уточнить BRD04 на основе опыта BRD04.01: внедрить блоковую структуру `where`/`having` (как в 04.01), но распространить на все контексты inline-подзапросов (WHERE, HAVING, virtualTable, SELECT, JOIN).

## 2. Проблемы BRD04, выявленные в 04.01

### 2.1 Плоский список `inlineSubqueries`
BRD04 предлагал плоский список `inlineSubqueries` в `QueryAst`. Проблема: невозможно определить, из какого конкретного JOIN-условия взят подзапрос (если подзапросов несколько).

### 2.2 Замена текста через `String.replace`
Ненадёжно: может повредить идентификаторы, содержащие имя подзапроса как подстроку.

### 2.3 Универсальные имена `_INLINE_N`
Неинформативно: непонятно, из какого контекста подзапрос.

## 3. Решения из 04.01, принимаемые в 04.02

| Решение 04.01 | Применение в 04.02 |
|---------------|-------------------|
| `where` как объект `WhereBlock` | Принимается. Распространяется также на `having` |
| Точная замена по `startIndex`/`endIndex` | Принимается для всех контекстов |
| Встраивание подзапросов в структуру родителя | Принимается: подзапросы внутри `WhereBlock.subqueries`, не плоский список |

## 4. Архитектура: WhereBlock / HavingBlock

### 4.1 Принцип: одно поле = блок

`where` — одно поле, объект с обязательным `text` и опциональными дополнениями в зависимости от уровня:

**SDBL_PARS:**
```json
{
  "where": {
    "text": "Контрагенты.Ссылка В (ВТ_Подзапрос_1)",
    "subqueries": [
      {
        "name": "ВТ_Подзапрос_1",
        "query": { "type": "select", ... }
      }
    ]
  }
}
```

**LINE_PARS:**
```json
{
  "where": {
    "text": "Контрагенты.Ссылка В (Результат_3_WHERE_1)",
    "subqueryIds": [7]
  }
}
```

**FULL_PARS:**
```json
{
  "where": {
    "text": "Контрагенты.Ссылка В (Результат_3_WHERE_1)",
    "subqueryIds": [7],
    "fields": [
      { "text": "Контрагенты.Ссылка", "child_fields": [...] }
    ]
  }
}
```

- `where.fields` — бывшее `where_fields`, структурированное представление WHERE с `child_fields`
- Отдельное поле `where_fields` на уровне ноды **удаляется**

### 4.2 HavingBlock

Аналогичная структура для `having`:

**SDBL_PARS:**
```json
{
  "having": {
    "text": "СУММА(Поле) > (ВЫБРАТЬ ...)",
    "subqueries": [...]
  }
}
```

**FULL_PARS:**
```json
{
  "having": {
    "text": "СУММА(Поле) > (Результат_3_HAVING_1)",
    "subqueryIds": [8],
    "fields": [...]
  }
}
```

## 5. Грамматика — изменения не требуются

ANTLR4-грамматика `SDBLParser.g4` **уже содержит** правила для подзапросов во всех нужных контекстах:

| Контекст | Правило в грамматике | Строка |
|----------|---------------------|--------|
| WHERE / HAVING | `inPredicate: ... LPAREN (subquery \| expressionList) RPAREN` | 309 |
| SELECT | `bracketExpression: (LPAREN subquery RPAREN)` | 212 |
| Виртуальная таблица | `virtualTableParameter: logicalExpression?` → `inPredicate` → `subquery` | 355 |
| JOIN-условие | `joinPart: ... BY condition=logicalExpression` → `inPredicate` → `subquery` | 365 |

**Вывод:** изменения в `SDBLParser.g4` **не требуются**. Подзапросы уже разбираются грамматикой. Проблема в том, что текущий `QueryPackageVisitor` **пропускает** эти подзапросы, беря только `textOf(ctx)` — весь текст контекста целиком, включая вложенные подзапросы как неразобранный текст.

**Что нужно доработать:** только `QueryPackageVisitor` — добавить обход дерева `ParserRuleContext` для извлечения `subquery` из `inPredicate` и `bracketExpression`.

## 6. Контексты inline-подзапросов

### 6.1 WHERE — `inPredicate` в `WhereBlock.subqueries`
Уже разобрано в 04.01. Подзапросы из `inPredicate` внутри `LogicalExpressionContext` WHERE попадают в `WhereBlock.subqueries`.

### 6.2 HAVING — `inPredicate` в `HavingBlock.subqueries`
Аналогично WHERE. Подзапросы из `inPredicate` внутри HAVING попадают в `HavingBlock.subqueries`.

### 6.3 Виртуальная таблица — `VirtualTableParameterBlock`
Параметры виртуальной таблицы (`virtualTableParameter: logicalExpression?`) могут содержать `inPredicate` с подзапросом.

**SDBL_PARS:**
```json
{
  "virtualTable": {
    "text": "РегистрСведений.Таб.СрезПоследних(&Период, Поле В (ВТ_Подзапрос_1))",
    "subqueries": [
      {
        "name": "ВТ_Подзапрос_1",
        "query": { ... }
      }
    ]
  }
}
```

**LINE_PARS:**
```json
{
  "virtualTable": {
    "text": "РегистрСведений.Таб.СрезПоследних(&Период, Поле В (Результат_3_VT_1))",
    "subqueryIds": [9]
  }
}
```

### 6.4 SELECT-выражение — `SelectField.inlineSubquery`
Подзапрос внутри `bracketExpression` в SELECT-поле:

**SDBL_PARS:**
```json
{
  "select": [
    {
      "text": "(ВТ_Подзапрос_1) КАК Поле",
      "alias": "Поле",
      "inlineSubquery": {
        "name": "ВТ_Подзапрос_1",
        "query": { ... }
      }
    }
  ]
}
```

**LINE_PARS:**
```json
{
  "select": [
    {
      "text": "(Результат_3_SELECT_1) КАК Поле",
      "alias": "Поле",
      "inlineSubqueryId": 10
    }
  ]
}
```

### 6.5 JOIN-условие — `JoinPart.conditionSubqueries`
Подзапросы внутри условия JOIN:

**SDBL_PARS:**
```json
{
  "joins": [
    {
      "joinType": "left",
      "source": { ... },
      "condition": "Таб1.Поле В (ВТ_Подзапрос_1)",
      "conditionSubqueries": [
        {
          "name": "ВТ_Подзапрос_1",
          "query": { ... }
        }
      ]
    }
  ]
}
```

**LINE_PARS:**
```json
{
  "joins": [
    {
      "joinType": "left",
      "source": { ... },
      "condition": "Таб1.Поле В (Результат_3_JOIN_1)",
      "conditionSubqueryIds": [11]
    }
  ]
}
```

## 7. Именование подзапросов

### 6.1 SDBL_PARS (временные имена)
- WHERE: `ВТ_Подзапрос_<N>`
- HAVING: `ВТ_Подзапрос_<N>` (общий счётчик с WHERE)
- virtualTable: `ВТ_Подзапрос_<N>` (общий счётчик)
- SELECT: `ВТ_Подзапрос_<N>` (общий счётчик)
- JOIN: `ВТ_Подзапрос_<N>` (общий счётчик)

Общий счётчик в рамках одного `QueryAst`.

### 6.2 LINE_PARS (имена SUB-нод)
- WHERE: `<parent>_WHERE_<N>`
- HAVING: `<parent>_HAVING_<N>`
- virtualTable: `<parent>_VT_<N>`
- SELECT: `<parent>_SELECT_<N>`
- JOIN: `<parent>_JOIN_<N>`

## 8. Точная замена текста

Вместо `String.replace()` использовать позиции из ANTLR токенов:

```java
StringBuilder sb = new StringBuilder(fullText);
int offset = 0;
for (SubqueryEntry entry : entries) {
    String alias = "ВТ_Подзапрос_" + counter++;
    String subText = textOf(entry.subqueryCtx);
    int start = entry.subqueryCtx.getStart().getStartIndex() - ctx.getStart().getStartIndex();
    int end = entry.subqueryCtx.getStop().getStopIndex() - ctx.getStart().getStartIndex() + 1;
    sb.replace(start + offset, end + offset, alias);
    offset += alias.length() - subText.length();
}
```

## 9. Downstream-изменения

### 8.1 SDBL_PARS
- `QueryAst.where` → `WhereBlock`
- `QueryAst.having` → `HavingBlock`
- `DataSource.virtualTable` → `VirtualTableBlock` (или остаётся строкой + `virtualTableSubqueries`)
- `SelectField.inlineSubquery` → `InlineSubquery`
- `JoinPart.conditionSubqueries` → `List<InlineSubquery>`

### 8.2 LINE_PARS
- `LineParsNode.where` → `WhereBlock` { text, subqueryIds }
- `LineParsNode.having` → `HavingBlock` { text, subqueryIds }
- SUB-ноды создаются из всех контекстов (WHERE, HAVING, VT, SELECT, JOIN)
- `subqueryIds` на уровне ноды содержит ВСЕ subquery (для обратной совместимости)

### 8.3 FULL_PARS
- `FullParsNode.where` → `WhereBlock` { text, subqueryIds, fields }
- `FullParsNode.having` → `HavingBlock` { text, subqueryIds, fields }
- Поля `whereFields`, `havingFields` **удаляются** (переезжают внутрь блоков)
- `FullParsModelBuilder` строит `fields` внутри блоков

### 8.4 FFL / RESTORED_QUERIES
- Без изменений структуры — только наполнение `child_fields` через subqueryIds

## 10. Тестирование

1. `example_4.sql` парсится без ошибок
2. В `sdbl_parse_model_example_4.json` `where` — объект с `text` и `subqueries`
3. В `LINE_PARS_model_example_4.json` `where` — объект с `text` и `subqueryIds`
4. В `FULL_PARS_model_example_4.json` `where` — объект с `text`, `subqueryIds`, `fields`
5. Восстановленный SQL содержит подзапросы в скобках

## 11. Ограничения

- **Формат первичен** — выходной JSON определяется примером
- **Пример первее кода** — `example_4` генерируется до коммита кода
- **Каскадное обновление** — изменение требует переписывания всех downstream builder-ов

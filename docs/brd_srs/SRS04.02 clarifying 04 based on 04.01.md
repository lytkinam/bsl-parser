# SRS04.02: Уточнение SRS04 — блоковая структура where/having и извлечение inline-подзапросов

## 1. Введение

### 1.1 Цель

Уточнить SRS04 на основе опыта SRS04.01: внедрить блоковую структуру `where`/`having`, распространить на все контексты inline-подзапросов.

### 1.2 Термины

| Термин | Описание |
|--------|----------|
| **WhereBlock** | Объект `{ text, subqueries?, subqueryIds?, fields? }` — блок WHERE на всех уровнях |
| **HavingBlock** | Аналогичный блок для HAVING |
| **VirtualTableBlock** | Блок для виртуальной таблицы с параметрами-подзапросами |
| **InlineSubquery** | Подзапрос вне FROM: `{ name, query }` |
| **Контекстное имя** | Имя SUB-ноды: `_WHERE_<N>`, `_HAVING_<N>`, `_VT_<N>`, `_SELECT_<N>`, `_JOIN_<N>` |

---

## 2. Модель данных

### 2.1 WhereBlock

```java
package com.github._1c_syntax.bsl.parser.sdql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WhereBlock {
  private String text;
  private List<WhereSubquery> subqueries;     // SDBL_PARS
  private List<Integer> subqueryIds;          // LINE_PARS / FULL_PARS
  private List<FullParsConditionField> fields; // FULL_PARS (бывшее where_fields)
}
```

### 2.2 HavingBlock

Идентичная структура:

```java
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HavingBlock {
  private String text;
  private List<WhereSubquery> subqueries;
  private List<Integer> subqueryIds;
  private List<FullParsConditionField> fields;
}
```

### 2.3 WhereSubquery

```java
@Data
public class WhereSubquery {
  private String name;      // ВТ_Подзапрос_N (SDBL_PARS)
  private QueryAst query;   // AST подзапроса
}
```

### 2.4 VirtualTableBlock (SDBL_PARS)

```java
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VirtualTableBlock {
  private String text;                    // Полный текст вирт. таблицы
  private List<WhereSubquery> subqueries; // Подзапросы в параметрах
}
```

### 2.5 SelectField (расширение)

```java
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SelectField {
  private String fieldType;
  private String text;
  private String alias;
  private InlineSubquery inlineSubquery;        // SDBL_PARS
  private Integer inlineSubqueryId;             // LINE_PARS
}
```

### 2.6 JoinPart (расширение)

```java
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JoinPart {
  private String joinType;
  private DataSource source;
  private String condition;
  private List<WhereSubquery> conditionSubqueries;  // SDBL_PARS
  private List<Integer> conditionSubqueryIds;       // LINE_PARS
}
```

---

## 3. Функциональные требования

### 3.1 SDBL_PARS — QueryPackageVisitor

**Важно:** изменения в ANTLR4-грамматике `SDBLParser.g4` **не требуются**. Подзапросы уже разбираются грамматикой в правилах `inPredicate` (строка 309) и `bracketExpression` (строка 212). Требуется только доработка `QueryPackageVisitor` для извлечения этих подзапросов из дерева разбора.

#### FR-3.1.1 WhereBlock
При обработке `QueryContext.where`:
1. Обойти дерево `LogicalExpressionContext` рекурсивно
2. Найти все `InPredicateContext` с `subquery() != null`
3. Для каждого: `visitSubquery()` → `WhereSubquery`
4. Построить `whereText` через `StringBuilder` с заменой по позициям
5. Сформировать `WhereBlock` и записать в `QueryAst.where`

#### FR-3.1.2 HavingBlock
Аналогично FR-3.1.1 для `QueryContext.having`.

#### FR-3.1.3 VirtualTableBlock
При обработке `DataSourceContext.virtualTable()`:
1. Обойти `virtualTableParameters`
2. Найти `inPredicate` с `subquery()` в каждом параметре
3. Построить `VirtualTableBlock`

#### FR-3.1.4 SelectField.inlineSubquery
При обработке `ExpressionFieldContext`:
1. Если `expression` содержит `bracketExpression` с `subquery()`
2. Создать `InlineSubquery` и записать в `SelectField.inlineSubquery`

#### FR-3.1.5 JoinPart.conditionSubqueries
При обработке `JoinPartContext.condition`:
1. Обойти `LogicalExpressionContext` рекурсивно
2. Найти `inPredicate` с `subquery()`
3. Создать `WhereSubquery` и записать в `JoinPart.conditionSubqueries`

#### FR-3.1.6 Точная замена текста
Замена подзапроса на псевдоним выполняется через `StringBuilder.replace()` по позициям ANTLR токенов, не через `String.replace()`.

```java
private String buildTextWithReplacements(ParserRuleContext ctx, List<SubqueryEntry> entries) {
    String fullText = textOf(ctx);
    StringBuilder sb = new StringBuilder(fullText);
    int offset = 0;
    int counter = 1;
    for (SubqueryEntry entry : entries) {
        String alias = "ВТ_Подзапрос_" + counter++;
        String subText = textOf(entry.subqueryCtx);
        int start = entry.subqueryCtx.getStart().getStartIndex() - ctx.getStart().getStartIndex();
        int end = entry.subqueryCtx.getStop().getStopIndex() - ctx.getStart().getStartIndex() + 1;
        sb.replace(start + offset, end + offset, alias);
        offset += alias.length() - subText.length();
        entry.generatedAlias = alias;
    }
    return sb.toString();
}
```

### 3.2 LINE_PARS — LineParsModelBuilder

#### FR-3.2.1 WhereBlock в LineParsNode
`LineParsNode.where` меняет тип: `String` → `WhereBlock` { `text`, `subqueryIds` }.

#### FR-3.2.2 Создание SUB-нод из WhereBlock.subqueries
```java
if (ast.getWhere() != null && ast.getWhere().getSubqueries() != null) {
    int counter = 1;
    List<Integer> whereSubIds = new ArrayList<>();
    for (WhereSubquery ws : ast.getWhere().getSubqueries()) {
        LineParsNode sub = new LineParsNode();
        sub.setId(idCounter++);
        sub.setName(parent.getName() + "_WHERE_" + counter++);
        sub.setType("sub_query");
        copyQueryFields(sub, ws.getQuery());
        sub.setUpqueryId(parent.getId());
        nodes.add(sub);
        whereSubIds.add(sub.getId());
        parent.getSubqueryIds().add(sub.getId());
    }
    // Замена временных имен на имена SUB-нод
    String whereText = parent.getWhere().getText();
    for (int i = 0; i < ast.getWhere().getSubqueries().size(); i++) {
        String tempName = ast.getWhere().getSubqueries().get(i).getName();
        String subName = parent.getName() + "_WHERE_" + (i + 1);
        whereText = whereText.replace(tempName, subName);
    }
    parent.getWhere().setText(whereText);
    parent.getWhere().setSubqueryIds(whereSubIds);
}
```

#### FR-3.2.3 Аналогично для HavingBlock, VirtualTableBlock, SelectField, JoinPart
Создание SUB-нод с контекстными именами: `_HAVING_<N>`, `_VT_<N>`, `_SELECT_<N>`, `_JOIN_<N>`.

### 3.3 FULL_PARS — FullParsModelBuilder

#### FR-3.3.1 WhereBlock в FullParsNode
`FullParsNode.where` меняет тип: `String` → `WhereBlock` { `text`, `subqueryIds`, `fields` }.

#### FR-3.3.2 Перенос where_fields внутрь WhereBlock
Поле `FullParsNode.whereFields` **удаляется**. Данные строятся внутри `WhereBlock.fields`:

```java
WhereBlock whereBlock = new WhereBlock();
whereBlock.setText(lpNode.getWhere().getText());
whereBlock.setSubqueryIds(lpNode.getWhere().getSubqueryIds());
whereBlock.setFields(extractConditionFields(lpNode.getId(), lpNode.getWhere().getText()));
fpNode.setWhere(whereBlock);
```

#### FR-3.3.3 child_fields через subqueryIds
При построении `fields` внутри `WhereBlock`:
- Если текст содержит ссылку на where-SUB-ноду (`_WHERE_<N>`), `child_fields` должен содержать `node_id` этой SUB-ноды
- Поиск SUB-ноды: по `subqueryIds` + `nodeByName`

### 3.4 Иерархия — LineParsHierarchyBuilder

#### FR-3.4.1 where_subquery в иерархии
SUB-ноды из `WhereBlock` добавляются в `tableHierarchy` родителя с `typeHierarchy="where_subquery"`.

### 3.5 Восстановление SQL — QueryReconstructor

#### FR-3.5.1 Inline-вставка where-подзапросов
`SqlGenerator` при генерации `WHERE`:
1. Берёт `where.text`
2. Для каждого `subqueryId` в `where.subqueryIds` находит SUB-ноду
3. Заменяет имя SUB-ноды на `(SQL подзапроса)`
4. Inline-ит в условие WHERE

---

## 4. Структуры JSON по уровням

### 4.1 SDBL_PARS

```json
{
  "type": "select",
  "select": [
    {
      "text": "(ВТ_Подзапрос_1) КАК Поле",
      "alias": "Поле",
      "inlineSubquery": {
        "name": "ВТ_Подзапрос_1",
        "query": { "type": "select", ... }
      }
    }
  ],
  "from": [...],
  "where": {
    "text": "Контрагенты.Ссылка В (ВТ_Подзапрос_2)",
    "subqueries": [
      {
        "name": "ВТ_Подзапрос_2",
        "query": { "type": "select", ... }
      }
    ]
  },
  "having": {
    "text": "СУММА(Поле) > (ВТ_Подзапрос_3)",
    "subqueries": [...]
  }
}
```

### 4.2 LINE_PARS

```json
{
  "id": 5,
  "name": "Результат_3",
  "type": "result",
  "where": {
    "text": "Контрагенты.Ссылка В (Результат_3_WHERE_1)",
    "subqueryIds": [7]
  },
  "having": {
    "text": "СУММА(Поле) > (Результат_3_HAVING_1)",
    "subqueryIds": [8]
  },
  "subqueryIds": [6, 7, 8]
}
```

### 4.3 FULL_PARS

```json
{
  "id": 5,
  "name": "Результат_3",
  "type": "result",
  "where": {
    "text": "Контрагенты.Ссылка В (Результат_3_WHERE_1)",
    "subqueryIds": [7],
    "fields": [
      {
        "text": "Контрагенты.Ссылка",
        "child_fields": [
          {
            "alias": "Ссылка",
            "nodeName": "Контрагенты",
            "source": "Справочник.Контрагенты"
          }
        ]
      }
    ]
  },
  "having": {
    "text": "СУММА(Поле) > (Результат_3_HAVING_1)",
    "subqueryIds": [8],
    "fields": [...]
  }
}
```

---

## 5. Алгоритм замены текста

```java
private String replaceSubqueriesByPosition(ParserRuleContext parentCtx,
                                            List<SubqueryContext> subqueries) {
    String fullText = textOf(parentCtx);
    StringBuilder sb = new StringBuilder(fullText);
    int offset = 0;
    int counter = 1;

    for (SubqueryContext sq : subqueries) {
        String alias = "ВТ_Подзапрос_" + counter++;
        String subText = textOf(sq);
        int start = sq.getStart().getStartIndex() - parentCtx.getStart().getStartIndex();
        int end = sq.getStop().getStopIndex() - parentCtx.getStart().getStartIndex() + 1;

        sb.replace(start + offset, end + offset, alias);
        offset += alias.length() - subText.length();
    }

    return sb.toString();
}
```

---

## 6. Тестирование

### FR-6.1 Тест WhereBlock в SDBL_PARS
```java
@Test
void testWhereBlock() {
    QueryAst ast = parse("example_4.sql");
    assertThat(ast.getWhere()).isInstanceOf(WhereBlock.class);
    assertThat(ast.getWhere().getText()).contains("ВТ_Подзапрос_1");
    assertThat(ast.getWhere().getSubqueries()).hasSize(1);
}
```

### FR-6.2 Тест WhereBlock в LINE_PARS
```java
@Test
void testLineParsWhereBlock() {
    LineParsNode node = findNode("Результат_3");
    assertThat(node.getWhere()).isInstanceOf(WhereBlock.class);
    assertThat(node.getWhere().getSubqueryIds()).isNotEmpty();
}
```

### FR-6.3 Тест WhereBlock.fields в FULL_PARS
```java
@Test
void testFullParsWhereFields() {
    FullParsNode node = findNode("Результат_3");
    assertThat(node.getWhere().getFields()).isNotEmpty();
    assertThat(node.getWhere().getFields().get(0).getChildFields()).isNotEmpty();
}
```

---

## 7. История изменений

| Версия | Дата | Автор | Изменения |
|--------|------|-------|-----------|
| 1.0 | 2026-06-05 | Kimi AI Agent | Первоначальная версия |

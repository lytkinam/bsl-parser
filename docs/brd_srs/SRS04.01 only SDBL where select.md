# SRS04.01: Спецификация реализации — парсинг подзапросов в WHERE

## 1. Общее описание

Реализация BRD04.01: извлечение подзапросов из блока `WHERE` SDBL-запроса на этапе ANTLR4-визита.

## 2. Изменяемые компоненты

### 2.1 Модель — новые классы

#### `WhereBlock.java`
```java
package com.github._1c_syntax.bsl.parser.sdql.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class WhereBlock {
  private String text;
  private List<WhereSubquery> subqueries = new ArrayList<>();
}
```

#### `WhereSubquery.java`
```java
package com.github._1c_syntax.bsl.parser.sdql.model;

import lombok.Data;

@Data
public class WhereSubquery {
  private String name;
  private QueryAst query;
}
```

### 2.2 Модель — изменения

#### `QueryAst.java`
Поле `where` изменяет тип:
```java
// Было:
private String where;

// Стало:
private WhereBlock where;
```

Для обратной совместимости сериализации используется кастомный сериализатор/десериализатор либо полиморфное поле. **Рекомендуемый подход**: всегда `WhereBlock`, Jackson скрывает `null` через `@JsonInclude(NON_EMPTY)`.

### 2.3 Визитор — `QueryPackageVisitor.java`

#### Новый метод: `visitWhere(SDBLParser.LogicalExpressionContext ctx)`

```java
private WhereBlock visitWhere(SDBLParser.LogicalExpressionContext ctx) {
    WhereBlock block = new WhereBlock();
    List<WhereSubquery> subqueries = new ArrayList<>();
    int counter = 1;

    // Обход дерева поиска подзапросов
    List<SubqueryEntry> entries = findSubqueries(ctx);

    String whereText = textOf(ctx);
    for (SubqueryEntry entry : entries) {
        String alias = "ВТ_Подзапрос_" + counter++;
        QueryAst subAst = visitSubquery(entry.subqueryCtx);

        WhereSubquery ws = new WhereSubquery();
        ws.setName(alias);
        ws.setQuery(subAst);
        subqueries.add(ws);

        // Замена текста подзапроса на псевдоним
        String subText = textOf(entry.subqueryCtx);
        whereText = whereText.replace(subText, alias);
    }

    block.setText(whereText);
    block.setSubqueries(subqueries);
    return block;
}
```

#### Вспомогательный класс `SubqueryEntry`
```java
private static class SubqueryEntry {
    SDBLParser.SubqueryContext subqueryCtx;
    // позиция в тексте для точной замены
}
```

#### Метод поиска подзапросов
```java
private List<SubqueryEntry> findSubqueries(SDBLParser.LogicalExpressionContext ctx) {
    List<SubqueryEntry> result = new ArrayList<>();
    findSubqueriesRecursive(ctx, result);
    return result;
}

private void findSubqueriesRecursive(ParserRuleContext ctx, List<SubqueryEntry> result) {
    if (ctx instanceof SDBLParser.InPredicateContext) {
        SDBLParser.InPredicateContext inCtx = (SDBLParser.InPredicateContext) ctx;
        if (inCtx.subquery() != null) {
            SubqueryEntry entry = new SubqueryEntry();
            entry.subqueryCtx = inCtx.subquery();
            result.add(entry);
            return; // не уходим глубже в subquery
        }
    }
    for (int i = 0; i < ctx.getChildCount(); i++) {
        ParseTree child = ctx.getChild(i);
        if (child instanceof ParserRuleContext) {
            findSubqueriesRecursive((ParserRuleContext) child, result);
        }
    }
}
```

#### Изменение в `visitQuery`
```java
// Было:
if (ctx.where != null) ast.setWhere(textOf(ctx.where));

// Стало:
if (ctx.where != null) ast.setWhere(visitWhere(ctx.where));
```

### 2.4 LINE_PARS — `LineParsModelBuilder.java`

#### Изменения в `processAst`

После обработки `from` и `unions` добавить обработку `where.subqueries`:

```java
// Process subqueries in WHERE
if (ast.getWhere() != null && ast.getWhere().getSubqueries() != null) {
    int whereSubCounter = 1;
    for (WhereSubquery ws : ast.getWhere().getSubqueries()) {
        LineParsNode sub = new LineParsNode();
        sub.setId(idCounter++);
        sub.setSdblId(parent.getSdblId());
        sub.setName(parent.getName() + "_WHERE_SUB_" + whereSubCounter++);
        sub.setType("sub_query");
        copyQueryFields(sub, ws.getQuery());
        sub.setUpqueryId(parent.getId());
        nodes.add(sub);
        parent.getSubqueryIds().add(sub.getId());

        // Replace subquery name in parent's where.text
        String whereText = parent.getWhere().getText();
        whereText = whereText.replace(ws.getName(), sub.getName());
        parent.getWhere().setText(whereText);
    }
}
```

**Важно**: `LineParsNode.where` остаётся `String` (текст), поэтому перед копированием полей нужно преобразовать `WhereBlock` в строку:
```java
// В copyQueryFields или отдельно:
if (ast.getWhere() != null) {
    node.setWhere(ast.getWhere().getText());
}
```

### 2.5 Hierarchy — `LineParsHierarchyBuilder.java`

#### Изменения в `buildNode`

После обработки `from` и `union` добавить обработку подзапросов из `where`:

```java
// 3. Subqueries from WHERE
if (node.getWhere() != null) {
    // where.text может содержать ссылки на WHERE_SUB-узлы
    // Найдём их через subqueryIds
    for (int subId : node.getSubqueryIds()) {
        LineParsNode sub = nodeById.get(subId);
        if (sub != null && sub.getName() != null && sub.getName().contains("_WHERE_SUB_")) {
            HierarchyNode childNode = new HierarchyNode();
            childNode.setId(sub.getId());
            childNode.setName(sub.getName());
            childNode.setTypeHierarchy("where_subquery");
            childNode.setSource(sub.getName());
            result.getTableHierarchy().add(childNode);
        }
    }
}
```

### 2.6 FULL_PARS — `FullParsModelBuilder.java`

Изменений **не требуется** — `FullParsModelBuilder` работает с `LineParsNode`, где `where` уже строка. `where_fields` строятся из текста `where` регулярками, как и раньше.

### 2.7 Восстановление SQL — `SqlGenerator.java`

#### Изменения в `generateSingleQuery`

При генерации `WHERE` нужно inline-ить подзапросы из `where.text`:

```java
// WHERE
if (!node.getWhereConditions().isEmpty()) {
    sb.append("ГДЕ\n    ");
    List<String> conditions = new ArrayList<>();
    for (String cond : node.getWhereConditions()) {
        // Заменить имена WHERE_SUB-узлов на inline SQL
        String expanded = expandWhereSubqueries(cond, node.getInlineSubqueries());
        conditions.add(expanded);
    }
    sb.append(String.join("\n    И ", conditions));
    sb.append("\n");
}
```

Метод `expandWhereSubqueries` ищет `Имя_Родителя_WHERE_SUB_N` в тексте и заменяет на `(\n<SQL>\n)`.

## 3. Алгоритм замены текста в WHERE

### 3.1 Проблема

Простой `String.replace()` может повредить текст, если псевдоним встречается как часть идентификатора.

### 3.2 Решение

Использовать замену по границам слов (word boundaries) либо по позициям из ANTLR-контекста.

**Рекомендуемый подход** (через позиции):
1. При обходе `InPredicateContext` записывать `startIndex` и `endIndex` подзапроса.
2. Строить `whereText` через `StringBuilder`, удаляя фрагменты по индексам.

```java
private String buildWhereTextWithReplacements(LogicalExpressionContext ctx,
                                               List<SubqueryEntry> entries) {
    String fullText = textOf(ctx);
    StringBuilder sb = new StringBuilder(fullText);
    int offset = 0;
    int counter = 1;

    for (SubqueryEntry entry : entries) {
        String alias = "ВТ_Подзапрос_" + counter++;
        String subText = textOf(entry.subqueryCtx);
        int start = entry.subqueryCtx.getStart().getStartIndex()
                    - ctx.getStart().getStartIndex();
        int end = entry.subqueryCtx.getStop().getStopIndex()
                  - ctx.getStart().getStartIndex() + 1;

        sb.replace(start + offset, end + offset, alias);
        offset += alias.length() - subText.length();
        entry.generatedAlias = alias;
    }

    return sb.toString();
}
```

## 4. Тестирование

### 4.1 Минимальный тест

```java
@Test
void testWhereSubquery() throws Exception {
    File output = tempDir.resolve("out").toFile();
    SdqlCli.main(new String[]{"examples/example_4.sql", output.getAbsolutePath()});

    QueryModel model = ModelJsonMapper.read(
        output.toPath().resolve("sdbl_parse_model_example_4.json"));

    // Find node "Результат_3"
    QueryNode resultNode = model.getNodes().stream()
        .filter(n -> "Результат_3".equals(n.getName()))
        .findFirst()
        .orElseThrow();

    assertThat(resultNode.getQuery().getWhere()).isNotNull();
    assertThat(resultNode.getQuery().getWhere().getText())
        .contains("ВТ_Подзапрос_1");
    assertThat(resultNode.getQuery().getWhere().getSubqueries())
        .hasSize(1);
    assertThat(resultNode.getQuery().getWhere().getSubqueries().get(0).getQuery())
        .isNotNull();
}
```

### 4.2 LINE_PARS тест

```java
@Test
void testLineParsWhereSubquery() throws Exception {
    // ... after CLI run
    LineParsModel linePars = MAPPER.readValue(
        tempDir.resolve("LINE_PARS/LINE_PARS_model_example_4.json").toFile(),
        LineParsModel.class);

    LineParsNode whereSub = linePars.getNodes().stream()
        .filter(n -> n.getName().contains("_WHERE_SUB_"))
        .findFirst()
        .orElseThrow();

    assertThat(whereSub.getType()).isEqualTo("sub_query");
    assertThat(whereSub.getUpqueryId()).isNotNull();
}
```

## 5. Порядок реализации

1. Создать `WhereBlock.java` и `WhereSubquery.java`.
2. Изменить `QueryAst.where` → `WhereBlock`.
3. Обновить `QueryPackageVisitor` — метод `visitWhere`.
4. Обновить `LineParsModelBuilder` — обработка `where.subqueries`.
5. Обновить `LineParsHierarchyBuilder` — добавление `where_subquery` в иерархию.
6. Обновить `SqlGenerator` — inline WHERE-подзапросов при восстановлении.
7. Сгенерировать артефакты для `middle_example.sql`.
8. Обновить тесты.
9. Коммит примера, затем кода.

## 6. Зависимости

- BRD04.01 — бизнес-требование.
- ANTLR4 runtime 4.13.1 (существующий).
- Jackson 2.17.2 (существующий).

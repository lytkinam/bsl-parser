# Особенности работы парсера

## Конвейер обработки (SdqlCli)

```
1. SdqlQueryPackageAnalyzer.analyze()
   → sdbl_parse_nodes_<basename>.json  (полные тексты)
   → sdbl_parse_model_<basename>.json   (AST без текстов)

2. LineParsModelBuilder.build()
   → LINE_PARS/LINE_PARS_model_<basename>.json

3. LineParsHierarchyBuilder.build()
   → LINE_PARS/LINE_PARS_hierarchy_<basename>.json

4. LineParsFieldLineageBuilder.build()
   → field_lineage/<basename>/<nodeId>_<nodeName>/FLS_*.json

5. FullParsModelBuilder.build()
   → FULL_PARS/FULL_PARS_model_<basename>.json

6. FullFieldLineageBuilder.build()
   → full_field_lineage/<basename>/<nodeId>_<nodeName>/FFL_*.json

7. QueryReconstructor.build()
   → RESTORED_QUERIES/<basename>/<nodeId>_<nodeName>/<alias>.sql

8. QueryExtractor.build()
   → EXTRACTED_QUERIES/<basename>/<id>_<name>.sql
   → VERIFICATION/<basename>/verification_report.json

Markdown-отчёты (параллельно):
   → sdbl_parse_model_<basename>.md
   → LINE_PARS/LINE_PARS_model_<basename>.md
   → LINE_PARS/LINE_PARS_hierarchy_<basename>.md
   → field_lineage/<basename>/.../*.md
```

## Итерация 0: SDBL_PARS

- Разбивает SQL-пакет на отдельные запросы по `;`
- Для каждого запроса: id, тип (`temp_query`, `select`, `drop_query`), имя (ВТ), AST
- Рёбра (`QueryEdge`): когда запрос использует ВТ из другого запроса (`from.table` начинается с `ВТ_`)
- `QueryAst` содержит вложенные подзапросы (`DataSource.subquery` как `QueryAst`)
- `UnionPart` — части UNION с `unionType`

### Блоковая структура inline подзапросов (BRD04.02/SRS04.02)

В SDBL-модели inline подзапросы не выносятся в отдельные узлы, а хранятся внутри блоков:

| Блок | Поле | Тип | Описание |
|------|------|-----|----------|
| `WhereBlock` | `where` в `QueryAst` / `LineParsNode` / `FullParsNode` | `text`, `subqueries` (`List<WhereSubquery>`), `subqueryIds`, `fields` | WHERE с inline подзапросами |
| `HavingBlock` | `having` в `QueryAst` / `LineParsNode` / `FullParsNode` | `text`, `subqueries`, `subqueryIds`, `fields` | HAVING с inline подзапросами |
| `VirtualTableBlock` | `virtualTable` в `DataSource` | `text`, `subqueries`, `subqueryIds` | Параметры виртуальной таблицы с inline подзапросами |
| `WhereSubquery` | элемент `subqueries` | `name`, `query` (`QueryAst`) | Inline подзапрос с контекстным именем |

**Именование inline подзапросов в SDBL**: `ВТ_Подзапрос_<N>` (позиционная замена, глобальный счётчик `vtCounter`)

**SelectField с inline подзапросом**: `SelectField.inlineSubquery` (`WhereSubquery`) + `inlineSubqueryId` (после обработки в LINE_PARS)

**JoinPart с inline подзапросом**: `JoinPart.conditionSubqueries` (`List<WhereSubquery>`) + `conditionSubqueryIds` (после обработки в LINE_PARS)

## Итерация 1: LINE_PARS

**Цель**: развернуть подзапросы и UNION в отдельные узлы для плоского анализа.

### Правила именования

| Тип узла | Шаблон имени | Пример |
|----------|-------------|--------|
| Корневой temp_query | `ИмяВТ` | `ВТ_ТипыРезервов` |
| UNION часть 0 | `Имя_UNION_0` | `ВТ_ТипыРезервов_UNION_0` |
| UNION часть N | `Имя_UNION_N` | `ВТ_ТипыРезервов_UNION_1` |
| Подзапрос из FROM | `Имя_SUB_N` | `ВТ_ПенсионныеСчета_ПР_SUB_1` |
| Inline подзапрос WHERE | `Имя_WHERE_N` | `ВТ_Результат_1_WHERE_1` |
| Inline подзапрос HAVING | `Имя_HAVING_N` | `ВТ_Результат_1_HAVING_1` |
| Inline подзапрос VT | `Имя_VT_N` | `ВТ_Результат_1_VT_1` |
| Inline подзапрос SELECT | `Имя_SELECT_N` | `ВТ_Результат_1_SELECT_1` |
| Inline подзапрос JOIN | `Имя_JOIN_N` | `ВТ_Результат_1_JOIN_1` |

### Виртуальный родитель для UNION

- Если запрос содержит UNION, исходный узел становится **виртуальным**:
  - `select` заменяется на виртуальные `union_field` (только alias)
  - `from`, `where`, `groupBy` и т.д. очищаются
  - `unionNodesIds` содержит id всех частей UNION
- Оригинальный запрос копируется в `UNION_0`
- Все части UNION имеют `unionGroupId` → id виртуального родителя

### Подзапросы

- Подзапросы из `from[].subquery` создаются как отдельные узлы
- В родительском `from[].subquery` заменяется на **String** (имя узла подзапроса)
- Подзапрос имеет `upqueryId` → id родителя
- Родитель имеет `subqueryIds` → список id подзапросов

### Inline подзапросы в LINE_PARS

- Inline подзапросы из `WhereBlock.subqueries`, `HavingBlock.subqueries`, `VirtualTableBlock.subqueries`, `SelectField.inlineSubquery`, `JoinPart.conditionSubqueries` создаются как отдельные узлы типа `sub_query`
- В родительских блоках имена заменяются с `ВТ_Подзапрос_N` на `<parent>_<CONTEXT>_<N>`
- `subqueryIds` заполняется в родителе
- Блоки в LINE_PARS: `WhereBlock` (только `text` + `subqueryIds`), `HavingBlock` (только `text` + `subqueryIds`), `VirtualTableBlock` (только `text` + `subqueryIds`)

### Поля DataSource

| Поле | Тип | Описание |
|------|-----|----------|
| `table` | String | Обычная таблица (справочник, документ, регистр, ВТ) |
| `virtualTable` | `VirtualTableBlock` | Виртуальная таблица регистра (Остатки, Обороты, СрезПоследних) с inline подзапросами |
| `parameterTable` | String | Параметрическая таблица |
| `externalDataSource` | String | Внешний источник данных |
| `subquery` | Object | В SDBL — `QueryAst`, в LINE_PARS — `String` (имя узла) |
| `alias` | String | Псевдоним источника |
| `joins` | List<JoinPart> | JOIN-соединения |

### Определение "виртуальной таблицы" (по правилам пользователя)

- Если `table` содержит **точку** (`РегистрСведений.уп_СостоянияПС`) → обычная таблица
- Если `table` **не содержит точки** (`ВТ_ПенсионныеСчета_ПР`) → temp-table / виртуальная таблица
- Для таких таблиц `id` разрешается через поиск узла LINE_PARS модели по полю `name`

## Особенности десериализации

- `DataSource.subquery` сериализуется как String в LINE_PARS, но как Object в SDBL
- Для корректной чтения LINE_PARS модели используется `SubqueryDeserializer`:
  - JSON-string → `String`
  - JSON-object → `QueryAst`

## Типы узлов

| Тип | Описание |
|-----|----------|
| `temp_query` | Запрос с `ПОМЕСТИТЬ` (создаёт ВТ) |
| `select` | Обычный SELECT |
| `drop_query` | `УНИЧТОЖИТЬ` |
| `union_query` | Часть UNION |
| `sub_query` | Подзапрос из FROM или inline подзапрос |
| `result` | Результат (fallback) |

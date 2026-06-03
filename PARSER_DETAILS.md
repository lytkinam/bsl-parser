# Особенности работы парсера

## Конвейер обработки (SdqlCli)

```
1. SdqlQueryPackageAnalyzer.analyze()
   → sdbl_parse_nodes_<basename>.json  (полные тексты)
   → sdbl_parse_model_<basename>.json   (AST без текстов)

2. QueryTextExporter.export()
   → query_texts_<basename>/node_N.sql + node_N.md

3. FieldsNodeBuilder.build()
   → fields_node_<basename>/fields_node.json + table_alias_map.json

4. FieldLineageAnalyzer.analyze()
   → lineage_<basename>/field_lineage.json

5. LineParsModelBuilder.build()
   → LINE_PARS/LINE_PARS_model_<basename>.json

6. LineParsHierarchyBuilder.build()
   → LINE_PARS/LINE_PARS_hierarchy_<basename>.json
```

## Итерация 0: SDBL_PARS

- Разбивает SQL-пакет на отдельные запросы по `;`
- Для каждого запроса: id, тип (`temp_query`, `select`, `drop_query`), имя (ВТ), AST
- Рёбра (`QueryEdge`): когда запрос использует ВТ из другого запроса (`from.table` начинается с `ВТ_`)
- `QueryAst` содержит вложенные подзапросы (`DataSource.subquery` как `QueryAst`)
- `UnionPart` — части UNION с `unionType`

## Итерация 1: LINE_PARS

**Цель**: развернуть подзапросы и UNION в отдельные узлы для плоского анализа.

### Правила именования

| Тип узла | Шаблон имени | Пример |
|----------|-------------|--------|
| Корневой temp_query | `ИмяВТ` | `ВТ_ТипыРезервов` |
| UNION часть 0 | `Имя_UNION_0` | `ВТ_ТипыРезервов_UNION_0` |
| UNION часть N | `Имя_UNION_N` | `ВТ_ТипыРезервов_UNION_1` |
| Подзапрос | `Имя_SUB_N` | `ВТ_ПенсионныеСчета_ПР_SUB_1` |

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

### Поля DataSource

| Поле | Тип | Описание |
|------|-----|----------|
| `table` | String | Обычная таблица (справочник, документ, регистр, ВТ) |
| `virtualTable` | String | Виртуальная таблица регистра (Остатки, Обороты, СрезПоследних) |
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
| `sub_query` | Подзапрос из FROM |
| `result` | Результат (fallback) |

# Структура проекта

## Корневые директории

```
bsl-parser/
├── src/main/java/com/github/_1c_syntax/bsl/parser/sdql/   # Основной код SDQL
├── src/test/java/com/github/_1c_syntax/bsl/parser/sdql/   # Тесты
├── examples/                                               # SQL-примеры и артефакты
│   ├── SDBL_PARS/                                         # Итерация 0 (SDBL модель)
│   ├── LINE_PARS/                                         # Итерация 1 (LINE_PARS модель)
│   ├── FULL_PARS/                                         # Итерация 2 (FULL_PARS модель)
│   ├── field_lineage/                                     # LINE_PARS field lineage
│   ├── full_field_lineage/                                # FULL_PARS field lineage
│   ├── RESTORED_QUERIES/                                  # Восстановленные SQL-запросы
│   ├── EXTRACTED_QUERIES/                                 # Извлечённые SQL-запросы
│   ├── VERIFICATION/                                      # Отчёты верификации
│   ├── *.sql                                              # Исходные SQL-файлы
│   └── sdbl/                                              # Дополнительные SQL-файлы
├── build.gradle.kts                                       # Сборка Gradle (Kotlin DSL)
└── gradle/wrapper/                                        # Gradle Wrapper
```

## Пакеты и ключевые классы

### `sdql/` — пакет анализа SQL-запросов

| Класс/Пакет | Назначение |
|-------------|-----------|
| `SdqlCli.java` | Точка входа CLI. Оркестрирует весь конвейер: анализ → LINE_PARS → FULL_PARS → field_lineage → restored_queries → extracted_queries + verification |
| `SdqlQueryPackageAnalyzer.java` | Основной анализатор. Парсит SQL-пакет через ANTLR4, строит `QueryModel` и `QueryNode` |
| `NodeSplitter.java` | Разбивает SQL-пакет на отдельные запросы по `;` |
| `visitor/QueryPackageVisitor.java` | ANTLR4-визитор, строит AST (`QueryAst`) для каждого запроса |

### `sdql/model/` — модели данных SDBL

| Класс | Назначение |
|-------|-----------|
| `QueryModel.java` | Корневая модель SDBL (список `QueryNode` + `QueryEdge`) |
| `QueryNode.java` | Узел SDBL-модели: `id`, `type`, `name`, `query` (`QueryAst`) |
| `QueryAst.java` | AST запроса: `select`, `from`, `where` (`WhereBlock`), `groupBy`, `having` (`HavingBlock`), `orderBy`, `unions`, etc. |
| `DataSource.java` | Источник данных в FROM: `table`, `virtualTable` (`VirtualTableBlock`), `parameterTable`, `externalDataSource`, `subquery`, `alias`, `joins` |
| `JoinPart.java` | Часть JOIN: `joinType`, `source` (`DataSource`), `condition`, `conditionSubqueries`, `conditionSubqueryIds` |
| `SelectField.java` | Поле SELECT: `text`, `alias`, `inlineSubquery` (`WhereSubquery`), `inlineSubqueryId` |
| `QueryEdge.java` | Ребро между узлами (использование ВТ одним запросом из другого) |
| `WhereBlock.java` | Блок WHERE: `text`, `subqueries` (`List<WhereSubquery>`), `subqueryIds`, `fields` |
| `HavingBlock.java` | Блок HAVING: `text`, `subqueries`, `subqueryIds`, `fields` |
| `VirtualTableBlock.java` | Блок виртуальной таблицы: `text`, `subqueries`, `subqueryIds` |
| `WhereSubquery.java` | Inline подзапрос в where/having/virtualTable: `name`, `query` (`QueryAst`) |
| `UnionPart.java` | Часть UNION: `unionType`, `query` |
| `TotalBy.java` | Итоги: `fields`, `groups` |
| `SubqueryDeserializer.java` | Полиморфный десериализатор `DataSource.subquery`: JSON-string → `String`, JSON-object → `QueryAst` |

### `sdql/line_pars/` — LINE_PARS модель (итерация 1)

| Класс | Назначение |
|-------|-----------|
| `LineParsModel.java` | Корневая модель LINE_PARS: список `LineParsNode` + `edges` + `dropQueries` |
| `LineParsNode.java` | Узел LINE_PARS: все поля `QueryAst` плюс `id`, `sdblId`, `name`, `type`, `upqueryId`, `subqueryIds`, `unionNodesIds`, `unionGroupId`, `unionType` |
| `LineParsEdge.java` | Ребро LINE_PARS (зарезервировано) |
| `LineParsModelBuilder.java` | Строит LINE_PARS модель из SDBL: разворачивает UNION, подзапросы FROM, и inline подзапросы (WHERE, HAVING, VT, SELECT, JOIN) в отдельные узлы |
| `HierarchyNode.java` | Модель выхода иерархии: `id`, `name`, `source`, `type_hierarchy`, `table_hierarchy` |
| `LineParsHierarchyBuilder.java` | Строит JSON-иерархию из LINE_PARS модели |
| `LineParsFieldExtractor.java` | Извлекает lineage поля по `nodeId` + `alias` (рекурсивный обход через `HierarchyNode`) |
| `LineParsFieldLineageBuilder.java` | Строит `field_lineage/` артефакты для target nodes (result или последний temp_query) |
| `FieldLineageNode.java` | Узел lineage: `id`, `name`, `alias`, `text`, `source`, `childName`, `child_fields` |

### `sdql/full_pars/` — FULL_PARS модель (итерация 2)

| Класс | Назначение |
|-------|-----------|
| `FullParsModel.java` | Корневая модель FULL_PARS: список `FullParsNode` |
| `FullParsNode.java` | Узел FULL_PARS: поля `LineParsNode` + `whereFields`, `groupByFields`, `havingFields`, `joinFields`, `select` с `field_id` и `child_fields` |
| `FullParsSelectField.java` | Поле SELECT FULL_PARS: `field_id`, `alias`, `text`, `child_fields` |
| `FullParsChildField.java` | Дочернее поле: `field_id`, `alias`, `nodeId`, `nodeName`, `source` |
| `FullParsConditionField.java` | Поле условия: `text`, `child_fields` |
| `FullParsJoinCondition.java` | Условие JOIN: `join_type`, `source`, `condition`, `condition_fields` |
| `FullParsModelBuilder.java` | Строит FULL_PARS модель из LINE_PARS: добавляет `field_id`, `child_fields`, `where_fields`, `group_by_fields`, `having_fields`, `join_fields` |
| `FullFieldLineageBuilder.java` | Строит `full_field_lineage/` артефакты для всех полей target nodes |

### `sdql/query_reconstruction/` — Восстановление SQL-запросов

| Класс | Назначение |
|-------|-----------|
| `QueryReconstructor.java` | Восстанавливает SQL из FFL (full_field_lineage) + FULL_PARS, пишет в `RESTORED_QUERIES/` |
| `QueryNodeBuilder.java` | Строит `RestoredQueryNode` из `FullParsNode` (FFL) |
| `SqlGenerator.java` | Генерирует SQL-текст из `RestoredQueryNode` |
| `RestoredQueryNode.java` | Модель восстановленного запроса: select, from, joins, where, groupBy, having, orderBy, unionParts, inlineSubqueries |
| `RestoredJoin.java` | Модель JOIN: `joinType`, `sourceTable`, `alias`, `condition` |
| `TopologicalSorter.java` | Топологическая сортировка узлов (зависимости first, leaves → root) |

### `sdql/query_extraction/` — Извлечение и верификация запросов

| Класс | Назначение |
|-------|-----------|
| `QueryExtractor.java` | Извлекает SQL из FULL_PARS, верифицирует против primary texts, пишет в `EXTRACTED_QUERIES/` и `VERIFICATION/` |
| `QueryBuilder.java` | Строит `RestoredQueryNode` из `FullParsNode` (FULL_PARS) для извлечения |
| `QueryVerifier.java` | Сравнивает extracted SQL с primary text (SHA-256 нормализованных текстов) |
| `TextNormalizer.java` | Нормализует SQL для сравнения (удаляет комментарии, пробелы, upper case) |
| `NodeVerificationResult.java` | Результат верификации: `status` (matched/mismatched/skipped), хеши, diff |
| `VerificationReport.java` | Отчёт верификации: `baseName`, `totalNodes`, `matched`, `mismatched`, список результатов |

### `sdql/md/` — Генерация Markdown-отчётов

| Класс | Назначение |
|-------|-----------|
| `SdqlModelMdBuilder.java` | Генерирует `sdbl_parse_model_<basename>.md` из SDBL модели |
| `LineParsModelMdBuilder.java` | Генерирует `LINE_PARS_model_<basename>.md` из LINE_PARS модели |
| `HierarchyMdBuilder.java` | Генерирует `LINE_PARS_hierarchy_<basename>.md` из иерархии |
| `LineParsFieldLineageMdBuilder.java` | Генерирует `.md` для каждого `FLS_*.json` в `field_lineage/` |

### `sdql/io/` — сериализация

| Класс | Назначение |
|-------|-----------|
| `ModelJsonMapper.java` | ObjectMapper для `QueryModel` с `INDENT_OUTPUT` |
| `NodesJsonMapper.java` | Сериализация `List<QueryNode>` (полные тексты) |

### Устаревшие/пустые пакеты

| Пакет | Статус |
|-------|--------|
| `sdql/export/` | Пустой (QueryTextExporter удалён) |
| `sdql/fields/` | Пустой (FieldsNodeBuilder удалён) |
| `sdql/lineage/` | Пустой (FieldLineageAnalyzer удалён, lineage теперь в `line_pars/` и `full_pars/`) |

# Структура проекта

## Корневые директории

```
bsl-parser/
├── src/main/java/com/github/_1c_syntax/bsl/parser/sdql/   # Основной код SDQL
├── src/test/java/com/github/_1c_syntax/bsl/parser/sdql/   # Тесты
├── examples/                                               # SQL-примеры и артефакты
│   ├── SDBL_PARS/                                         # Итерация 0 (SDBL модель)
│   ├── LINE_PARS/                                         # Итерация 1+ (LINE_PARS модель)
│   ├── *.sql                                              # Исходные SQL-файлы
│   └── sdbl/                                              # Дополнительные SQL-файлы
├── build.gradle.kts                                       # Сборка Gradle (Kotlin DSL)
└── gradle/wrapper/                                        # Gradle Wrapper
```

## Пакеты и ключевые классы

### `sdql/` — пакет анализа SQL-запросов

| Класс/Пакет | Назначение |
|-------------|-----------|
| `SdqlCli.java` | Точка входа CLI. Оркестрирует весь конвейер: анализ → экспорт текстов → fields_node → lineage → LINE_PARS модель → LINE_PARS иерархия |
| `SdqlQueryPackageAnalyzer.java` | Основной анализатор. Парсит SQL-пакет через ANTLR4, строит `QueryModel` и `QueryNode` |
| `NodeSplitter.java` | Разбивает SQL-пакет на отдельные запросы по `;` |
| `visitor/QueryPackageVisitor.java` | ANTLR4-визитор, строит AST (`QueryAst`) для каждого запроса |

### `sdql/model/` — модели данных

| Класс | Назначение |
|-------|-----------|
| `QueryModel.java` | Корневая модель SDBL (список `QueryNode` + `QueryEdge`) |
| `QueryNode.java` | Узел SDBL-модели: `id`, `type`, `name`, `query` (`QueryAst`) |
| `QueryAst.java` | AST запроса: `select`, `from`, `where`, `groupBy`, `having`, `orderBy`, `unions`, etc. |
| `DataSource.java` | Источник данных в FROM: `table`, `virtualTable`, `parameterTable`, `externalDataSource`, `subquery`, `alias`, `joins` |
| `JoinPart.java` | Часть JOIN: `joinType`, `source` (`DataSource`), `condition` |
| `SelectField.java` | Поле SELECT: `text`, `alias` |
| `QueryEdge.java` | Ребро между узлами (использование ВТ одним запросом из другого) |

### `sdql/line_pars/` — LINE_PARS модель (итерация 1+)

| Класс | Назначение |
|-------|-----------|
| `LineParsModel.java` | Корневая модель LINE_PARS: список `LineParsNode` + `edges` + `dropQueries` |
| `LineParsNode.java` | Узел LINE_PARS: все поля `QueryAst` плюс `id`, `sdblId`, `name`, `type`, `upqueryId`, `subqueryIds`, `unionNodesIds`, `unionGroupId`, `unionType` |
| `LineParsEdge.java` | Ребро LINE_PARS (зарезервировано) |
| `LineParsModelBuilder.java` | Строит LINE_PARS модель из SDBL: разворачивает UNION и подзапросы в отдельные узлы |
| `HierarchyNode.java` | Модель выхода иерархии: `id`, `name`, `source`, `type_hierarchy`, `table_hierarchy` |
| `LineParsHierarchyBuilder.java` | Строит JSON-иерархию из LINE_PARS модели |

### `sdql/io/` — сериализация

| Класс | Назначение |
|-------|-----------|
| `ModelJsonMapper.java` | ObjectMapper для `QueryModel` с `INDENT_OUTPUT` |
| `NodesJsonMapper.java` | Сериализация `List<QueryNode>` (полные тексты) |

### `sdql/export/` — экспорт текстов

| Класс | Назначение |
|-------|-----------|
| `QueryTextExporter.java` | Экспортирует тексты запросов в `query_texts_<basename>/node_N.sql` |

### `sdql/fields/` — анализ полей

| Класс | Назначение |
|-------|-----------|
| `FieldsNodeBuilder.java` | Строит `fields_node.json` и `table_alias_map.json` |
| `FieldRecord.java`, `FieldRef.java`, `TableAlias.java` | Модели для field analysis |

### `sdql/lineage/` — lineage анализ

| Класс | Назначение |
|-------|-----------|
| `FieldLineageAnalyzer.java` | Строит `field_lineage.json` |

### `sdql/model/` — десериализация

| Класс | Назначение |
|-------|-----------|
| `SubqueryDeserializer.java` | Полиморфный десериализатор `DataSource.subquery`: JSON-string → `String`, JSON-object → `QueryAst` |

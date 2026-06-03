# Особенности хранения примеров

## Директории

```
examples/
├── example.sql                    # Простой пример (5 узлов)
├── example_258.sql                # Сложный пример (>100 узлов)
├── middle_example.sql             # **Эталонный пример для проверки** (26 узлов)
├── sdbl/
│   └── union_subquery_example.sql # Пример с UNION + подзапросы
├── SDBL_PARS/                     # Артефакты итерации 0
│   ├── sdbl_parse_model_<basename>.json
│   ├── sdbl_parse_nodes_<basename>.json
│   ├── query_texts_<basename>/
│   │   ├── node_0.sql ... node_N.sql
│   │   ├── node_0.md  ... node_N.md
│   │   ├── normalized_queries.sql
│   │   └── texts_index.json
│   ├── fields_node_<basename>/
│   │   ├── fields_node.json
│   │   └── table_alias_map.json
│   └── lineage_<basename>/
│       └── field_lineage.json
└── LINE_PARS/                     # Артефакты итерации 1+
    ├── LINE_PARS_model_<basename>.json
    └── LINE_PARS_hierarchy_<basename>.json
```

## Эталонный пример: `middle_example.sql`

- **26 узлов** в SDBL модели
- Содержит: temp_query, union, subquery, virtual tables, joins
- **После любого изменения кода** необходимо:
  1. Запустить CLI на `middle_example.sql`
  2. С `examples/SDBL_PARS/` как outputDir (абсолютный путь)
  3. Проверить сгенерированные файлы
  4. Закоммитить пример отдельно от кода

## Запуск CLI для генерации примера

```bash
cd /tmp/bsl-parser
./gradlew classes
java -cp "build/classes/java/main:build/resources/main:$(find ~/.gradle/caches -name 'antlr4-runtime-4.13.1.jar' -o -name 'jackson-databind-2.13.*.jar' -o -name 'jackson-core-*.jar' -o -name 'jackson-annotations-*.jar' -o -name 'lombok-*.jar' | tr '\n' ':')" \
  com.github._1c_syntax.bsl.parser.sdql.SdqlCli \
  examples/middle_example.sql \
  "$(pwd)/examples/SDBL_PARS"
```

**Важно**: использовать **абсолютный путь** для outputDir, иначе `Path.getParent()` вернёт `null` и LINE_PARS не создастся.

## Workflow добавления новой выжимки

1. **Разработать код** (новый Builder + модель + интеграция в SdqlCli)
2. **Сгенерировать пример** для `middle_example.sql`
3. **Проверить пример** пользователем
4. **Закоммитить пример** в гит (`git add examples/... && git commit`)
5. **Закоммитить код** в гит (`git add src/... && git commit`)
6. **Запушить** в develop

## Существующие примеры

| Базовое имя | SQL-файл | Описание |
|------------|----------|----------|
| `example` | `examples/example.sql` | Простой, 5 узлов |
| `example_258` | `examples/example_258.sql` | Сложный, >100 узлов |
| `union_subquery_example` | `examples/sdbl/union_subquery_example.sql` | UNION + подзапросы |
| `middle_example` | `examples/middle_example.sql` | **Эталонный**, 26 узлов |

## Файлы, которые НЕ коммитятся

- Сгенерированные артефакты для `example` и `example_258` (только `middle_example` и `union_subquery_example`)
- Временные директории (`out_test/`, `LINE_PARS/` в корне при запуске с относительным путём)

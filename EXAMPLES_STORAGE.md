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
│   └── sdbl_parse_model_<basename>.md
├── LINE_PARS/                     # Артефакты итерации 1
│   ├── LINE_PARS_model_<basename>.json
│   ├── LINE_PARS_hierarchy_<basename>.json
│   └── LINE_PARS_hierarchy_<basename>.md
├── FULL_PARS/                     # Артефакты итерации 2
│   └── FULL_PARS_model_<basename>.json
├── field_lineage/                 # LINE_PARS field lineage
│   └── <basename>/<nodeId>_<nodeName>/
│       ├── FLS_<field_id>_<alias>.json
│       └── FLS_<field_id>_<alias>.md
├── full_field_lineage/            # FULL_PARS field lineage
│   └── <basename>/<nodeId>_<nodeName>/
│       ├── FFL_<field_id>_<alias>.json
│       └── FFL_<field_id>_<alias>.md
├── RESTORED_QUERIES/              # Восстановленные SQL-запросы
│   └── <basename>/<nodeId>_<nodeName>/
│       └── <alias>.sql
├── EXTRACTED_QUERIES/             # Извлечённые SQL-запросы
│   └── <basename>/<id>_<name>.sql
└── VERIFICATION/                  # Отчёты верификации
    └── <basename>/verification_report.json
```

## Эталонный пример: `middle_example.sql`

- **26 узлов** в SDBL модели
- Содержит: temp_query, union, subquery, virtual tables, joins, inline subqueries
- **После любого изменения кода** необходимо:
  1. Запустить CLI на `middle_example.sql`
  2. С `examples/SDBL_PARS/` как outputDir (абсолютный путь)
  3. Проверить сгенерированные файлы
  4. Закоммитить пример отдельно от кода

## Запуск CLI для генерации примера

```bash
cd /tmp/bsl-parser
./gradlew classes

# ВАЖНО: использовать АБСОЛЮТНЫЙ путь для outputDir
java -cp "build/classes/java/main:build/resources/main:$(find ~/.gradle/caches -name 'antlr4-runtime-4.13.1.jar' -o -name 'jackson-databind-2.17.2.jar' -o -name 'jackson-core-2.17.2.jar' -o -name 'jackson-annotations-2.17.2.jar' -o -name 'lombok-*.jar' | tr '\n' ':')" \
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

- Сгенерированные артефакты для `example` (они генерируются только для локальных тестов)
- Временные директории (`out_test/`, `LINE_PARS/` в корне при запуске с относительным путём)

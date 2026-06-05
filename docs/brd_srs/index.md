# BSL Parser — BRD/SRS

## SDQL (SQL-анализатор для 1С)

### Уровень 1: Полный парсинг
- [BRD01: Полный парсинг](BRD01%20full_pars.md)
- [SRS01: full_pars + full_field_lineage](SRS01%20full_pars.md)

### Уровень 2: Восстановление SQL-запросов
- [BRD02: Восстановление урезанного запроса](BRD02%20query_reconstruction.md)
- [SRS02: query_reconstruction](SRS02%20query_reconstruction.md)

### Уровень 3: Извлечение и сверка запросов
- [BRD03: Извлечение запроса из FULL_PARS и сверка](BRD03%20query_extraction_and_verification.md)
- [SRS03: query_extraction_and_verification](SRS03%20query_extraction_and_verification.md)

### Уровень 4: Извлечение inline-подзапросов
- [BRD04: Извлечение inline-подзапросов из не-FROM контекстов](BRD04%20inline_subqueries.md)
- [SRS04: inline_subqueries](SRS04%20inline_subqueries.md)
- [BRD04.01: Парсинг подзапросов в WHERE](BRD04.01%20only%20SDBL%20where%20select.md)
- [SRS04.01: Спецификация парсинга WHERE](SRS04.01%20only%20SDBL%20where%20select.md)
- [BRD04.02: Уточнение блоковой структуры where/having](BRD04.02%20clarifying%2004%20based%20on%2004.01.md)
- [SRS04.02: Уточнение блоковой структуры](SRS04.02%20clarifying%2004%20based%20on%2004.01.md)

### Уровень 4.1: Парсинг подзапросов в WHERE (SDBL)
- [BRD04.01: Парсинг подзапросов в блоке WHERE](BRD04.01%20only%20SDBL%20where%20select.md)
- [SRS04.01: Спецификация реализации — парсинг подзапросов в WHERE](SRS04.01%20only%20SDBL%20where%20select.md)

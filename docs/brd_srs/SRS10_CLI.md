# SRS10 — SDQL CLI: Спецификация системных требований

**Версия:** 1.0  
**Дата:** 2026-06-06  
**Статус:** Черновик  
**Задача Redmine:** #646  
**Трассировка:** BRD10 — CLI-интерфейс для SDQL-парсера

---

## 1. Назначение

Настоящий документ определяет системные требования к CLI-интерфейсу SQL-анализатора 1С (SDQL). CLI предназначен для изолированного взаимодействия AI-агентов с парсером через стандартные потоки ввода/вывода.

---

## 2. Ссылки

| Документ | Описание |
|----------|----------|
| BRD10_CLI.md | Бизнес-требования к CLI-интерфейсу |
| INTRO 10 CLI.MD | Принципы интеграции классических сервисов через CLI |
| INST06_MCP.md | Развёртывание SDQL MCP |
| PARSER_DETAILS.md | Конвейер обработки, модели, именование узлов |

---

## 3. Термины и определения

| Термин | Определение |
|--------|-------------|
| CLI | Command Line Interface — интерфейс командной строки |
| Exit-код | Целое число 0–4, возвращаемое процессом при завершении |
| Команда | Именованная операция CLI (analyze, sdbl-model и т.д.) |
| Контракт | Формализованное описание входных и выходных данных команды |
| MCP_QUERY_1C | MCP-сервис (порт 15372), основной потребитель CLI |

---

## 4. Общее описание системы

### 4.1 Архитектура

```
MCP_QUERY_1C (порт 15372)
    │
    ▼  Process spawn (stdin/stdout)
SDQL CLI (JVM process)
    │
    ▼  Direct Java call
SdqlPipeline (BRD06)
    │
    ▼  File system
Артефакты (SDBL_PARS, LINE_PARS, ...)
```

### 4.2 Отличие от MCP HTTP (порт 5372)

| Аспект | MCP HTTP (5372) | CLI (SRS10) |
|--------|-----------------|-------------|
| Транспорт | HTTP POST | stdin/stdout |
| Потребитель | Внешние клиенты | MCP_QUERY_1C |
| Состояние | Сервер с сессиями | Stateless процесс |
| Запуск | `java -jar ... --mode=mcp` | `java -cp ... SdqlCli` |
| Параллелизм | Многопоточный | Процесс на запрос |

---

## 5. Функциональные требования

### SRS-10.01 Чтение из stdin / запись в stdout

**Требование:** CLI должен читать JSON-запрос из stdin и записывать JSON-ответ в stdout.

**Формат входа:**
```json
{
  "command": "analyze",
  "args": { ... }
}
```

**Формат выхода (успех):**
```json
{
  "success": true,
  "exitCode": 0,
  "data": { ... }
}
```

**Формат выхода (ошибка):**
```json
{
  "success": false,
  "exitCode": 2,
  "errorCode": "MISSING_INPUT",
  "message": "...",
  "details": { ... }
}
```

**Проверка:**
```bash
echo '{"command":"analyze","args":{"sqlText":"ВЫБРАТЬ 1"}}' | \
  java -cp bsl-parser.jar com.github._1c_syntax.bsl.parser.sdql.cli.SdqlCli
```

---

### SRS-10.02 Флаг --help

**Требование:** Каждая команда и сам CLI должны поддерживать `--help`.

**Вывод `--help` (пример для `analyze`):**
```
Usage: sdql-cli analyze [OPTIONS]

Analyze SQL package and return metadata.

Options:
  --sql-file PATH       Path to SQL file (alternative to --sql-text)
  --sql-text TEXT       SQL text directly
  --output-dir PATH     Directory for artifacts (required)
  --base-name NAME      Base name for artifacts (optional, auto-generated)
  --detailed            Include detailed models
  --online              Do not persist artifacts (temp dir)
  --help                Show this message

Exit codes:
  0  Success
  2  Missing required argument
  3  SQL parse error
  4  Internal error
```

---

### SRS-10.03 Таксономия exit-кодов

**Требование:** Процесс должен завершаться с кодом:

| Код | Условие | Когда возникает |
|-----|---------|-----------------|
| 0 | Успех | Команда выполнена, результат в stdout |
| 1 | Сетевая ошибка | Команда `analyze` с `--fetch-from-1c` и 1C недоступна |
| 2 | Ошибка валидации | Отсутствует обязательный аргумент, неверный тип, пустое значение |
| 3 | Ошибка разбора SQL | ANTLR4 не может распарсить текст, семантическая ошибка |
| 4 | Внутренняя ошибка | IOException, OOM, непредвиденное исключение |

**Требование к stderr:** При кодах 1–4 в stderr записывается человекочитаемое сообщение. В stdout всегда JSON с полем `exitCode`.

---

### SRS-10.04 Строгая валидация входных данных

**Требование:** Валидация выполняется до запуска бизнес-логики.

**Правила валидации:**
- Обязательные поля присутствуют и не пустые
- Типы данных соответствуют ожидаемым (string, boolean, integer)
- Пути к файлам/директориям существуют (если требуется)
- SQL-текст не пустой и не превышает 10 МБ

**Пример ошибки валидации (stdout):**
```json
{
  "success": false,
  "exitCode": 2,
  "errorCode": "MISSING_ARGUMENT",
  "message": "Missing required argument: outputDir",
  "details": {
    "field": "outputDir",
    "command": "analyze"
  }
}
```

---

### SRS-10.05 Команда `analyze`

**Требование:** Полный разбор SQL-пакета с сохранением артефактов.

**Аргументы:**

| Аргумент | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `sqlText` | string | Да (если нет `sqlFile`) | Текст SQL-запроса |
| `sqlFile` | string | Да (если нет `sqlText`) | Путь к файлу с SQL |
| `outputDir` | string | Да (в offline-режиме) | Директория для артефактов |
| `baseName` | string | Нет | Имя для артефактов (auto-generated если не указано) |
| `detailed` | boolean | Нет | Включить детальные модели (default: false) |
| `online` | boolean | Нет | Не сохранять артефакты (default: false) |

**Результат (data):**
```json
{
  "baseName": "tmp_a1b2c3d4",
  "nodesCount": 5,
  "targetNodes": [
    {"id": 0, "name": "ВТ_Тест", "type": "temp_query"}
  ],
  "models": {
    "sdblPars": "sdbl_parse_model_tmp_a1b2c3d4.json",
    "linePars": "LINE_PARS/LINE_PARS_model_tmp_a1b2c3d4.json",
    "fullPars": "FULL_PARS/FULL_PARS_model_tmp_a1b2c3d4.json"
  },
  "artifacts": {
    "hierarchy": "LINE_PARS/LINE_PARS_hierarchy_tmp_a1b2c3d4.json",
    "fieldLineage": "field_lineage/tmp_a1b2c3d4/",
    "fullFieldLineage": "full_field_lineage/tmp_a1b2c3d4/",
    "restoredQueries": "RESTORED_QUERIES/tmp_a1b2c3d4/",
    "extractedQueries": "EXTRACTED_QUERIES/tmp_a1b2c3d4/",
    "verification": "VERIFICATION/tmp_a1b2c3d4/verification_report.json"
  }
}
```

---

### SRS-10.06 Команды чтения моделей

**Требование:** Команды для получения ранее сохранённых моделей.

**Общие аргументы:**

| Аргумент | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `artifactsDir` | string | Да | Директория с артефактами |
| `baseName` | string | Да | Имя артефактов |

**Команды:**

| Команда | Результат (data) |
|---------|-----------------|
| `sdbl-model` | `QueryModel` JSON |
| `line-pars-model` | `LineParsModel` JSON |
| `full-pars-model` | `FullParsModel` JSON |
| `hierarchy` | `List<HierarchyNode>` JSON |
| `list-nodes` | `List<NodeInfo>` JSON |
| `verify` | `VerificationReport` JSON |

**Пример:**
```bash
echo '{"command":"sdbl-model","args":{"artifactsDir":"/tmp/out","baseName":"tmp_a1b2c3d4"}}' | \
  java -cp bsl-parser.jar com.github._1c_syntax.bsl.parser.sdql.cli.SdqlCli
```

---

### SRS-10.07 Команды lineage и restore

**Требование:** Команды для получения lineage и восстановления SQL.

**Команда `field-lineage`:**

| Аргумент | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `artifactsDir` | string | Да | Директория с артефактами |
| `baseName` | string | Да | Имя артефактов |
| `nodeId` | integer | Да (если нет `nodeName`) | ID узла |
| `nodeName` | string | Да (если нет `nodeId`) | Имя узла |
| `alias` | string | Да | Имя поля |

**Команда `full-field-lineage`:**

| Аргумент | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `artifactsDir` | string | Да | Директория с артефактами |
| `baseName` | string | Да | Имя артефактов |
| `nodeId` | integer | Да (если нет `nodeName`) | ID узла |
| `nodeName` | string | Да (если нет `nodeId`) | Имя узла |
| `aliases` | string[] | Да | Список имён полей |

**Команда `restore-query`:**

| Аргумент | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `artifactsDir` | string | Да | Директория с артефактами |
| `baseName` | string | Да | Имя артефактов |
| `nodeId` | integer | Да (если нет `nodeName`) | ID узла |
| `nodeName` | string | Да (если нет `nodeId`) | Имя узла |
| `aliases` | string[] | Да | Список имён полей |
| `format` | string | Нет | `sql` или `json` (default: `sql`) |

---

### SRS-10.08 Генерация baseName

**Требование:** Если `baseName` не указан в команде `analyze`, генерировать автоматически.

**Формат:** `tmp_` + 8 случайных alphanumeric символов.

**Пример:** `tmp_a1b2c3d4`

---

### SRS-10.09 Режим `online`

**Требование:** При `online: true` артефакты сохраняются во временную директорию, которая удаляется после завершения процесса.

**Поведение:**
- `outputDir` игнорируется (или не требуется)
- Результат `analyze` содержит все модели inline в поле `data.models`
- Артефакты не доступны для последующих команд чтения

---

### SRS-10.10 Обработка больших SQL-запросов

**Требование:** CLI должен корректно обрабатывать SQL-тексты до 10 МБ.

**Ограничения:**
- Максимальный размер `sqlText` в JSON: 10 МБ
- При превышении — exit-код 2, `errorCode: "INPUT_TOO_LARGE"`

---

## 6. Нефункциональные требования

### SRS-10.11 Производительность

| Метрика | Требование |
|---------|-----------|
| Время запуска JVM | < 3 секунды |
| Время разбора (100 узлов) | < 10 секунд |
| Время разбора (600 узлов) | < 60 секунд |
| Пиковое потребление памяти | < 1 GB |

### SRS-10.12 Безопасность

- CLI не выполняет SQL-запросы, только парсит их
- Нет сетевых вызовов (кроме опционального `--fetch-from-1c`)
- Пути к файлам проверяются на выход за пределы `--output-dir`

### SRS-10.13 Логирование

- Логи пишутся в stderr
- Уровень логирования: INFO по умолчанию
- Флаг `--debug` для DEBUG-уровня
- Структурированный формат: `[LEVEL] timestamp message`

---

## 7. Интерфейсы

### SRS-10.14 Интерфейс с MCP_QUERY_1C

```java
// Псевдокод интеграции MCP_QUERY_1C → CLI
ProcessBuilder pb = new ProcessBuilder(
    "java", "-cp", "bsl-parser.jar",
    "com.github._1c_syntax.bsl.parser.sdql.cli.SdqlCli"
);
Process process = pb.start();

// stdin
process.getOutputStream().write(jsonRequest.getBytes());
process.getOutputStream().close();

// stdout
String jsonResponse = new String(process.getInputStream().readAllBytes());

// exit code
int exitCode = process.waitFor();
```

### SRS-10.15 Интерфейс с SdqlPipeline

```java
// Внутри JVM CLI вызывает Pipeline напрямую
SdqlPipeline pipeline = new SdqlPipelineImpl(repository);
PipelineResult result = pipeline.analyze(sqlText, outputDir, baseName, detailed);
```

---

## 8. Требования к среде выполнения

| Компонент | Версия |
|-----------|--------|
| Java | 17+ |
| Gradle | 8.5+ (сборка) |
| ANTLR4 Runtime | 4.13.1 |
| Jackson | 2.17.2 |
| Файловая система | Доступ на чтение/запись для `--output-dir` |

---

## 9. Трассировка BRD → SRS

| BRD | SRS | Реализация |
|-----|-----|-----------|
| REQ-10.01 | SRS-10.01 | `SdqlCli.main()` — чтение stdin, запись stdout |
| REQ-10.02 | SRS-10.02 | `CliHelpPrinter` |
| REQ-10.03 | SRS-10.03 | `System.exit(N)` + `CliErrorResponse` |
| REQ-10.04 | SRS-10.04 | `CliArgumentValidator` |
| REQ-10.05 | SRS-10.05 | Интерфейс `CliCommand` |
| REQ-10.06 | SRS-10.06–10.07 | Классы `AnalyzeCommand`, `SdblModelCommand`, ... |
| REQ-10.07 | SRS-10.01 | `CliResponse` + Jackson |
| REQ-10.08 | SRS-10.06 | `--artifacts-dir` во всех командах чтения |
| REQ-10.09 | SRS-10.09 | `AnalyzeCommand` с `online` флагом |

---

*Refs Redmine #646*

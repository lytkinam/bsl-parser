# AI_INTRO_10 — Вводное руководство для AI-агентов по задаче CLI-интерфейса SDQL

**Версия:** 1.0  
**Дата:** 2026-06-06  
**Задача Redmine:** #646  
**Целевая аудитория:** AI-агенты (Kimi CLI, Hermes, и др.)

---

## 1. Зачем это руководство

Этот документ помогает AI-агенту быстро понять контекст задачи #646 (SDQL CLI) и принять правильные архитектурные решения. Прочитай его перед любыми действиями по BRD10/SRS10.

---

## 2. Что такое SDQL

SDQL (SQL-анализатор для 1С) — это Java-библиотека, которая:
- Парсит SQL-запросы на диалекте 1С (SDBL)
- Строит модели: SDBL_PARS → LINE_PARS → FULL_PARS
- Извлекает иерархию, field lineage, восстанавливает SQL
- Работает с пакетными запросами (ВТ, JOIN, UNION, подзапросы)

**Ключевой класс:** `SdqlPipeline.analyze(sqlText, outputDir, baseName, detailed)`

---

## 3. Текущая архитектура (до CLI)

```
┌─────────────────┐     HTTP 5372     ┌─────────────────┐
│   Kimi CLI      │ ◄──────────────► │  SDQL MCP       │
│   (агент)       │                   │  (McpHttpServer)│
└─────────────────┘                   └─────────────────┘
                                              │
                                              ▼
                                       ┌─────────────────┐
                                       │  SdqlPipeline   │
                                       └─────────────────┘
```

Проблема: MCP-сервер на порту 5372 — это HTTP-сервис. MCP_QUERY_1C (порт 15372) тоже HTTP. Взаимодействие между ними идёт по сети, что:
- Добавляет лишнюю точку отказа
- Усложняет развёртывание (два порта)
- Нарушает принцип изоляции (сетевые вызовы внутри одного хоста)

---

## 4. Целевая архитектура (с CLI)

```
┌─────────────────┐     MCP 15372      ┌─────────────────┐
│   Kimi CLI      │ ◄───────────────► │  MCP_QUERY_1C   │
│   (агент)       │    HTTP/SSE       │  (McpQuery1c    │
└─────────────────┘                   │   HttpServer)   │
                                      └─────────────────┘
                                               │
                                               │ Process spawn
                                               │ stdin/stdout
                                               ▼
                                      ┌─────────────────┐
                                      │   SDQL CLI      │
                                      │  (SdqlCli)      │
                                      │  Stateless JVM  │
                                      └─────────────────┘
                                               │
                                               ▼
                                      ┌─────────────────┐
                                      │  SdqlPipeline   │
                                      └─────────────────┘
```

**Преимущества:**
- MCP_QUERY_1C и SDQL CLI работают на одном хосте без сети
- SDQL CLI — заменяемый чёрный ящик
- Проще тестировать и отлаживать
- Соответствует принципам INTRO 10 CLI.MD

---

## 5. Что уже сделано (не трогать)

| Компонент | Статус | Где находится |
|-----------|--------|---------------|
| SDQL Pipeline | ✅ Готов | `src/main/java/.../sdql/api/SdqlPipeline.java` |
| MCP HTTP (5372) | ✅ Готов | `McpHttpServer.java` — остаётся для внешних клиентов |
| MCP_QUERY_1C (15372) | ✅ Готов | `McpQuery1cHttpServer.java` — будет вызывать CLI |
| Артефакты | ✅ Готов | `SDBL_PARS/`, `LINE_PARS/`, `FULL_PARS/` и др. |
| BRD10 | ✅ Готов | `docs/brd_srs/BRD10_CLI.md` |
| SRS10 | ✅ Готов | `docs/brd_srs/SRS10_CLI.md` |

---

## 6. Что нужно реализовать (по SRS10)

### 6.1 Основные классы

```
src/main/java/com/github/_1c_syntax/bsl/parser/sdql/cli/
├── SdqlCli.java              # Точка входа (main)
├── CliRequest.java           # DTO: входной JSON
├── CliResponse.java          # DTO: выходной JSON
├── CliErrorResponse.java     # DTO: ошибка
├── CliCommand.java           # Интерфейс команды
├── CliArgumentParser.java    # Парсер аргументов + --help
├── CliArgumentValidator.java # Валидация контракта
├── CliHelpPrinter.java       # Вывод --help
└── commands/
    ├── AnalyzeCommand.java
    ├── SdblModelCommand.java
    ├── LineParsModelCommand.java
    ├── FullParsModelCommand.java
    ├── HierarchyCommand.java
    ├── FieldLineageCommand.java
    ├── FullFieldLineageCommand.java
    ├── RestoreQueryCommand.java
    ├── ListNodesCommand.java
    └── VerifyCommand.java
```

### 6.2 Точка входа

```java
public class SdqlCli {
    public static void main(String[] args) {
        // Режим 1: JSON из stdin (для MCP_QUERY_1C)
        if (args.length == 0) {
            String json = new String(System.in.readAllBytes());
            CliRequest request = parseRequest(json);
            CliResponse response = execute(request);
            System.out.println(toJson(response));
            System.exit(response.getExitCode());
        }
        
        // Режим 2: Аргументы командной строки (для человека)
        if ("--help".equals(args[0])) {
            printHelp();
            System.exit(0);
        }
        
        String command = args[0];
        Map<String, String> parsedArgs = parseArgs(args);
        CliResponse response = execute(command, parsedArgs);
        System.out.println(toJson(response));
        System.exit(response.getExitCode());
    }
}
```

### 6.3 Exit-коды (критически важно)

```java
public enum ExitCode {
    SUCCESS(0),           // Всё ок
    NETWORK_ERROR(1),     // 1C недоступна (только для fetch-from-1c)
    VALIDATION_ERROR(2),  // Неверные аргументы
    PARSE_ERROR(3),       // SQL не парсится
    INTERNAL_ERROR(4);    // Непредвиденная ошибка
}
```

**Правило:** При коде 2 агент должен получить структурированное описание ошибки и исправить запрос, а не менять логику CLI.

---

## 7. Как взаимодействовать с CLI

### 7.1 Из кода (MCP_QUERY_1C)

```java
// Пример вызова из MCP_QUERY_1C
ProcessBuilder pb = new ProcessBuilder(
    "java", "-cp", "/app/app.jar",
    "com.github._1c_syntax.bsl.parser.sdql.cli.SdqlCli"
);
Process process = pb.start();

// Отправить запрос
String request = "{\"command\":\"analyze\",\"args\":{\"sqlText\":\"ВЫБРАТЬ 1\",\"outputDir\":\"/tmp/out\"}}";
process.getOutputStream().write(request.getBytes());
process.getOutputStream().close();

// Получить ответ
String response = new String(process.getInputStream().readAllBytes());
int exitCode = process.waitFor();

if (exitCode == 2) {
    // Исправить аргументы и повторить
} else if (exitCode == 3) {
    // SQL некорректен — сообщить пользователю
}
```

### 7.2 Из командной строки (человек)

```bash
# JSON-режим (для интеграции)
echo '{"command":"analyze","args":{"sqlText":"ВЫБРАТЬ 1","outputDir":"/tmp/out"}}' | \
  java -cp bsl-parser.jar com.github._1c_syntax.bsl.parser.sdql.cli.SdqlCli

# Аргументы-режим (для отладки)
java -cp bsl-parser.jar com.github._1c_syntax.bsl.parser.sdql.cli.SdqlCli \
  analyze --sql-text "ВЫБРАТЬ 1" --output-dir /tmp/out

# Помощь
java -cp bsl-parser.jar com.github._1c_syntax.bsl.parser.sdql.cli.SdqlCli --help
java -cp bsl-parser.jar com.github._1c_syntax.bsl.parser.sdql.cli.SdqlCli analyze --help
```

---

## 8. Частые ошибки (не делать)

| ❌ Неправильно | ✅ Правильно |
|----------------|-------------|
| HTTP-вызов из MCP_QUERY_1C на порт 5372 | Process spawn + stdin/stdout |
| Возвращать длинный текст ошибки | Возвращать JSON с `exitCode` и `errorCode` |
| Глобальное состояние (синглтоны) | Все данные через аргументы |
| Игнорировать `--help` | Полное описание каждой команды |
| Exit-код 1 при любой ошибке | Детерминированные коды 0–4 |
| Писать логи в stdout | Логи в stderr, результат в stdout |

---

## 9. Связанные документы

| Документ | Зачем читать |
|----------|-------------|
| `BRD10_CLI.md` | Бизнес-требования, контракты команд |
| `SRS10_CLI.md` | Системные требования, форматы JSON, exit-коды |
| `INTRO 10 CLI.MD` | Философия: почему CLI, принципы изоляции |
| `PARSER_DETAILS.md` | Как работает конвейер разбора |
| `INST06_MCP.md` | Развёртывание SDQL (контекст) |
| `BRD08_MCP_QUERY_1C.md` | Как MCP_QUERY_1C будет использовать CLI |

---

## 10. Быстрый старт для агента

1. Прочитать этот файл (AI_INTRO_10)
2. Прочитать `BRD10_CLI.md` — понять команды и контракты
3. Прочитать `SRS10_CLI.md` — понять JSON-форматы и exit-коды
4. НЕ приступать к реализации без утверждения плана
5. При реализации — начать с `SdqlCli.java` + `AnalyzeCommand.java`
6. Тестировать через `echo '{...}' | java -cp ... SdqlCli`

---

*Refs Redmine #646*

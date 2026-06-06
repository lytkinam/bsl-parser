# SRS08 — MCP_QUERY_1C: Спецификация системных требований

**Версия:** 1.0  
**Дата:** 2026-06-03  
**Статус:** Реализовано  
**Задача Redmine:** #646

---

## 1. Обзор программного решения

MCP_QUERY_1C — самостоятельный Java-сервис (JDK 17), построенный на Jetty 11.0.20, предоставляющий MCP-интерфейс (Model Context Protocol) для получения, кэширования и разбора SQL-запросов из конфигурации 1С:УНПФ8.

---

## 2. Архитектура

```
┌─────────────────┐     MCP HTTP      ┌──────────────────────┐
│   Kimi CLI /    │ ◄────────────────►│   MCP_QUERY_1C       │
│   Другой клиент │    JSON-RPC 2.0   │   (Jetty HTTP)       │
└─────────────────┘                   └──────────┬───────────┘
                                                 │
                    ┌────────────────────────────┼────────────────────────────┐
                    │                            │                            │
                    ▼                            ▼                            ▼
           ┌─────────────┐            ┌─────────────────┐           ┌──────────────┐
           │ 1c-mcp-     │            │   SdqlPipeline  │           │  FileSystem  │
           │ server-     │◄──────────►│   (BRD06)       │◄─────────►│  Repository  │
           │ popov       │ list_query_│   analyze()     │           │  (artifacts) │
           │             │ param      │                 │           │              │
           └─────────────┘            └─────────────────┘           └──────────────┘
```

---

## 3. Компоненты системы

### 3.1 Пакет `sdql.mcp_query_1c`

| Класс | Назначение | Строк кода |
|-------|-----------|-----------|
| `McpQuery1cConfig` | Загрузка конфигурации из args/properties | ~80 |
| `McpQuery1cServer` | Точка входа (main), DI, запуск | ~50 |
| `McpQuery1cHttpServer` | Jetty HTTP-сервер, маршрутизация `/mcp`, `/health` | ~200 |
| `McpQuery1cTools` | Диспетчер 15 MCP-методов | ~600 |
| `McpQuery1cResponse` | JSON-RPC response DTO | ~55 |
| `Query1cClient` | HTTP-клиент для test_popov с пагинацией | ~120 |
| `QueryParameter` | DTO параметра (Lombok @Data) | ~25 |
| `QueryParameterStore` | Файловое хранилище параметров и реестра | ~150 |

### 3.2 Зависимости

| Библиотека | Версия | Назначение |
|-----------|--------|-----------|
| Jetty Server | 11.0.20 | HTTP-сервер |
| Jackson Databind | 2.17.2 | JSON сериализация |
| Lombok | (via plugin) | Генерация boilerplate |
| Java HTTP Client | JDK 17 | Вызовы внешнего MCP |

Новые зависимости не добавляются — используются существующие из проекта bsl-parser.

---

## 4. MCP-методы

### 4.1 Управление параметрами

#### `add_parameter`
```json
{
  "name": "258_произвЗапр_такс6.0",
  "forceRefresh": false
}
```
**Логика:**
1. Проверить наличие в кэше (если есть и `forceRefresh=false` — вернуть `status: exists`).
2. Вызвать `list_query_param` у `test_popov` с пагинацией (`part_query=0,1,2...`).
3. Склеить части запроса.
4. Сохранить SQL в `QueryParameterStore`.
5. Вызвать `SdqlPipeline.analyze(sqlText, artifactsDir, baseName, false)`.
6. Сохранить метаданные параметра.

**Ответ:**
```json
{
  "parameterName": "258_произвЗапр_такс6.0",
  "baseName": "258_proizvZapr_taks6_0",
  "nodesCount": 150,
  "status": "created|updated|exists",
  "updatedAt": "2026-06-03T20:00:00Z"
}
```

#### `add_custom_query`
```json
{
  "name": "my_custom_query",
  "sqlText": "ВЫБРАТЬ ..."
}
```
**Логика:** Аналогично `add_parameter`, но SQL передаётся напрямую, `source=CUSTOM`.

#### `list_parameters`
```json
{
  "filter": "258"
}
```
**Ответ:**
```json
{
  "parameters": [
    { "name": "...", "baseName": "...", "updatedAt": "...", "nodesCount": 150, "source": "onec" }
  ],
  "total": 1
}
```

#### `analyze_parameter`
```json
{ "name": "258_произвЗапр_такс6.0" }
```
**Логика:** Перезапросить SQL из 1С (если `source=ONEC`) и выполнить полный разбор.

#### `get_parameter_info`
```json
{ "parameterName": "258_произвЗапр_такс6.0" }
```
**Ответ:** Метаданные без полного SQL.

### 4.2 Прокси-методы BRD06

Все методы принимают `parameterName` вместо `sqlText`/`baseName`.

| Метод | Аргументы | Описание |
|-------|----------|----------|
| `get_sdbl_model` | `parameterName` | SDBL модель |
| `get_line_pars_model` | `parameterName` | LINE_PARS модель |
| `get_full_pars_model` | `parameterName` | FULL_PARS модель |
| `get_hierarchy` | `parameterName` | Иерархия узлов |
| `get_field_lineage` | `parameterName`, `alias`, `nodeId?`, `nodeName?` | Lineage поля |
| `get_full_field_lineage` | `parameterName`, `aliases[]`, `nodeId?`, `nodeName?` | Full lineage |
| `get_restored_query` | `parameterName`, `aliases[]`, `format?`, `nodeId?`, `nodeName?` | Восстановленный SQL |
| `get_node_info` | `parameterName`, `nodeId?`, `nodeName?`, `modelType?` | Информация об узле |
| `list_nodes` | `parameterName`, `nodeType?`, `modelType?` | Список узлов |
| `get_verification_report` | `parameterName` | Отчёт верификации |

---

## 5. Алгоритм пагинации `part_query`

```java
StringBuilder fullQuery = new StringBuilder();
int part = 0;
while (part < MAX_PARTS) {
    String chunk = callListQueryParam(name, part);
    if (chunk == null || chunk.isEmpty()) break;
    fullQuery.append(chunk);
    part++;
}
```

- `MAX_PARTS = 100` (защита от бесконечного цикла).
- Каждый вызов — POST `tools/call` с `name=list_query_param`, `arguments={name, part_query}`.
- Ответ `list_query_param` — JSON-массив с одним строковым элементом (текст запроса).

---

## 6. Конфигурация

### 6.1 Файл `mcp_query_1c.properties`

```properties
server.port=8081
external.mcp.url=http://192.168.117.247/npf_ops_users_test_popov/hs/mcp
storage.dir=./mcp_query_1c_storage
```

### 6.2 Аргументы командной строки

| Аргумент | Описание |
|----------|----------|
| `--config=<file>` | Путь к properties-файлу |
| `--port=<n>` | Порт сервиса (переопределяет config) |
| `--external-mcp-url=<url>` | URL внешнего MCP (переопределяет config) |
| `--storage-dir=<path>` | Директория хранения (переопределяет config) |

---

## 7. Хранение данных

### 7.1 Реестр параметров

Файл `mcp_query_1c_storage/parameters.json` — JSON-массив `QueryParameter[]`.

### 7.2 Структура директории параметра

```
<storage_dir>/<sanitized_name>/
├── query.sql          # Исходный SQL
├── parameter.json     # Метаданные (QueryParameter)
└── artifacts/         # Артефакты SdqlPipeline
    ├── SDBL_PARS/
    │   └── sdbl_parse_model_<baseName>.json
    ├── LINE_PARS/
    │   ├── LINE_PARS_model_<baseName>.json
    │   └── LINE_PARS_hierarchy_<baseName>.json
    ├── FULL_PARS/
    │   └── FULL_PARS_model_<baseName>.json
    ├── field_lineage/
    ├── full_field_lineage/
    ├── RESTORED_QUERIES/
    ├── EXTRACTED_QUERIES/
    └── VERIFICATION/
```

### 7.3 Санитизация имён

Имя параметра преобразуется в безопасное для ФС: `[^a-zA-Z0-9_\-]` → `_`.

---

## 8. Запуск и развёртывание

### 8.1 Gradle

```bash
./gradlew runMcpQuery1cServer
```

### 8.2 Java CLI

```bash
java -cp "build/classes/java/main:build/resources/main:$(find ~/.gradle/caches -name '*.jar' | tr '\n' ':')" \
  com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c.McpQuery1cServer \
  --config=mcp_query_1c.properties
```

### 8.3 Проверка работоспособности

```bash
curl -s http://localhost:8081/health
# {"status":"ok"}
```

---

## 9. NFR

### 9.1 Производительность
- `add_parameter` для запроса ~100000 символов: ≤ 60 сек (включая разбор).
- `get_*` методы: ≤ 1 сек (чтение из файловой системы).

### 9.2 Надёжность
- Если `test_popov` недоступен — `add_parameter` возвращает ошибку, `add_custom_query` работает.
- Если параметр удалён из 1С — локальная копия сохраняется, можно работать Offline.

### 9.3 Безопасность
- Сервис не хранит учётные данные 1С (вызовы MCP анонимные).
- Хранилище локальное; доступ контролируется ОС.

---

## 10. Трассировка требований

| SRS | Класс | Метод |
|-----|-------|-------|
| SRS-08.01 | `Query1cClient` | `fetchQuery(String)` |
| SRS-08.02 | `Query1cClient` | `fetchQueryPart(String, int)` |
| SRS-08.03 | `QueryParameterStore` | `save(QueryParameter)` |
| SRS-08.04 | `McpQuery1cTools` | `handleAddParameter()` |
| SRS-08.05 | `McpQuery1cTools` | `handleAnalyzeParameter()` |
| SRS-08.06 | `McpQuery1cTools` | `handleAddCustomQuery()` |
| SRS-08.07 | `McpQuery1cTools` | `handleGet*()` прокси |
| SRS-08.08 | `McpQuery1cTools` | `handleListParameters()` |

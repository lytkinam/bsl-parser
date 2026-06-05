# BRD06: MCP Server для SDQL

## 1. Назначение

MCP-сервер предоставляет AI-агентам доступ к моделям парсера SQL-запросов 1С (SDQL) через стандартный протокол MCP (Model Context Protocol). Сервер работает в двух режимах: **Online** (анализ SQL-текста на лету) и **Offline** (чтение уже сгенерированных артефактов).

## 2. Режимы работы

| Режим | Описание |
|-------|----------|
| **Online** | Параметр `sqlText` передаётся напрямую. Сервер запускает полный конвейер SdqlCli во временной директории и возвращает результат. |
| **Offline** | Параметр `baseName` указывает на уже сгенерированные артефакты. Сервер читает JSON-файлы из файловой системы. |

**Правило определения режима:**
- Если передан `sqlText` — режим **Online** (параметр `baseName` игнорируется, генерируется временный `baseName`)
- Если передан только `baseName` — режим **Offline**

**Валидация:** Хотя бы один из `sqlText` или `baseName` должен быть указан.

## 3. Общие параметры (все методы)

Каждый метод MCP-сервера принимает следующие параметры:

| Параметр | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `sqlText` | string | *условно* | Текст SQL-пакета (1С-запрос). Если указан — режим Online |
| `baseName` | string | *условно* | Базовое имя существующих артефактов. Обязателен, если не указан `sqlText` |
| `detailed` | boolean | нет | Генерировать ли markdown-отчёты (только Online, по умолчанию `false`) |

## 4. Идентификация узла

Во всех методах, где требуется указать узел, используется **один из** вариантов:

| Параметр | Тип | Описание |
|----------|-----|----------|
| `nodeId` | integer | Числовой ID узла (приоритет, если указаны оба) |
| `nodeName` | string | Имя узла (например, `ВТ_Суммы_ПР_ТранзитныеВиды`) |

**Разрешение:**
1. Если указан `nodeId` — используется он
2. Если указан только `nodeName` — выполняется поиск узла по имени в FULL_PARS модели
3. Если не найден — возвращается ошибка `NODE_NOT_FOUND`

## 5. Методы MCP-сервера

### 5.1 `analyze_sql_package`

**Назначение:** Разобрать SQL-пакет и вернуть мета-информацию о сгенерированных моделях.

**Параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `sqlText` | string | да | Текст SQL-пакета |
| `detailed` | boolean | нет | Генерировать markdown-отчёты |

**Возвращает:**
```json
{
  "baseName": "tmp_abc123",
  "nodesCount": 26,
  "targetNodes": [
    {"id": 86, "name": "ВТ_Суммы_ПР_ТранзитныеВиды", "type": "temp_query"}
  ],
  "models": {
    "sdblPars": "sdbl_parse_model_tmp_abc123.json",
    "linePars": "LINE_PARS/LINE_PARS_model_tmp_abc123.json",
    "fullPars": "FULL_PARS/FULL_PARS_model_tmp_abc123.json"
  },
  "artifacts": {
    "hierarchy": "LINE_PARS/LINE_PARS_hierarchy_tmp_abc123.json",
    "fieldLineage": "field_lineage/tmp_abc123/",
    "fullFieldLineage": "full_field_lineage/tmp_abc123/",
    "restoredQueries": "RESTORED_QUERIES/tmp_abc123/",
    "extractedQueries": "EXTRACTED_QUERIES/tmp_abc123/",
    "verification": "VERIFICATION/tmp_abc123/verification_report.json"
  }
}
```

---

### 5.2 `get_sdbl_model`

**Назначение:** Получить SDBL модель (итерация 0) — AST всех запросов пакета.

**Параметры:** `sqlText` | `baseName`, `detailed`

**Возвращает:** JSON `QueryModel` (список `QueryNode` + `QueryEdge`)

---

### 5.3 `get_line_pars_model`

**Назначение:** Получить LINE_PARS модель (итерация 1) — развёрнутые подзапросы и UNION.

**Параметры:** `sqlText` | `baseName`, `detailed`

**Возвращает:** JSON `LineParsModel` (список `LineParsNode` + edges)

---

### 5.4 `get_full_pars_model`

**Назначение:** Получить FULL_PARS модель (итерация 2) — с `field_id` и `child_fields`.

**Параметры:** `sqlText` | `baseName`, `detailed`

**Возвращает:** JSON `FullParsModel` (список `FullParsNode`)

---

### 5.5 `get_hierarchy`

**Назначение:** Получить иерархию узлов LINE_PARS.

**Параметры:** `sqlText` | `baseName`, `detailed`

**Возвращает:** JSON `HierarchyNode` (дерево зависимостей)

---

### 5.6 `get_field_lineage`

**Назначение:** Получить lineage поля на уровне LINE_PARS.

**Параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `sqlText` \| `baseName` | string | да | Режим Online/Offline |
| `detailed` | boolean | нет | Генерировать markdown |
| `nodeId` | integer | *условно* | ID целевого узла |
| `nodeName` | string | *условно* | Имя целевого узла |
| `alias` | string | да | Alias поля |

**Возвращает:** JSON `FieldLineageNode` (дерево lineage)

---

### 5.7 `get_full_field_lineage`

**Назначение:** Получить полный lineage одного или нескольких полей на уровне FULL_PARS.

**Параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `sqlText` \| `baseName` | string | да | Режим Online/Offline |
| `detailed` | boolean | нет | Генерировать markdown |
| `nodeId` | integer | *условно* | ID целевого узла |
| `nodeName` | string | *условно* | Имя целевого узла |
| `aliases` | string[] | да | Список alias полей (минимум 1) |
| `includeRelated` | boolean | нет | Включить связанные поля (group_by, where, having) |

**Возвращает:**
```json
{
  "baseName": "middle_example",
  "nodeId": 86,
  "nodeName": "ВТ_Суммы_ПР_ТранзитныеВиды",
  "aliases": ["Взносы", "ПенсионныйСчет"],
  "fflNodes": [
    {
      "id": 86,
      "name": "ВТ_Суммы_ПР_ТранзитныеВиды",
      "type": "temp_query",
      "select": [...],
      "from": [...],
      "whereFields": [...],
      "joinFields": [...],
      "groupByFields": [...],
      "havingFields": [...]
    },
    {
      "id": 87,
      "name": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1",
      "select": [...],
      "from": [...],
      ...
    }
  ]
}
```

---

### 5.8 `get_restored_query`

**Назначение:** Восстановить SQL-запрос(ы) из FFL по указанным полям.

**Параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `sqlText` \| `baseName` | string | да | Режим Online/Offline |
| `detailed` | boolean | нет | Генерировать markdown |
| `nodeId` | integer | *условно* | ID целевого узла |
| `nodeName` | string | *условно* | Имя целевого узла |
| `aliases` | string[] | да | Список alias полей для восстановления |
| `format` | string | нет | Формат вывода: `sql` (по умолчанию) или `json` |

**Возвращает (`format=sql`):**
```json
{
  "baseName": "middle_example",
  "nodeId": 86,
  "nodeName": "ВТ_Суммы_ПР_ТранзитныеВиды",
  "aliases": ["Взносы", "ПенсионныйСчет"],
  "sql": "ВЫБРАТЬ\n  СУММА(ПодЗапрос.Взносы) КАК Взносы,\n  ПодЗапрос.ПенсионныйСчет КАК ПенсионныйСчет,\n  ...\nПОМЕСТИТЬ ВТ_Суммы_ПР_ТранзитныеВиды"
}
```

**Возвращает (`format=json`):**
```json
{
  "restoredQueryNode": {
    "id": 86,
    "name": "ВТ_Суммы_ПР_ТранзитныеВиды",
    "selectExpressions": [...],
    "from": [...],
    "joins": [...],
    "whereConditions": [...],
    "groupByFields": [...],
    "havingConditions": [...],
    "unionParts": [...],
    "inlineSubqueries": {...}
  }
}
```

---

### 5.9 `get_node_info`

**Назначение:** Получить информацию об узле по ID или имени.

**Параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `sqlText` \| `baseName` | string | да | Режим Online/Offline |
| `detailed` | boolean | нет | Генерировать markdown |
| `nodeId` | integer | *условно* | ID узла |
| `nodeName` | string | *условно* | Имя узла |
| `modelType` | string | нет | Тип модели: `sdbl`, `line_pars`, `full_pars` (по умолчанию `full_pars`) |

**Возвращает:** Узел указанной модели

---

### 5.10 `list_nodes`

**Назначение:** Получить список всех узлов модели.

**Параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `sqlText` \| `baseName` | string | да | Режим Online/Offline |
| `detailed` | boolean | нет | Генерировать markdown |
| `modelType` | string | нет | Тип модели: `sdbl`, `line_pars`, `full_pars` |
| `nodeType` | string | нет | Фильтр по типу узла: `temp_query`, `select`, `sub_query`, `union_query`, `drop_query` |

**Возвращает:**
```json
{
  "nodes": [
    {"id": 0, "name": "ВТ_ИспользоватьДляНПО", "type": "temp_query", "sdblId": 0},
    {"id": 86, "name": "ВТ_Суммы_ПР_ТранзитныеВиды", "type": "temp_query", "sdblId": 25}
  ]
}
```

---

### 5.11 `get_verification_report`

**Назначение:** Получить отчёт верификации (сравнение извлечённых запросов с оригиналом).

**Параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `sqlText` \| `baseName` | string | да | Режим Online/Offline |
| `detailed` | boolean | нет | Генерировать markdown |

**Возвращает:** JSON `VerificationReport`

## 6. Коды ошибок

| Код | Сообщение | Условие |
|-----|-----------|---------|
| `MISSING_INPUT` | "Either sqlText or baseName must be provided" | Не указан ни `sqlText`, ни `baseName` |
| `NODE_NOT_FOUND` | "Node not found: name=<nodeName>" | `nodeName` не найден в модели |
| `NODE_NOT_FOUND` | "Node not found: id=<nodeId>" | `nodeId` не найден в модели |
| `ALIAS_NOT_FOUND` | "Alias '<alias>' not found in node <nodeId>" | Поле не найдено в select узла |
| `PIPELINE_ERROR` | "Failed to analyze SQL: <message>" | Ошибка в конвейере |
| `ARTIFACT_NOT_FOUND` | "Artifact not found: <path>" | Артефакт отсутствует (Offline) |

## 7. Архитектура

```
┌─────────────────────────────────────────────────────────────┐
│                    MCP Server (SDQL)                        │
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │  HTTP API   │  │  NodeResolver│  │  Pipeline Engine    │ │
│  │  (MCP proto)│  │  (name→id)   │  │  (SdqlCli wrappers) │ │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘ │
│         └─────────────────┴────────────────────┘            │
│                         │                                   │
│              ┌──────────┴──────────┐                        │
│              │   Artifact Store    │                        │
│              │  (baseName → Path)  │                        │
│              │  - Online: temp dir │                        │
│              │  - Offline: examples│                        │
│              └─────────────────────┘                        │
└─────────────────────────────────────────────────────────────┘
```

**Ключевые компоненты:**
- `NodeResolver` — разрешает `nodeName` → `nodeId` через `FullParsModel`
- `PipelineEngine` — обёртка над `SdqlCli` для Online-режима
- `ArtifactStore` — абстракция над файловой системой

## 8. Файлы реализации

| Файл | Назначение |
|------|-----------|
| `src/main/java/.../sdql/mcp/SdqlMcpServer.java` | Точка входа MCP-сервера |
| `src/main/java/.../sdql/mcp/SdqlMcpHandler.java` | Обработчик MCP-запросов |
| `src/main/java/.../sdql/mcp/NodeResolver.java` | Разрешение имени узла в ID |
| `src/main/java/.../sdql/mcp/ArtifactStore.java` | Хранилище артефактов (кэш) |
| `src/main/java/.../sdql/mcp/McpPipelineRunner.java` | Обертка над SdqlCli для Online-режима |

## 9. Зависимости

- Jackson 2.17.2 (JSON сериализация/десериализация)
- ANTLR4 Runtime 4.13.1 (парсинг SQL)
- JGraphT 1.5.2 (графы зависимостей)
- Lombok (генерация boilerplate)

## 10. Связь с другими BRD

| BRD | Описание | Связь |
|-----|----------|-------|
| BRD04.02 | Блоковая структура inline подзапросов | Модели `WhereBlock`, `HavingBlock`, `VirtualTableBlock` используются в FFL |
| BRD03 | AST-based verification | Метод `get_verification_report` возвращает результаты верификации |
| SRS04.02 | Структура моделей FULL_PARS | Методы `get_full_field_lineage`, `get_restored_query` работают с FFL |

## 11. Пример сценария использования

**Запрос:** "Восстанови SQL для полей Взносы и ПенсионныйСчет из узла 86"

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "get_restored_query",
    "arguments": {
      "baseName": "middle_example",
      "nodeId": 86,
      "aliases": ["Взносы", "ПенсионныйСчет"],
      "format": "sql"
    }
  }
}
```

**Ответ:** SQL-запрос, восстановленный из FFL по указанным полям.

---

*Refs Redmine #646*

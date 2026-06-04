# SRS01: Полный парсинг запросов (full_pars / full_field_lineage)

## 1. Введение

### 1.1 Цель документа

Настоящее техническое задание (SRS) определяет требования к третьему уровню парсинга SQL-пакетов 1С (SDBL) в рамках модуля SDQL проекта `bsl-parser`. Уровень включает две подсистемы:
- **full_pars** — расширенная полевая модель на основе `LINE_PARS` с уникальными идентификаторами полей и их зависимостями.
- **full_field_lineage** — извлечение полного набора данных, влияющих на результат конкретного поля запроса.

### 1.2 Область применения

Документ относится исключительно к модулю `sdql/` проекта `bsl-parser`. Разработка ведётся в ветке `develop`, все коммиты содержат `Refs Redmine #646`.

### 1.3 Определения и сокращения

| Термин | Определение |
|--------|-------------|
| **SDBL** | Язык запросов 1С:Предприятие (аналог SQL) |
| **LINE_PARS** | Второй уровень парсинга: плоская модель узлов с развёрнутыми UNION и подзапросами |
| **full_pars** | Третий уровень парсинга: LINE_PARS + field_id + child_fields для всех полей |
| **full_field_lineage** | Подсистема извлечения полного набора узлов и полей, влияющих на результат выбранного поля |
| **field_id** | Сквозной уникальный идентификатор поля (Integer), назначается только полям `select` |
| **child_fields** | Список прямых зависимостей конкретного поля (один уровень): ссылки на поля других узлов или физических таблиц |
| **full_child_fields** | Внутренняя переменная алгоритма (не выводится в JSON): объединение всех `child_fields` полей узла |
| **ВТ** | Временная таблица (создаётся `ПОМЕСТИТЬ`, используется `ВТ_Имя`) |
| **target node** | Узел, для которого строится full_field_lineage (обычно `result` или последний `temp_query`) |
| **virtual union parent** | Узел с `union_nodes_ids`, содержащий виртуальные `union_field` в `select` (очищен `from`, `where` и т.д.) |

### 1.4 Ссылки

- `docs/brd_srs/BRD01 full_pars.md` — исходные бизнес-требования
- `AGENTS.md` — инструкции для AI-агентов (порядок коммитов, тесты, CLI)
- `PARSER_DETAILS.md` — конвейер обработки, модели, именование узлов
- `PROJECT_STRUCTURE.md` — структура проекта, ключевые классы
- `JAVA_DEVELOPMENT.md` — особенности Gradle, Jackson, Lombok, ANTLR4

---

## 2. Общее описание

### 2.1 Контекст продукта

Модуль SDQL обрабатывает пакет SQL-запросов 1С через конвейер:

```
SDBL_PARS (итерация 0) → LINE_PARS (итерация 1)  →         full_pars (итерация 3)
                                        ↓                           ↓
                     field_lineage (итерация 2)      full_field_lineage (итерация 3)
```

- **SDBL_PARS**: AST + рёбра ВТ (разбиение по `;`)
- **LINE_PARS**: плоская модель с развёрнутыми UNION и подзапросами
- **field_lineage**: lineage для target-узлов через `LineParsFieldExtractor`
- **full_pars**: LINE_PARS + `field_id` для `select` + `child_fields` для каждого поля в `select`, `where`, `condition`, `group by`, `having`
- **full_field_lineage**: рекурсивный обрезанный граф зависимостей для выбранного поля, выводимый как **плоский (линейный)** массив узлов

### 2.2 Пользователи

Разработчики 1С и аналитики запросов, использующие JSON-выход для анализа зависимостей полей между запросами пакета.

### 2.3 Ограничения

- **Формат первичен** — выходной JSON определяется примером, без самовольных добавлений
- **Пример первее кода** — `middle_example` генерируется до коммита кода
- **Одноуровневая структура** — `child_fields` конкретного поля содержит только прямых потомков (один уровень)
- **Источник данных** — только каталог `LINE_PARS` (`LINE_PARS_model_*.json` + `LINE_PARS_hierarchy_*.json`)
- **Java 17**, Jackson 2.17.2, Lombok, ANTLR4 4.13.1

### 2.4 Зависимости

- `LINE_PARS_model_<basename>.json` — модель LINE_PARS
- `LINE_PARS_hierarchy_<basename>.json` — иерархия источников

---

## 3. Функциональные требования

### 3.1 full_pars

#### FR-3.1.1 Источник данных
Парсинг выполняется исключительно по данным каталога `LINE_PARS`. За основу берётся структура `LINE_PARS_model_<basename>.json`.

#### FR-3.1.2 Сквозная нумерация полей
Каждому полю в блоке `select` любого узла присваивается уникальный `field_id` (Integer, начиная с 1). Нумерация сквозная по всем узлам модели.

Поля `where_fields`, `join_conditions`, `group_by_fields`, `having_fields` **не получают** `field_id`.

#### FR-3.1.3 Расширение select
Структура каждого поля в `select` расширяется полем `child_fields` — массивом прямых зависимостей.

#### FR-3.1.4 Алгоритм child_fields для select
`child_fields` для поля `select` заполняется алгоритмом `LineParsFieldExtractor` (как в `field_lineage`), но **только один уровень** — дальше по иерархии не идём.

Для каждой найденной ссылки `alias.field`:
- Если `alias` соответствует subquery / ВТ (есть `id` в `LINE_PARS`) — `child_fields` содержит `field_id`, `alias`, `node_id`, `node_name`
- Если `alias` соответствует физической таблице (нет `id`) — `child_fields` содержит `alias`, `node_name`, `source`, `field_id` отсутствует (`null`)

#### FR-3.1.5 field_id в child_fields
В `child_fields` указывается `field_id` дочернего поля (только если дочерний узел — это `select`-поле). Если используется физическая таблица — `field_id` отсутствует (`null`).

#### FR-3.1.6 child_fields для where, condition, group by, having
Определение `child_fields` добавляется для:
- `where` — каждое поле в условии WHERE → `where_fields[]`
- `join condition` — каждое поле в условиях JOIN (`joins[].condition`) → `join_conditions[].condition_fields[]`
- `group_by` — каждое поле в GROUP BY → `group_by_fields[]`
- `having` — каждое поле в HAVING → `having_fields[]`

Исходные текстовые поля (`where`, `group_by`, `having`, `joins[].condition`) **сохраняются без изменений**. Новые `_fields` массивы добавляются параллельно.

Алгоритм тот же: регекс `alias.field`, определение источника по `LINE_PARS_hierarchy`.

#### FR-3.1.7 Выходной формат
JSON-массив узлов `full_pars`, структура аналогична `LINE_PARS_model`, но с расширенными полями:
- `select[]` содержит `field_id` и `child_fields`
- `where_fields[]` — массив объектов `{text, child_fields[]}` (параллельно `where`)
- `join_conditions[].condition_fields[]` — массив объектов `{text, child_fields[]}` (внутри каждого JOIN)
- `group_by_fields[]` — массив объектов `{text, child_fields[]}` (параллельно `group_by`)
- `having_fields[]` — массив объектов `{text, child_fields[]}` (параллельно `having`)

#### FR-3.1.8 Сохранение
Файл: `FULL_PARS/FULL_PARS_model_<basename>.json` (рядом с `SDBL_PARS/` и `LINE_PARS/`).

---

### 3.2 full_field_lineage

#### FR-3.2.1 Входные параметры
Одно или несколько полей, заданных парой `{nodeId, [aliases]}` (aliases — массив строк). Первоначальный эталон — `{74, [ЗадолженностьПенсии]}` для `middle_example`.

#### FR-3.2.2 Урезанная структура
В файл попадает урезанная структура `full_pars`: только узлы и поля, участвующие в lineage выбранного поля.

Выходной JSON — **плоский (линейный) массив узлов**, а не дерево. Каждый узел содержит `id`, `name`, `type`, `select[]`, `where_fields[]`, `join_conditions[]`, `group_by_fields[]`, `having_fields[]`. Связи между узлами выражаются через `child_fields[].node_id`.

#### FR-3.2.3 Поля where/condition/group by/having выбранной ноды
Для выбранной ноды в `where_fields`, `join_conditions`, `group_by_fields`, `having_fields` остаются **все существующие поля** (не только выбранное).

#### FR-3.2.4 Поля select выбранной ноды
В `select` выбранной ноды остаются:
- выбранные поля (из входных параметров `aliases`)
- поля из `group_by`, которых нет в `select` — **добавляются** с новым `field_id`

#### FR-3.2.5 Определение используемых нод
По полям `select` + `where_fields` + `join_conditions` + `group_by_fields` + `having_fields` собираются все `child_fields[]` в единую переменную `full_child_fields[]` (внутренняя, не выводится в JSON).

Сворачиваем `full_child_fields[]` по полям `{node_id, alias, field_id}` и **исключаем записи без `node_id`** (физические таблицы являются терминальными узлами, рекурсии по ним нет).

Определяются используемые ноды через уникальные `full_child_fields[].node_id`.

#### FR-3.2.6 Рекурсивный обход
Для каждой используемой ноды вызывается тот же алгоритм: `{node_id, [aliases]}`, где `aliases` — все `alias` из собранных ранее `full_child_fields[]`, с отбором по `full_child_fields[].node_id`.

#### FR-3.2.7 Обработка уже добавленных нод (линейность JSON)
Если рекурсия попадает на ноду, **уже присутствующую** в результирующем массиве FFL:
- Не создавать дубликат узла
- **Расширить** существующий узел новыми полями в `select`, если таких `alias` ещё нет
- `where_fields`, `group_by_fields`, `having_fields`, `join_conditions` — уже полные, не менять

Таким образом JSON остаётся плоским (линейным), без вложенности.

#### FR-3.2.8 Обработка UNION (виртуальный проброс)
Если нода содержит `union_nodes_ids` (виртуальный родитель UNION):
- Сама виртуальная нода **добавляется** в FFL-массив (если ещё не добавлена)
- Её `select` содержит только переданные `aliases` (как `union_field`)
- Затем **всегда** вызывается рекурсия для **всех** `union_nodes_ids` с теми же `aliases`
- Union-части добавляются в FFL-массив как отдельные узлы

Это правило «виртуального проброса»: виртуальная нода UNION не содержит реальных данных, но обязана передать вызов всем своим частям.

#### FR-3.2.9 Структура подкаталогов
```
full_field_lineage/
└── <baseName>/
    └── <nodeId>_<nodeName>/
        └── FFL_<baseName>_<nodeId>_<nodeName>_<alias>.json
```

#### FR-3.2.10 Имя файла
Шаблон: `FFL_<baseName>_<nodeId>_<nodeName>_<alias>.json`

---

## 4. Структуры данных

### 4.1 FullParsNode

Расширенная копия `LineParsNode`. Исходные текстовые поля сохраняются, добавляются `_fields` массивы.

```json
{
  "id": 74,
  "sdbl_id": 25,
  "name": "ВТ_Суммы_ПР_ТранзитныеВиды",
  "type": "temp_query",
  "select": [
    {
      "field_id": 1,
      "alias": "ЗадолженностьПенсии",
      "text": "СУММА(ПодЗапрос.ЗадолженностьПенсии)",
      "child_fields": [
        {
          "field_id": 2,
          "alias": "ЗадолженностьПенсии",
          "node_id": 75,
          "node_name": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1",
          "source": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1"
        }
      ]
    }
  ],
  "from": [...],
  "where": null,
  "where_fields": [],
  "group_by": ["ПодЗапрос.ПенсионныйСчет", "ПодЗапрос.ВидОбязательств", ...],
  "group_by_fields": [
    {
      "text": "ПодЗапрос.ПенсионныйСчет",
      "child_fields": [
        {
          "field_id": 3,
          "alias": "ПенсионныйСчет",
          "node_id": 75,
          "node_name": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1",
          "source": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1"
        }
      ]
    }
  ],
  "having": null,
  "having_fields": [],
  "joins": [],
  "join_conditions": [],
  "upquery_id": null,
  "subquery_ids": [75],
  "union_nodes_ids": [],
  "union_group_id": null,
  "union_type": null
}
```

### 4.2 FullParsChildField

Элемент массива `child_fields` (привязан к конкретному полю, не к узлу в целом):

| Поле | Тип | Описание |
|------|-----|----------|
| `field_id` | Integer/null | `field_id` дочернего поля (null для физических таблиц) |
| `alias` | String | Alias поля в дочернем узле |
| `node_id` | Integer/null | ID дочернего узла LINE_PARS (null для физических таблиц) |
| `node_name` | String/null | Имя дочернего узла или физической таблицы |
| `source` | String/null | Имя источника (для физических таблиц — полное имя с точкой) |

### 4.3 FullFieldLineageNode

Узел урезанной структуры `full_field_lineage`. Структура совпадает с `FullParsNode`, но `select` содержит только нужные поля.

FFL выводится как **плоский массив** таких узлов. Пример см. в разделе 9.3.

---

## 5. Алгоритмы

### 5.1 Назначение field_id

```
field_id = 1
for each node in full_pars_nodes (в порядке id):
    for each field in node.select:
        field.field_id = field_id++
```

### 5.2 Построение child_fields для select

Для каждого поля `select`:
1. Берём `text` поля
2. Регекс `(?U)([\w]+)\.([\w]+)` — ищем `alias.field`
3. Фильтруем цепочки `a.b.c` (удаляем `b.c`)
4. Для каждой ссылки `alias.field`:
   - Ищем `alias` в `tableHierarchy` текущего узла (через `LINE_PARS_hierarchy`)
   - Если найден `HierarchyNode` с `id != null` (subquery/ВТ):
     - Ищем дочерний узел по `id`
     - Ищем поле `field` в `select` дочернего узла
     - Если найдено — добавляем `child_fields` с `field_id` дочернего поля
   - Если `id == null` (физическая таблица) — добавляем leaf с `source`

### 5.3 Построение child_fields для where/condition/group by/having

Алгоритм аналогичен 5.2, но применяется к тексту условия/выражения.

Результат сохраняется в параллельные массивы:
- `where_fields[]` — для `where`
- `join_conditions[].condition_fields[]` — для каждого JOIN
- `group_by_fields[]` — для `group_by`
- `having_fields[]` — для `having`

### 5.4 Алгоритм full_field_lineage (линейный массив)

```
function buildLineage(node_id, aliases, result_map):
    node = full_pars[node_id]

    // 1. Create or extend node in result_map
    if result_map.containsKey(node_id):
        existing = result_map.get(node_id)
        // Extend select with new aliases
        for alias in aliases:
            if not existing.select.hasAlias(alias):
                field = node.select.findByAlias(alias)
                existing.select.add(cloneField(field))
    else:
        result = createNode(node, aliases) // includes all where_fields, group_by_fields, etc.
        result_map.put(node_id, result)

    // 2. Collect all child_fields from select + where + joins + group_by + having
    full_child_fields = []
    for field in result.select + result.where_fields + result.join_conditions + result.group_by_fields + result.having_fields:
        for child in field.child_fields:
            if child.node_id != null:
                full_child_fields.add(child)

    // 3. Group by node_id
    grouped = groupBy(full_child_fields, child -> child.node_id)

    // 4. Handle UNION virtual parent
    if node.union_nodes_ids is not empty:
        for union_id in node.union_nodes_ids:
            // Virtual pass-through: call with same aliases
            buildLineage(union_id, aliases, result_map)
        return

    // 5. Recursive call for each used node
    for (used_node_id, children) in grouped:
        used_aliases = children.stream().map(c -> c.alias).distinct().toList()
        buildLineage(used_node_id, used_aliases, result_map)

// Entry point:
result_map = new LinkedHashMap() // preserves insertion order
buildLineage(start_node_id, [start_alias], result_map)
output = result_map.values() // linear array
```

**Примечание:**
- `result_map` — LinkedHashMap (сохраняет порядок вставки)
- Рекурсия останавливается на физических таблицах (`child_fields` с `node_id=null` не добавляются в `full_child_fields`)
- UNION-родитель всегда пробрасывает вызов всем `union_nodes_ids` с исходными `aliases`

---

## 6. Интеграция с CLI

### 6.1 Новые шаги в SdqlCli

Конвейер расширяется:

```
1. SdqlQueryPackageAnalyzer.analyze()       → SDBL_PARS/
2. LineParsModelBuilder.build()             → LINE_PARS/LINE_PARS_model_*.json
3. LineParsHierarchyBuilder.build()         → LINE_PARS/LINE_PARS_hierarchy_*.json
4. LineParsFieldLineageBuilder.build()      → field_lineage/
5. SdqlModelMdBuilder.build()               → SDBL_PARS/*.md
6. LineParsModelMdBuilder.build()           → LINE_PARS/*.md
7. HierarchyMdBuilder.build()               → LINE_PARS/*.md
8. [NEW] FullParsModelBuilder.build()       → FULL_PARS/FULL_PARS_model_*.json
9. [NEW] FullFieldLineageBuilder.build()    → full_field_lineage/<baseName>/...
10. [NEW] FullParsModelMdBuilder.build()    → FULL_PARS/*.md
11. [NEW] FullFieldLineageMdBuilder.build() → full_field_lineage/<baseName>/...
```

### 6.2 Новые классы

| Класс | Пакет | Назначение |
|-------|-------|-----------|
| `FullParsModelBuilder` | `sdql/full_pars/` | Строит `FULL_PARS_model_*.json` из `LINE_PARS_model_*.json` |
| `FullFieldLineageBuilder` | `sdql/full_pars/` | Строит `FFL_*.json` для target-полей |
| `FullParsModelMdBuilder` | `sdql/md/` | Генерирует Markdown для `FULL_PARS_model` |
| `FullFieldLineageMdBuilder` | `sdql/md/` | Генерирует Markdown для каждого `FFL_*.json` |

---

## 7. Нефункциональные требования

### 7.1 Формат первичен
Выходной JSON-формат определяется примером (см. раздел 9). Новые поля добавляются только по согласованию.

### 7.2 Пример первее кода
`middle_example` генерируется до коммита кода. Артефакты коммитятся отдельно от кода.

### 7.3 Одноуровневая структура
`child_fields` конкретного поля содержит только прямых потомков (один уровень). Рекурсия — только в коде алгоритма `full_field_lineage`, выходной JSON — плоский.

### 7.4 Минимальные изменения
Не изменять существующие классы `LINE_PARS`, `field_lineage`, `SdqlCli` без необходимости. Новый код изолируется в пакетах `sdql/full_pars/` и `sdql/md/`.

---

## 8. Тестирование

### 8.1 Тест полной модели
`testFullParsModel()` — запускает CLI на `middle_example.sql`, проверяет наличие `FULL_PARS/FULL_PARS_model_middle_example.json` и корректность `field_id` + `child_fields`.

### 8.2 Тест полного lineage
`testFullFieldLineage()` — проверяет наличие `full_field_lineage/middle_example/74_ВТ_Суммы_ПР_ТранзитныеВиды/FFL_middle_example_74_ВТ_Суммы_ПР_ТранзитныеВиды_ЗадолженностьПенсии.json`.

### 8.3 Тест рекурсии и UNION
Проверяет, что lineage для `{74, ЗадолженностьПенсии}` включает:
- node 75 (SUB_1, виртуальный UNION-родитель)
- node 79 (UNION_3)
- node 70 (`ВТ_ТранзитныеВидыНачалоКонец`)
- Физическую таблицу `уп_ЗадолженностьПоПенсиям` (leaf, `node_id=null`)

### 8.4 Тест линейности JSON
Проверяет, что `FFL_*.json` — плоский массив узлов (нет вложенных `used_nodes` или `union_nodes`).

### 8.5 Тест расширения существующей ноды
Проверяет, что если нода добавляется дважды (из разных источников), она расширяется новыми полями в `select`, а не дублируется.

---

## 9. Примеры (middle_example, node 74, поле ЗадолженностьПенсии)

### 9.1 full_pars — Node 74

```json
{
  "id": 74,
  "name": "ВТ_Суммы_ПР_ТранзитныеВиды",
  "type": "temp_query",
  "select": [
    {
      "field_id": 1,
      "alias": "ЗадолженностьПенсии",
      "text": "СУММА(ПодЗапрос.ЗадолженностьПенсии)",
      "child_fields": [
        {
          "field_id": 2,
          "alias": "ЗадолженностьПенсии",
          "node_id": 75,
          "node_name": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1",
          "source": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1"
        }
      ]
    }
  ],
  "from": [
    {
      "alias": "ПодЗапрос",
      "subquery": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1"
    }
  ],
  "where": null,
  "where_fields": [],
  "group_by": [
    "ПодЗапрос.ПенсионныйСчет",
    "ПодЗапрос.ВидОбязательств",
    "ПодЗапрос.ДатаОпределенияОбязательств",
    "ПодЗапрос.ДатаОкончанияОбязательств",
    "ПодЗапрос.ПризнакИсхОстатка"
  ],
  "group_by_fields": [
    {
      "text": "ПодЗапрос.ПенсионныйСчет",
      "child_fields": [
        {
          "field_id": 3,
          "alias": "ПенсионныйСчет",
          "node_id": 75,
          "node_name": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1",
          "source": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1"
        }
      ]
    }
  ],
  "having": null,
  "having_fields": [],
  "joins": [],
  "join_conditions": [],
  "upquery_id": null,
  "subquery_ids": [75],
  "union_nodes_ids": [],
  "union_group_id": null,
  "union_type": null
}
```

### 9.2 full_pars — Node 79 (UNION_3)

```json
{
  "id": 79,
  "name": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1_UNION_3",
  "type": "union_query",
  "union_group_id": 75,
  "select": [
    {
      "field_id": 6,
      "alias": "ЗадолженностьПенсии",
      "text": "СУММА(ВЫБОР уп_ЗадолженностьПоПенсиям.ВидДвижения ... КОНЕЦ)",
      "child_fields": [
        {
          "field_id": null,
          "alias": "ВидДвижения",
          "node_id": null,
          "node_name": "уп_ЗадолженностьПоПенсиям",
          "source": "РегистрНакопления.уп_ЗадолженностьПоПенсиям"
        },
        {
          "field_id": null,
          "alias": "СуммаПенсии",
          "node_id": null,
          "node_name": "уп_ЗадолженностьПоПенсиям",
          "source": "РегистрНакопления.уп_ЗадолженностьПоПенсиям"
        },
        {
          "field_id": null,
          "alias": "СуммаДоплаты",
          "node_id": null,
          "node_name": "уп_ЗадолженностьПоПенсиям",
          "source": "РегистрНакопления.уп_ЗадолженностьПоПенсиям"
        }
      ]
    }
  ],
  "from": [
    {
      "table": "ВТ_ТранзитныеВидыНачалоКонец",
      "alias": "ВТ_ТранзитныеВидыНачалоКонец"
    }
  ],
  "where": null,
  "where_fields": [],
  "group_by": [
    "ВТ_ТранзитныеВидыНачалоКонец.ПенсионныйСчет",
    "ВТ_ТранзитныеВидыНачалоКонец.ВидОбязательств",
    "ВТ_ТранзитныеВидыНачалоКонец.ДатаОпределенияОбязательств",
    "ВТ_ТранзитныеВидыНачалоКонец.ДатаОкончанияОбязательств"
  ],
  "group_by_fields": [
    {
      "text": "ВТ_ТранзитныеВидыНачалоКонец.ПенсионныйСчет",
      "child_fields": [
        {
          "field_id": 10,
          "alias": "ПенсионныйСчет",
          "node_id": 70,
          "node_name": "ВТ_ТранзитныеВидыНачалоКонец",
          "source": "ВТ_ТранзитныеВидыНачалоКонец"
        }
      ]
    }
  ],
  "having": null,
  "having_fields": [],
  "joins": [
    {
      "join_type": "left",
      "source": {
        "table": "РегистрНакопления.уп_ЗадолженностьПоПенсиям",
        "alias": "уп_ЗадолженностьПоПенсиям"
      },
      "condition": "уп_ЗадолженностьПоПенсиям.НомерСчета = ВТ_ТранзитныеВидыНачалоКонец.ПенсионныйСчет"
    }
  ],
  "join_conditions": [
    {
      "join_type": "left",
      "source": "уп_ЗадолженностьПоПенсиям",
      "condition": "уп_ЗадолженностьПоПенсиям.НомерСчета = ВТ_ТранзитныеВидыНачалоКонец.ПенсионныйСчет",
      "condition_fields": [
        {
          "text": "уп_ЗадолженностьПоПенсиям.НомерСчета",
          "child_fields": [
            {
              "field_id": null,
              "alias": "НомерСчета",
              "node_id": null,
              "node_name": "уп_ЗадолженностьПоПенсиям",
              "source": "РегистрНакопления.уп_ЗадолженностьПоПенсиям"
            }
          ]
        },
        {
          "text": "ВТ_ТранзитныеВидыНачалоКонец.ПенсионныйСчет",
          "child_fields": [
            {
              "field_id": 10,
              "alias": "ПенсионныйСчет",
              "node_id": 70,
              "node_name": "ВТ_ТранзитныеВидыНачалоКонец",
              "source": "ВТ_ТранзитныеВидыНачалоКонец"
            }
          ]
        }
      ]
    }
  ]
}
```

### 9.3 full_field_lineage — {74, [ЗадолженностьПенсии]} (линейный массив)

FFL — плоский массив узлов. Порядок: от target-ноды к листьям (в порядке обхода).

```json
[
  {
    "id": 74,
    "name": "ВТ_Суммы_ПР_ТранзитныеВиды",
    "type": "temp_query",
    "select": [
      {
        "field_id": 1,
        "alias": "ЗадолженностьПенсии",
        "text": "СУММА(ПодЗапрос.ЗадолженностьПенсии)",
        "child_fields": [
          {
            "field_id": 2,
            "alias": "ЗадолженностьПенсии",
            "node_id": 75,
            "node_name": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1",
            "source": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1"
          }
        ]
      }
    ],
    "where_fields": [],
    "group_by_fields": [
      {
        "text": "ПодЗапрос.ПенсионныйСчет",
        "child_fields": [
          {
            "field_id": 3,
            "alias": "ПенсионныйСчет",
            "node_id": 75,
            "node_name": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1",
            "source": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1"
          }
        ]
      }
    ],
    "having_fields": [],
    "join_conditions": []
  },
  {
    "id": 75,
    "name": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1",
    "type": "sub_query",
    "select": [
      {
        "field_id": 2,
        "alias": "ЗадолженностьПенсии",
        "text": "ЗадолженностьПенсии",
        "child_fields": []
      }
    ],
    "where_fields": [],
    "group_by_fields": [],
    "having_fields": [],
    "join_conditions": [],
    "union_nodes_ids": [76, 77, 78, 79]
  },
  {
    "id": 79,
    "name": "ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1_UNION_3",
    "type": "union_query",
    "union_group_id": 75,
    "select": [
      {
        "field_id": 6,
        "alias": "ЗадолженностьПенсии",
        "text": "СУММА(ВЫБОР уп_ЗадолженностьПоПенсиям.ВидДвижения ... КОНЕЦ)",
        "child_fields": [
          {
            "field_id": null,
            "alias": "ВидДвижения",
            "node_id": null,
            "node_name": "уп_ЗадолженностьПоПенсиям",
            "source": "РегистрНакопления.уп_ЗадолженностьПоПенсиям"
          },
          {
            "field_id": null,
            "alias": "СуммаПенсии",
            "node_id": null,
            "node_name": "уп_ЗадолженностьПоПенсиям",
            "source": "РегистрНакопления.уп_ЗадолженностьПоПенсиям"
          },
          {
            "field_id": null,
            "alias": "СуммаДоплаты",
            "node_id": null,
            "node_name": "уп_ЗадолженностьПоПенсиям",
            "source": "РегистрНакопления.уп_ЗадолженностьПоПенсиям"
          }
        ]
      }
    ],
    "where_fields": [],
    "group_by_fields": [
      {
        "text": "ВТ_ТранзитныеВидыНачалоКонец.ПенсионныйСчет",
        "child_fields": [
          {
            "field_id": 10,
            "alias": "ПенсионныйСчет",
            "node_id": 70,
            "node_name": "ВТ_ТранзитныеВидыНачалоКонец",
            "source": "ВТ_ТранзитныеВидыНачалоКонец"
          }
        ]
      }
    ],
    "having_fields": [],
    "join_conditions": [
      {
        "join_type": "left",
        "source": "уп_ЗадолженностьПоПенсиям",
        "condition": "уп_ЗадолженностьПоПенсиям.НомерСчета = ВТ_ТранзитныеВидыНачалоКонец.ПенсионныйСчет",
        "condition_fields": [
          {
            "text": "уп_ЗадолженностьПоПенсиям.НомерСчета",
            "child_fields": [
              {
                "field_id": null,
                "alias": "НомерСчета",
                "node_id": null,
                "node_name": "уп_ЗадолженностьПоПенсиям",
                "source": "РегистрНакопления.уп_ЗадолженностьПоПенсиям"
              }
            ]
          },
          {
            "text": "ВТ_ТранзитныеВидыНачалоКонец.ПенсионныйСчет",
            "child_fields": [
              {
                "field_id": 10,
                "alias": "ПенсионныйСчет",
                "node_id": 70,
                "node_name": "ВТ_ТранзитныеВидыНачалоКонец",
                "source": "ВТ_ТранзитныеВидыНачалоКонец"
              }
            ]
          }
        ]
      }
    ]
  },
  {
    "id": 70,
    "name": "ВТ_ТранзитныеВидыНачалоКонец",
    "type": "temp_query",
    "select": [
      {
        "field_id": 10,
        "alias": "ПенсионныйСчет",
        "text": "...",
        "child_fields": [...]
      },
      {
        "field_id": 11,
        "alias": "ВидОбязательств",
        "text": "...",
        "child_fields": [...]
      },
      {
        "field_id": 12,
        "alias": "ДатаОпределенияОбязательств",
        "text": "...",
        "child_fields": [...]
      },
      {
        "field_id": 13,
        "alias": "ДатаОкончанияОбязательств",
        "text": "...",
        "child_fields": [...]
      }
    ],
    "where_fields": [...],
    "group_by_fields": [...],
    "having_fields": [],
    "join_conditions": [...]
  }
]
```

**Пояснение линейности:**
- Узел 74 ссылается на 75 (`child_fields[].node_id=75`)
- Узел 75 — виртуальный UNION-родитель, `select` содержит только `ЗадолженностьПенсии` (union_field), `union_nodes_ids=[76,77,78,79]`
- Узел 79 — UNION_3, единственная часть с ненулевым `ЗадолженностьПенсии`. Содержит `child_fields` на физическую таблицу (`node_id=null`) и на node 70 (`node_id=70`)
- Узел 70 — добавлен рекурсивно, `select` расширен полями из `group_by` (ПенсионныйСчет, ВидОбязательств и т.д.)
- Связи видны через `child_fields[].node_id`, вложенности нет

---

## 10. История изменений

| Версия | Дата | Автор | Изменения |
|--------|------|-------|-----------|
| 1.0 | 2026-06-03 | Kimi AI Agent | Первоначальная версия на основе BRD01 |
| 1.1 | 2026-06-03 | Пользователь | Внес замечания: alias как массив, структура вместо текста для where/join/group_by/having |
| 1.2 | 2026-06-03 | Kimi AI Agent | Уточнено: `_fields` параллельно текстовым полям; `field_id` только для `select`; FFL — плоский массив; UNION — виртуальный проброс; расширение существующих нод |

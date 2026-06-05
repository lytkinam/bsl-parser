# SRS03: Извлечение запроса из FULL_PARS и сверка с первичным

## 1. Введение

### 1.1 Цель

Определить требования к подсистеме извлечения SQL-запросов из полной структуры `FULL_PARS` и их сверки с первичными (исходными) запросами. Подсистема генерирует работающий SQL для каждого узла и проверяет его идентичность оригиналу.

### 1.2 Область применения

Модуль SDQL, пакет `sdql/query_extraction/`. Разработка в ветке `develop`, коммиты содержат `Refs Redmine #646`.

### 1.3 Термины

| Термин | Описание |
|--------|----------|
| **FULL_PARS** | Полная структура парсинга: LINE_PARS + field_id + child_fields для всех полей |
| **Query Extractor** | Компонент, извлекающий SQL из FULL_PARS |
| **Query Verifier** | Компонент, сверяющий извлечённый SQL с первичным |
| **Первичный запрос** | Исходный SQL-запрос из пакета (из `sdbl_parse_nodes_*.json` или `query_texts_*/node_N.sql`) |
| **Первичные поля** | Текстовые поля FULL_PARS: `where`, `group_by[]`, `having`, `from[].joins[]`, `into`, `order_by[]`, `limitations` |
| **_fields поля** | `where_fields`, `group_by_fields`, `having_fields`, `condition_fields` — используются только для разбора в full_pars, для извлечения SQL не нужны |
| **Sub query-нода** | Нода типа `sub_query`, используемая как inline-подзапрос в скобках внутри родительского `ИЗ`. Не генерирует отдельный SQL |
| **UNION-родитель** | Узел с `union_nodes_ids`. Если `type = "temp_query"` — генерирует `ПОМЕСТИТЬ` + UNION. Если `type = "sub_query"` — inline UNION в скобках родителя |

### 1.4 Ссылки

- `docs/brd_srs/BRD03 query_extraction_and_verification.md`
- `docs/brd_srs/SRS01 full_pars.md`
- `docs/brd_srs/SRS02 query_reconstruction.md`

---

## 2. Общее описание

### 2.1 Контекст

На входе подсистемы — результат работы `FullParsModelBuilder`: полная структура FULL_PARS со всеми узлами, полями и условиями. FULL_PARS **самодостаточен** для извлечения SQL: содержит все первичные текстовые поля, необходимые для построения запроса.

В отличие от BRD02/SRS02 (query_reconstruction), где запрос **урезается** под конкретное target-поле, BRD03/SRS03 извлекает **полный** запрос для каждого узла (все поля SELECT, все условия WHERE/GROUP BY/HAVING).

### 2.2 Пользователи

Разработчики 1С, аналитики запросов. Используют извлечённый запрос для:
- Проверки корректности парсинга (сверка с первичным)
- Получения полного текста отдельного запроса из пакета
- Отладки и тестирования

### 2.3 Ограничения

- **Формат первичен** — пример example_2 определяет структуру выходного SQL
- **Пример первее кода** — `example_2` генерируется до коммита кода
- **FULL_PARS — единственный источник** — не обращаться к `LINE_PARS_model` или `SDBL_PARS_model`
- **Минимальные изменения** — не изменять существующие модули full_pars

---

## 3. Функциональные требования

### 3.1 Входные данные

#### FR-3.1.1 FULL_PARS-файл
Путь: `FULL_PARS/FULL_PARS_model_<basename>.json`

FULL_PARS содержит плоский массив узлов. Каждый узел содержит первичные поля, необходимые для извлечения SQL.

#### FR-3.1.2 Первичный запрос
Путь: `SDBL_PARS/sdbl_parse_nodes_<basename>.json` или `SDBL_PARS/query_texts_<basename>/node_N.sql`

Первичный текст запроса используется для сверки с извлечённым.

### 3.2 Выходные данные

#### FR-3.2.1 Структура каталогов для извлечённых запросов
```
EXTRACTED_QUERIES/
└── <baseName>/
    └── <nodeId>_<nodeName>.sql
```

#### FR-3.2.2 Структура каталогов для отчёта сверки
```
VERIFICATION/
└── <baseName>/
    └── verification_report.json
```

#### FR-3.2.3 Формат SQL-файла
Один текстовый файл с SQL-запросом SDBL. Кодировка UTF-8. Без trailing `;` для sub_query, с `;` для остальных.

#### FR-3.2.4 Формат отчёта сверки
```json
{
  "baseName": "example_2",
  "totalNodes": 6,
  "matched": 6,
  "mismatched": 0,
  "nodes": [
    {
      "id": 0,
      "name": "ЗЛО",
      "status": "matched",
      "primaryHash": "sha256...",
      "extractedHash": "sha256..."
    },
    {
      "id": 4,
      "name": "Результат_2",
      "status": "mismatched",
      "differences": ["SELECT field order differs", "WHERE condition missing"]
    }
  ]
}
```

### 3.3 Режимы запуска

#### FR-3.3.1 Режим "извлечение всех запросов"
Вход: `--extract-all <baseName>` (по умолчанию)
Для каждого узла из FULL_PARS:
1. Извлекается SQL
2. Сверяется с первичным запросом
3. Результат записывается в `EXTRACTED_QUERIES/` и `VERIFICATION/`

#### FR-3.3.2 Режим "извлечение конкретного узла"
Вход: `--extract-node <nodeId>`
Извлекается SQL только для указанного узла. Сверка выполняется если первичный запрос доступен.

#### FR-3.3.3 Режим "только сверка"
Вход: `--verify-only <baseName>`
Только сверка существующих извлечённых запросов с первичными. Если `EXTRACTED_QUERIES/` отсутствует — ошибка.

### 3.4 Построение запроса для узла

#### FR-3.4.1 SELECT
Для каждого поля `select[]`:
- Вывод: `text КАК alias`
- Поля перечисляются через запятую с отступом
- Если `text` совпадает с `alias` (как в виртуальных UNION-родителях) — выводится только `alias` (без `КАК`)

#### FR-3.4.2 LIMITATIONS
Если `limitations != null` — добавляется перед `ВЫБРАТЬ`:
- `РАЗЛИЧНЫЕ` → `ВЫБРАТЬ РАЗЛИЧНЫЕ`
- `ПЕРВЫЕ N` → `ВЫБРАТЬ ПЕРВЫЕ N`

#### FR-3.4.3 INTO
Если `into != null` и `type == "temp_query"` — добавлять строку `ПОМЕСТИТЬ <into>` перед `FROM`.

#### FR-3.4.4 FROM
Восстанавливается из `from[]`:
- `table` — `ИЗ <table> КАК <alias>`
- `virtualTable` — `ИЗ <virtualTable> КАК <alias>` (текст с параметрами, как есть)
- `subquery` (String) — `ИЗ (<subquery_sql>) КАК <alias>`, где `<subquery_sql>` — извлечённый SQL соответствующей `sub_query`-ноды. Сопоставление выполняется через `subquery_ids` текущей ноды: среди нод из `subquery_ids` выбирается та, чьё `name` совпадает со значением `from[].subquery`
- `externalDataSource` — `ИЗ <externalDataSource> КАК <alias>`

Если несколько источников — через запятую.

#### FR-3.4.5 JOIN
Восстанавливается из `from[].joins[]` (полная структура):
- Тип: `ЛЕВОЕ СОЕДИНЕНИЕ`, `ПРАВОЕ СОЕДИНЕНИЕ`, `ПОЛНОЕ СОЕДИНЕНИЕ`, `ВНУТРЕННЕЕ СОЕДИНЕНИЕ`
- Источник: `<table/ВТ> КАК <alias>`
- Условие: `ПО <condition>`

**Не использовать** отдельный `joins[]` на уровне узла (он предназначен только для разбора).

#### FR-3.4.6 WHERE
Из поля `where` (строка):
- Блок: `ГДЕ <where>`
- **Не собирать** из `where_fields[].text`

#### FR-3.4.7 GROUP BY
Из поля `group_by[]` (массив строк):
- Блок: `СГРУППИРОВАТЬ ПО <field1>, <field2>, ...`
- **Не собирать** из `group_by_fields[].text`

#### FR-3.4.8 HAVING
Из поля `having` (строка):
- Блок: `ИМЕЮЩИЕ <having>`
- **Не собирать** из `having_fields[].text`

#### FR-3.4.9 ORDER BY
Из `order_by[]` (если присутствует):
- Блок: `УПОРЯДОЧИТЬ ПО <field1>, <field2>, ...`

### 3.5 Обработка UNION

#### FR-3.5.1 UNION-родитель
UNION-родитель — узел с непустым `union_nodes_ids`. Поведение зависит от `type`:
- `temp_query` — генерирует самостоятельный запрос: `ВЫБРАТЬ ... ПОМЕСТИТЬ <into>` + UNION-части
- `sub_query` — inline UNION-конструкция в скобках внутри родительского запроса; `ПОМЕСТИТЬ` не добавляется

#### FR-3.5.2 Построение UNION
Для UNION-родителя:
1. Для каждого `union_id` в `union_nodes_ids` получить запрос из FULL_PARS
2. Соединить через `ОБЪЕДИНИТЬ ВСЕ` (если `union_type = "union_all"`) или `ОБЪЕДИНИТЬ` (если `union_type = "union"`)
3. Если у узла есть `into` (`temp_query`) — вставить `ПОМЕСТИТЬ <into>` в нулевую UNION-часть **между SELECT и FROM** (перед первым `ИЗ`)
4. Если узел `sub_query` — вернуть UNION-конструкцию **без** `ПОМЕСТИТЬ` для inline-вставки

#### FR-3.5.3 SELECT для UNION-частей
Каждая часть UNION содержит полный набор полей из `select[]` FULL_PARS. Порядок полей — как в `select[]`.

#### FR-3.5.4 Subquery внутри UNION-частей
Если `union_query` в `from[].subquery` использует другой subquery — он также inline-ится в скобках внутри соответствующей UNION-части.

### 3.6 Топологическая сортировка

#### FR-3.6.1 Алгоритм
1. Построить граф зависимостей из `child_fields[].node_id` (исключая `null`)
2. Выполнить топологическую сортировку
3. **Пропустить `sub_query`-ноды** — они inline-ятся внутри родительских запросов и не генерируют отдельный SQL
4. Узлы без зависимостей (только физические таблицы) — не создают отдельных запросов
5. Порядок вывода: от листьев к корню

### 3.7 Сверка с первичным запросом

#### FR-3.7.1 Получение первичного запроса
Первичный запрос получается из:
1. `SDBL_PARS/sdbl_parse_nodes_<basename>.json` — поле `text` узла с соответствующим `id`
2. Или `SDBL_PARS/query_texts_<basename>/node_<sdblId>.sql` — если nodes.json недоступен

#### FR-3.7.2 Нормализация текста
Для сверки оба текста (извлечённый и первичный) нормализуются:
1. Удаление лишних пробелов (сворачивание multiple spaces в один)
2. Удаление переносов строк (замена на пробел)
3. Приведение к верхнему регистру
4. Удаление комментариев (`// ...`, `/* ... */`)
5. Нормализация алиасов: если в SELECT поле `Таблица.Поле` без `КАК` — считать alias = `Поле`

#### FR-3.7.3 Сравнение
Нормализованные тексты сравниваются посимвольно.

#### FR-3.7.4 Результат сверки
- `matched` — тексты совпадают после нормализации
- `mismatched` — тексты различаются
  - Для mismatched: вычисляется diff (первые 3 отличия)
  - Различия записываются в `differences[]` отчёта

### 3.8 Исключения

#### FR-3.8.1 Физические таблицы
Узлы, которые являются leaf (все `child_fields` имеют `node_id=null`), не порождают отдельных запросов.

#### FR-3.8.2 DROP-запросы
`drop_query` не включаются.

#### FR-3.8.3 Виртуальные UNION-родители
Виртуальные UNION-родители (узлы с `union_nodes_ids`, очищенные `from`/`where`/`groupBy`) не генерируют отдельный SQL. SQL генерируется для их UNION-частей.

---

## 4. Структуры данных

### 4.1 ExtractedQueryNode

Внутренняя структура для построения SQL (идентична RestoredQueryNode из SRS02):

```java
class ExtractedQueryNode {
    int id;
    String name;
    String type;
    String into;
    String limitations;     // "РАЗЛИЧНЫЕ", "ПЕРВЫЕ N"
    List<String> selectExpressions;
    List<DataSource> from;  // из FULL_PARS
    List<RestoredJoin> joins;
    String where;           // из FULL_PARS (строка)
    List<String> groupBy;   // из FULL_PARS (массив строк)
    String having;          // из FULL_PARS (строка)
    List<String> orderBy;   // из FULL_PARS
    boolean isUnionParent; // true для узлов с непустым union_nodes_ids
    List<Integer> unionNodeIds;
    String unionType;
}
```

### 4.2 VerificationReport

```java
class VerificationReport {
    String baseName;
    int totalNodes;
    int matched;
    int mismatched;
    List<NodeVerificationResult> nodes;
}
```

### 4.3 NodeVerificationResult

```java
class NodeVerificationResult {
    int id;
    String name;
    String status; // "matched" | "mismatched" | "skipped"
    String primaryHash;      // sha256 нормализованного первичного текста
    String extractedHash;    // sha256 нормализованного извлечённого текста
    List<String> differences; // только для mismatched
}
```

---

## 5. Алгоритмы

### 5.1 Общий пайплайн

```
1. Загрузить FULL_PARS_model_<basename>.json
2. Загрузить sdbl_parse_nodes_<basename>.json (для получения первичных текстов)
3. Для каждого узла из FULL_PARS:
   a. Построить граф зависимостей из child_fields[].node_id
   b. Топологическая сортировка → List<nodeId> (sub_query-ноды пропускаются)
   c. Для каждого nodeId построить SQL из первичных полей FULL_PARS
      - При построении FROM для from[].subquery — inline SQL соответствующей sub_query-ноды
      - Если nodeId имеет union_nodes_ids и type == "temp_query" — вставить ПОМЕСТИТЬ <into> в UNION_0
   d. Записать в EXTRACTED_QUERIES/<baseName>/<nodeId>_<nodeName>.sql
   e. Получить первичный текст для данного sdblId
   f. Нормализовать оба текста
   g. Сравнить, записать результат в отчёт
4. Записать VERIFICATION/<baseName>/verification_report.json
```

### 5.2 Извлечение SQL для узла

```
function extractQuery(node, fullPars):
    if node.type == "sub_query":
        // sub_query inline-ится в родителя
        return buildQuery(node, fullPars)  // без ПОМЕСТИТЬ и без ;
    
    if node.union_nodes_ids != null && !node.union_nodes_ids.isEmpty():
        return buildUnionQuery(node, fullPars)
    
    return buildQuery(node, fullPars)
```

### 5.3 Построение UNION

```
function buildUnionQuery(unionParent, fullPars):
    union_queries = []
    for i, union_id in enumerate(unionParent.union_nodes_ids):
        union_node = fullPars.find(union_id)
        union_sql = buildQuery(union_node, fullPars)
        union_queries.add(union_sql)
    
    separator = unionParent.union_type == "union_all"
        ? "\n\nОБЪЕДИНИТЬ ВСЕ\n\n"
        : "\n\nОБЪЕДИНИТЬ\n\n"
    
    union_body = join(union_queries, separator)
    
    // temp_query с into — вставляем ПОМЕСТИТЬ в UNION_0
    if unionParent.type == "temp_query" and unionParent.into != null and union_queries not empty:
        union_queries[0] = insertBeforeFirstFrom(union_queries[0], "\nПОМЕСТИТЬ " + unionParent.into)
        union_body = join(union_queries, separator)
    
    return union_body
```

### 5.4 Построение FROM + JOIN

```
function findSubQueryNode(ds, parentNode, fullPars):
    if ds.subquery == null:
        return null
    for subId in parentNode.subquery_ids:
        candidate = fullPars.find(subId)
        if candidate != null and candidate.name == ds.subquery:
            return candidate
    return null

function resolveSource(ds, parentNode, fullPars):
    if ds.table != null:
        return ds.table
    if ds.virtualTable != null:
        return ds.virtualTable
    if ds.subquery != null:
        subNode = findSubQueryNode(ds, parentNode, fullPars)
        subSql = extractQuery(subNode, fullPars)  // без ПОМЕСТИТЬ и без ;
        return "(" + subSql + ")"
    if ds.externalDataSource != null:
        return ds.externalDataSource
    return "?"

from_parts = []
for ds in node.from:
    from_parts.add(resolveSource(ds, node, fullPars) + " КАК " + ds.alias)

from_block = "ИЗ\n    " + join(from_parts, ",\n    ")

join_lines = []
for ds in node.from:
    if ds.joins != null:
        for j in ds.joins:
            join_type = mapJoinType(j.joinType)
            source_table = resolveSource(j.source, node, fullPars)
            line = join_type + " " + source_table + " КАК " + j.source.alias
                + "\n    ПО " + j.condition
            join_lines.add(line)

join_block = join_lines.isEmpty() ? "" : "\n    " + join(join_lines, "\n    ")
```

### 5.5 Нормализация текста для сверки

```
function normalize(text):
    // Удаление комментариев
    text = removeComments(text)
    // Замена переносов строк на пробелы
    text = text.replace("\n", " ").replace("\r", " ")
    // Сворачивание multiple spaces
    text = text.replaceAll("\\s+", " ")
    // Приведение к верхнему регистру
    text = text.toUpperCase()
    // Удаление trailing/leading spaces
    text = text.trim()
    return text

function removeComments(text):
    // Удаление // comments
    text = text.replaceAll("//[^\\n]*", "")
    // Удаление /* */ comments
    text = text.replaceAll("/\\*.*?\\*/", "")
    return text
```

### 5.6 Сверка

```
function verify(node, extractedSql, primaryText):
    extractedNormalized = normalize(extractedSql)
    primaryNormalized = normalize(primaryText)
    
    extractedHash = sha256(extractedNormalized)
    primaryHash = sha256(primaryNormalized)
    
    if extractedNormalized.equals(primaryNormalized):
        return {status: "matched", primaryHash, extractedHash}
    else:
        differences = computeDiff(extractedNormalized, primaryNormalized, 3)
        return {status: "mismatched", primaryHash, extractedHash, differences}
```

---

## 6. CLI

### 6.1 Параметры командной строки

```
QueryExtractor <baseName> [options]

Options:
  --extract-all               Извлечь все запросы (по умолчанию)
  --extract-node <nodeId>     Извлечь конкретный узел
  --verify-only               Только сверка (без извлечения)
  --output-dir <path>         Каталог для выходных файлов (по умолчанию: EXTRACTED_QUERIES)
  --primary-source <path>     Путь к первичным запросам (по умолчанию: SDBL_PARS)
```

### 6.2 Примеры вызова

```bash
# Извлечь все запросы и сверить
java QueryExtractor example_2

# Извлечь конкретный узел
java QueryExtractor example_2 --extract-node 0

# Только сверка
java QueryExtractor example_2 --verify-only
```

### 6.3 Новые классы

| Класс | Пакет | Назначение |
|-------|-------|-----------|
| `QueryExtractor` | `sdql/query_extraction/` | Оркестратор: CLI, режимы, запуск |
| `QueryBuilder` | `sdql/query_extraction/` | Строит `ExtractedQueryNode` из FULL_PARS |
| `SqlGenerator` | `sdql/query_extraction/` | Генерирует текст SDBL (переиспользуется из query_reconstruction) |
| `TopologicalSorter` | `sdql/query_extraction/` | Топологическая сортировка (переиспользуется из query_reconstruction) |
| `QueryVerifier` | `sdql/query_extraction/` | Сверка извлечённого SQL с первичным |
| `TextNormalizer` | `sdql/query_extraction/` | Нормализация текста для сверки |

---

## 7. Примеры (example_2)

### 7.1 Вход

`FULL_PARS_model_example_2.json`

### 7.2 Выход

`EXTRACTED_QUERIES/example_2/0_ЗЛО.sql`:
```sql
ВЫБРАТЬ
    уп_ЗаявлениеЗЛОЕдиновременнойВыплате.Ссылка КАК Ссылка,
    уп_ЗаявлениеЗЛОЕдиновременнойВыплате.Участник КАК Получатель,
    уп_ЗаявлениеЗЛОЕдиновременнойВыплате.ДоговорОПС КАК ДоговорОПС
ПОМЕСТИТЬ ЗЛО
ИЗ
    Документ.уп_ЗаявлениеЗЛОЕдиновременнойВыплате КАК уп_ЗаявлениеЗЛОЕдиновременнойВыплате

ОБЪЕДИНИТЬ ВСЕ

ВЫБРАТЬ
    уп_ЗаявлениеЗЛОНазначенииНЧТП.Ссылка КАК Ссылка,
    уп_ЗаявлениеЗЛОНазначенииНЧТП.Получатель КАК Получатель,
    уп_ЗаявлениеЗЛОНазначенииНЧТП.ДоговорОПС КАК ДоговорОПС
ИЗ
    Документ.уп_ЗаявлениеЗЛОНазначенииНЧТП КАК уп_ЗаявлениеЗЛОНазначенииНЧТП

ОБЪЕДИНИТЬ ВСЕ

ВЫБРАТЬ
    уп_ЗаявлениеЗЛОНазначенииСрочнойВыплаты.Ссылка КАК Ссылка,
    уп_ЗаявлениеЗЛОНазначенииСрочнойВыплаты.Получатель КАК Получатель,
    уп_ЗаявлениеЗЛОНазначенииСрочнойВыплаты.ДоговорОПС КАК ДоговорОПС
ИЗ
    Документ.уп_ЗаявлениеЗЛОНазначенииСрочнойВыплаты КАК уп_ЗаявлениеЗЛОНазначенииСрочнойВыплаты
```

`EXTRACTED_QUERIES/example_2/4_Результат_2.sql`:
```sql
ВЫБРАТЬ
    ВложенныйЗапрос.Ссылка КАК Ссылка,
    ВложенныйЗапрос.Получатель КАК Получатель,
    Контрагенты.ИНН КАК ИНН
ИЗ
    (
        ВЫБРАТЬ
            ЗЛО.Ссылка КАК Ссылка,
            ЗЛО.Получатель КАК Получатель,
            ЗЛО.ДоговорОПС КАК ДоговорОПС
        ИЗ
            ЗЛО КАК ЗЛО
        ГДЕ
            ЗЛО.Получатель.ЭтоГруппа = &ЭтоГруппа
    ) КАК ВложенныйЗапрос
        ЛЕВОЕ СОЕДИНЕНИЕ Справочник.Контрагенты КАК Контрагенты
        ПО ВложенныйЗапрос.Получатель = Контрагенты.Ссылка
```

### 7.3 Результат сверки

`VERIFICATION/example_2/verification_report.json`:
```json
{
  "baseName": "example_2",
  "totalNodes": 6,
  "matched": 6,
  "mismatched": 0,
  "nodes": [
    {
      "id": 0,
      "name": "ЗЛО",
      "status": "matched",
      "primaryHash": "sha256...",
      "extractedHash": "sha256..."
    },
    {
      "id": 1,
      "name": "ЗЛО_UNION_0",
      "status": "matched",
      "primaryHash": "sha256...",
      "extractedHash": "sha256..."
    },
    {
      "id": 2,
      "name": "ЗЛО_UNION_1",
      "status": "matched",
      "primaryHash": "sha256...",
      "extractedHash": "sha256..."
    },
    {
      "id": 3,
      "name": "ЗЛО_UNION_2",
      "status": "matched",
      "primaryHash": "sha256...",
      "extractedHash": "sha256..."
    },
    {
      "id": 4,
      "name": "Результат_2",
      "status": "matched",
      "primaryHash": "sha256...",
      "extractedHash": "sha256..."
    },
    {
      "id": 5,
      "name": "Результат_2_SUB_1",
      "status": "matched",
      "primaryHash": "sha256...",
      "extractedHash": "sha256..."
    }
  ]
}
```

---

## 8. Нефункциональные требования

### 8.1 Формат первичен
Выходной SQL определяется примером (раздел 7). Новые конструкции добавляются только по согласованию.

### 8.2 Пример первее кода
`example_2` генерируется до коммита кода. Артефакты коммитятся отдельно от кода.

### 8.3 Читаемость
Извлечённый SQL должен быть отформатирован с отступами (как в оригинальных `query_texts_*.sql`).

### 8.4 Переиспользование
Где возможно, переиспользовать классы из `sdql/query_reconstruction/`:
- `SqlGenerator` — генерация SQL
- `TopologicalSorter` — топологическая сортировка
- `QueryNodeBuilder` — построение внутренней структуры узла

---

## 9. Тестирование

### 9.1 Тест наличия файлов
`testExtractedQueryFiles()` — проверяет наличие `EXTRACTED_QUERIES/example_2/0_ЗЛО.sql` и `EXTRACTED_QUERIES/example_2/4_Результат_2.sql`.

### 9.2 Тест синтаксиса
Проверяет, что извлечённый SQL содержит корректные блоки: `ВЫБРАТЬ`, `ИЗ`, `ПОМЕСТИТЬ`, `СГРУППИРОВАТЬ ПО`.

### 9.3 Тест сверки
Проверяет, что `verification_report.json` содержит `matched` для всех узлов `example_2`.

### 9.4 Тест UNION
Проверяет, что UNION-конструкция извлекается корректно с `ОБЪЕДИНИТЬ ВСЕ`.

### 9.5 Тест inline-subquery
Проверяет, что `sub_query`-ноды inline-ятся в скобках внутри родительского `ИЗ`.

### 9.6 Тест нормализации
Проверяет, что нормализация корректно обрабатывает пробелы, переносы строк и комментарии.

---

## 10. История изменений

| Версия | Дата | Автор | Изменения |
|--------|------|-------|-----------|
| 1.0 | 2026-06-05 | Kimi AI Agent | Первоначальная версия |

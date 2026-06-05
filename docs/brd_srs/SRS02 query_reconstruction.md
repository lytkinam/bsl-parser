# SRS02: Восстановление урезанного запроса для выполнения в 1С

## 1. Введение

### 1.1 Цель

Определить требования к подсистеме восстановления SQL-запросов 1С (SDBL) на основании урезанной структуры `full_field_lineage` (FFL). Подсистема генерирует работающий пакет запросов, который можно выполнить в консоли запросов 1С или встроить в код конфигурации.

### 1.2 Область применения

Модуль SDQL, пакет `sdql/query_reconstruction/`. Разработка в ветке `develop`, коммиты содержат `Refs Redmine #646`.

### 1.3 Термины

| Термин | Описание |
|--------|----------|
| **FFL** | `full_field_lineage` — плоский массив узлов, урезанный под конкретное поле. **Единственный источник данных** для восстановления |
| **Query Reconstructor** | Компонент, преобразующий FFL в текст SDBL |
| **Restored Query** | Восстановленный SQL-файл, готовый к выполнению |
| **Первичные поля** | Текстовые поля FFL: `where`, `group_by[]`, `having`, `from[].joins[]`, `into`, `order_by[]`, `limitations` |
| **_fields поля** | `where_fields`, `group_by_fields`, `having_fields`, `condition_fields` — используются только для разбора в full_pars, для восстановления SQL не нужны |
| **Sub query-нода** | Нода типа `sub_query`, используемая как inline-подзапрос в скобках внутри родительского `ИЗ`. Не генерирует отдельный SQL |
| **UNION-родитель** | Узел с `union_nodes_ids`. Если `type = "temp_query"` — генерирует `ПОМЕСТИТЬ` + UNION. Если `type = "sub_query"` — inline UNION в скобках родителя |
| **Режим запуска** | Один из 4 способов вызова CLI (поле, таблица, пакет, пересоздание) |

### 1.4 Ссылки

- `docs/brd_srs/BRD02 query_reconstruction.md`
- `docs/brd_srs/SRS01 full_pars.md`

---

## 2. Общее описание

### 2.1 Контекст

На входе подсистемы — результат работы `full_field_lineage`: плоский массив узлов, содержащий только поля, влияющие на target-поле. FFL **самодостаточен**: содержит все первичные текстовые поля, необходимые для построения SQL.

### 2.2 Пользователи

Разработчики 1С, аналитики запросов. Используют восстановленный запрос для отладки, тестирования или извлечения подмножества данных.

### 2.3 Ограничения

- **Формат первичен** — пример middle_example определяет структуру выходного SQL
- **Пример первее кода** — `middle_example` генерируется до коммита кода
- **FFL — единственный источник** — не обращаться к `FULL_PARS_model` или `LINE_PARS_model`
- **Минимальные изменения** — не изменять существующие модули full_pars

---

## 3. Функциональные требования

### 3.1 Входные данные

#### FR-3.1.1 FFL-файл
Путь: `full_field_lineage/<baseName>/<nodeId>_<nodeName>/FFL_<baseName>_<nodeId>_<nodeName>_<alias>.json`

FFL содержит плоский массив узлов. Каждый узел содержит первичные поля, необходимые для восстановления SQL.

### 3.2 Выходные данные

#### FR-3.2.1 Структура каталогов
```
RESTORED_QUERIES/
└── <baseName>/
    └── <nodeId>_<nodeName>/
        └── <alias>.sql
```

#### FR-3.2.2 Формат SQL-файла
Один текстовый файл с пакетом запросов SDBL, разделённых символом `;`. Кодировка UTF-8.

### 3.3 Режимы запуска

#### FR-3.3.1 Режим "конкретное поле"
Вход: `--field <nodeId>:<alias>`  
Если FFL-файл существует — читается и восстанавливается SQL.  
Если FFL-файл **отсутствует** — автоматически запускается `FullFieldLineageBuilder` для `{nodeId, [alias]}`, создаётся FFL, затем восстанавливается SQL.  
Выход: один SQL-файл.

#### FR-3.3.2 Режим "конкретная таблица"
Вход: `--table <nodeId>` или `--table <nodeName>`  
Берётся `FullParsNode` из `FULL_PARS_model` (для определения списка полей `select[]`).  
Для каждого поля `select[]` данной ноды:
- Если FFL отсутствует — создаётся через `FullFieldLineageBuilder`
- Восстанавливается SQL
Выход: набор SQL-файлов (по одному на поле) в `RESTORED_QUERIES/<baseName>/<nodeId>_<nodeName>/`.

#### FR-3.3.3 Режим "общий запрос" (пакет)
Вход: `--package <baseName>` (или без явного `--field`/`--table`)  
Определяются target-ноды:
1. Все `result` узлы из `FULL_PARS_model`
2. Если нет — последний `temp_query` (максимальный `id`)
3. Если нет — последний UNION-родитель (`temp_query` с непустым `union_nodes_ids`, максимальный `id`)

Для каждой target-ноды и каждого её поля `select[]` — создаётся/читается FFL и восстанавливается SQL.  
Выход: полный набор SQL-файлов для всех target-полей.

#### FR-3.3.4 Режим "пересоздание зависимостей"
Флаг: `--recreate-deps`  
Может комбинироваться с любым из режимов выше.  
При восстановлении SQL для каждой ВТ (`temp_query`) в начало пакета добавляется:
```sql
УНИЧТОЖИТЬ <имя_ВТ>;
```
перед её созданием через `ПОМЕСТИТЬ`.  
Это последний шаг в цепочке: изменение первичного SQL → перегенерация SDBL_PARS/LINE_PARS/FULL_PARS/FFL → восстановление SQL с `--recreate-deps`.

### 3.4 Построение запроса для узла

#### FR-3.4.1 SELECT
Для каждого поля `select[]`:
- Вывод: `text КАК alias`
- Поля перечисляются через запятую с отступом

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
- `subquery` (String) — `ИЗ (<subquery_sql>) КАК <alias>`, где `<subquery_sql>` — восстановленный SQL соответствующей `sub_query`-ноды. Сопоставление выполняется через `subquery_ids` текущей ноды: среди нод из `subquery_ids` выбирается та, чьё `name` совпадает со значением `from[].subquery`
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
1. Для каждого `union_id` в `union_nodes_ids` получить запрос из FFL
2. Соединить через `ОБЪЕДИНИТЬ ВСЕ` (если `union_type = "union_all"`) или `ОБЪЕДИНИТЬ` (если `union_type = "union"`)
3. Если у узла есть `into` (`temp_query`) — вставить `ПОМЕСТИТЬ <into>` в нулевую UNION-часть **между SELECT и FROM** (перед первым `ИЗ`)
4. Если узел `sub_query` — вернуть UNION-конструкцию **без** `ПОМЕСТИТЬ` для inline-вставки

#### FR-3.5.3 SELECT для UNION-частей
Каждая часть UNION содержит полный набор полей из `select[]` FFL. Порядок полей — как в `select[]`.

#### FR-3.5.4 Subquery внутри UNION-частей
Если `union_query` в `from[].subquery` использует другой subquery — он также inline-ится в скобках внутри соответствующей UNION-части.

### 3.6 Топологическая сортировка

#### FR-3.6.1 Алгоритм
1. Построить граф зависимостей из `child_fields[].node_id` (исключая `null`)
2. Выполнить топологическую сортировку
3. **Пропустить `sub_query`-ноды** — они inline-ятся внутри родительских запросов и не генерируют отдельный SQL
4. Узлы без зависимостей (только физические таблицы) — не создают отдельных запросов
5. Порядок вывода: от листьев к корню

### 3.7 Исключения

#### FR-3.7.1 Физические таблицы
Узлы, которые являются leaf (все `child_fields` имеют `node_id=null`), не порождают отдельных запросов.

#### FR-3.7.2 DROP-запросы
`drop_query` не включаются (кроме режима `--recreate-deps`).

---

## 4. Структуры данных

### 4.1 RestoredQueryNode

Внутренняя структура для построения SQL:

```java
class RestoredQueryNode {
    int id;
    String name;
    String type;
    String into;
    String limitations;     // "РАЗЛИЧНЫЕ", "ПЕРВЫЕ N"
    List<String> selectExpressions;
    List<DataSource> from;  // из FFL
    List<RestoredJoin> joins;
    String where;           // из FFL (строка)
    List<String> groupBy;   // из FFL (массив строк)
    String having;          // из FFL (строка)
    List<String> orderBy;   // из FFL
    boolean isUnionParent; // true для узлов с непустым union_nodes_ids
    List<Integer> unionNodeIds;
    String unionType;
}
```

### 4.2 RestoredJoin

```java
class RestoredJoin {
    String joinType;    // left, right, full, inner
    DataSource source;  // из from[].joins[].source
    String condition;   // из from[].joins[].condition
}
```

---

## 5. Алгоритмы

### 5.1 Общий пайплайн

```
1. Определить режим запуска (CLI-параметры)
2. В зависимости от режима:
   a. --field: создать/прочитать FFL для одного поля
   b. --table: получить список полей ноды, для каждого создать/прочитать FFL
   c. --package: найти target-ноды, для каждой ноды и каждого поля создать/прочитать FFL
3. Для каждого FFL:
   a. Построить граф зависимостей из child_fields[].node_id с учётом subquery_ids
   b. Топологическая сортировка → List<nodeId> (`sub_query`-ноды пропускаются, они inline-ятся)
   c. Для каждого nodeId построить SQL из первичных полей FFL
      - При построении FROM для `from[].subquery` — inline SQL соответствующей `sub_query`-ноды (поиск по subquery_ids)
      - Если nodeId имеет `union_nodes_ids` и `type == "temp_query"` — вставить `ПОМЕСТИТЬ <into>` в UNION_0
   d. Если --recreate-deps: добавить УНИЧТОЖИТЬ для каждой ВТ
   e. Объединить тексты через ";\n\n"
   f. Записать в <alias>.sql
```

### 5.2 Создание FFL при отсутствии

```
function ensureFfl(baseName, nodeId, alias):
    fflPath = full_field_lineage/<baseName>/<nodeId>_<nodeName>/FFL_...json
    if not exists(fflPath):
        full_pars_path = FULL_PARS/FULL_PARS_model_<baseName>.json
        hierarchy_path = LINE_PARS/LINE_PARS_hierarchy_<baseName>.json
        run FullFieldLineageBuilder with {nodeId, [alias]}
    return fflPath
```

### 5.3 Определение target-нод (режим --package)

```
function findTargetNodes(fullParsModel):
    resultNodes = filter(fullParsModel.nodes, n -> n.type == "result")
    if not empty(resultNodes):
        return resultNodes
    
    tempQueries = filter(fullParsModel.nodes, n -> n.type == "temp_query")
    if not empty(tempQueries):
        return [maxBy(tempQueries, n -> n.id)]
    
    unionParents = filter(fullParsModel.nodes, n -> n.union_nodes_ids != null && !n.union_nodes_ids.isEmpty())
    if not empty(unionParents):
        return [maxBy(unionParents, n -> n.id)]
    
    return []
```

### 5.4 Построение SELECT

```
select_lines = []
for field in node.select:
    line = field.text + " КАК " + field.alias
    select_lines.add(line)

limitations = node.limitations != null ? node.limitations + " " : ""
select_block = "ВЫБРАТЬ " + limitations + "\n    " + join(select_lines, ",\n    ")
```

### 5.5 Построение UNION

```
function buildUnionQuery(unionParent, ffl):
    union_queries = []
    for i, union_id in enumerate(unionParent.union_nodes_ids):
        union_node = ffl.find(union_id)
        union_sql = buildQuery(union_node)  // может содержать inline subquery
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

### 5.6 Построение FROM + JOIN

```
function findSubQueryNode(ds, parentNode, ffl):
    if ds.subquery == null:
        return null
    for subId in parentNode.subquery_ids:
        candidate = ffl.find(subId)
        if candidate != null and candidate.name == ds.subquery:
            return candidate
    return null

function resolveSource(ds, parentNode, ffl):
    if ds.table != null:
        return ds.table
    if ds.virtualTable != null:
        return ds.virtualTable
    if ds.subquery != null:
        subNode = findSubQueryNode(ds, parentNode, ffl)  // ищем по subquery_ids
        subSql = buildSubQuery(subNode, ffl)             // без ПОМЕСТИТЬ и без ;
        return "(" + subSql + ")"
    if ds.externalDataSource != null:
        return ds.externalDataSource
    return "?"

from_parts = []
for ds in node.from:
    from_parts.add(resolveSource(ds, node, ffl) + " КАК " + ds.alias)

from_block = "ИЗ\n    " + join(from_parts, ",\n    ")

join_lines = []
for ds in node.from:
    if ds.joins != null:
        for j in ds.joins:
            join_type = mapJoinType(j.joinType)
            source_table = resolveSource(j.source, node, ffl)
            line = join_type + " " + source_table + " КАК " + j.source.alias
                + "\n    ПО " + j.condition
            join_lines.add(line)

join_block = join_lines.isEmpty() ? "" : "\n    " + join(join_lines, "\n    ")
```

### 5.7 Построение WHERE / GROUP BY / HAVING

```
where_block = node.where != null ? "\nГДЕ " + node.where : ""
group_by_block = node.group_by != null && !node.group_by.isEmpty()
    ? "\nСГРУППИРОВАТЬ ПО\n    " + join(node.group_by, ",\n    ")
    : ""
having_block = node.having != null ? "\nИМЕЮЩИЕ " + node.having : ""
order_by_block = node.order_by != null && !node.order_by.isEmpty()
    ? "\nУПОРЯДОЧИТЬ ПО\n    " + join(node.order_by, ",\n    ")
    : ""
```

### 5.8 Пересоздание зависимостей (--recreate-deps)

```
if recreateDeps:
    for node in topological_order:
        if node.into != null and node.type != "sub_query":
            drop_statement = "УНИЧТОЖИТЬ " + node.into + ";"
            insert_before(node.query, drop_statement)
```

**Важно:** `УНИЧТОЖИТЬ` вставляется перед соответствующим `ПОМЕСТИТЬ`. Для UNION-конструкций `ПОМЕСТИТЬ` находится внутри UNION_0, поэтому `УНИЧТОЖИТЬ` вставляется перед всей UNION-конструкцией.

### 5.9 Топологическая сортировка с учётом sub_query

```
function buildTopologicalOrder(fflNodes):
    graph = {}
    for node in fflNodes:
        if node.type == "sub_query":
            // Sub query inline-ится в родителя, отдельный SQL не генерирует
            // Но зависимости sub_query учитываем через ребро от зависимостей к родителям
            continue
        
        for field in node.select + node.where_fields + node.group_by_fields + node.having_fields:
            for child in field.child_fields:
                if child.node_id != null:
                    graph.addEdge(child.node_id, node.id)
    
    // Добавляем зависимости sub_query-нод к их родителям через parent.subquery_ids
    for parent in fflNodes:
        if parent.subquery_ids != null:
            for subId in parent.subquery_ids:
                subNode = ffl.find(subId)
                if subNode != null:
                    // Все зависимости subNode должны быть созданы до parent
                    for field in subNode.select + subNode.where_fields + subNode.group_by_fields + subNode.having_fields:
                        for child in field.child_fields:
                            if child.node_id != null:
                                graph.addEdge(child.node_id, parent.id)
    
    return topologicalSort(graph)
```

---

## 6. CLI

### 6.1 Параметры командной строки

```
QueryReconstructor <baseName> [options]

Options:
  --field <nodeId>:<alias>    Режим "конкретное поле"
  --table <nodeId>            Режим "конкретная таблица"
  --package                   Режим "общий запрос" (по умолчанию, если нет --field/--table)
  --recreate-deps             Добавить УНИЧТОЖИТЬ для всех ВТ
  --output-dir <path>         Каталог для выходных файлов (по умолчанию: RESTORED_QUERIES)
```

### 6.2 Примеры вызова

```bash
# Конкретное поле
java QueryReconstructor middle_example --field 74:ЗадолженностьПенсии

# Конкретная таблица
java QueryReconstructor middle_example --table 74

# Общий запрос
java QueryReconstructor middle_example --package

# Пересоздание с зависимостями
java QueryReconstructor middle_example --field 74:ЗадолженностьПенсии --recreate-deps
```

### 6.3 Новые классы

| Класс | Пакет | Назначение |
|-------|-------|-----------|
| `QueryReconstructor` | `sdql/query_reconstruction/` | Оркестратор: CLI, режимы, запуск |
| `QueryNodeBuilder` | `sdql/query_reconstruction/` | Строит `RestoredQueryNode` из FFL |
| `SqlGenerator` | `sdql/query_reconstruction/` | Генерирует текст SDBL |
| `TopologicalSorter` | `sdql/query_reconstruction/` | Топологическая сортировка |
| `FflEnsurer` | `sdql/query_reconstruction/` | Проверяет/создаёт FFL при отсутствии |
| `TargetNodeSelector` | `sdql/query_reconstruction/` | Выбирает target-ноды для режима --package |
| `UnionBuilder` | `sdql/query_reconstruction/` | Строит UNION-конструкции с переносом `into` |

---

## 7. Примеры (middle_example, node 74, ЗадолженностьПенсии)

### 7.1 Вход

`FFL_middle_example_74_ВТ_Суммы_ПР_ТранзитныеВиды_ЗадолженностьПенсии.json`

### 7.2 Выход

`RESTORED_QUERIES/middle_example/74_ВТ_Суммы_ПР_ТранзитныеВиды/ЗадолженностьПенсии.sql`

```sql
ВЫБРАТЬ РАЗЛИЧНЫЕ
    ВТ_ПенсионныеСчета_ПР.ПенсионныйСчет КАК ПенсионныйСчет
ПОМЕСТИТЬ ВТ_ПенсионныеСчета
ИЗ
    ВТ_ПенсионныеСчета_ПР КАК ВТ_ПенсионныеСчета_ПР
ГДЕ
    НЕ ВТ_ПенсионныеСчета_ПР.ПенсионныйСчет.ПометкаУдаления
;

ВЫБРАТЬ
    ВТ_ПенсионныеСчета.ПенсионныйСчет КАК ПенсионныйСчет,
    ВЫБОР
        ...
    КОНЕЦ КАК ВидОбязательствВход,
    ВЫБОР
        ...
    КОНЕЦ КАК ДатаОпределенияОбязательств
ПОМЕСТИТЬ ВТ_ВидОбязательствНачало
ИЗ
    ВТ_ПенсионныеСчета КАК ВТ_ПенсионныеСчета
    ЛЕВОЕ СОЕДИНЕНИЕ ВТ_ВыплатныеНачалоПериода КАК ВТ_ВыплатныеНачалоПериода
    ПО ВТ_ПенсионныеСчета.ПенсионныйСчет = ВТ_ВыплатныеНачалоПериода.НомерСчета
    ЛЕВОЕ СОЕДИНЕНИЕ ВТ_СостояниеНачалоПериода КАК ВТ_СостояниеНачалоПериода
    ПО ВТ_ПенсионныеСчета.ПенсионныйСчет = ВТ_СостояниеНачалоПериода.НомерСчета
ГДЕ
    НЕ ВТ_СостояниеНачалоПериода.ВидОбязательств ЕСТЬ NULL
;

// ... промежуточные запросы ...

ВЫБРАТЬ
    ПодЗапрос.ПенсионныйСчет КАК ПенсионныйСчет,
    ПодЗапрос.ВидОбязательств КАК ВидОбязательств,
    ПодЗапрос.ДатаОпределенияОбязательств КАК ДатаОпределенияОбязательств,
    ПодЗапрос.ДатаОкончанияОбязательств КАК ДатаОкончанияОбязательств
ПОМЕСТИТЬ ВТ_ОстаткиТранзитныеВиды
ИЗ
    (
        ВЫБРАТЬ
            ...
        ИЗ
            ...
    ) КАК ПодЗапрос
СГРУППИРОВАТЬ ПО
    ...

ОБЪЕДИНИТЬ ВСЕ

ВЫБРАТЬ
    ...
ИЗ
    ...

// ... другие UNION-части ...

ВЫБРАТЬ
    ВТ_ТранзитныеВидыНачалоКонец.ПенсионныйСчет КАК ПенсионныйСчет,
    ...
    СУММА(...) КАК ЗадолженностьПенсии
ИЗ
    ВТ_ТранзитныеВидыНачалоКонец КАК ВТ_ТранзитныеВидыНачалоКонец
    ЛЕВОЕ СОЕДИНЕНИЕ РегистрНакопления.уп_ЗадолженностьПоПенсиям КАК уп_ЗадолженностьПоПенсиям
    ПО уп_ЗадолженностьПоПенсиям.НомерСчета = ВТ_ТранзитныеВидыНачалоКонец.ПенсионныйСчет
СГРУППИРОВАТЬ ПО
    ...
;

ВЫБРАТЬ
    ПодЗапрос.ЗадолженностьПенсии КАК ЗадолженностьПенсии,
    ...
ПОМЕСТИТЬ ВТ_Суммы_ПР_ТранзитныеВиды
ИЗ
    (
        ВЫБРАТЬ
            ...
        ИЗ
            ВТ_ОстаткиТранзитныеВиды КАК ВТ_ОстаткиТранзитныеВиды

        ОБЪЕДИНИТЬ ВСЕ

        ВЫБРАТЬ
            ...
        ИЗ
            ВТ_ТранзитныеВидыНачалоКонец КАК ВТ_ТранзитныеВидыНачалоКонец
        ...
    ) КАК ПодЗапрос
СГРУППИРОВАТЬ ПО
    ...
;
```

**Важно:** `sub_query`-ноды (`ВТ_ОстаткиТранзитныеВиды_UNION_0_SUB_1`, `ВТ_Суммы_ПР_ТранзитныеВиды_SUB_1`) не выводятся как отдельные запросы. Они inline-ятся в скобках внутри родительского `ИЗ`, а их зависимости (`ВТ_ОстаткиТранзитныеВиды`, `ВТ_ТранзитныеВидыНачалоКонец`) должны быть созданы раньше.

---

## 8. Нефункциональные требования

### 8.1 Формат первичен
Выходной SQL определяется примером (раздел 7). Новые конструкции добавляются только по согласованию.

### 8.2 Пример первее кода
`middle_example` генерируется до коммита кода. Артефакты коммитятся отдельно от кода.

### 8.3 Читаемость
Восстановленный SQL должен быть отформатирован с отступами (как в оригинальных `query_texts_*.sql`).

---

## 9. Тестирование

### 9.1 Тест наличия файла
`testRestoredQueryFile()` — проверяет наличие `RESTORED_QUERIES/middle_example/74_ВТ_Суммы_ПР_ТранзитныеВиды/ЗадолженностьПенсии.sql`.

### 9.2 Тест синтаксиса
Проверяет, что восстановленный SQL содержит корректные блоки: `ВЫБРАТЬ`, `ИЗ`, `ПОМЕСТИТЬ`, `СГРУППИРОВАТЬ ПО`.

### 9.3 Тест порядка
Проверяет, что `ВТ_ТранзитныеВидыНачалоКонец` создаётся раньше, чем используется.

### 9.4 Тест WHERE как строки
Проверяет, что `ГДЕ` содержит исходный текст из FFL (`where`), а не собранный из `where_fields`.

### 9.5 Тест автосоздания FFL
Проверяет, что при запуске `--field` для несуществующего FFL он создаётся автоматически.

### 9.6 Тест режима --package
Проверяет, что для `middle_example` создаются SQL-файлы для всех target-полей.

### 9.7 Тест --recreate-deps
Проверяет, что перед каждым `ПОМЕСТИТЬ` присутствует `УНИЧТОЖИТЬ` для той же ВТ.

### 9.8 Тест inline-subquery
Проверяет, что `sub_query`-ноды не создаются как отдельные `ПОМЕСТИТЬ`, а их SQL вставляется в скобках внутри родительского `ИЗ`:
- Для `node 74` (target): `ИЗ ( ... ОБЪЕДИНИТЬ ВСЕ ... ) КАК ПодЗапрос`
- Для `node 63` (UNION_0): `ИЗ ( ... ) КАК ПодЗапрос`
- Отсутствие `ПОМЕСТИТЬ ВТ_..._SUB_1` как отдельного запроса.

---

## 10. История изменений

| Версия | Дата | Автор | Изменения |
|--------|------|-------|-----------|
| 1.0 | 2026-06-03 | Kimi AI Agent | Первоначальная версия |
| 1.1 | 2026-06-04 | Kimi AI Agent | Уточнено: FFL самодостаточен; первичные поля; _fields не используются |
| 1.2 | 2026-06-04 | Kimi AI Agent | Добавлены 4 режима запуска (--field, --table, --package, --recreate-deps); автосоздание FFL |
| 1.3 | 2026-06-04 | Kimi AI Agent | Уточнено: виртуальные UNION-ноды не генерируют SQL; into переносится в UNION_0; subquery внутри UNION-частей |
| 1.4 | 2026-06-05 | Kimi AI Agent | Уточнено: subquery inline-ится в скобках внутри родителя; поиск subquery по `subquery_ids`; UNION-родитель (`temp_query` с `union_nodes_ids`) генерирует `ПОМЕСТИТЬ` + UNION |

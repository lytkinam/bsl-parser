# SRS07: Оптимизация FFL — исключение неиспользуемых LEFT JOIN

## 1. Введение

### 1.1 Цель

Определить технические требования к подсистеме оптимизации `full_field_lineage` (FFL) путём исключения неиспользуемых LEFT JOIN'ов. Подсистема работает на этапе построения FFL и фильтрует JOIN'ы, которые не влияют на результат target-поля.

### 1.2 Область применения

Модуль SDQL, пакет `sdql/full_pars/`. Разработка в ветке `develop`, коммиты содержат `Refs Redmine #646`.

### 1.3 Термины

| Термин | Описание |
|--------|----------|
| **FFL** | `full_field_lineage` — плоский массив узлов, урезанный под конкретное поле |
| **Unused LEFT JOIN** | LEFT JOIN, ни alias ни table которого не встречаются в SELECT/WHERE/GROUP BY/HAVING target-поля и не нужны для условий последующих используемых JOIN'ов |
| **Used source** | Alias или table, которые встречаются в `child_fields[].nodeName` / `source` любого поля ноды |
| **Right-to-left scan** | Проход по массиву JOIN'ов от последнего к первому с накоплением множества используемых источников |
| **Nested join** | JOIN, у которого `source.joins[]` не пуст (вложенные соединения) |

### 1.4 Ссылки

- `docs/brd_srs/BRD07 unused left join optimization.md`
- `docs/brd_srs/SRS01 full_pars.md`
- `docs/brd_srs/SRS02 query_reconstruction.md`

---

## 2. Общее описание

### 2.1 Контекст

FFL строится как урезанный граф зависимостей: для target-поля остаются только те узлы и поля, которые влияют на его результат. Однако структура JOIN'ов в каждом узле копируется из FULL_PARS без фильтрации — в FFL попадают все JOIN'ы, включая те, чьи поля не используются.

Настоящий SRS определяет алгоритм фильтрации JOIN'ов на этапе создания FFL-ноды.

### 2.2 Пользователи

Разработчики 1С, аналитики запросов. Используют оптимизированный FFL для:
- Построения компактного восстановленного SQL (меньше JOIN'ов → читаемее)
- Анализа реальных зависимостей поля (без "шума" от неиспользуемых таблиц)
- Отладки сложных запросов с большим количеством LEFT JOIN'ов

### 2.3 Ограничения

- **Формат первичен** — выходной JSON определяется примером
- **Пример первее кода** — `middle_example` генерируется до коммита кода
- **INNER JOIN'ы не трогаются** — они фильтруют строки
- **Минимальные изменения** — только `FullFieldLineageBuilder`, без изменения upstream/downstream

---

## 3. Функциональные требования

### 3.1 Место применения фильтрации

#### FR-3.1.1 Точка встраивания

Фильтрация JOIN'ов выполняется в методе `FullFieldLineageBuilder.createNode()` **после** формирования `selectFields` и копирования `where_fields`, `group_by_fields`, `having_fields`, `join_fields`, но **до** возврата результата.

Почему здесь:
- FFL-нода уже содержит урезанный `select` (только нужные поля)
- Условия (`where_fields`, `group_by_fields`, `having_fields`) уже скопированы
- Это последний момент перед записью в JSON

### 3.2 Алгоритм сбора используемых источников

#### FR-3.2.1 Начальное множество

Собрать множество строк `usedSources`:
- `from[].table` (основная таблица)
- `from[].alias` (alias основной таблицы)

#### FR-3.2.2 Источники из SELECT

Для каждого поля `select[]`:
- Для каждого `child_fields[]`: добавить `nodeName` и `source` (если не null)

#### FR-3.2.3 Источники из WHERE

Для каждого поля `where_fields[]`:
- Для каждого `child_fields[]`: добавить `nodeName` и `source` (если не null)

#### FR-3.2.4 Источники из GROUP BY

Для каждого поля `group_by_fields[]`:
- Для каждого `child_fields[]`: добавить `nodeName` и `source` (если не null)

#### FR-3.2.5 Источники из HAVING

Для каждого поля `having_fields[]`:
- Для каждого `child_fields[]`: добавить `nodeName` и `source` (если не null)

#### FR-3.2.6 Источники из JOIN condition_fields (начальный проход)

Для каждого `join_fields[]`:
- Для каждого `condition_fields[].child_fields[]`: добавить `nodeName` и `source` (если не null)

### 3.3 Алгоритм right-to-left сканирования

#### FR-3.3.1 Проход справа налево

Для массива `from[].joins[]` выполнить проход от последнего элемента к первому:

```
for i = joins.size() - 1 downto 0:
    join = joins[i]
    alias = join.source.alias
    table = join.source.table
    subquery = join.source.subquery (если String)
    isInner = "inner".equalsIgnoreCase(join.joinType)
```

#### FR-3.3.2 Решение об использовании

JOIN считается используемым (`isUsed = true`) если:
- `isInner == true` (INNER JOIN всегда используется), **ИЛИ**
- `alias != null && usedSources.contains(alias)`, **ИЛИ**
- `table != null && usedSources.contains(table)`, **ИЛИ**
- `subquery != null && usedSources.contains(subquery)`

#### FR-3.3.3 Расширение usedSources

Если JOIN используется (`isUsed == true`):
1. Добавить его `alias` (если не null) в `usedSources`
2. Добавить его `table` (если не null) в `usedSources`
3. Добавить его `subquery` (если не null) в `usedSources`
4. Найти соответствующий `FullParsJoinCondition` в `join_fields[]` (по `source`)
5. Для каждого `condition_fields[].child_fields[]`: добавить `nodeName` и `source` в `usedSources`

#### FR-3.3.4 Накопление keep-списка

Если JOIN используется — добавить его `alias`/`table`/`subquery` в множества `keepJoinAliases` / `keepJoinTables`.

### 3.4 Фильтрация списков

#### FR-3.4.1 Фильтрация from[].joins[]

Сформировать новый список `filteredJoins`, включая только те JOIN'ы, для которых:
- `isInner == true`, **ИЛИ**
- `alias != null && keepJoinAliases.contains(alias)`, **ИЛИ**
- `table != null && keepJoinTables.contains(table)`, **ИЛИ**
- `subquery != null && keepJoinTables.contains(subquery)`

Заменить `from[].joins` на `filteredJoins`.

#### FR-3.4.2 Фильтрация join_fields[]

Сформировать новый список `filteredJoinFields`, включая только те `FullParsJoinCondition`, для которых `source` совпадает с `alias`, `table` или `subquery` хотя бы одного JOIN'а из `filteredJoins`.

Заменить `join_fields` на `filteredJoinFields`.

### 3.5 Обработка вложенных JOIN'ов

#### FR-3.5.1 Рекурсивная фильтрация

Если `join.source.joins` не пуст (nested joins), применить тот же алгоритм фильтрации рекурсивно к вложенным JOIN'ам.

#### FR-3.5.2 Родительский JOIN с вложенными

Если после рекурсивной фильтрации у родительского JOIN'а остались вложенные JOIN'ы — родитель остаётся.
Если вложенные JOIN'ы все удалены — родитель оценивается по обычным правилам (FR-3.3.2).

---

## 4. Структуры данных

### 4.1 Изменения в FullFieldLineageBuilder

Новый приватный метод:

```java
private void filterUnusedJoins(FullParsNode node)
```

Вспомогательный метод:

```java
private FullParsJoinCondition findJoinCondition(
    List<FullParsJoinCondition> joinFields, 
    String alias, 
    String table
)
```

### 4.2 Псевдокод алгоритма

```java
function filterUnusedJoins(node):
    if node.from == null or node.from.isEmpty():
        return
    
    mainSource = node.from.get(0)
    joins = mainSource.getJoins()
    if joins == null or joins.isEmpty():
        return
    
    // 1. Collect initially used sources
    usedSources = new HashSet<String>()
    addMainSource(usedSources, mainSource)
    addSelectSources(usedSources, node.select)
    addConditionSources(usedSources, node.whereFields)
    addConditionSources(usedSources, node.groupByFields)
    addConditionSources(usedSources, node.havingFields)
    addJoinConditionSources(usedSources, node.joinFields)
    
    // 2. Right-to-left scan
    keepAliases = new HashSet<String>()
    keepTables = new HashSet<String>()
    
    for i = joins.size() - 1 downto 0:
        join = joins.get(i)
        src = join.getSource()
        alias = src != null ? src.getAlias() : null
        table = src != null ? src.getTable() : null
        sq = src != null && src.getSubquery() != null ? src.getSubquery().toString() : null
        isInner = "inner".equalsIgnoreCase(join.getJoinType())
        
        isUsed = isInner
              || (alias != null && usedSources.contains(alias))
              || (table != null && usedSources.contains(table))
              || (sq != null && usedSources.contains(sq))
        
        if isUsed:
            if (alias != null) keepAliases.add(alias)
            if (table != null) keepTables.add(table)
            if (sq != null) keepTables.add(sq)
            
            // Add condition field sources to usedSources
            jc = findJoinCondition(node.getJoinFields(), alias, table)
            if jc != null:
                for cf in jc.getConditionFields():
                    for ccf in cf.getChildFields():
                        if ccf.getNodeName() != null: usedSources.add(ccf.getNodeName())
                        if ccf.getSource() != null: usedSources.add(ccf.getSource())
    
    // 3. Filter joins
    filteredJoins = new ArrayList<JoinPart>()
    for join in joins:
        src = join.getSource()
        alias = src != null ? src.getAlias() : null
        table = src != null ? src.getTable() : null
        sq = src != null && src.getSubquery() != null ? src.getSubquery().toString() : null
        isInner = "inner".equalsIgnoreCase(join.getJoinType())
        
        keep = isInner
            || (alias != null && keepAliases.contains(alias))
            || (table != null && keepTables.contains(table))
            || (sq != null && keepTables.contains(sq))
        
        if keep:
            filteredJoins.add(join)
    
    mainSource.setJoins(filteredJoins)
    
    // 4. Filter join_fields
    filteredJc = new ArrayList<FullParsJoinCondition>()
    for jc in node.getJoinFields():
        srcName = jc.getSource()
        keep = false
        for join in filteredJoins:
            js = join.getSource()
            if js != null:
                if srcName != null && srcName.equals(js.getAlias()): keep = true
                if srcName != null && srcName.equals(js.getTable()): keep = true
                if js.getSubquery() != null && srcName != null && srcName.equals(js.getSubquery().toString()): keep = true
        if keep:
            filteredJc.add(jc)
    
    node.setJoinFields(filteredJc)
```

---

## 5. Алгоритмы

### 5.1 Полный пайплайн (изменения отмечены [NEW])

```
function buildLineage(nodeId, aliases, resultMap):
    node = full_pars[nodeId]
    
    if resultMap.containsKey(nodeId):
        result = resultMap.get(nodeId)
        // Extend select with new aliases
        ...
    else:
        result = createNode(node, aliases)
        [NEW] filterUnusedJoins(result)  // <-- фильтрация JOIN'ов
        resultMap.put(nodeId, result)
    
    // Handle UNION
    ...
    
    // Collect child_fields and recurse
    ...
```

### 5.2 Пример работы алгоритма

**Узел:** `ВТ_РезультатПредв_UNION_0` (id=296)
**Поле:** `Взносы26`
**JOIN'ы (упрощённо):**

| # | Тип | Таблица | Alias | Используется? |
|---|-----|---------|-------|---------------|
| 1 | inner | ВТ_НеТранзитныеВидыНачалоКонец | ... | Да (inner) |
| 2 | inner | ВТ_ПенсионныеДоговоры_Свойства | ... | Да (inner) |
| 3 | left | ВТ_Суммы | ВТ_Суммы | Да (в SELECT) |
| 4 | left | ВТ_СхемыПенсионныхСчетов | ... | Нет |
| 5 | left | ВТ_ФизическиеЛица | ... | Нет |

**Шаг 1 — usedSources (начальные):**
- SELECT `Взносы26` → `child_fields[].nodeName = "ВТ_Суммы"` → usedSources = {"ВТ_Суммы", "ВТ_ПенсионныеСчета_Свойства", ...}

**Шаг 2 — right-to-left scan:**
- JOIN 5 (left, ВТ_ФизическиеЛица): не в usedSources → удаляем
- JOIN 4 (left, ВТ_СхемыПенсионныхСчетов): не в usedSources → удаляем
- JOIN 3 (left, ВТ_Суммы): в usedSources → оставляем, добавляем condition_fields в usedSources
- JOIN 2 (inner): оставляем, добавляем condition_fields
- JOIN 1 (inner): оставляем, добавляем condition_fields

**Шаг 3 — filtered joins:** [1, 2, 3]

---

## 6. Интеграция

### 6.1 Изменения в существующих классах

| Класс | Изменение |
|-------|-----------|
| `FullFieldLineageBuilder` | Добавить `filterUnusedJoins()` и `findJoinCondition()`; вызвать из `createNode()` |

### 6.2 Без изменений

| Класс / Файл | Почему без изменений |
|--------------|---------------------|
| `FullParsModelBuilder` | Фильтрация на уровне FFL, не FULL_PARS |
| `FullParsNode` | Структура не меняется, меняется только наполнение |
| `QueryReconstructor` | Работает с уже отфильтрованным FFL |
| `QueryExtractor` | Работает с FULL_PARS, не с FFL |

---

## 7. Нефункциональные требования

### 7.1 Формат первичен

Выходной JSON определяется примером. Новые поля не добавляются — только фильтрация существующих.

### 7.2 Пример первее кода

`middle_example` генерируется до коммита кода. Артефакты коммитятся отдельно от кода.

### 7.3 Минимальные изменения

Только один метод в `FullFieldLineageBuilder`. Не изменять upstream (FULL_PARS) или downstream (QueryReconstructor).

### 7.4 Производительность

Алгоритм линейный по количеству JOIN'ов в узле. Накладные расходы незначительны.

---

## 8. Тестирование

### 8.1 Тест наличия файла

`testOptimizedFflFile()` — проверяет, что `full_field_lineage/middle_example/.../FFL_*.json` создаётся после оптимизации.

### 8.2 Тест удаления неиспользуемых LEFT JOIN

Для `middle_example` найти ноду с LEFT JOIN'ами, чьи поля не используются в SELECT target-поля. Проверить, что после оптимизации:
- Количество `from[].joins` уменьшилось
- Удалены только LEFT JOIN'ы (INNER остались)

### 8.3 Тест сохранения используемых LEFT JOIN

Проверить, что LEFT JOIN, чьи поля используются в SELECT/WHERE/GROUP BY/HAVING, остался в FFL.

### 8.4 Тест right-to-left зависимостей

Создать тестовый случай, где JOIN N (used) ссылается в условии на JOIN N-1 (unused сам по себе). Проверить, что JOIN N-1 остался.

### 8.5 Тест каскадного эффекта

Проверить, что если удалённый LEFT JOIN был единственным пользователем child-ноды, эта child-нода не попадает в FFL.

### 8.6 Тест на example_258

Сравнить оптимизированный FFL для `Взносы26` с файлом `FFL_example_258_for_what.json`. В узле 296 (`ВТ_РезультатПредв_UNION_0`) должны быть удалены ~20 неиспользуемых LEFT JOIN'ов.

---

## 9. Пример (middle_example)

### 9.1 Вход

`FULL_PARS_model_middle_example.json` + вызов `FullFieldLineageBuilder` для `{74, [ЗадолженностьПенсии]}`.

### 9.2 Выход

`FFL_middle_example_74_ВТ_Суммы_ПР_ТранзитныеВиды_ЗадолженностьПенсии.json` — с оптимизированными JOIN'ами в каждом узле.

### 9.3 Ожидаемый эффект

Для узлов, где `ЗадолженностьПенсии` не использует поля некоторых LEFT JOIN'ов — эти JOIN'ы исключаются. Восстановленный SQL становится компактнее.

---

## 10. История изменений

| Версия | Дата | Автор | Изменения |
|--------|------|-------|-----------|
| 1.0 | 2026-06-05 | Kimi AI Agent | Первоначальная версия |

# BRD04.01: Парсинг подзапросов в блоке WHERE (SDBL)

## 1. Цель

Расширить SDBL-парсер таким образом, чтобы подзапросы, вложенные в условия блока `WHERE` (в частности, в предикатах `В (подзапрос)` / `IN (subquery)`), распознавались как отдельные AST-узлы, а не сохранялись как неразобранный текст внутри строки `where`.

## 2. Проблема

В текущей реализации парсера SDBL блок `WHERE` сериализуется как единая строка:

```json
{
  "where": "Контрагенты.Ссылка В\n    (ВЫБРАТЬ\n        НашиКонтрагенты.Ссылка\n    ИЗ\n        Справочник.Контрагенты КАК НашиКонтрагенты)"
}
```

Подзапрос внутри `WHERE` остаётся неразобранным текстом. Это приводит к тому, что:
- downstream-системы (FULL_PARS, FFL, восстановление SQL) не видят зависимости от таблиц, используемых внутри `WHERE`-подзапросов;
- lineage-анализ теряет цепочки полей, проходящие через `WHERE`;
- при восстановлении/извлечении SQL подзапрос в `WHERE` не может быть корректно обработан как отдельный inline-узел.

## 3. Входные данные

- Исходный SQL-пакет 1С (SDBL), содержащий в `WHERE` конструкции вида:
  ```sql
  ГДЕ
      Таблица.Поле В (ВЫБРАТЬ ... ИЗ ...)
  ```
- Пример: `examples/example_4.sql` (узел `Результат_3`, строки 59–64).

## 4. Выходные данные

### 4.1 SDBL-модель (QueryAst)

Поле `where` в `QueryAst` меняет тип со `String` на объект `WhereBlock`:

```json
{
  "where": {
    "text": "Контрагенты.Ссылка В (ВТ_Подзапрос_1)",
    "subqueries": [
      {
        "name": "ВТ_Подзапрос_1",
        "query": { /* QueryAst подзапроса */ }
      }
    ]
  }
}
```

| Поле | Тип | Обязательность | Описание |
|------|-----|----------------|----------|
| `where.text` | String | Да | Текст WHERE с заменой подзапросов на псевдонимы |
| `where.subqueries` | Array | Нет | Список подзапросов, найденных в WHERE |
| `subqueries[].name` | String | Да | Сгенерированное имя подзапроса (`ВТ_Подзапрос_N`) |
| `subqueries[].query` | QueryAst | Да | AST вложенного запроса |

### 4.2 Правила именования подзапросов в WHERE

- Шаблон: `ВТ_Подзапрос_N`, где `N` — порядковый номер в рамках текущего `QueryAst` (начиная с 1).
- Имя уникально в рамках одного `QueryAst`.

### 4.3 Замена в тексте WHERE

- В `where.text` каждый подзапрос заменяется на свой псевдоним в скобках: `(ВТ_Подзапрос_N)`.
- Исходный текст подзапроса полностью удаляется из `where.text`.

### 4.4 Обратная совместимость

- Если в `WHERE` нет подзапросов — `where` остаётся строкой (legacy-режим) либо сериализуется как объект с `text` и отсутствующим `subqueries`.
- **Рекомендуемый подход**: всегда сериализовать `where` как объект `WhereBlock`, а для случаев без подзапросов `subqueries` отсутствует (`@JsonInclude(NON_EMPTY)`).

## 5. Основные требования

### 5.1 Грамматика

Изменения в грамматике ANTLR4 **не требуются** — подзапрос в `WHERE` уже разбирается грамматикой через правило `inPredicate` → `subquery`.

### 5.2 Визитор (QueryPackageVisitor)

- При обработке `QueryContext.where` (тип `LogicalExpressionContext`) визитор должен:
  1. Обойти дерево `LogicalExpressionContext` и найти все узлы `InPredicateContext`, содержащие `subquery()`.
  2. Для каждого найденного `subquery()` создать `QueryAst` через `visitSubquery()`.
  3. Сгенерировать имя `ВТ_Подзапрос_N`.
  4. Построить `where.text` — исходный текст `LogicalExpressionContext` с заменой текста подзапроса на `(ВТ_Подзапрос_N)`.
  5. Сформировать `WhereBlock` и записать в `QueryAst.where`.

### 5.3 Модель (QueryAst)

- Добавить класс `WhereBlock`:
  ```java
  @Data
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public class WhereBlock {
      private String text;
      private List<WhereSubquery> subqueries;
  }
  ```
- Добавить класс `WhereSubquery`:
  ```java
  @Data
  public class WhereSubquery {
      private String name;
      private QueryAst query;
  }
  ```
- В `QueryAst` поле `where` изменить тип: `String` → `WhereBlock`.

### 5.4 LINE_PARS (LineParsModelBuilder)

- При обработке `where` в `LineParsModelBuilder`:
  1. Если `where.subqueries` не пуст — для каждого подзапроса создать `LineParsNode` типа `sub_query` (аналогично подзапросам из `FROM`).
  2. Имя подзапроса: `<parentName>_WHERE_SUB_N` (например, `Результат_3_WHERE_SUB_1`).
  3. В родительском `where.text` заменить `ВТ_Подзапрос_N` на имя `LineParsNode`.
  4. `upqueryId` / `subqueryIds` связываются как для обычных подзапросов.

### 5.5 Hierarchy (LineParsHierarchyBuilder)

- Подзапросы из `WHERE` добавляются в `tableHierarchy` родительского узла с `typeHierarchy="where_subquery"`.
- `source` указывает на имя подзапроса, `id` — на `LineParsNode.id`.

### 5.6 FULL_PARS / FFL / Восстановление SQL

- Подзапросы из `WHERE` обрабатываются аналогично подзапросам из `FROM`:
  - `child_fields` строятся из `where.text`;
  - при восстановлении SQL подзапрос inline-ится в скобках внутри условия `WHERE`.

## 6. Пример

**Вход (фрагмент `example_4.sql`):**
```sql
ГДЕ
    Контрагенты.Ссылка В
        (ВЫБРАТЬ
            НашиКонтрагенты.Ссылка
        ИЗ
            Справочник.Контрагенты КАК НашиКонтрагенты)
```

**Выход (SDBL модель):**
```json
{
  "where": {
    "text": "Контрагенты.Ссылка В (ВТ_Подзапрос_1)",
    "subqueries": [
      {
        "name": "ВТ_Подзапрос_1",
        "query": {
          "type": "select",
          "select": [
            { "text": "НашиКонтрагенты.Ссылка" }
          ],
          "from": [
            {
              "table": "Справочник.Контрагенты",
              "alias": "НашиКонтрагенты"
            }
          ]
        }
      }
    ]
  }
}
```

**Выход (LINE_PARS модель):**
```json
{
  "id": 5,
  "name": "Результат_3",
  "type": "result",
  "where": "Контрагенты.Ссылка В (Результат_3_WHERE_SUB_1)",
  "subqueryIds": [7]
}
```

## 7. Ограничения

- Обрабатываются только подзапросы внутри `WHERE` (предикат `IN`).
- Подзапросы в `HAVING`, `JOIN`-условиях, виртуальных таблицах — в рамках данного BRD не обрабатываются.
- Подзапросы в `SELECT`-выражениях (скалярные) — в рамках данного BRD не обрабатываются.

## 8. Зависимости

- SRS04.01 — спецификация реализации.
- Изменения затрагивают: `QueryAst`, `QueryPackageVisitor`, `LineParsModelBuilder`, `LineParsHierarchyBuilder`.

## 9. Критерии приёмки

1. `example_4.sql` парсится без ошибок.
2. В `sdbl_parse_model_example_4.json` узел `Результат_3` содержит `where` как объект с `text` и `subqueries`.
3. В `LINE_PARS_model_example_4.json` появляется узел `Результат_3_WHERE_SUB_1` типа `sub_query`.
4. В `LINE_PARS_hierarchy_example_4.json` узел `Результат_3` содержит дочерний элемент с `typeHierarchy="where_subquery"`.
5. Тесты `SdqlQueryPackageAnalyzerTest` проходят.
6. Артефакты `middle_example` перегенерированы и проверены.

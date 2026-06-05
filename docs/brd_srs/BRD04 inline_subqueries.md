# BRD04: Извлечение inline-подзапросов из не-FROM контекстов

## 1. Цель

Расширить парсинг SQL-пакетов 1С (SDBL) так, чтобы inline-подзапросы, расположенные **вне секции FROM**, также извлекались в отдельные структурированные ноды (аналогично FROM-подзапросам). Это необходимо для корректного построения полевых зависимостей (child_fields / field_lineage) в случаях, когда подзапрос скрыт внутри текстовых полей `where`, `virtualTable`, `joins[].condition`, `select[].text`.

## 2. Проблема

В текущей реализации (BRD01–BRD03) подзапросы извлекаются только из секции `FROM` (`dataSource` с `subquery`). Подзапросы в других контекстах остаются **неструктурированным текстом**:

| Контекст | Пример в SDBL | Где хранится сейчас | Проблема |
|----------|---------------|---------------------|----------|
| WHERE (IN) | `ГДЕ Поле В (ВЫБРАТЬ ...)` | `where` — строка | Зависимости подзапроса не видны lineage |
| Виртуальная таблица | `СрезПоследних(&Период, Поле В (ВЫБРАТЬ ...))` | `virtualTable` — строка | Зависимости ВТ внутри параметров не видны |
| SELECT-выражение | `(ВЫБРАТЬ ...) КАК Поле` | `select[].text` — строка | Зависимости не отслеживаются |
| JOIN-условие | `ПО Поле В (ВЫБРАТЬ ...)` | `joins[].condition` — строка | Зависимости не видны |

### 2.1 Пример из example_4.sql

```sql
ВЫБРАТЬ
    ВложенныйЗапрос.Ссылка КАК Ссылка
ИЗ
    (ВЫБРАТЬ ... ИЗ ЗЛО ...) КАК ВложенныйЗапрос        -- FROM-подзапрос: уже извлекается

    ЛЕВОЕ СОЕДИНЕНИЕ Справочник.Контрагенты КАК Контрагенты
    ПО ВложенныйЗапрос.Получатель = Контрагенты.Ссылка,

    РегистрСведений.уп_СостоянияДоговораОПС.СрезПоследних(
        &Период,
        ДоговорОПС В
            (ВЫБРАТЬ НашиДоговора.Ссылка ИЗ вт_НашиДоговора)   -- inline в virtualTable
    ) КАК уп_СостоянияДоговораОПССрезПоследних

ГДЕ
    Контрагенты.Ссылка В
        (ВЫБРАТЬ НашиКонтрагенты.Ссылка ИЗ Справочник.Контрагенты)  -- inline в WHERE
```

В текущей модели:
- FROM-подзапрос `ВложенныйЗапрос` → создаёт ноду `Результат_3_SUB_1` ✓
- Подзапрос в `virtualTable` → остаётся в строке `virtualTable` ✗
- Подзапрос в `WHERE` → остаётся в строке `where` ✗

## 3. Требования

### 3.1 Контексты извлечения

Подзапросы должны извлекаться из следующих контекстов ANTLR-грамматики:

#### 3.1.1 WHERE — `inPredicate`
Правило: `inPredicate: expression IN LPAREN (subquery | expressionList) RPAREN`
При обнаружении `subquery` внутри `inPredicate` — извлечь как inline-подзапрос.

#### 3.1.2 Виртуальная таблица — `virtualTableParameter`
Правило: `virtualTableParameter: logicalExpression?`
`logicalExpression` может содержать `predicate` → `inPredicate` → `subquery`.
При обнаружении `subquery` внутри параметров виртуальной таблицы — извлечь как inline-подзапрос.

#### 3.1.3 SELECT-выражение — `bracketExpression`
Правило: `bracketExpression: (LPAREN expression RPAREN) | (LPAREN subquery RPAREN)`
При обнаружении `subquery` внутри `bracketExpression` (в `expressionField`) — извлечь как inline-подзапрос.

#### 3.1.4 JOIN-условие — `joinPart.condition`
Правило: `joinPart: ... logicalExpression?`
`logicalExpression` может содержать `inPredicate` с `subquery`.
При обнаружении `subquery` внутри условия JOIN — извлечь как inline-подзапрос.

### 3.2 Поведение извлечения

#### 3.2.1 Создание SUB-ноды
Каждый inline-подзапрос создаёт отдельную ноду типа `sub_query` в `LINE_PARS`, аналогично FROM-подзапросам:
- Имя: `<parent_name>_INLINE_<N>` (где N — порядковый номер в рамках родителя)
- Тип: `sub_query`
- Поля `select`, `from`, `where` и т.д. — из AST подзапроса

#### 3.2.2 Замена в тексте
В исходном тексте родительского узла подзапрос заменяется на **ссылку-имя**:
- `where`: `Поле В (ВЫБРАТЬ ...)` → `Поле В <parent_name>_INLINE_1`
- `virtualTable`: `СрезПоследних(..., Поле В (ВЫБРАТЬ ...))` → `СрезПоследних(..., Поле В <parent_name>_INLINE_1)`
- `select[].text`: `(ВЫБРАТЬ ...) КАК Поле` → `(<parent_name>_INLINE_1) КАК Поле`
- `joins[].condition`: `ПО Поле В (ВЫБРАТЬ ...)` → `ПО Поле В <parent_name>_INLINE_1`

#### 3.2.3 Ссылки в модели
В `QueryAst` добавляется поле `inlineSubqueries` — список объектов:
```json
{
  "context": "where|virtualTable|select|joinCondition",
  "name": "Результат_3_INLINE_1",
  "query": { /* QueryAst подзапроса */ }
}
```

### 3.3 Зависимости в FULL_PARS

#### 3.3.1 child_fields через inline-подзапросы
`FullParsModelBuilder` должен учитывать inline-подзапросы при построении `child_fields`:
- Если в тексте `where`/`virtualTable`/`select`/`condition` встречается ссылка на inline-подзапрос (`<name>_INLINE_<N>`), то `child_fields` должен содержать зависимости **из этого подзапроса** (рекурсивно, один уровень).
- Или: в `child_fields` добавляется запись с `node_id` указывающим на inline-SUB-ноду.

#### 3.3.2 Иерархия
`LineParsHierarchyBuilder` должен включать inline-SUB-ноды в `table_hierarchy` родителя (как `subquery` типа `from`).

### 3.4 Восстановление SQL

#### 3.4.1 QueryReconstructor
При восстановлении SQL inline-подзапросы должны inline-иться обратно в скобки:
- `Поле В <parent_name>_INLINE_1` → `Поле В (ВЫБРАТЬ ...)`
- Сопоставление: по имени ссылки из `inlineSubqueries` или `subquery_ids` (если inline-подзапрос добавлен в `subquery_ids`).

## 4. Входные данные

- `SDBL_PARS/sdbl_parse_model_<basename>.json` — AST с извлечёнными inline-подзапросами
- `SDBL_PARS/sdbl_parse_nodes_<basename>.json` — первичные тексты (для сверки)

## 5. Выходные данные

### 5.1 SDBL_PARS
Расширенный `sdbl_parse_model_<basename>.json` с полем `inlineSubqueries` в `QueryAst`.

### 5.2 LINE_PARS
- `LINE_PARS_model_<basename>.json` — дополнительные ноды типа `sub_query` (inline)
- `LINE_PARS_hierarchy_<basename>.json` — inline-SUB-ноды в иерархии родителей

### 5.3 FULL_PARS
- `FULL_PARS_model_<basename>.json` — `child_fields` с зависимостями через inline-подзапросы

### 5.4 RESTORED_QUERIES
- Восстановленный SQL с inline-подзапросами в скобках (как в оригинале)

## 6. Тестирование

### 6.1 Тестовый пример
Использовать `example_4.sql` — содержит подзапросы в `virtualTable` и `WHERE`.

### 6.2 Проверки
1. В `LINE_PARS_model_example_4.json` присутствуют ноды `_INLINE_1`, `_INLINE_2` и т.д.
2. В `FULL_PARS_model_example_4.json` `child_fields` для `where_fields` содержит `node_id` inline-SUB-ноды.
3. Восстановленный SQL содержит подзапросы в скобках (как в оригинале).
4. Топологическая сортировка учитывает зависимости inline-подзапросов.

## 7. Последствия для downstream-форматов

Добавление `inlineSubqueries` в `QueryAst` требует каскадного обновления всех уровней парсинга:

| Уровень | Формат | Что меняется | Обратная совместимость |
|---------|--------|-------------|----------------------|
| SDBL_PARS | `sdbl_parse_model_*.json` | Новое поле `inlineSubqueries` в `QueryAst` | Поле опциональное (`NON_NULL`) |
| LINE_PARS | `LINE_PARS_model_*.json` | Новые ноды `_INLINE_<N>` типа `sub_query` | Ноды без inline — без изменений |
| LINE_PARS | `LINE_PARS_hierarchy_*.json` | Inline-SUB в `table_hierarchy` | — |
| FULL_PARS | `FULL_PARS_model_*.json` | `child_fields` с `node_id` inline-SUB | Структура `child_fields` не меняется |
| FFL | `FFL_*.json` | Inline-SUB как обычные `sub_query` ноды | Структура FFL не меняется |
| RESTORED_QUERIES | `*.sql` | Inline-подзапросы в скобках | — |
| MD-отчёты | `*.md` | Раздел inline-подзапросов | — |

**Порядок обновления:** SDBL_PARS → LINE_PARS → FULL_PARS → FFL → RESTORED_QUERIES. Каждый уровень зависит от предыдущего.

## 8. Ограничения

- **Формат первичен** — выходной JSON определяется примером
- **Пример первее кода** — `example_4` генерируется до коммита кода
- **Один уровень** — `child_fields` содержит только прямых потомков
- **Не менять грамматику** — изменения только в visitor и builder-ах
- **Каскадное обновление** — изменение требует переписывания всех downstream builder-ов

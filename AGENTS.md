# AGENTS.md — Инструкции для AI-агентов (bsl-parser / SDQL)

## Назначение

Этот файл содержит контекст, необходимый для работы AI-агентов над проектом `bsl-parser` в части модуля SDQL (SQL-анализатор для 1С).

## Справочные файлы (читать перед работой)

| Файл | Что внутри | Когда читать |
|------|-----------|-------------|
| `PRINCIPLES.md` | Принципы разработки, приоритеты, workflow | **Обязательно** перед любой задачей |
| `PROJECT_STRUCTURE.md` | Структура проекта, ключевые классы, пакеты | При знакомстве с кодовой базой |
| `JAVA_DEVELOPMENT.md` | Gradle, Jackson, Lombok, ANTLR4, тесты | При написании или изменении Java-кода |
| `PARSER_DETAILS.md` | Конвейер обработки, модели, именование узлов | При работе с LINE_PARS / SDBL моделями |
| `EXAMPLES_STORAGE.md` | Директории, workflow примеров, запуск CLI | При генерации примеров |

## Рабочая директория

```
/tmp/bsl-parser
```

Все операции выполняются относительно этой директории.

## Особенности работы с Git

### Ветка

- Основная ветка: `develop`
- Все коммиты идут в `origin/develop`

### Порядок коммитов (критично)

1. **Сначала пример** — сгенерировать `middle_example` артефакты, закоммитить
2. **Потом код** — только после подтверждения пользователя
3. Не коммитить код до проверки примера

### Что коммитить

- **Коммитить**: исходный код (`src/`), примеры для `middle_example` и `union_subquery_example`
- **Не коммитить**: артефакты для `example` и `example_258` (они генерируются только для локальных тестов)

### Сообщения коммитов

- Формат: `SDQL: <краткое описание>`
- В конце: `Refs Redmine #646`
- Примеры:
  - `SDQL: add LINE_PARS hierarchy extraction`
  - `fix: remove type from HierarchyNode output`
  - `SDQL: add middle_example artifacts (iteration 0-1)`

## Особенности Gradle и ANTLR4

- **Не запускать `./gradlew clean`** без крайней необходимости — ANTLR4 генерация занимает время
- Грамматика: `src/main/antlr4/.../SDBL.g4`
- Генерация парсера: `./gradlew generateGrammarSource`
- Компиляция: `./gradlew classes`
- Тесты: `./gradlew test --tests SdqlQueryPackageAnalyzerTest`

## Особенности Jackson

- Для опциональных id использовать `Integer` (wrapper), иначе Jackson не сможет скрыть `null`
- `@JsonInclude(JsonInclude.Include.NON_EMPTY)` скрывает `null` для объектов и пустые коллекции
- Для примитивов (`int`) Jackson **всегда** сериализует значение, даже если оно `0`
- Полиморфные поля (`Object subquery`) требуют кастомного `JsonDeserializer`

## Запуск CLI

```bash
cd /tmp/bsl-parser
./gradlew classes

# ВАЖНО: использовать АБСОЛЮТНЫЙ путь для outputDir
java -cp "build/classes/java/main:build/resources/main:$(find ~/.gradle/caches -name 'antlr4-runtime-4.13.1.jar' -o -name 'jackson-databind-2.17.2.jar' -o -name 'jackson-core-2.17.2.jar' -o -name 'jackson-annotations-2.17.2.jar' -o -name 'lombok-*.jar' | tr '\n' ':')" \
  com.github._1c_syntax.bsl.parser.sdql.SdqlCli \
  examples/middle_example.sql \
  "$(pwd)/examples/SDBL_PARS"
```

**Почему абсолютный путь**: `LineParsModelBuilder` использует `sdblParsDir.getParent().resolve("LINE_PARS")`. Если путь относительный без родителя (например, `examples/SDBL_PARS` из корня), `getParent()` вернёт `null` → NullPointerException.

## Именование артефактов

- `baseName` = имя SQL-файла без расширения
- Примеры:
  - `middle_example.sql` → `sdbl_parse_model_middle_example.json`
  - `middle_example.sql` → `LINE_PARS_hierarchy_middle_example.json`

## Редактирование файлов

- **Всегда** использовать `WriteFile` или `StrReplaceFile` для изменения кода
- **Не использовать** `sed`, `echo`, `cat >` для редактирования Java-файлов — это не надёжно
- Для чтения кода использовать `ReadFile` и `Grep`

## Тестирование

- Минимальный набор: `./gradlew test --tests SdqlQueryPackageAnalyzerTest`
- Должны проходить: `testExampleSql` и `testExample258Sql`
- После добавления новой выжимки — добавить проверку наличия выходного файла в тест

## Примеры SQL-файлов

| Файл | Назначение | Коммитить артефакты? |
|------|-----------|---------------------|
| `examples/example.sql` | Простой тест (5 узлов) | Нет |
| `examples/example_258.sql` | Сложный тест (>100 узлов) | Нет |
| `examples/middle_example.sql` | **Эталонный пример** (26 узлов) | **Да** |
| `examples/sdbl/union_subquery_example.sql` | UNION + подзапросы | **Да** |

## Связь с Redmine

- Задача: **#646**
- Все коммиты должны содержать `Refs Redmine #646`

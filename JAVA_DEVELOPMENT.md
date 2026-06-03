# Особенности разработки Java

## Сборка

- **Gradle 9.5.1** с **Kotlin DSL** (`build.gradle.kts`)
- **Java 17**
- Команды:
  - `./gradlew classes` — компиляция
  - `./gradlew test --tests SdqlQueryPackageAnalyzerTest` — запуск тестов
  - `./gradlew test` — все тесты

## Зависимости

| Библиотека | Версия | Назначение |
|-----------|--------|-----------|
| ANTLR4 | 4.13.1 | Парсер грамматики SDBL |
| Jackson | 2.17.2 | JSON сериализация/десериализация |
| JGraphT | 1.5.2 | Графы (используется в lineage) |
| Lombok | latest | Генерация getter/setter через `@Data` |
| JUnit 5 | Jupiter | Тестирование |
| AssertJ | latest | Fluent assertions в тестах |

## Jackson

- Основной ObjectMapper создаётся через `new ObjectMapper()`
- Для красивого вывода используется `MAPPER.writerWithDefaultPrettyPrinter()`
- `@JsonInclude(JsonInclude.Include.NON_EMPTY)` — скрывает пустые коллекции и `null` для объектов
- `@JsonInclude(JsonInclude.Include.NON_NULL)` — скрывает `null` поля
- `@JsonProperty("snake_case")` — для маппинга camelCase → snake_case
- **Важно**: для примитивов `int` Jackson НЕ умеет скрывать значение. Для опциональных id использовать `Integer` (wrapper), тогда `@JsonInclude(NON_EMPTY)` скроет `null`
- Полиморфные поля (например, `Object subquery`) требуют кастомного `JsonDeserializer` если могут быть и String, и Object

## Lombok

- `@Data` — генерирует getters, setters, `equals`, `hashCode`, `toString`
- `@JsonInclude` работает вместе с Lombok без проблем
- Конструкторов без аргументов не требуется (Jackson использует反射 для создания объектов)

## ANTLR4

- Грамматика SDBL определена в `src/main/antlr4/.../SDBL.g4`
- Генерация парсера: `./gradlew generateGrammarSource`
- Основные классы: `SDBLParser`, `SDBLTokenizer` (обёртка над ANTLR lexer/parser)
- Визитор: `QueryPackageVisitor` — обходит AST и строит `List<QueryAst>`

## Тесты

- Тестовый класс: `SdqlQueryPackageAnalyzerTest`
- Использует `@TempDir` для временных директорий
- Два основных теста: `testExampleSql()` (5 узлов) и `testExample258Sql()` (>100 узлов)
- Для middle_example тест добавляется по мере необходимости
- CLI запускается напрямую через `SdqlCli.main(new String[]{"examples/...", outputPath})`

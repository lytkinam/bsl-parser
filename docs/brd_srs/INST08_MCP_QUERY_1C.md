# INST08: Развёртывание MCP-сервера MCP_QUERY_1C

## 1. Назначение

Документ описывает процесс сборки, развёртывания и установки MCP-сервера MCP_QUERY_1C. Сервер реализован на Java 17 и может быть развёрнут как standalone-приложение, Docker-контейнер или Podman-контейнер.

MCP_QUERY_1C — это сервис-обёртка над внешним 1C MCP-сервером (`1c-mcp-server-popov`), который:
- Получает SQL-запросы по именам параметров регламентированных отчётов 1С.
- Кэширует запросы локально в файловой системе.
- Выполняет полный разбор через SDQL Pipeline (BRD06).
- Предоставляет MCP-интерфейс для анализа кэшированных запросов.

## 2. Требования к окружению

### 2.1 Минимальные требования

| Компонент | Версия | Описание |
|-----------|--------|----------|
| Java | 17+ | OpenJDK или Eclipse Temurin |
| Gradle | 8.5+ | Сборка проекта |
| ANTLR4 Runtime | 4.13.1 | Генерация парсера |
| Jackson | 2.17.2 | JSON сериализация |
| Jetty | 11.0.20 | Встроенный HTTP-сервер |

### 2.2 Опциональные требования

| Компонент | Версия | Описание |
|-----------|--------|----------|
| Docker | 24.0+ | Контейнеризация |
| Podman | 4.0+ | Альтернативная контейнеризация |
| Docker Compose | 2.20+ | Оркестрация контейнеров |

## 3. Структура артефактов сборки

```
build/
├── classes/                    # Скомпилированные классы
├── libs/
│   └── bsl-parser-<version>-all.jar  # Fat JAR (включая зависимости)
├── resources/                  # Ресурсы (грамматика ANTLR4)
└── distributions/
    └── bsl-parser-<version>.zip    # Дистрибутив
```

## 4. Режимы развёртывания

### 4.1 Режим Standalone

#### 4.1.1 Сборка

```bash
cd /tmp/bsl-parser

# Компиляция (без очистки — ANTLR4 генерация занимает время)
./gradlew classes

# Сборка fat JAR
./gradlew shadowJar

# Результат: build/libs/bsl-parser-<version>-all.jar
```

#### 4.1.2 Запуск MCP_QUERY_1C сервера

```bash
java -cp build/libs/bsl-parser-<version>-all.jar \
  com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c.McpQuery1cServer \
  --port=8081 \
  --external-mcp-url=http://192.168.117.247/npf_ops_users_test_popov/hs/mcp \
  --storage-dir=./mcp_query_1c_storage
```

**Параметры:**

| Параметр | Описание | По умолчанию |
|----------|----------|--------------|
| `--port` | Порт HTTP-сервера MCP | `8081` |
| `--external-mcp-url` | URL внешнего 1C MCP-сервера | `http://192.168.117.247/npf_ops_users_test_popov/hs/mcp` |
| `--storage-dir` | Директория для хранения кэша | `./mcp_query_1c_storage` |
| `--config` | Путь к properties-файлу | `mcp_query_1c.properties` |

#### 4.1.3 Конфигурационный файл

```properties
# mcp_query_1c.properties
server.port=8081
external.mcp.url=http://192.168.117.247/npf_ops_users_test_popov/hs/mcp
storage.dir=./mcp_query_1c_storage
```

---

### 4.2 Режим Docker

#### 4.2.1 Dockerfile

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="SDQL Team"
LABEL description="MCP_QUERY_1C Server — 1C query parameter cache and analysis"

# Создание пользователя
RUN addgroup -g 1000 sdql && \
    adduser -u 1000 -G sdql -s /bin/sh -D sdql

# Директории
RUN mkdir -p /app /app/storage && \
    chown -R sdql:sdql /app

WORKDIR /app

# Копирование JAR
COPY --chown=sdql:sdql build/libs/bsl-parser-*-all.jar app.jar

# Переключение на пользователя
USER sdql

# Порты
EXPOSE 15372

# Точка входа
ENTRYPOINT ["java", "-cp", "/app/app.jar", "com.github._1c_syntax.bsl.parser.sdql.mcp_query_1c.McpQuery1cServer"]
CMD ["--port=15372", "--storage-dir=/app/storage", "--external-mcp-url=http://192.168.117.247/npf_ops_users_test_popov/hs/mcp"]
```

#### 4.2.2 Сборка образа

```bash
cd /tmp/bsl-parser

# Сборка JAR
./gradlew shadowJar

# Сборка Docker-образа
docker build -f Dockerfile.mcp_query_1c -t mcp-query-1c:latest .
```

#### 4.2.3 Запуск контейнера

```bash
# Базовый запуск
docker run -d \
  --name mcp-query-1c \
  -p 15372:15372 \
  -v /host/mcp-query-1c-storage:/app/storage:rw \
  mcp-query-1c:latest

# С кастомными параметрами
docker run -d \
  --name mcp-query-1c \
  -p 15372:15372 \
  -v /host/mcp-query-1c-storage:/app/storage:rw \
  mcp-query-1c:latest \
  --port=15372 --external-mcp-url=http://other-host/hs/mcp
```

#### 4.2.4 Docker Compose

```yaml
# ~/mcp-query-1c/docker-compose.yml
services:
  mcp-query-1c:
    build:
      context: /tmp/bsl-parser
      dockerfile: Dockerfile.mcp_query_1c
    image: mcp-query-1c:latest
    container_name: mcp-query-1c
    ports:
      - "15372:15372"
    volumes:
      - ./storage:/app/storage:rw
    networks:
      - ai-network
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:15372/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 512M

networks:
  ai-network:
    external: true
```

```bash
# Запуск
cd ~/mcp-query-1c && docker compose up -d

# Просмотр логов
docker compose logs -f mcp-query-1c

# Остановка
docker compose down
```

---

### 4.3 Режим Podman

#### 4.3.1 Сборка образа

```bash
cd /tmp/bsl-parser
./gradlew shadowJar
podman build -f Dockerfile.mcp_query_1c -t mcp-query-1c:latest .
```

#### 4.3.2 Запуск контейнера (rootless)

```bash
mkdir -p ~/.local/share/mcp-query-1c/storage

podman run -d \
  --name mcp-query-1c \
  --userns=keep-id \
  -p 15372:15372 \
  -v ~/.local/share/mcp-query-1c/storage:/app/storage:Z \
  mcp-query-1c:latest
```

**Особенности Podman:**
- Флаг `:Z` для SELinux-меток (обязателен на RHEL/CentOS/Fedora)
- `--userns=keep-id` для сохранения UID пользователя
- Rootless-режим по умолчанию

#### 4.3.3 Podman Quadlet (systemd-интеграция)

```ini
# ~/.config/containers/systemd/mcp-query-1c.container
[Container]
Image=mcp-query-1c:latest
ContainerName=mcp-query-1c
PublishPort=15372:15372
Volume=%h/.local/share/mcp-query-1c/storage:/app/storage:Z

[Service]
Restart=always

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user start mcp-query-1c
systemctl --user enable mcp-query-1c
```

## 5. Подготовка хранилища

Перед первым запуском необходимо подготовить директорию хранилища с правильными правами:

```bash
mkdir -p ~/mcp-query-1c/storage
sudo chown -R 1000:1000 ~/mcp-query-1c/storage
```

Или через скрипт:

```bash
~/.config/agents/skills/ubuntu-admin/scripts/ai-init-sdql-volumes.sh
```

## 6. Структура хранилища

```
mcp_query_1c_storage/
├── parameters.json              # Реестр всех параметров
├── <sanitized_name>/            # Директория параметра
│   ├── query.sql                # Исходный SQL-запрос
│   ├── parameter.json           # Метаданные
│   ├── SDBL_PARS/               # SDBL модель (итерация 0)
│   ├── LINE_PARS/               # LINE_PARS модель и иерархия
│   ├── FULL_PARS/               # FULL_PARS модель
│   ├── field_lineage/           # Lineage полей LINE_PARS
│   ├── full_field_lineage/      # Full field lineage FULL_PARS
│   ├── RESTORED_QUERIES/        # Восстановленные SQL
│   ├── EXTRACTED_QUERIES/       # Извлечённые запросы
│   └── VERIFICATION/            # Отчёты верификации
```

## 7. Конфигурация MCP-клиента

### 7.1 Регистрация в Kimi CLI

```json
// ~/.kimi/mcp.json
{
  "mcpServers": {
    "mcp-query-1c": {
      "url": "http://localhost:15372/mcp",
      "transport": "http",
      "headers": {}
    }
  }
}
```

### 7.2 Проверка подключения

```bash
# Healthcheck
curl -s http://localhost:15372/health
# {"status":"ok"}

# Список инструментов
curl -s -X POST \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  http://localhost:15372/mcp
```

## 8. Примеры запросов

### 8.1 Добавить параметр из 1С

```bash
curl -s -X POST http://localhost:15372/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "add_parameter",
      "arguments": {
        "name": "258_произвЗапр_такс6.0",
        "forceRefresh": false
      }
    }
  }'
```

### 8.2 Добавить произвольный запрос

```bash
curl -s -X POST http://localhost:15372/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "add_custom_query",
      "arguments": {
        "name": "my_custom_query",
        "sqlText": "ВЫБРАТЬ 1 КАК Поле1 ПОМЕСТИТЬ ВТ_Тест"
      }
    }
  }'
```

### 8.3 Получить full field lineage

```bash
curl -s -X POST http://localhost:15372/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "get_full_field_lineage",
      "arguments": {
        "parameterName": "258_произвЗапр_такс6.0",
        "nodeName": "ВТ_Суммы_ПР_ТранзитныеВиды",
        "aliases": ["Взносы", "ПенсионныйСчет"]
      }
    }
  }'
```

### 8.4 Получить восстановленный SQL

```bash
curl -s -X POST http://localhost:15372/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "get_restored_query",
      "arguments": {
        "parameterName": "258_произвЗапр_такс6.0",
        "nodeName": "ВТ_Суммы_ПР_ТранзитныеВиды",
        "aliases": ["Взносы", "ПенсионныйСчет"],
        "format": "sql"
      }
    }
  }'
```

## 9. Мониторинг и логирование

### 9.1 Логи контейнера

```bash
# Docker
docker logs -f mcp-query-1c

# Podman
podman logs -f mcp-query-1c
```

### 9.2 Метрики

| Эндпоинт | Описание |
|----------|----------|
| `GET /health` | Healthcheck (200 OK) |
| `POST /mcp` | MCP JSON-RPC 2.0 endpoint |

## 10. Обновление

### 10.1 Обновление образа

```bash
cd /tmp/bsl-parser
./gradlew shadowJar
docker build -f Dockerfile.mcp_query_1c -t mcp-query-1c:latest .

docker stop mcp-query-1c
docker rm mcp-query-1c
docker run -d --name mcp-query-1c -p 15372:15372 \
  -v ~/mcp-query-1c/storage:/app/storage:rw \
  mcp-query-1c:latest
```

### 10.2 Rolling update (Docker Compose)

```bash
cd ~/mcp-query-1c
docker compose pull
docker compose up -d
```

## 11. Безопасность

### 11.1 Рекомендации

- Запускать контейнер от непривилегированного пользователя (`USER sdql`)
- Ограничить ресурсы CPU/память
- Использовать read-only root filesystem (опционально)
- Не экспонировать порт наружу без reverse proxy
- Хранить `mcp_query_1c_storage` на зашифрованном диске (опционально)

### 11.2 Ограничения ресурсов

```bash
docker run -d \
  --name mcp-query-1c \
  -p 127.0.0.1:15372:15372 \
  --memory=512m \
  --cpus=1.0 \
  --read-only \
  --tmpfs /tmp:noexec,nosuid,size=100m \
  -v /host/mcp-query-1c-storage:/app/storage:rw \
  mcp-query-1c:latest
```

## 12. Troubleshooting

| Симптом | Причина | Решение |
|---------|---------|---------|
| `Connection refused` | Сервер не запущен | Проверить `docker ps` / `podman ps` |
| `Permission denied` на `/app/storage` | Неправильные права volume | `sudo chown -R 1000:1000 ~/mcp-query-1c/storage` |
| `Failed to save parameter` | Ошибка сериализации `Instant` | Убедиться, что JAR собран с `jackson-datatype-jsr310` |
| `ARTIFACT_NOT_FOUND` | Неправильный путь к артефактам | Проверить `getArtifactsDir()` → `.../SDBL_PARS`, `createRepoForParameter()` → parent dir |
| `SELinux denied` | Контекст безопасности | Использовать флаг `:Z` для volumes (Podman) |
| `OutOfMemoryError` | Недостаточно памяти | Увеличить `--memory` или `-Xmx` |
| `ANTLR generation slow` | Первый запуск после clean | Не запускать `./gradlew clean` без необходимости |
| Пустой ответ от 1C MCP | Параметр не найден | Проверить URL и имя параметра в 1С |

## 13. Связь с другими документами

| Документ | Описание |
|----------|----------|
| SRS08_MCP_QUERY_1C.md | Спецификация системных требований |
| BRD08_MCP_QUERY_1C.md | Бизнес-требования |
| SRS06_03.md | Docker-интеграция SDQL MCP |
| INST06_MCP.md | Развёртывание SDQL MCP |

---

*Refs Redmine #646*

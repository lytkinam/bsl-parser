# INST06: Развёртывание MCP-сервера SDQL

## 1. Назначение

Документ описывает процесс сборки, развёртывания и установки MCP-сервера SDQL. Сервер реализован на Java 17 и может быть развёрнут как standalone-приложение, Docker-контейнер или Podman-контейнер.

## 2. Требования к окружению

### 2.1 Минимальные требования

| Компонент | Версия | Описание |
|-----------|--------|----------|
| Java | 17+ | OpenJDK или Oracle JDK |
| Gradle | 8.5+ | Сборка проекта |
| ANTLR4 Runtime | 4.13.1 | Генерация парсера |
| Jackson | 2.17.2 | JSON сериализация |
| JGraphT | 1.5.2 | Графы зависимостей |

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

#### 4.1.2 Запуск MCP-сервера

```bash
# Запуск с указанием порта и директории артефактов
java -jar build/libs/bsl-parser-<version>-all.jar \
  --mode=mcp \
  --port=8080 \
  --artifacts-dir=/var/sdql/artifacts
```

**Параметры:**

| Параметр | Описание | По умолчанию |
|----------|----------|--------------|
| `--mode` | Режим работы: `cli` или `mcp` | `cli` |
| `--port` | Порт HTTP-сервера MCP | `8080` |
| `--artifacts-dir` | Директория для хранения артефактов | `./artifacts` |
| `--temp-dir` | Директория для временных файлов (Online-режим) | `./tmp` |
| `--log-level` | Уровень логирования: `DEBUG`, `INFO`, `WARN`, `ERROR` | `INFO` |

---

### 4.2 Режим Docker

#### 4.2.1 Dockerfile

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="SDQL Team"
LABEL description="MCP Server for SDQL (SQL parser for 1C)"

# Создание пользователя
RUN addgroup -g 1000 sdql && \
    adduser -u 1000 -G sdql -s /bin/sh -D sdql

# Директории
RUN mkdir -p /app /var/sdql/artifacts /var/sdql/tmp && \
    chown -R sdql:sdql /app /var/sdql

WORKDIR /app

# Копирование JAR
COPY --chown=sdql:sdql build/libs/bsl-parser-*-all.jar app.jar

# Переключение на пользователя
USER sdql

# Порты
EXPOSE 8080

# Точка входа
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["--mode=mcp", "--port=8080", "--artifacts-dir=/var/sdql/artifacts", "--temp-dir=/var/sdql/tmp"]
```

#### 4.2.2 Сборка образа

```bash
cd /tmp/bsl-parser

# Сборка JAR
./gradlew shadowJar

# Сборка Docker-образа
docker build -t sdql-mcp:latest -t sdql-mcp:$(git describe --tags --always) .
```

#### 4.2.3 Запуск контейнера

```bash
# Базовый запуск
docker run -d \
  --name sdql-mcp \
  -p 8080:8080 \
  -v /host/artifacts:/var/sdql/artifacts:rw \
  sdql-mcp:latest

# С кастомными параметрами
docker run -d \
  --name sdql-mcp \
  -p 9090:8080 \
  -v /host/artifacts:/var/sdql/artifacts:rw \
  -v /host/tmp:/var/sdql/tmp:rw \
  -e LOG_LEVEL=DEBUG \
  sdql-mcp:latest \
  --mode=mcp --port=8080 --log-level=DEBUG
```

#### 4.2.4 Docker Compose

```yaml
# docker-compose.yml
version: "3.8"

services:
  sdql-mcp:
    build:
      context: .
      dockerfile: Dockerfile
    image: sdql-mcp:latest
    container_name: sdql-mcp
    ports:
      - "8080:8080"
    volumes:
      - ./artifacts:/var/sdql/artifacts:rw
      - ./tmp:/var/sdql/tmp:rw
    environment:
      - LOG_LEVEL=INFO
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s
```

```bash
# Запуск
docker-compose up -d

# Просмотр логов
docker-compose logs -f sdql-mcp

# Остановка
docker-compose down
```

---

### 4.3 Режим Podman

#### 4.3.1 Сборка образа

```bash
cd /tmp/bsl-parser

# Сборка JAR
./gradlew shadowJar

# Сборка Podman-образа (rootless)
podman build -t sdql-mcp:latest .
```

#### 4.3.2 Запуск контейнера (rootless)

```bash
# Создание директорий
mkdir -p ~/.local/share/sdql/artifacts ~/.local/share/sdql/tmp

# Запуск контейнера
podman run -d \
  --name sdql-mcp \
  --userns=keep-id \
  -p 8080:8080 \
  -v ~/.local/share/sdql/artifacts:/var/sdql/artifacts:Z \
  -v ~/.local/share/sdql/tmp:/var/sdql/tmp:Z \
  sdql-mcp:latest
```

**Особенности Podman:**
- Флаг `:Z` для SELinux-меток (обязателен на RHEL/CentOS/Fedora)
- `--userns=keep-id` для сохранения UID пользователя
- Rootless-режим по умолчанию

#### 4.3.3 Podman Quadlet (systemd-интеграция)

```ini
# ~/.config/containers/systemd/sdql-mcp.container
[Container]
Image=sdql-mcp:latest
ContainerName=sdql-mcp
PublishPort=8080:8080
Volume=%h/.local/share/sdql/artifacts:/var/sdql/artifacts:Z
Volume=%h/.local/share/sdql/tmp:/var/sdql/tmp:Z

[Service]
Restart=always

[Install]
WantedBy=default.target
```

```bash
# Активация
systemctl --user daemon-reload
systemctl --user start sdql-mcp
systemctl --user enable sdql-mcp
```

#### 4.3.4 Podman Compose

```bash
# Использование podman-compose (drop-in замена docker-compose)
podman-compose up -d

# Или через quadlet
podman-compose -f podman-compose.yml up -d
```

## 5. Конфигурация MCP-клиента

### 5.1 Регистрация в Kimi CLI

```json
// ~/.kimi/mcp.json
{
  "mcpServers": {
    "sdql-mcp": {
      "url": "http://localhost:8080/mcp",
      "transport": "http",
      "headers": {}
    }
  }
}
```

### 5.2 Проверка подключения

```bash
# Проверка healthcheck
curl http://localhost:8080/health

# Проверка MCP endpoint
curl -s -X POST \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  http://localhost:8080/mcp
```

## 6. Управление артефактами

### 6.1 Директория артефактов

```
/var/sdql/artifacts/
├── <baseName_1>/
│   ├── SDBL_PARS/
│   ├── LINE_PARS/
│   ├── FULL_PARS/
│   ├── field_lineage/
│   ├── full_field_lineage/
│   ├── RESTORED_QUERIES/
│   ├── EXTRACTED_QUERIES/
│   └── VERIFICATION/
├── <baseName_2>/
│   └── ...
```

### 6.2 Монтирование внешнего хранилища

```bash
# Docker: монтирование NFS/SMB
docker run -d \
  --name sdql-mcp \
  -p 8080:8080 \
  -v /mnt/nfs/sdql-artifacts:/var/sdql/artifacts:rw \
  sdql-mcp:latest

# Podman: монтирование с SELinux
podman run -d \
  --name sdql-mcp \
  -p 8080:8080 \
  -v /mnt/nfs/sdql-artifacts:/var/sdql/artifacts:Z \
  sdql-mcp:latest
```

## 7. Мониторинг и логирование

### 7.1 Логи контейнера

```bash
# Docker
docker logs -f sdql-mcp

# Podman
podman logs -f sdql-mcp
```

### 7.2 Метрики (опционально)

| Эндпоинт | Описание |
|----------|----------|
| `GET /health` | Healthcheck (200 OK) |
| `GET /metrics` | Prometheus-метрики (опционально) |

## 8. Обновление

### 8.1 Обновление образа

```bash
# Сборка новой версии
./gradlew shadowJar
docker build -t sdql-mcp:latest .

# Перезапуск с сохранением артефактов
docker stop sdql-mcp
docker rm sdql-mcp
docker run -d --name sdql-mcp -p 8080:8080 \
  -v /host/artifacts:/var/sdql/artifacts:rw \
  sdql-mcp:latest
```

### 8.2 Rolling update (Docker Compose)

```bash
docker-compose pull
docker-compose up -d
```

## 9. Безопасность

### 9.1 Рекомендации

- Запускать контейнер от непривилегированного пользователя (`USER sdql`)
- Ограничить ресурсы CPU/память
- Использовать read-only root filesystem (опционально)
- Не экспонировать порт наружу без reverse proxy

### 9.2 Ограничения ресурсов

```bash
# Docker
docker run -d \
  --name sdql-mcp \
  -p 127.0.0.1:8080:8080 \
  --memory=512m \
  --cpus=1.0 \
  --read-only \
  --tmpfs /var/sdql/tmp:noexec,nosuid,size=100m \
  -v /host/artifacts:/var/sdql/artifacts:rw \
  sdql-mcp:latest
```

## 10. Troubleshooting

| Симптом | Причина | Решение |
|---------|---------|---------|
| `Connection refused` | Сервер не запущен | Проверить `docker ps` / `podman ps` |
| `Permission denied` | Проблемы с volumes | Проверить права на директории артефактов |
| `SELinux denied` | Контекст безопасности | Использовать флаг `:Z` для volumes |
| `OutOfMemoryError` | Недостаточно памяти | Увеличить `--memory` или `-Xmx` |
| `ANTLR generation slow` | Первый запуск после clean | Не запускать `./gradlew clean` без необходимости |

---

*Refs Redmine #646*

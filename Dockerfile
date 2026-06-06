# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="SDQL Team"
LABEL description="MCP Server for SDQL (SQL parser for 1C)"

# Install wget for healthcheck
RUN apk add --no-cache wget

# Create non-privileged user
RUN addgroup -g 1000 sdql && \
    adduser -u 1000 -G sdql -s /bin/sh -D sdql

# Pre-create working directories with correct ownership
RUN mkdir -p /app/artifacts /tmp && \
    chown -R sdql:sdql /app /tmp

WORKDIR /app

# Copy JAR with correct ownership
COPY --chown=sdql:sdql build/libs/*-all.jar app.jar

# Switch to non-privileged user
USER sdql

# Ports
EXPOSE 5372

# Healthcheck
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD wget -qO- http://127.0.0.1:5372/health >/dev/null 2>&1 || exit 1

# Entrypoint
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["--port=5372", "--artifacts-dir=/app/artifacts", "--temp-dir=/tmp"]

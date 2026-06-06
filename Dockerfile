FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY build/libs/*-all.jar app.jar
EXPOSE 5372
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["--port=5372", "--artifacts-dir=/app/artifacts", "--temp-dir=/tmp"]

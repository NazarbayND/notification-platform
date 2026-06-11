FROM eclipse-temurin:21-jre

WORKDIR /app
ARG JAR_FILE=target/outbox-publisher-service.jar
COPY ${JAR_FILE} app.jar

ENV SERVER_PORT=8084
EXPOSE 8084

ENTRYPOINT ["java", "-jar", "/app/app.jar"]


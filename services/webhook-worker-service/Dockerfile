FROM eclipse-temurin:21-jre

WORKDIR /app
ARG JAR_FILE=target/webhook-worker-service.jar
COPY ${JAR_FILE} app.jar

ENV SERVER_PORT=8090
EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

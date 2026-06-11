FROM eclipse-temurin:21-jre

WORKDIR /app
ARG JAR_FILE=target/notification-api-service.jar
COPY ${JAR_FILE} app.jar

ENV SERVER_PORT=8081
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]


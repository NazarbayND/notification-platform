FROM eclipse-temurin:21-jre

WORKDIR /app
ARG JAR_FILE=target/push-worker-service.jar
COPY ${JAR_FILE} app.jar

ENV SERVER_PORT=8087
EXPOSE 8087

ENTRYPOINT ["java", "-jar", "/app/app.jar"]


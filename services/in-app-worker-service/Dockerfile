FROM eclipse-temurin:21-jre

WORKDIR /app
ARG JAR_FILE=target/in-app-worker-service.jar
COPY ${JAR_FILE} app.jar

ENV SERVER_PORT=8089
EXPOSE 8089

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

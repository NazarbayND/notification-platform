FROM eclipse-temurin:21-jre

WORKDIR /app
ARG JAR_FILE=target/email-worker-service.jar
COPY ${JAR_FILE} app.jar

ENV SERVER_PORT=8085
EXPOSE 8085

ENTRYPOINT ["java", "-jar", "/app/app.jar"]


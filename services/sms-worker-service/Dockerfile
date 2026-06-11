FROM eclipse-temurin:21-jre

WORKDIR /app
ARG JAR_FILE=target/sms-worker-service.jar
COPY ${JAR_FILE} app.jar

ENV SERVER_PORT=8086
EXPOSE 8086

ENTRYPOINT ["java", "-jar", "/app/app.jar"]


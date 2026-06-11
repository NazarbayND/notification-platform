FROM eclipse-temurin:21-jre

WORKDIR /app
ARG JAR_FILE=target/template-service.jar
COPY ${JAR_FILE} app.jar

ENV SERVER_PORT=8082
EXPOSE 8082

ENTRYPOINT ["java", "-jar", "/app/app.jar"]


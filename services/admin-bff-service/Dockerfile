FROM eclipse-temurin:21-jre

WORKDIR /app
ARG JAR_FILE=target/admin-bff-service.jar
COPY ${JAR_FILE} app.jar

ENV SERVER_PORT=8088
EXPOSE 8088

ENTRYPOINT ["java", "-jar", "/app/app.jar"]


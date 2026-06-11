FROM eclipse-temurin:21-jre

WORKDIR /app
ARG JAR_FILE=target/preference-service.jar
COPY ${JAR_FILE} app.jar

ENV SERVER_PORT=8083
EXPOSE 8083

ENTRYPOINT ["java", "-jar", "/app/app.jar"]


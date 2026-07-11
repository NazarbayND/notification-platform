FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/notification-orchestrator-service.jar app.jar
ENV SERVER_PORT=8091
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

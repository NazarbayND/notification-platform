FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/notification-projection-service.jar app.jar
ENV SERVER_PORT=8092
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

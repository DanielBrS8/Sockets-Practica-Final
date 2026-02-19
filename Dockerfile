FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY target/ServerSockerMi-1.0-SNAPSHOT.jar app.jar

EXPOSE 7070

ENTRYPOINT ["java", "-jar", "app.jar"]

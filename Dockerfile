FROM eclipse-temurin:25-jdk AS build

WORKDIR /app

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:resolve

COPY src src
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /app/target/ServerSockerMi-1.0-SNAPSHOT.jar app.jar

EXPOSE 7070

ENTRYPOINT ["java", "-jar", "app.jar"]

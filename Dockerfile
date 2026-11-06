# Compilación estable en Railway sin descargar Maven a través de Mise.
FROM maven:3.9.13-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests clean package

# Imagen final: solo Java y la API, sin Maven ni código fuente.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/jm-aromas-api-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

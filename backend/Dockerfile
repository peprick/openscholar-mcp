FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
RUN ./mvnw --batch-mode --no-transfer-progress package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/target/openscholar-backend-*.jar application.jar

USER 10001
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/application.jar"]

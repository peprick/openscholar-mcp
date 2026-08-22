FROM eclipse-temurin:21-jdk@sha256:32a5f68f3bb3ace880243c9e55c89ad8fb09e5de904b6c8990693b103840c4d3 AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress package -DskipTests

FROM eclipse-temurin:21-jre@sha256:fcd44c3caa637c915b17688363b90cc9b0a5b7b66fac76fc5bc6d4e216abc16f

WORKDIR /app
COPY --from=build /workspace/target/openscholar-backend-*.jar application.jar

USER 10001
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/application.jar"]

FROM eclipse-temurin:21-jdk@sha256:32a5f68f3bb3ace880243c9e55c89ad8fb09e5de904b6c8990693b103840c4d3 AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress package -DskipTests

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:15e24b3e7b4563992cb6956feee510e647616fcd5a714c6e29743e6fde53f3f5

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/openscholar-backend-*.jar application.jar

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["/usr/bin/java", "-jar", "/app/application.jar"]

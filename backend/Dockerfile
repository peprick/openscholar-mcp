FROM eclipse-temurin:21-jdk@sha256:85f00967bcc624fc19fa9c2cf124ea426a5363898e267141726f31f358c2e14b AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress package -DskipTests

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:15e24b3e7b4563992cb6956feee510e647616fcd5a714c6e29743e6fde53f3f5

LABEL org.opencontainers.image.title="OpenScholar Backend" \
      org.opencontainers.image.description="Spring Boot research discovery and MCP backend" \
      org.opencontainers.image.source="https://github.com/peprick/openscholar-mcp"

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/openscholar-backend-*.jar application.jar

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["/usr/bin/java", "-jar", "/app/application.jar"]

# syntax=docker/dockerfile:1.7
# Multi-stage build for every Java service. Build context = repository root.
#   docker build -f infrastructure/docker/java-service.Dockerfile --build-arg MODULE=services/catalog .
# Target "runtime" builds inside Docker (CI, multi-arch). Target "runtime-prebuilt" packages a jar built
# on the host by `make build` (fast local loop, no second Maven cache).
ARG MODULE=services/catalog

FROM maven:3.9.11-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY .mvn .mvn
COPY packages packages
COPY services services
COPY workers workers
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -Dmaven.repo.local=/root/.m2/repository -pl ${MODULE} -am package -DskipTests && \
    cp ${MODULE}/target/app.jar /app.jar

FROM eclipse-temurin:21-jre-alpine AS base
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app && apk add --no-cache wget
WORKDIR /app
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM base AS runtime-prebuilt
ARG MODULE
COPY --chown=10001:10001 ${MODULE}/target/app.jar /app/app.jar

FROM base AS runtime
COPY --from=build --chown=10001:10001 /app.jar /app/app.jar

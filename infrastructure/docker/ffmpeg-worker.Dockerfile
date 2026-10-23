# syntax=docker/dockerfile:1.7
# FFmpeg worker. Contains Debian/Ubuntu ffmpeg (GPL build with libx264): distributing this image is
# distribution of GPL binaries — corresponding source is the distro's source packages (see docs/cost/FREE_TIER_VERIFICATION.md).
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY .mvn .mvn
COPY packages packages
COPY services services
COPY workers workers
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -Dmaven.repo.local=/root/.m2/repository -pl workers/ffmpeg -am package -DskipTests && cp workers/ffmpeg/target/app.jar /app.jar

FROM eclipse-temurin:21-jre-noble AS base
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/* \
 && groupadd -g 10001 app && useradd -u 10001 -g app -M -s /usr/sbin/nologin app \
 && mkdir -p /work && chown 10001:10001 /work
WORKDIR /app
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=40 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM base AS runtime-prebuilt
COPY --chown=10001:10001 workers/ffmpeg/target/app.jar /app/app.jar

FROM base AS runtime
COPY --from=build --chown=10001:10001 /app.jar /app/app.jar

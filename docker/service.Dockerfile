# syntax=docker/dockerfile:1.7
#
# One multi-stage image for every Spring Boot service. The service is selected with
#   --build-arg SERVICE=catalog
# where the value is the Gradle project name from settings.gradle.kts.
#
# Tests are skipped on purpose: the suite drives Testcontainers and needs a Docker socket the
# build must not have. `bootJar` is the artifact the runtime stage copies, so the image never
# depends on a jar someone built on the host.
ARG SERVICE=catalog

FROM eclipse-temurin:21-jdk-alpine AS build
ARG SERVICE
WORKDIR /workspace
# Layers are ordered from the least to the most volatile so a source change does not invalidate
# the wrapper or the dependency resolution inside the cache mount.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY services ./services
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
 && ./gradlew --no-daemon --console=plain ":${SERVICE}:bootJar" -x test \
 && find "services/${SERVICE}/build/libs" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' \
      -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:21-jre-alpine AS runtime
# curl is here only so Compose (and `docker run`) can healthcheck the Actuator endpoint from
# inside the container; the Kubernetes probes call it over HTTP from the kubelet.
RUN apk add --no-cache curl \
 && addgroup -g 10001 -S eventflow \
 && adduser -u 10001 -S -G eventflow -h /app eventflow
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar
# The Boot services read their port and datasource from the environment (see application.yml).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
USER 10001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

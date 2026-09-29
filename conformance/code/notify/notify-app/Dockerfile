# syntax=docker/dockerfile:1.7

# Notify service image. Same build contract as build/maven/Dockerfile
# (repo-root context, WORK_DIR build-arg) but a dedicated Dockerfile because
# notify is a multi-module Maven build: notify-app must be packaged together
# with its sibling notify-spi module via the parent pom.
#
# WORK_DIR points at the notify-app module (src/services/notify/notify-app);
# the parent pom, notify-spi and the provider jars live one level up at
# ${WORK_DIR}/..

# ---------- Build stage: compile + package the Spring Boot jar ----------
FROM maven:3.9-eclipse-temurin-25 AS builder
ARG WORK_DIR
WORKDIR /build

# Poms first so the dependency layer is cached across source-only changes.
COPY ${WORK_DIR}/../pom.xml ./pom.xml
COPY ${WORK_DIR}/../notify-spi/pom.xml notify-spi/
COPY ${WORK_DIR}/pom.xml notify-app/
RUN mvn dependency:go-offline -B -q

COPY ${WORK_DIR}/../notify-spi/src notify-spi/src
COPY ${WORK_DIR}/src notify-app/src
RUN mvn package -pl notify-app -am -DskipTests -B -q

# ---------- Runtime stage: run the jar ----------
FROM eclipse-temurin:25-jre-alpine
# Re-declared because a build arg is scoped to the stage that declares it; without
# this the provider COPY below silently resolves to /../providers and fails.
ARG WORK_DIR
RUN addgroup -S notify && adduser -S notify -G notify
WORKDIR /app
COPY --from=builder /build/notify-app/target/notify-app-*.jar app.jar
# Channel providers (notify-spi implementations), baked in the same way the
# keycloak image carries its SPI jars. The loader scans this directory once at
# startup, so anything added here needs a rebuild -- there is no hot reload.
# A provider whose credentials are absent stays unregistered rather than
# failing the pod.
COPY --chown=notify:notify ${WORK_DIR}/../providers/ /providers/
USER notify
EXPOSE 8080
ENTRYPOINT ["java", "--enable-preview", "-jar", "/app/app.jar"]

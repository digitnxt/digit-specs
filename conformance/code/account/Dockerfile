# syntax=docker/dockerfile:1.7

# Account service image. Same Java 25 / Maven build as build/maven/Dockerfile,
# but with a dedicated Dockerfile because the runtime image also needs the
# Keycloak config files placed on the filesystem at the paths the service reads
# by default (internal/keycloak/), mirroring the original Go image layout.

# ---------- Build stage: compile + package the Spring Boot jar ----------
FROM amazoncorretto:25 AS build
ARG WORK_DIR
WORKDIR /app

RUN dnf install -y maven && dnf clean all

COPY ${WORK_DIR}/pom.xml ./pom.xml
COPY ${WORK_DIR}/src ./src
RUN mvn -B -f /app/pom.xml clean package -DskipTests

# ---------- Runtime stage: run the jar ----------
FROM amazoncorretto:25
ARG WORK_DIR
WORKDIR /opt/egov

COPY --from=build /app/target/*.jar /opt/egov/app.jar
COPY build/maven/start.sh /opt/egov/start.sh

COPY ${WORK_DIR}/src/main/resources/realm_config.json /opt/egov/internal/keycloak/realm_config.json

RUN chmod +x /opt/egov/start.sh

EXPOSE 8080
CMD ["/opt/egov/start.sh"]
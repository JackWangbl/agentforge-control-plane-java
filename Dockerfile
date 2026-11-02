FROM maven:3.9.16-eclipse-temurin-21 AS builder
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system agentforge \
    && useradd --system --gid agentforge --home-dir /app agentforge \
    && mkdir -p /app/workspaces /app/rag-files \
    && chown -R agentforge:agentforge /app

COPY --from=builder --chown=agentforge:agentforge \
    /workspace/target/agentforge-control-plane-1.0.0.jar /app/agentforge.jar

USER agentforge
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/agentforge.jar"]


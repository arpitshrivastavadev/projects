FROM gradle:8.10.2-jdk17 AS builder
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY common common
COPY event-service event-service

RUN chmod +x gradlew && ./gradlew :event-service:bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy
RUN groupadd -r police && useradd -r -g police police
USER police
WORKDIR /app
COPY --from=builder /workspace/event-service/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]

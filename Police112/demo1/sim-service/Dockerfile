FROM gradle:8.10.2-jdk17 AS builder
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle build.gradle ./
COPY common common
COPY sim-service sim-service

RUN chmod +x gradlew && ./gradlew :sim-service:bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy
RUN groupadd -r police && useradd -r -g police police
USER police
WORKDIR /app
COPY --from=builder /workspace/sim-service/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]

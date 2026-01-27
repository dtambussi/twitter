FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src src

RUN ./gradlew build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

COPY --from=builder /app/build/libs/*.jar app.jar

USER appuser

EXPOSE 8080

# JVM tuning for stress testing:
# -XX:MaxRAMPercentage=75: Use up to 75% of container memory for heap
# -Djdk.tracePinnedThreads=short: Log when virtual threads get pinned
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djdk.tracePinnedThreads=short", "-jar", "app.jar"]

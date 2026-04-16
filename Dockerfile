# Stage 1 — build
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY src src
RUN chmod +x gradlew && ./gradlew -q -x test bootJar

# Stage 2 — extract layered jar
FROM eclipse-temurin:25-jdk-alpine AS extractor
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Stage 3 — minimal runtime image
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Copy layers in dependency-change order (least → most frequent)
COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/ ./

RUN addgroup -g 1000 spring && adduser -u 1000 -G spring -s /bin/sh -D spring
USER 1000:1000

EXPOSE 8080

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

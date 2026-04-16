# Stage 1 — extract layers from pre-built JAR (produced by CI build-and-test job)
FROM eclipse-temurin:25-jre-alpine AS extractor
WORKDIR /app
COPY build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Stage 2 — minimal runtime image
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

ENTRYPOINT ["java", "-XX:+UseCompactObjectHeaders", "org.springframework.boot.loader.launch.JarLauncher"]

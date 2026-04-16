# Stage 1 — extract layers from pre-built JAR (produced by CI build-and-test job)
# Pin to digest for supply-chain safety; update via: docker pull eclipse-temurin:25-jre-alpine
FROM eclipse-temurin:25-jre-alpine@sha256:5fcc27581b238efbfda93da3a103f59e0b5691fe522a7ac03fe8057b0819c888 AS extractor
WORKDIR /app
COPY build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Stage 2 — minimal runtime image
FROM eclipse-temurin:25-jre-alpine@sha256:5fcc27581b238efbfda93da3a103f59e0b5691fe522a7ac03fe8057b0819c888
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

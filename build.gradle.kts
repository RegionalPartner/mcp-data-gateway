import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask


plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("jacoco")
    id("checkstyle")
    id("com.github.spotbugs") version "6.5.0"
    id("org.owasp.dependencycheck") version "9.2.0"
}

group = "io.ancoris"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.1.4")
        mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
    }
    dependencies {
        // MCP SDK 0.17.2 adds ProtocolVersions.MCP_2025_11_25 (required by Claude Code 2.1.x)
        // while remaining API-compatible with mcp-annotations:0.8.0 used by Spring AI 1.1.4.
        // (0.18.x removed McpJsonMapper.createDefault(), breaking mcp-annotations:0.8.0)
        dependency("io.modelcontextprotocol.sdk:mcp:0.17.2")
        dependency("io.modelcontextprotocol.sdk:mcp-core:0.17.2")
        dependency("io.modelcontextprotocol.sdk:mcp-json-jackson2:0.17.2")
        dependency("io.modelcontextprotocol.sdk:mcp-spring-webmvc:0.17.2")
    }
}

// ── Separate integrationTest source set (Testcontainers, slow) ─────────────
sourceSets {
    create("integrationTest") {
        java.srcDir("src/integrationTest/java")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
        runtimeClasspath += output + compileClasspath
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations["testImplementation"])
}

dependencies {
    // Core
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Spring AI MCP Server (Streamable HTTP via WebMVC)
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    // Spring AI OpenAI client — OpenAiEmbeddingModel pointed at TEI (RAG-001)
    // TEI (HuggingFace Text Embeddings Inference) exposes an OpenAI-compatible API.
    // Using the core module instead of the starter to avoid spring-ai-retry-autoconfigure,
    // which requires org.springframework.core.retry.RetryListener (Spring 7+, not in Boot 3.5)
    implementation("org.springframework.ai:spring-ai-openai")

    // Database
    runtimeOnly("org.postgresql:postgresql:42.7.3")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // AOP — used by RlsContextAspect to inject SET LOCAL per @Tool call (SEC-RLS)
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // In-memory cache for API key list (SEC-003)
    implementation("com.github.ben-manes.caffeine:caffeine")

    // SpotBugs security plugin
    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")  // WebTestClient for SSE
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ── Unit tests (fast — no Testcontainers) ──────────────────────────────────
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
    // shaded docker-java in Testcontainers reads "api.version" system property
    // Docker Engine 26+ requires >= 1.40; docker-java defaults to 1.32
    jvmArgs("-Dapi.version=1.41")
    environment("DOCKER_HOST", "unix:///var/run/docker.sock")
    finalizedBy(tasks.jacocoTestReport)
}

// ── Integration tests (Testcontainers — Docker required) ───────────────────
val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests with Testcontainers (PostgreSQL)"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

// ── JaCoCo ─────────────────────────────────────────────────────────────────
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.70".toBigDecimal()  // 70% — realistic for pre-GA Spring AI demo
            }
        }
    }
}

// ── SpotBugs + FindSecBugs ─────────────────────────────────────────────────
tasks.withType<SpotBugsTask>().configureEach {
    effort.set(Effort.MAX)
    reportLevel.set(Confidence.MEDIUM)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
    reports.create("xml") { required.set(true) }
    reports.create("html") { required.set(false) }
}

// ── Checkstyle ─────────────────────────────────────────────────────────────
checkstyle {
    toolVersion = "10.17.0"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

// ── OWASP Dependency Check ─────────────────────────────────────────────────
dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("XML", "SARIF", "HTML")
    suppressionFile = "config/owasp/suppression.xml"
    nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""
}

// ── check task gate ─────────────────────────────────────────────────────────
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// SEC-023: key values must come from env vars — never hardcoded in source.
// Prints HMAC-SHA256 hashes for the two demo keys using the configured pepper.
// Usage: MCP_HMAC_PEPPER=<pepper> ./gradlew computeDemoHashes
tasks.register("computeDemoHashes") {
    doLast {
        val pepper = System.getenv("MCP_HMAC_PEPPER")
            ?: error("Set MCP_HMAC_PEPPER env var — never put pepper values in source")
        val readonlyKey = System.getenv("DEMO_READONLY_KEY") ?: "demo-readonly-key-001"
        val adminKey = System.getenv("DEMO_ADMIN_KEY") ?: "demo-admin-key-001"
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(pepper.toByteArray(), "HmacSHA256"))
        val hash1 = mac.doFinal(readonlyKey.toByteArray()).joinToString("") { "%02x".format(it) }
        mac.reset()
        val hash2 = mac.doFinal(adminKey.toByteArray()).joinToString("") { "%02x".format(it) }
        println("HMAC hash for DEMO_READONLY_KEY ($readonlyKey): $hash1")
        println("HMAC hash for DEMO_ADMIN_KEY    ($adminKey): $hash2")
    }
}

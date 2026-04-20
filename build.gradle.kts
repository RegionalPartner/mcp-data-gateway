import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask


plugins {
    java
    id("org.springframework.boot") version "4.1.0-M4"
    id("jacoco")
    id("checkstyle")
    id("com.github.spotbugs") version "6.5.0"
    id("org.owasp.dependencycheck") version "12.2.1"
    id("org.cyclonedx.bom") version "3.2.4"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "io.ancoris"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
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
    // BOMs — Gradle native platform() replaces io.spring.dependency-management (Boot 4.0+)
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0-M4"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))

    // Core
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Spring AI MCP Server (Streamable HTTP via WebMVC)
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    // Spring AI OpenAI core module — OpenAiEmbeddingModel pointed at TEI (RAG-001).
    // TEI exposes an OpenAI-compatible API. Core module avoids starter auto-configuration.
    implementation("org.springframework.ai:spring-ai-openai")

    // Database
    runtimeOnly("org.postgresql:postgresql:42.7.3")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // AOP — used by RlsContextAspect to inject SET LOCAL per @Tool call (SEC-RLS)
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // In-memory cache for API key list (SEC-003)
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Hibernate 7.2.x JacksonJsonFormatMapper uses com.fasterxml.jackson.databind.ObjectMapper.
    // Boot 4.1 migrated to Jackson 3.x (tools.jackson.core group) which uses a different package.
    // Both coexist on the classpath without conflict (different group IDs + packages).
    runtimeOnly("com.fasterxml.jackson.core:jackson-databind")

    // SpotBugs security plugin
    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0")

    // Force jackson-core 3.x upgrade — GHSA-2m67-wjpj-xhg9 (document length bypass), fixed in 3.1.1
    constraints {
        implementation("tools.jackson.core:jackson-core:3.1.1")
    }

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")  // WebTestClient for SSE
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
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
    nvd {
        apiKey = providers.environmentVariable("NVD_API_KEY").getOrElse("")
        delay = 16000
    }
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    suppressionFile = "config/owasp/suppression.xml"
}

// ── check task gate ─────────────────────────────────────────────────────────
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ── CycloneDX SBOM ─────────────────────────────────────────────────────────
// Spring Boot 4 plugin auto-embeds the CycloneDX BOM at META-INF/sbom/application.cdx.json.
// Restrict to runtimeClasspath — keeps build-tool and test JARs (plexus-utils, beanutils, etc.)
// out of the embedded SBOM so they don't generate false-positive Trivy findings.
tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
    includeConfigs.set(listOf("runtimeClasspath"))
}

// ── PIT Mutation Testing ────────────────────────────────────────────────────
// Run via: ./gradlew pitest  (nightly CI only — too slow for every build)
pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(setOf("io.ancoris.mcp.*"))
    targetTests.set(setOf("io.ancoris.mcp.*"))
    mutationThreshold.set(60)
    outputFormats.set(setOf("HTML", "XML"))
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

import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask

buildscript {
    dependencies {
        classpath("org.springframework.security:spring-security-crypto:6.3.3")
    }
}

plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("jacoco")
    id("checkstyle")
    id("com.github.spotbugs") version "6.0.9"
    id("org.owasp.dependencycheck") version "9.2.0"
}

group = "io.ancoris"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.0-M2")
        mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
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

    // Database
    runtimeOnly("org.postgresql:postgresql:42.7.3")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // MinIO S3-compatible storage
    implementation("io.minio:minio:8.5.9")

    // SpotBugs security plugin
    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.12.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:minio")
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
    description = "Runs integration tests with Testcontainers (PostgreSQL + MinIO)"
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

tasks.register("verifyHashes") {
    doLast {
        val enc = org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)
        val hash1 = "\$2a\$12\$KjSltdyBNKZ7bZ7habe1meKexuEliqEElwocLKsjJ5WEJzfHl65tS"
        val hash2 = "\$2a\$12\$zh883gLsNBA58UHbsTmlw.lq2GwpOzv2KlfNVCOrDH6eeKGhigcyS"
        println("hash1 matches demo-readonly-key-001: ${enc.matches("demo-readonly-key-001", hash1)}")
        println("hash2 matches demo-admin-key-001: ${enc.matches("demo-admin-key-001", hash2)}")
        if (!enc.matches("demo-readonly-key-001", hash1)) {
            println("NEW hash for demo-readonly-key-001: ${enc.encode("demo-readonly-key-001")}")
            println("NEW hash for demo-admin-key-001: ${enc.encode("demo-admin-key-001")}")
        }
    }
}

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.dependency.management)
}

group = "com.robsartin"
version = "0.1.0"

// Override the Spring-Boot-managed Flyway version (Boot 3.5.16 still only manages Flyway
// 11.7.2, which only officially supports PostgreSQL <= 16 and WARNs on boot against Render's
// PG 18.4). 11.20.3 is the last stable Flyway 11.x and raises the supported-PostgreSQL
// ceiling to 18, clearing the warning. This property flows to both flyway-core and
// flyway-database-postgresql (see #46).
extra["flyway.version"] = "11.20.3"

java {
    // JDK pinned via the toolchain so the build uses the same Java version everywhere,
    // independent of whatever JDK happens to be on PATH. Gradle 8.14.3 itself can't launch on
    // JDK 25, so it must be run with JAVA_HOME pointed at JDK 21 (or earlier); Gradle then
    // forks a JDK 25 JVM for this toolchain to compile and run tests on (see ADR-0015 and #43).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Spring Modulith isn't in the Spring Boot BOM, so import its own BOM to align all
// spring-modulith artifacts on 1.4.x (Boot-3.5-compatible); see the redesign spec.
dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:1.4.1")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Exposes /actuator/health for Render's health check (permitted in SecurityConfig).
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    runtimeOnly("org.postgresql:postgresql")

    // Versioned schema migrations (replaces ddl-auto=update; see ADR-0009).
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // HTML fetch + parse for scraping band official-site tour pages (#22).
    implementation("org.jsoup:jsoup:1.18.1")

    // UUIDv7 (time-ordered) correlation ids; Java's built-in UUID is v4 only.
    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")

    // Modular-monolith structure + boundary verification + application events (see spec).
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    // Durable event-publication registry (JPA-backed); inert until Phase B adds publishers/listeners.
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Full-context smoke test boots the app against a throwaway Postgres.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Keep the Maven finalName -- the runnable artifact stays build/libs/setlist-scout.jar.
tasks.bootJar {
    archiveFileName.set("setlist-scout.jar")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Gradle defaults to 1 -- all test classes run serially in one JVM. 23 of ~62 classes boot a
    // real Testcontainers Postgres + Spring context (AbstractPostgresIntegrationTest), which
    // dominates the ~11.5min wall time. Forking multiple JVMs gives each its own independent
    // container, so this doesn't reintroduce the connection-pool contention that made a single
    // SHARED container across cached contexts unworkable (see AbstractPostgresIntegrationTest's
    // class javadoc). Set to 4, not the host's full core count: Docker Desktop here has ~7.75GB
    // memory allocated and is shared with other concurrent build processes on this machine.
    maxParallelForks = 4
}

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.dependency.management)
}

group = "com.robsartin"
version = "0.1.0"

java {
    // JDK pinned via the toolchain so the build uses the same Java version everywhere,
    // independent of whatever JDK happens to be on PATH.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    // WebClient only; the MVC servlet stack still serves the web app.
    implementation("org.springframework.boot:spring-boot-starter-webflux")
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

    testImplementation("org.springframework.boot:spring-boot-starter-test")
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
}

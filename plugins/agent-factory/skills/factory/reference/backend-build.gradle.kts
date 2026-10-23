// Quality build template for Agent Factory backends.
// Versions below were current when this kit was written. Before using it, check for the
// latest stable releases on https://plugins.gradle.org and https://central.sonatype.com and bump them.

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.2"
    id("com.github.spotbugs") version "6.1.7"
}

group = "com.factory"
version = "1.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    // Spring Boot 4 uses modular starters (webmvc, flyway, ...). No Docker, so no Testcontainers.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // Embedded H2 file database by default, so `java -jar app.jar` runs with no database install.
    runtimeOnly("com.h2database:h2")
    // Optional: run with --spring.profiles.active=postgres to use PostgreSQL instead.
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---- Deliverable: one executable JAR named <project>-<version>.jar (no "-plain" jar).
tasks.bootJar { archiveFileName = "${rootProject.name}-${project.version}.jar" }
tasks.jar { enabled = false }

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

// ---- Formatting: fails `check` if code is not google-java-format clean. Fix with `spotlessApply`.
spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---- Static analysis: high-priority bugs fail the build.
spotbugs {
    ignoreFailures = false
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.HIGH
}
tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsTest") { enabled = false }

// ---- Coverage: below 80% line coverage fails `check`.
val coverageExcludes = listOf("**/*Application*", "**/config/**", "**/dto/**")

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required = true; html.required = true }
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExcludes) } }))
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExcludes) } }))
    violationRules {
        rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } }
    }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

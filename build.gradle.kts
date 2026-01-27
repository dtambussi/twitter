plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.gorylenko.gradle-git-properties") version "2.4.2"
}

gitProperties {
    failOnNoGitDirectory = false
}

group = "com.twitter"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Only produce Spring Boot executable jar, not the plain jar
tasks.jar { enabled = false }

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Kafka
    implementation("org.springframework.kafka:spring-kafka")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // UUID v7
    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")

    // OpenAPI (generates /api-docs for Scalar, no Swagger UI)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.7.0")

    // Metrics
    implementation("io.micrometer:micrometer-registry-prometheus")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Logging
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:redpanda")
    // Explicit docker-java transport for Docker Desktop 4.57+ compatibility
    testImplementation("com.github.docker-java:docker-java-transport-zerodep:3.4.1")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
    testImplementation("org.awaitility:awaitility:4.2.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    
    // Testcontainers Docker configuration
    environment("DOCKER_HOST", "unix:///var/run/docker.sock")
    // Force API version 1.44 for Docker Desktop 4.57+ compatibility
    environment("DOCKER_API_VERSION", "1.44")
    
    val mockitoAgent = configurations.testRuntimeClasspath.get().files.find { it.name.contains("mockito-core") }
    val agentArg = mockitoAgent?.let { "-javaagent:${it.absolutePath}" }
    val args = mutableListOf(
        "-XX:+EnableDynamicAgentLoading",
        "-Xshare:off",
        "-Djava.util.logging.config.file=src/test/resources/logging.properties",
        "-Dmockito.mock-maker-class=mock-maker-subclass"
    )
    if (agentArg != null) {
        args.add(agentArg)
    }
    jvmArgs(args)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
    doLast {
        val reportFile = file("${layout.buildDirectory.get()}/reports/jacoco/test/jacocoTestReport.xml")
        if (reportFile.exists()) {
            val content = reportFile.readText()
            val pattern = """<counter type="(\w+)" missed="(\d+)" covered="(\d+)"/>""".toRegex()
            val counters = mutableMapOf<String, Pair<Long, Long>>()
            
            pattern.findAll(content).forEach { match ->
                val (type, missed, covered) = match.destructured
                counters[type] = Pair(covered.toLong(), missed.toLong() + covered.toLong())
            }
            
            fun pct(type: String): String {
                val (covered, total) = counters[type] ?: return "N/A"
                return if (total > 0) "%.1f%%".format(covered * 100.0 / total) else "N/A"
            }
            fun stats(type: String): String {
                val (covered, total) = counters[type] ?: return "(N/A)"
                return "($covered/$total)"
            }
            
            println("\n" + "=".repeat(60))
            println("CODE COVERAGE SUMMARY")
            println("=".repeat(60))
            println("Instructions: ${pct("INSTRUCTION")} ${stats("INSTRUCTION")}")
            println("Branches:     ${pct("BRANCH")} ${stats("BRANCH")}")
            println("Lines:        ${pct("LINE")} ${stats("LINE")}")
            println("=".repeat(60))
            println("Full report: ${layout.buildDirectory.get()}/reports/jacoco/test/html/index.html")
            println("=".repeat(60) + "\n")
        }
    }
}

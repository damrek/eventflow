import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot")
}

description = "Catalog service: venues, events, ticket types and publication."

dependencies {
    // The Boot BOM constrains implementation by default; annotation-processing
    // configurations are separate, so they need the platform explicitly.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    compileOnly(platform(SpringBootPlugin.BOM_COORDINATES))
    annotationProcessor(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Prometheus scrape endpoint (S1 of Phase 7); the version comes from the Boot BOM.
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Tracing (S3): Boot 4's tracing auto-configuration, the OpenTelemetry bridge and the OTLP
    // exporter are separate BOM-managed modules; the starter is not used because it also adds the
    // OTLP metrics registry, and metrics here are scraped by Prometheus instead.
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    runtimeOnly("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("org.springframework.boot:spring-boot-starter-rabbitmq")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.springdoc.openapi.webmvc.ui)

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

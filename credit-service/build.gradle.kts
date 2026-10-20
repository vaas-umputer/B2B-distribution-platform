dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.21.3")
    }
}

dependencies {
    implementation(project(":common"))
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:rabbitmq")
    testImplementation("org.awaitility:awaitility:4.2.2")
}

tasks.withType<Test> {
    // See Phase 0's tenant-catalog-service build.gradle.kts for why this is
    // needed: docker-java's default Remote API version is too old for a
    // recent Docker Desktop engine.
    systemProperty("api.version", "1.44")
}

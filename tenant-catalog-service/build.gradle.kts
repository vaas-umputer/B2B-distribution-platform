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
}

tasks.withType<Test> {
    // docker-java defaults to a very old Docker Remote API version (1.32);
    // recent Docker Desktop engines reject anything below their MinAPIVersion
    // with a 400. Forcing a modern version here is what lets Testcontainers
    // actually talk to the daemon on a current Docker Desktop install.
    systemProperty("api.version", "1.44")
}

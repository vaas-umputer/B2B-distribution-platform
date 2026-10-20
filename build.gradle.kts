plugins {
    id("org.springframework.boot") version "3.3.4" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    java
}

val serviceModules = listOf(
    "tenant-catalog-service",
    "order-inventory-service",
    "credit-service",
    "invoice-service"
)

allprojects {
    group = "com.imperial"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

configure(subprojects.filter { it.name in serviceModules }) {
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")
}

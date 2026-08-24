import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    alias(libs.plugins.spring.boot)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)   // 04-tech-stack.md 2.1
    }
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-web")            // MVC + 가상 스레드 (2.4)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")     // Lettuce (3.4)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")       // 5장 — 계측은 0단계

    // JPA·Flyway·PostgreSQL은 의도적으로 없다. 실시간 매칭은 Redis만 본다.

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")   // 2.x에서 아티팩트 이름이 바뀌었다
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

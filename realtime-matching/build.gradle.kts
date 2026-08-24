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

    // 02 1.3 — 불변식 검증의 권위는 RDB에 있다. 판정 게이트가 INV-1~5 검출 질의라서
    // 어느 구현이든 이 스키마가 필요하다. 실행기가 DB를 읽느냐 마느냐는 비교 축이다.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")       // 04 3.1
    implementation("org.flywaydb:flyway-core")                                    // 04 1장
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")   // 2.x에서 아티팩트 이름이 바뀌었다
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

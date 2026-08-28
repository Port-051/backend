plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.port051"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        // 04-tech-stack.md 2.1 — Java 21 LTS. 가상 스레드가 SSE 연결 유지 비용을 내린다.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Boot 4는 JSON 라이브러리를 starter-web에 묶어 보내지 않는다 (여러 구현을 지원한다).
    // 계약(05 2절)이 req: JSON의 필드 이름과 타입을 고정했으므로 직렬화는 명시적으로 붙인다.
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // Lettuce 커넥션 풀. 인스턴스 2대가 초당 다섯 번씩 명단을 훑고 Lua를 쏘므로
    // 커넥션을 매번 새로 여는 비용이 그대로 틱 지연에 얹힌다.
    implementation("org.apache.commons:commons-pool2")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 스파이크 계약: JPA · Flyway · PostgreSQL 은 넣지 않는다 (이슈 #48).
    // 판정 게이트도 Redis 스캔으로 센다.

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Redis 전용 모듈 대신 GenericContainer를 쓴다. 띄우는 것이 redis:7-alpine 하나뿐이라
    // 모듈이 얹어주는 것보다 컨테이너 정의 세 줄이 짧다.
}

dependencyManagement {
    imports { mavenBom("org.testcontainers:testcontainers-bom:1.21.3") }
}

/**
 * Testcontainers가 붙을 도커 소켓.
 *
 * OrbStack · Colima처럼 소켓이 `/var/run/docker.sock`에 없는 런타임에서는 Testcontainers가
 * 데몬을 찾지 못하고 "Could not find a valid Docker environment"로 죽는다. 셸에 DOCKER_HOST가
 * 있으면 그것을 쓰고, 없으면 흔한 경로를 훑어 찾는다.
 *
 * Gradle 데몬은 자기가 뜰 때의 환경변수를 들고 있으므로, 셸에서 DOCKER_HOST를 새로 export 해도
 * 이미 떠 있는 데몬에는 반영되지 않는다. 그래서 값을 찾는 일을 여기서 한다.
 */
val dockerHost: String? =
    System.getenv("DOCKER_HOST")
        ?: listOf(
                "${System.getProperty("user.home")}/.orbstack/run/docker.sock",
                "${System.getProperty("user.home")}/.colima/default/docker.sock",
                "${System.getProperty("user.home")}/.docker/run/docker.sock",
                "/var/run/docker.sock",
            )
            .firstOrNull { file(it).exists() }
            ?.let { "unix://$it" }

tasks.withType<Test> {
    useJUnitPlatform()
    dockerHost?.let { environment("DOCKER_HOST", it) }
    // Testcontainers 1.21이 쓰는 docker-java는 API 버전을 협상하지 않고 1.32로 붙는다.
    // Docker 29의 데몬은 1.40 미만을 거절하므로("client version 1.32 is too old") 명시해야 한다.
    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.43")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

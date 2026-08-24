plugins {
    // 스프링을 아직 붙이지 않는다. 지금 만드는 것은 프레임워크가 필요 없는 판정 로직이다.
    `java-library`
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        // 04-tech-stack.md 2.1 — 설치된 JDK가 무엇이든 21로 컴파일한다.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        // 기본값은 "몇 번째 줄에서 깨졌다"까지만 콘솔에 찍는다.
        // 표를 반복문으로 도는 테스트는 어느 칸이 틀렸는지가 실패 메시지에 있으므로 그것까지 띄운다.
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

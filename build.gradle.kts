plugins {
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "com.port051.queuemate"
    version = "0.1.0-SPIKE"

    repositories {
        mavenCentral()
    }
}

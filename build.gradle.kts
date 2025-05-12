plugins {
    kotlin("jvm") version "2.0.0"
}

group = "com.gustmmer"
version = "0.5-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")

    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "8192m"
}

kotlin {
    jvmToolchain(21)
}
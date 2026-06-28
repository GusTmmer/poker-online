plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("com.gustmmer.poker.server.ApplicationKt")
}

dependencies {
    implementation(project(":poker-engine"))

    implementation("io.ktor:ktor-server-core:3.1.1")
    implementation("io.ktor:ktor-server-netty:3.1.1")
    implementation("io.ktor:ktor-server-websockets:3.1.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("io.ktor:ktor-server-cors:3.1.1")
    implementation("io.ktor:ktor-server-auth-jwt:3.1.1")
    implementation("io.ktor:ktor-server-resources:3.1.1")
    implementation("io.ktor:ktor-server-rate-limit:3.1.1")
    implementation("io.ktor:ktor-server-forwarded-header:3.1.1")

    implementation("com.google.cloud:google-cloud-firestore:3.29.0")
    implementation("com.google.cloud:google-cloud-tasks:2.74.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("ch.qos.logback:logback-classic:1.5.16")

    testImplementation(enforcedPlatform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.ktor:ktor-server-test-host:3.1.1")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    testImplementation("io.ktor:ktor-client-resources:3.1.1")
    // Spins a Firestore emulator in a container so the Firestore-listener test runs in CI.
    // `disabledWithoutDocker` makes it skip where Docker is absent (keeps local builds green).
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:gcloud:1.21.4")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runLocal") {
    group = "application"
    description = "Run the server locally with in-memory persistence (no GCP credentials needed)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.gustmmer.poker.server.LocalServerKt")
}

plugins {
    kotlin("jvm") version "2.3.10"
}

group = "ch.flavianz"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktor_version = "2.3.12"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
}

dependencies {
    implementation("org.postgresql:postgresql:42.7.11")
    implementation("org.mongodb:mongodb-driver-sync:5.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
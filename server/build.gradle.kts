plugins {
    kotlin("jvm") version "2.3.10"
    id("io.ktor.plugin") version "3.1.3"
    kotlin("plugin.serialization") version "2.3.10"
    application
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
    implementation("io.ktor:ktor-server-cors:${ktor_version}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(kotlin("stdlib-jdk8"))
}

dependencies {
    implementation("org.postgresql:postgresql:42.7.11")
    implementation("org.mongodb:mongodb-driver-sync:5.1.0")
    implementation("org.neo4j.driver:neo4j-java-driver:6.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("net.datafaker:datafaker:2.4.2")

    testImplementation(kotlin("test"))
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")

}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("ch.flavianz.MainKt")
}
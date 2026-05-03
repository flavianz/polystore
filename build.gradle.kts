plugins {
    kotlin("jvm") version "2.3.10"
}

group = "ch.flavianz"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.postgresql:postgresql:42.7.11")
    implementation("org.mongodb:mongodb-driver-sync:5.1.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
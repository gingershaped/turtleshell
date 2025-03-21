val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "1.9.25"
    id("io.ktor.plugin") version "3.0.3"
}

group = "computer.gingershaped.turtleshell"
version = "0.0.1"

application {
    mainClass.set("computer.gingershaped.turtleshell.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-websockets-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-cors")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("org.apache.sshd:sshd-netty:2.12.1")
    implementation("com.github.ajalt.colormath:colormath:3.4.0")
    implementation("com.sksamuel.hoplite:hoplite-core:2.9.0")
    implementation("com.sksamuel.hoplite:hoplite-toml:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.6.0")

    testImplementation("io.ktor:ktor-server-tests-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}

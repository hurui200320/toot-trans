plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("io.insert-koin.compiler.plugin") version "1.2.1"
    application
}

group = "info.skyblond"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// dependency injection: the container + the compiler plugin DSL
// (org.koin.plugin.module.dsl.*), see di/AppModule.kt
val koinVersion = "4.2.2"

dependencies {
    // logging
    implementation("ch.qos.logback:logback-classic:1.6.5")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    // http and json
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0-RC")
    // koin (DI)
    implementation(platform("io.insert-koin:koin-bom:$koinVersion"))
    implementation("io.insert-koin:koin-core")

    testImplementation(kotlin("test"))
}

kotlin {
    // Use latest LTS version
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("info.skyblond.fediverse.toot.trans.MainKt")
}

koinCompiler {
    aiAssist = false
}

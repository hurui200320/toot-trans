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
// (org.koin.plugin.module.dsl.*)
val koinVersion = "4.2.2"

// OkHttp and its sibling artifacts (coroutines support, test server)
// share the same version line, so one val bumps them together
val okhttpVersion = "5.5.0"

// coroutines core and its test artifact must stay in the same version
val coroutinesVersion = "1.11.0"

dependencies {
    // logging
    implementation("ch.qos.logback:logback-classic:1.6.5")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    // coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    // http
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.squareup.okhttp3:okhttp-coroutines:$okhttpVersion")
    // json
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0-RC")
    // koin (DI)
    implementation(platform("io.insert-koin:koin-bom:$koinVersion"))
    implementation("io.insert-koin:koin-core")
    // jsoup, clean up html
    implementation("org.jsoup:jsoup:1.23.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    // in-memory HTTP server for the API client tests
    testImplementation("com.squareup.okhttp3:mockwebserver3:$okhttpVersion")
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

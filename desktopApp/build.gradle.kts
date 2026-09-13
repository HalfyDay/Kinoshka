plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(project(":shared"))
    // compose.* accessor'ы депрекейтнуты в CMP ("Specify dependency directly") —
    // версии = связке CMP 1.12.0 (см. whats-new-compose-112).
    implementation("org.jetbrains.compose.runtime:runtime:1.12.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.compose.ui:ui:1.12.0")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
}

compose.desktop.application {
    mainClass = "hd.kinoshka.desktop.MainKt"
}

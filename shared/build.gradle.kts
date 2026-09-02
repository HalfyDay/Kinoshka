plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidLibrary {
        namespace = "hd.kinoshka.app.shared"
        compileSdk = 37
        minSdk = 26
    }

    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                // Общий UI-слой (M2+): композаблы shared собираются CMP-артефактами;
                // на Android CMP делегирует в androidx-артефакты, версиями рулит BOM приложения.
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)
                api("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
                // Material-иконки для общих экранов (HomeScreen и др.): полный набор —
                // History/PauseCircle/WifiOff и прочие есть только в extended.
                api("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            }
        }
        // JVM-код, общий для Android- и desktop-таргетов (gson, java.util.concurrent
        // и прочее, чего нет в commonMain), но недоступный платформенно-нейтральному коду.
        val jvmShared by creating {
            dependsOn(commonMain)
            dependencies {
                api("com.google.code.gson:gson:2.11.0")
                api("com.squareup.retrofit2:retrofit:3.0.0")
                api("com.squareup.retrofit2:converter-gson:3.0.0")
                api("com.squareup.okhttp3:logging-interceptor:5.4.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        val androidMain by getting {
            dependsOn(jvmShared)
            dependencies {
                implementation("io.coil-kt:coil-compose:2.7.0")
                // Выравнивание по версиям приложения: expressive-API (LoadingIndicator)
                // и activity BackHandler есть только в новых androidx-артефактах,
                // CMP-делегация тянуть их не обязана.
                implementation("androidx.activity:activity-compose:1.13.0")
                implementation("androidx.compose.material3:material3:1.5.0-alpha22")
            }
        }
        val desktopMain by getting {
            dependsOn(jvmShared)
            dependencies {
                implementation("net.java.dev.jna:jna:5.17.0")
                // На Android org.json входит в платформу; на desktop нужен артефакт.
                implementation("org.json:json:20240303")
                // Dispatchers.Main для общего кода (FilmsViewModel): на desktop это EDT.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
    }

    compilerOptions {
        // expect/actual классы (KinoPrefs, KLog) — стабильное API, бета-предупреждение KT-61573
        freeCompilerArgs.addAll("-Xexpect-actual-classes")
        // Как в приложении: общие экраны используют experimental material3 (SheetState и др.)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
        )
    }
}

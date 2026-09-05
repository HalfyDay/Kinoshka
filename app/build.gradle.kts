import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "hd.kinoshka.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "hd.kinoshka.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "1.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // mpv-AAR тащит нативные библиотеки для 4 ABI; x86/x86_64 нужны только эмулятору.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        val localProps = Properties().apply {
            val localFile = rootProject.file("local.properties")
            if (localFile.exists()) {
                localFile.inputStream().use { load(it) }
            }
        }
        val apiKeyRaw = (project.findProperty("KP_API_KEY") as String?)
            ?: localProps.getProperty("KP_API_KEY")
            ?: System.getenv("KP_API_KEY")
            ?: ""
        val apiKey = apiKeyRaw.trim().removeSurrounding("\"").removeSurrounding("'")
        val githubReleasesUrlRaw = (project.findProperty("GITHUB_RELEASES_URL") as String?)
            ?: localProps.getProperty("GITHUB_RELEASES_URL")
            ?: System.getenv("GITHUB_RELEASES_URL")
            ?: ""
        val githubReleasesUrl = githubReleasesUrlRaw.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
        buildConfigField("String", "KP_API_KEY", "\"$apiKey\"")
        buildConfigField("String", "GITHUB_RELEASES_URL", "\"$githubReleasesUrl\"")
        buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "true")
        buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")
        buildConfigField("String", "GIT_SHA", "\"unknown\"")

        val shikimoriClientId = (localProps.getProperty("SHIKIMORI_CLIENT_ID") ?: "").trim().removeSurrounding("\"").removeSurrounding("'")
        val shikimoriClientSecret = (localProps.getProperty("SHIKIMORI_CLIENT_SECRET") ?: "").trim().removeSurrounding("\"").removeSurrounding("'")
        buildConfigField("String", "SHIKIMORI_CLIENT_ID", "\"$shikimoriClientId\"")
        buildConfigField("String", "SHIKIMORI_CLIENT_SECRET", "\"$shikimoriClientSecret\"")

        val yandexDiskClientId = (localProps.getProperty("YANDEX_DISK_CLIENT_ID") ?: "").trim().removeSurrounding("\"").removeSurrounding("'")
        val yandexDiskClientSecret = (localProps.getProperty("YANDEX_DISK_CLIENT_SECRET") ?: "").trim().removeSurrounding("\"").removeSurrounding("'")
        buildConfigField("String", "YANDEX_DISK_CLIENT_ID", "\"$yandexDiskClientId\"")
        buildConfigField("String", "YANDEX_DISK_CLIENT_SECRET", "\"$yandexDiskClientSecret\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // mediainfo используется только браузерным UI mpvEx, который недостижим
            // из Kinoshka; нативные библиотеки (8.7 МБ на ABI) в APK не нужны.
            excludes += listOf(
                "lib/**/libmediainfo.so",
                "lib/**/libzen.so"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")

    implementation(project(":shared"))
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha22")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.animation:animation-graphics")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("com.google.android.material:material:1.14.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")

    implementation("androidx.media:media:1.8.0")
    implementation(files("libs/mpv-android-lib-v0.0.1.aar"))

    // Koin
    implementation("io.insert-koin:koin-core:4.2.2")
    implementation("io.insert-koin:koin-android:4.2.2")
    implementation("io.insert-koin:koin-compose:4.2.2")
    implementation("io.insert-koin:koin-compose-viewmodel:4.2.2")

    // Room
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Seeker, preference, reorderable, scrollbar
    implementation("com.github.abdallahmehiz:seeker:2.0.1")
    implementation("me.zhanghai.compose.preference:preference:2.2.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation("com.github.nanihadesuka:LazyColumnScrollbar:2.2.0")

    // Network protocols
    implementation("commons-net:commons-net:3.13.0")
    implementation("com.hierynomus:smbj:0.14.0")
    // Android provides XmlPullParser; xpp3 bundled by Sardine conflicts with it during R8.
    implementation("com.github.thegrizzlylabs:sardine-android:0.8") {
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Other utilities
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.0")
    implementation("io.github.yubyf:truetypeparser-light:2.1.4")
    implementation("com.github.K1rakishou:Fuck-Storage-Access-Framework:1.1.3")
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout-compose:1.1.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.github.marlboro-advance:mediainfoAndroid:v1.0.0-fix")

    // Security - encrypted preferences for tokens
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    // Real org.json instead of the mockable Android stub: resolvers parse JSON responses
    // (ddbb/kinobox player lists) and their tests assert on the parsed output.
    testImplementation("org.json:json:20240303")

    // Navigation3 for mpvEx
    implementation("androidx.navigation3:navigation3-runtime:1.1.3")
    implementation("androidx.navigation3:navigation3-ui:1.1.3")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=com.google.accompanist.permissions.ExperimentalPermissionsApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.material.ExperimentalMaterialApi"
        )
    }
}


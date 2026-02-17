import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hd.kinoshka.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "hd.kinoshka.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}


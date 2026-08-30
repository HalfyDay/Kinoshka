pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Fallback mirrors: mavenCentral/gradlePluginPortal answer 403 through this
        // machine's network, so anything not already cached must come from a mirror.
        // Aliyun intermittently 502s here — tencent/huawei are the reliable ones.
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://repo.huaweicloud.com/repository/maven") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "Kino"
include(":app")
include(":shared")
include(":desktopApp")

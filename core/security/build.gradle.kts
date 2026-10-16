plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.myplayer.core.security"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // 库模块不声明 targetSdk：该字段只对 :app 生效（docs/02-技术规范.md §2.1）
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// jvmTarget 无需配置：AGP 9 内置 Kotlin 默认取 android.compileOptions.targetCompatibility（=17）

dependencies {
    // 无额外依赖：PBKDF2 / MessageDigest 来自 JDK，Keystore 来自 Android 平台

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

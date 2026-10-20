plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.myplayer.core.designsystem"
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

    buildFeatures { compose = true }
}

// jvmTarget 无需配置：AGP 9 内置 Kotlin 默认取 android.compileOptions.targetCompatibility（=17）

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

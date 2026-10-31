plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.myplayer.feature.privacy"
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
    implementation(project(":core:model"))
    implementation(project(":core:security"))
    implementation(project(":core:designsystem"))

    // 生物识别（BiometricPrompt + CryptoObject）：只在本模块用，不往上层扩散
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.core)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.myplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.myplayer"
        minSdk = 26
        // targetSdk 只在 :app 声明（库模块声明它没有意义）。
        // 36 = Google Play 自 2026-08-31 起的底线；等 Android 17 稳定后再上 37。
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-M1"
    }

    buildTypes {
        release {
            // 混淆与签名在 M5 落实（docs/04-开发计划.md §3.6）
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

// jvmTarget 无需配置：AGP 9 内置 Kotlin 默认取 android.compileOptions.targetCompatibility（=17）

dependencies {
    // 12 个模块的依赖图在此收敛：feature → source / core → core:model（单向）
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:designsystem"))
    implementation(project(":source:httpapi"))
    implementation(project(":source:emby"))
    implementation(project(":feature:library"))
    implementation(project(":feature:lock"))
    implementation(project(":feature:server"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)

    // TODO(1-12): Hilt 在任务 1-12 接入本模块（与 :feature:* 一起）：
    //   plugins 加 alias(libs.plugins.hilt) + alias(libs.plugins.ksp)
    //   deps  加 implementation(libs.hilt.android) + ksp(libs.hilt.compiler)
    //          + implementation(libs.androidx.hilt.navigation.compose)
    //   并新增 @HiltAndroidApp 的 Application 类，在 Manifest 的 android:name 上指向它。

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

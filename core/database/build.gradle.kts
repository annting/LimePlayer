plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.myplayer.core.database"
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
    implementation(project(":core:model"))

    // TODO(1-3): Room 代码生成在任务 1-3 接入本模块：
    //   plugins 加 alias(libs.plugins.ksp)
    //   deps  加 implementation(libs.room.runtime) / implementation(libs.room.ktx) / ksp(libs.room.compiler)
    //   ⚠️ 接入前先核对 KSP 与 Kotlin 的版本兼容性（docs/02-技术规范.md §2.1）

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

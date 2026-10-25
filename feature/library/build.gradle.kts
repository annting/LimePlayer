plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.myplayer.feature.library"
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
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    // 首页设置与设置页共用同一份 DataStore（HomeSettingsRepository 读 PlayerSettings 里的两份首页规则）
    implementation(project(":core:datastore"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // 搜索页历史清空 / 折叠图标（Icons.Outlined.Delete、KeyboardArrowDown/Up）
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // 多选态按系统返回要「退出多选」而不是退出页面（BackHandler）；
    // feature:player 同款依赖（Compose 的 BackHandler 在 activity-compose 里）
    implementation(libs.androidx.activity.compose)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // Compose UI 测试跑在 Robolectric 上（同 :feature:lock）：渲染类问题（如空列表
    // 越界崩溃）只有实际测量/布局才能覆盖。
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
}

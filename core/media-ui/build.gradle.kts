plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.myplayer.core.mediaui"
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

// 为什么单独建这一层（docs/13-组件统一修复任务.md T7）：
// `PosterCard` 已经是「媒体卡」而不是「某个 feature 的组件」—— 媒体库 / SMB / Emby /
// 搜索 / 本机文件夹 / 演员作品 六个入口都在用它。放进 `:core:designsystem` 会让设计系统
// 反向依赖 `:core:model` 与 coil（纯 UI 层被域模型污染），所以单独一层：
// designsystem（纯视觉）+ model（数据）+ coil（取图）在这里汇合。
dependencies {
    // api：PosterCard 的参数里出现 MediaItemDto / ImageSource，消费方必须能看到它们
    api(project(":core:model"))
    api(project(":core:designsystem"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

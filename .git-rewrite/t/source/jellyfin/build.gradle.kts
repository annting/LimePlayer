plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.myplayer.source.jellyfin"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    // api：JellyfinSource 继承 HttpMediaSource（基类与领域类型必须在消费方编译期可见）
    api(project(":source:httpapi"))
    api(project(":core:model"))
    api(libs.okhttp)

    // 复用 Emby 的 BaseItemDto→领域映射（Jellyfin 是 Emby 分叉，字段口径一致）。
    // 若将来出现字段差异，再把映射下沉到 :source:httpapi 并各自覆盖。
    implementation(project(":source:emby"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

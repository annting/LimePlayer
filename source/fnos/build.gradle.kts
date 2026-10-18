import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

// 密钥只从本机 local.properties 读取（该文件 gitignore），源码不含明文（docs/04 §4.0）
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val fnSecret: String = localProps.getProperty("FN_SECRET").orEmpty()
val fnApiKey: String = localProps.getProperty("FN_API_KEY").orEmpty()

android {
    namespace = "app.myplayer.source.fnos"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "FN_SECRET", "\"$fnSecret\"")
        buildConfigField("String", "FN_API_KEY", "\"$fnApiKey\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core:model"))
    api(libs.okhttp)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

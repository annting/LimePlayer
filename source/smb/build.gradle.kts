plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.myplayer.source.smb"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // 库模块不声明 targetSdk（只对 :app 生效，见 docs/02 §2.1）
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.jcifs.ng)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

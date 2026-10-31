plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
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

ksp {
    // 迁移基线（docs/10-数据存储方案.md §9）：不导出 schema 就无法用 MigrationTestHelper 测 v1→v2。
    // 产物入库到 core/database/schemas/，改表时先改代码再跑一次编译让 json 落盘。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))

    // Room（KSP 代码生成；版本兼容性见 docs/02-技术规范.md §2.1 规则 2/6）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // 迁移测试（v1→v2）：读上面 room.schemaLocation 导出的 json
    testImplementation(libs.androidx.room.testing)
}

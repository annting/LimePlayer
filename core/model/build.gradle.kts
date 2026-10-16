import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    // java-library：为了用 api 暴露「出现在公开 API 里的依赖」（见下方 coroutines）
    `java-library`
}

// 不写 jvmToolchain(17)：那会要求本机恰好装有 JDK 17，装了 21/25 反而报
// 「No matching toolchains」。用 compilerOptions 固定字节码目标更宽容，
// 只要运行 Gradle 的 JDK ≥ 17（AGP 9 的要求）即可。
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

// 纯 JVM 模块必须同时固定 Java 侧的字节码目标：否则 compileTestJava 会跟随
// 运行 Gradle 的 JDK（如 21），与 Kotlin 的 17 不一致，直接触发
// 「Inconsistent JVM-target compatibility」构建失败（2026-10-17 实测）。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // api 而非 implementation：LibraryRepository 的公开方法返回 Flow，属于本模块 API 面，
    // 消费方（如 :core:database 实现该接口）必须在编译期能看到 kotlinx.coroutines。
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

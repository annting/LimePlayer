import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 不写 jvmToolchain(17)：那会要求本机恰好装有 JDK 17，装了 21/25 反而报
// 「No matching toolchains」。用 compilerOptions 固定字节码目标更宽容，
// 只要运行 Gradle 的 JDK ≥ 17（AGP 9 的要求）即可。
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// 根构建脚本：只声明插件版本，不在这里配置任何模块行为。
// 插件版本统一来自 gradle/libs.versions.toml。
//
// 说明：KSP 与 Hilt 的插件已在版本目录中登记（版本已核对），但**尚未 apply**——
// 它们是代码生成插件，版本耦合最紧，各自在需要它们的任务里启用：
//   · Room  → 任务 1-3（:core:database）
//   · Hilt  → 任务 1-12（:app、:feature:*）
// 见 docs/04-开发计划.md §3.2。

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

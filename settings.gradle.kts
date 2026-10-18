// LimePlayer —— 多模块构建入口
//
// 仓库顺序：国内镜像优先（快），官方仓库兜底（镜像偶尔滞后于刚发布的版本时仍能拉到）。
// 想切回纯官方源：删掉三行 `maven("https://maven.aliyun.com/...")` 即可。
// 详见 docs/02-技术规范.md §2.1「版本与仓库管理规则」。

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "LimePlayer"

// M1 骨架的 12 个模块（docs/04-开发计划.md §3.2 任务 1-0）。
// 其余模块（:source:jellyfin、:source:local、:feature:search/detail/player/settings）
// 在各自里程碑引入，不提前建空壳。
include(
    ":app",
    ":core:model",
    ":core:database",
    ":core:datastore",
    ":core:network",
    ":core:security",
    ":core:designsystem",
    ":core:playback",
    ":source:httpapi",
    ":source:emby",
    ":source:jellyfin",
    ":source:fnos",
    ":feature:library",
    ":feature:lock",
    ":feature:player",
    ":feature:server",
    ":feature:settings",
)

# P1 · 骨架 + Emby 源 + 应用锁（M1）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **执行环境说明（本机现状）**：这两条 sub-skill 属于 `superpowers` 插件，**当前机器上未安装**（`~/.workbuddy/skills` 下无对应 skill）。三种可选执行方式：
> 1. 先安装该插件，再按其规范执行（任务间由主代理审查，上下文最干净）；
> 2. 不装插件：**按 Task 0 → 14 顺序串行执行**，每个 Task 完成即跑该 Task 的测试命令并提交，Task 之间不停；
> 3. 用 Explore/通用子代理逐 Task 派发，主代理审查（等价于方式 1，只是手工派发）。
>
> 无论哪种方式，**「Step N 运行确认失败 → 实现 → 运行确认通过 → 提交」的红绿节奏不得省略**——这是本计划可验收的前提。

**Goal:** 搭好多模块工程骨架，打通「添加 Emby 服务器 → 登录 → 拉取库与条目 → 落 Room → 海报网格展示」这条链路，并落地默认开启的九宫格图案应用锁。

**Architecture:** 多模块 Gradle + Clean Architecture + MVVM。`:feature:*` 只依赖 `:core:*` 与 `:source:*`；`:core:model` 是纯 Kotlin 领域模型；`:source:httpapi` 抽出 Emby/Jellyfin 共享的 REST 骨架，`:source:emby` 只覆盖差异。

**Tech Stack:** Kotlin 2.4 · Compose · Material 3 · Hilt · Room(KSP) · DataStore · OkHttp · kotlinx.serialization · Coil · Robolectric · MockWebServer · Turbine。

> **单一事实来源**：`docs/superpowers/specs/2026-10-16-android-media-player-design.md`。本计划曾对 spec §3.2 做的两处偏离（DTO 新增 `serverId`、接口新增 `serverId`）**已经回写 spec §3.2**，spec 与本计划现已一致。
>
> **与 spec §11 的关系**：spec §11 把「切后台自动暂停」列在 **M2**（不在 M1），本计划因此只落地**应用锁与回前台重新上锁**（Task 12 的 `shouldLock`）；切后台自动暂停由 P2 与「回前台先解锁、再恢复播放」合并实现。**本计划与 spec §11 / roadmap 的 M1 DoD 一致，无偏离。**
>
> ### 修订记录（rev.2 · 2026-10-16）
>
> 本计划首次落笔后经过一轮评审，rev.2 做了以下修订（对应评审报告 `docs/superpowers/reviews/2026-10-16-docs-review.md`）：
>
> | 编号 | 修订内容 | 位置 |
> |---|---|---|
> | A1 | **补 Task 13 / Task 14**：添加服务器与登录页、`SourceFactory`、导航接线与首次同步——原稿缺少 M1 验收链路的前 4 环 | Task 13 / 14（新增） |
> | A2 | DI 改为**加密落盘**（`FileServerConfigStore` + `EncryptedTokenStore`），替换原内存实现 | Task 5 / 12 |
> | A3 | `planPlayback` 严格按 spec §6.5 三档降级，并按 `baseUrl` 拼接 `TranscodingUrl`（服务端返回的是相对路径） | Task 7 |
> | A4 | 取库改用 `/Users/{userId}/Views`；`getItems` / `getLibraries` 补 `requireNotNull(userId)` | Task 7 |
> | A5 | 列表查询不再请求 `People` 字段（详情页才取），避免抵消 spec R5 的同步优化 | Task 7 |
> | A6 | 海报占位：DTO 新增 `hasPoster`，无海报时返回 `ImageSource.None` | Task 2 / 7 / 8 |
> | A7 | 图片鉴权改为**请求头**，URL 不再携带 `api_key`；`LibraryScreen` 用 `LaunchedEffect` 异步取图 | Task 7 / 11 |
> | A8 | `Ticks` **全面启用**（领域层用 `Ticks`，Room 边界显式转 `Long`），不再出现只造不用的值类型 | Task 1 / 2 / 3 / 8 |
> | A9 | `Ticks` 单测命令修正为 `:core:model:test`（纯 JVM 模块无 `testDebugUnitTest`） | Task 1 |
> | A10 | `SettingsStore` 改为 `update(transform)` 单一写入口，补齐附 B 全部设置项字段与 setter，`DataStore` 读取 `catch(IOException)` | Task 5 |
> | A11 | 版本矩阵上移（Kotlin 2.4 / AGP 9 / Compose BOM 2026.08 / `compileSdk 37`），补 `targetSdk` | Task 0 |
> | A12 | 仓库路径改为项目根（Windows 用 `gradlew.bat`），去掉 `/workspace` | Task 0 |
> | A13 | Manifest 的 `usesCleartextTraffic` 标注为临时放宽，P5 改为 security-config 白名单 | Task 12 |
> | A14 | 九宫格锁屏：统一画布尺寸口径、点尺寸按 dp 换算、补轻点选中说明 | Task 10 |
> | A15 | `AppNavHost` 不再留 `Text` 占位，改为可运行的「无服务器」空态 + 真实库页路由 | Task 12 |
> | A16 | 执行方式补 superpowers 未安装时的兜底；Task 0 与 DoD 的测试计数同步 | 执行方式 / 完成判定 |

---

## Task 0: 初始化仓库与多模块 Gradle 骨架

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `core/model/build.gradle.kts`
- Create: `core/database/build.gradle.kts`
- Create: `core/datastore/build.gradle.kts`
- Create: `core/network/build.gradle.kts`
- Create: `core/security/build.gradle.kts`
- Create: `core/designsystem/build.gradle.kts`
- Create: `source/httpapi/build.gradle.kts`
- Create: `source/emby/build.gradle.kts`
- Create: `feature/library/build.gradle.kts`
- Create: `feature/lock/build.gradle.kts`
- Create: `feature/server/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`

> **工作目录**：本计划所有命令都在**项目根目录**（本机为 `D:\porject\LimePlayer`）下执行，不要 `cd` 到别处。
> Windows 下用 `gradlew.bat`（或 Git Bash 里的 `./gradlew`）；`git` 若不在 PATH，用 Git 自带的 `git.exe` 全路径。

- [ ] **Step 1: 初始化 git**

```bash
git init
printf '%s\n' '.gradle/' 'build/' '*/build/' 'local.properties' '.idea/' '*.iml' '.kotlin/' > .gitignore
git add .gitignore && git commit -m "chore: init repo and gitignore"
```

- [ ] **Step 2: 写版本目录**

`gradle/libs.versions.toml`:

> ⚠️ **版本矩阵规则（必读）**：原稿的版本停在 2024 年末（Kotlin 2.1 / AGP 8.7 / Compose BOM 2024.12 / `compileSdk 35`），已落后约两代。下面把**核心三件套与编译目标**上移到 2026-10 的现状，其余依赖**开工前必须用 Android Studio 的 Version Catalog 检查器（或 `./gradlew dependencyUpdates`）整体刷新到当前稳定版**：
> - **KSP 的版本号前缀必须等于 Kotlin 版本**（如 `2.4.20-<ksp>`），不刷新会直接构建失败；
> - Compose BOM 与 AGP 的最低版本有两处官方说法不一致（9.1.1 / 9.2.0），**取高者**；
> - `compileSdk 37` 是 Compose 1.12 的硬要求；`targetSdk 36` 是 Google Play 自 2026-08-31 起的底线（自用不上架，但保持一致性更省事）。

```toml
[versions]
# —— 核心工具链（2026-10 现状，已上移）——
agp = "9.2.0"                     # ⚠️ 与 Compose BOM 2026.08.00 配套，按 AGP release notes 复核
kotlin = "2.4.20"
composeBom = "2026.08.00"         # Compose 1.12，要求 compileSdk 37
# —— 以下依赖需开工前刷新，前缀/配对规则见上方警告 ——
ksp = "2.1.0-1.0.29"              # ⚠️ 必须换成 <kotlin 版本>-<ksp 版本>，如 2.4.20-*
hilt = "2.54"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
navigation = "2.8.5"
room = "2.6.1"
datastore = "1.1.1"
okhttp = "4.12.0"
serialization = "1.7.3"
coroutines = "1.9.0"
coil = "2.7.0"
media3 = "1.5.1"
tinypinyin = "2.0.3"
junit = "4.13.2"
truth = "1.4.4"
robolectric = "4.14.1"
androidxTestCore = "1.6.1"
androidxJunit = "1.2.1"
turbine = "1.2.0"
mockwebserver = "4.12.0"
composeUiTest = "1.7.5"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version = "1.15.0" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "mockwebserver" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
coil-compose = { module = "io.coil-kt:coil-compose", version.ref = "coil" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version = "1.2.0" }
junit = { module = "junit:junit", version.ref = "junit" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxJunit" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 3: 写根构建脚本与 settings**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "myplayer"
include(
    ":app",
    ":core:model", ":core:database", ":core:datastore", ":core:network",
    ":core:security", ":core:designsystem",
    ":source:httpapi", ":source:emby",
    ":feature:library", ":feature:lock", ":feature:server",
)
```

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 4: 写各模块构建脚本**

`:core:model` 是纯 Kotlin JVM 模块，`core/model/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
```

其余 Android 库模块（`:core:database`/`:core:datastore`/`:core:network`/`:core:security`/`:core:designsystem`/`:source:httpapi`/`:source:emby`/`:feature:library`/`:feature:lock`/`:feature:server`）统一模板，例如 `core/network/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "app.myplayer.core.network"
    compileSdk = 37                      // Compose 1.12 起要求 37
    defaultConfig {
        minSdk = 26
        targetSdk = 36                   // Play 自 2026-08-31 起的底线；待 Android 17 稳定后再上 37
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
```

> `targetSdk` 必须显式声明。缺省时 AGP 会跟随 `compileSdk`，等于把 App 直接标成面向最新 API，新系统的行为变更（edge-to-edge 强制、权限收紧等）会在无预期的情况下生效。

包名约定：`app.myplayer.core.network` / `app.myplayer.core.database` / `app.myplayer.core.datastore` / `app.myplayer.core.security` / `app.myplayer.core.designsystem` / `app.myplayer.source.httpapi` / `app.myplayer.source.emby` / `app.myplayer.feature.library` / `app.myplayer.feature.lock` / `app.myplayer.feature.server`。

`:core:database` 额外加 `ksp(libs.room.compiler)`；`:core:designsystem` 额外加 `compose` 系列 + `androidx.activity.compose`；`:feature:*` 加 compose + hilt + lifecycle-viewmodel-compose 与 `project(":core:designsystem")`；`:feature:server` 还要加 `project(":core:datastore")`、`project(":core:network")`、`project(":source:httpapi")`、`project(":source:emby")`、`project(":core:security")`；`:app` 用 `com.android.application` + hilt。

`app/src/main/AndroidManifest.xml`（最小可运行）:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="MyPlayer" android:theme="@style/Theme.MyPlayer">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 5: 生成 wrapper 并验证构建**

```bash
gradle wrapper --gradle-version 9.3.1   # AGP 9.x 要求 Gradle ≥ 9.3.x，以 AGP release notes 为准
./gradlew projects                      # Windows: gradlew.bat projects
```

Expected: 输出包含 12 个工程（`:app`、`:core:*`×6、`:source:*`×2、`:feature:*`×3），`BUILD SUCCESSFUL`。

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "chore: scaffold multi-module gradle project"
```

---

## Task 1: `:core:model` — 时间单位 `Ticks`

**Files:**
- Create: `core/model/src/main/kotlin/app/myplayer/core/model/Ticks.kt`
- Test: `core/model/src/test/kotlin/app/myplayer/core/model/TicksTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package app.myplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TicksTest {
    @Test fun `one second equals ten million ticks`() {
        assertThat(Ticks.ofSeconds(1).value).isEqualTo(10_000_000L)
    }
    @Test fun `ticks convert to millis by rounding down`() {
        assertThat(Ticks(10_000L).millis).isEqualTo(1L)
        assertThat(Ticks(15_999L).millis).isEqualTo(1L)
    }
    @Test fun `ticks convert to seconds`() {
        assertThat(Ticks(90_000_000L).seconds).isEqualTo(9L)
    }
    @Test fun `ofMillis round trips`() {
        assertThat(Ticks.ofMillis(1_500L)).isEqualTo(Ticks(15_000_000L))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:model:test`
Expected: FAIL — `Unresolved reference: Ticks`。

> `:core:model` 是纯 Kotlin JVM 模块，**只有 `test`，没有 `testDebugUnitTest`**。

- [ ] **Step 3: 最小实现**

```kotlin
package app.myplayer.core.model

@JvmInline
value class Ticks(val value: Long) {
    val millis: Long get() = value / 10_000L
    val seconds: Long get() = value / 10_000_000L
    companion object {
        val ZERO = Ticks(0L)
        fun ofSeconds(s: Long) = Ticks(s * 10_000_000L)
        fun ofMillis(ms: Long) = Ticks(ms * 10_000L)
    }
}
```

> **`Ticks` 是强制使用的，不是装饰**：Emby / Jellyfin 的时间单位是 100ns tick，MediaStore 是 ms，ExoPlayer 是 ms——三者混用是这类客户端最常见的 bug 来源。因此：
> - **领域层（`:core:model` 的 `MediaItemDto.durationTicks` / `UserData.resumePositionTicks`、`PlaybackPlan` 相关计算）一律用 `Ticks`**；
> - **只在 Room 边界显式转 `Long`**（实体字段仍是 `Long`，`Mappers.kt` 里 `.value` / `Ticks(...)`）——避免依赖 Room 对 inline class 的转换支持，也避免数据库里出现二义性的单位；
> - 跨模块传值时不做隐式 `Long` 互转，需要 ms 时调 `.millis`。
>
> 若实现时发现某处必须用 `Long`，**在该处写明单位注释**，并优先考虑把该字段也改回 `Ticks`。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :core:model:test`
Expected: PASS（4 tests）。

- [ ] **Step 5: 提交**

```bash
git add core/model && git commit -m "feat(model): add Ticks value type"
```

---

## Task 2: `:core:model` — 领域模型与 `MediaSource` 接口

**Files:**
- Create: `core/model/src/main/kotlin/app/myplayer/core/model/Models.kt`
- Create: `core/model/src/main/kotlin/app/myplayer/core/model/MediaSource.kt`
- Test: `core/model/src/test/kotlin/app/myplayer/core/model/MediaItemDtoTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package app.myplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaItemDtoTest {
    private fun item(duration: Ticks, resume: Ticks) = MediaItemDto(
        id = "1", serverId = "s1", sourceType = SourceType.EMBY, title = "T",
        durationTicks = duration, userData = UserData(resumePositionTicks = resume),
    )

    @Test fun `resume fraction is zero when duration unknown`() {
        assertThat(item(Ticks.ZERO, Ticks(500L)).resumeFraction()).isEqualTo(0f)
    }
    @Test fun `resume fraction is ratio of resume to duration`() {
        assertThat(item(Ticks(100L), Ticks(25L)).resumeFraction()).isEqualTo(0.25f)
    }
    @Test fun `resume fraction clamps above one`() {
        assertThat(item(Ticks(100L), Ticks(999L)).resumeFraction()).isEqualTo(1f)
    }
    @Test fun `hasPoster defaults to false`() {
        assertThat(item(Ticks.ZERO, Ticks.ZERO).hasPoster).isFalse()
    }
    @Test fun `paged result reports hasMore`() {
        assertThat(PagedResult(listOf("a", "b"), 5, 0).hasMore).isTrue()
        assertThat(PagedResult(listOf("d", "e"), 5, 3).hasMore).isFalse()
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:model:test`
Expected: FAIL — `Unresolved reference: MediaItemDto`。

- [ ] **Step 3: 实现 `Models.kt`**

```kotlin
package app.myplayer.core.model

enum class SourceType { EMBY, JELLYFIN, LOCAL }
enum class ImageKind { POSTER, BACKDROP, THUMB, LOGO }
enum class ImageSize(val px: Int) { SMALL(200), MEDIUM(400), LARGE(800) }
enum class PlayState { PLAYING, PAUSED, STOPPED }

data class UserData(
    val isPlayed: Boolean = false,
    val resumePositionTicks: Ticks = Ticks.ZERO,
    val isFavorite: Boolean = false,
)

data class MediaItemDto(
    val id: String,
    val serverId: String,
    val sourceType: SourceType,
    val title: String,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val durationTicks: Ticks = Ticks.ZERO,
    val sizeBytes: Long? = null,
    val bitrate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val audioCodecs: List<String> = emptyList(),
    val container: String? = null,
    val dateCreated: Long? = null,
    val communityRating: Float? = null,
    val officialRating: String? = null,
    val playCount: Int = 0,
    val userData: UserData = UserData(),
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val libraryId: String? = null,
    /** 是否有可用海报；false 时 `image()` 返回 [ImageSource.None]，UI 走占位而不是请求一个必然 404 的 URL。 */
    val hasPoster: Boolean = false,
    val trickplayAvailable: Boolean = false,
)

fun MediaItemDto.resumeFraction(): Float {
    if (durationTicks.value <= 0L) return 0f
    return (userData.resumePositionTicks.value.toDouble() / durationTicks.value.toDouble())
        .toFloat().coerceIn(0f, 1f)
}

data class MediaLibrary(
    val id: String,
    val name: String,
    val itemCount: Int? = null,
    val collectionType: String? = null,
)

data class ItemQuery(
    val libraryId: String? = null,
    val searchTerm: String? = null,
    val startIndex: Int = 0,
    val limit: Int = 200,
    val includeItemTypes: List<String> = listOf("Movie", "Series", "Episode", "Video"),
)

data class PagedResult<T>(val items: List<T>, val totalCount: Int, val startIndex: Int) {
    val hasMore: Boolean get() = startIndex + items.size < totalCount
}

sealed interface ImageSource {
    data class Remote(val url: String, val headers: Map<String, String> = emptyMap()) : ImageSource
    data class Local(val uri: String) : ImageSource
    data object None : ImageSource
}

sealed interface PlaybackPlan {
    data class Direct(
        val uri: String,
        val headers: Map<String, String> = emptyMap(),
        val mediaSourceId: String? = null,
    ) : PlaybackPlan

    data class Transcode(
        val hlsUrl: String,
        val headers: Map<String, String> = emptyMap(),
        val isRemuxOnly: Boolean = false,
    ) : PlaybackPlan
}

data class ClientCapabilities(
    val supportedVideoCodecs: Set<String>,
    val supportedAudioCodecs: Set<String>,
    val maxWidth: Int,
    val maxHeight: Int,
    val supportsHdr: Boolean,
    val deviceName: String,
    val deviceId: String,
)

data class TrickplaySpec(
    val width: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val tileCount: Int,
    val intervalMs: Long,
    val playlistUrl: String,
)
```

- [ ] **Step 4: 实现 `MediaSource.kt`**

```kotlin
package app.myplayer.core.model

import kotlin.time.Duration

interface MediaSource {
    val type: SourceType
    val serverId: String
    suspend fun getLibraries(): List<MediaLibrary>
    suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto>
    suspend fun getItemDetail(id: String): MediaItemDto
    suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource
    suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan
    suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec?
    suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState)
}

interface LibraryRepository {
    fun observeLibraries(serverId: String): kotlinx.coroutines.flow.Flow<List<MediaLibrary>>
    fun observeItems(serverId: String, libraryId: String): kotlinx.coroutines.flow.Flow<List<MediaItemDto>>
    suspend fun syncLibraries(source: MediaSource)
    suspend fun syncItems(source: MediaSource, libraryId: String)
}
```

> **单位口径（与 Task 1 的 `Ticks` 配套）**：`MediaItemDto` 侧一律 `Ticks`；`reportProgress` 的 `pos` 用 `kotlin.time.Duration`，因为它的调用方是播放层（ExoPlayer 以 ms 为单位）。**两者只在 `:core:playback` 的边界互转**（`pos.inWholeMilliseconds * 10_000` ↔ `Ticks(...).millis`），转换函数在 P2 实现，M1 不涉及。

- [ ] **Step 5: 运行确认通过**

Run: `./gradlew :core:model:test`
Expected: PASS（Task 1 + Task 2 共 9 tests）。

- [ ] **Step 6: 提交**

```bash
git add core/model && git commit -m "feat(model): add domain models and MediaSource interface"
```

---

## Task 3: `:core:database` — Room 实体、DAO 与映射

**Files:**
- Create: `core/database/src/main/kotlin/app/myplayer/core/database/Entities.kt`
- Create: `core/database/src/main/kotlin/app/myplayer/core/database/Daos.kt`
- Create: `core/database/src/main/kotlin/app/myplayer/core/database/MediaDatabase.kt`
- Create: `core/database/src/main/kotlin/app/myplayer/core/database/Mappers.kt`
- Create: `core/database/src/main/kotlin/app/myplayer/core/database/RoomLibraryRepository.kt`
- Test: `core/database/src/test/kotlin/app/myplayer/core/database/MediaItemDaoTest.kt`
- Test: `core/database/src/test/kotlin/app/myplayer/core/database/MappersTest.kt`

- [ ] **Step 1: 写失败测试（映射）**

```kotlin
package app.myplayer.core.database

import app.myplayer.core.model.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MappersTest {
    private val dto = MediaItemDto(
        id = "42", serverId = "srv", sourceType = SourceType.EMBY, title = "星际穿越",
        durationTicks = Ticks(9_000_000_000L), sizeBytes = 8_000_000_000L, bitrate = 12_000_000,
        width = 3840, height = 2160, videoCodec = "hevc", audioCodecs = listOf("dts", "ac3"),
        container = "mkv", communityRating = 8.6f, playCount = 3,
        userData = UserData(isPlayed = false, resumePositionTicks = Ticks(600_000_000L), isFavorite = true),
        genres = listOf("科幻", "剧情"), cast = listOf("诺兰"), libraryId = "lib1", hasPoster = true,
    )

    @Test fun `round trip preserves scalar fields`() {
        val back = dto.toEntity().toDto()
        assertThat(back.id).isEqualTo(dto.id)
        assertThat(back.sizeBytes).isEqualTo(dto.sizeBytes)
        assertThat(back.bitrate).isEqualTo(dto.bitrate)
        assertThat(back.durationTicks).isEqualTo(Ticks(9_000_000_000L))
        assertThat(back.userData.resumePositionTicks).isEqualTo(Ticks(600_000_000L))
        assertThat(back.sourceType).isEqualTo(SourceType.EMBY)
        assertThat(back.hasPoster).isTrue()
    }

    @Test fun `round trip preserves list fields`() {
        val back = dto.toEntity().toDto()
        assertThat(back.audioCodecs).isEqualTo(listOf("dts", "ac3"))
        assertThat(back.genres).isEqualTo(listOf("科幻", "剧情"))
    }

    @Test fun `null size bytes survives round trip`() {
        assertThat(dto.copy(sizeBytes = null).toEntity().toDto().sizeBytes).isNull()
    }
}
```

- [ ] **Step 2: 写失败测试（DAO，Robolectric）**

```kotlin
package app.myplayer.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.myplayer.core.model.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaItemDaoTest {
    private lateinit var db: MediaDatabase

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MediaDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun item(id: String, lib: String, title: String) = MediaItemDto(
        id = id, serverId = "srv", sourceType = SourceType.EMBY, title = title, libraryId = lib,
    )

    @Test fun `upsert then observe library`() = runTest {
        db.mediaItemDao().upsertAll(listOf(item("1", "lib1", "A").toEntity(), item("2", "lib1", "B").toEntity()))
        val rows = db.mediaItemDao().observeLibrary("srv", "lib1").first()
        assertThat(rows.map { it.title }).containsExactly("A", "B")
    }

    @Test fun `upsert is idempotent on primary key`() = runTest {
        db.mediaItemDao().upsertAll(listOf(item("1", "lib1", "A").toEntity()))
        db.mediaItemDao().upsertAll(listOf(item("1", "lib1", "A2").toEntity()))
        assertThat(db.mediaItemDao().count("srv", "lib1")).isEqualTo(1)
    }

    @Test fun `delete by server clears rows`() = runTest {
        db.mediaItemDao().upsertAll(listOf(item("1", "lib1", "A").toEntity(), item("3", "lib2", "C").toEntity()))
        db.mediaItemDao().deleteByServer("srv")
        assertThat(db.mediaItemDao().count("srv", "lib1")).isEqualTo(0)
        assertThat(db.mediaItemDao().count("srv", "lib2")).isEqualTo(0)
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :core:database:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: MediaDatabase`。

- [ ] **Step 4: 实现实体与 DAO**

`Entities.kt`:

```kotlin
package app.myplayer.core.database

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "libraries", primaryKeys = ["serverId", "libraryId"])
data class LibraryEntity(
    val serverId: String,
    val libraryId: String,
    val name: String,
    val collectionType: String?,
    val itemCount: Int?,
)

@Entity(
    tableName = "media_items",
    primaryKeys = ["serverId", "itemId"],
    indices = [Index("serverId"), Index("libraryId"), Index("sortTitle")],
)
data class MediaItemEntity(
    val serverId: String,
    val itemId: String,
    val sourceType: String,
    val title: String,
    val sortTitle: String,
    val seriesName: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    /** 单位：Emby/Jellyfin tick（100ns）。领域层用 `Ticks`，此处为 Room 边界，见 Mappers.kt 的显式转换。 */
    val durationTicks: Long,
    val sizeBytes: Long?,
    val bitrate: Int?,
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val audioCodecs: String,
    val container: String?,
    val dateCreated: Long?,
    val communityRating: Float?,
    val officialRating: String?,
    val playCount: Int,
    val isPlayed: Boolean,
    /** 单位同上。 */
    val resumePositionTicks: Long,
    val isFavorite: Boolean,
    val genres: String,
    val cast: String,
    val libraryId: String?,
    val hasPoster: Boolean,
    val trickplayAvailable: Boolean,
)
```

`Daos.kt`:

```kotlin
package app.myplayer.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Upsert suspend fun upsertAll(rows: List<LibraryEntity>)
    @Query("SELECT * FROM libraries WHERE serverId = :serverId ORDER BY name")
    fun observe(serverId: String): Flow<List<LibraryEntity>>
    @Query("DELETE FROM libraries WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)
}

@Dao
interface MediaItemDao {
    @Upsert suspend fun upsertAll(rows: List<MediaItemEntity>)
    @Query("SELECT * FROM media_items WHERE serverId = :serverId AND libraryId = :libraryId ORDER BY sortTitle")
    fun observeLibrary(serverId: String, libraryId: String): Flow<List<MediaItemEntity>>
    @Query("SELECT COUNT(*) FROM media_items WHERE serverId = :serverId AND libraryId = :libraryId")
    suspend fun count(serverId: String, libraryId: String): Int
    @Query("DELETE FROM media_items WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)
}
```

- [ ] **Step 5: 实现数据库与映射**

`MediaDatabase.kt`:

```kotlin
package app.myplayer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [LibraryEntity::class, MediaItemEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun mediaItemDao(): MediaItemDao
}
```

`Mappers.kt`:

```kotlin
package app.myplayer.core.database

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData

private const val SEP = "\u0001"
private fun List<String>.encode() = joinToString(SEP)
private fun String?.decode(): List<String> =
    if (isNullOrEmpty()) emptyList() else split(SEP)

fun MediaItemDto.toEntity() = MediaItemEntity(
    serverId = serverId, itemId = id, sourceType = sourceType.name, title = title,
    sortTitle = title.lowercase(), seriesName = seriesName, seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    durationTicks = durationTicks.value,          // Ticks → Long（Room 边界）
    sizeBytes = sizeBytes,
    bitrate = bitrate, width = width, height = height, videoCodec = videoCodec,
    audioCodecs = audioCodecs.encode(), container = container, dateCreated = dateCreated,
    communityRating = communityRating, officialRating = officialRating, playCount = playCount,
    isPlayed = userData.isPlayed,
    resumePositionTicks = userData.resumePositionTicks.value,   // Ticks → Long（Room 边界）
    isFavorite = userData.isFavorite, genres = genres.encode(), cast = cast.encode(),
    libraryId = libraryId, hasPoster = hasPoster, trickplayAvailable = trickplayAvailable,
)

fun MediaItemEntity.toDto() = MediaItemDto(
    id = itemId, serverId = serverId, sourceType = SourceType.valueOf(sourceType), title = title,
    seriesName = seriesName, seasonNumber = seasonNumber, episodeNumber = episodeNumber,
    durationTicks = Ticks(durationTicks),         // Long → Ticks
    sizeBytes = sizeBytes, bitrate = bitrate, width = width,
    height = height, videoCodec = videoCodec, audioCodecs = audioCodecs.decode(), container = container,
    dateCreated = dateCreated, communityRating = communityRating, officialRating = officialRating,
    playCount = playCount,
    userData = UserData(
        isPlayed = isPlayed,
        resumePositionTicks = Ticks(resumePositionTicks),
        isFavorite = isFavorite,
    ),
    genres = genres.decode(), cast = cast.decode(), libraryId = libraryId,
    hasPoster = hasPoster, trickplayAvailable = trickplayAvailable,
)

fun LibraryEntity.toModel() = MediaLibrary(id = libraryId, name = name, itemCount = itemCount, collectionType = collectionType)
fun MediaLibrary.toEntity(serverId: String) = LibraryEntity(serverId, id, name, collectionType, itemCount)
```

- [ ] **Step 6: 实现 `RoomLibraryRepository`**

```kotlin
package app.myplayer.core.database

import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary
import app.myplayer.core.model.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLibraryRepository(private val db: MediaDatabase) : LibraryRepository {

    override fun observeLibraries(serverId: String): Flow<List<MediaLibrary>> =
        db.libraryDao().observe(serverId).map { rows -> rows.map { it.toModel() } }

    override fun observeItems(serverId: String, libraryId: String): Flow<List<MediaItemDto>> =
        db.mediaItemDao().observeLibrary(serverId, libraryId).map { rows -> rows.map { it.toDto() } }

    override suspend fun syncLibraries(source: MediaSource) {
        val libs = source.getLibraries()
        db.libraryDao().upsertAll(libs.map { it.toEntity(source.serverId) })
    }

    override suspend fun syncItems(source: MediaSource, libraryId: String) {
        var start = 0
        val pageSize = 200
        while (true) {
            val page = source.getItems(
                ItemQuery(libraryId = libraryId, startIndex = start, limit = pageSize)
            )
            if (page.items.isEmpty()) break
            db.mediaItemDao().upsertAll(page.items.map { it.toEntity() })
            start += page.items.size
            if (!page.hasMore) break
        }
    }
}
```

> 循环写成「**先处理空页，再累加，最后判 `hasMore`**」：原顺序在服务端返回 `TotalRecordCount` 偏大时会空转一次；这样写同时兼顾「空页/短页」两种情况，不会死循环。

> `ItemQuery` 用 `import app.myplayer.core.model.ItemQuery` 引入，不必写全限定名。

- [ ] **Step 7: 运行确认通过**

Run: `./gradlew :core:database:testDebugUnitTest`
Expected: PASS（6 tests）。

- [ ] **Step 8: 提交**

```bash
git add core/database && git commit -m "feat(database): add room entities, dao, mappers and repository"
```

---

## Task 4: `:core:security` — 图案编码、弱图案规则、密码派生与凭据存储

**Files:**
- Create: `core/security/src/main/kotlin/app/myplayer/core/security/PatternCodec.kt`
- Create: `core/security/src/main/kotlin/app/myplayer/core/security/WeakPatternRules.kt`
- Create: `core/security/src/main/kotlin/app/myplayer/core/security/SecretHasher.kt`
- Create: `core/security/src/main/kotlin/app/myplayer/core/security/Credentials.kt`
- Create: `core/security/src/main/kotlin/app/myplayer/core/security/PatternCredentialStore.kt`
- Test: `core/security/src/test/kotlin/app/myplayer/core/security/PatternCredentialStoreTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package app.myplayer.core.security

import app.myplayer.core.security.VerifyResult.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PatternCredentialStoreTest {
    private val store = PatternCredentialStore(
        hasher = SecretHasher(iterations = 1_000),
        storage = InMemoryCredentialStorage(),
    )

    @Test fun `not configured before save`() {
        assertThat(store.isConfigured()).isFalse()
    }

    @Test fun `correct pattern verifies`() {
        store.savePattern(pattern = listOf(0, 1, 2, 4), backupPin = "1234")
        assertThat(store.verifyPattern(listOf(0, 1, 2, 4))).isInstanceOf(Success::class.java)
    }

    @Test fun `wrong pattern reports remaining attempts`() {
        store.savePattern(pattern = listOf(0, 1, 5, 7), backupPin = null)
        val r = store.verifyPattern(listOf(8, 6, 3, 0))
        assertThat(r).isInstanceOf(WrongPattern::class.java)
        assertThat((r as WrongPattern).attemptsLeft).isEqualTo(4)
    }

    @Test fun `cooldown starts after five failures`() {
        store.savePattern(pattern = listOf(0, 3, 6, 7), backupPin = null)
        repeat(5) { store.verifyPattern(listOf(1, 4, 7, 8)) }
        assertThat(store.verifyPattern(listOf(1, 4, 7, 8))).isInstanceOf(Cooldown::class.java)
    }

    @Test fun `backup pin verifies`() {
        store.savePattern(pattern = listOf(0, 1, 5, 7), backupPin = "4321")
        assertThat(store.verifyPin("4321")).isInstanceOf(Success::class.java)
    }

    @Test fun `weak pattern encoded is rejected on save`() {
        val r = runCatching { store.savePattern(pattern = listOf(0, 3, 6), backupPin = null) }
        assertThat(r.isFailure).isTrue()
    }

    @Test fun `pattern codec joins indices`() {
        assertThat(PatternCodec.encode(listOf(0, 1, 2, 4))).isEqualTo("0-1-2-4")
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:security:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: PatternCredentialStore`。

- [ ] **Step 3: 实现纯 Kotlin 部分**

`PatternCodec.kt`:

```kotlin
package app.myplayer.core.security

object PatternCodec {
    fun encode(points: List<Int>): String = points.joinToString("-")
}
```

`WeakPatternRules.kt`:

```kotlin
package app.myplayer.core.security

object WeakPatternRules {
    private const val MIN_POINTS = 4

    fun isWeak(points: List<Int>): Boolean {
        if (points.size < MIN_POINTS) return true
        val coords = points.map { it / 3 to it % 3 }
        val first = coords.first()
        // 所有点共线（同一行 / 同一列 / 同一对角线的直线）视为弱图案
        return coords.all { (r, c) -> r == first.first } ||
            coords.all { (r, c) -> c == first.second } ||
            coords.all { (r, c) -> r - c == first.first - first.second } ||
            coords.all { (r, c) -> r + c == first.first + first.second }
    }
}
```

`SecretHasher.kt`:

```kotlin
package app.myplayer.core.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SecretHasher(
    private val iterations: Int = 120_000,
    private val keyLengthBits: Int = 256,
    private val random: SecureRandom = SecureRandom(),
) {
    fun newSalt(size: Int = 16): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun derive(secret: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, keyLengthBits)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun verify(secret: String, salt: ByteArray, expected: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(derive(secret, salt), expected)
}
```

`Credentials.kt`:

```kotlin
package app.myplayer.core.security

import java.util.Base64

interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipher: ByteArray): ByteArray
}

data class CredentialRecord(
    val patternHash: String,   // Base64(派生结果)
    val patternSalt: String,   // Base64
    val backupPinHash: String? = null,
    val backupPinSalt: String? = null,
    val failedAttempts: Int = 0,
    val lastFailedAtMillis: Long = 0L,
)

interface CredentialStorage {
    fun read(): CredentialRecord?
    fun write(record: CredentialRecord)
    fun clear()
}

class InMemoryCredentialStorage : CredentialStorage {
    private var record: CredentialRecord? = null
    override fun read() = record
    override fun write(record: CredentialRecord) { this.record = record }
    override fun clear() { record = null }
}

internal fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
internal fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)
```

- [ ] **Step 4: 实现 `PatternCredentialStore`**

```kotlin
package app.myplayer.core.security

import java.util.concurrent.TimeUnit

sealed interface VerifyResult {
    data object Success : VerifyResult
    data class WrongPattern(val attemptsLeft: Int) : VerifyResult
    data object WrongPin : VerifyResult
    data class Cooldown(val remainingMillis: Long) : VerifyResult
    data object NotConfigured : VerifyResult
}

class PatternCredentialStore(
    private val hasher: SecretHasher,
    private val storage: CredentialStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun isConfigured(): Boolean = storage.read() != null

    fun savePattern(pattern: List<Int>, backupPin: String?) {
        require(!WeakPatternRules.isWeak(pattern)) { "图案过于简单" }
        val salt = hasher.newSalt()
        val pinSalt = backupPin?.let { hasher.newSalt() }
        storage.write(
            CredentialRecord(
                patternHash = hasher.derive(PatternCodec.encode(pattern), salt).toBase64(),
                patternSalt = salt.toBase64(),
                backupPinHash = backupPin?.let { hasher.derive(it, pinSalt!!).toBase64() },
                backupPinSalt = pinSalt?.toBase64(),
            )
        )
    }

    fun verifyPattern(pattern: List<Int>): VerifyResult = verify(PatternCodec.encode(pattern), isPin = false)

    fun verifyPin(pin: String): VerifyResult = verify(pin, isPin = true)

    private fun verify(secret: String, isPin: Boolean): VerifyResult {
        val record = storage.read() ?: return VerifyResult.NotConfigured
        cooldownOf(record)?.let { return it }
        val saltB64 = if (isPin) record.backupPinSalt else record.patternSalt
        val hashB64 = if (isPin) record.backupPinHash else record.patternHash
        if (saltB64 == null || hashB64 == null) return VerifyResult.WrongPin

        return if (hasher.verify(secret, saltB64.fromBase64(), hashB64.fromBase64())) {
            storage.write(record.copy(failedAttempts = 0, lastFailedAtMillis = 0L))
            VerifyResult.Success
        } else {
            val attempts = record.failedAttempts + 1
            storage.write(record.copy(failedAttempts = attempts, lastFailedAtMillis = nowMillis()))
            if (attempts >= MAX_ATTEMPTS) {
                VerifyResult.Cooldown(cooldownMillis(attempts))
            } else if (isPin) {
                VerifyResult.WrongPin
            } else {
                VerifyResult.WrongPattern(attemptsLeft = MAX_ATTEMPTS - attempts)
            }
        }
    }

    private fun cooldownOf(record: CredentialRecord): VerifyResult.Cooldown? {
        if (record.failedAttempts < MAX_ATTEMPTS) return null
        val elapsed = nowMillis() - record.lastFailedAtMillis
        val remaining = cooldownMillis(record.failedAttempts) - elapsed
        return if (remaining > 0) VerifyResult.Cooldown(remaining) else null
    }

    private fun cooldownMillis(attempts: Int): Long = when {
        attempts >= 8 -> TimeUnit.MINUTES.toMillis(5)
        attempts >= 7 -> TimeUnit.MINUTES.toMillis(1)
        else -> TimeUnit.SECONDS.toMillis(30)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}
```

- [ ] **Step 5: 实现 Android Keystore 版 `SecretCipher`**

`core/security/src/main/kotlin/app/myplayer/core/security/KeystoreSecretCipher.kt`:

```kotlin
package app.myplayer.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreSecretCipher(
    private val alias: String = "myplayer_credentials",
) : SecretCipher {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(cipherBytes: ByteArray): ByteArray {
        val ivLen = cipherBytes[0].toInt()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, cipherBytes.copyOfRange(1, 1 + ivLen)))
        return cipher.doFinal(cipherBytes.copyOfRange(1 + ivLen, cipherBytes.size))
    }
}
```

- [ ] **Step 6: 运行确认通过**

Run: `./gradlew :core:security:testDebugUnitTest`
Expected: PASS（7 tests）。

- [ ] **Step 7: 提交**

```bash
git add core/security && git commit -m "feat(security): add pattern credential store with pbkdf2 and keystore cipher"
```

---

## Task 5: `:core:datastore` — 设置、服务器配置与加密令牌存储

**Files:**
- Create: `core/datastore/src/main/kotlin/app/myplayer/core/datastore/PlayerSettings.kt`
- Create: `core/datastore/src/main/kotlin/app/myplayer/core/datastore/SettingsStore.kt`
- Create: `core/datastore/src/main/kotlin/app/myplayer/core/datastore/ServerConfigStore.kt`
- Create: `core/datastore/src/main/kotlin/app/myplayer/core/datastore/EncryptedTokenStore.kt`
- Create: `core/datastore/src/main/kotlin/app/myplayer/core/datastore/FileStores.kt`
- Test: `core/datastore/src/test/kotlin/app/myplayer/core/datastore/SettingsStoreTest.kt`
- Test: `core/datastore/src/test/kotlin/app/myplayer/core/datastore/ServerConfigStoreTest.kt`
- Test: `core/datastore/src/test/kotlin/app/myplayer/core/datastore/FileServerConfigStoreTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package app.myplayer.core.datastore

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SettingsStoreTest {
    @Test fun `默认倍速为一点零且应用锁默认开启`() = runTest {
        val store = InMemorySettingsStore()
        val s = store.settings.first()
        assertThat(s.defaultSpeed).isEqualTo(1.0f)
        assertThat(s.appLockEnabled).isTrue()
        assertThat(s.keepBackgroundPlayback).isFalse()
        assertThat(s.unlockMethod).isEqualTo(UnlockMethod.PATTERN)
        assertThat(s.hideRecentsSnapshot).isFalse()
        assertThat(s.libraryDisplayMode).isEqualTo(LibraryDisplayMode.AUTO)
    }

    @Test fun `setSpeed 会持久化且不污染其它项`() = runTest {
        val store = InMemorySettingsStore()
        store.setDefaultSpeed(1.5f)
        val s = store.settings.first()
        assertThat(s.defaultSpeed).isEqualTo(1.5f)
        assertThat(s.autoPlayNext).isTrue()
    }

    @Test fun `update 是唯一写入口且只改目标项`() = runTest {
        val store = InMemorySettingsStore()
        store.update { it.copy(keepBackgroundPlayback = true, doubleTapSeekMillis = 30_000L) }
        val s = store.settings.first()
        assertThat(s.keepBackgroundPlayback).isTrue()
        assertThat(s.doubleTapSeekMillis).isEqualTo(30_000L)
        assertThat(s.defaultSpeed).isEqualTo(1.0f)
        assertThat(s.appLockEnabled).isTrue()
    }

    @Test fun `画面与字幕设置项有明确默认值`() = runTest {
        val s = InMemorySettingsStore().settings.first()
        assertThat(s.screenOrientation).isEqualTo(ScreenOrientation.AUTO)
        assertThat(s.defaultAspectRatio).isEqualTo(AspectRatio.FIT)
        assertThat(s.subtitleStyle.fontScale).isEqualTo(1.0f)
    }
}
```

```kotlin
package app.myplayer.core.datastore

import app.myplayer.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ServerConfigStoreTest {
    @Test fun `upsert then observe`() = runTest {
        val store = InMemoryServerConfigStore()
        store.upsert(ServerConfig(id = "a", sourceType = SourceType.EMBY, name = "Home", baseUrl = "http://10.0.0.2:8096"))
        val all = store.observeAll().first()
        assertThat(all).hasSize(1)
        assertThat(all.first().baseUrl).isEqualTo("http://10.0.0.2:8096")
    }

    @Test fun `same id updates not duplicates`() = runTest {
        val store = InMemoryServerConfigStore()
        store.upsert(ServerConfig("a", SourceType.EMBY, "Home", "http://x"))
        store.upsert(ServerConfig("a", SourceType.EMBY, "Home2", "http://y"))
        assertThat(store.observeAll().first()).hasSize(1)
        assertThat(store.observeAll().first().first().baseUrl).isEqualTo("http://y")
    }
}
```

```kotlin
package app.myplayer.core.datastore

import app.myplayer.core.model.SourceType
import app.myplayer.core.security.SecretCipher
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 落盘版的验收：跨实例（= 模拟重启）可读回，且磁盘上不出现明文。 */
class FileServerConfigStoreTest {
    private lateinit var dir: File
    private lateinit var file: File

    /** 测试用假密码器：可逆且能一眼看出是否落盘 —— 真机用 KeystoreSecretCipher（Task 4）。 */
    private val reversed = object : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(bytes: ByteArray) = bytes.reversedArray()
    }

    @Before fun setup() {
        dir = Files.createTempDirectory("cfg").toFile()
        file = File(dir, "servers.bin")
    }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun store() = FileServerConfigStore(file, reversed)

    @Test fun `落盘后新实例仍能读回`() = runTest {
        store().upsert(ServerConfig("a", SourceType.EMBY, "Home", "http://10.0.0.2:8096"))
        val reopened = store().observeAll().first()
        assertThat(reopened).hasSize(1)
        assertThat(reopened.first().baseUrl).isEqualTo("http://10.0.0.2:8096")
    }

    @Test fun `磁盘上不出现明文地址`() = runTest {
        store().upsert(ServerConfig("a", SourceType.EMBY, "Home", "http://10.0.0.2:8096"))
        assertThat(String(file.readBytes())).doesNotContain("10.0.0.2")
    }
}
```

> 说明：设置与服务器配置都按「**接口 + 内存实现 + 落盘实现**」三层定义。内存实现服务于 JVM 逻辑测试；落盘实现服务于 M1 DoD 的「令牌与配置为密文」验收。真机上由 Hilt 注入落盘实现（Task 12），内存实现只在测试里出现。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:datastore:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: InMemorySettingsStore`。

- [ ] **Step 3: 实现设置模型与存储接口**

`PlayerSettings.kt`（覆盖附 B 的**全部**设置项；新增项只需加字段，无需改 Store）：

```kotlin
package app.myplayer.core.datastore

import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class UnlockMethod { PATTERN, PIN, BIOMETRIC }
enum class LibraryDisplayMode { AUTO, TABS, DRAWER, AGGREGATE }
enum class ScreenOrientation { AUTO, LANDSCAPE, PORTRAIT }
enum class AspectRatio { FIT, FILL, CROP, ORIGINAL }

@Serializable
data class SubtitleStyle(
    val fontScale: Float = 1.0f,
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val outlineColorArgb: Int = 0xFF000000.toInt(),
    val outlineWidthDp: Float = 2f,
    val bottomPaddingDp: Float = 16f,
)

/** 与 spec 附 B 一一对应；分组顺序也和设置页一致，便于 P5 直接渲染。 */
@Serializable
data class PlayerSettings(
    // 外观
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // 播放
    val defaultSpeed: Float = 1.0f,
    val keepBackgroundPlayback: Boolean = false,
    val doubleTapSeekMillis: Long = 10_000L,
    val autoPlayNext: Boolean = true,
    // 画面
    val screenOrientation: ScreenOrientation = ScreenOrientation.AUTO,
    val defaultAspectRatio: AspectRatio = AspectRatio.FIT,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    // 隐私
    val appLockEnabled: Boolean = true,
    val unlockMethod: UnlockMethod = UnlockMethod.PATTERN,
    val unlockGraceMillis: Long = 0L,
    val hideRecentsSnapshot: Boolean = false,
    // 网络
    val allowCleartextHttp: Boolean = false,
    val trustSelfSignedCert: Boolean = false,
    // 预览
    val localThumbnails: Boolean = false,
    // 搜索
    val searchFuzzyPinyin: Boolean = false,
    val searchTraditionalSimplified: Boolean = false,
    // 界面
    val libraryDisplayMode: LibraryDisplayMode = LibraryDisplayMode.AUTO,
)
```

`SettingsStore.kt`（接口 + 内存实现 + DataStore 实现）:

```kotlin
package app.myplayer.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 设置的唯一写入口是 [update]，其它 setter 都是它的语法糖。
 * 好处：新增设置项时只需在 PlayerSettings 加字段 + 加一行 setter，
 * 不会出现「模型有字段但 Store 没有 setter」的缺口（原稿就是这种缺口）。
 */
interface SettingsStore {
    val settings: Flow<PlayerSettings>
    suspend fun update(transform: (PlayerSettings) -> PlayerSettings)

    suspend fun setThemeMode(v: ThemeMode) = update { it.copy(themeMode = v) }
    suspend fun setDefaultSpeed(v: Float) = update { it.copy(defaultSpeed = v) }
    suspend fun setKeepBackgroundPlayback(v: Boolean) = update { it.copy(keepBackgroundPlayback = v) }
    suspend fun setDoubleTapSeekMillis(v: Long) = update { it.copy(doubleTapSeekMillis = v) }
    suspend fun setAutoPlayNext(v: Boolean) = update { it.copy(autoPlayNext = v) }
    suspend fun setScreenOrientation(v: ScreenOrientation) = update { it.copy(screenOrientation = v) }
    suspend fun setDefaultAspectRatio(v: AspectRatio) = update { it.copy(defaultAspectRatio = v) }
    suspend fun setSubtitleStyle(v: SubtitleStyle) = update { it.copy(subtitleStyle = v) }
    suspend fun setAppLockEnabled(v: Boolean) = update { it.copy(appLockEnabled = v) }
    suspend fun setUnlockMethod(v: UnlockMethod) = update { it.copy(unlockMethod = v) }
    suspend fun setUnlockGraceMillis(v: Long) = update { it.copy(unlockGraceMillis = v) }
    suspend fun setHideRecentsSnapshot(v: Boolean) = update { it.copy(hideRecentsSnapshot = v) }
    suspend fun setAllowCleartextHttp(v: Boolean) = update { it.copy(allowCleartextHttp = v) }
    suspend fun setTrustSelfSignedCert(v: Boolean) = update { it.copy(trustSelfSignedCert = v) }
    suspend fun setLocalThumbnails(v: Boolean) = update { it.copy(localThumbnails = v) }
    suspend fun setSearchFuzzyPinyin(v: Boolean) = update { it.copy(searchFuzzyPinyin = v) }
    suspend fun setSearchTraditionalSimplified(v: Boolean) = update { it.copy(searchTraditionalSimplified = v) }
    suspend fun setLibraryDisplayMode(v: LibraryDisplayMode) = update { it.copy(libraryDisplayMode = v) }
}

class InMemorySettingsStore : SettingsStore {
    private val state = MutableStateFlow(PlayerSettings())
    override val settings: Flow<PlayerSettings> get() = state
    override suspend fun update(transform: (PlayerSettings) -> PlayerSettings) {
        state.value = transform(state.value)
    }
}

```

> **为什么整份设置存成一个 JSON**：`PlayerSettings` 是纯数据、字段少、读多写少；单 key 存 JSON（`player_settings_v1`）比逐字段 key 更不容易漏，加字段也不用写迁移。若后续设置项显著变多或需要严格跨版本迁移，再换 proto DataStore（spec §9 无此要求，M1 不做）。

`DataStoreSettingsStore`（同文件内）：

```kotlin
class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {
    private val key = stringPreferencesKey("player_settings_v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val settings: Flow<PlayerSettings> = dataStore.data
        // 读取失败 / 文件损坏时退回默认值，不能让整条 Flow 抛异常
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[key]?.let(::decode) ?: PlayerSettings() }

    override suspend fun update(transform: (PlayerSettings) -> PlayerSettings) {
        dataStore.edit { prefs ->
            val current = prefs[key]?.let(::decode) ?: PlayerSettings()
            prefs[key] = json.encodeToString(transform(current))
        }
    }

    private fun decode(raw: String): PlayerSettings =
        runCatching { json.decodeFromString<PlayerSettings>(raw) }.getOrElse { PlayerSettings() }
}
```

- [ ] **Step 4: 实现服务器配置与令牌存储**

`ServerConfigStore.kt`:

```kotlin
package app.myplayer.core.datastore

import app.myplayer.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

data class ServerConfig(
    val id: String,
    val sourceType: SourceType,
    val name: String,
    val baseUrl: String,
    val userId: String? = null,
)

interface ServerConfigStore {
    fun observeAll(): Flow<List<ServerConfig>>
    suspend fun upsert(config: ServerConfig)
    suspend fun remove(id: String)
}

class InMemoryServerConfigStore : ServerConfigStore {
    private val state = MutableStateFlow<List<ServerConfig>>(emptyList())
    override fun observeAll(): Flow<List<ServerConfig>> = state
    override suspend fun upsert(config: ServerConfig) {
        state.value = state.value.filterNot { it.id == config.id } + config
    }
    override suspend fun remove(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }
}
```

`EncryptedTokenStore.kt`:

```kotlin
package app.myplayer.core.datastore

import app.myplayer.core.security.SecretCipher

interface TokenStore {
    fun put(serverId: String, token: String)
    fun get(serverId: String): String?
    fun remove(serverId: String)
}

class InMemoryTokenStore : TokenStore {
    private val map = mutableMapOf<String, String>()
    override fun put(serverId: String, token: String) { map[serverId] = token }
    override fun get(serverId: String): String? = map[serverId]
    override fun remove(serverId: String) { map.remove(serverId) }
}

/** 令牌以密文写入磁盘；读写通过 SecretCipher，明文不落盘。 */
class EncryptedTokenStore(
    private val cipher: SecretCipher,
    private val readBytes: (String) -> ByteArray?,
    private val writeBytes: (String, ByteArray) -> Unit,
    private val deleteFile: (String) -> Unit,
    private val charset: Charset = Charsets.UTF_8,
) : TokenStore {
    private fun fileName(serverId: String) = "token_$serverId.bin"
    override fun put(serverId: String, token: String) =
        writeBytes(fileName(serverId), cipher.encrypt(token.toByteArray(charset)))
    override fun get(serverId: String): String? =
        readBytes(fileName(serverId))?.let { String(cipher.decrypt(it), charset) }
    override fun remove(serverId: String) = deleteFile(fileName(serverId))
}
```

> `Charset` 从 `java.nio.charset.Charset` 导入。

- [ ] **Step 5: 实现落盘版存储（`FileStores.kt`）**

> 原稿在 Task 12 的 DI 里注入的是 `InMemoryServerConfigStore` / `InMemoryTokenStore`，这与 DoD「令牌为密文落盘」**直接冲突**（内存实现没有任何文件产出，也活不过一次进程重启）。本步补齐落盘实现，Task 12 改为注入它们。

```kotlin
package app.myplayer.core.datastore

import app.myplayer.core.model.SourceType
import app.myplayer.core.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** filesDir 下的字节文件读写，供 [EncryptedTokenStore] 使用。 */
class DirByteFileStorage(private val dir: File) {
    fun read(name: String): ByteArray? = File(dir, name).takeIf { it.exists() }?.readBytes()
    fun write(name: String, bytes: ByteArray) {
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
    }
    fun delete(name: String) { File(dir, name).delete() }
}

@Serializable
private data class ServerConfigRecord(
    val id: String,
    val sourceType: String,
    val name: String,
    val baseUrl: String,
    val userId: String? = null,
)

/**
 * 服务器配置以**密文**写入 `filesDir/servers.bin`（spec §5.1 要求令牌与配置加密存储）。
 * 加载失败（文件损坏、或换机后 Keystore 密钥失效）时退化为空配置，不抛异常、不崩启动。
 */
class FileServerConfigStore(
    private val file: File,
    private val cipher: SecretCipher,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ServerConfigStore {

    private val state = MutableStateFlow(load())

    override fun observeAll(): Flow<List<ServerConfig>> = state.asStateFlow()

    override suspend fun upsert(config: ServerConfig) {
        write(state.value.filterNot { it.id == config.id } + config)
    }

    override suspend fun remove(id: String) {
        write(state.value.filterNot { it.id == id })
    }

    private fun load(): List<ServerConfig> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString<List<ServerConfigRecord>>(String(cipher.decrypt(file.readBytes())))
            .map { ServerConfig(it.id, SourceType.valueOf(it.sourceType), it.name, it.baseUrl, it.userId) }
    }.getOrElse { emptyList() }

    private fun write(list: List<ServerConfig>) {
        val records = list.map { ServerConfigRecord(it.id, it.sourceType.name, it.name, it.baseUrl, it.userId) }
        file.parentFile?.mkdirs()
        file.writeBytes(cipher.encrypt(json.encodeToString(records).toByteArray()))
        state.value = list
    }
}
```

- [ ] **Step 6: 运行确认通过**

Run: `./gradlew :core:datastore:testDebugUnitTest`
Expected: PASS（8 tests：SettingsStore 4 + ServerConfigStore 2 + FileServerConfigStore 2）。

- [ ] **Step 7: 提交**

```bash
git add core/datastore && git commit -m "feat(datastore): add settings, server config and encrypted token store"
```

---

## Task 6: `:core:network` — OkHttp 工厂、鉴权头拦截器与错误映射

**Files:**
- Create: `core/network/src/main/kotlin/app/myplayer/core/network/NetworkConfig.kt`
- Create: `core/network/src/main/kotlin/app/myplayer/core/network/HttpClientFactory.kt`
- Create: `core/network/src/main/kotlin/app/myplayer/core/network/ApiException.kt`
- Test: `core/network/src/test/kotlin/app/myplayer/core/network/HttpClientFactoryTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package app.myplayer.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpClientFactoryTest {
    private lateinit var server: MockWebServer

    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun teardown() { server.shutdown() }

    @Test fun `auth interceptor attaches headers`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        val client: OkHttpClient = HttpClientFactory.create(
            config = NetworkConfig(tokenProvider = { mapOf("X-Emby-Token" to "T1") })
        )
        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { }
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("X-Emby-Token")).isEqualTo("T1")
    }

    @Test fun `toApiException maps 401`() {
        assertThat(ApiException.Companion.fromStatus(401, "")).isInstanceOf(ApiException.Unauthorized::class.java)
    }

    @Test fun `toApiException maps 404`() {
        assertThat(ApiException.Companion.fromStatus(404, "")).isInstanceOf(ApiException.NotFound::class.java)
    }

    @Test fun `toApiException maps 5xx to server error`() {
        val e = ApiException.Companion.fromStatus(503, "oops")
        assertThat(e).isInstanceOf(ApiException.ServerError::class.java)
        assertThat((e as ApiException.ServerError).body).isEqualTo("oops")
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:network:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: HttpClientFactory`。

- [ ] **Step 3: 实现**

`NetworkConfig.kt`:

```kotlin
package app.myplayer.core.network

import okhttp3.Interceptor
import java.util.concurrent.TimeUnit

data class NetworkConfig(
    val tokenProvider: () -> Map<String, String> = { emptyMap() },
    val allowCleartextHttp: Boolean = false,
    val trustSelfSignedCert: Boolean = false,
    val connectTimeoutSeconds: Long = 15,
    val readTimeoutSeconds: Long = 30,
    val extraInterceptors: List<Interceptor> = emptyList(),
)
```

`HttpClientFactory.kt`:

```kotlin
package app.myplayer.core.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientFactory {
    fun create(config: NetworkConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(AuthHeaderInterceptor(config.tokenProvider))
        if (config.trustSelfSignedCert) builder.applyTrustAll()
        config.extraInterceptors.forEach(builder::addInterceptor)
        return builder.build()
    }

    private class AuthHeaderInterceptor(private val provider: () -> Map<String, String>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val headers = provider()
            if (headers.isEmpty()) return chain.proceed(chain.request())
            val request = chain.request().newBuilder().apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            return chain.proceed(request)
        }
    }
}
```

`core/network/src/main/kotlin/app/myplayer/core/network/TrustAllSupport.kt`:

```kotlin
package app.myplayer.core.network

import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal fun OkHttpClient.Builder.applyTrustAll() {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), java.security.SecureRandom()) }
    sslSocketFactory(ctx.socketFactory, trustAll)
    hostnameVerifier { _, _ -> true }
}
```

`ApiException.kt`:

```kotlin
package app.myplayer.core.network

sealed class ApiException(message: String) : Exception(message) {
    class Unauthorized(message: String = "认证失败") : ApiException(message)
    class NotFound(message: String = "资源不存在") : ApiException(message)
    class ServerError(val code: Int, val body: String) : ApiException("服务端错误 $code")
    class Unknown(val code: Int, val body: String) : ApiException("未知错误 $code")

    companion object {
        fun fromStatus(code: Int, body: String): ApiException = when {
            code == 401 || code == 403 -> Unauthorized()
            code == 404 -> NotFound()
            code in 500..599 -> ServerError(code, body)
            else -> Unknown(code, body)
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :core:network:testDebugUnitTest`
Expected: PASS（4 tests）。

- [ ] **Step 5: 提交**

```bash
git add core/network && git commit -m "feat(network): add okhttp factory, auth interceptor and api error mapping"
```

---

## Task 7: `:source:httpapi` — Emby/Jellyfin 共享 REST 骨架与 DTO

**Files:**
- Create: `source/httpapi/src/main/kotlin/app/myplayer/source/httpapi/Dtos.kt`
- Create: `source/httpapi/src/main/kotlin/app/myplayer/source/httpapi/SourceConfig.kt`
- Create: `source/httpapi/src/main/kotlin/app/myplayer/source/httpapi/HttpMediaApi.kt`
- Create: `source/httpapi/src/main/kotlin/app/myplayer/source/httpapi/HttpMediaSource.kt`
- Test: `source/httpapi/src/test/kotlin/app/myplayer/source/httpapi/HttpMediaApiTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package app.myplayer.source.httpapi

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpMediaApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpMediaApi

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        api = HttpMediaApi(
            client = OkHttpClient(),
            config = SourceConfig(
                serverId = "s1", baseUrl = server.url("/").toString().trimEnd('/'),
                token = "T", userId = "u1",           // 必须给 userId：取数接口会 requireNotNull
                deviceId = "d1", clientName = "myplayer",
            ),
        )
    }
    @After fun teardown() { server.shutdown() }

    @Test fun `getItems parses response into dtos`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"i1","Name":"Le Film","RunTimeTicks":60000000}],"TotalRecordCount":1}"""))
        val page = api.getItems(libraryId = "lib1", startIndex = 0, limit = 50)
        assertThat(page.totalCount).isEqualTo(1)
        assertThat(page.items.first().id).isEqualTo("i1")
        assertThat(page.items.first().name).isEqualTo("Le Film")
    }

    @Test fun `列表查询默认不请求 People 字段`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}"""))
        api.getItems(libraryId = "lib1", startIndex = 0, limit = 50)
        assertThat(server.takeRequest().path).doesNotContain("People")

        server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}"""))
        api.getItems(libraryId = "lib1", startIndex = 0, limit = 50, includePeople = true)
        assertThat(server.takeRequest().path).contains("People")
    }

    @Test fun `getViews 走 Views 专用端点`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"lib1","Name":"电影","CollectionType":"movies","ChildCount":42}],"TotalRecordCount":1}"""))
        val views = api.getViews()
        assertThat(server.takeRequest().path).startsWith("/Users/u1/Views")
        assertThat(views.items.first().collectionType).isEqualTo("movies")
        assertThat(views.items.first().childCount).isEqualTo(42)
    }

    @Test fun `authenticate sends body and parses token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"AccessToken":"TK","User":{"Id":"u1","Name":"bob"}}"""))
        val result = api.authenticateByName("bob", "pw")
        assertThat(result.accessToken).isEqualTo("TK")
        assertThat(result.user.id).isEqualTo("u1")
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/Users/AuthenticateByName")
        assertThat(req.method).isEqualTo("POST")
    }

    @Test fun `image url 带尺寸但不带令牌`() = runTest {
        val url = api.imageUrl(itemId = "i1", kind = "Primary", maxWidth = 400)
        assertThat(url).contains("/Items/i1/Images/Primary")
        assertThat(url).contains("maxWidth=400")
        assertThat(url).doesNotContain("api_key")   // 令牌走 headers，不进 URL / Coil 缓存键
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :source:httpapi:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: HttpMediaApi`。

- [ ] **Step 3: 实现 DTO**

`Dtos.kt`:

```kotlin
package app.myplayer.source.httpapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicSystemInfoDto(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("ProductName") val productName: String? = null,
)

@Serializable
data class AuthResultDto(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: UserDto,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("CommunityRating") val communityRating: Float? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    /** 有 "Primary" 键 = 有条目海报；用于 `MediaItemDto.hasPoster`，缺海报时不发无意义的图片请求。 */
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("UserData") val userData: UserDataDto? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfoDto>? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("People") val people: List<PersonDto>? = null,
)

@Serializable
data class UserDataDto(
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0L,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("PlayCount") val playCount: Int = 0,
)

@Serializable
data class PersonDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
)

@Serializable
data class MediaSourceInfoDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
)

@Serializable
data class ItemsResponseDto(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class PlaybackInfoResponseDto(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfoDto> = emptyList(),
)
```

- [ ] **Step 4: 实现 `SourceConfig` 与 `HttpMediaApi`**

`SourceConfig.kt`:

```kotlin
package app.myplayer.source.httpapi

data class SourceConfig(
    val serverId: String,
    val baseUrl: String,            // 已去除尾斜杠
    val prefix: String = "",        // 飞牛实测前缀（M0 结论），Emby 默认空
    val token: String? = null,
    val userId: String? = null,
    val deviceId: String = "unknown",
    val clientName: String = "myplayer",
    val clientVersion: String = "0.1.0",
    val authHeaderStyle: AuthHeaderStyle = AuthHeaderStyle.X_EMBY_TOKEN,
)

enum class AuthHeaderStyle { X_EMBY_TOKEN, MEDIABROWSER_AUTHORIZATION }
```

`HttpMediaApi.kt`:

```kotlin
package app.myplayer.source.httpapi

import app.myplayer.core.model.PagedResult
import app.myplayer.core.network.ApiException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

class HttpMediaApi(
    private val client: OkHttpClient,
    val config: SourceConfig,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private fun url(path: String, extra: Map<String, String> = emptyMap()): HttpUrl {
        val base = "${config.baseUrl}${config.prefix}".trimEnd('/')
        val builder = "$base$path".toHttpUrl().newBuilder()
        extra.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    /** 公开给 `HttpMediaSource.image()` 用：图片请求也走请求头鉴权，**不把令牌写进 URL**。 */
    fun authHeaders(): Map<String, String> {
        val token = config.token ?: return emptyMap()
        return when (config.authHeaderStyle) {
            AuthHeaderStyle.X_EMBY_TOKEN -> mapOf("X-Emby-Token" to token)
            AuthHeaderStyle.MEDIABROWSER_AUTHORIZATION ->
                mapOf("Authorization" to "MediaBrowser Token=\"$token\"")
        }
    }

    private fun clientInfoHeader(): String =
        "MediaBrowser Client=\"${config.clientName}\", Device=\"android\", DeviceId=\"${config.deviceId}\", Version=\"${config.clientVersion}\""

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException.fromStatus(resp.code, body)
            json.decodeFromString<T>(body)
        }
    }

    suspend fun getPublicSystemInfo(): PublicSystemInfoDto =
        execute(Request.Builder().url(url("/System/Info/Public")).build())

    suspend fun authenticateByName(username: String, password: String): AuthResultDto {
        val payload = """{"Username":"${username.esc()}","Pw":"${password.esc()}"}"""
        val request = Request.Builder()
            .url(url("/Users/AuthenticateByName"))
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("X-Emby-Authorization", clientInfoHeader())
            .build()
        return execute(request)
    }

    /**
     * 库列表：用 `/Users/{userId}/Views`（Emby/Jellyfin 的「媒体库根」专用端点）。
     * 不要用 `Items + IncludeItemTypes=CollectionFolder` 代替——那会把非媒体库的文件夹一起扫进来。
     * ⚠️ 飞牛是否支持该端点需在 M0 实测（P0 Task 3）；若不支持，再在 `:source:jellyfin` 覆盖。
     */
    suspend fun getViews(): PagedResult<BaseItemDto> {
        val userId = requireNotNull(config.userId) { "登录后才能获取媒体库列表" }
        val dto = execute<ItemsResponseDto>(
            Request.Builder()
                .url(url("/Users/$userId/Views", mapOf("IncludeExternalContent" to "false")))
                .applyAuth().build()
        )
        return PagedResult(dto.items, dto.totalRecordCount, 0)
    }

    /**
     * 列表查询。`includePeople=false` 时不请求 `People` —— 演职员数组在数万条的库上是响应体积与解析耗时的主要来源，
     * 会直接抵消 spec R5 的分页/增量优化；演职员等重字段留到 [getItemDetail]。
     */
    suspend fun getItems(
        libraryId: String?,
        startIndex: Int,
        limit: Int,
        types: List<String>,
        searchTerm: String? = null,
        includePeople: Boolean = false,
    ): PagedResult<BaseItemDto> {
        val userId = requireNotNull(config.userId) { "登录后才能取条目" }
        val fields = if (includePeople) "MediaSources,UserData,Genres,People" else "MediaSources,UserData,Genres"
        val extra = buildMap<String, String> {
            put("Recursive", "true")
            put("IncludeItemTypes", types.joinToString(","))
            put("Fields", fields)
            put("StartIndex", startIndex.toString())
            put("Limit", limit.toString())
            libraryId?.let { put("ParentId", it) }
            searchTerm?.let { put("SearchTerm", it) }
        }
        val dto = execute<ItemsResponseDto>(
            Request.Builder().url(url("/Users/$userId/Items", extra)).applyAuth().build()
        )
        return PagedResult(dto.items, dto.totalRecordCount, startIndex)
    }

    suspend fun getItemDetail(itemId: String, userId: String): BaseItemDto =
        execute(Request.Builder().url(url("/Users/$userId/Items/$itemId", mapOf("Fields" to "MediaSources,UserData,Genres,People"))).applyAuth().build())

    suspend fun getPlaybackInfo(itemId: String, userId: String, deviceProfileJson: String): PlaybackInfoResponseDto =
        execute(
            Request.Builder().url(url("/Items/$itemId/PlaybackInfo", mapOf("UserId" to userId)))
                .post(deviceProfileJson.toRequestBody("application/json".toMediaType()))
                .applyAuth().build()
        )

    /** 图片 URL **不带令牌**；鉴权走 [authHeaders]，由 Coil 的请求头注入。 */
    fun imageUrl(itemId: String, kind: String, maxWidth: Int): String =
        url("/Items/$itemId/Images/$kind", mapOf("maxWidth" to maxWidth.toString())).toString()

    /**
     * 直连播放地址。这里**有意**用 `api_key` 查询参数（spec §5.1 的官方形态）：
     * 播放 URL 会交给 ExoPlayer / HLS 播放列表，`api_key` 是最稳的形态；
     * 图片 URL 才改用请求头（见 [imageUrl]），因为图片是 Coil 直接拉、能带 header。
     */
    fun directStreamUrl(itemId: String, mediaSourceId: String, apiKey: String?): String =
        url("/Videos/$itemId/stream", buildMap {
            put("static", "true")
            put("MediaSourceId", mediaSourceId)
            apiKey?.let { put("api_key", it) }
        }).toString()

    private fun Request.Builder.applyAuth(): Request.Builder =
        apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }

    private fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
}
```

> **`userId` 必须在取数前存在**：`userId` 为 null 时会拼出 `/Users/null/Items`，服务端只回 404，排查成本很高。因此 `getViews` / `getItems` / `getItemDetail` / `planPlayback` **全部**用 `requireNotNull(config.userId)` 前置校验，由 Task 13 保证「登录成功后先写 userId 再构造 source」。
>
> **编译提示**：reified 版 `json.decodeFromString<T>(...)` / `json.encodeToString(...)` 在部分 kotlinx.serialization 版本需要显式 import `kotlinx.serialization.decodeFromString` / `kotlinx.serialization.encodeToString`；若编译器报「未解析的引用」就补上这两行 import。

- [ ] **Step 5: 实现 `HttpMediaSource`（共享的 `MediaSource` 骨架）**

`HttpMediaSource.kt`:

```kotlin
package app.myplayer.source.httpapi

import app.myplayer.core.model.*
import kotlin.time.Duration

/**
 * Emby / Jellyfin 共用的 MediaSource 骨架。
 * 子类需要实现的只有 [mapItem] 与 [buildDeviceProfile]；
 * 其余差异（路径前缀、鉴权头形态）通过构造时的 [SourceConfig] 传入，不改本类。
 */
abstract class HttpMediaSource(
    protected val api: HttpMediaApi,
) : MediaSource {

    override val serverId: String get() = api.config.serverId

    abstract fun mapItem(dto: BaseItemDto, libraryId: String?): MediaItemDto
    abstract fun buildDeviceProfile(caps: ClientCapabilities): String

    override suspend fun getLibraries(): List<MediaLibrary> {
        val page = api.getViews()
        return page.items.map {
            MediaLibrary(id = it.id, name = it.name ?: "", itemCount = it.childCount, collectionType = it.collectionType)
        }
    }

    override suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto> {
        val page = api.getItems(query.libraryId, query.startIndex, query.limit, query.includeItemTypes, query.searchTerm)
        return PagedResult(page.items.map { mapItem(it, query.libraryId) }, page.totalCount, page.startIndex)
    }

    override suspend fun getItemDetail(id: String): MediaItemDto {
        val userId = requireNotNull(api.config.userId) { "登录后才能获取详情" }
        return mapItem(api.getItemDetail(id, userId), libraryId = null)
    }

    /** 无海报时直接返回 [ImageSource.None]；有海报时 URL 不带令牌，令牌走 headers（由 Coil 注入）。 */
    override suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource {
        if (kind == ImageKind.POSTER && !item.hasPoster) return ImageSource.None
        val kindName = when (kind) { ImageKind.POSTER -> "Primary"; ImageKind.BACKDROP -> "Backdrop"; ImageKind.THUMB -> "Thumb"; ImageKind.LOGO -> "Logo" }
        return ImageSource.Remote(
            url = api.imageUrl(item.id, kindName, size.px),
            headers = api.authHeaders(),
        )
    }

    /**
     * 严格按 spec §6.5 的三档降级：直放 → 仅重封装/换音轨（isRemuxOnly=true）→ 完整转码。
     *
     * 与原稿的差别：**前两档都不匹配时不再默默回退成直连**——那会把服务端明确声明「不支持直放」的原文件流交给播放器，
     * 结果是播放失败且原因难查。现在按 spec 的第三档走转码；连转码地址都拿不到才抛错，由 spec §7.8 的错误态 UI 呈现。
     */
    override suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan {
        val userId = requireNotNull(api.config.userId) { "登录后才能播放" }
        val info = api.getPlaybackInfo(item.id, userId, buildDeviceProfile(caps))
        val source = info.mediaSources.firstOrNull()
        val mediaSourceId = source?.id ?: item.id
        val token = api.config.token
        val transcodingUrl = source?.transcodingUrl?.takeIf { it.isNotBlank() }?.let(::playbackUrl)

        return when {
            source?.supportsDirectPlay == true ->
                PlaybackPlan.Direct(
                    uri = api.directStreamUrl(item.id, mediaSourceId, token),
                    mediaSourceId = mediaSourceId,
                )
            transcodingUrl != null ->
                PlaybackPlan.Transcode(transcodingUrl, isRemuxOnly = source?.supportsDirectStream == true)
            source?.supportsTranscoding == true ->
                // 服务端说支持转码但没回地址：按 spec §5.1 的固定入口自拼 HLS
                PlaybackPlan.Transcode(
                    playbackUrl("/Videos/${item.id}/master.m3u8?MediaSourceId=$mediaSourceId"),
                    isRemuxOnly = false,
                )
            else -> error("该条目既不能直放也不能转码：${item.id}")
        }
    }

    /**
     * `PlaybackInfo` 回的 `TranscodingUrl` 是**相对路径**（形如 `/videos/x/master.m3u8?...`），
     * 必须补上 `baseUrl + prefix` 才能交给 ExoPlayer。原稿把它当绝对 URL 用，真机上必然播不了。
     */
    protected fun playbackUrl(raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val base = "${api.config.baseUrl}${api.config.prefix}".trimEnd('/')
        return if (raw.startsWith("/")) "$base$raw" else "$base/$raw"
    }

    override suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec? = null // M3 实现

    override suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState) = Unit // M2 实现
}
```

- [ ] **Step 6: 运行确认通过**

Run: `./gradlew :source:httpapi:testDebugUnitTest`
Expected: PASS（5 tests）。

- [ ] **Step 7: 提交**

```bash
git add source/httpapi && git commit -m "feat(httpapi): add shared rest skeleton, dtos and media source base"
```

---

## Task 8: `:source:emby` — Emby 适配器

**Files:**
- Create: `source/emby/src/main/kotlin/app/myplayer/source/emby/EmbySource.kt`
- Create: `source/emby/src/main/kotlin/app/myplayer/source/emby/EmbyMapper.kt`
- Create: `source/emby/src/main/kotlin/app/myplayer/source/emby/DeviceProfile.kt`
- Test: `source/emby/src/test/kotlin/app/myplayer/source/emby/EmbyMapperTest.kt`
- Test: `source/emby/src/test/kotlin/app/myplayer/source/emby/EmbySourceTest.kt`

- [ ] **Step 1: 写失败测试（映射）**

```kotlin
package app.myplayer.source.emby

import app.myplayer.core.model.SourceType
import app.myplayer.source.httpapi.BaseItemDto
import app.myplayer.source.httpapi.MediaSourceInfoDto
import app.myplayer.source.httpapi.UserDataDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmbyMapperTest {
    private val mapper = EmbyMapper(serverId = "srv-emby")

    @Test fun `maps core fields`() {
        val dto = BaseItemDto(
            id = "i1", name = "沙丘", runTimeTicks = 9_000_000_000L,
            imageTags = mapOf("Primary" to "tag1"),
            mediaSources = listOf(MediaSourceInfoDto(id = "ms1", size = 12_000_000_000L, bitrate = 20_000_000, width = 3840, height = 2160, videoCodec = "hevc", audioCodec = "dts", container = "mkv")),
            userData = UserDataDto(played = false, playbackPositionTicks = 60_000_000L, isFavorite = true, playCount = 2),
        )
        val item = mapper.map(dto, libraryId = "lib1")
        assertThat(item.sourceType).isEqualTo(SourceType.EMBY)
        assertThat(item.serverId).isEqualTo("srv-emby")
        assertThat(item.durationTicks).isEqualTo(Ticks(9_000_000_000L))
        assertThat(item.sizeBytes).isEqualTo(12_000_000_000L)
        assertThat(item.bitrate).isEqualTo(20_000_000)
        assertThat(item.audioCodecs).containsExactly("dts")
        assertThat(item.userData.resumePositionTicks).isEqualTo(Ticks(60_000_000L))
        assertThat(item.userData.isFavorite).isTrue()
        assertThat(item.playCount).isEqualTo(2)
        assertThat(item.libraryId).isEqualTo("lib1")
        assertThat(item.hasPoster).isTrue()
    }

    @Test fun `missing media source leaves size and codecs null or empty`() {
        val item = mapper.map(BaseItemDto(id = "i2", name = "无源"), libraryId = null)
        assertThat(item.sizeBytes).isNull()
        assertThat(item.audioCodecs).isEmpty()
        assertThat(item.hasPoster).isFalse()                 // 无 ImageTags → 无海报
        assertThat(item.durationTicks).isEqualTo(Ticks.ZERO) // 无 RunTimeTicks → 0
    }
}
```

- [ ] **Step 2: 写失败测试（端到端走 MockWebServer）**

```kotlin
package app.myplayer.source.emby

import app.myplayer.core.model.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class EmbySourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: EmbySource

    private val caps = ClientCapabilities(
        supportedVideoCodecs = setOf("hevc", "h264"), supportedAudioCodecs = setOf("aac", "ac3"),
        maxWidth = 3840, maxHeight = 2160, supportsHdr = true, deviceName = "test", deviceId = "d1",
    )

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        source = EmbySource.create(
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            serverId = "srv-emby",
            token = "T",
            userId = "u1",
            deviceId = "d1",
        )
    }
    @After fun teardown() { server.shutdown() }

    @Test fun `getItems returns mapped items`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"i1","Name":"Dune","RunTimeTicks":60000000,"ImageTags":{"Primary":"t"},"MediaSources":[{"Id":"ms1","Size":123}]}],"TotalRecordCount":1}"""))
        val page = source.getItems(ItemQuery(libraryId = "lib1"))
        assertThat(page.items.first().id).isEqualTo("i1")
        assertThat(page.items.first().sizeBytes).isEqualTo(123L)
        assertThat(page.items.first().sourceType).isEqualTo(SourceType.EMBY)
        assertThat(page.items.first().hasPoster).isTrue()
    }

    @Test fun `planPlayback prefers direct play`() = runTest {
        server.enqueue(MockResponse().setBody("""{"MediaSources":[{"Id":"ms1","SupportsDirectPlay":true}]}"""))
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune")
        val plan = source.planPlayback(item, caps)
        assertThat(plan).isInstanceOf(PlaybackPlan.Direct::class.java)
        assertThat((plan as PlaybackPlan.Direct).mediaSourceId).isEqualTo("ms1")
    }

    /** 服务端给的 TranscodingUrl 是相对路径，最终必须变成能被 ExoPlayer 直接使用（或与其 baseUrl 拼接）的地址。 */
    @Test fun `planPlayback 拼接相对转码地址为绝对 URL`() = runTest {
        server.enqueue(MockResponse().setBody("""{"MediaSources":[{"Id":"ms1","SupportsDirectPlay":false,"SupportsDirectStream":false,"SupportsTranscoding":true,"TranscodingUrl":"/videos/i1/master.m3u8"}]}"""))
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune")
        val plan = source.planPlayback(item, caps) as PlaybackPlan.Transcode
        assertThat(plan.hlsUrl).isEqualTo("${server.url("/").toString().trimEnd('/')}/videos/i1/master.m3u8")
        assertThat(plan.isRemuxOnly).isFalse()
    }

    @Test fun `planPlayback 标记纯重封装`() = runTest {
        server.enqueue(MockResponse().setBody("""{"MediaSources":[{"Id":"ms1","SupportsDirectPlay":false,"SupportsDirectStream":true,"SupportsTranscoding":true,"TranscodingUrl":"/videos/i1/master.m3u8?a=1"}]}"""))
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune")
        val plan = source.planPlayback(item, caps) as PlaybackPlan.Transcode
        assertThat(plan.isRemuxOnly).isTrue()
    }

    /** 支持转码但没回 TranscodingUrl：按 spec §5.1 的固定入口自拼 HLS，而不是退回直连。 */
    @Test fun `planPlayback 缺转码地址时自拼 HLS 入口`() = runTest {
        server.enqueue(MockResponse().setBody("""{"MediaSources":[{"Id":"ms1","SupportsDirectPlay":false,"SupportsTranscoding":true}]}"""))
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune")
        val plan = source.planPlayback(item, caps) as PlaybackPlan.Transcode
        assertThat(plan.hlsUrl).contains("/Videos/i1/master.m3u8")
        assertThat(plan.isRemuxOnly).isFalse()
    }

    /** 令牌走请求头，不进 URL（否则会出现在 Coil 的磁盘缓存键与日志里）。 */
    @Test fun `image 返回带鉴权头的 remote url`() = runTest {
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune", hasPoster = true)
        val img = source.image(item, ImageKind.POSTER, ImageSize.MEDIUM) as ImageSource.Remote
        assertThat(img.url).contains("/Items/i1/Images/Primary")
        assertThat(img.url).doesNotContain("api_key")
        assertThat(img.headers["X-Emby-Token"]).isEqualTo("T")
    }

    @Test fun `无海报时返回 None 而不是 404 的 URL`() = runTest {
        val item = MediaItemDto(id = "i1", serverId = "srv-emby", sourceType = SourceType.EMBY, title = "Dune", hasPoster = false)
        assertThat(source.image(item, ImageKind.POSTER, ImageSize.MEDIUM)).isEqualTo(ImageSource.None)
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :source:emby:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: EmbySource`。

- [ ] **Step 4: 实现 `EmbyMapper`**

```kotlin
package app.myplayer.source.emby

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.UserData
import app.myplayer.source.httpapi.BaseItemDto

class EmbyMapper(private val serverId: String) {
    fun map(dto: BaseItemDto, libraryId: String?): MediaItemDto {
        val src = dto.mediaSources?.firstOrNull()
        return MediaItemDto(
            id = dto.id, serverId = serverId, sourceType = SourceType.EMBY,
            title = dto.name ?: "", seriesName = dto.seriesName,
            seasonNumber = dto.parentIndexNumber, episodeNumber = dto.indexNumber,
            durationTicks = Ticks(dto.runTimeTicks ?: 0L),        // Long(tick) → Ticks
            sizeBytes = src?.size, bitrate = src?.bitrate, width = src?.width, height = src?.height,
            videoCodec = src?.videoCodec,
            audioCodecs = listOfNotNull(src?.audioCodec), container = src?.container,
            communityRating = dto.communityRating, officialRating = dto.officialRating,
            playCount = dto.userData?.playCount ?: 0,
            userData = UserData(
                isPlayed = dto.userData?.played ?: false,
                resumePositionTicks = Ticks(dto.userData?.playbackPositionTicks ?: 0L),
                isFavorite = dto.userData?.isFavorite ?: false,
            ),
            genres = dto.genres ?: emptyList(),
            cast = dto.people?.filter { it.type == "Actor" }?.mapNotNull { it.name } ?: emptyList(),
            libraryId = libraryId,
            hasPoster = dto.imageTags?.containsKey("Primary") == true,
        )
    }
}
```

- [ ] **Step 5: 实现 `DeviceProfile`**

```kotlin
package app.myplayer.source.emby

import app.myplayer.core.model.ClientCapabilities

object DeviceProfile {
    /** Emby 的 PlaybackInfo 需要 DeviceProfile，声明客户端能直放的能力。 */
    fun build(caps: ClientCapabilities): String {
        val video = caps.supportedVideoCodecs.joinToString(",")
        val audio = caps.supportedAudioCodecs.joinToString(",")
        return """
        {"DeviceProfile":{
          "MaxStreamingBitrate":120000000,
          "MaxStaticBitrate":120000000,
          "DirectPlayProfiles":[{"Container":"mp4,mkv,mov,webm","Type":"Video","VideoCodec":"$video","AudioCodec":"$audio"}],
          "TranscodingProfiles":[{"Container":"ts","Type":"Video","VideoCodec":"h264","AudioCodec":"aac","Protocol":"hls"}],
          "CodecProfiles":[],
          "SubtitleProfiles":[{"Format":"srt","Method":"External"},{"Format":"ass","Method":"External"}]
        }}""".trimIndent()
    }
}
```

- [ ] **Step 6: 实现 `EmbySource`**

```kotlin
package app.myplayer.source.emby

import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SourceType
import app.myplayer.source.httpapi.AuthHeaderStyle
import app.myplayer.source.httpapi.BaseItemDto
import app.myplayer.source.httpapi.HttpMediaApi
import app.myplayer.source.httpapi.HttpMediaSource
import app.myplayer.source.httpapi.SourceConfig
import okhttp3.OkHttpClient

class EmbySource(
    api: HttpMediaApi,
    private val mapper: EmbyMapper,
) : HttpMediaSource(api) {

    override val type: SourceType = SourceType.EMBY

    override fun mapItem(dto: BaseItemDto, libraryId: String?): MediaItemDto = mapper.map(dto, libraryId)

    override fun buildDeviceProfile(caps: ClientCapabilities): String = DeviceProfile.build(caps)

    companion object {
        fun create(
            client: OkHttpClient,
            baseUrl: String,
            serverId: String,
            token: String?,
            userId: String?,
            deviceId: String,
        ): EmbySource = EmbySource(
            api = HttpMediaApi(
                client = client,
                config = SourceConfig(
                    serverId = serverId, baseUrl = baseUrl, prefix = "", token = token,
                    userId = userId, deviceId = deviceId, authHeaderStyle = AuthHeaderStyle.X_EMBY_TOKEN,
                ),
            ),
            mapper = EmbyMapper(serverId),
        )
    }
}
```

- [ ] **Step 7: 运行确认通过**

Run: `./gradlew :source:emby:testDebugUnitTest`
Expected: PASS（9 tests = EmbyMapper 2 + EmbySource 7）。

- [ ] **Step 8: 提交**

```bash
git add source/emby && git commit -m "feat(emby): add emby adapter, mapper and device profile"
```

---

## Task 9: `:core:designsystem` — 主题与色板 token

**Files:**
- Create: `core/designsystem/src/main/kotlin/app/myplayer/core/designsystem/ColorTokens.kt`
- Create: `core/designsystem/src/main/kotlin/app/myplayer/core/designsystem/MyPlayerTheme.kt`
- Test: `core/designsystem/src/test/kotlin/app/myplayer/core/designsystem/ColorTokensTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package app.myplayer.core.designsystem

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColorTokensTest {
    @Test fun `dark scheme uses dark background`() {
        val scheme = colorSchemeFor(isDark = true)
        assertThat(scheme.background).isEqualTo(Color(0xFF0E0E10))
    }
    @Test fun `light scheme uses white background`() {
        assertThat(colorSchemeFor(isDark = false).background).isEqualTo(Color(0xFFFFFFFF))
    }
    @Test fun `brand primary differs between themes`() {
        assertThat(colorSchemeFor(false).primary).isEqualTo(Color(0xFF0FDC78))
        assertThat(colorSchemeFor(true).primary).isEqualTo(Color(0xFF32F08C))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:designsystem:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: colorSchemeFor`。

- [ ] **Step 3: 实现**

`ColorTokens.kt`:

```kotlin
package app.myplayer.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val BrandLight = Color(0xFF0FDC78)
private val BrandDark = Color(0xFF32F08C)
private val OnBrand = Color(0xFF004D26)

val Danger = Color(0xFFE8463A)
val Warning = Color(0xFFEFAA17)
val Success = Color(0xFF1DC981)

fun colorSchemeFor(isDark: Boolean): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = BrandDark, onPrimary = OnBrand, primaryContainer = Color(0xFF004D26),
        background = Color(0xFF0E0E10), surface = Color(0xFF171717), surfaceVariant = Color(0xFF262626),
        onSurface = Color(0xFFE5E5E5), onSurfaceVariant = Color(0xFFA1A1AA), outline = Color(0xFF2E2E2E),
        error = Danger,
    )
} else {
    lightColorScheme(
        primary = BrandLight, onPrimary = OnBrand, primaryContainer = Color(0xFFE5FFF2),
        background = Color(0xFFFFFFFF), surface = Color(0xFFF7F7F8), surfaceVariant = Color(0xFFEFEFF2),
        onSurface = Color(0xFF171717), onSurfaceVariant = Color(0xFF52525B), outline = Color(0xFFE4E4E7),
        error = Danger,
    )
}

object Spacing {
    val xs = 4
    val sm = 8
    val md = 12
    val lg = 16
    val xl = 24
}

object Radius {
    val control = 8
    val card = 12
    val pill = 999
}
```

`MyPlayerTheme.kt`:

```kotlin
package app.myplayer.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun MyPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = colorSchemeFor(darkTheme), content = content)
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :core:designsystem:testDebugUnitTest`
Expected: PASS（3 tests）。

- [ ] **Step 5: 提交**

```bash
git add core/designsystem && git commit -m "feat(designsystem): add material3 theme tokens"
```

---

## Task 10: `:feature:lock` — 九宫格图案锁页面与门卫

**Files:**
- Create: `feature/lock/src/main/kotlin/app/myplayer/feature/lock/PatternHitTest.kt`
- Create: `feature/lock/src/main/kotlin/app/myplayer/feature/lock/LockViewModel.kt`
- Create: `feature/lock/src/main/kotlin/app/myplayer/feature/lock/PatternLockScreen.kt`
- Test: `feature/lock/src/test/kotlin/app/myplayer/feature/lock/PatternHitTestTest.kt`
- Test: `feature/lock/src/test/kotlin/app/myplayer/feature/lock/LockViewModelTest.kt`

- [ ] **Step 1: 写失败测试（几何命中）**

```kotlin
package app.myplayer.feature.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PatternHitTestTest {
    private val hit = PatternHitTest(canvasSize = 300f, hitRadius = 24f)

    @Test fun `point index follows row major order`() {
        assertThat(hit.indexAt(60f, 60f)).isEqualTo(0)   // 左上
        assertThat(hit.indexAt(150f, 150f)).isEqualTo(4)  // 中心
        assertThat(hit.indexAt(240f, 240f)).isEqualTo(8)  // 右下
    }

    @Test fun `outside hit radius returns null`() {
        assertThat(hit.indexAt(10f, 10f)).isNull()
    }

    @Test fun `point coordinates are evenly spaced`() {
        assertThat(hit.pointCenter(0)).isEqualTo(60f to 60f)
        assertThat(hit.pointCenter(8)).isEqualTo(240f to 240f)
    }
}
```

- [ ] **Step 2: 写失败测试（状态机）**

```kotlin
package app.myplayer.feature.lock

import app.myplayer.core.security.InMemoryCredentialStorage
import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.core.security.SecretHasher
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LockViewModelTest {
    private fun vm(): LockViewModel {
        val store = PatternCredentialStore(SecretHasher(iterations = 1_000), InMemoryCredentialStorage())
        store.savePattern(listOf(0, 1, 2, 4), backupPin = "1234")
        return LockViewModel(store)
    }

    @Test fun `drawing then correct pattern reaches unlocked`() = runTest {
        val vm = vm()
        vm.onPointTouched(0); vm.onPointTouched(1); vm.onPointTouched(2); vm.onPointTouched(4)
        vm.onDrawEnd()
        assertThat(vm.state.value.status).isEqualTo(LockStatus.UNLOCKED)
    }

    @Test fun `wrong pattern shows error then resets`() = runTest {
        val vm = vm()
        vm.onPointTouched(8); vm.onPointTouched(6); vm.onPointTouched(3); vm.onPointTouched(0)
        vm.onDrawEnd()
        assertThat(vm.state.value.status).isEqualTo(LockStatus.ERROR)
    }

    @Test fun `fewer than four points does not verify`() = runTest {
        val vm = vm()
        vm.onPointTouched(0); vm.onPointTouched(1)
        vm.onDrawEnd()
        assertThat(vm.state.value.status).isEqualTo(LockStatus.IDLE)
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :feature:lock:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: PatternHitTest`。

- [ ] **Step 4: 实现几何与状态机**

`PatternHitTest.kt`:

```kotlin
package app.myplayer.feature.lock

/** 3x3 九宫格命中判定；坐标以画布左上角为原点。 */
class PatternHitTest(private val canvasSize: Float, private val hitRadius: Float) {
    private val margin = canvasSize / 5f   // 60 at 300

    fun pointCenter(index: Int): Pair<Float, Float> {
        val row = index / 3
        val col = index % 3
        val spacing = (canvasSize - margin * 2f) / 2f
        return (margin + col * spacing) to (margin + row * spacing)
    }

    fun indexAt(x: Float, y: Float): Int? {
        for (i in 0 until 9) {
            val (cx, cy) = pointCenter(i)
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= hitRadius * hitRadius) return i
        }
        return null
    }
}
```

`LockViewModel.kt`:

```kotlin
package app.myplayer.feature.lock

import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.core.security.VerifyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LockStatus { IDLE, DRAWING, VERIFYING, ERROR, UNLOCKED, COOLDOWN }

data class LockUiState(
    val status: LockStatus = LockStatus.IDLE,
    val selected: List<Int> = emptyList(),
    val message: String? = null,
    val cooldownMillis: Long = 0L,
)

class LockViewModel(private val credentials: PatternCredentialStore) {
    private val _state = MutableStateFlow(LockUiState())
    val state: StateFlow<LockUiState> = _state

    fun onPointTouched(index: Int) {
        val current = _state.value
        if (current.status == LockStatus.COOLDOWN || current.status == LockStatus.VERIFYING) return
        if (index in current.selected) return
        _state.value = current.copy(status = LockStatus.DRAWING, selected = current.selected + index, message = null)
    }

    fun onDrawEnd() {
        val selected = _state.value.selected
        if (selected.size < MIN_POINTS) {
            _state.value = LockUiState(status = LockStatus.IDLE)
            return
        }
        _state.value = _state.value.copy(status = LockStatus.VERIFYING)
        when (val result = credentials.verifyPattern(selected)) {
            is VerifyResult.Success -> _state.value = LockUiState(status = LockStatus.UNLOCKED)
            is VerifyResult.WrongPattern -> _state.value = LockUiState(
                status = LockStatus.ERROR, message = "图案错误，还可尝试 ${result.attemptsLeft} 次",
            )
            is VerifyResult.Cooldown -> _state.value = LockUiState(
                status = LockStatus.COOLDOWN, cooldownMillis = result.remainingMillis,
                message = "尝试过多，请稍后重试",
            )
            is VerifyResult.WrongPin -> _state.value = LockUiState(status = LockStatus.ERROR, message = "PIN 错误")
            is VerifyResult.NotConfigured -> _state.value = LockUiState(status = LockStatus.UNLOCKED)
        }
    }

    fun onErrorAnimationFinished() {
        if (_state.value.status == LockStatus.ERROR) _state.value = LockUiState(status = LockStatus.IDLE)
    }

    companion object { const val MIN_POINTS = 4 }
}
```

- [ ] **Step 5: 实现 `PatternLockScreen`（Compose 画布）**

```kotlin
package app.myplayer.feature.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun PatternLockScreen(
    state: LockUiState,
    onPointTouched: (Int) -> Unit,
    onDrawEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 命中半径与点半径都按 dp → px 换算（spec §15：点直径 12dp、命中半径 24dp）。
    // 原稿把 12f 直接当半径用（像素），在高密度屏上点会明显偏小。
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 24.dp.toPx() }
    val dotRadiusPx = with(density) { 6.dp.toPx() }

    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("绘制解锁图案", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))

        var sidePx by remember { mutableStateOf(0f) }
        val hit = remember(sidePx, hitRadiusPx) { PatternHitTest(sidePx, hitRadiusPx) }

        Canvas(
            modifier = Modifier
                .size(300.dp)                 // 正方形：width == height == 边长，命中与绘制同源
                .pointerInput(state.status) {
                    sidePx = size.width.toFloat()
                    // 单个手势循环同时覆盖「轻点选中一个点」与「按住拖动连线」。
                    // 不用 detectDragGestures：它要等超过 touch slop 才回调，轻点单个点会毫无反应。
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        hit.indexAt(down.position.x, down.position.y)?.let(onPointTouched)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) { onDrawEnd(); break }
                            if (change.positionChanged()) {
                                hit.indexAt(change.position.x, change.position.y)?.let(onPointTouched)
                            }
                        }
                    }
                }
        ) {
            val errorColor = MaterialTheme.colorScheme.error
            val activeColor = MaterialTheme.colorScheme.primary
            val idleColor = MaterialTheme.colorScheme.outline
            for (i in 0 until 9) {
                val (cx, cy) = hit.pointCenter(i)
                drawCircle(
                    color = if (i in state.selected) activeColor else idleColor,
                    radius = dotRadiusPx,
                    center = Offset(cx, cy),
                )
            }
            state.selected.zipWithNext { a, b ->
                val (ax, ay) = hit.pointCenter(a)
                val (bx, by) = hit.pointCenter(b)
                val lineColor = if (state.status == LockStatus.ERROR) errorColor else activeColor
                drawLine(lineColor, Offset(ax, ay), Offset(bx, by), strokeWidth = 4f, cap = StrokeCap.Round)
            }
        }

        Spacer(Modifier.height(16.dp))
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
```

> `PatternHitTest` 的 `canvasSize` 与 `pointCenter` 都基于**画布边长**，所以画布必须是正方形（`size(300.dp)` 已保证）；命中判定与绘制共用同一个 `hit` 实例，不再出现「拖动用 `size.width`、绘制用 `size.minDimension`」两套口径。

- [ ] **Step 6: 运行确认通过**

Run: `./gradlew :feature:lock:testDebugUnitTest`
Expected: PASS（6 tests）。

- [ ] **Step 7: 提交**

```bash
git add feature/lock && git commit -m "feat(lock): add pattern lock geometry, viewmodel and compose screen"
```

---

## Task 11: `:feature:library` — 库与海报网格

**Files:**
- Create: `feature/library/src/main/kotlin/app/myplayer/feature/library/LibraryUiState.kt`
- Create: `feature/library/src/main/kotlin/app/myplayer/feature/library/LibraryViewModel.kt`
- Create: `feature/library/src/main/kotlin/app/myplayer/feature/library/LibraryScreen.kt`
- Test: `feature/library/src/test/kotlin/app/myplayer/feature/library/LibraryViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package app.myplayer.feature.library

import app.myplayer.core.model.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class FakeRepo(
    private val libs: List<MediaLibrary>,
    private val items: List<MediaItemDto>,
) : LibraryRepository {
    override fun observeLibraries(serverId: String) = MutableStateFlow(libs)
    override fun observeItems(serverId: String, libraryId: String) = MutableStateFlow(items.filter { it.libraryId == libraryId })
    override suspend fun syncLibraries(source: MediaSource) = Unit
    override suspend fun syncItems(source: MediaSource, libraryId: String) = Unit
}

class LibraryViewModelTest {
    @Test fun `loads libraries and selects first`() = runTest {
        val vm = LibraryViewModel(FakeRepo(
            libs = listOf(MediaLibrary("lib1", "电影"), MediaLibrary("lib2", "剧集")),
            items = listOf(MediaItemDto("i1", "srv", SourceType.EMBY, "Dune", libraryId = "lib1")),
        ), serverId = "srv")
        val state = vm.state.first { it.libraries.isNotEmpty() }
        assertThat(state.selectedLibraryId).isEqualTo("lib1")
        assertThat(state.items.map { it.title }).containsExactly("Dune")
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :feature:library:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: LibraryViewModel`。

- [ ] **Step 3: 实现**

`LibraryUiState.kt`:

```kotlin
package app.myplayer.feature.library

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaLibrary

data class LibraryUiState(
    val libraries: List<MediaLibrary> = emptyList(),
    val selectedLibraryId: String? = null,
    val items: List<MediaItemDto> = emptyList(),
    val loading: Boolean = true,
)
```

`LibraryViewModel.kt`:

```kotlin
package app.myplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.model.LibraryRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repository: LibraryRepository,
    private val serverId: String,
) : ViewModel() {
    private val selectedLibrary = MutableStateFlow<String?>(null)

    val state: StateFlow<LibraryUiState> = combine(
        repository.observeLibraries(serverId),
        selectedLibrary,
    ) { libs, selected ->
        val chosen = selected ?: libs.firstOrNull()?.id
        libs to chosen
    }.flatMapLatest { (libs, chosen) ->
        if (chosen == null) {
            flowOf(LibraryUiState(libraries = libs, loading = false))
        } else {
            repository.observeItems(serverId, chosen).map { items ->
                LibraryUiState(libraries = libs, selectedLibraryId = chosen, items = items, loading = false)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun selectLibrary(id: String) { selectedLibrary.value = id }

    fun refresh(source: app.myplayer.core.model.MediaSource) = viewModelScope.launch {
        repository.syncLibraries(source)
        repository.observeLibraries(serverId).first().forEach { repository.syncItems(source, it.id) }
    }
}
```

`LibraryScreen.kt`:

```kotlin
package app.myplayer.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    loadImage: suspend (MediaItemDto) -> ImageSource,
    onSelectLibrary: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = 0, edgePadding = 16.dp) {
            state.libraries.forEach { lib ->
                Tab(
                    selected = lib.id == state.selectedLibraryId,
                    onClick = { onSelectLibrary(lib.id) },
                    text = { Text(lib.name) },
                )
            }
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    PosterCard(item = item, loadImage = loadImage)
                }
            }
        }
    }
}

/**
 * `MediaSource.image()` 是 **suspend** 的（本地源以后还要抽帧），而 Coil 需要一个已就绪的 model。
 * 原稿把 `posterFor` 定义成同步回调 `(MediaItemDto) -> ImageSource`，与接口对不上、无法实现——
 * 这里改为「suspend 加载 + per-item LaunchedEffect 落成 UI 状态」：
 * 先渲染占位，拿到结果再切图；key 用 item.id，列表复用不会串图。
 */
@Composable
private fun rememberImageSource(itemId: String, load: suspend () -> ImageSource): ImageSource {
    var source by remember(itemId) { mutableStateOf<ImageSource>(ImageSource.None) }
    LaunchedEffect(itemId) { source = load() }
    return source
}

@Composable
private fun PosterCard(
    item: MediaItemDto,
    loadImage: suspend (MediaItemDto) -> ImageSource,
    modifier: Modifier = Modifier,
) {
    val image = rememberImageSource(item.id) { loadImage(item) }
    val context = LocalContext.current
    val request: ImageRequest? = when (image) {
        is ImageSource.Remote -> ImageRequest.Builder(context)
            .data(image.url)
            .apply { image.headers.forEach { (k, v) -> addHeader(k, v) } }   // 令牌走请求头，不进 URL
            .crossfade(true)
            .build()
        is ImageSource.Local -> ImageRequest.Builder(context).data(image.uri).build()
        ImageSource.None -> null
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            modifier = Modifier.aspectRatio(2f / 3f).fillMaxWidth(),
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize())   // 无海报占位（spec §3.2），不发必然 404 的请求
            }
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}
```

> - `Modifier.aspectRatio` 需要 `import androidx.compose.foundation.layout.aspectRatio`；`addHeader` 是 Coil 2.x 的 `ImageRequest.Builder` API。
> - `items(..., key = { it.id })` 是**必须的**：服务端与本地源的条目可能重名，无 key 时 LazyGrid 复用会串图；加了 key 后 `rememberImageSource` 的 `remember(itemId)` 才有意义。
> - 详情页（P5）会复用同一个 `rememberImageSource`，届时把它提到 `:core:designsystem` 作为公共组件。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :feature:library:testDebugUnitTest`
Expected: PASS（1 test）。

- [ ] **Step 5: 提交**

```bash
git add feature/library && git commit -m "feat(library): add library viewmodel and poster grid screen"
```

---

## Task 12: `:app` — 装配、导航与应用锁门卫

**Files:**
- Create: `app/src/main/kotlin/app/myplayer/MyPlayerApp.kt`
- Create: `app/src/main/kotlin/app/myplayer/di/AppModule.kt`
- Create: `app/src/main/kotlin/app/myplayer/di/FileCredentialStorage.kt`
- Create: `app/src/main/kotlin/app/myplayer/AppLockGate.kt`
- Create: `app/src/main/kotlin/app/myplayer/MainActivity.kt`
- Create: `app/src/main/kotlin/app/myplayer/nav/Routes.kt`
- Create: `app/src/main/kotlin/app/myplayer/nav/AppNavHost.kt`
- Modify: `app/src/main/AndroidManifest.xml`（加 `android:name=".MyPlayerApp"`、网络与明文权限）
- Test: `app/src/test/kotlin/app/myplayer/AppLockGateTest.kt`

- [ ] **Step 1: 写失败测试（门卫逻辑）**

```kotlin
package app.myplayer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLockGateTest {
    @Test fun `locked on cold start when lock enabled`() {
        assertThat(shouldLock(lockEnabled = true, lastUnlockAtMillis = null, nowMillis = 1000, graceMillis = 0)).isTrue()
    }
    @Test fun `within grace period stays unlocked`() {
        assertThat(shouldLock(lockEnabled = true, lastUnlockAtMillis = 1000, nowMillis = 1500, graceMillis = 1000)).isFalse()
    }
    @Test fun `past grace period locks`() {
        assertThat(shouldLock(lockEnabled = true, lastUnlockAtMillis = 1000, nowMillis = 2500, graceMillis = 1000)).isTrue()
    }
    @Test fun `never locks when disabled`() {
        assertThat(shouldLock(lockEnabled = false, lastUnlockAtMillis = null, nowMillis = 0, graceMillis = 0)).isFalse()
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: shouldLock`。

- [ ] **Step 3: 实现门卫函数与应用类**

`app/src/main/kotlin/app/myplayer/AppLockGate.kt`:

```kotlin
package app.myplayer

fun shouldLock(lockEnabled: Boolean, lastUnlockAtMillis: Long?, nowMillis: Long, graceMillis: Long): Boolean {
    if (!lockEnabled) return false
    if (lastUnlockAtMillis == null) return true
    return nowMillis - lastUnlockAtMillis > graceMillis
}
```

`MyPlayerApp.kt`:

```kotlin
package app.myplayer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyPlayerApp : Application()
```

- [ ] **Step 4: 实现 DI 模块**

`app/src/main/kotlin/app/myplayer/di/AppModule.kt`:

```kotlin
package app.myplayer.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import app.myplayer.core.database.MediaDatabase
import app.myplayer.core.database.RoomLibraryRepository
import app.myplayer.core.datastore.*
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.network.HttpClientFactory
import app.myplayer.core.network.NetworkConfig
import app.myplayer.core.security.KeystoreSecretCipher
import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.core.security.SecretCipher
import app.myplayer.core.security.SecretHasher
import app.myplayer.core.security.CredentialStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun mediaDatabase(@ApplicationContext ctx: Context): MediaDatabase =
        Room.databaseBuilder(ctx, MediaDatabase::class.java, "myplayer.db").build()

    @Provides @Singleton
    fun libraryRepository(db: MediaDatabase): LibraryRepository = RoomLibraryRepository(db)

    @Provides @Singleton
    fun settingsStore(@ApplicationContext ctx: Context): SettingsStore = DataStoreSettingsStore(ctx.dataStore)

    /** 应用私有目录：`servers.bin` 与 `token_*.bin` 都放这里，明文不落盘。 */
    @Provides @Singleton
    fun credentialDir(@ApplicationContext ctx: Context): File =
        File(ctx.filesDir, "secrets").apply { mkdirs() }

    @Provides @Singleton
    fun secretCipher(): SecretCipher = KeystoreSecretCipher()

    @Provides @Singleton
    fun serverConfigStore(dir: File, cipher: SecretCipher): ServerConfigStore =
        FileServerConfigStore(File(dir, "servers.bin"), cipher)

    @Provides @Singleton
    fun tokenStore(dir: File, cipher: SecretCipher): TokenStore {
        val storage = DirByteFileStorage(dir)
        return EncryptedTokenStore(cipher, storage::read, storage::write, storage::delete)
    }

    @Provides @Singleton
    fun credentialStorage(dir: File, cipher: SecretCipher): CredentialStorage =
        FileCredentialStorage(File(dir, "app_lock.bin"), cipher)

    @Provides @Singleton
    fun patternCredentialStore(storage: CredentialStorage): PatternCredentialStore =
        PatternCredentialStore(SecretHasher(), storage)

    @Provides @Singleton
    fun okHttpClient(): OkHttpClient = HttpClientFactory.create(NetworkConfig())
}
```

> 补充文件 `app/src/main/kotlin/app/myplayer/di/FileCredentialStorage.kt`：

```kotlin
package app.myplayer.di

import app.myplayer.core.security.CredentialRecord
import app.myplayer.core.security.CredentialStorage
import app.myplayer.core.security.SecretCipher
import java.io.File

class FileCredentialStorage(private val file: File, private val cipher: SecretCipher) : CredentialStorage {

    override fun read(): CredentialRecord? =
        runCatching { file.takeIf { it.exists() }?.let { decode(String(cipher.decrypt(it.readBytes()))) } }.getOrNull()

    override fun write(record: CredentialRecord) {
        file.parentFile?.mkdirs()
        file.writeBytes(cipher.encrypt(encode(record).toByteArray()))
    }

    override fun clear() { if (file.exists()) file.delete() }

    private fun encode(r: CredentialRecord) = listOf(
        r.patternHash, r.patternSalt,
        r.backupPinHash ?: "", r.backupPinSalt ?: "",
        r.failedAttempts.toString(), r.lastFailedAtMillis.toString(),
    ).joinToString("|")

    private fun decode(s: String): CredentialRecord {
        val p = s.split("|")
        return CredentialRecord(
            patternHash = p[0], patternSalt = p[1],
            backupPinHash = p[2].ifEmpty { null }, backupPinSalt = p[3].ifEmpty { null },
            failedAttempts = p[4].toInt(), lastFailedAtMillis = p[5].toLong(),
        )
    }
}
```

- [ ] **Step 5: 实现 Activity、导航与门卫**

`MainActivity.kt`:

```kotlin
package app.myplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.myplayer.core.datastore.SettingsStore
import app.myplayer.core.designsystem.MyPlayerTheme
import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.feature.lock.LockStatus
import app.myplayer.feature.lock.LockViewModel
import app.myplayer.feature.lock.PatternLockScreen
import app.myplayer.nav.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var credentials: PatternCredentialStore
    @Inject lateinit var libraryRepository: LibraryRepository
    @Inject lateinit var serverConfigStore: ServerConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyPlayerTheme {
                val settings by settingsStore.settings.collectAsStateWithLifecycle(initialValue = null)
                val servers by serverConfigStore.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                val lockVm = remember { LockViewModel(credentials) }
                val lockState by lockVm.state.collectAsStateWithLifecycle()
                var unlocked by remember { mutableStateOf(false) }
                var lastUnlockAt by remember { mutableStateOf<Long?>(null) }

                // 回前台判定（spec §8.1）：仅在应用锁开启且超过宽限期时重新上锁
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner, settings) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START && settings?.appLockEnabled == true) {
                            unlocked = !shouldLock(true, lastUnlockAt, System.currentTimeMillis(), settings.unlockGraceMillis)
                        }
                    }
                    owner.lifecycle.addObserver(observer)
                    onDispose { owner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(lockState.status) {
                    if (lockState.status == LockStatus.UNLOCKED) {
                        unlocked = true
                        lastUnlockAt = System.currentTimeMillis()
                    }
                }

                // 设置尚未加载完（settings == null）时按「锁着」处理：宁可多解一次，也不要漏锁一帧
                val needsLock = settings?.appLockEnabled != false && !unlocked
                if (needsLock) {
                    PatternLockScreen(lockState, lockVm::onPointTouched, lockVm::onDrawEnd)
                } else {
                    AppNavHost(
                        repository = libraryRepository,
                        serverId = servers.firstOrNull()?.id,
                        source = null,                       // ← Task 14 换成 SourceFactory 造出的真实 source
                        onAddServer = { /* ← Task 13/14 接上 Routes.ADD_SERVER */ },
                    )
                }
            }
        }
    }
}
```

> **本步结束时的已知中间态**（Task 13/14 会补齐，DoD 只在全部 Task 完成后验收）：
> 1. 库页能进入、能读 Room，但因为 `source = null`，此时没有任何数据与海报；
> 2. 引导页的「添加服务器」按钮存在但点按无反应（`onAddServer` 是空实现）。
>
> 这两项**不是**占位文本或假 UI，只是尚未接线；Task 14 完成即全部闭合。

`app/src/main/kotlin/app/myplayer/nav/Routes.kt`:

```kotlin
package app.myplayer.nav

object Routes {
    const val WELCOME = "welcome"        // 还没有任何服务器时的引导页
    const val ADD_SERVER = "addServer"   // Task 13
    const val LOGIN = "login"            // Task 13
    const val LIBRARY = "library"
}
```

`app/src/main/kotlin/app/myplayer/nav/AppNavHost.kt`:

```kotlin
package app.myplayer.nav

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.myplayer.core.model.ImageKind
import app.myplayer.core.model.ImageSize
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MediaSource
import app.myplayer.feature.library.LibraryScreen
import app.myplayer.feature.library.LibraryViewModel

/**
 * 只做「路由 + 屏幕装配」，不含业务逻辑。
 *
 * - [serverId] 为 null → 落到引导页；非 null → 直接进库页。
 * - [source] 由 Task 14 的 `SourceFactory` 提供；为 null 时库页只读 Room（图/数据都还是空的），不崩。
 * - [onAddServer] 由调用方注入：Task 13 完成后指向 `Routes.ADD_SERVER`，Task 12 阶段点按无反应（已知中间态）。
 */
@Composable
fun AppNavHost(
    repository: LibraryRepository,
    serverId: String?,
    source: MediaSource?,
    onAddServer: () -> Unit,
) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = if (serverId == null) Routes.WELCOME else Routes.LIBRARY) {

        composable(Routes.WELCOME) { NoServerScreen(onAddServer) }

        composable(Routes.LIBRARY) {
            val vm: LibraryViewModel = viewModel(
                key = "library-$serverId",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        LibraryViewModel(repository, serverId!!) as T
                },
            )
            val state by vm.state.collectAsStateWithLifecycle()

            // 首次进入即同步一次（分页落 Room，UI 只读 Room）；Task 14 会把 source 换成真实实现
            LaunchedEffect(serverId) { source?.let { vm.refresh(it) } }

            LibraryScreen(
                state = state,
                loadImage = { item ->
                    source?.image(item, ImageKind.POSTER, ImageSize.MEDIUM) ?: ImageSource.None
                },
                onSelectLibrary = vm::selectLibrary,
            )
        }
    }
}

@Composable
private fun NoServerScreen(onAddServer: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有添加服务器", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "添加一台 Emby 或飞牛影视服务器后即可开始浏览。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddServer, modifier = Modifier.height(48.dp)) { Text("添加服务器") }
    }
}
```

- [ ] **Step 6: 更新 Manifest**

`app/src/main/AndroidManifest.xml` 完整内容：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <!--
      明文 HTTP：M1 阶段临时放宽，便于直连自建 NAS。
      ⚠️ spec §10 与附 B 要求「默认关闭、显式开启」，因此 P5 必须：
        1) 移除本行 usesCleartextTraffic；
        2) 改为 networkSecurityConfig 白名单；
        3) 由设置项 allowCleartextHttp（默认关）决定是否放行。
    -->
    <application
        android:name=".MyPlayerApp"
        android:label="MyPlayer"
        android:usesCleartextTraffic="true"
        android:theme="@style/Theme.MyPlayer">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

> `configChanges` 里必须有 `smallestScreenSize`（spec §7.2 明确列出），否则某些折叠屏/分屏切换仍会走 Activity 重建，播放器实例会被销毁。
> `targetSdk` 在 `app/build.gradle.kts` 的 `defaultConfig` 里声明（Task 0 的模块模板），不要只写 `compileSdk`。

同时创建 `app/src/main/res/values/themes.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.MyPlayer" parent="android:Theme.Material.NoActionBar" />
</resources>
```

- [ ] **Step 7: 运行确认通过**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `shouldLock` 的 4 个测试 PASS，`BUILD SUCCESSFUL`。

- [ ] **Step 8: 提交**

```bash
git add app && git commit -m "feat(app): wire hilt, navigation, app lock gate"
```

---

## Task 13: `:feature:server` — 添加服务器与登录（补齐 M1 链路的前两环）

> **为什么必须有这个 Task**：原稿从「库页读 Room」起步，但 M1 的 DoD 是「添加 Emby 服务器并登录 → 看到库与海报」。
> 把链路拆开看，**添加服务器 UI、登录换 Token、写入 ServerConfig/Token** 这三环在原稿里没有任何实现任务，
> Task 7 的 `authenticateByName()` 甚至没有调用方。没有本 Task，M1 无法验收。对应 spec §17。

**Files:**
- Create: `feature/server/src/main/kotlin/app/myplayer/feature/server/AddServerViewModel.kt`
- Create: `feature/server/src/main/kotlin/app/myplayer/feature/server/AddServerScreen.kt`
- Create: `feature/server/src/main/kotlin/app/myplayer/feature/server/LoginViewModel.kt`
- Create: `feature/server/src/main/kotlin/app/myplayer/feature/server/LoginScreen.kt`
- Test: `feature/server/src/test/kotlin/app/myplayer/feature/server/AddServerViewModelTest.kt`
- Test: `feature/server/src/test/kotlin/app/myplayer/feature/server/LoginViewModelTest.kt`

> **范围**：本 Task 的选源只有 **Emby 一张卡片**（spec §17：M1 只加 Emby，「本机视频」L2、飞牛登录页形态在 M4 才出现）。
> 因此不存在「选了飞牛却无法登录」的中间态。

- [ ] **Step 1: 写失败测试（地址归一化与探测）**

```kotlin
package app.myplayer.feature.server

import app.myplayer.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AddServerViewModelTest {

    @Test fun `补全协议头并按需补默认端口`() {
        assertThat(normalizeBaseUrl("192.168.31.222", 8005)).isEqualTo("http://192.168.31.222:8005")
        assertThat(normalizeBaseUrl("http://nas:8096/", 8096)).isEqualTo("http://nas:8096")   // 已带端口 → 不重复追加
        assertThat(normalizeBaseUrl("https://nas", 443)).isEqualTo("https://nas")            // 默认端口 → 不追加
    }

    @Test fun `探测成功回显服务器名并识别为飞牛协议`() = runTest {
        val vm = AddServerViewModel(probe = { _, _ ->
            ProbeStatus.Reachable(serverName = "客厅 NAS", productName = "fnOS", version = "1.2.0800")
        })
        vm.onAddressChange("192.168.31.222")
        vm.onPortChange("8005")
        vm.onTest()
        val status = vm.state.value.probeStatus
        assertThat(status).isInstanceOf(ProbeStatus.Reachable::class.java)
        assertThat((status as ProbeStatus.Reachable).serverName).isEqualTo("客厅 NAS")
        assertThat(vm.state.value.detectedSourceType).isEqualTo(SourceType.JELLYFIN)
    }

    @Test fun `探测 404 时提示检查端口与路径前缀`() = runTest {
        val vm = AddServerViewModel(probe = { _, _ -> ProbeStatus.NotFound })
        vm.onAddressChange("10.0.0.9")
        vm.onPortChange("8005")
        vm.onTest()
        assertThat(vm.state.value.error).contains("端口")
    }
}
```

- [ ] **Step 2: 写失败测试（登录与落库）**

```kotlin
package app.myplayer.feature.server

import app.myplayer.core.datastore.InMemoryServerConfigStore
import app.myplayer.core.datastore.InMemoryTokenStore
import app.myplayer.core.model.SourceType
import app.myplayer.core.network.ApiException
import app.myplayer.source.httpapi.AuthResultDto
import app.myplayer.source.httpapi.UserDto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LoginViewModelTest {

    private fun vm(
        login: suspend (String, String) -> AuthResultDto,
        configs: InMemoryServerConfigStore = InMemoryServerConfigStore(),
        tokens: InMemoryTokenStore = InMemoryTokenStore(),
    ) = LoginViewModel(
        serverId = "s1", sourceType = SourceType.EMBY, baseUrl = "http://nas:8096",
        login = login, configStore = configs, tokenStore = tokens,
    )

    @Test fun `登录成功写入配置与令牌且顺序正确`() = runTest {
        val configs = InMemoryServerConfigStore()
        val tokens = InMemoryTokenStore()
        val vm = vm({ _, _ -> AuthResultDto("TK", UserDto("u1", "bob")) }, configs, tokens)
        vm.onUsernameChange("bob"); vm.onPasswordChange("pw"); vm.submit()

        assertThat(vm.state.value.status).isEqualTo(LoginStatus.SUCCESS)
        val saved = configs.observeAll().first().single()
        assertThat(saved.userId).isEqualTo("u1")          // 必须早于构造 MediaSource，否则 requireNotNull(userId) 会炸
        assertThat(saved.baseUrl).isEqualTo("http://nas:8096")
        assertThat(tokens.get("s1")).isEqualTo("TK")
    }

    @Test fun `认证失败提示账号或密码错误`() = runTest {
        val vm = vm({ _, _ -> throw ApiException.Unauthorized() })
        vm.onUsernameChange("bob"); vm.onPasswordChange("bad"); vm.submit()
        assertThat(vm.state.value.status).isEqualTo(LoginStatus.ERROR)
        assertThat(vm.state.value.message).contains("账号或密码")
    }

    @Test fun `404 与网络异常给出不同文案`() = runTest {
        val notFound = vm({ _, _ -> throw ApiException.NotFound() })
        notFound.onUsernameChange("bob"); notFound.onPasswordChange("pw"); notFound.submit()
        assertThat(notFound.state.value.message).contains("404")

        val offline = vm({ _, _ -> throw java.net.ConnectException("refused") })
        offline.onUsernameChange("bob"); offline.onPasswordChange("pw"); offline.submit()
        assertThat(offline.state.value.message).contains("不可达")
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :feature:server:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: AddServerViewModel`。

- [ ] **Step 4: 实现 `AddServerViewModel`**

```kotlin
package app.myplayer.feature.server

import app.myplayer.core.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 探测结果与探测状态合为一个类型，避免 ProbeResult / ProbeStatus 两份模型来回映射。 */
sealed interface ProbeStatus {
    data object Idle : ProbeStatus
    data object Testing : ProbeStatus
    data class Reachable(val serverName: String, val productName: String?, val version: String?) : ProbeStatus
    data object NotFound : ProbeStatus
    data class Unreachable(val reason: String) : ProbeStatus
}

data class AddServerUiState(
    val address: String = "",
    val port: String = "",
    val probeStatus: ProbeStatus = ProbeStatus.Idle,
    val detectedSourceType: SourceType = SourceType.EMBY,
    val error: String? = null,
) {
    val canProceed: Boolean get() = probeStatus is ProbeStatus.Reachable
}

class AddServerViewModel(
    private val probe: suspend (baseUrl: String, port: String) -> ProbeStatus,
    private val defaultPort: Int = 8096,      // M1 只有 Emby；M4 增加飞牛卡片时按所选类型传入 8005
) {
    private val _state = MutableStateFlow(AddServerUiState(port = defaultPort.toString()))
    val state: StateFlow<AddServerUiState> = _state

    fun onAddressChange(v: String) { _state.value = _state.value.copy(address = v, probeStatus = ProbeStatus.Idle, error = null) }
    fun onPortChange(v: String) { _state.value = _state.value.copy(port = v, probeStatus = ProbeStatus.Idle, error = null) }

    suspend fun onTest() {
        val s = _state.value
        val base = normalizeBaseUrl(s.address, s.port.toIntOrNull() ?: defaultPort)
        if (base.isEmpty()) {
            _state.value = s.copy(error = "请填写服务器地址")
            return
        }
        _state.value = s.copy(probeStatus = ProbeStatus.Testing, error = null)
        when (val result = probe(base, s.port)) {
            is ProbeStatus.Reachable -> _state.value = s.copy(
                probeStatus = result,
                detectedSourceType = detectSourceType(result.serverName, result.productName),
            )
            ProbeStatus.NotFound -> _state.value = s.copy(
                probeStatus = result,
                error = "服务器响应 404：端口或路径前缀可能不对，请检查端口后重试",
            )
            is ProbeStatus.Unreachable -> _state.value = s.copy(
                probeStatus = result,
                error = "地址不可达：${result.reason}",
            )
            else -> _state.value = s.copy(probeStatus = result)
        }
    }

    /** spec §5.2 设备识别：ProductName 含 fnOS 或服务器名含「飞牛」→ 走 Jellyfin 协议。 */
    fun detectSourceType(serverName: String?, productName: String?): SourceType =
        if (productName?.contains("fnOS", ignoreCase = true) == true ||
            serverName?.contains("飞牛") == true
        ) SourceType.JELLYFIN else SourceType.EMBY
}

/** spec §17：输入框智能补全 `http://`；已写协议/端口则不重复追加，默认端口也不追加。 */
fun normalizeBaseUrl(hostInput: String, port: Int): String {
    val raw = hostInput.trim().trimEnd('/')
    if (raw.isEmpty()) return ""
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val scheme = withScheme.substringBefore("://")
    val afterScheme = withScheme.substringAfter("://")
    val hasPort = afterScheme.contains(":")
    val defaultForScheme = if (scheme == "https") 443 else 80
    return if (hasPort || port == defaultForScheme) withScheme else "$withScheme:$port"
}
```

- [ ] **Step 5: 实现 `LoginViewModel`**

```kotlin
package app.myplayer.feature.server

import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.TokenStore
import app.myplayer.core.model.SourceType
import app.myplayer.core.network.ApiException
import app.myplayer.source.httpapi.AuthResultDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

enum class LoginStatus { IDLE, SUBMITTING, SUCCESS, ERROR }

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val status: LoginStatus = LoginStatus.IDLE,
    val message: String? = null,
)

class LoginViewModel(
    private val serverId: String,
    private val sourceType: SourceType,
    private val baseUrl: String,
    /** 生产环境传 `httpApi::authenticateByName`；测试传假实现。 */
    private val login: suspend (String, String) -> AuthResultDto,
    private val configStore: ServerConfigStore,
    private val tokenStore: TokenStore,
) {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    fun onUsernameChange(v: String) { _state.value = _state.value.copy(username = v, message = null) }
    fun onPasswordChange(v: String) { _state.value = _state.value.copy(password = v, message = null) }

    suspend fun submit() {
        val s = _state.value
        if (s.username.isBlank() || s.password.isBlank()) {
            _state.value = s.copy(status = LoginStatus.ERROR, message = "请填写账号与密码")
            return
        }
        _state.value = s.copy(status = LoginStatus.SUBMITTING, message = null)
        try {
            val result = login(s.username, s.password)
            // 顺序是有意的：先落盘 userId 与令牌，上层才能安全构造 MediaSource（取数接口 requireNotNull(userId)）
            tokenStore.put(serverId, result.accessToken)
            configStore.upsert(
                ServerConfig(
                    id = serverId,
                    sourceType = sourceType,
                    name = result.user.name ?: baseUrl,
                    baseUrl = baseUrl,
                    userId = result.user.id,
                )
            )
            _state.value = _state.value.copy(status = LoginStatus.SUCCESS)
        } catch (e: ApiException.Unauthorized) {
            _state.value = _state.value.copy(status = LoginStatus.ERROR, message = "账号或密码错误")
        } catch (e: ApiException.NotFound) {
            _state.value = _state.value.copy(status = LoginStatus.ERROR, message = "服务器响应 404：请检查端口与路径前缀")
        } catch (e: IOException) {
            _state.value = _state.value.copy(status = LoginStatus.ERROR, message = "服务器地址不可达，请检查网络与地址")
        } catch (e: Exception) {
            _state.value = _state.value.copy(status = LoginStatus.ERROR, message = "登录失败：${e.message}")
        }
    }
}
```

- [ ] **Step 6: 实现两个页面（Compose）**

`AddServerScreen.kt`（结构按 spec §17：选源卡片 → 地址 + 端口 → 测试连接回显 → 下一步）：

```kotlin
@Composable
fun AddServerScreen(
    state: AddServerUiState,
    onAddressChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTest: () -> Unit,
    onProceed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 选源：M1 只有 Emby 一张卡片（飞牛卡片在 M4 加、本机视频在 L2 加）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Emby", style = MaterialTheme.typography.titleMedium)
                Text("用 Emby 账号密码登录", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = state.address,
            onValueChange = onAddressChange,
            label = { Text("服务器地址") },
            placeholder = { Text("192.168.31.10") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.port,
            onValueChange = onPortChange,
            label = { Text("端口") },
            singleLine = true,
            isError = state.error?.contains("端口") == true,
            supportingText = { state.error?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        when (val s = state.probeStatus) {
            is ProbeStatus.Testing -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is ProbeStatus.Reachable -> Text(
                "已连接：${s.serverName}${s.version?.let { " · $it" } ?: ""}",
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onTest, modifier = Modifier.height(48.dp)) { Text("测试连接") }
            Button(
                onClick = onProceed,
                enabled = state.canProceed,          // 未探测成功不给进下一步（spec §17）
                modifier = Modifier.height(48.dp),
            ) { Text("下一步") }
        }
    }
}
```

`LoginScreen.kt`：账号 + 密码 + 登录按钮 + 内联错误；`status == SUCCESS` 时通过 `LaunchedEffect` 回调 `onLoggedIn()`。
**M1 不显示**「NAS 账号授权 / 扫码登录」两个分段控件（spec §17 明确：不做占位、不做置灰入口）。

- [ ] **Step 7: 运行确认通过**

Run: `./gradlew :feature:server:testDebugUnitTest`
Expected: PASS（6 tests = AddServer 3 + Login 3）。

- [ ] **Step 8: 提交**

```bash
git add feature/server && git commit -m "feat(server): add server discovery and password login flow"
```

---

## Task 14: 源装配与导航接线（闭合 M1 链路）

> 本 Task 把 Task 13 的登录结果接到 Task 11 的库页与 Task 8 的 `EmbySource`，并接上 `AppNavHost` 的
> `addServer` / `login` 两条路由。**完成后 M1 的 DoD 才可能全绿。**

**Files:**
- Create: `feature/server/src/main/kotlin/app/myplayer/feature/server/SourceFactory.kt`
- Test: `feature/server/src/test/kotlin/app/myplayer/feature/server/SourceFactoryTest.kt`
- Modify: `app/src/main/kotlin/app/myplayer/nav/AppNavHost.kt`（补两条路由 + 真实 `onAddServer`）
- Modify: `app/src/main/kotlin/app/myplayer/MainActivity.kt`（由 `SourceFactory` 造 `source`）
- Modify: `app/src/main/kotlin/app/myplayer/di/AppModule.kt`（提供 `SourceFactory` 与 `deviceId`）

- [ ] **Step 1: 写失败测试**

```kotlin
package app.myplayer.feature.server

import app.myplayer.core.datastore.InMemoryServerConfigStore
import app.myplayer.core.datastore.InMemoryTokenStore
import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.model.SourceType
import app.myplayer.source.emby.EmbySource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

class SourceFactoryTest {

    private val factory = SourceFactory(
        client = OkHttpClient(),
        serverConfigStore = InMemoryServerConfigStore(),
        tokenStore = InMemoryTokenStore(),
        deviceId = "d1",
    )

    @Test fun `EMBY 配置可造出 EmbySource 且 serverId 正确`() {
        factory.tokenStore.put("s1", "TK")   // 见下方提示：测试里用 internal 暴露的 store
        val source = factory.create(
            ServerConfig("s1", SourceType.EMBY, "Home", "http://nas:8096", userId = "u1"),
        )
        assertThat(source).isInstanceOf(EmbySource::class.java)
        assertThat(source!!.serverId).isEqualTo("s1")
        assertThat(source.type).isEqualTo(SourceType.EMBY)
    }

    @Test fun `缺少令牌或 userId 时返回 null 而不是半成品 source`() {
        assertThat(factory.create(ServerConfig("s1", SourceType.EMBY, "Home", "http://nas:8096"))).isNull()
        factory.tokenStore.put("s2", "TK")
        assertThat(factory.create(ServerConfig("s2", SourceType.EMBY, "Home", "http://nas:8096"))).isNull()
    }
}
```

> 上面测试需要拿到内部 store，实现里把 `serverConfigStore` / `tokenStore` 声明为 `internal val`（同模块测试可见），不要为了测试改成 public。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :feature:server:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: SourceFactory`。

- [ ] **Step 3: 实现 `SourceFactory`**

```kotlin
package app.myplayer.feature.server

import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.TokenStore
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.SourceType
import app.myplayer.source.emby.EmbySource
import okhttp3.OkHttpClient

/**
 * 把「已保存的服务器配置」变成可用的 [MediaSource]，是 feature 层与 source 层之间唯一的装配点。
 * 放在 `:feature:server` 而不是 `:source:*`：spec §4 要求 `:source:*` 之间互不依赖，
 * 而这里同时知道 Emby 与（将来的）Jellyfin。
 */
class SourceFactory(
    private val client: OkHttpClient,
    internal val serverConfigStore: ServerConfigStore,
    internal val tokenStore: TokenStore,
    private val deviceId: String,
) {
    /**
     * 令牌或 userId 缺失时返回 **null**（= 需要重新登录），而不是造一个会在第一次请求时
     * 抛 `requireNotNull(userId)` 的半成品 source。
     */
    fun create(config: ServerConfig): MediaSource? {
        val token = tokenStore.get(config.id) ?: return null
        val userId = config.userId ?: return null
        return when (config.sourceType) {
            SourceType.EMBY -> EmbySource.create(
                client = client,
                baseUrl = config.baseUrl,
                serverId = config.id,
                token = token,
                userId = userId,
                deviceId = deviceId,
            )
            // JELLYFIN 在 P4（M4）接入；LOCAL 在 L2。M1 不会走到这里。
            else -> null
        }
    }
}
```

- [ ] **Step 4: 在 DI 中提供 `SourceFactory` 与稳定的 `deviceId`**

```kotlin
    /** 稳定的设备标识：Emby 用它区分会话，必须在重装之外保持稳定。 */
    @Provides @Singleton
    fun deviceId(@ApplicationContext ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    @Provides @Singleton
    fun sourceFactory(
        client: OkHttpClient,
        serverConfigStore: ServerConfigStore,
        tokenStore: TokenStore,
        deviceId: String,
    ): SourceFactory = SourceFactory(client, serverConfigStore, tokenStore, deviceId)
```

> 若 `HttpClientFactory.create` 的 token 需要在登录后热替换，M1 采用**每次造 source 时新建 `OkHttpClient`** 也可接受（服务器数量是个位数）；
> 更优做法是拦截器里按 serverId 查 `TokenStore`（spec 未强制，P5 再说）。

- [ ] **Step 5: 补 `AppNavHost` 的两条路由**

```kotlin
    var pending by remember { mutableStateOf<PendingServer?>(null) }   // 选源结果在两页之间传递，避免 URL 编码

    composable(Routes.WELCOME) { NoServerScreen(onAddServer) }

    composable(Routes.ADD_SERVER) {
        val vm = remember { AddServerViewModel(probe = { base, port -> probeServer(client, base, port) }) }
        val state by vm.state.collectAsStateWithLifecycle()
        AddServerScreen(
            state = state,
            onAddressChange = vm::onAddressChange,
            onPortChange = vm::onPortChange,
            onTest = { scope.launch { vm.onTest() } },
            onProceed = {
                val s = state
                if (s.probeStatus is ProbeStatus.Reachable) {
                    pending = PendingServer(
                        serverId = UUID.randomUUID().toString(),
                        sourceType = s.detectedSourceType,
                        baseUrl = normalizeBaseUrl(s.address, s.port.toIntOrNull() ?: 8096),
                    )
                    nav.navigate(Routes.LOGIN)
                }
            },
            onBack = { nav.popBackStack() },
        )
    }

    composable(Routes.LOGIN) {
        val target = pending
        if (target == null) {
            // 异常路径（例如直接深链）：退回引导页，不显示空白页
            LaunchedEffect(Unit) { nav.popBackStack(Routes.WELCOME, inclusive = false) }
        } else {
            LoginScreen(
                config = target,
                onLoggedIn = {
                    // 登录已把 ServerConfig/Token 落盘 → 回到根级重进，MainActivity 会重新造出 source
                    nav.navigate(Routes.LIBRARY) { popUpTo(0) }
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
```

`probeServer(client, base, port)` 的实现（放在 `:feature:server`，避免让 UI 直接依赖 `:source:httpapi` 的细节）：

```kotlin
suspend fun probeServer(client: OkHttpClient, baseUrl: String, port: String): ProbeStatus = try {
    val api = HttpMediaApi(client, SourceConfig(serverId = "probe", baseUrl = baseUrl, token = null, userId = null))
    val info = api.getPublicSystemInfo()
    ProbeStatus.Reachable(info.serverName ?: baseUrl, info.productName, info.version)
} catch (e: ApiException.NotFound) {
    ProbeStatus.NotFound
} catch (e: Exception) {
    ProbeStatus.Unreachable(e.message ?: e::class.java.simpleName)
}
```

- [ ] **Step 6: `MainActivity` 换成真实的 `source`**

```kotlin
    @Inject lateinit var sourceFactory: SourceFactory
    // …
    val servers by serverConfigStore.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val activeConfig = servers.firstOrNull()
    // 无服务器 → null（落引导页）；有配置但令牌缺失 → 工厂返回 null，同样落引导页等重新登录
    val source: MediaSource? = remember(activeConfig) { activeConfig?.let(sourceFactory::create) }
    val showWelcome = activeConfig == null || source == null

    AppNavHost(
        repository = libraryRepository,
        serverId = if (showWelcome) null else activeConfig!!.id,
        source = source,
        onAddServer = { /* 见下 */ },
    )
```

`onAddServer` 需要从 `AppNavHost` 内部触达 `nav`，所以把导航动作**收进 `AppNavHost` 内部**（`NoServerScreen(onAddServer = { nav.navigate(Routes.ADD_SERVER) })`），
`MainActivity` 侧只需传一个 `startWithAddServer: Boolean`（首次启动且无服务器时直接落在引导页）。这样不用把 `NavController` 提升到 Activity。

- [ ] **Step 7: 运行确认通过**

Run: `./gradlew :feature:server:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS（feature:server 8 tests）+ `BUILD SUCCESSFUL`。

- [ ] **Step 8: 手动验收（M1 的关键一步）**

真机（或模拟器，需与服务器同一局域网）：

1. 首启 → 解锁 → 引导页 → 「添加服务器」→ 填 Emby 地址与端口 → 「测试连接」应回显服务器名；
2. 输入账号密码 → 登录成功 → 自动进入库页，能看到**库 Tab + 海报网格**；
3. 杀掉进程重开 → **不需要重新添加服务器**（配置与令牌已落盘）→ 仍能直接进库页；
4. `adb shell run-as app.myplayer ls files/secrets` 应看到 `servers.bin` / `token_s1.bin` / `app_lock.bin`，且 `cat` 出来是密文。

- [ ] **Step 9: 回写 spec（兑现 roadmap 的「先改 spec 再改实现」）**

若本 Task 的落地与 spec 有任何出入（接口、流程、字段），**先改 spec 再提交**，并在本计划的「已知衔接点」表中登记。

- [ ] **Step 10: 提交**

```bash
git add -A && git commit -m "feat(app): wire source factory, navigation and end-to-end login flow"
```

---

## 完成判定（M1 DoD）

- [ ] `./gradlew test` 全部通过（共 **70** tests：`:core:model` 9、`:core:database` 6、`:core:security` 7、`:core:datastore` 8、`:core:network` 4、`:source:httpapi` 5、`:source:emby` 9、`:core:designsystem` 3、`:feature:lock` 6、`:feature:library` 1、`:feature:server` 8、`:app` 4）。
- [ ] `:app:assembleDebug` 成功产出可安装 APK。
- [ ] **首启引导**：无任何服务器时启动落在引导页，点「添加服务器」能进选源页（Task 13/14）。
- [ ] **添加并登录**：填 Emby 地址 → 「测试连接」回显服务器名 → 账号密码登录成功 → 自动进入库页，看到**库 Tab 与海报网格**（Task 13/14）。
- [ ] **持久化**：杀掉进程重开，无需重新添加服务器即可直接进库页（Task 5 的落盘实现 + Task 14 的 `SourceFactory`）。
- [ ] **应用锁**：冷启动出现九宫格锁屏页 → 绘制已保存图案后进入下一页；再次切后台再回前台，按 `unlockGraceMillis`（默认 0 = 立即）重新上锁。
- [ ] **密文**：`adb shell run-as app.myplayer ls files/secrets` 能看到 `servers.bin` / `token_<id>.bin` / `app_lock.bin`，`cat` 结果**无明文**地址、令牌或图案轨迹。
- [ ] **海报降级**：库中存在无海报条目时显示占位而不是空白（`hasPoster=false` → `ImageSource.None`）。
- [ ] 未在本计划中的能力（排序 / 中文搜索 / 播放 / trickplay / 飞牛 / 本地文件）**明确保留到 P2–P5**，未提前实现。

## 已知衔接点（供 P2–P5 使用）

| 本计划留的钩子 | 位置 | 下游计划 |
|---|---|---|
| `PlaybackPlan` / `ImageSource` / `ClientCapabilities` 领域模型 | `:core:model` | P2 播放层直接消费 |
| `MediaSource.getTrickplay()` 返回 `null` 占位 | `:source:httpapi` | P3 用 M0 实测端点实现（spec §6.1） |
| `MediaSource.reportProgress()` 空实现 | `:source:httpapi` | P2 实现 `/Sessions/Playing*`（spec §6.4） |
| `Ticks` ↔ ms 的转换点（`reportProgress` 边界） | `:core:model` + P2 的 `:core:playback` | P2 落地双向转换（spec §6.2 seek 用 ms） |
| `HttpMediaApi.getItems(includePeople)` 为详情页留的重字段开关 | `:source:httpapi` | P5 详情页 `getItemDetail` 直接可用（spec §14.3） |
| `SourceFactory.create()` 的 `else -> null` 分支 | `:feature:server` | **P4** 在此返回 `JellyfinSource`；**L2** 返回 `LocalSource` |
| `ServerConfigStore` 已支持多服务器（`upsert`/`remove`） | `:core:datastore` | **P5** 服务器管理页（增删 + 切换） |
| `AppNavHost` 只接了 welcome / addServer / login / library | `:app` | P5 补 detail / player；P3 补 search |
| `RoomLibraryRepository.observeItems` 是排序/筛选/搜索的唯一数据源 | `:core:database` | P3 本地排序 + FTS4 索引（spec §6.3 / §6.6） |
| `LibraryScreen` 仅有库 Tab，无排序筛选入口 | `:feature:library` | P3 加右下角单一「排序 / 筛选」按钮（spec §14.1） |
| `rememberImageSource` 目前私有于 `:feature:library` | `:feature:library` | P5 抽到 `:core:designsystem` 供详情页复用 |
| Manifest 的 `usesCleartextTraffic="true"` 为临时放宽 | `:app` | **P5** 改为 `networkSecurityConfig` 白名单 + 设置项控制（spec §10） |
| `PlayerSettings` 已含附 B 全部字段，设置页尚未渲染 | `:core:datastore` | P5 设置页按分组渲染（spec §16 / 附 B） |

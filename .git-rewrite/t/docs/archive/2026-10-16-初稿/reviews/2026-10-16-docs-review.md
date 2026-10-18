# LimePlayer 开发文档评审报告

- 日期：2026-10-16
- 评审对象：
  - `docs/superpowers/specs/2026-10-16-android-media-player-design.md`（下称 **spec**）
  - `docs/superpowers/plans/2026-10-16-android-player-00-roadmap.md`（下称 **roadmap**）
  - `docs/superpowers/plans/2026-10-16-android-player-01-m0-source-verification.md`（下称 **P0**）
  - `docs/superpowers/plans/2026-10-16-android-player-02-m1-foundation-and-emby.md`（下称 **P1**）
  - `design/player-ui-mockups.html`（下称 **设计稿**）
- 总体评价：**文档质量高于多数个人项目**——痛点可追溯、结论有出处、模块边界清晰、计划是 TDD 粒度（先写失败测试 → 实现 → 通过 → 提交，测试数量与完成判定可对账）。以下问题集中在「任务的闭合性」与「实现层的技术正确性」，属于**改动量不大但必须改**的类型。

---

## 一、P0 必修（会阻塞执行或自相矛盾）

### 1. P1 缺少整条「添加服务器 → 登录 → 落库」的实现任务 ★最高优先

M1 的验收目标是「能浏览 Emby 库并显示海报」，但把这条链路拆开看，6 个环节里 **4 个在 P1 里没有对应的 Task**：

| 环节 | P1 中的状态 |
|---|---|
| 添加服务器 UI（spec §17：选源 → 填地址 → 测试连接） | **无任务** |
| 登录并换取 `AccessToken` | 只有 Task 7 的 `api.authenticateByName()`，**没有任何调用方** |
| 写入 `ServerConfig` / `TokenStore` | **无任务** |
| 拉取库与条目落 Room | Task 3 的 `syncLibraries` / `syncItems` ✅ |
| 海报网格渲染 | Task 11 的 `LibraryScreen` ✅ |
| 导航接入库页 + 触发首次同步 | **无任务**（Task 12 的 `NavHost` 仍是占位 `Text("库浏览（M1 骨架）")`） |

后果：**P1 完成后，`M1 DoD` 第 ① ② 条无法验收**，也无法走通 Task 12 里那句「Emby 登录→拉库→海报网格链路可手工验证一次」——因为从 UI 到 `EmbySource.create()` 之间没有任何装配代码。设计稿里已经有「③ 添加服务器」「④ 登录」两张屏幕原型，说明这部分在 UI 侧是明确要做的，只是计划漏了。

**建议**：在 P1 增补两个 Task：
- **Task 13**：`:feature:settings`（或叫 `:feature:server`）——添加服务器页 + 登录页。产物：`AddServerViewModel`（探测 `/System/Info/Public` 回显服务器名）+ `LoginViewModel`（调 `authenticateByName`，错误分支映射）+ 写入 `ServerConfigStore` / `TokenStore` + 组装 `EmbySource`。
- **Task 14**：`ServerRepository` / `SourceFactory` —— 根据 `ServerConfig` 造出对应的 `MediaSource`；`AppNavHost` 接入 `library` 路由 + `LibraryViewModel(serverId)` + 首次进入触发 `refresh(source)`。同时把 Task 12 的 NavHost 占位替换掉。

### 2. P1 Task 12 的 DI 与 M1 DoD 直接矛盾

`AppModule` 提供的是：

```kotlin
fun serverConfigStore(): ServerConfigStore = InMemoryServerConfigStore()
fun tokenStore(): TokenStore = InMemoryTokenStore()
```

而 M1 DoD 写着「**存储的令牌为密文**（`adb shell run-as ... cat files/token_*.bin` 检查）」。内存实现**根本没有文件产出**，DoD 无从验收；且重启 App 后服务器配置与登录态全部丢失，与 spec §5.1「写入 `:core:datastore`（令牌字段加密存储）」不符。

Task 5 其实**已经写好了** `EncryptedTokenStore`（依赖 `readBytes/writeBytes/deleteFile` 回调）和 `KeystoreSecretCipher`，只是没人把它们装配起来。

**建议**：在 Task 5 或新增 Task 里补 `FileServerConfigStore`（加密 JSON 落 `filesDir`）+ 把 `EncryptedTokenStore` 接进 `AppModule`，并把 DoD 的验收命令写成实际文件名。

### 3. P1 开头对 spec 的「差异化说明」记错了

P1 写道：「spec 把『切后台自动暂停』列为 M1 内容……因此本计划只落地应用锁，切后台自动暂停移入 P2，此差异已在 roadmap 的 M1 DoD 中同步」。

核对 spec §11：**M1 行只有「…同时落地应用锁（默认九宫格图案）| 能浏览 Emby 库并显示正确海报；冷启动 / 回前台需解锁」**，切后台自动暂停本来就写在 **M2 行**。所以这个「差异」并不存在，roadmap 的 M1 DoD 里也没有任何同步痕迹。这段说明会给执行者造成误导。

**建议**：删除或改写为「spec §11 已把切后台暂停列在 M2，本计划不涉及」。

### 4. P1 的仓库路径与本机不符

Task 0 Step 1 与 Step 5 用 `cd /workspace && git init` / `gradle wrapper`，但本机项目根是 `D:\porject\LimePlayer`（且目前**尚无 git 仓库**）。子代理按字面执行会在错误目录建仓。

**建议**：改为项目根（或直接依赖当前工作目录，不写 `cd`）；Step 5 补一句 Windows 下用 `gradlew.bat`。

---

## 二、P1 建议修（实现层技术正确性）

### 5. `TranscodingUrl` 被当作绝对 URL 使用 ★

P1 Task 7 `HttpMediaSource.planPlayback()`：

```kotlin
!source?.transcodingUrl.isNullOrEmpty() ->
    PlaybackPlan.Transcode(source!!.transcodingUrl!!, ...)
```

Emby / Jellyfin 的 `PlaybackInfo` 返回的 `TranscodingUrl` 是**相对路径**（形如 `/videos/<id>/master.m3u8?...`）。Task 8 的测试里 mock 的正是 `"/videos/i1/master.m3u8"`，但断言只检查了 `isInstanceOf(Transcode::class.java)`，**测试把问题掩盖了**。真机上 ExoPlayer 会拿到一个不可解析的 URL。

**建议**：`playbackUrl(baseUrl, transcodingUrl)` 做前缀拼接（已是 `/` 开头就拼 `baseUrl + prefix`）；同时把测试断言改成校验**完整 URL 前缀**。

### 6. `planPlayback` 的降级顺序偏离 spec §6.5

spec 的决策链是：`DirectPlay → Direct` / `DirectStream → Transcode(remuxOnly)` / `否则 → Transcode(完整转码)`。
P1 的实现是：`DirectPlay → Direct` / `transcodingUrl 非空 → Transcode(remuxOnly = supportsDirectStream)` / **`否则 → Direct`**。

第三分支把「服务端既不支持直放也不给转码地址」降级为**直连原文件**，会导致播放失败而不是明确报错；且 `SupportsTranscoding` 字段被取到了却从未使用。

**建议**：严格照 spec 三档实现（第三档可用本地拼 `/Videos/{id}/master.m3u8` 兜底），并在都不支持时返回可诊断的错误态，配合 spec §7.8 的错误卡片。

### 7. `getLibraries()` 的取库方式与 `userId` 未保护

`HttpMediaSource.getLibraries()` 走 `api.getItems(libraryId = null, types = ["CollectionFolder","Folder"])`，即 `/Users/{userId}/Items`。问题有二：

1. Emby/Jellyfin 取库列表的标准做法是 `/Users/{userId}/Views`，用 `Items + CollectionFolder` 会把非媒体库的文件夹也扫进来；
2. `config.userId` 为 null 时会请求 `/Users/null/Items`（返回 404 而非清晰报错）——`requireNotNull` 只加在 `getItemDetail` / `planPlayback`，`getLibraries` / `getItems` 没有。

**建议**：改用 `/Users/{userId}/Views`（并把这条列入 **P0 的验证项**，飞牛是否有该端点需实测）；`getLibraries` / `getItems` 同样加 `requireNotNull`。

### 8. `Fields` 一次拉满，抵消 R5 的同步优化

`getItems` 固定 `Fields=MediaSources,UserData,Genres,People`。`People`（全量演职员）在数千～数万条库上是响应体积与解析耗时的主要来源，与 spec R5「分页 + 增量同步」的意图相冲突。

**建议**：列表页只请求 `Fields=MediaSources,UserData`；`Genres/People` 留给详情页 `getItemDetail`。或按 spec §6.6 本地索引需要的最小字段集来定。

### 9. `posterFor: (MediaItemDto) -> ImageSource` 无法实现

Task 11 的 `LibraryScreen` 要求一个**同步**回调返回海报，但 `MediaSource.image()` 是 **suspend**。计划没有给出桥接方案，执行者会卡在这里。

**建议**：改为在 ViewModel 里预生成 `Map<itemId, String /*url*/>`（登录后一次性构造，URL 是纯字符串拼接，无需挂起），或让 `LibraryScreen` 接收 `posterUrlOf: (MediaItemDto) -> String?`。顺带把 `image()` 拆成非挂起的 `imageUrl()`，语义更准。

### 10. 无海报的条目缺 `ImageSource.None` 分支

spec §3.2 定义了 `ImageSource.None`，但 P1 的 `image()` **无条件**返回 `Remote`，`BaseItemDto` 里也没有 `ImageTags` 字段。结果是无海报条目会去请求一个 404 URL，UI 显示空白而非占位图。

**建议**：DTO 补 `ImageTags`，无海报时返回 `ImageSource.None`；这与 spec §3.2 的原始设计一致。

### 11. `DataStore` 未处理 IO 异常

`DataStoreSettingsStore.settings` 直接 `dataStore.data.map { }`。官方要求捕获 `IOException`（默认文件流损坏/读失败会直接崩掉整条 Flow）：

```kotlin
dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { ... }
```

### 12. `PlayerSettings` 只覆盖了附 B 的一部分

附 B 有 20 项设置，`PlayerSettings` 只有 14 个字段、`SettingsStore` 只有 6 个 setter，缺：屏幕方向、默认画面比例、字幕样式、连播开关、双击间隔（字段有、接口无）等。计划未说明剩余项归属。

**建议**：Task 5 明确写「其余设置项在 P5 的 `:feature:settings` 中随设置页一起补齐」，或一次性把字段与 setter 建全（成本很低）。

### 13. `Manifest` 里 `usesCleartextTraffic="true"` 与「默认关闭」冲突

spec §10 与附 B 均要求明文 HTTP **默认关闭、显式开启**；P1 直接写死 `true`，且没有 `network_security_config`，也没有 `targetSdk` 声明。

**建议**：M1 可以临时放宽，但要在计划里显式标注「临时，P5 改为 security-config 白名单」；同时补 `targetSdk`（缺失时默认跟随 `compileSdk`，Android 15+/新版系统的行为变更会不可控）。

### 14. 应用锁组件的两处不一致

- `PatternLockScreen` 的 `pointerInput` 用 `size.width` 作为画布尺寸，而 `Canvas` 绘制用 `size.minDimension`——非正方布局下命中区与绘制会错位，应统一；
- 绘制点用 `radius = 12f`（**像素**），而 spec §15 规定「点直径 12dp、命中半径 24dp」，应按 dp 换算，否则真机上点会偏小；
- `detectDragGestures` 只在拖动时回调，**轻点单个点不会选中**，与「绘制图案」的直觉略有落差（可接受，但建议在规格里写明）。

### 15. `Ticks` 值类型造了但没用

Task 1 引入 `@JvmInline value class Ticks` 并配了 4 个测试，但 Task 2 的 `MediaItemDto.durationTicks` / `resumePositionTicks` 仍用裸 `Long`，之后全部代码都不使用 `Ticks`。这属于「类型安全只做了一半」，反而增加认知负担。

**建议**：要么全面改用 `Ticks`（推荐，跨 Emby/Jellyfin/MediaStore 的三套时间单位最容易出错），要么删掉 Task 1 只留转换函数。

### 16. 令牌出现在图片 URL 里

`imageUrl(..., apiKey = token)` 把令牌放查询参数，并断言 `contains("api_key=T")`。Coil 会据此做磁盘缓存键，令牌会进入缓存文件名/日志。

**建议**：优先用请求头传令牌（Coil 支持 `ImageRequest.Builder.addHeader`），`api_key` 仅作兜底。

---

## 三、一致性回写（spec ↔ 计划）

roadmap 自己规定了「需要偏离时先改 spec 再改实现」，但 P1 已经有 3 处偏离没有回写，P1 也没有像 P0 那样的「回写 spec」收尾任务。

| 位置 | 现状 | 建议 |
|---|---|---|
| spec §4 模块表 | 列了 16 个模块，**既无 `:core:security` 也无 `:feature:lock`**；但 roadmap 的依赖图与 P1 都已引入它们 | spec §4 补这两行（职责：图案/PIN/Keystore 凭据；锁屏页与门卫） |
| spec §3.2 `MediaSource` 接口 | 无 `serverId` 成员；P1 的接口有 | spec §3.2 补 `val serverId: String` |
| spec §3.2 `MediaItemDto` | 有 `imageTags{}`；P1 去掉了 | 二者取一，建议保留 `imageTags`（见问题 10） |
| spec §7.2 | `configChanges` 含 `smallestScreenSize` | P1 Manifest 少了这一项，补齐 |
| P1 与 spec §2/§11 | P1 新增了 serverId、core:security、feature:lock 三处偏离 | 在 P1 末尾加一个「Task 15：回写 spec」，与 P0 Task 6 对称 |

另外 `roadmap` 的 P2–P5 目前只有一行占位，这本身没问题，但 **P2 的编写建议现在就锁定两件事**：`reportProgress` 与「切后台暂停 + 回前台先解锁再恢复」的交互顺序（spec §8.2 已定，实现时要避免「先恢复播放再解锁」的隐私泄露窗口）。

---

## 四、体验与文档工程

1. **版本矩阵落后约两代，且缺 `targetSdk`**。P1 写的是 Kotlin 2.1.0 / AGP 8.7.3 / Compose BOM 2024.12.01 / Gradle 8.11.1 / `compileSdk = 35`——这是一份 2024 年末的矩阵。参考 2026-10 的公开现状：Kotlin 已到 2.4.x（2.4.20 / 2026-09）、AGP 已到 9.x（9.1.1 要求 Gradle ≥9.3.1、JDK 17，9.2.0 起配合新 Compose）、Compose BOM 2026.08.00 对应 Compose 1.12 且**要求 compileSdk 37**、Google Play 自 2026-08-31 起要求新提交 `targetSdk ≥ 36`。
   → 建议开工前把版本目录整体上移一档，并**以官方 release notes 复核兼容矩阵**（Compose 1.12 的最低 AGP 在官方两处文档里有 9.1.1 / 9.2.0 两种说法，取高者）。自用 App 不上架，但 `compileSdk 35` 意味着新系统行为全部未验证，得不偿失。注意 Kotlin 2.1 → 2.4 跨越 `K2` 之后的多轮变更，若坚持用旧矩阵，请把这句写进计划的「已知约束」。

2. **`superpowers` 子技能在本机未安装**。两份计划头部都写了 `REQUIRED SUB-SKILL: superpowers:subagent-driven-development` / `superpowers:executing-plans`，但本机 `~/.workbuddy/skills` 下并没有对应的 skill。
   → 要么补上安装方式，要么把「执行方式」一节改成「未安装时按 Task 顺序串行执行」。

3. **P0 的 curl 在 Windows 上会踩坑**。PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名，`curl -s -i -X POST` 会直接报参数错误。
   → P0 里注明「用 `curl.exe` 或 Git Bash」，或在每条命令前统一加 `curl.exe`。

4. **P0 可以直接填真实地址**。用户实际有飞牛 NAS（`192.168.31.222`），`FN_BASE` 无需留占位示例。
   → 把 P0 前置准备一节的 `http://192.168.1.10:8005` 换成实际地址，`EMBY_BASE` 留占位。

5. **P0 建议补两项验证**（都直接影响 P1 的代码写法）：
   - **库列表端点**：`/Users/{userId}/Views` 是否存在（见问题 7）；
   - **trickplay 前置条件**：先在 Emby / 飞牛后台确认 trickplay 已开启并完成预生成，否则 Task 4 会得出「不可用」的错误结论（spec R3 已提示，但 P0 的步骤里没写）。

6. **设计稿与文档的覆盖差**。设计稿有 6 张原型（多库浏览 / 九宫格锁屏 / 添加服务器 / 登录飞牛 / 设置 / 横屏播放），spec 里已有的**搜索页、详情页、竖屏播放、排序筛选弹层、连播浮层、错误与降级态、平板布局**尚无原型。
   → 按 M3（差异化）的优先级，建议下一批优先补「搜索页」与「进度条拖动预览态」这两张——它们是痛点 2、痛点 3 与中文搜索的验收现场。

7. **`docs/superpowers/` 目录名与本机 skill 现状不匹配**（沿用未安装的 skill 命名空间）。若后续要落地，建议改为 `docs/design/` + `docs/plans/`，或保留但在 README 里说明来源。

---

## 五、建议的修改顺序

1. 补 P1 的 Task 13 / Task 14（添加服务器 + 源装配与导航）—— **不做这两步，M1 无法验收**；
2. 修 DI 的内存实现（问题 2）；
3. 修 `playbackUrl` 拼接、`planPlayback` 降级顺序、`/Views` 与 `requireNotNull`（问题 5、6、7）；
4. 回写 spec（问题 1 的一节表格）+ 给 P1 加「回写 spec」收尾任务；
5. 版本矩阵与 `targetSdk` 上移，补 Windows / superpowers 两条执行说明；
6. 其余（9–16）随对应 Task 一并修订。

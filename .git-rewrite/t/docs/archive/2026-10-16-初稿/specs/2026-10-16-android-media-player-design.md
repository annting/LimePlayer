# 青柠视频播放器（LimePlayer）设计文档

- 日期：2026-10-16
- 状态：待用户评审
- 应用名：青柠视频播放器 / Lime Video Player（简称 LimePlayer）
- 定位：个人自用 / 学习开源

---

## 1. 背景与目标

### 1.1 要解决的痛点（来自用户）

1. **VidHub 收费，且部分视频无法播放** —— 需要免费、格式兼容性更好的播放器。
2. **Emby 官方 App 播放体验差** —— 可以点击按钮快进几秒，但**无法拖动进度条实时预览**；点击进度条位置有约 1 秒卡顿。同手机、同网络下 VidHub 无此问题。
3. **飞牛影视 App 排序能力弱** —— 没有按「视频时长」「文件大小」等方式排序。
4. **第三方 App 普遍有广告、界面粗糙** —— 需要无广告、精致 UI。
5. **Emby 无法设置全局倍速** —— 每次播放都得手动调倍速，需要在设置里配置「默认倍速」并自动套用到每一次播放。
6. **Emby 官方搜索对中文支持很差** —— 中文标题基本搜不到，需要客户端以本地索引补足中文 / 拼音 / 首字母搜索（§6.6）。

### 1.2 产品目标

- 一个**无广告、界面精致**的安卓播放器，手机 / 平板为主（不做 TV / 车机适配）。
- 主链路先接入两类服务端源：**Emby 服务器**、**飞牛影视（fnOS 影视）**；**本机视频文件**放到后期迭代（§11 的 L2）。
- 针对痛点做差异化：**进度条实时缩略图预览**、**毫秒级无卡顿 seek**、**强大的本地排序与筛选**、**全局默认倍速**、**中文友好搜索**。
- **默认安全与省电**：应用级**九宫格划线图案锁**（启动 / 回前台解锁）；**默认切后台自动暂停**播放。
- 播放页横竖屏**跟随手机旋转自动切换**，并支持手动锁定。

### 1.3 非目标（本期不做）

- Android TV / 盒子 / 车机的大屏与遥控器适配。
- Plex、SMB / WebDAV / NFS 目录直挂（作为后续可插拔源考虑，见 §12）。
- 直播（IPTV）、音乐库、相册、下载离线包。
- 商业上架、多语言国际化、账号体系。
- **飞牛影视的授权式登录（NAS 账号 OAuth / 扫码 / 一次性授权码）** —— 后期迭代 L1（§5.2）。
- **本机视频文件播放** —— 后期迭代 L2（§5.3、§11）。
- **复杂主题系统**（动态取色 / 多套色板 / 主题商店）—— 只做**亮色 + 暗色**两套主题（§14.0）。

---

## 2. 关键调研结论（决定方案结构）

| 结论 | 依据 | 对设计的影响 |
|---|---|---|
| **飞牛影视使用 Jellyfin 兼容协议** | 飞牛官方帮助中心：Infuse 连接飞牛需选「其他 Jellyfin」并手填端口 `8005`；VidHub 直接提供「飞牛/fnOS」服务器类型。文档还提示局域网存在 Emby/Jellyfin 时会互相干扰扫描 | 飞牛与 Emby 可**共用一套核心 + 两个薄适配层**，而非两套独立客户端 |
| Emby 与 Jellyfin API 同源 | Jellyfin 是 Emby 3.5 的分支，`/Users/AuthenticateByName`、`/Items`、`/Items/{Id}/PlaybackInfo`、`/Sessions/Playing*`、`/Videos/{Id}/stream` 等端点和模型高度一致 | 差异集中在**鉴权头、能力协商细节、转码参数命名**，用适配层吸收 |
| **Trickplay（缩略图预览）Jellyfin 10.9+ 原生支持** | Jellyfin API：`GET /Videos/{itemId}/Trickplay/{width}/tiles.m3u8` 与 `GET /Videos/{itemId}/Trickplay/{width}/{index}.jpg`；需**按媒体库开启**并预生成 | 进度条预览的**主路径**；需优雅降级到本地生成 |
| Emby 4.8+ 也有 trickplay | Emby 官方版本说明与社区资料 | 作为 Emby 的预览来源，**端点与参数需在 M0 实测确认** |
| **Emby 服务端排序不含文件大小** | 官方 `GET /Items` 的 `SortBy` 仅列出 `Album/Artist/Budget/CommunityRating/CriticRating/DateCreated/DatePlayed/PlayCount/PremiereDate/ProductionYear/SortName/Random/Revenue/Runtime` | 「按文件大小/码率/分辨率排序」**必须客户端本地排序**（服务端只负责取数） |
| **取「媒体库根」要用 `/Users/{userId}/Views`** | Emby/Jellyfin 的库列表专用端点；若用 `Items + IncludeItemTypes=CollectionFolder` 会把非媒体库的普通文件夹一起扫进来 | `MediaSource.getLibraries()` 只走 `/Views`；`CollectionType` / `ChildCount` 也从该端点取，用于库 Tab 的类型与条目数 |
| **`PlaybackInfo` 返回的 `TranscodingUrl` 是相对路径** | 形如 `/videos/<id>/master.m3u8?...`，不含 host | 播放层拿到后**必须**与 `baseUrl + prefix` 拼接；否则 ExoPlayer 无法解析 |
| **图片可用请求头鉴权** | Emby/Jellyfin 的图片端点同时接受 `X-Emby-Token` 头与 `api_key` 查询参数 | 图片走**请求头**（Coil 支持注入 header），避免令牌进入 URL、磁盘缓存键与日志；播放流仍用 `api_key`（交给 ExoPlayer/HLS，形态最稳） |

> ⚠️ 待验证项（M0 抓包确认）：飞牛影视 `:8005` 的**接口路径前缀**（是否带 `/emby/` 或 `/jellyfin/`）、鉴权头、`PlaybackInfo` 行为、**`/Users/{userId}/Views` 是否可用**（若不可用，取库需回退到 `Items + IncludeItemTypes=CollectionFolder`，见 §5.1）、以及 trickplay 是否需要服务端先开启预生成。官方无公开 API 文档。

---

## 3. 架构总览

### 3.1 分层

```
┌──────────────── UI 层（Compose + Material 3） ─────────────────┐
│ 库浏览 · 搜索 · 排序/筛选 · 详情页 · 播放器页(控制器 + 预览条) · 设置 │
├──────────────── 领域层（纯 Kotlin，无 Android 依赖） ──────────┤
│ MediaSourceRepository · SortEngine · SearchEngine                │
│ PlaybackNegotiator · PlaybackState · ProgressSync                │
├──────────────── 数据层 ────────────────────────────────────────┤
│ Room(元数据缓存/索引/本地进度) · DataStore(服务器/账号) · Coil 图片 │
├──────── 源适配层（同一接口，三种实现） ───────────────────────────┤
│ EmbySource        │ JellyfinSource(飞牛 :8005) │ LocalSource       │
├──────────────── 播放层（Media3） ──────────────────────────────┤
│ ExoPlayer · HLS(转码/重封装) · FFmpeg 音频扩展 · MediaSessionService │
└───────────────────────────────────────────────────────────────┘
                ↕ OkHttp + kotlinx.serialization
        Emby 服务器           飞牛影视 :8005        本机 MediaStore / SAF
```

### 3.2 媒体源抽象（核心设计）

三类源统一为同一接口，差异全部封装在实现里。**这是「本地文件播放」加入后最关键的结构变化**：

```kotlin
enum class SourceType { EMBY, JELLYFIN, LOCAL }

interface MediaSource {
    val type: SourceType
    /** 多服务器时用于区分来源（Room 主键的一部分）。同一时刻只展示一个源，见 §14.4。 */
    val serverId: String
    suspend fun getLibraries(): List<MediaLibrary>
    suspend fun getItems(query: ItemQuery): PagedResult<MediaItemDto>
    suspend fun getItemDetail(id: String): MediaItemDto
    suspend fun image(item: MediaItemDto, kind: ImageKind, size: ImageSize): ImageSource
    suspend fun planPlayback(item: MediaItemDto, caps: ClientCapabilities): PlaybackPlan
    suspend fun getTrickplay(item: MediaItemDto, width: Int): TrickplaySpec?   // 可空 → 降级
    suspend fun reportProgress(item: MediaItemDto, pos: Duration, state: PlayState)  // LOCAL 为空实现
}
```

> **时间单位的类型约定**：领域层的时间一律用 `Ticks`（`@JvmInline value class`，单位 100ns，与 Emby/Jellyfin 一致），
> 因为本机源用 ms、服务端用 tick、播放层用 ms，三者混用是这类客户端最常见的 bug 来源。
> 例外只有 `reportProgress` 的 `pos: Duration`——它的调用方是播放层，边界处显式互转。

**统一定义 `PlaybackPlan`**，让播放层对三类源完全无感：

```kotlin
sealed interface PlaybackPlan {
    /** 直连播放：HTTP Range 或本地 content:// / file:// URI */
    data class Direct(
        val uri: String,
        val headers: Map<String, String>,
        val mediaSourceId: String?,   // Emby/Jellyfin 需要
    ) : PlaybackPlan

    /** 服务器转码/重封装：HLS */
    data class Transcode(
        val hlsUrl: String,
        val headers: Map<String, String>,
        val isRemuxOnly: Boolean,     // true = 仅重封装/换音轨，视频未重编码
    ) : PlaybackPlan
}
```

**统一定义 `ImageSource`**（服务端源给 URL，本地源给本地生成结果）：

```kotlin
sealed interface ImageSource {
    /** 服务端图片，直接交给 Coil 请求 */
    data class Remote(val url: String, val headers: Map<String, String> = emptyMap()) : ImageSource
    /** 本地抽帧 / 内嵌封面，交给 Coil 的本地数据源 */
    data class Local(val uri: String) : ImageSource
    /** 无可用海报，UI 显示占位 */
    data object None : ImageSource
}
```

**统一 DTO `MediaItemDto`**（三源归一，供统一展示与本地排序）：

```
id, serverId, sourceType, title, seriesName, seasonNumber, episodeNumber,
durationTicks: Ticks, sizeBytes, bitrate, width, height,
videoCodec, audioCodecs[], container, libraryId,
dateCreated, communityRating, officialRating,
playCount, userData{isPlayed, resumePositionTicks: Ticks, isFavorite},
hasPoster, genres[], cast[], trickplayAvailable
```

- `hasPoster: Boolean`（由响应的 `ImageTags.Primary` 派生）：为 false 时 `image()` 返回 `ImageSource.None`，UI 显示占位——**不要**为无海报条目发一个必然 404 的图片请求。

- Emby / Jellyfin：从 `Items` + `Fields` 映射，`sizeBytes/bitrate` 取自 `MediaSources`；`image()` 返回 `ImageSource.Remote`。
- Local：从 `MediaStore.Video` 映射，`sizeBytes` 来自 `MediaStore.Video.Media.SIZE`，时长来自 `DURATION`；评分/演职员等字段留空；`image()` 返回 `ImageSource.Local`（未生成时返回 `None` 并由 UI 显示占位）。
- **本地排序/筛选完全基于 Room 中的 `MediaItemDto` 副本**，不依赖任何服务端能力。

---

## 4. 模块划分（多模块 Gradle）

| 模块 | 职责 | 关键依赖 |
|---|---|---|
| `:app` | 入口、导航、DI 装配、主题 | Compose, Hilt |
| `:core:model` | `MediaItemDto` / `PlaybackPlan` / `SourceType` 等领域模型 | 无 Android 依赖 |
| `:core:database` | Room 实体、DAO、索引与迁移 | Room |
| `:core:datastore` | 服务器配置、账号令牌、用户偏好 | DataStore |
| `:core:security` | 图案 / PIN 的编码与弱图案规则、PBKDF2 派生、Keystore 对称加密、凭据落盘（§8.1） | 无 UI 依赖 |
| `:core:network` | OkHttp 客户端、TLS/超时、日志、重试 | OkHttp, kotlinx.serialization |
| `:core:designsystem` | 主题、色板、基础组件 | Compose |
| `:core:playback` | 播放内核抽象 `PlaybackEngine`（当前实现为 ExoPlayer）+ MediaSession + 缓冲与 seek 策略；抽象层让 M5 可平行加入 libmpv 实现 | Media3 |
| `:source:httpapi` | Emby/Jellyfin 共享的 REST 骨架：请求构造、鉴权头、序列化模型、错误映射 | `:core:model`, `:core:network` |
| `:source:emby` | Emby 适配器（继承 `:source:httpapi`，覆盖 Emby 差异） | `:source:httpapi` |
| `:source:jellyfin` | 飞牛影视（Jellyfin 协议）适配器（继承 `:source:httpapi`） | `:source:httpapi` |
| `:source:local` | MediaStore + SAF 本机媒体源 | `:core:model`, `:core:database` |
| `:feature:library` | 库浏览、排序、筛选、搜索入口 | 上述 |
| `:feature:lock` | 九宫格图案锁页面、图案状态机、回前台门卫（§8.1 / §15） | `:core:security`, Compose |
| `:feature:server` | 添加服务器（探测 `/System/Info/Public`）与登录页；**`SourceFactory`**（把已保存的 `ServerConfig` 装配成 `MediaSource`） | `:core:datastore`, `:source:httpapi`, `:source:emby` |
| `:feature:search` | 搜索页：本地索引检索（中文子串 / 拼音 / 首字母）、结果权重排序 | `:core:database`, `:core:model` |
| `:feature:detail` | 详情页 | 上述 |
| `:feature:player` | 播放器页、进度条预览、字幕/音轨切换 | `:core:playback` |
| `:feature:settings` | 服务器管理、播放偏好 | 上述 |

依赖方向单向向下：`feature → source/core → core:model`。**`:source:*` 之间互不依赖**，保证新增源（如 Plex、SMB）只是加一个模块。

> `SourceFactory`（把 `ServerConfig` 装配成 `MediaSource`）需要**同时**知道 Emby 与 Jellyfin，按上面的规则不能放进任一 `:source:*`，因此落在 `:feature:server`。它是上层唯一需要知道的装配点：新增一种源时只改这里 + 加一个 `:source:*` 模块，UI 层不动。

---

## 5. 三类播放源的接入

### 5.1 Emby

- 鉴权：`POST /Users/AuthenticateByName`（body `Username` / `Pw`），成功返回 `AccessToken`；后续请求带 `X-Emby-Token` 头，客户端信息走 `X-Emby-Authorization`。
- 取库：`GET /Users/{UserId}/Views`（**媒体库根专用端点**）→ `CollectionType` / `ChildCount` 用于库 Tab 的类型与条目数。
  **不要**用 `Items + IncludeItemTypes=CollectionFolder` 代替：那会把非媒体库的普通文件夹一起扫进来。
- 取数：`GET /Users/{UserId}/Items`，参数 `ParentId / Recursive / IncludeItemTypes(Media) / Fields / SortBy / SortOrder / StartIndex / Limit / EnableImageTypes / ImageTypeLimit`。
  - 列表查询的 `Fields` **不含 `People`**（演职员数组在数万条的库上是响应体积与解析耗时的主要来源，会抵消 R5 的同步优化）；`People` 只在详情页取。
- 播放协商：`POST /Items/{Id}/PlaybackInfo`（带 `DeviceProfile`）→ 读取 `MediaSources[]` 的 `SupportsDirectPlay / SupportsDirectStream / SupportsTranscoding / TranscodingUrl`。
- 直连：`GET /Videos/{Id}/stream?static=true&MediaSourceId=&api_key=`。
- 转码：`GET /Videos/{Id}/master.m3u8`（HLS）。**PlaybackInfo 返回的 `TranscodingUrl` 是相对路径**，必须与 `baseUrl` 拼接后再交给播放器。
- 进度：`POST /Sessions/Playing`、`/Sessions/Playing/Progress`、`/Sessions/Playing/Stopped`。
- 图片：`GET /Items/{Id}/Images/Primary|Backdrop|Thumb`。**鉴权走请求头**（图片由 Coil 直接加载，可注入 header），避免令牌进入 URL 与磁盘缓存键；播放流仍用 `api_key`。
- 预览：trickplay（端点待 M0 实测）。
- 搜索：`GET /Search/Hints?SearchTerm=`（或旧接口 `/Items?SearchTerm=`）—— **对中文命中很差，仅作本地索引的兜底**（主通道见 §6.6）。

### 5.2 飞牛影视（Jellyfin 协议，`:8005`）

#### 授权（登录）方式

按飞牛官方文档整理，共 4 条可走路径：

| 方式 | 官方依据 | 说明 | 迭代归属 |
|---|---|---|---|
| ① 影视账号密码 | 飞牛影视 / 飞牛 TV 帮助文档：可手动填写「影视账号密码」登录 | 影视应用有**独立账号体系**，与 NAS 账号分离；最接近 Jellyfin 的 `Users/AuthenticateByName` | **M4 唯一必做路径** |
| ② 使用 NAS 账号登录（OAuth 式） | 官方原文：「NAS 账号在这里起到类似 **OAuth** 的作用」；「进入影视登录页，选择『使用 NAS 账号登录』，打开 NAS 授权登录页面，确认授权」 | 跳转 NAS 授权页 → 用户确认 → 换取影视侧凭据 | 后期迭代 **L1**（需内嵌 `WebView` 承载授权页并捕获回跳凭据） |
| ③ 扫码登录 | 飞牛 TV 帮助文档：「打开飞牛 App → 首页左上角头像 → 更多 → 扫码 → 飞牛 App 上**同意应用账号授权**」 | 服务端生成二维码 → 飞牛 App 扫码并确认 → 客户端轮询取凭据 | 后期迭代 **L1**（体验最好，但接口未公开） |
| ④ NAS 层「授权管理 / 一次性授权码」 | 官方文档《如何授权 AI Agent 访问 NAS》，需 fnOS **1.2.0800+**；授权页默认 HTTPS，IP 访问会提示自签证书 | NAS 账号登录 → 勾选功能 → 确认授权 → 复制**一次性授权码** | 后期迭代 **L1**（面向 NAS 能力授权，非影视专有） |

> **结论**：飞牛**没有公开的第三方影视开发 API 文档**。落地顺序已按需求调整为：
> - **M4 只做 ① 影视账号密码**，它最接近标准 Jellyfin 登录，是唯一必做路径。
> - **②③④ 三种授权式登录统一后置到「后期迭代 L1」**，主链路（浏览 / 播放 / 进度）跑通后再补，**不阻塞 M4**。
> - **Emby 恒为账号密码**（`Users/AuthenticateByName`），不涉及任何授权流程。

#### 可参考的实现（M0 抓包时的对照物）

- [`QiaoKes/fntv-electron`](https://github.com/QiaoKes/fntv-electron)：基于**飞牛影视 Web 端**构建的桌面客户端，含多账户管理、FN Connect / FN ID 登录、直链与 302 播放、进度回传 —— 说明**复用飞牛影视 Web 端接口**是可行路线。
- 社区维护的 [fnOS Web API 文档](https://fnosp.github.io/fnnas-api/)：系统层 `WebSocket + HTTP`，鉴权为 **Token + 签名**（非影视接口，但可用来理解 fnOS 的鉴权模型）。
- 第三方 TV 客户端 `fnos-tv` 的公开 Apifox 接口文档（含 NAS 登录相关接口）。
- 「飞鲸影视」等第三方 App 亦实现了飞牛授权，可作对照，但未检索到公开技术资料。

⚠️ 上述第三方资料均**非官方文档**，必须自行抓包验证；未经实测的接口不得写入生产代码路径。

#### 授权实现流程

> **M4 只实现 ①**；②③ 的流程保留在下方，作为**后期迭代 L1** 的实现依据，届时再抓包确认接口。

**① 影视账号密码（M4 唯一必做路径）**

1. 用户输入：`服务器地址(host:8005)` + 影视账号 + 密码。
2. `POST {base}/Users/AuthenticateByName`，body `{"Username":…, "Pw":…}`，请求头带 `X-Emby-Authorization`（Emby 惯例）或 `Authorization: MediaBrowser Token="…"`（Jellyfin 惯例）—— 两者在 M0 实测二选一。
3. 成功 → 取 `AccessToken` + `User.Id`，写入 `:core:datastore`（令牌字段加密存储）。
4. 失败分支：`401/403` → 提示账号或密码错误；连接超时 → 提示「地址不可达」；`404` → 提示「端口/路径前缀可能不对」。
5. 若实测发现飞牛鉴权头 / 路径与标准 Jellyfin 有差异，**只改 `:source:jellyfin` 的覆盖参数，不动 `:source:httpapi` 骨架**。

**② NAS 账号授权登录（OAuth 式）—— 后期迭代 L1**

1. 客户端用 `WebView` 打开 NAS 授权页（默认 HTTPS；自签证书需引导用户信任，见 R6）。
2. 用户在授权页勾选功能并「确认授权」→ 页面回跳到客户端**预登记的回跳地址** `myplayer://auth/callback`。
3. 在 `WebViewClient.shouldOverrideUrlLoading` 中捕获该回跳 URL，解析出授权码 / token。
4. 用凭据向影视端换取 `AccessToken`（换取接口在 M0 确认），后续与 ① 一致。

> 实现约束：回跳 scheme 必须在 `AndroidManifest.xml` 注册 `<intent-filter>`；`WebView` 仅承载授权页、**不注入任何脚本**；授权完成后立即清理 cookie 与历史，避免凭据残留。

**③ 扫码登录 —— 后期迭代 L1（接口未公开）**

1. 客户端请求影视端生成「登录二维码」，拿到二维码内容 + 轮询令牌 + 轮询间隔。
2. UI 渲染 QR；按服务端返回的 `interval`（缺省 2s）轮询，总超时 2 分钟。
3. 飞牛 App 扫码并「同意应用账号授权」→ 轮询返回凭据 → 换取 `AccessToken`。
4. 轮询状态机须区分：`等待扫码 / 已扫码待确认 / 已确认 / 已过期`，并映射到对应 UI 文案（过期给「刷新二维码」按钮）。

> 若 M0 无法获取扫码相关接口，则本路径**暂缓**，不影响 ① 的可用性。

**设备识别（区分飞牛 vs 标准 Jellyfin）**

- 连接时先探测 `{host:8005}/System/Info/Public`，读取 `ServerName / Version / ProductName`。
- `ProductName` 含 `fnOS / 飞牛` → 走 `:source:jellyfin` 的差异化配置；否则按标准 Jellyfin 处理。
- **不做局域网自动发现**：同一网络下 Emby / Jellyfin 可能互相干扰扫描，统一以用户**显式添加的源类型**为准。

#### 接口差异（相对 Emby）

- 与 Emby 共用同一套端点与模型；差异点：
  - **路径前缀与端口**（默认 `8005`，是否有前缀待 M0 确认）。
  - 鉴权头：Jellyfin 惯例为 `Authorization: MediaBrowser Token="..."`，Emby 为 `X-Emby-Token`；适配层按实测选择并统一。
  - `PlaybackInfo` 的 `DeviceProfile` 字段名与转码参数命名可能不同。
- **实现策略**：不复制 `:source:emby` 的代码。抽出 `:source:httpapi`（共享的 REST 调用/序列化/鉴权骨架），Emby 与 Jellyfin 模块各自继承并覆盖差异参数。
- 与 Emby 一致的降级策略（见 §6.5）。

### 5.3 本机视频文件（后期迭代 L2）

> **迭代归属**：本章内容**不在 M1–M5 范围内**，放到后期迭代 **L2** 实现。M1–M5 只需要保证 `MediaSource` 抽象与 `:core:model` 的 `SourceType.LOCAL` / `ImageSource.Local` 已就位，届时新增 `:source:local` 模块即可，无需改动上层。

- **枚举**：
  - API 33+：`READ_MEDIA_VIDEO`；API ≤32：`READ_EXTERNAL_STORAGE`。通过 `MediaStore.Video.Media` 查询标题/时长/大小/宽高/路径。
  - 超出 MediaStore 范围的目录（SD 卡、自定义目录）：用 SAF `ACTION_OPEN_DOCUMENT_TREE`，持久化 URI 授权（`takePersistableUriPermission`），把用户选中的目录作为「本地媒体库」记录在 DataStore。
- **播放**：`PlaybackPlan.Direct`，URI 为 `content://`（MediaStore 或 SAF），**无服务器、无转码**。
- **元数据**：从 MediaStore 字段构造 `MediaItemDto`；海报用 `MediaMetadataRetriever` 抽帧 / 读内嵌封面并缓存。
- **外挂字幕发现**：同目录同名 `.srt/.ass/.ssa/.vtt`。SAF 目录用 `DocumentFile.listFiles()` 枚举同胞文件；MediaStore 目录用 `RELATIVE_PATH + DISPLAY_NAME` 反查。
- **进度**：仅存本地 Room，`reportProgress` 为空实现。
- ⚠️ **关键代价**：本地文件**没有服务器转码兜底**。NAS 常见的 DTS/TrueHD 音轨、ASS 精细字幕在手机端可能无法解码/还原。应对方案（见 §12-R2）。

---

## 6. 关键机制（差异化重点）

### 6.1 进度条实时缩略图预览（痛点 2）

1. 播放前查询 `getTrickplay(item, width)`：
   - 服务端可用 → 拉取 `tiles.m3u8`，客户端解析出**精灵图分片**地址列表（不需要逐个请求 jpg）。
   - 服务端不可用 / 本地源 → 返回 `null`，进入降级路径。
2. 拖动时：`时间 → (分片序号, 图内行列偏移)`，从已解码的 bitmap 缓存中直接切片渲染，**零网络请求、零解码延迟**。
3. 预取：播放开始后后台预取前后各 N 个分片，LRU 缓存（复用 Coil 的 `MemoryCache`，避免重复造轮子）。
4. 降级：时间气泡 + 若为用户开启项则在本地用 `MediaMetadataRetriever` 按固定间隔抽帧，缓存到应用私有目录。

> 交互规格（浮层尺寸、节流、tap/drag 提交策略）见 §7.3。

### 6.2 无卡顿 seek（痛点 2）

定位：Emby 官方 App 的 1 秒卡顿**大概率来自转码流重启**，而非「点击进度条」本身慢。因此：

1. **强制直放优先**：`PlaybackNegotiator` 先按客户端能力（`MediaCodecList` 探测的解码器 + 屏幕 HDR/刷新率 + 网络带宽）请求 `PlaybackInfo`，能 `DirectPlay` 就不转码。
2. **乐观更新**：进度条点击 → 立即更新 UI 位置 → 异步发起 seek，UI 不等网络。
3. **关键帧对齐**：`SeekParameters.CLOSEST_SYNC` + `setAllowWaitForFirstFrame`；避免精确 seek 触发大量解码。
4. **分段而非重启**：必须转码时用 `StartTimeTicks` 请求新分段，而不是整路重启转码会话。
5. **缓冲策略**：`DefaultLoadControl` 调参（增大 `bufferForPlaybackAfterReblockMs`、`maxBufferMs`），配合慢速回退；本地 URI 与局域网直连走 `ProgressiveMediaSource`，seek 是纯本地字节跳转。

> 交互规格（tap/drag 提交策略）见 §7.3。

### 6.3 统一排序与筛选（痛点 3）

- 库同步：分页拉全量 `MediaItemDto` 落 Room（前台可见页优先，后台增量补全）；**同步落库的同时构建搜索索引**（见 §6.6）。
- `SortEngine` 本地排序维度：**时长、文件大小、码率、分辨率、添加时间、评分、播放次数、首播年份、标题**；支持主序 + 次序（如「时长降序 + 标题升序」）。
- 筛选：类型（电影/剧集/集数）、演员、年份区间、分辨率区间、未看/已看/在看、收藏、源（Emby/飞牛/本地）。
- 排序在本地瞬时完成，UI 无网络等待 —— 这正是飞牛 App 做不到的。
- 服务端仍可承担部分排序（`SortBy=Runtime` 等）以减少同步量，但**客户端排序是唯一事实来源**。

### 6.4 进度同步

- 服务端源：播放中每 10s 上报 `/Sessions/Playing/Progress`；暂停/退出/切后台立即上报；开始播放调 `/Sessions/Playing`。
- 离线或上报失败 → 写入 Room 待补队列，恢复网络后批量补报。
- 前台服务 `MediaSessionService` + 通知栏控制 + 画中画；避免系统杀进程导致进度丢失。

### 6.5 播放协商（直放优先、转码兜底）

```
planPlayback(item, caps):
  1. 取 PlaybackInfo（含 DeviceProfile，声明支持的容器/视频/音频/字幕能力）
  2. 若 SupportsDirectPlay                     → PlaybackPlan.Direct(原文件流)
  3. 若 TranscodingUrl 非空                     → PlaybackPlan.Transcode(
                                                   baseUrl + TranscodingUrl,   // 服务端给的是相对路径
                                                   isRemuxOnly = SupportsDirectStream)
  4. 若 SupportsTranscoding 但无 TranscodingUrl  → PlaybackPlan.Transcode(
                                                   自拼 /Videos/{Id}/master.m3u8?...,
                                                   isRemuxOnly = false)
  5. 以上都不满足                                → 抛错 → §7.8 的错误态
  LOCAL 源                                      → 恒为 Direct(content://)
```

- **第 5 档不得「兜底成直连」**：服务端明确声明既不支持直放也不支持转码时，把原文件流硬塞给播放器只会失败，而且失败原因极难定位（表现为「某些视频就是播不了」）。宁可明确报错并展示诊断信息。
- **字幕**优先客户端渲染（避免烧录），无法渲染的格式（如需样式还原的 ASS）才考虑服务器烧录。
- 用户可手动强制「直放」/「转码」，便于排查与体验对比。

### 6.6 搜索（中文友好）

> 背景：Emby 官方 App 的搜索依赖服务端 `SearchHints` / `SearchTerm`，其索引按「词」做前缀 / 包含匹配，**对中文基本无效**——中文无空格切分，服务端多数只索引 `SortName`（常为拼音或去冠词的英文）。因此搜索必须**以客户端本地索引为主**，服务端搜索仅作兜底。本地源（§5.3）无服务端可查，只走本地索引。

**双通道策略**

| 通道 | 数据 | 用途 |
|---|---|---|
| 主：本地索引（Room） | 同步落库时构建的中文友好索引 | 默认通道，支持中文任意位置子串、拼音全拼、拼音首字母 |
| 辅：服务端 `SearchHints`（`/Search/Hints?SearchTerm=`；旧接口 `/Items?SearchTerm=`） | 服务端索引 | 本地索引未命中 / 首次同步未完成时兜底 |

**本地索引构建（同步时生成）**

- 字段来源：标题、原始标题 / 别名、系列名、演员、导演、类型、标签、工作室、简介（截断）。
- 归一化：`NFKC`（全角→半角）+ `lowercase` + 去标点空白；可选繁→简（设置项可开关）。
- 拼音：全拼（`三体` → `santi`）+ 首字母（`st`），用轻量库 **TinyPinyin（Apache-2.0）**（纯查表、无 NDK、体积小）。
- **中文逐字分词**：在 CJK 字符之间插入空格后再写入 FTS 列（如 `三 体`），配合 SQLite **FTS4 `unicode61`** 把每个汉字切成独立 token —— 从而支持**任意位置子串命中**，不依赖空格、不依赖前缀。

**查询流程**

1. 用户输入 → 同样归一化 → 生成多路 query：原始子串、拼音全拼、拼音首字母，并将中文按字切分。
2. 合并多路结果并去重。
3. 排序权重：**标题 > 别名 / 原名 > 系列 > 演员 / 导演 > 类型 / 标签 > 简介**；同权重再按 **播放次数 / 添加时间**。
4. 输入 debounce 200ms、输入即搜；用 `Job.cancel()` 丢弃过期查询，避免结果错序。
5. 结果是统一 `MediaItemDto`，复用库页网格与 §6.3 排序 / 筛选；搜索入口位于库页顶部搜索框。

**实现取舍（Room）**

- 个人使用的库量级（数千~数万条）：优先用 **FTS4 虚表**（`unicode61` + 逐字分词），查询快、支持子串，标题短则索引体积可控。
- 兼容降级：在普通表上保存含拼音的 `searchText` 列，用 `LIKE '%…%'` 匹配——数万条规模实测可接受，实现最简单。
- **决策**：默认走 FTS4；`LIKE` 作为异常 / 不兼容时的降级路径。

**可选增强（设置项，默认关）**

- 「模糊音」：`zh↔z`、`ch↔c`、`sh↔s`、`n↔l`、前后鼻音（`ang↔an` 等）—— 额外生成一份模糊音拼写入索引。
- 「繁体 / 简体互搜」：开启繁简互转后，繁体查询也能命中简体库。

**降级与状态**

- 首次同步未完成 → 回退服务端 `SearchHints`，结果顶部提示「本地索引仍在构建，结果可能不全」。
- 两通道均无结果 → 空态 + 「若为服务器源，请确认服务端搜索已开启；或稍后重试」。
- 中文命中差是**服务端固有缺陷**，因此**不做**「优先信服务端搜索」的策略，一律以本地结果为准。

---

## 7. 播放器页 UI / 交互规格

> 本章是痛点 2（预览 + 卡顿）的实现规格，§6.1 / §6.2 的机制在此细化为可实现的交互。

### 7.1 页面状态

| 状态 | 说明 | 进入方式 |
|---|---|---|
| 沉浸 Idle | 全屏视频，控制层隐藏 | 进入播放页 / 控制层 3s 无操作 |
| 控制层 Controls | 顶部 + 底部控制层可见 | 单击画面 |
| 拖动预览 Scrubbing | 进度条按下/拖动，预览浮层显示 | 按下进度条 |
| 设置抽屉 Sheet | 字幕 / 音轨 / 倍速 / 画面比例 | 底部控制层按钮 |
| 锁定 Locked | 隐藏控制层并屏蔽全部手势 | 控制层「锁定」按钮 |

### 7.2 布局

```
横屏（默认 sensorLandscape）
┌──────────────────────────────────────────────┐
│ ←  标题 / 集数                                │  顶部控制层
├──────────────────────────────────────────────┤
│                                              │
│        ⟲10s          ⏯          10s⟳        │  中部控制
│                                              │
├──────────────────────────────────────────────┤
│   ┌───────── 预览浮层 ─────────┐              │
│   │  [sprite 切片 160×90dp]     │              │
│   │        01:23:45             │              │
│   └────────────────────────────┘              │
│  ━━━━━━━━━━━●━━━━━━━━━━━━━━━━━━━━            │  进度条
│ 01:23:45          ⟨设置⟩            2:04:10  │  底部控制层
└──────────────────────────────────────────────┘
```

- **常驻控件极简**：底部控制层只保留「进度 + 时间 + 播放/暂停 + 快进快退 + 一个⟨设置⟩按钮 + 全屏/锁定」。字幕 / 音轨 / 倍速 / 画面比例**统一收进 ⟨设置⟩ 弹出的一个底部弹层**，不并排平铺按钮（§14.0 简洁原则）。
- **横竖屏跟随手机旋转自动切换**：播放页默认 `sensor`（跟随系统自动旋转）；设置中提供「自动 / 仅横屏 / 仅竖屏」三态默认值，播放页可临时锁定。
- 旋转时**不重建播放器实例**：保持 `ExoPlayer` 与 `MediaSession` 存活，仅重排布局，避免卡顿与进度丢失。
- 实现要点：播放 Activity 声明 `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"`，由 Compose 布局自行响应尺寸变化，**不走系统 Activity 重建**；仅在用户选择「锁定」时才调用 `setRequestedOrientation`，其余交给系统 `sensor`。
- 旋转后恢复：保留播放位置、字幕 / 音轨 / 倍速选择与控制层显隐状态；若旋转时正在拖动进度条，则中断拖动但不打断播放。
- 竖屏：视频 16:9 居中，下方留信息区（标题 / 集数 / 简介），复用同一控制层组件。
- `WindowInsets` 处理刘海 / 挖孔，视频在切口区域外绘制。

### 7.3 进度条与预览条（核心规格）

**进度条**
- 轨道高度 3dp，按下后展开为 6dp。
- 分段配色：已播放=品牌色；已缓冲=中性加深；未播放=`border`。
- 触摸热区高度 32dp，外层 padding 保证可点击区域 ≥48dp。

**预览浮层（trickplay）**
- **触发延迟 ≤100ms**：按下即显示，使用已预取的分片，**不发起新网络请求**。
- 切片尺寸 160×90dp，圆角 `--radius`；时间戳等宽字体 12sp。
- 定位：水平跟随触点，左右各内缩 16dp，越界贴边；垂直固定在进度条上方 12dp。
- 更新节流：拖动中位移 ≥8dp **或** ≥60ms 才刷新一次预览位，避免每帧重绘。
- 切片换算：`tileIndex = floor(timeMs / tileIntervalMs)`，`(col, row)` 由 `tiles.m3u8` 的列数与切片宽高反算。

**提交策略（关键，直接对应痛点 2）**

| 操作 | 行为 |
|---|---|
| 轻点 tap | **立即** seek，UI 位置**乐观更新**，不等网络 |
| 拖动 drag | 拖动全程**不 seek**，只更新预览；**松手（ACTION_UP）才提交一次 seek** |
| 长按 | 2× 临时倍速，松手恢复 |

> 理由：拖动过程中反复起流正是「点进度条卡 1 秒」的根因；commit-on-release 把 N 次起流压缩成 1 次，配合 §6.5 的直放优先，seek 退化为本地字节跳转。

**降级**：服务端无 trickplay → 只显示时间气泡；若用户在设置里开启「本地生成缩略图」，则改为按需抽帧（优先复用已缓存帧）。

### 7.4 手势

| 手势 | 行为 |
|---|---|
| 单击 | 切换控制层显隐 |
| 双击左 / 右 | 快退 / 快进（可配置 5s / 10s / 30s，默认 10s） |
| 左侧上下滑 | 亮度 0–100%，带渐变指示器 |
| 右侧上下滑 | 音量 0–100% |
| 水平滑 | seek（显示预览，松手提交） |
| 长按 | 2× 临时倍速 |

- 手势方向锁定：起始 24dp 内位移角 > 30° 判为纵向，否则判为横向。
- 锁定态（Locked）下全部手势禁用。

### 7.5 字幕 / 音轨 / 倍速 / 画面比例

- 入口：底部控制层按钮 → `ModalBottomSheet`。
- 字幕列表 = 内嵌轨（Media3 `Tracks.Group`）+ 外挂轨（本地源自动发现同目录同名 `.srt/.ass/.ssa/.vtt`；服务端源取 `MediaStreams` 的 Subtitle 轨）+「关闭」。
- 字幕样式：字号 / 颜色 / 描边色与宽度 / 底部边距 → 映射 Media3 `CaptionStyleCompat`。
- 音轨：列出语言 / 声道 / 编码并支持切换；不支持切换的源置灰并给说明文案。
- 倍速：0.5×–3.0×，步进 0.25×，记忆偏好。
- **全局默认倍速（新增痛点 5）**：设置页提供「默认播放倍速」，**新建播放会话时自动套用**，无需每次手动调整（对标 Emby 官方 App 的缺失）。播放页临时改速只影响**本次会话**，退出后回落到全局默认值；长按画面的 2× 属临时效果，不写入默认值。
- **音调补偿**：变速时用 `PlaybackParameters(speed, pitch = 1.0f)` 保持音调不变尖 / 不闷；内核不支持时回退为默认音调。
- **与转码会话的关系**：直放与本地源倍速为纯客户端行为；服务端**转码流**倍速可能不受支持或需重开转码会话（带 `Speed` 参数）。某源不支持时，倍速按钮置灰并给说明文案，不静默失效。
- **作用域**：全局默认值只决定「新建会话的初始倍速」，不跨会话共享进程内临时调整。
- 画面比例：适应 / 填充 / 裁剪 / 原始 / 自定义缩放。

### 7.6 连播与浮层

- 剧集播放至 **90%** 显示「下一集」浮层：`下一集 + 10s 倒计时`，自动连播可关闭。
- 提供「上一集 / 下一集」按钮。
- 播放结束：回列表并置 `userData.isPlayed`。

### 7.7 系统集成

- 屏幕常亮：播放中 `FLAG_KEEP_SCREEN_ON`，暂停 30s 后释放。
- 画中画：`onUserLeaveHint` 进入 PiP（16:9），Android 13+ 用 `setAutoEnterEnabled`。
- 通知栏：`MediaSessionService`，含封面 / 进度 / 上一集 / 下一集 / 快进快退 15s。
- 音频焦点：来电或其它应用抢占 → 自动暂停，恢复后提示继续。
- **后台行为（默认）**：切到后台 / 锁屏 / 切走立即**暂停播放**；「保持后台播放」为设置项且**默认关闭**；与 §8 的应用锁配合（回前台先解锁再恢复播放）。进入最近任务时任务快照显示**当前播放内容**，返回后默认停在当前播放内容（详见 §8.2）。
- 锁屏：`MediaSession` 元数据（标题 / 海报 / 时长）。

### 7.8 错误与降级态

| 情况 | UI 表现 | 行为 |
|---|---|---|
| 直放失败 | 顶部小卡片「直放失败，正在切换转码…」 | 自动按 §6.5 降级重试 |
| 转码失败 | 全屏错误卡片 + 错误码 + 「重试」 | 写入诊断日志 |
| 服务器离线 | 断网占位 + 「重试」 | 恢复后自动重连 |
| 资源不存在（404） | 「该视频已不存在」 | 提供「从库中移除」 |
| 无可用音轨 / 字幕 | 按钮置灰 + 说明文案 | — |

### 7.9 平板与折叠屏差异

- 平板横屏：控制层左右内缩 48dp（防边缘误触），预览切片放大到 200×112dp。
- 分屏 / 折叠屏：由 `WindowSizeClass` 驱动布局切换，不新增专用代码路径。

---

## 8. 应用锁与后台行为

> 明确**不做服务器账号体系**（§1.3）。本章的「锁屏密码」指**应用级本地锁**，用于保护隐私（媒体库、海报、播放记录），完全不涉及任何账号。

### 8.1 应用锁（默认开启）

- **默认方案：安卓九宫格划线图案锁（Pattern）** —— 3×3 九点连线，至少连接 4 个点；保存时做弱图案校验（拒绝「Z / L / 直线」等过于简单的连线）。
- **触发时机**：① 冷启动进入 App；② 从后台回到前台（超过可配置宽限期，**默认立即**）；③ 手动触发「立即锁定」。
- **解锁方式**（图案为默认，可叠加）：
  - 九宫格图案（默认）
  - PIN 码（4–8 位）
  - 生物识别（指纹 / 人脸）作为**快捷解锁**，失败自动回落图案
- **凭据存储**：图案 / PIN **不以明文保存**。密钥交由 Android **Keystore** 保护，凭据经 `PBKDF2`（或 Argon2）加盐派生后存入 `EncryptedFile`；图案仅保存轨迹的哈希，无法反推。
- **暴力尝试防护**：连续 5 次失败起递增冷却（30s → 1min → 5min），失败次数与时间戳加密存储。
- **找回路径**：设置时可配置一个「备用 PIN」；未配置则仅能「清除应用数据」重置（设置页需明确警告）。
- **实现**：自研 Compose 九宫格组件（`Path` + 点命中距离判定），不引入 GPL 第三方锁库。

### 8.2 后台行为（默认暂停）

- **默认：切到后台立即暂停播放**（锁屏、切到其它应用、进入最近任务均视为后台）。理由：省电、省流量、避免隐私泄露。
- 设置项「保持后台播放」**默认关闭**；开启后由 `MediaSessionService` 前台服务 + 通知栏 + 画中画承载，并遵循 §8.1 的回前台解锁规则。
- **与应用锁的交互**：若应用锁开启，回前台后**先解锁、再恢复播放**，避免在锁屏界面直接暴露播放控制。
- **进度不丢**：暂停即上报进度（§6.4），退出应用时也补报一次。

#### 最近任务（Recents）行为

- 进入最近任务（总览）时，播放页**保留为任务快照内容**：任务卡片默认显示**当前播放内容**（画面 / 封面 + 标题），而不是库列表或启动页。
- 从最近任务或后台返回 App 时，**默认回到播放器页并停在当前播放内容**：已暂停则显示「继续播放」，**不跳回库列表 / 启动页**。
- 任务快照策略：默认**保留当前播放内容**；设置项「后台遮挡任务快照」默认关闭，仅在需要隐私保护时才开启遮挡（见 §8.1 应用锁）。
- 与自动暂停的配合：进入后台即暂停（保留进度）；返回时若应用锁开启则**先解锁、再恢复同一播放页的当前内容**。
- 实现要点：播放页保持为**顶栈 Activity**，不因切后台而 `finish` 销毁；不主动关闭系统任务快照（不调用 `setRecentsScreenshotEnabled(false)`）；PiP 与最近任务快照由系统行为承载（§7.7）。

### 8.3 与权限的关系

- 应用锁不需要任何运行时权限；`Keystore` 与 `EncryptedFile` 亦无需权限。清单见 §10。

---

## 9. 技术栈

| 关注点 | 选择 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose + Material 3（`WindowSizeClass` 适配平板） |
| 架构 | 多模块 + Clean Architecture + MVVM + 单向数据流（`StateFlow`） |
| DI | Hilt |
| 本地存储 | Room（元数据/索引/进度）+ DataStore（配置/账号/令牌） |
| 网络 | OkHttp + kotlinx.serialization（REST 骨架共享） |
| 图片 | Coil（含内存/磁盘缓存，复用为 trickplay 分片缓存） |
| 播放 | Media3：`media3-exoplayer` + `media3-exoplayer-hls` + `media3-session` + `media3-ui` |
| 解码扩展 | FFmpeg 音频软解扩展（覆盖 DTS/TrueHD/AC3 等，见 R2） |
| 许可 | Apache-2.0 为主；FFmpeg 相关为 LGPL，**开源项目中需在 about 页声明** |

---

## 10. 权限与合规

- `READ_MEDIA_VIDEO`（API 33+）/ `READ_EXTERNAL_STORAGE`（≤32），仅在用户启用「本机视频」时申请。
- SAF 目录授权使用持久化 URI 权限，不申请 `MANAGE_EXTERNAL_STORAGE`。
- `POST_NOTIFICATIONS`（API 33+）用于媒体通知；`FOREGROUND_SERVICE_MEDIA_PLAYBACK`。
- 局域网明文 HTTP：为支持自建 NAS，需 `usesCleartextTraffic` 或按域白名单（security-config），并在设置中明确提示风险。
- 仅个人使用 / 开源，不内置任何内容源；不做账号体系。

---

## 11. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| **M0 验证（最高优先）** | ① 抓包飞牛影视 `:8005`，确认路径前缀、鉴权头、`PlaybackInfo` 与标准 Jellyfin 的差异；② 验证飞牛**影视账号密码登录**（§5.2 路径 ①，唯一必做）；③ 实测 Emby 与飞牛的 trickplay 端点 | 输出「接口差异对照表」；`:source:jellyfin` 的登录与请求参数清单确定 |
| M1 骨架与 Emby 源 | 多模块 + 领域模型 + `:core:network` + `:core:security` + `:source:emby`；**添加服务器页（§17：选源 → 填地址 → 探测 `/System/Info/Public`）与登录页**、`SourceFactory` 装配、导航接线；库列表 / 条目列表 / 详情 / 图片；Room 缓存与同步；**同时落地应用锁（默认九宫格图案）**（§8） | 首启引导 → 添加 Emby 服务器并登录 → 看到库 Tab 与海报网格；杀进程重开无需重新添加；冷启动 / 回前台需解锁，且配置与令牌在磁盘上为密文 |
| M2 播放闭环 | `:core:playback`：直放 + 转码兜底 + 进度上报（`/Sessions/Playing*`）+ 通知栏/画中画 + **切后台自动暂停**（§8.2） | 手机端能播放 Emby 内容，进度与服务端同步；切后台暂停、回前台先解锁再恢复 |
| M3 差异化 | 进度条缩略图预览 + 无卡顿 seek 策略 + 本地排序/筛选 UI + **中文友好搜索**（§6.6，播放器交互按 §7 实现） | 痛点 2、痛点 3 与中文搜索场景通过自测 |
| M4 飞牛影视 | `:source:jellyfin`（基于 `httpapi` 共享骨架）接入，**登录方式仅「影视账号密码」**（§5.2 ①） | 与 Emby 同等功能 |
| M5 打磨与设置 | 平板布局、错误态与重试、多服务器管理、设置页（含全局默认倍速、应用锁项、主题）、关于页许可声明 | 可日常使用 |
| **L1 后期：飞牛授权登录** | ② NAS 账号授权登录 / ③ 扫码登录 / ④ 一次性授权码（§5.2，需届时抓包） | 在不影响账号密码登录的前提下补强登录体验 |
| **L2 后期：本机视频文件** | `:source:local`（MediaStore + SAF）+ 外挂字幕发现 + 本地缩略图生成（§5.3） | 能播放手机本地视频，与服务端条目混排排序 |

> 建议顺序：M0 → M1 → M2 先跑通**一条源（Emby）**的完整链路，再横向复制到飞牛（M4）。**L1 / L2 属于后期迭代，不阻塞 M0–M5**。

---

## 12. 风险与未决问题

| 编号 | 风险 | 影响 | 应对 |
|---|---|---|---|
| **R1** | 飞牛影视无公开 API 文档，`:8005` 行为可能偏离标准 Jellyfin（路径前缀 / 鉴权 / 转码参数） | 阻塞 M4 | M0 提前抓包验证；`:source:httpapi` 骨架预留差异化覆盖点；若差异过大则退化为独立实现 |
| **R2** | **本地文件无服务器转码兜底**，NAS 常见 DTS/TrueHD 音轨、ASS 精细字幕在手机端可能无法还原 | 影响「部分视频无法播放」这一原始痛点 | ① 内建 FFmpeg 音频软解扩展；② 预留可切换的 **libmpv 播放内核**作为「极限兼容模式」（仅本地/特殊文件启用）；③ UI 明确提示不支持项 |
| **R3** | 服务端未开启 trickplay | 预览条不可用 | 优雅降级（时间气泡 + 可选本地抽帧），并在设置里给出开启 trickplay 的引导文案 |
| **R4** | Emby 与 Jellyfin 的 trickplay 端点/参数不完全一致 | 预览在某一源失效 | M0 实测；`getTrickplay` 由各适配器自行实现，返回 `null` 即降级，不污染领域层 |
| **R5** | 大媒体库同步（数万条）耗时与内存 | 首屏体验 | 分页 + 增量同步 + Room 索引；列表分页渲染；只在需要排序的字段上建索引 |
| **R6** | 明文 HTTP / 自签证书的兼容 | 连不上自建服务器 | 支持用户信任自签证书 + 明文开关（默认关闭，需显式开启） |
| **R7** | Media3 对部分 ASS 字幕样式还原不足 | 动漫类体验 | 客户端渲染基础样式；必要时服务端烧录；R2 的 libmpv 兜底 |
| **R8** | 飞牛影视**无公开第三方 API 文档**；②③④ 授权式登录接口未知 | **不影响 M4**（M4 只用账号密码）；仅影响后期 L1 | M0 只需确认 ① 账号密码登录的路径前缀与鉴权头（以 `fntv-electron` 等为对照）；②③④ 留到 L1 再抓包，届时不可得则 L1 整体暂缓 |
| **R9** | 中文搜索依赖客户端本地索引与拼音库（服务端 `SearchHints` 对中文几乎无效） | 搜索体验 | 同步时构建 FTS4 / `searchText` 索引；拼音用 **TinyPinyin（Apache-2.0）**；服务端搜索仅兜底，索引未就绪时明确提示 |

### 未决问题

1. 飞牛影视 `:8005` 是否支持多用户与权限（普通用户受限媒体库）？—— M0 确认。
2. trickplay 宽度档位与服务端配置如何映射（`width` 取 320 / 240 / 160）？—— M0 确认。
3. ~~是否需要在引入本地文件之前就引入 libmpv 兜底内核~~ → **已决策：留到 L2 再定**。M0–M5 只做 FFmpeg 音频软解扩展；**L2 接入本地文件后**，按真实遇到的不兼容文件数量决定是否引入 libmpv 兜底内核。所需预留：`:core:playback` 把播放内核抽象为 `PlaybackEngine` 接口，使 L2 可平行加入 libmpv 实现而不影响上层。
4. ~~飞牛授权最终采用哪条路径~~ → **已决策**：**M4 只用 ① 影视账号密码**；②③④ 后置后期迭代 **L1**（§5.2）。M0 只需确认 ① 的路径前缀、鉴权头与失败分支。

---

## 13. 测试策略

- **单元测试**：`SortEngine`（各排序维度 + 组合 + 空值/缺失字段）、`SearchEngine`（中文子串 / 拼音全拼 / 首字母 / NFKC 归一化 / 权重排序）、`PlaybackNegotiator` 的降级决策表（含「都不支持 → 报错」一档）、`Ticks` 与 ms 的互转、**服务器地址归一化与登录错误分支映射**、DTO 映射（用 Emby / Jellyfin / MediaStore 的样例 JSON 与 fake cursor 驱动）、trickplay 时间→分片换算。
- **集成测试**：`:source:*` 各适配器对本地 mock server（MockWebServer）的请求构造与解析（含取库走 `/Views`、列表不请求 `People`、转码相对路径被拼成绝对 URL）。
- **UI 测试**：**添加服务器 → 测试连接回显 → 登录 → 进库页**的完整首启流程、库浏览排序 / 筛选交互（§14.1）、搜索页输入即搜与状态机（§14.2）、详情页主按钮文案与剧集选择（§14.3）、播放器页进度条拖动（Compose 测试）、九宫格图案锁的命中与解锁流程。
- **专项测试**：应用锁（图案 / PIN 派生与校验、失败冷却、Keystore 存取、回前台解锁）、全局默认倍速（新会话自动套用且不污染全局值）、旋屏不重建播放器实例、最近任务（快照显示当前播放内容、返回后停在当前播放内容、应用锁开启时先解锁再恢复）。
- **人工验收**：真机对 Emby / 飞牛 / 本地三类源各跑一遍痛点场景（§1.1 的 2、3、6，其中中文搜索为新增验收项）。

---

## 14. 库浏览 / 搜索 / 详情页 UI 规格

> 本章补齐痛点 3（排序 / 筛选）与中文搜索（§6.6）的界面规格，与 §7 播放器页规格并列。

### 14.0 设计系统（视觉基线）

> 目标是「无广告、界面精致」，先固定一套 token，所有页面与组件只引用 token，不写死数值。

- **简洁轻便（首要原则）**：页面上只保留必要控件，一切能藏进手势、长按、底部弹层的操作都不常驻在屏幕上。具体约束：
  - 库页常驻控件仅「源切换 / 搜索 / 库 Tab / 排序筛选入口」，排序 + 筛选合并为**一个**入口按钮，不做常驻筛选条。
  - 播放页控制层只用一行底部控制 + 一行顶部信息；字幕 / 音轨 / 倍速 / 画面比例全部收进**一个**底部弹层，不并排平铺按钮。
  - 详情页只留一个主按钮（播放 / 继续播放）+ 少量图标操作（收藏、标记已看）；技术信息折叠在展开区内。
  - 设置页用分组列表，二级设置进独立页；不在首页铺开关。
  - 删除一切装饰性元素（无广告位、无推荐位、无冗余文案）。
- **主题（只做两套）**：**亮色 + 暗色**，不做动态取色 / 多套色板 / 主题商店。默认**跟随系统**（深色优先，观影场景），设置里可手动切「跟随系统 / 亮色 / 暗色」三态。实现上就是一份 `colorSchemeFor(isDark)`，无额外主题层。
- **颜色 token**：`color-brand`(主色) · `surface` · `surface-variant` · `on-surface` · `border` · `scrim`(遮罩) · `success / warning / danger`（Material 3 语义色映射）。
- **字体**：`display` 24sp · `headline` 20sp · `title` 16sp · `body` 14sp · `label` 12sp；中文字重与字距按 MiSans / 思源体系适配。
- **间距**：4 / 8 / 12 / 16 / 24dp 阶梯；页面左右安全边距 16dp（平板 24dp）。
- **圆角**：8dp（控件 / 标签）· 12dp（卡片 / 浮层）· 999dp（胶囊）。
- **海报比例**：库与搜索网格 2:3；剧集缩略图 16:9。
- **动效**：**所有交互都必须带轻量微动画**，规格见下方「微动画基线」，无例外。
- **触控目标**：所有可点元素热区 ≥ 48dp。

**色板（Material 3 角色，二套主题）**

| 角色 | 浅色 | 深色 |
|---|---|---|
| primary（品牌） | `#0FDC78` | `#32F08C` |
| onPrimary | `#004D26` | `#004D26` |
| primaryContainer | `#E5FFF2` | `#004D26` |
| background | `#FFFFFF` | `#0E0E10` |
| surface / surfaceVariant | `#F7F7F8` / `#EFEFF2` | `#171717` / `#262626` |
| onSurface / onSurfaceVariant | `#171717` / `#52525B` | `#E5E5E5` / `#A1A1AA` |
| outline（边框/分割线） | `#E4E4E7` | `#2E2E2E` |
| scrim（遮罩） | `rgba(0,0,0,0.6)` | `rgba(0,0,0,0.6)` |
| success / warning / danger | `#1DC981` / `#EFAA17` / `#E8463A` | 同左 |

**组件规范**

- **海报卡**：2:3；圆角 12dp；标题区叠 24dp 底部 scrim 保证可读性；观看进度为 3dp 品牌色底条。
- **按钮**：主按钮 filled、高 48dp；次按钮 outlined；文字按钮用于低强调操作。
- **Chip / 标签 / 库标签**：高 32dp、圆角 999dp；选中态用 `primaryContainer` 填充 + 品牌色描边。
- **BottomSheet（排序 / 字幕 / 音轨 / 倍速）**：顶部圆角 16dp；`scrim` 遮罩；标题 16sp + 关闭按钮。
- **列表行（设置 / 详情）**：高 56dp；标题 16sp、副标题 12sp；尾部按需为 `Switch` / `Chevron` / 值文本；分割线左右 inset 16dp。
- **图标**：24dp 线性图标，线宽 1.5–2；默认 `onSurfaceVariant`，选中 `primary`。

**微动画基线（所有交互必须有动画）**

> 需求：按钮操作、菜单展开等**所有功能**都要加动画，且一律是**轻便微动画**。下表为强制基线，实现时不得省略。

| 交互 | 动画 | 时长 / 曲线 |
|---|---|---|
| 按钮按下 / 松开 | 缩放 `1.0 → 0.96`，松开回弹 | 90ms / 150ms，`spring(dampingRatio = 0.7)` |
| 图标按钮（播放·暂停·收藏） | 尺寸 `1.0 → 1.15 → 1.0` + 颜色过渡 | 180ms，`FastOutSlowInEasing` |
| 底部弹层展开 / 收起 | 自底部滑入 + `scrim` 淡入；高度变化用 `AnimatedContent` 平滑 | 250ms 入 / 200ms 出 |
| 菜单 / 下拉 / 排序选项浮层 | 缩放 `0.92 → 1.0` + 透明度 `0 → 1` | 150ms，`FastOutSlowInEasing` |
| Chip / 库 Tab 选中 | 背景 + 描边颜色过渡，下划线滑块横向滑动 | 200ms |
| 页面切换（库 → 详情 → 播放） | 共享元素（海报）+ 淡入淡出 / 横向位移 | 250ms |
| 列表 / 网格条目入场 | 首屏 `staggered 40ms/项`，位移 8dp + 淡入；分页追加不重复播放 | 200ms |
| 海报卡按下 | 缩放 `1.0 → 0.97` | 120ms |
| 排序 / 筛选结果刷新 | `animateItemPlacement` 重排 | 250ms |
| 播放页控制层显隐 | 位移 12dp + 淡入淡出 | 200ms |
| 进度条拖动 | 轨道高度 `3dp → 6dp` 平滑；预览浮层淡入 | 150ms |
| 九宫格锁屏 | 连线跟手；成功 150ms 淡出；错误红色抖动 300ms | 见 §15 |
| 加载态 | 骨架屏 shimmer（不用全屏转圈） | 循环 |
| 提示 / 错误 Toast | 自底部淡入上移，2s 后淡出 | 200ms |
| 开关（Switch） | 用 Material 3 系统默认动画，不自定义 | — |

- **硬约束**：时长一律 **≤ 300ms**；只做轻量微动画，**禁止**大面积位移、强弹性、视差、粒子等重动效；不做循环装饰动画（骨架屏 shimmer 除外）。
- **统一曲线**：入场 `FastOutSlowInEasing`、退场 `LinearOutSlowInEasing`（Material 3 标准曲线）。
- **无障碍**：当系统关闭动画（`ANIMATOR_DURATION_SCALE == 0`）时退化为瞬时切换。

### 14.1 库浏览页

- 结构：顶部 AppBar（**源切换** + **搜索入口**）→ 二级栏（媒体库 / 分类 Tab）→ 内容网格 + 右下角**单一「排序 / 筛选」入口按钮**（不常驻筛选条，见 §14.0 简洁原则）。
- **排序 / 筛选入口（合并为一个按钮）**：
  - 点击弹出**同一个** `ModalBottomSheet`：上半为排序维度（§6.3 全部维度，支持「主序 + 次序」，当前态高亮），下半为筛选条件。
  - 有生效的筛选或非默认排序时，按钮显示数量徽标；弹层内提供「恢复默认」一键清空。
  - 排序结果**本地瞬时完成、无 loading**（区别于飞牛 App 的服务端排序等待，即痛点 3）。
- 网格：`LazyVerticalGrid`，海报卡（2:3）+ 标题 + 观看进度角标；长按进入多选（批量标记已看 / 收藏）。
- 滚动：服务端增量分页加载；排序 / 筛选作用在**已同步的本地索引**上，不触发网络。
- 空态三分：库为空 / 未登录 / 同步中，分别给引导文案。

### 14.2 搜索页

- 入口：库页顶部搜索框；进入独立搜索页（或用 Material3 `SearchBar` 内联展开）。
- 结构：输入框（自动聚焦并唤起输入法）→ 最近搜索 chips → 输入即搜的结果网格。
- 交互：debounce 200ms（§6.6）；**输入即搜**，无需点「搜索」按钮；过期查询被 `Job.cancel()` 丢弃。
- 结果：复用库页网格 + 排序 / 筛选；多结果时按 §6.6 权重排序。
- 状态机：`历史（输入为空）→ 搜索中（骨架屏）→ 有结果 → 无结果（空态）→ 索引未就绪（顶部提示 + 服务端兜底结果）`。
- 最近搜索：本地保存最近 N 条并可清空；点击回填并立即检索。
- 关键词高亮：命中词在标题中高亮（可选增强）。

### 14.3 详情页

- 结构：海报背景 + 标题 / 评分 / 年份 / 时长 / 官方分级 → 「播放 / 继续播放」主按钮 → 剧集列表（分季）→ 简介 → 演职员 → 技术信息。
- 主按钮文案随 `userData` 变化：未看「播放」/ 在看「继续播放 · 剩 xx 分」/ 已看「再次播放」。
- 剧集：分季 Tab + 每集缩略图 + 简介 + 观看进度条 + 已看标记；点集直接进播放器并按 §7.6 连播。
- **技术信息**取 `MediaItemDto`（§3.2）：容器 / 视频编码 / 音频编码 / 码率 / 分辨率 / 文件大小——本机文件与服务端条目**统一展示**，便于排查「为什么某文件播不了」。
- 操作：收藏、标记已看 / 未看、播放方式选择（自动 / 强制直放 / 强制转码，§6.5）。

### 14.4 多库 / 多服务器的库展示

单台 Emby / 飞牛通常有**多个媒体库**（电影、电视剧、动漫、纪录片、综艺、家庭视频…），本项目再叠加多服务器与「本机」源。展示规则：

**三级层级**

| 层级 | 内容 | 手机竖屏 | 平板 / 折叠屏 |
|---|---|---|---|
| 一级 AppBar | 源（服务器）切换 + 搜索 | 服务器胶囊 + 图标 → 弹出服务器列表 | `NavigationRail` 顶部常驻 |
| 二级 库切换 | 当前源下的库 + 「全部」 | 顶部**横向库 Tab**，粘性置顶 | Rail / Drawer 内「库」分组 |
| 三级 内容 | 海报网格 + 排序 / 筛选条 | 网格 | 网格（列数更多） |

**三种展示模式（设置项「库展示方式」，默认按屏幕自适应）**

1. **库 Tab（手机默认 · 推荐）**：顶部横向可滚动 Tab，条目 = `全部` + 各库（图标 + 名称 + 条目数）。切换在**本地已同步索引**上瞬时完成，无网络等待。库 ≤ 5 均分不滚动；> 5 横向滚动并给渐隐边缘提示。
2. **抽屉导航（平板 / 折叠屏默认）**：`NavigationDrawer` 按「源 → 库」两级树列出，当前源展开、其它折叠；底部固定「本机视频」「设置」。
3. **聚合首页（可选）**：默认落地「全部」，按库分区（每库一行横滑海报 + 「查看全部」），顶部「继续观看」卡片。适合「随便看看」。

**多服务器规则**

- **同一时刻只显示一个源**：一级切换源后，二级库与内容整体替换；**不做多服务器库混排**（避免 ID 冲突与语义混乱）。
- 库列表来自 `getLibraries()`；离线时展示上次同步快照并标注「离线」。
- 「本机」源不分组库，直接以目录 / 文件夹作为二级项（**后期 L2**；M0–M5 不出现「本机」源的任何入口）。

**空态 / 降级**

- 源未登录 → 一级红点提示 + 「去登录」。
- 库为空 → 默认隐藏空库（可设置显示）。
- 库数量 > 8 且手机竖屏 → 自动回退到抽屉模式，避免 Tab 挤压。

## 15. 应用锁锁屏页 UI 规格

> 对应 §8.1 的九宫格图案锁，落地为独立全屏页。

- **布局**：居中 3×3 九宫格；标题「绘制解锁图案」；失败文案行；底部辅助操作行（生物识别 / 用 PIN 解锁 / 忘记图案？）。
- **几何**：点直径 12dp、连线 4dp；**命中半径 24dp**；外圈保护环 36dp；点间距按屏宽自适应（左右安全边距 24dp）。
- **状态机**：`idle → drawing → verifying → success（150ms 淡出进入）| error（红色抖动 300ms）| cooldown（倒计时期间禁用绘制）`。
- **颜色**：空闲点 `outline`；已连点 / 连线 `primary`；错误态用 `danger`；背景 `background`。
- **规则**：至少连 4 点才可提交；弱图案（Z / L / 直线等）在设置保存时拒绝（§8.1）。
- **生物识别**：进入即弹系统指纹 / 人脸；失败自动回落图案，不阻塞。
- **冷却态**：显示「尝试过多，请 30s 后重试」，期间九宫格降透明度且不可交互（对应 §8.1 递增冷却）。
- **忘记路径**：底部「忘记图案？」→ 弹窗说明（配备用 PIN 则引导用 PIN；否则仅能清除应用数据）。

---

## 16. 设置页 UI 规格

- **结构**：分组滚动列表；组标题 12sp 小字；组内为 56dp 列表行（§14.0 组件规范）。
- **分组与项（对应附 B）**：
  - **外观**：主题（跟随系统 / 亮色 / 暗色，仅此一项，无其它主题设置，§14.0）
  - **播放**：默认倍速 · 保持后台播放 · 双击快进 / 快退间隔 · 自动连播下一集
  - **画面**：屏幕方向 · 默认画面比例 · 字幕样式
  - **隐私**：应用锁 · 解锁方式 · 回前台解锁宽限期 · 后台遮挡任务快照
  - **搜索**：模糊音 · 繁体 / 简体互搜
  - **网络**：明文 HTTP · 信任自签证书
  - **源**：服务器管理（后期 L2 再加「本机视频目录」）
- **行样式**：标题 + 一句话副标题（解释作用）；尾部控件按类型为 `Switch` / `Chevron`（跳二级页）/ 当前值文本。
- **危险操作**：「清除应用数据」用 `danger` 文字色，点击弹二次确认对话框。
- **二级页**：服务器管理、字幕样式、关于页（含 LGPL / FFmpeg 许可声明，§9）。

---

## 17. 服务器添加 / 登录流程 UI 规格

**步骤**：选源类型 → 填地址 → 选登录方式 → 输入凭据 → 拉取库 → 完成。

- **选源类型**：卡片二选（Emby / 飞牛影视），每张带图标 + 一句说明。**「本机视频」卡片后期 L2 再加**。
- **填地址**：输入框智能补全 `http://`；端口默认值 Emby `8096` / 飞牛 `8005`；「测试连接」实时探测 `/System/Info/Public` 并回显服务器名（§5.2 设备识别）。
- **飞牛登录页（M4 只做账号密码）**：
  1. **账号密码（M4 唯一实现）**：账号 + 密码表单 + 「登录」；错误内联提示（§5.2 失败分支）。
  2. **NAS 账号授权 / 3. 扫码登录**：**后期 L1** 再补，M4 阶段这两个分段控件**不显示**（不做占位、不做置灰入口）。
- **Emby 登录页**：账号 + 密码；成功即返回 `AccessToken`。
- **错误态文案**：地址不可达 / 认证失败 / 路径前缀错误（404）三类分别提示，并给「修改地址」入口。
- **完成**：拉取 `getLibraries()`（§14.4），进入库页并高亮新添加的源。

---

## 附 B：设置项清单（初稿）

> 汇总分散在 §7 / §8 各处的设置项，作为设置页开发与验收的基线。

| 分组 | 设置项 | 默认值 | 关联 |
|---|---|---|---|
| 外观 | 主题 | 跟随系统（可选 亮色 / 暗色） | §14.0 |
| 播放 | 默认倍速 | 1.0× | 痛点 5 / §7.5 |
| 播放 | 保持后台播放 | 关 | §8.2 |
| 播放 | 双击快进 / 快退间隔 | 10s | §7.4 |
| 播放 | 自动连播下一集 | 开 | §7.6 |
| 画面 | 屏幕方向 | 自动（跟随旋转） | §7.2 |
| 画面 | 默认画面比例 | 适应 | §7.5 |
| 字幕 | 字号 / 颜色 / 描边 / 边距 | 系统默认 | §7.5 |
| 隐私 | 应用锁 | 开 | §8.1 |
| 隐私 | 解锁方式 | 九宫格图案 | §8.1 |
| 隐私 | 回前台解锁宽限期 | 立即 | §8.1 |
| 隐私 | 后台遮挡任务快照 | 关 | §8.2 |
| 网络 | 明文 HTTP | 关 | §10 |
| 网络 | 信任自签证书 | 关 | R6 |
| 源 | 服务器列表（Emby / 飞牛） | 空 | §5 |
| 源 | 本机视频目录（SAF） | 空 | §5.3（**后期 L2**） |
| 预览 | 本地生成缩略图（降级） | 关 | §6.1 |
| 搜索 | 模糊音（zh/z、n/l、前后鼻音…） | 关 | §6.6 |
| 搜索 | 繁体 / 简体互搜 | 关 | §6.6 |
| 界面 | 库展示方式 | 自动（按屏幕自适应） | §14.4 |

---

## 附：本次调研来源

- 飞牛帮助中心：[Apple TV 上如何通过 Infuse 使用飞牛影视？](https://help.fnnas.com/articles/v1/media/atv_infuse)
- 飞牛帮助中心：[Apple TV 上如何通过 VidHub 使用飞牛影视？](https://help.fnnas.com/articles/v1/media/atv_vidhub)
- Emby：[Playback Methods（DirectPlay / DirectStream / Transcode）](https://emby.media/support/articles/DirectPlay-Stream-Transcoding.html)
- Emby REST API：[Playback Guidelines](https://betadev.emby.media/doc/restapi/Playback-Guidelines.html)
- Emby REST API：[getItems（SortBy 可选值）](https://dev.emby.media/reference/RestAPI/ItemsService/getItems.html)
- Emby REST API：[Browsing the Library（Sorting）](https://dev.emby.media/doc/restapi/Browsing-the-Library.html)
- Emby REST API：[User Authentication](https://dev.emby.media/doc/restapi/User-Authentication.html)
- Jellyfin：[10.9 API diff（Trickplay 端点）](https://gist.githubusercontent.com/nielsvanvelzen/6b5a98ef2da6c748ebc8ae190ff29680)
- Jellyfin：[Kotlin SDK / 官方与第三方客户端](https://kotlin-sdk.jellyfin.org/guide/getting-started.html)

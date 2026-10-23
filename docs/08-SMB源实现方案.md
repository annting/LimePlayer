# 08-SMB 源实现方案（调研报告）

> 状态：**调研完成，待用户确认后实施**。本文只做现状盘点与方案设计，**不改任何代码**。
> 触发背景：2026-10-22「添加 / 修改服务器」页落库了 SMB 协议与配置行（只做页面，`sourceType = null`，
> 保存 / 登录刻意挡死），源模块本身未接入。本文回答「该如何实现」。
> 关联：`02-技术规范.md` §1.2 早已预留「新增一种源（Plex、SMB）= 新增一个模块 + 改一处装配点」。

---

## 0. 结论摘要（TL;DR）

| 问题 | 结论 |
|---|---|
| 昨天到底落地了什么 | **只有页面层**：SMB 类型、域 / 共享目录行、445 TCP 探活、`smb://` URL 拼拆（已单测）。保存登录被 `!kind.isPlayable` 挡死 |
| 缺什么 | `:source:smb` 模块、`SourceType.SMB`、凭据持久化、登录网关分支、`SourceFactory` 装配，共 **1 个新模块 + 9 处装配点改动** |
| 播放怎么做 | ExoPlayer 不认 `smb://`。推荐**进程内本地 Range HTTP 代理**（绑 `127.0.0.1` + 随机端口），`planPlayback` 返回普通 HTTP 地址，seek / 字幕 / 抽帧全部搭车（§2.5） |
| 客户端库 | 首选 **smbj**（SMB2/3，群晖 DSM7 与飞牛均支持 SMB3）；老 NAS 兜底 jcifs-ng（§3 有 9 条真机验证清单） |
| UI 要改多少 | **首页 / 库页 / 搜索 / 详情页 / 播放页 / 设置页全部零改动**（§6 适配矩阵）；只有添加页的「未接入」提示要下线 |
| 分期 | P0 浏览 + 播放 + 本地进度（最小可用）→ P1 字幕 + 继续观看 + 抽帧策略 → P2 文件夹海报 / 增量同步（§8） |

---

## 1. 现状盘点

### 1.1 已落地（页面层，均在未提交的工作区改动里）

| 文件 | 能力 | 位置 |
|---|---|---|
| `feature/server/.../ServerKinds.kt` | `ServerKind.SMB`（`sourceType = null`、默认 445、协议锁定、无认证方式行、`isSmb` 分支） | :74-81, :85-94 |
| 同上 | SMB 行序：网络分组多「域 / 共享目录」、认证分组只有账号 + 密码 | :111-116, :126-133 |
| 同上 | `smb://` URL 拼 / 拆 / 粘贴纠偏，**原样往返已有单测** | :153-170, :181-208, :215-223 |
| `feature/server/.../AddServerViewModel.kt` | `domain` / `share` 状态；`targetUrl` 拼 `smb://host:445/share`；SMB 只要求账号 | :37-40, :70-74, :93-98 |
| 同上 | `canSubmit` 对未接入类型恒 false；`submitBlockReason` 给「SMB 直连尚未接入」提示 | :114-123, :129-143 |
| `feature/server/.../AddServerScreen.kt` | 网络分组按 `networkFields` 动态出行；账号占位符「如 guest」；SMB footnote；SMB 双主题 Preview | :155-205, :220, :258-265, :413-428 |
| `feature/server/.../ProbeServer.kt` | `probeSmb`：`host:445` TCP 握手（3s 超时），回复「可达」不报版本 | :33-43 |
| `app/.../nav/AppNavHost.kt` | 探活按类型分派，SMB → `probeSmb` | :145-149 |
| `core/designsystem/.../SettingsComponents.kt` | 锁定行（SMB 的协议）不画 chevron | :265-269 |

现有测试（`:feature:server`，26 个 AddServer 用例中）SMB 相关 5 个：切类型端口 / 协议 / 行序（:50-97）、
`smb://` URL 往返（:145-146, :158-159）、「SMB 不可提交并给出明确原因」（:66-75）、
「SMB 点提交不发请求」（:329-343）、`ServerKind.SMB.sourceType isNull`（:345）。

### 1.2 缺口清单（「具体功能没有实现」的准确范围）

| # | 缺口 | 位置 | 后果 |
|---|---|---|---|
| 1 | `SourceType` 没有 SMB | `core/model/Models.kt:10` | `ServerConfig` 无法表达 SMB 服务器，根本存不下来 |
| 2 | `SignInRequest` 没有 `domain` | `ServerKinds.kt:228-240` | 域账号凭据无法传给登录网关 |
| 3 | `ServerConfig` 没有 `username` / `domain`，密码无存放位 | `ServerConfigStore.kt:7-13` | 保存后无法重建连接（密码可复用 `TokenStore`） |
| 4 | `buildAuthGateway` 没有 SMB 分支 | `AppNavHost.kt:73-142` | 点「登录」无动作（当前被 `canSubmit` 挡死，见 #7） |
| 5 | `SourceFactory.create` 没有 SMB 分支 | `SourceFactory.kt:30-62` | 即使存了配置也造不出 `MediaSource`，`MainActivity:172` 返回 null → 永远停在欢迎页 |
| 6 | 无 `:source:smb` 模块 | — | 目录枚举、元数据、播放、字幕、进度全部缺失 |
| 7 | `canSubmit` / `submitBlockReason` 的 `!isPlayable` 特判 | `AddServerViewModel.kt:116, :131` | SMB 接入后要随 `isPlayable` 转正下线 |
| 8 | 文档口径 | `01-功能说明.md:44` 非目标表把 SMB 列为「不做」 | 实施时要改状态（文档先行，`README.md` §5 约定） |

---

## 2. 架构方案

### 2.1 模块划分（遵循 `02` §1.2 依赖规则）

新增 **`:source:smb`**，与 `:source:fnos` 同构：纯 Kotlin 库模块（无 Android 依赖、JVM 单测可直接驱动），
依赖 `:core:model` + smbj + coroutines + kotlinx-serialization（本地进度 JSON）。

```
source/smb/
  SmbClient.kt         连接 / 认证 / 列举 / 定位读（smbj 薄封装，界面见 §3）
  SmbSource.kt         MediaSource 实现（§2.4 映射表）
  SmbPathUtils.kt      文件名解析：标题 / 年份 / SxxExx（纯函数，单测）
  SmbProgressStore.kt  本地断点 {文件路径: 位置ms}，JSON 落 filesDir
  SmbLocalProxy.kt     本地 Range HTTP 代理（GET/HEAD/Range → smbj 定位读）
  SmbProxyServer.kt    代理服务器生命周期（起播启动、空闲自杀、会话 token）
```

> 为什么代理放在 `:source:smb` 而不是 `:core:playback`：代理是「SMB 文件 → 可寻址字节流」的源侧能力，
> 与 Emby 的 `image()`/飞牛的 `play_link` 同级；`core:playback` 继续只认 HTTP/file URI，零改动。

### 2.2 `SourceType.SMB` 影响面扫描（加枚举值的安全性）

逐处核查了所有按 `SourceType` 分派的代码，结论：**加枚举值安全**，只有装配点要动。

| 分派点 | 现状 | 加 SMB 后 |
|---|---|---|
| `ServerKind.of()`（`ServerKinds.kt:98`） | `entries.firstOrNull` | 自动映射，零改动 |
| `SourceFactory.create`（`SourceFactory.kt:40-61`） | `when` 带 `else -> null` | **要加分支**（不加则 SMB 配置永远造不出 source） |
| 鉴权头样式分派（`AppNavHost.kt:87`） | `if JELLYFIN` | 不受影响 |
| 登录实现分派（`AppNavHost.kt:115`） | `if FNOS` | **要加 SMB 分支** |
| `ThumbnailFallback.kt:55` | `if FNOS` 放弃抽帧 | 不受影响（SMB 要走抽帧，§2.8） |
| Room `Mappers.kt:37` / `FileStores.kt:58` | `SourceType.valueOf(名)` | 老数据按名存取，不受影响 |
| `SettingsScreen.kt:546,555`（Preview） | 写死 EMBY/FNOS | 不受影响 |

### 2.3 凭据模型与持久化

| 数据 | 存放 | 改动 |
|---|---|---|
| 用户名 / 域 | `ServerConfig` 新增 `username: String? = null`、`domain: String? = null` | `ServerConfigStore.kt` + `ServerConfigRecord`（JSON record 同步加两个可选字段；`ignoreUnknownKeys = true` 已开 → **老配置文件向后兼容**，读出来是 null） |
| 密码 | 复用 `TokenStore`（Keystore 加密落盘，`EncryptedTokenStore.kt`） | 零改动；`SourceFactory` 里 `tokenStore.get(id)` 即密码 |
| 删除清理 | `SettingsViewModel.kt:56-57` 已 `remove(config) + remove(token)` | 零改动 |
| `SignInRequest` | 加 `domain: String` 字段（`ServerKinds.kt:228`） | 网关透传 |

**「登录」的语义**：SMB 没有登录端点。「登录成功」= 带凭据完成 SMB 握手 + 列举共享根目录成功。
网关 SMB 分支：`SmbClient(host, port, domain, username, password).listShare(share)` → 成功即
`configStore.upsert(ServerConfig(sourceType = SMB, userId = null))` + `tokenStore.put(serverId, password)` + 按需激活。
`probeSmb`（TCP 探活）保持轻量不动——探测按钮在填完凭据前就能点，真验证放登录时做。

### 2.4 `MediaSource` → SMB 映射

「一台 SMB 服务器 = 一个共享目录」（UI 只有一个共享目录字段）。**共享内顶层文件夹 = 媒体库**，
递归扫描到的视频文件 = 条目。

| `MediaSource` 方法 | SMB 实现 | 备注 |
|---|---|---|
| `getLibraries()` | 共享根目录的**一层子目录**；根下没有子目录则单库 = 根 | `MediaLibrary(id = 目录路径, name = 目录名, collectionType)`；collectionType 要给 `Movie/Series/Video` 之一才能进首页库横滑的「作品级」过滤（`ServerHomeDataSource.kt:73-79`）。猜法：目录名含「剧」→ Series、含「电影」→ Movie、其它 → Video；目录名为空时按内容构成兜底。**纯函数，可单测** |
| `getItems(query)` | 内存全量扫描（递归 + 视频扩展名白名单）后按 `startIndex/limit` 切片 | `RoomLibraryRepository.syncItems` 每页 200 自然工作；`itemId` = 文件在共享内路径（稳定唯一，可作 Room 主键）；`libraryId` = 顶层目录 |
| `getItemDetail(id)` | 文件属性 + `MediaMetadataRetriever` 经代理读时长 + 合并本地断点 | `path` 填绝对路径（详情页「文件路径」展示白拿）；时长**只在详情页读**，列表期不读文件头（否则大库同步要把每个文件头拉一遍，SMB 上很慢） |
| `getLatestItems(limit)` | 全文件按 `lastModified` 倒序取 limit | 首页「最近添加」 |
| `getResumeItems(limit)` | 本地断点存储里有进度的文件（P1） | 实现了首页才有「继续观看」；不实现则模块自动隐藏（既定降级） |
| `image()` | `ImageSource.None`（`hasPoster = false`） | 搭车 `LocalThumbnailProvider` 抽帧（§2.8） |
| `planPlayback()` | 代理 URL（§2.5） | 幂等：同文件永远同一 URL，无需 TTL 缓存（不像飞牛有服务端副作用） |
| `getTrickplay()` | `null`（默认） | 进度条无预览，可接受 |
| `reportProgress()` | 写本地断点存储（§2.7） | 无服务端可报 |
| `planTranscode()` | `null`（默认） | 直放失败 → 错误卡片 + 重试（合理：本地文件没有转码端） |
| `canMarkPlayed` / `deleteItem` / `markPlayed` | 默认 false / 默认抛 | 详情页按钮自动隐藏，零改动 |
| `getAdjacentEpisodes` / `getEpisodes` / 人物 | 默认空 | 连播浮层不出现，零改动 |

视频扩展名白名单（初版）：`mkv mp4 avi mov ts m2ts wmv flv rmvb webm m4v mpg mpeg 3gp vob m2v divx iso`——
按 Emby 侧常见格式 + 飞牛 download 目录实际文件核定，**实施时先 `ls` 一遍真实共享目录再定稿**。

### 2.5 播放链路（本方案唯一的技术决策点）

**问题**：`Media3PlaybackEngine.prepare`（`Media3PlaybackEngine.kt:135-151`）→ `toMediaItem`（:274-297）
只认 `MediaItem.setUri`，ExoPlayer 的 `DefaultMediaSourceFactory` 只解 http/https/file/content/asset/rtsp，
`smb://` 无人可解。三个候选：

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. 本地 Range HTTP 代理** | 进程内微型 HTTP 服务器（`127.0.0.1` + 随机端口），`GET/HEAD + Range` → smbj 定位读；`planPlayback` 返回 `http://127.0.0.1:<port>/<token>/<path>` | ExoPlayer 与 `core:playback` **零改动**；seek / 拖进度条天然可用；字幕可同一代理；`MediaMetadataRetriever` 抽帧也可用（§2.8）；全部实现在 `:source:smb` 内闭环 | 多一个端口 + 线程；要正确实现 206/Range | **推荐** |
| B. 自定义 media3 `DataSource` | `:source:smb` 依赖 `media3-datasource`，包一个定位读 DataSource | 无端口 | media3 依赖进 source 层；字幕 URL 与 MMR 也要过同一 factory（引擎构造要改）；ExoPlayer 升级兼容风险；动 `core:playback` | 不选 |
| C. 整文件下载后 `file://` 播 | 先下到缓存再播 | 实现最简单 | 几十 GB 影视文件不可行；seek 要等下载；磁盘翻倍 | 仅小文件场景，不作主路径 |

**代理设计要点**（A 方案展开）：

1. **绑定 `127.0.0.1` + 随机端口**：不用固定端口（避开冲突与被扫）；URL 带每会话随机 token
   （`http://127.0.0.1:port/<token>/<urlencoded path>`）——同局域网其它设备即使扫到端口，没有 token 也读不到。
2. **必须正确实现**：`GET`、`HEAD`、`Range`（`bytes=a-b` / `bytes=a-` / 后缀范围）、`206`、
   `Content-Length`、`Content-Range`、`Accept-Ranges: bytes`、`404` / `416`。
   ExoPlayer 的 `ProgressiveMediaSource` 靠这些做缓冲与 seek——**缺 Range 会退化成不能拖进度条**。
3. **读路径**：HTTP 工作线程收到 Range → smbj 定位读（SMB2 READ at offset）→ 回写。
   smbj 随机读 API 是代理的地基，列入 §3 验证清单。
4. **并发**：ExoPlayer 预取可能并发 2–3 个 Range 请求 → smbj `Session` 需线程安全（验证点）；
   HTTP 侧用有界线程池（2–4），同文件读串行化。
5. **生命周期**：随播放会话启动（`planPlayback` 时 ensure started）；空闲 5 分钟自杀省电；
   进程内单例（`PlaybackService` 与 Activity 同进程，后台播放 / 通知栏控制不受影响）。
6. **明文约束已核实可放行**：`app/src/main/res/xml/network_security_config.xml` 主源集
   `cleartextTrafficPermitted="true"`（release 也生效）；OkHttp 的 `cleartextGuard`（`NetworkPolicy.kt`）
   是 OkHttp 层拦截器，管不到 ExoPlayer。**无需新增配置**。
7. 协议面很小（一个文件 + Range），倾向直接 `ServerSocket` 手写 ~200 行，不引 NanoHTTPD 类库。

### 2.6 字幕（P1）

- 枚举规则：视频同目录、同名（大小写不敏感）的 `X.srt / .ass / .ssa / .vtt`；可选语言标记
  `X.zh.srt` → `label = 中文`（解析纯函数，可单测）。
- 进 `PlaybackPlan.Direct.subtitles`，URL 走代理，`mimeType` 按扩展名映射。
- **ExoPlayer 侧零改动**：`SubtitleConfiguration` 机制现成（`Media3PlaybackEngine.kt:277-284`）。
- 枚举时机：`getItemDetail` 时顺带做（目录列举本来就要做）。

### 2.7 进度与断点（无服务端，本地化）

- `reportProgress` → `SmbProgressStore`（`filesDir/smb_progress_<serverId>.json`，`{路径: 位置ms}`）。
  写入频率低（`ProgressReporter` 10s 一次 + STOPPED，`PlayerViewModel.kt:195`），直接落盘即可。
- `getItems` / `getItemDetail` 把本地断点合并进 `userData.resumePositionTicks` → **详情页续播行、
  Room 缓存、首页继续观看全部自动生效**（现有管道）。
- 进度文件体积：万级条目 ≈ 几百 KB；文件被删后下次同步自然消失，可定期按同步结果裁剪（二期）。

### 2.8 缩略图（搭车，零额外工作量）

`LocalThumbnailProvider`（`app/.../ThumbnailFallback.kt:27-80`）的逻辑：`hasPoster = false` 时对
**直放地址**跑 `MediaMetadataRetriever` 抽一帧，落缓存后按 `ImageSource.Local` 交给 Coil。
SMB 的直放地址 = 代理 URL → **抽帧自动生效，海报问题不花一行专门代码**。

注意点：
- MMR 通过 HTTP 读 moov 需要 Range 支持（与 §2.5 同一地基）；
- 代理未启动时（如设置页预览路径）返回 `None` → 占位兜底，不崩；
- **成本**：列表滚动会逐条触发抽帧（每条走代理读一次文件头）。设置项 `localThumbnails` 已有开关；
  代理侧要限流（同文件串行 + 全局并发上限）。真机验证「列表滚动抽帧是否跟手」列入 P1 验证清单，
  跟不上则退化为「仅详情页抽帧」。

---

## 3. SMB 客户端库选型（调研期验证）

| 库 | 协议 | 维护 | Android 适配 |
|---|---|---|---|
| **smbj**（`com.github.agschwager:smbj`） | SMB2 / SMB3 | 较活跃 | 纯 Java + JCE 标准算法，社区有 Android 用例；需在 API 26 实测 |
| jcifs-ng | SMB1 / 2 / 3 | 较活跃 | 纯 Java；**只开 SMB1 的老 NAS 唯一选择** |

用户 NAS 现状：群晖 DSM7 与飞牛 fnOS 均支持 SMB3 → **首选 smbj**。
版本号纪律：`libs.versions.toml` 的每个版本都要从 maven-metadata **实测**取回（文件头注释的硬规矩），
不许凭记忆写。

**P0 前置验证清单**（任一条失败 → 退 jcifs-ng）：

1. DSM7：SMB3 + NTLMv2（账号密码）connect + `listShare`
2. 飞牛 fnOS：同上
3. guest / 匿名访问（DSM 需开启「不安全 guest」；UI 占位符已提示「如 guest」）
4. 域账号（`domain` 字段 + NTLMv2）
5. **> 4GB 文件 64-bit offset 定位读**（SMB2 READ at offset，代理的地基）
6. 随机读 API 形态：`seek + read(at offset)`（或等价的定位读入口）
7. `Session` 线程安全性（并发 Range 请求）
8. API 26 可用性（无 `java.desktop` 等桌面 API 依赖；JCE 算法齐全）
9. R8 / minify 下无反射问题（release 构建验证）

---

## 4. 装配点改动清单（1 个新模块 + 9 处改动）

| # | 文件 | 改动 | 说明 |
|---|---|---|---|
| 1 | `settings.gradle.kts:33` | `include(":source:smb")` | 照 `:source:fnos` |
| 2 | `gradle/libs.versions.toml` | smbj 依赖条目 | 版本实测 maven-metadata |
| 3 | `source/smb/build.gradle.kts` | 新模块脚本 | 照 fnos 的纯 Kotlin 结构（无 Android 依赖） |
| 4 | `core/model/Models.kt:10` | `SourceType` 加 `SMB` | 影响面已扫（§2.2），安全 |
| 5 | `core/datastore/ServerConfigStore.kt` | `ServerConfig` + `username`/`domain`；`ServerConfigRecord` 同步 | 向后兼容（`ignoreUnknownKeys`） |
| 6 | `feature/server/ServerKinds.kt` | `SMB.sourceType = SourceType.SMB`；`SignInRequest` + `domain` | `isPlayable` 自动转 true |
| 7 | `feature/server/AddServerViewModel.kt` | `canSubmit`/`submitBlockReason` 的 `!isPlayable` 特判下线（SMB 转正），保留 share 必填校验 | `onSubmit` 的 `sourceType ?: return` 自然可走 |
| 8 | `app/.../nav/AppNavHost.kt` | `buildAuthGateway` 加 SMB 分支：列举验证 → upsert + `tokenStore.put(密码)` + activate | 复用 `SignInResult` 既有契约 |
| 9 | `feature/server/SourceFactory.kt` | `create` 加 SMB 分支：凭据 = `config.username/domain` + `tokenStore.get(id)`（密码）；传入进度目录 | 构造签名加参数 → `SourceFactoryTest` 同步 |
| 10 | `app/.../di/AppModule.kt` | `sourceFactory` 提供方加 `filesDir`（`SmbProgressStore` 用） | Hilt 装配 |

**测试影响**：`AddServerViewModelTest` 中 3 个用例要改——「SMB 不可提交」（:66-75）、
「SMB 点提交不发请求」（:329-343）、`sourceType isNull`（:345）；端口 / 协议 / 行序 / URL 往返用例不受影响。
新增 `:source:smb` 单测（映射、文件名解析、字幕枚举、Range 解析、进度存储合并）。

**UI 文案同步**：`AddServerScreen.kt:258-265` 的「SMB 源模块尚未接入」footnote 删除或改说明；
`AddServerViewModel.kt:131` 的 SMB 提示分支删除。

---

## 5. 页面适配矩阵（论证「UI 层基本不动」）

| 页面 | 改动 | 说明 |
|---|---|---|
| 首页 | **零改动** | `ServerHomeDataSource` 四请求并发：`getLibraries` = 顶层目录（失败上抛 → 「无法连接」空态，合理）；每库 `getItems` limit 12；`getLatestItems` = mtime 倒序；`getResumeItems` 默认空 → 模块隐藏（P1 实现后自动出现） |
| 库页 | **零改动** | tabs = 顶层目录；排序 / 筛选走 `MediaItemDto` 字段（title / year / size / dateCreated 都有）；下拉刷新 `syncItems` 200/页 |
| 搜索 | **零改动** | Room 入库即搜，`SearchIndex` 拼音管道现成（`LibrarySearchViewModel.kt:49`） |
| 详情页 | **零改动** | `getItemDetail` 填 `path`（「文件路径」展示白拿）；标记已看 / 删除按钮 `canMarkPlayed`/`canDelete = false` 自动隐藏 |
| 播放页 | **零改动** | 代理 URL 就是普通 HTTP；字幕走 `subtitles`；进度 `ProgressReporter` → 本地；trickplay null → 进度条无预览；转码降级 null → 错误卡片 + 重试 |
| 设置页 / 服务器管理 | **零改动** | 删除服务器已清理 config + token（`SettingsViewModel.kt:56-57`） |
| 添加 / 修改服务器 | 小改 | SMB 转正：`sourceType` 赋值、特判下线、footnote 更新 |
| 通知栏 / 画中画 / 切后台 | **零改动** | 引擎进程级单例，代理同进程 |

---

## 6. 风险与真机验证计划

### 6.1 风险表

| 风险 | 影响 | 缓解 |
|---|---|---|
| smbj 在 Android 上兼容性（§3 清单） | P0 地基 | 前置验证；失败退 jcifs-ng |
| 大库同步耗时（SMB 列举比 HTTP 慢） | 首屏 / 下拉刷新慢 | 并发列举；增量同步（按 mtime 比对）列 P2；先接受万文件级一次性成本 |
| 抽帧流量（列表逐条 MMR 走代理读文件头） | 滚动卡顿 / 带宽 | 代理限流；验证不达标则「仅详情页抽帧」（§2.8） |
| 代理稳定性（SMB 断流重连、seek 边界、异常 Range） | 播放中断 | 读失败重试一次 → 仍失败回 5xx，ExoPlayer 走既有错误卡片 + 重试 |
| 共享离线 / 断网 | 首页「无法连接」、播放错误卡片 | 现有降级机制全覆盖，无需新代码 |
| 多服务器并存 | — | `SourceType` 区分，同一时刻单源（既定约束） |
| 新权限 | — | 无（`INTERNET` 已有） |

### 6.2 真机验证清单（P0 完工后，SM-G9860）

1. 群晖 `media` 共享：添加（填域可空）→ 登录 → 首页三模块 → 库页 tabs → 详情（时长 / 路径）→ 播放
2. 飞牛共享：同上
3. **> 4GB 文件 seek 到中后段**（64-bit offset 实证）
4. guest 与域账号各一遍
5. 断点：播到 1/3 退出 → 重进详情显示续播 → 继续播位置正确；杀进程重进仍记得（本地进度实证）
6. 切后台 / 锁屏 / 回前台（引擎 + 代理生命周期）；通知栏控制
7. SMB 共享临时下线 → 首页「无法连接」+ 重试恢复
8. 收尾按项目铁律熄屏（`svc power stayon false` → `KEYCODE_SLEEP` → 确认 `Asleep`）

---

## 7. 分期实施建议

| 期 | 范围 | 验收标准 |
|---|---|---|
| **P0** 最小可用 | §4 全部装配点 + `SmbClient`/`SmbSource`/`SmbLocalProxy`/`SmbProgressStore` + 浏览 + 播放 + 本地进度 | 群晖真机：添加→登录→首页→库页→详情→播放（含 seek）→断点恢复；`./gradlew test` 全绿 |
| **P1** 体验补齐 | 字幕枚举（§2.6）、`getResumeItems` 本地继续观看、抽帧策略定型（§2.8） | 字幕轨可切换；首页出现「继续观看」；列表滚动抽帧不卡 |
| **P2** 增强 | 文件夹海报 / 同目录图片、增量同步、WebDAV / NFS 复用「本地代理」模式 | 按需 |

每期 DoD 对齐 `04-开发计划.md` 的测试与真机要求；**改完不自动提交，等用户明确说「提交」再 commit**（项目铁律）。

---

## 8. 附：与现有约定的对照

- `02` §1.2「`:source:*` 之间互不依赖，新增一种源 = 新增一个模块 + 改一处装配点」——本方案正是该规则的第一次实战，形态完全吻合。
- 装配点唯一：`SourceFactory`（`:feature:server`）——SMB 分支加在这里，UI 层不动。
- 版本目录纪律：smbj 版本从 maven-metadata 实测，不凭记忆。
- 文档先行：实施时同步更新 `01-功能说明.md:44`（SMB 从「不做」移出）与 `02` §3.2 装配点描述。
- 提交纪律：本次调研及后续实施**均不自动提交**。

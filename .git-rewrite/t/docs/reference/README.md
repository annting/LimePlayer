# reference · 参考实现索引

> **为什么代码不放在主干文档里**：规范（`02`/`03`）要能被快速读完并当作契约来核对；混入大段实现代码后，
> 人读不完，AI 也会把示例代码误当规范。因此主干只保留**契约**，完整实现退到本目录索引的归档文件里。

---

## 唯一参考实现：M1 计划（任务级 TDD 全文）

**文件**：`../archive/2026-10-16-初稿/plans/2026-10-16-android-player-02-m1-foundation-and-emby.md`（4255 行）

它的组织方式是「一任务一章」，每章都按 `写失败测试 → 运行确认失败 → 实现 → 运行确认通过 → 提交` 排列，
并附有**完整可编译的代码**。当你不确定某个函数的签名细节、某个测试该怎么写、某个坑具体长什么样时，直接跳到对应行。

### 按模块直达

| 要找什么 | 归档文件中的章节 | 起始行 |
|---|---|---|
| 多模块 Gradle 骨架 / 版本目录 / 模块构建脚本 | Task 0 | 47 |
| `Ticks` 值类型与单位换算 | Task 1 | 300 |
| 领域模型与 `MediaSource` 接口的完整定义 | Task 2 | 375 |
| Room 实体 / DAO / 映射 / 分页同步仓库 | Task 3 | 572 |
| 图案编码、弱图案规则、PBKDF2、Keystore、凭据存储与冷却 | Task 4 | 905 |
| `PlayerSettings`（全部设置项）/ `SettingsStore` / 服务器配置 / 加密令牌落盘 | Task 5 | 1213 |
| OkHttp 工厂、鉴权头注入、错误映射 | Task 6 | 1658 |
| 共享 REST 骨架：DTO、URL 拼接、取库走 `/Views`、图片走请求头、三档播放协商 | Task 7 | 1828 |
| Emby 适配器：Mapper（含 `hasPoster`）、`DeviceProfile`、`EmbySource` | Task 8 | 2288 |
| 主题与色板 token | Task 9 | 2567 |
| 九宫格锁屏：命中几何、状态机、Compose 画布 | Task 10 | 2685 |
| 库页与海报网格（异步取图 + 无海报占位） | Task 11 | 2965 |
| App 装配：DI、导航、应用锁门卫、Manifest | Task 12 | 3198 |
| 添加服务器与登录（地址归一化、探测、登录落库） | Task 13 | 3634 |
| `SourceFactory` 与导航接线 | Task 14 | 3989 |
| M1 完成判定（测试计数与手工验收） | 完成判定 | 4227 |
| 给下游计划留的钩子清单 | 已知衔接点 | 4239 |

### 使用须知（重要）

1. **这是「初稿 + 一轮修订」的产物，不是最终契约。** 若与 `02-技术规范.md` / `03-UI设计规范.md` 冲突，**以规范为准**。
2. 该文件的 **Task 0–14 已按评审结论修订过**（见下节），但其中的行号、测试计数只保证与其内部一致。
3. **P0（M0 验证）与 roadmap 两份计划没有完成修订**，其内容已被 [`../05-接口契约.md`](../05-接口契约.md) §7 与 [`../04-开发计划.md`](../04-开发计划.md) §3.1 取代，**不要再以它们为准**。

---

## 这份参考实现里已经修掉的坑（照抄时不要退回旧写法）

| 坑 | 正确写法 |
|---|---|
| 把 `PlaybackInfo` 返回的 `TranscodingUrl` 当绝对 URL | 必须拼 `baseUrl + prefix` |
| 取库用 `Items + IncludeItemTypes=CollectionFolder` | 改用 `/Users/{userId}/Views` |
| 列表查询的 `Fields` 里带 `People` | 列表不带，详情页才取 |
| `userId` 为空时拼出 `/Users/null/Items` | 取数前 `requireNotNull`，立即失败 |
| 三档协商都不满足时回退成直连 | 明确报错，不要硬播 |
| 图片 URL 里带 `api_key` | 令牌走请求头（Coil 注入），URL 不带 |
| 无海报条目仍请求图片 URL | `hasPoster=false` → 返回 `ImageSource.None` |
| DI 注入内存版配置 / 令牌存储 | 必须注入密文落盘实现，否则不满足验收 |
| `N` 宫格点半径写成裸像素 | 按 dp → px 换算；命中与绘制共用同一套几何 |
| 领域层混用 `Long` 与 `Ticks` | 领域层一律 `Ticks`，只在 Room 与播放层边界显式转换 |
| DataStore 直接 `data().map{}` | 先 `catch(IOException)` 再 `map`，损坏时退回默认值 |
| 分页循环先累加偏移再判空页 | 先判空页、再累加、最后判 `hasMore` |
| Manifest 里 `usesCleartextTraffic="true"` 且无 `targetSdk` | 明文用 security-config 且只在 debug 放行；`targetSdk` 显式声明在 `:app` |
| 纯 JVM 模块只设 Kotlin 侧 `jvmTarget` | 必须同时 `java { source/targetCompatibility = VERSION_17 }`；否则 `compileTestJava`（跟随本机 JDK，如 21）与 Kotlin（17）不一致 → `Inconsistent JVM-target compatibility` 构建失败 |
| 在 Canvas 的绘制 lambda（`DrawScope`）里读 `MaterialTheme.colorScheme` | 颜色必须在 **Composable 作用域**读取后捕获进绘制 lambda —— `DrawScope` 不是 Composable 作用域，直接读编译不过（任务 1-10 实测） |
| `pointerInput(state.status)` 用会变化的业务状态作 key | key 用 `Unit`（或稳定值）+ `rememberUpdatedState` 读最新命中器/回调 —— 手势进行中状态必然变化（IDLE→DRAWING），以状态为 key 会**取消并重启手势协程**：拖动被掐断、抬手事件丢失（真机表现为「九宫格无法滑动」，2026-10-17 修复） |
| 在 `pointerInput` 块里读 `size` 取画布尺寸 | 尺寸改由 `Modifier.onSizeChanged` 写入状态 —— pointerInput 块启动时布局可能尚未完成，读到的 `size` 为 0，命中器以 0 边长构造后**所有触摸全部 miss**（与上一条叠加，2026-10-17 修复） |
| 不判空直接渲染 `PrimaryScrollableTabRow` | **库列表为空时不要渲染 Tab 行** —— 零个 Tab 时 Material3 内部测量越界崩溃（`IndexOutOfBoundsException` @ `TabRow.kt`）；真机表现为「登录成功进库页瞬间闪退」（首次同步尚未拉到库列表，2026-10-17 修复，`LibraryScreenTest` 3 用例锁定） |

### 与当前工程骨架的差异（照抄前先看这条）

骨架（任务 1-0）已按 2026-10 的版本矩阵重写，与归档参考实现有几处**刻意的不同**，直接复制参考代码会踩：
 
| 归档参考实现 | 现在的工程 | 原因 |
|---|---|---|
| Coil 2：`coil.compose.AsyncImage`、`coil.request.ImageRequest` | **Coil 3**：包名改为 `coil3.compose.*` / `coil3.request.*`，并需额外的 `io.coil-kt.coil3:coil-network-okhttp` 依赖 | Coil 3 是当前主线，网络层被拆成独立模块 |
| OkHttp 4.12 | OkHttp 5.5（`mockwebserver` 仍可用，但 OkHttp 5 另有一套 `mockwebserver3` API） | 版本上移 |
| `kotlin { jvmToolchain(17) }` | Android 模块**不写任何 jvmTarget 配置**（AGP 9 内置 Kotlin 自动取 `compileOptions.targetCompatibility`）；纯 JVM 模块 `:core:model` 用 `compilerOptions.jvmTarget` **并同时以 `java {}` 固定 Java 侧目标**（见上方避坑表） | toolchain 会要求本机恰好装 JDK 17；内置 Kotlin 下 `kotlin-android` 插件已被禁止 |
| 模块插件块里有 `alias(libs.plugins.kotlin.android)` | **删掉** —— AGP 9 起 Kotlin 编译内置，保留该插件会直接构建失败 | 官方迁移指南 `migrate-to-built-in-kotlin` |
| Room / Hilt 在 Task 0 就随模块脚本接入 | 版本已登记在 `libs.versions.toml`，**Room + KSP 在任务 1-3 接入（已实测通过）**、Hilt 在任务 1-12 接入 | 代码生成插件版本耦合最紧，先保证骨架能编译 |
| 包名 `app.myplayer.*` | **相同** | 刻意保持一致，参考代码可直接沿用 |

---

## 另一份值得保留的过程文档

`../archive/2026-10-16-初稿/reviews/2026-10-16-docs-review.md`
—— 对初稿的一次完整评审（16 条问题，按严重度分级，含位置与改法）。
想知道「某条规范为什么长这样」时，答案通常在这里。其中与接口相关的结论已并入 [`../05-接口契约.md`](../05-接口契约.md)。

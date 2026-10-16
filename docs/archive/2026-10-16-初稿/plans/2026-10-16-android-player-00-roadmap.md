# 安卓视频播放器 实施路线图（Roadmap）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把设计文档拆成可独立执行、可验收的里程碑计划，按「先跑通一条源、再横向复制」的顺序落地。

**Architecture:** 多模块 Gradle + Clean Architecture + MVVM + 单向数据流；三类媒体源统一为 `MediaSource` 接口；播放内核抽象为 `PlaybackEngine`（当前实现 Media3/ExoPlayer）。

**Tech Stack:** Kotlin 2.1 · Jetpack Compose · Material 3 · Hilt · Room · DataStore · OkHttp · kotlinx.serialization · Media3 · Coil · TinyPinyin。

---

## 单一事实来源

`docs/superpowers/specs/2026-10-16-android-media-player-design.md`（下称 **spec**）。
计划与实现若与 spec 冲突，**以 spec 为准**；需要偏离时先改 spec 再改实现。

## 为什么拆成多个计划

spec 覆盖的子系统差异很大（网络源接入 / 播放内核 / 本地媒体 / 中文搜索 / 应用锁 / 多套 UI），一次性写成一个计划会产生「跨子系统、相互阻塞、无法独立验收」的巨型任务表。因此按里程碑拆分，**每个计划都能独立产出可用、可测的软件**。

## 计划清单

| 编号 | 计划文件 | 对应 spec 里程碑 | 产出（可独立验收） | 状态 |
|---|---|---|---|---|
| P0 | [P0 · 源与授权验证](2026-10-16-android-player-01-m0-source-verification.md) | M0 | 《接口差异对照表》+ 飞牛**账号密码登录**参数清单（无代码，纯验证） | 未开始 |
| P1 | [P1 · 骨架 + Emby 源 + 应用锁](2026-10-16-android-player-02-m1-foundation-and-emby.md) | M1 | 能浏览 Emby 库并显示海报；冷启动/回前台需解锁 | 未开始 |
| P2 | P2 · 播放闭环（`core:playback`） | M2 | 播放 Emby 内容 + 进度上报 + 通知栏/PiP + 切后台自动暂停 | 待 P1 完成后编写 |
| P3 | P3 · 差异化（预览条 / 无卡顿 seek / 本地排序 / 中文搜索） | M3 | 痛点 2、痛点 3、中文搜索场景自测通过 | 待 P0/P2 结论 |
| P4 | P4 · 飞牛影视源（**仅账号密码登录**） | M4 | 与 Emby 同等功能 | 依赖 P0 结论 |
| P5 | P5 · 打磨与设置 | M5 | 平板布局、错误态、多服务器管理、设置页（含主题）、关于页许可声明 | 待 P3 完成后 |
| L1 | 后期 · 飞牛授权登录（NAS OAuth / 扫码 / 一次性授权码） | L1 | 在不影响账号密码登录的前提下补强登录体验 | 后期，待 M4 完成后 |
| L2 | 后期 · 本机视频文件源（MediaStore + SAF） | L2 | 播放手机本地视频 + 与服务端条目混排排序 | 后期，待 M5 完成后 |

**执行顺序**：P0 → P1 → P2 → P3 →（P4 可与 P3 并行）→ P5 → L1 / L2。
P0 不产出代码，但它决定 P4 的登录与请求参数，必须先做（可与 P1 并行）。

**范围调整（已按需求确认）**
- 飞牛影视**先只做「影视账号密码」登录**；NAS 账号授权 / 扫码 / 一次性授权码全部后置到 **L1**，不进 M0–M5。
- **Emby 恒为账号密码**，无授权流程。
- **本机视频文件**整体后置到 **L2**；M0–M5 只需保证 `MediaSource` 抽象与 `SourceType.LOCAL` / `ImageSource.Local` 已就位。
- **主题只做亮 / 暗两套**（跟随系统 + 手动三态），见 spec §14.0。

## 依赖关系

```
P0 ─────────────────────────┬──────────────► P4 (飞牛登录/请求参数)
                            │
P1 ──► P2 ──► P3 ───────────┴──► P5 ──►┬──► L1（飞牛授权登录）
        │                                └──► L2（本机视频文件）
        └──► P4（复用 :source:httpapi 骨架，可并行）
```

- P1 提供：`:core:model` / `:core:database` / `:core:security` / `:core:datastore` / `:core:network` / `:source:httpapi` / `:core:designsystem`，是 P2–P5 与 L1/L2 的地基。
- P2 提供：`:core:playback` 的 `PlaybackEngine` 抽象与 `MediaSessionService`。
- P3 依赖 P2 的播放器页与 P1 的本地索引能力。
- **L1 只改 `:source:jellyfin` 的登录流程**，不动 httpapi 骨架；**L2 只新增 `:source:local` 模块**，不动上层。

## 各里程碑验收口径（Definition of Done）

| 里程碑 | DoD |
|---|---|
| **M0** | 输出 `docs/superpowers/specs/2026-10-16-m0-findings.md`，含：飞牛 `:8005` 路径前缀/鉴权头/`PlaybackInfo` 差异、**飞牛账号密码登录**（§5.2 路径 ①）的端点与失败分支、Emby 与飞牛的 trickplay 端点与参数。所有结论标注「已验证 / 未验证」。**未验证的接口不得进入生产代码路径**。 |
| **M1** | 真机可：① 添加 Emby 服务器并登录；② 看到库列表与海报网格（数据落 Room）；③ 冷启动与回前台需先解锁，九宫格图案可解锁；④ 存储的令牌为密文。 |
| **M2** | 真机可：① 播放 Emby 内容（直放优先、转码兜底）；② 播放中每 10s 上报进度、暂停/退出立即上报；③ 通知栏与画中画可控；④ 切后台默认暂停，回前台先解锁再恢复。 |
| **M3** | 真机可：① 进度条拖动实时缩略图预览，松手前不 seek、无 1 秒卡顿；② 库内可按时长/文件大小/码率/分辨率等本地排序，无 loading；③ 中文关键词（含拼音全拼/首字母）能搜到结果。 |
| **M4** | 真机可：飞牛影视 `:8005` 用**影视账号 + 密码**完成登录，并具备与 Emby 等价的浏览 / 播放 / 进度能力。**不含** NAS 授权 / 扫码（属 L1）。 |
| **M5** | 平板/折叠屏布局可用；错误态可重试；多服务器可增删；设置项按附 B 全部生效（含主题三态）；关于页含 LGPL/FFmpeg 声明。 |
| **L1（后期）** | 飞牛影视可在账号密码之外，用 NAS 账号授权 / 扫码完成登录，且不影响原有账号密码路径。 |
| **L2（后期）** | 真机可：扫描并播放本机视频，外挂字幕被发现，本地条目与服务端条目在同一列表混排排序。 |

## 全局工程约定

- **语言 / 模块**：Kotlin；包名前缀 `app.myplayer`；`feature → source/core → core:model` 单向依赖，`:source:*` 之间互不依赖。
- **测试**：纯 Kotlin 逻辑用 JVM 单测（JUnit4 + Truth）；Android 相关（Room/DataStore/Compose）用 Robolectric 或 instrumented 测试；网络用 MockWebServer。
- **提交**：每个任务结束提交一次；对没有 git 仓库的环境，首个任务先 `git init`。
- **版本**：统一由 `gradle/libs.versions.toml` 管理（见 P1 Task 1）。
- **不做**：服务器账号体系、内容源内置、多服务器库混排、局域网自动发现。

## 执行方式

计划写好后有两种执行方式：

1. **子代理驱动（推荐）**：每个任务派一个全新子代理实现，任务间由主代理审查，迭代快、上下文干净。→ 需加载 `superpowers:subagent-driven-development`。
2. **会话内执行**：在当前会话里按批次执行，带检查点。→ 需加载 `superpowers:executing-plans`。

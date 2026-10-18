# P0 · 源与授权验证（M0）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在写任何生产代码前，用真机抓包/实测确认飞牛影视 `:8005` 与 Emby 的接口差异、飞牛授权路径、以及两边的 trickplay 端点与参数，产出一份带证据的《接口差异对照表》。

**Architecture:** 纯验证，无代码产出。所有命令在**本机（能访问 NAS 的机器）**执行；结论写入 `docs/superpowers/specs/2026-10-16-m0-findings.md`。

**Tech Stack:** curl / 浏览器开发者工具 / 抓包工具（Charles 或 mitmproxy 或 Proxyman）/ 飞牛影视 App / Emby 服务器。

---

## 前置准备

- 准备一台**已安装并登录飞牛影视**的 NAS，以及一个可用的 Emby 服务器（有内容）。
- 记录两个地址：
  - 飞牛影视：`FN_BASE`（形如 `http://192.168.1.10:8005`）
  - Emby：`EMBY_BASE`（形如 `http://192.168.1.20:8096`）
- 准备一个 Emby 的账号密码，及飞牛的「影视账号密码」。
- 抓包工具需能解密 HTTPS；若飞牛为自签证书，先在工具中信任。

> ⚠️ 所有结论必须**标注证据来源**（截图 / 原始响应 / 抓包记录）。**未验证的接口一律标记「未验证」，不得进入生产代码路径**（spec §5.2、§12-R1/R8）。

---

### Task 1: 确认飞牛 `:8005` 的路径前缀与设备信息端点

**Files:**
- Create: `docs/superpowers/specs/2026-10-16-m0-findings.md`（本计划所有任务都写入此文件）

- [ ] **Step 1: 探测 System/Info/Public（无鉴权）**

运行（把 `$FN_BASE` 换成实际地址）：

```bash
curl -s -i "$FN_BASE/System/Info/Public" | head -40
```

- [ ] **Step 2: 若上一步 404，尝试常见前缀**

```bash
curl -s -i "$FN_BASE/emby/System/Info/Public"  | head -40
curl -s -i "$FN_BASE/jellyfin/System/Info/Public" | head -40
curl -s -i "$FN_BASE/media/System/Info/Public" | head -40
```

- [ ] **Step 3: 记录结论到 findings 文件**

在 `2026-10-16-m0-findings.md` 写入下表并填实测值：

```markdown
## 1. 飞牛 :8005 路径前缀与设备信息

- 成功前缀：<填空，如 `(无前缀)` 或 `/emby`>
- 证据：<粘贴 curl 命令与响应前 20 行>
- `/System/Info/Public` 响应 JSON：
  - ServerName = <填空>
  - Version = <填空>
  - ProductName = <填空>   ← 决定 §5.2「设备识别」是否按 fnOS 走差异化
- 结论：`{base}` = `<实际前缀>` ，`:source:jellyfin` 的 `baseUrl` 拼接规则 = <填空>
```

- [ ] **Step 4: 提交**

```bash
git add docs/superpowers/specs/2026-10-16-m0-findings.md
git commit -m "docs(m0): record fnOS :8005 path prefix and server info"
```

> 若仓库尚未初始化，先执行 `git init && git add -A && git commit -m "chore: init repo"`。

---

### Task 2: 确认飞牛登录接口与鉴权头

**Files:**
- Modify: `docs/superpowers/specs/2026-10-16-m0-findings.md`

- [ ] **Step 1: 用「影视账号密码」尝试 AuthenticateByName（Emby 风格头）**

```bash
curl -s -i -X POST "$FN_BASE/Users/AuthenticateByName" \
  -H 'Content-Type: application/json' \
  -H 'X-Emby-Authorization: MediaBrowser Client="mplayer", Device="pc", DeviceId="m0-test", Version="0.1.0"' \
  -d '{"Username":"<影视账号>","Pw":"<影视密码>"}'
```

- [ ] **Step 2: 再试 Jellyfin 风格头**

```bash
curl -s -i -X POST "$FN_BASE/Users/AuthenticateByName" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: MediaBrowser Client="mplayer", Device="pc", DeviceId="m0-test", Version="0.1.0"' \
  -d '{"Username":"<影视账号>","Pw":"<影视密码>"}'
```

- [ ] **Step 3: 用返回的 token 验证后续请求头**

```bash
TOKEN='<上一步返回的 AccessToken>'
curl -s -i "$FN_BASE/Users/Me" -H "X-Emby-Token: $TOKEN" | head -20
curl -s -i "$FN_BASE/Users/Me" -H "Authorization: MediaBrowser Token=\"$TOKEN\"" | head -20
curl -s -i "$FN_BASE/Users/Me?api_key=$TOKEN" | head -20
```

- [ ] **Step 4: 记录**

```markdown
## 2. 飞牛登录与鉴权头

- 登录端点：`<xxx>/Users/AuthenticateByName`，HTTP 状态 = <200/401/404>
- 生效的请求头形态：<X-Emby-Authorization / Authorization: MediaBrowser / 其它>
- 后续请求鉴权方式：<X-Emby-Token / Authorization: MediaBrowser Token= / api_key 查询参数>
- 返回字段：AccessToken=<是否返回>，User.Id=<是否返回>
- 失败分支实测：错误密码 → <状态码>；错误端口 → <状态码>
- 结论：`:source:jellyfin` 需覆盖的鉴权参数 = <填空>
```

- [ ] **Step 5: 提交**

```bash
git add docs/superpowers/specs/2026-10-16-m0-findings.md
git commit -m "docs(m0): record fnOS auth endpoint and header variant"
```

---

### Task 3: 确认飞牛 PlaybackInfo 与直连/转码 URL 形态

**Files:**
- Modify: `docs/superpowers/specs/2026-10-16-m0-findings.md`

- [ ] **Step 1: 取一个条目的 Id**

```bash
TOKEN='<Task 2 得到的 token>'
USER_ID='<Task 2 得到的 User.Id>'
curl -s "$FN_BASE/Users/$USER_ID/Items?Recursive=true&IncludeItemTypes=Movie&Limit=3&Fields=MediaSources" \
  -H "X-Emby-Token: $TOKEN" | head -60
```

- [ ] **Step 2: 请求 PlaybackInfo**

```bash
ITEM_ID='<Step 1 得到的条目 Id>'
curl -s -i -X POST "$FN_BASE/Items/$ITEM_ID/PlaybackInfo?UserId=$USER_ID" \
  -H "X-Emby-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"DeviceProfile":{"MaxStreamingBitrate":120000000,"DirectPlayProfiles":[{"Container":"mp4,mkv","Type":"Video"}]}}' \
  | head -80
```

- [ ] **Step 3: 验证直连与 HLS 地址**

```bash
curl -s -i "$FN_BASE/Videos/$ITEM_ID/stream?static=true&MediaSourceId=$ITEM_ID&api_key=$TOKEN" -H 'Range: bytes=0-1023' | head -20
curl -s -i "$FN_BASE/Videos/$ITEM_ID/master.m3u8?MediaSourceId=$ITEM_ID&api_key=$TOKEN" | head -20
```

- [ ] **Step 4: 记录**

```markdown
## 3. 飞牛 PlaybackInfo / 直连 / HLS

- PlaybackInfo 端点与参数差异：<填空>
- `MediaSources[]` 中 `SupportsDirectPlay / SupportsDirectStream / SupportsTranscoding` 是否返回：<是/否>
- 直连接口：`<实际路径>`（是否需 `static=true`、`MediaSourceId`、`api_key`）：<填空>
- HLS 入口：`<实际路径>`；`TranscodingUrl` 字段名 = <填空>
- `DeviceProfile` 字段名差异：<填空>
- 结论：`PlaybackNegotiator` 对飞牛需覆盖的参数 = <填空>
```

- [ ] **Step 5: 提交**

```bash
git add docs/superpowers/specs/2026-10-16-m0-findings.md
git commit -m "docs(m0): record fnOS playback negotiation and stream urls"
```

---

### Task 4: 实测 Emby 与飞牛的 trickplay 端点与参数

**Files:**
- Modify: `docs/superpowers/specs/2026-10-16-m0-findings.md`

- [ ] **Step 1: 飞牛 trickplay（Jellyfin 10.9+ 形态）**

```bash
ws=320   # 依次试 320 / 240 / 160
curl -s -i "$FN_BASE/Videos/$ITEM_ID/Trickplay/$ws/tiles.m3u8" -H "X-Emby-Token: $TOKEN" | head -30
curl -s -i "$FN_BASE/Videos/$ITEM_ID/Trickplay/$ws/0.jpg" -H "X-Emby-Token: $TOKEN" | head -5
```

- [ ] **Step 2: Emby trickplay（端点待确认，依次尝试）**

```bash
curl -s -i "$EMBY_BASE/Videos/$ITEM_ID/Trickplay/$ws/tiles.m3u8?api_key=$EMBY_TOKEN" | head -30
curl -s -i "$EMBY_BASE/Items/$ITEM_ID/Trickplay/$ws/tiles.m3u8?api_key=$EMBY_TOKEN" | head -30
curl -s -i "$EMBY_BASE/Videos/$ITEM_ID/Trickplay/0.jpg?api_key=$EMBY_TOKEN" | head -5
```

- [ ] **Step 3: 解析 `tiles.m3u8` 内容**

记录：分片命名规则（`{index}.jpg`？）、`#EXT-X-TILES` 标签（`RESOLUTION=WxH`、`LAYOUT=NxM`、`INTERVAL=秒`）、分片数量与总时长覆盖。示例：

```markdown
## 4. Trickplay 端点与参数

### 飞牛
- 端点：`<实际可用路径>`
- 可用宽度档位：<320/240/160 中哪些返回 200>
- `tiles.m3u8` 示例（贴前 10 行）：
  ```
  <粘贴>
  ```
- 标签解析：RESOLUTION=WxH <填空>；LAYOUT=NxM <填空>；INTERVAL=<填空>；分片数=<填空>
- 图片 URL 规则：<填空>

### Emby
- 端点：`<实际可用路径>`（若三者皆 404 → 标记 `trickplay 不可用`，按 R3 降级）
- 参数/标签差异相对飞牛：<填空>
```

- [ ] **Step 4: 提交**

```bash
git add docs/superpowers/specs/2026-10-16-m0-findings.md
git commit -m "docs(m0): record trickplay endpoints and tile layout"
```

---

### Task 5: 冻结飞牛「影视账号密码」登录参数（M4 唯一登录路径）

> **范围说明（已按需求调整）**：M4 只做**影视账号密码**登录，因此 M0 **只验证这一条**。NAS 账号授权 / 扫码 / 一次性授权码属于后期迭代 **L1**，本任务**不抓包、不验证**，仅在结论里标注「后置 L1」。

**Files:**
- Modify: `docs/superpowers/specs/2026-10-16-m0-findings.md`

- [ ] **Step 1: 用真机（飞牛 App 或浏览器）实测密码登录的成功与失败分支**

```bash
# 成功分支（Task 2 已跑通一次，这里补齐边界）
curl -s -i -X POST "$FN_BASE/Users/AuthenticateByName" \
  -H 'Content-Type: application/json' \
  -H 'X-Emby-Authorization: MediaBrowser Client="mplayer", Device="pc", DeviceId="m0-test", Version="0.1.0"' \
  -d '{"Username":"<影视账号>","Pw":"<正确密码>"}' | head -20

# 失败分支 1：密码错误
curl -s -i -X POST "$FN_BASE/Users/AuthenticateByName" \
  -H 'Content-Type: application/json' \
  -H 'X-Emby-Authorization: MediaBrowser Client="mplayer", Device="pc", DeviceId="m0-test", Version="0.1.0"' \
  -d '{"Username":"<影视账号>","Pw":"<错误密码>"}' | head -20

# 失败分支 2：账号不存在
curl -s -i -X POST "$FN_BASE/Users/AuthenticateByName" \
  -H 'Content-Type: application/json' \
  -H 'X-Emby-Authorization: MediaBrowser Client="mplayer", Device="pc", DeviceId="m0-test", Version="0.1.0"' \
  -d '{"Username":"not_exist_zzz","Pw":"x"}' | head -20
```

记录每个分支的 HTTP 状态码与响应体，用于 P4 的错误文案映射（对应 spec §5.2「失败分支」）。

- [ ] **Step 2: 记录**

```markdown
## 5. 飞牛登录结论（M4 只需账号密码）

### 唯一实现路径：① 影视账号密码
- 登录端点：`<前缀>/Users/AuthenticateByName`
- HTTP 状态（成功 / 密码错 / 账号不存在）：<200 / 401 / 401>
- 生效请求头：<X-Emby-Authorization 等>
- 返回：AccessToken=<有无>、User.Id=<有无>
- 后续请求鉴权方式：<X-Emby-Token 等（见 Task 2）>
- 复用性：<可复用 / 需覆盖>
- 结论：`:source:jellyfin` 覆盖参数 = <填空>

### 授权式登录（NAS 授权 / 扫码 / 一次性授权码）
- 状态：**M0 不验证，后置后期迭代 L1**（§5.2 路径 ②③④）。
- M4 的登录页只显示「账号密码」，不显示其它分段控件（spec §17）。
```

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/specs/2026-10-16-m0-findings.md
git commit -m "docs(m0): freeze fnOS password-login parameters"
```

---

### Task 6: 回写 spec 并冻结接口参数清单

**Files:**
- Modify: `docs/superpowers/specs/2026-10-16-android-media-player-design.md`
- Modify: `docs/superpowers/specs/2026-10-16-m0-findings.md`

- [ ] **Step 1: 更新 spec**

将 M0 的已验证结论回写到 spec 对应位置：

- §2 待验证项（⚠️ 段落）→ 改为已确认的具体前缀/鉴权头。
- §5.2 授权方式表 → 把「可行性」列改为实测结论，并在末尾列出 M4 采用顺序。
- §5.2「接口差异」→ 用实测的具体路径前缀、鉴权头、`PlaybackInfo` 字段差异替换推测描述。
- §12 未决问题 1/2/4 → 逐条给出结论并删除该条（或标注「已验证」）。
- §12-R1/R8 → 状态改为「已验证/已缓解」。

- [ ] **Step 2: 在 findings 末尾追加参数清单**

```markdown
## 6. 冻结参数清单（供 :source:jellyfin / :source:emby 使用）

### 飞牛 :8005
- baseUrl 拼接：<prefix>
- 鉴权头：<header 形态>，token 字段：<AccessToken>
- Items 端点：`<路径>`，必需参数：<列表>
- 图片：`<路径模板>`
- 直连：`<路径模板>`；HLS：`<路径模板>`
- trickplay：`<路径模板>`，宽度档位：<列表>

### Emby
- （同上逐项，标注与飞牛的差异）
```

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/specs/2026-10-16-android-media-player-design.md docs/superpowers/specs/2026-10-16-m0-findings.md
git commit -m "docs(m0): freeze verified source parameter list and update spec"
```

---

## 完成判定

- [ ] `2026-10-16-m0-findings.md` 已包含 6 个章节，且每条结论都有证据。
- [ ] spec §2 / §5.2 / §12 已按实测结论回写。
- [ ] **参数清单**（第 6 节）足以让 P1（Emby）与 P4（飞牛）不改骨架直接实现。
- [ ] 所有「未验证」项在 findings 中显式列出，且**未被写入生产代码路径**。

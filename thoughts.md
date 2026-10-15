> Note: This document records the idea before I start coding for this project.
> When some features are not implemented yet, this document serves as a guideline.
> However, as the project progresses, this document might be outdated.
> **ALWAYS** use code as ground truth.

# Mastodon 自动英文翻译回复 Bot 设计文档 (Kotlin / JVM)

## 1. 背景与设计目标

### 1.1 背景痛点
- 账号从英文实例迁移至自建实例（`m.skyblond.info`，托管于 `masto.host`）后，日常发帖以中文为主，导致原英文圈关注者阅读困难。
- **为什么不依赖 Mastodon 原生翻译**：Mastodon 原生翻译接口（`POST /api/v1/statuses/:id/translate`）强制校验本实例登录态（`require_user!`）。在自己实例配置 DeepL/LibreTranslate 仅供自己阅读外语使用；外站关注者能否看到翻译按钮，完全取决于**对方所在实例**是否配置了翻译后端、原帖语言是否正确标记为 `zh`，且原帖可见性必须为 `public` 或 `unlisted`。

### 1.2 核心设计原则
1. **主账号自回复（Self-reply）**：不使用独立 `@bot` 账号，直接用主号 Token 在原帖下发英文评论，确保外站关注者可通过正常 ActivityPub 联邦机制收到回复，不掉出对话上下文。
2. **完全无状态（Stateless）**：不依赖本地数据库或状态文件。利用 Mastodon Snowflake ID 单调递增特性，每次轮询仅拉取最近 40 条推文即可在内存中完成原帖与翻译帖的配对。
3. **冷却期替代编辑同步**：规定原帖修改均在冷却期（Cooldown，如 1~5 分钟）内完成；以 `edited_at ?: created_at` 为基准，满冷却期后再触发翻译，省去复杂的译文原地修改与分段对齐逻辑。
4. **紧凑尾标与预计算分段**：译文最后一行固定附加 `🌐a1b2c3d4#x#y`（8 位 Hash + 当前段数 `x` + 总段数 `y`），一次性翻译后在内存算出总段数再依次连发。
5. **不引入重型第三方 SDK**：直接使用 `OkHttp` + `kotlinx.serialization` 调用 3~4 个核心 REST 接口，避免第三方 SDK 因 Mastodon 新增字段导致反序列化崩溃。

---

## 2. 准备工作与 Access Token 申请

### 2.1 申请 Mastodon Access Token
1. 使用主账号登录 `https://m.skyblond.info`。
2. 进入 **首选项 (Preferences)** -> **开发 (Development)** -> 点击 **新建应用 (New Application)**。
3. 填写应用名称（如 `AutoTranslateBot`），重定向 URI 保持默认的 `urn:ietf:wg:oauth:2.0:oob` 即可。
4. **权限范围 (Scopes)** 仅需勾选以下最小权限集：
    - `read:accounts`：启动时调用 `verify_credentials` 获取自身 `account_id`。
    - `read:statuses`：拉取个人推文列表、调用 `/source` 获取未渲染 HTML 的纯文本原文。
    - `write:statuses`：发布英文翻译回复（`POST`），以及在异常中断恢复时清理半截残链（`DELETE`）。
5. 保存后，进入该应用详情页，复制顶部的 **Your access token**。

### 2.2 日常发帖设置检查
- 进入 **首选项 (Preferences)** -> **其他 (Other)** -> **发帖语言 (Posting language)**，确保默认发帖语言设为 **中文 (`zh` / `zh-CN`)**，既方便支持原生翻译的外部实例识别，也便于 Bot 通过 `language != "en"` 过滤。

---

## 3. 项目依赖与最小数据模型

### 3.1 Gradle 依赖 (`build.gradle.kts`)
```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

### 3.2 JSON 序列化配置与最小实体模型
务必开启 `ignoreUnknownKeys = true`，仅映射核心稳定字段：

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val jsonParser = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
data class Account(
    val id: String,
    val username: String = ""
)

@Serializable
data class Status(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("edited_at") val editedAt: String? = null,
    val account: Account? = null,
    val content: String = "", // HTML 格式正文
    @SerialName("spoiler_text") val spoilerText: String = "", // CW 折叠警告词
    val visibility: String = "public", // public, unlisted, private, direct
    val sensitive: Boolean = false,
    val language: String? = null,
    @SerialName("in_reply_to_id") val inReplyToId: String? = null,
    @SerialName("in_reply_to_account_id") val inReplyToAccountId: String? = null,
    val reblog: Status? = null
)

@Serializable
data class StatusSource(
    val id: String,
    val text: String, // 发帖时的原始纯文本
    @SerialName("spoiler_text") val spoilerText: String = ""
)
```

---

## 4. 尾标协议与字数切分规则

### 4.1 尾标格式：`🌐{hash}#{x}#{y}`
每条英文翻译回复的**最后一行**固定追加尾标：
- **单段回复（未超长）**：`🌐a1b2c3d4#1#1`（占 14 个字符）
- **多段回复（超长拆分为 3 段）**：
    - 第 1 段末尾：`🌐a1b2c3d4#1#3`
    - 第 2 段末尾：`🌐a1b2c3d4#2#3`
    - 第 3 段末尾：`🌐a1b2c3d4#3#3`

**技术特性**：
1. **不会触发 Mastodon 标签解析**：根据 Mastodon 源码 `Tag::HASHTAG_RE`，`#` 前方必须是行首或空白字符且后方需包含字母才会被识别为 Hashtag。`🌐a1b2c3d4#1#3` 会被当作纯文本原样保留，不会生成 HTML `<a>` 标签。
2. **提取正则**：
   ```kotlin
   val footerRegex = Regex("""🌐([0-9a-f]{8})#(\d+)#(\d+)\s*$""")
   ```
    - `groupValues[1]`：原帖 8 位十六进制 Hash（如对 `P.content + "|" + P.spoilerText` 计算 CRC32）。
    - `groupValues[2]`：当前分段序号 `x`。
    - `groupValues[3]`：总分段数 `y`。

### 4.2 译文预计算分段算法
- **字数上限计算**：Mastodon 的 500 字上限检验公式为 `spoiler_text.length + text.length <= 500`。扣除换行与尾标（约 16~18 字符）及 UTF-16 安全余量，**每段英文正文的最大长度阈值设为：`maxChunkLen = 460 - spoilerText.length`**。
- **贪心切分步骤**：
    1. 一次性从大模型拿到完整英文译文 `fullTranslation`。
    2. 若 `fullTranslation.length <= maxChunkLen`，则总段数 `y = 1`，直接生成 `["$fullTranslation\n🌐${hash}#1#1"]`。
    3. 若超出阈值，优先按自然段 `\n` 拆分，若单段仍超长则按英文句末标点 `(?<=[.!?])\s+` 贪心拼接到当前分片，直至逼近 `maxChunkLen` 开启下一分片。
    4. 切分完成后得到 `chunks: List<String>`，总段数 `y = chunks.size` 已确定，遍历附上 `\n🌐${hash}#${index + 1}#${y}`。

---

## 5. 核心工作流（每分钟无状态轮询）

### 5.1 为什么无状态轮询不需要翻页？
Mastodon 的 Status ID 是按时间单调递增的 Snowflake ID，列表接口按 `ID DESC`（从新到旧）返回。
因为翻译帖 `E` 必然在原帖 `P` 之后发出，所以恒有 **`E.id > P.id`**。
在从新到旧排列的 `limit=40` 列表里，**只要原帖 `P` 还在列表内，它的翻译串 `E1..Ey` 在数学上 100% 保证也在这个列表的前半部分**。因此每次只需 1 次请求即可完成内存配对。

### 5.2 单次轮询 5 步流水线

#### Step 0：启动初始化（仅启动时执行 1 次）
- 调用 `GET /api/v1/accounts/verify_credentials`，获取并缓存当前账号的 `myAccountId`。

#### Step 1：拉取最近状态（每分钟 1 次请求）
- 调用 `GET /api/v1/accounts/{myAccountId}/statuses?limit=40&exclude_reblogs=true`。

#### Step 2：内存分类与回复链还原（0 次请求）
遍历返回的 40 条推文（可先剥离 HTML 标签提取纯文本末尾，或直接用正则匹配 `content` 末尾）：
1. **命中 `footerRegex` 的推文**：归入 **翻译回复池 (`translationPool`)**，解析出 `(hash, x, y)`。
2. **未命中 `footerRegex` 的推文**：归入 **原始推文候选池 (`candidatePool`)**。
3. **还原翻译链**：利用 `in_reply_to_id` 指针，为每个原帖 `P` 查找挂在 `P.id` 下的翻译链 `E1 -> E2 -> ... -> Ek`。

#### Step 3：筛选待翻译推文（0 次请求）
将 `candidatePool` 按发布时间**从旧到新**排序，筛选出同时满足以下全部条件的推文 `P`：
1. **时间水位线（Cutoff）**：`now - P.created_at <= 24 小时`（防止重启时扫描远古未翻译推文）。
2. **冷却期已满（Cooldown）**：取 `effectiveTime = P.editedAt ?: P.createdAt`，需满足 `now - effectiveTime >= configuredCooldown`（如 3 分钟；若在冷却期内编辑过推文，倒计时自动顺延）。
3. **非对外回复**：`P.inReplyToAccountId == null || P.inReplyToAccountId == myAccountId`（只翻译原创主帖和自己的串，不打扰别人评论区）。
4. **包含中文且非英文帖**：`P.language != "en"` 且正文/CW 包含中文字符（`[\u4e00-\u9fff]`）。
5. **尚未完成翻译**：
    - 若 `P.id` 下已存在翻译链 `E1..Ek`，且尾节点 `Ek` 的尾标满足 `x == y`（同时 `k == y`），说明**已翻译完成**，直接跳过。
    - **异常残链自愈**：若 `P.id` 下存在翻译链，但尾节点 `x < y`（说明上次发多段回复时中途网络中断），则先按 **从末端到根部（`Ek -> ... -> E1`）的逆序** 调用 `DELETE /api/v1/statuses/{id}` 清理残链，再进入 Step 4 重新翻译。

#### Step 4：获取纯文本、回溯 Context 并调用 LLM（1 次 `/source` + 1 次 LLM 请求）
1. **获取纯文本**：调用 `GET /api/v1/statuses/{P.id}/source`，拿到未渲染 HTML 的 `source.text` 与 `source.spoilerText`。
2. **计算 Hash**：对 `P.content + "|" + P.spoilerText` 计算 32 位哈希并格式化为 8 位十六进制小写字符串（如 `CRC32`）。
3. **回溯父推文 Context（若 `P.inReplyToId != null`）**：
    - 在内存列表中顺着 `inReplyToId` 向上回溯最多 `N` 条（如 `N = 3~5`）父推文 `P_parent` 及其已有的翻译链 `E_parent`。
    - 若串极长超出最近 40 条，可降级调用 1 次 `GET /api/v1/statuses/{P.id}/context` 获取 `ancestors`。
4. **构造多轮对话 Prompt（Multi-turn Messages）请求 OpenAI 兼容接口**：
    - `system`：要求将中文推文地道翻译为英文；**严格保留 `@username`、`#hashtag`、URL 链接和自定义表情 `:shortcode:` 原样不变**；若原文含 CW (`spoiler_text`) 则一并翻译；仅输出译文，不要解释。
    - `user` (历史 1)：`{父推文 1 原文}`
    - `assistant` (历史 1)：`{父推文 1 的已有英文译文（剥离尾标）}`
    - ...
    - `user` (当前)：`{当前推文 P 的 source.text}`

#### Step 5：分段连发英文回复（`y` 次 `POST` 请求）
1. 按 4.2 节算法将译文切分为 `y` 段，每段末尾附上 `\n🌐${hash}#${x}#${y}`。
2. 确定回复可见性：
    - 若原帖 `P.visibility == "public"`，建议回复设为 `"unlisted"`（静默公开，既能让所有人顺着串读到，又避免多段英文回复刷屏实例公共时间线）；
    - 其他可见性（`unlisted`、`private`、`direct`）保持与原帖 `P.visibility` 一致。
3. 依次调用 `POST /api/v1/statuses`：
    - 第 1 段：`in_reply_to_id = P.id`，`language = "en"`，若原帖有 CW 则带上翻译后的 `spoiler_text`。
    - 第 `i` 段（`i > 1`）：`in_reply_to_id = E_{i-1}.id`，`language = "en"`。

---

## 6. Mastodon 速率限制与安全备忘

| 接口类型 | Mastodon 默认限流阈值 | 本方案实际消耗 | 安全注意事项 |
| :--- | :--- | :--- | :--- |
| 常规 API (`GET` / `POST`) | 300 次 / 5 分钟 (Per Token) | 轮询 5 次 / 5 分钟（约 1.6%） | 极度充裕，无需担心触发 429 |
| 删帖 API (`DELETE`) | 30 次 / 30 分钟 (Per User) | 正常情况为 0，仅网络中断恢复时触发 | 清理残链时**必须从叶子节点往根节点倒序删除**（`Ek -> E1`），防止正序删掉 `E1` 后断网导致后续节点失去 `in_reply_to_id` 关联变成孤儿帖 |
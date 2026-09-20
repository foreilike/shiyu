# 任务书：语言陪练 App（LangPartner，英语 + 日语）从零实现

| 项 | 内容 |
|---|---|
| 文档版本 | v2.0（双语版；v1.0 仅日语，已被本文档取代） |
| 编制日期 | 2026-09-20 |
| 执行方 | 待定（由交付方指定的编码模型，自行在 REPORT 中署名） |
| 工作目录 | `D:\AI project\lang-partner` |
| 交付形态 | 可构建的 Android 工程 + debug APK + 单测全绿 + 自述报告 |
| 验收方式 | 客观命令 + 独立核验（规则已在本文件完整给出，按规则实现即可通过） |

---

## §0 阅读须知

1. 本文件同时是一份 **PRD**（第 4 章）与一份 **工程规格书**（第 5 章）。产品意图与实现细节都已定稿，**不需要你再做需求澄清或方案选型**。
2. 第 5 章里的算法规则是 **规范性（normative）** 的：每一条都给出了完整判定规则。凡本文件明确了的行为，实现必须与之逐字一致；核验方持有对应的完整测试向量表。
3. 本文件已经写明了 **本机环境的全部坑**（第 2 章、第 12 章）。请**逐条遵守**。如果构建失败的原因属于第 12 章已列出的项目，视为未按任务书执行。
4. 遇到本文件未覆盖的细节，按最小惊讶原则自行决定，并在 REPORT 的「自主决策」一节中列出。
5. **本次要同时交付「日语」与「英语」两个学习模块**，且两者必须共用同一套核心（排卡、对话、Provider、存储），差异只允许出现在 §5.6 定义的语言模块内部。

---

## §1 一句话目标

从零实现一个 **单机、可离线、双语（英语 + 日语）、可插拔 AI 语音接口** 的语言学习 Android App：离线刷卡片（间隔重复），在线跟 AI 做场景化口语对话（语音输入 → 转写 → 大模型回复 → 语音朗读），AI 负责纠错。**英语与日语共用同一套核心逻辑，语言差异收口在 `LanguageModule` 里。所有 AI 能力通过统一接口注入，凭证由用户在设置页按语言分别填写，代码里不含任何密钥。**

---

## §2 环境事实（不可协商，逐条遵守）

### 2.1 工具链（这台机器上已验证可构建的组合，请原样锁定）

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | **8.9** | `gradle/wrapper/gradle-wrapper.properties` 里 `distributionUrl` 必须指向 `gradle-8.9-bin.zip`。本地已有该发行版缓存。 |
| Android Gradle Plugin | **8.7.3** | 在根 `build.gradle.kts` 的 `plugins {}` 中声明 |
| Kotlin 插件 | **2.1.0** | 同上 |
| compileSdk / targetSdk | **35** | 本机 Android SDK 安装了 `android-35`；**没有 android-34**，不要写 34 |
| minSdk | **26** | |
| Java 源码/目标级别 | **17** | `sourceCompatibility`/`targetCompatibility`/`jvmTarget` 全部 17 |
| buildToolsVersion | **36.0.0** | 必须在 `android {}` 里显式声明（本机没有 35.0.0） |
| SDK 路径 | `sdk.dir=D\:/AndroidSdk` | 写入 `local.properties`，注意转义写法 |

### 2.2 JDK：本机最大的坑

本机环境变量 `JAVA_HOME` 指向 **JDK 11**，而 AGP 8.7.3 要求 **Java 17+**，直接用会构建失败：

```
Android Gradle plugin requires Java 17 to run. You are currently using Java 11.
```

**唯一可用的 JDK 是 `D:\a\jbr`（JBR 21，Android Studio 自带）。** 每次执行 Gradle 前必须先设置：

```powershell
$env:JAVA_HOME = "D:\a\jbr"
```

### 2.3 不要使用的组件（本地 Gradle 缓存中不存在）

为了构建可复现、失败原因可归因，**以下组件的本地缓存均不存在，禁止使用**（它们会触发联网下载，一旦下载失败会把工具链问题误判成实现问题）：

- Jetpack Compose（含 `org.jetbrains.kotlin.plugin.compose`）
- `androidx.security:security-crypto`（即 `EncryptedSharedPreferences`）
- `com.squareup.okhttp3:mockwebserver`
- `androidx.navigation:*`
- kotlinx-serialization 的 Gradle 插件（`org.jetbrains.kotlin.plugin.serialization`）

因此本任务规定：

- **UI 用 Views + XML + ViewBinding**（与本机已验证工程一致），不用 Compose
- **加密存储用 Android Keystore 自己实现**（见 §5.10），不依赖 security-crypto
- **网络层单测用手写的假 Transport 注入**（见 §5.2/§5.5），不用 MockWebServer
- **页面跳转用 Activity / FragmentTransaction**，不用 Navigation 组件；`androidx.fragment` 由 appcompat 传递引入，不要单独声明 `fragment-ktx`
- **JSON 用 `org.json`**（Android 内置），不用 kotlinx-serialization

### 2.4 其他环境注意

- `gradle.properties` 必须包含 `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8`（源码含大量日文与中文，编码错了会乱码）
- **不要**打开 `android.nonTransitiveRClass=true`（本机已验证会导致 `R` 类找不到）
- 首次构建需要联网下载缺失的 AndroidX 依赖；本机可联网

### 2.5 隔离要求

**严禁读取、修改、删除 `D:\AI project` 下的任何其他工程**（尤其是 `bottom-radar-android`；`nihongo-partner` 已废弃、无需理会）。本任务的产出只允许落在 `D:\AI project\lang-partner` 内。

---

## §3 工程骨架

### 3.1 目录结构（必须遵守）

```
D:\AI project\lang-partner\
├── settings.gradle.kts              rootProject.name = "LangPartner"; include(":app")
├── build.gradle.kts                 声明 AGP 8.7.3 / Kotlin 2.1.0，apply false
├── gradle.properties                见 §2.4
├── local.properties                 sdk.dir=D\:/AndroidSdk
├── gradle\wrapper\gradle-wrapper.properties     gradle-8.9-bin.zip
├── gradlew / gradlew.bat            Gradle 8.9 的 wrapper 脚本
└── app\
    ├── build.gradle.kts             namespace/applicationId = com.langpartner.app
    ├── proguard-rules.pro
    └── src\
        ├── main\
        │   ├── AndroidManifest.xml
        │   ├── assets\
        │   │   ├── kana.json            （日语图表：五十音）
        │   │   ├── n5_vocab.json        （日语卡组）
        │   │   ├── scenarios_ja.json    （日语场景）
        │   │   ├── en_phonemes.json     （英语图表：IPA 音素表）
        │   │   ├── en_words.json        （英语卡组）
        │   │   └── scenarios_en.json    （英语场景）
        │   ├── java\com\langpartner\app\   （包结构见 §5.1）
        │   └── res\                       布局、drawable、values（见 §4.2）
        └── test\java\com\langpartner\app\ 纯 JVM 单元测试
```

**Gradle wrapper 怎么来**：本机 `C:\Users\admin\.gradle\wrapper\dists\` 下已有 `gradle-8.9-bin` 发行版，`gradlew` / `gradlew.bat` / `gradle-wrapper.jar` 可以从现有工程复制（例如 `D:\AI project\bottom-radar-android\gradle\wrapper\`，**只读复制，不得修改对方工程**），或由已安装的 Gradle 生成。

### 3.2 应用标识

| 项 | 值 |
|---|---|
| rootProject.name | `LangPartner` |
| namespace / applicationId | `com.langpartner.app` |
| App 显示名 | `语言陪练` |
| versionCode / versionName | `1` / `"0.1.0"` |

---

## §4 产品需求（PRD）

### 4.1 目标用户与核心场景

**用户**：一名自学者，同时学英语与日语，每天通勤 + 睡前各 10 分钟。

| 场景 | 用户想要 | 对应功能 |
|---|---|---|
| 通勤路上（无网/不想耗流量） | 刷单词卡，忘记的自动多出现几次 | M1 卡片训练 |
| 通勤路上 | 今天想练英语，明天想练日语 | M0 语言切换 |
| 睡前（有网） | 挑一个场景，用嘴说目标语言，AI 用该语言回我、顺便指出我说错的地方 | M2 对话练习 |
| 换了个语音服务商 | 不想改代码，在设置页换地址和密钥就能用；两个语言还可能用不同厂商 | M3 可插拔 Provider（按语言分别配置） |
| 想看进度 | 英语多少张、日语多少张、各自掌握多少、聊过几次 | M5 统计 |

### 4.2 信息架构与页面

底部导航 3 个 Tab；**「学习」与「对话」两页顶部各有一条语言切换条**（日语 / 英语），默认取上次使用的语言。

| Tab | 页面 | 内容 |
|---|---|---|
| **学习** | `StudyActivity`（首页） | 顶部语言切换条（日语 / 英语）；今日进度条（`今日待复习 N / 已掌握 M`）；中央卡片区；底部 4 个评分按钮；右上角入口进图表页 |
| | `ChartActivity` | 语言化图表（日语 = 五十音表，平/片假名切换；英语 = IPA 音素表，按 vowel/consonant 分节）；点击格子显示说明并朗读 |
| **对话** | `ChatListActivity` | 当前语言下的 5+ 个场景卡片（目标语言标题 + 中文副标题 + 已完成轮数）；顶部同样有语言切换条 |
| | `ChatActivity` | 消息列表（气泡：用户右、AI 左）；AI 气泡内可折叠显示「纠错」；**AI 正文按 `EnSentenceSplitter` / 逐句切分后逐句可点击朗读**；底部输入区：[文本框] [发送] [录音/停止] |
| **我的** | `ProfileActivity` | 按语言分组的统计（总卡数 / 今日待复习 / 已掌握 / 累计对话轮数）；「设置」入口；「清空全部数据」 |
| | `SettingsActivity` | **顶部语言切换**；下方三组 Provider 配置卡片（对话 / 语音识别 / 语音合成），每张卡片含 baseUrl、apiKey、model、voice（仅合成）、启用开关、「测试连接」；底部「保存」。切换语言时表单随之切换，**两个语言的配置互不影响** |

### 4.3 功能需求

#### M0 语言切换（新增，贯穿全局）

- **M0-1** 全局维护一个「当前语言」（`ja` / `en`），持久化到设置文件，App 重启后保持。
- **M0-2** 切换语言后：卡组、复习进度、场景列表、Provider 配置、图表内容、统计数字**全部随之切换，且互不串数据**。
- **M0-3** 学习进度按语言隔离存储（见 §5.11），**同一个 `cardId` 在两种语言下必须是两条独立进度**。
- **M0-4** 切换语言不得丢失任何一方的进度，不得崩溃。

#### M1 卡片训练（**纯离线，不依赖网络**）

- **M1-1 今日任务**：启动时按当前语言的本地进度排序取卡。排序规则：`dueEpochDay <= 今天` 的卡优先，按 `dueEpochDay` 升序；不足 20 张时用未学过的卡补足；仍不足则展示提示「今日已完成」。
- **M1-2 卡片正面**：
  - 日语：`kanji`（为空则显示 `kana`），副标题 `JLPT N5`，下方中文释义
  - 英语：**中文释义**，副标题 `A1 · n.`（等级 + 词性），下方**例句挖空**（把 `example` 里的目标词替换为 `____`）
- **M1-3 作答**：用户在同一个输入框里输入答案。**输入内容由语言模块决定**：
  - 日语 = 假名读法（可用平假名/片假名/半角片假名/带空格/带标点）
  - 英语 = 英文单词拼写（大小写、连字符、空格、标点均不影响判定）
  - 占位提示文案由语言模块给出（`LanguageModule.answerHint`）
  - 点「确认」后翻转，显示：正确答案、读音提示（日语=罗马字，英语=IPA）、判定结果
- **M1-4 自我评分**：翻转后出现 4 个按钮，语义与 SM-2 质量值固定映射如下（**必须严格使用这组数值**）：

  | 按钮文案 | quality |
  |---|---|
  | 完全不会 | 2 |
  | 有点模糊 | 3 |
  | 想起来了 | 4 |
  | 太简单了 | 5 |

- **M1-5 判定规则**：作答对错由 §5.7 的 `AnswerJudge` 给出，**必须先把用户输入与标准答案都经语言模块归一化再比较**（用户写全角、写片假名、带空格、带标点、大小写不同都算对）。
- **M1-6 排卡**：每次评分调用 §5.8 的 `Sm2Scheduler`，结果立刻落盘（§5.11），App 被杀掉不丢进度。
- **M1-7 图表页**：点击某格用系统 TTS（`android.speech.tts.TextToSpeech`）读出该格内容——**这是系统自带能力，不占用本次的 AI Provider**。

#### M2 对话练习（在线，**必须可降级**）

- **M2-1 场景**：从当前语言的 `scenarios_*.json` 读取，**每种语言至少 5 个**，见 §4.4。
- **M2-2 进入场景**：显示 `openingLine`（AI 的第一句话）与「目标表达」清单。
- **M2-3 一轮对话的完整链路**：

  ```
  [用户点录音] → MediaRecorder 录 m4a → 停止
      → SpeechToTextProvider.transcribe()   （语音识别，language 取当前语言）
      → 得到用户文本，追加为 user 消息
      → 把 场景 systemPrompt + 历史 交给 ContextTrimmer（§5.9）
      → ChatProvider.chat()                  （大模型回复）
      → ReplyParser 解析出「正文 / 中文翻译 / 纠错说明」
      → AI 消息上屏（正文再经 EnSentenceSplitter 切句以获得逐句朗读能力）
      → TextToSpeechProvider.synthesize()    （语音合成）→ MediaPlayer 播放
      → 整轮落盘到会话记录
  ```

- **M2-4 纠错呈现**：AI 回复必须用如下分隔格式（`systemPrompt` 里已要求模型照此输出），App 需**按行首标记解析**，解析不到就把整段当正文、不显示纠错块。主标签由语言模块给出（日语 `[JA]`，英语 `[EN]`）：

  ```
  [<TAG>] <目标语言回复，1-3 句>
  [ZH]    <对应的中文翻译>
  [FIX]   <对用户上一句的语法/用词纠正；若用户没有错误则输出：无>
  ```
  解析规则：按行扫描，行首匹配 `[TAG]` / `[ZH]` / `[FIX]`（**方括号内的标签大小写不敏感**，标签前后允许空格）；缺失的段留空字符串；**不得因为格式不符而抛异常**。

- **M2-5 文本输入路径（降级）**：任何时候都必须允许直接打字发送。以下情况自动切到文本路径并给出明确提示，**不得崩溃、不得卡死、不得只显示"网络错误"**：
  - 用户拒绝麦克风权限
  - 未配置语音识别 Provider（当前语言）
  - 语音识别失败
- **M2-6 无凭证时的 Mock 路径**：当前语言的三个 Provider 都未配置或都禁用时，对话仍必须能跑完一轮：`MockProvider` 返回一条同时含主标签 / `[ZH]` / `[FIX]` 三段、且正文段带 `(mock)` 字样的预置回复。**这是验收 V3 的硬要求。**
- **M2-7 录音参数（固定，勿改）**：`MediaRecorder`，容器 `MPEG_4`，编码 `AAC`，采样率 `16000` Hz，单声道，码率 `64000`；文件写 `cacheDir/rec_<epochMillis>.m4a`。
- **M2-8 会话记录**：每次进入场景新建一个 `sessionId`，每轮追加 `turn` 并落盘（§5.11）。从「我的」页可看到按语言分开的累计轮数。
- **M2-9 逐句朗读**：AI 正文必须切句后逐句可点读（用 `EnSentenceSplitter`，见 §5.6.4）。这是**两种语言共用**的能力。

#### M3 AI Provider 抽象层（本任务的技术核心）

- **M3-1 按语言独立配置**：**每个语言各有 3 组**（对话、语音识别、语音合成）独立配置，每组含 `baseUrl` / `apiKey` / `model` / `enabled`（合成为 `voice`）。**共 6 组**，允许各自指向不同厂商。
- **M3-2 未配置即降级**：任意一组未启用时，使用对应的 Mock 实现（对话→Mock 回复；识别→提示「未配置语音识别，请直接输入文字」；合成→静默跳过朗读）。**不得抛异常、不得崩溃。**
- **M3-3 线路协议**：默认实现为 **OpenAI 兼容协议**（§5.3 给出三个 endpoint 的精确报文格式）。用户若接的是别的厂商，只需满足同名协议即可。
- **M3-4 测试连接**：设置页每组卡片上的「测试连接」按钮，向该组 endpoint 发一次最小请求，把结果映射为中文提示（成功 / Key 无效 / 地址或模型不存在 / 限流 / 服务端错误 / 网络不通），**不得统一显示为"网络错误"**（见 §5.5）。
- **M3-5 可替换性**：三组能力都必须通过 §5.2 的接口注入，UI 与其他层**不得直接依赖 OkHttp 或任何具体实现**。
- **M3-6 配置隔离**：`SettingsRepository` 必须能同时持有两种语言的 6 组配置，任一组缺失或损坏时只回退**该组**为默认值，不影响其它组（见 §5.10）。

#### M4 本地持久化

见 §5.11。要点：全部落 JSON 文件、**缺字段用默认值、损坏可自愈、按语言隔离**、不使用数据库。

#### M5 我的 / 设置

- **M5-1 统计**：按语言分列：总卡数、今日待复习数、已掌握数（`reps >= 5`）、累计对话轮数。
- **M5-2 清空数据**：二次确认后删除 `study_progress.json`、`sessions/` 目录、`secure_settings.bin`，并回到初始状态。
- **M5-3 密钥显示**：设置页里 apiKey 默认掩码显示（`sk-****xxxx`），点击「显示」才明文；离开页面回到掩码。切换语言时也必须回到掩码。

### 4.4 内容资产要求（可被单测断言）

| 文件 | 要求 |
|---|---|
| `assets/kana.json` | 必须包含 **46 个基础假名**全部（`あいうえお`…`わをん`），每条含 `hiragana`/`katakana`/`romaji`。另外可附浊音、半浊音、拗音（不做硬性要求）。 |
| `assets/n5_vocab.json` | **≥ 100 条**，字段：`id`（字符串，唯一）/ `kanji`（可为空串）/ `kana`（必须纯平假名或含 `ー`）/ `romaji` / `meaningZh` / `jlpt`（固定 `"N5"`）。**必须包含以下 10 个锚点词**：`私` `あなた` `学生` `先生` `日本` `水` `食べる` `飲む` `行く` `見る` |
| `assets/scenarios_ja.json` | **≥ 5 个**场景，字段：`id` / `titleTarget` / `titleZh` / `systemPrompt` / `openingLine` / `goalExpressions`（**≥ 5 条**）。`systemPrompt` 必须包含 §M2-4 的 `[JA]/[ZH]/[FIX]` 输出格式要求，并约束 AI 用 N5–N4 难度日语、每次回复 1–3 句。建议场景：`自己紹介` / `コンビニで買い物` / `道を聞く` / `レストランで注文` / `面接` |
| `assets/en_phonemes.json` | **≥ 24 条**，字段：`symbol`（IPA 符号，唯一）/ `type`（只能是 `"vowel"` 或 `"consonant"`）/ `example`（含该音素的英文例词）/ `exampleZh`。其中 `vowel` **≥ 12 条**、`consonant` **≥ 12 条** |
| `assets/en_words.json` | **≥ 100 条**，字段：`id`（唯一）/ `word`（必须匹配正则 `^[a-z][a-z'\-]*$`，全小写）/ `ipa` / `pos`（只能是 `n.` `v.` `adj.` `adv.` `prep.` `pron.` `conj.` 之一）/ `meaningZh`（非空）/ `example`（**必须包含 `word` 本身，不区分大小写**）/ `exampleZh` / `level`（只能是 `"A1"` `"A2"` `"B1"` 之一）。**必须包含以下 10 个锚点词**：`accommodation` `necessary` `receive` `environment` `government` `beautiful` `weather` `breakfast` `schedule` `restaurant` |
| `assets/scenarios_en.json` | **≥ 5 个**场景，字段同 `scenarios_ja.json`。`systemPrompt` 必须包含 `[EN]/[ZH]/[FIX]` 输出格式要求，并约束 AI 用 A2–B1 难度英语、每次回复 1–3 句。建议场景：`Self introduction` / `At the coffee shop` / `Asking for directions` / `Job interview` / `Making an appointment` |

> 上述所有数值断言（≥100 / ≥24 / ≥5 / 锚点词）都会被核验方的脚本独立复算，**不要写死断言代码而资产不达标**。

---

## §5 工程规格

### 5.1 包结构与关键文件（核验方按此定位，必须遵守）

```
com.langpartner.app
├── core\
│   ├── lang\LangId.kt               枚举 JA/EN
│   ├── lang\LanguageModule.kt       语言模块接口（§5.6.1）
│   ├── lang\LanguageRegistry.kt     按 LangId 取模块
│   ├── lang\ja\KanaConverter.kt     罗马字 → 假名（§5.6.2）
│   ├── lang\ja\KanaNormalizer.kt    半角/全角、片假名↔平假名、长音符统一（§5.6.3）
│   ├── lang\ja\JaModule.kt
│   ├── lang\en\EnNormalizer.kt      英文归一化（§5.6.3）
│   ├── lang\en\EnTokenizer.kt       英文分词（§5.6.4）
│   ├── lang\en\EnSentenceSplitter.kt 英文分句（§5.6.5）
│   ├── lang\en\EnModule.kt
│   ├── answer\AnswerJudge.kt        作答判定（§5.7）
│   ├── chat\ReplyParser.kt          三分段回复解析（§M2-4）
│   ├── srs\Sm2Scheduler.kt          间隔重复（§5.8）
│   ├── context\ContextTrimmer.kt    上下文裁剪（§5.9）
│   ├── net\BaseUrlNormalizer.kt     baseUrl 规范化（§5.4）
│   ├── net\OpenAiWire.kt            请求体构造 / 回复解析（§5.3）
│   ├── net\HttpErrors.kt            错误分类纯函数（§5.5）
│   ├── net\HttpTransport.kt         传输层接口 + OkHttp 实现
│   └── log\LogRedactor.kt           密钥脱敏（§6.2）
├── data\
│   ├── model\    Card.kt / ChartEntry.kt / Scenario.kt / ChatMessage.kt
│   │             CardProgress.kt / SessionRecord.kt / ProviderConfig.kt
│   │             LanguageConfig.kt / AppSettings.kt / AiError.kt
│   ├── store\    StudyStore.kt / SessionStore.kt / SettingsRepository.kt
│   ├── secret\   SecretStore.kt（接口）/ KeystoreSecretStore.kt（Android 实现）
│   └── asset\    AssetRepository.kt（读 assets 里的六份 JSON）
├── ai\
│   ├── AiProvider.kt            三个接口 + AiResult（§5.2）
│   ├── AiProviderRegistry.kt    按语言 + 配置返回真实/Mock 实现
│   ├── openai\OpenAiChatProvider.kt
│   ├── openai\OpenAiSttProvider.kt
│   ├── openai\OpenAiTtsProvider.kt
│   └── mock\MockChatProvider.kt / MockSttProvider.kt / MockTtsProvider.kt
└── ui\   study\ chat\ chart\ profile\ settings\  （Activity / Fragment / Adapter / ViewModel）
```

`core/` 与 `data/model`、`data/store`、`ai/` 的**纯逻辑部分必须不依赖 Android 框架类**（`Context` 除外，且只允许出现在 `KeystoreSecretStore`、`AssetRepository`、`OkHttpTransport`、UI 层），以便在 JVM 单元测试里直接调用。

### 5.2 AI Provider 抽象层（接口签名固定）

```kotlin
package com.langpartner.app.ai

data class ChatMessage(val role: String, val content: String)   // "system" | "user" | "assistant"

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7
)

sealed class AiError {
    object Network : AiError()                                  // 连接失败 / DNS / 无网络
    data class Timeout(val ms: Long) : AiError()
    data class Unauthorized(val code: Int) : AiError()           // 401 / 403
    data class NotFound(val code: Int) : AiError()               // 404
    data class RateLimited(val code: Int, val retryAfterSeconds: Long?) : AiError()   // 429
    data class ServerError(val code: Int, val body: String) : AiError()               // 5xx
    data class BadResponse(val detail: String) : AiError()       // 2xx 但 JSON 不合预期
    data class Unknown(val code: Int, val detail: String) : AiError()
}

sealed class AiResult<out T> {
    data class Ok<T>(val value: T) : AiResult<T>()
    data class Err(val error: AiError) : AiResult<Nothing>()
}

interface ChatProvider {
    suspend fun chat(request: ChatRequest): AiResult<String>          // 返回助手正文
}

interface SpeechToTextProvider {
    suspend fun transcribe(audioFile: java.io.File, language: String): AiResult<String>
}   // language 取当前语言模块的 sttLanguage（"ja" / "en"）

interface TextToSpeechProvider {
    suspend fun synthesize(text: String, voice: String?): AiResult<ByteArray>   // 音频字节
}

interface AiProviderFactory {          // 每个语言一个实例
    fun chat(): ChatProvider
    fun stt(): SpeechToTextProvider
    fun tts(): TextToSpeechProvider
}
```

传输层同样要抽出来，**这是网络层单测的唯一注入点**：

```kotlin
package com.langpartner.app.core.net

data class MultipartFile(val fieldName: String, val fileName: String, val mimeType: String, val bytes: ByteArray)

data class HttpCall(
    val method: String,                 // "POST"
    val url: String,
    val headers: Map<String, String>,
    val jsonBody: String? = null,
    val multipart: MultipartFile? = null
)

data class HttpRaw(
    val code: Int,                      // 未收到响应时为 0
    val body: String,                   // 二进制响应可留空
    val headers: Map<String, String> = emptyMap(),
    val ioError: String? = null,        // 非空表示连接层异常，如 "timeout" / "connect failed"
    val binary: ByteArray? = null
)

interface HttpTransport {
    suspend fun execute(call: HttpCall): HttpRaw
}
```

### 5.3 HTTP 线协议（三组 endpoint，精确格式）

设规范化后的 base 为 `BASE`（见 §5.4），`KEY` 为配置的 apiKey，`MODEL` 为配置的 model。

#### §5.3.1 对话

```
POST BASE/chat/completions
Header  Authorization: Bearer KEY
Header  Content-Type: application/json
Body    {"model":"MODEL","messages":[{"role":"system","content":"..."},{"role":"user","content":"..."}],"temperature":0.7}
```

响应解析：取 `choices[0].message.content` 作为纯文本。
- 取不到（`choices` 为空 / 元素缺 `message.content` / 整体不是 JSON 对象）→ `AiError.BadResponse`
- **注意**：`message.content` 可能是 `null`（某些模型走 reasoning 字段），此时同样归为 `BadResponse`，detail 里要写明 `missing choices[0].message.content`。

请求体构造规则（`OpenAiWire.buildChatBody`）：
- 输出必须是**合法 JSON**
- **中日文字符必须原样输出，不得转义成 `\uXXXX`**；换行必须转义为 `\n`；双引号必须转义为 `\"`；反斜杠必须转义为 `\\`
- `messages` 顺序与传入一致

#### §5.3.2 语音识别

```
POST BASE/audio/transcriptions
Header  Authorization: Bearer KEY
Content-Type: multipart/form-data; boundary=<随机 boundary>
Body    --<boundary>
        Content-Disposition: form-data; name="file"; filename="audio.m4a"
        Content-Type: audio/mp4

        <m4a 二进制>
        --<boundary>
        Content-Disposition: form-data; name="model"

        MODEL
        --<boundary>
        Content-Disposition: form-data; name="language"

        ja                                  ← 或 en，取当前语言
        --<boundary>--
```

字段名必须是 `file`，文件名必须是 `audio.m4a`，MIME 必须是 `audio/mp4`。响应解析：取 `text` 字段；缺失或非 JSON → `BadResponse`。

#### §5.3.3 语音合成

```
POST BASE/audio/speech
Header  Authorization: Bearer KEY
Header  Content-Type: application/json
Body    {"model":"MODEL","input":"<要朗读的文本>","voice":"alloy","response_format":"mp3"}
```

`voice` 取该组配置里的 `voice` 字段；为空时用 `"alloy"`。响应体是**二进制音频**（`audio/mpeg`），直接进 `binary` 字段。`binary` 为空但 code=200 → `BadResponse`。

### 5.4 baseUrl 规范化（`BaseUrlNormalizer.normalize`）

用户填写的地址千奇百怪，必须先规范化。规则按顺序执行：

1. `trim()`，去掉首尾空白
2. 若不含 `://`，在前面补 `https://`
3. 只允许 `https://`：若为 `http://`，**返回失败**（调用方在设置页提示「出于安全考虑仅支持 https」）
4. 去掉末尾所有的 `/`
5. 若以 `/chat/completions`、`/audio/transcriptions`、`/audio/speech` 之一结尾，**截掉该后缀**
6. 结果即 `BASE`

判定锚点（核验方持完整向量表，以下三条为口径示例）：

| 输入 | 期望 BASE |
|---|---|
| `https://api.example.com/v1/` | `https://api.example.com/v1` |
| `https://api.example.com/v1/chat/completions` | `https://api.example.com/v1` |
| `api.example.com` | `https://api.example.com` |

### 5.5 错误分类（`HttpErrors.classify`，**必须是纯函数**）

```kotlin
fun classify(raw: HttpRaw): AiError?
```
返回 `null` 表示成功（可继续解析业务体），非 `null` 表示需要短路为错误。

判定顺序（严格按此顺序，先命中先返回）：

1. `raw.ioError` 非空：
   - 含 `timeout`（不分大小写）→ `AiError.Timeout(30000)`
   - 其它 → `AiError.Network`
2. `code == 401 || code == 403` → `AiError.Unauthorized(code)`
3. `code == 404` → `AiError.NotFound(404)`
4. `code == 429`：
   - 读响应头 `Retry-After`（大小写不敏感）。能解析为正整数则填入 `retryAfterSeconds`，否则为 `null`
   - → `AiError.RateLimited(429, retryAfterSeconds)`
5. `code in 500..599` → `AiError.ServerError(code, body 前 200 字符)`
6. `code !in 200..299` → `AiError.Unknown(code, body 前 200 字符)`
7. 其余 → `null`

每种错误在 UI 上的中文提示必须可区分（**禁止统一成"网络错误"**）：

| AiError | 提示文案 |
|---|---|
| `Network` | 网络连接失败，请检查网络后重试 |
| `Timeout` | 请求超时，请稍后重试 |
| `Unauthorized` | API Key 无效或无权限，请到设置页检查 |
| `NotFound` | 接口地址或模型名不存在，请到设置页检查 |
| `RateLimited` | 请求过于频繁，请 `retryAfterSeconds` 秒后重试（为空时提示「请稍后重试」） |
| `ServerError` | 服务端错误（`code`），请稍后重试 |
| `BadResponse` | 响应格式异常，无法解析 |
| `Unknown` | 请求失败（`code`） |

---

### 5.6 语言模块（本任务新增的核心抽象）

#### §5.6.1 `LanguageModule` 接口（签名固定）

```kotlin
package com.langpartner.app.core.lang

enum class LangId(val tag: String, val displayName: String, val sttLanguage: String) {
    JA("ja", "日语", "ja"),
    EN("en", "英语", "en");
    companion object { fun fromTag(tag: String?): LangId = if (tag == "en") EN else JA }
}

data class Card(
    val id: String,
    val front: String,        // 卡片正面主体
    val frontSub: String,     // 正面副标题
    val back: String,         // 正确答案（判题基准）
    val backSub: String,      // 答案副标题（读音提示）
    val meaningZh: String,    // 正面下方补充（可为空）
    val speakText: String     // 交给 TTS 的文本
)

data class ChartEntry(val cell: String, val sub: String, val speak: String)
data class Token(val kind: String, val text: String, val start: Int, val end: Int)  // kind: WORD/NUMBER/PUNCT

interface LanguageModule {
    val id: LangId
    /** 输入框占位提示，如「输入假名读法」/「拼写英文单词」 */
    val answerHint: String
    /** AI 回复的主标签，如 "JA" / "EN" */
    val replyTag: String
    /** 归一化后用于判题比较 */
    fun normalizeForCompare(s: String): String
    /** 资产文件名 */
    fun assetNames(): Triple<String, String, String>   // (chart, deck, scenarios)
    /** 从资产 JSON 文本构建各语料（纯函数，输入是 JSON 字符串，便于单测） */
    fun buildChart(json: String): List<ChartEntry>
    fun buildDeck(json: String): List<Card>
    fun buildScenarios(json: String): List<Scenario>
    /** 分句（两种语言都提供；英语为 EnSentenceSplitter，日语按 。！？ 切分） */
    fun splitSentences(text: String): List<String>
}
```

**硬性要求**：`ui/`、`ai/`、`data/` 里**不得出现 `if (lang == JA)` 之外的语言判断**；所有语言相关分支必须收口在 `core/lang/` 内。核验方会扫描 `ui/` 与 `ai/` 目录下是否出现了 `kana` / `romaji` / `tokenize` 等语言专有词。

#### §5.6.2 日语：`KanaConverter.toHiragana(romaji: String): ConvertResult`

```kotlin
data class ConvertResult(val kana: String, val unparsed: List<String>)
```

**预处理**：先 `trim()` → 转小写 → **全角字母转半角**（`ａ`→`a`）。

**核心循环**：指针 `i` 从 0 开始，每一步按下列**优先级**判定，命中即处理并推进 `i`：

1. **促音（双辅音）**：若 `s[i] == s[i+1]`，且 `s[i]` 属于辅音集合 `{b c d f g h j k l m p r s t v w x y z}` → 输出 `っ`，`i += 1`。
   *（`n` 不在集合内，由规则 3 处理。）*
2. **促音特例 `tch`**：若 `s[i] == 't'` 且 `s[i+1] == 'c'` 且 `s[i+2] == 'h'` → 输出 `っ`，`i += 1`。
3. **拨音 `n`**（`s[i] == 'n'`），按下列分支判定：
   - 若 `s[i+1] == 'n'` **且 `i + 2 >= s.length`**（即这两个 `n` 就是结尾）→ 输出 `ん`，`i += 2`。例：`honn` → ほん
   - 若 `s[i+1] == 'n'`（还有后续字符）→ 输出 `ん`，**`i += 1`**（只消耗一个 `n`！）
     > ⚠ **这里最容易写错。** 第二个 `n` 必须留给后面的音节：`onna` = o + n + na → **おんな**；`konnichiwa` = ko + n + ni + chi + wa → **こんにちわ**。如果写成 `i += 2`，会得到 `おんあ` / `こんいちわ`，错得很隐蔽。
   - 若 `s[i+1] == '\''` → 输出 `ん`，`i += 2`
   - 若 `i` 已是最后一个字符 → 输出 `ん`，`i += 1`
   - 若 `s[i+1]` 是母音 `{a i u e o}` 或是 `y` → **不处理，交给规则 4**
   - 否则 → 输出 `ん`，`i += 1`
4. **最长匹配查表**：依次尝试 `s.substring(i, i+3)`、`s.substring(i, i+2)`、`s.substring(i, i+1)`（越界则跳过），命中表中任一项即输出对应假名并推进对应长度。
5. **未识别**：以上都不命中 → 把 `s[i]` 原样追加到输出，并加入 `unparsed`，`i += 1`。

**音节表**（Hepburn 为主，`|` 后为必须接受的 kunrei/输入法别名）：

| 行 | 条目 |
|---|---|
| 母音 | `a`→あ `i`→い `u`→う `e`→え `o`→お |
| か | `ka` `ki` `ku` `ke` `ko`→かきくけこ |
| さ | `sa` `su` `se` `so`；`shi｜si`→し |
| た | `ta` `te` `to`；`chi｜ti`→ち；`tsu｜tu`→つ |
| な | `na` `ni` `nu` `ne` `no`→なにぬねの |
| は | `ha` `hi` `he` `ho`；`fu｜hu`→ふ |
| ま | `ma` `mi` `mu` `me` `mo`→まみむめも |
| や | `ya`→や `yu`→ゆ `yo`→よ |
| ら | `ra` `ri` `ru` `re` `ro`→らりるれろ |
| わ | `wa`→わ `wo`→を |
| ん | （仅由规则 3 产生） |
| が | `ga` `gi` `gu` `ge` `go`→がぎぐげご |
| ざ | `za` `zu` `ze` `zo`；`ji｜zi`→じ |
| だ | `da` `de` `do`；`di`→ぢ；`du`→づ |
| ば | `ba` `bi` `bu` `be` `bo`→ばびぶべぼ |
| ぱ | `pa` `pi` `pu` `pe` `po`→ぱぴぷぺぽ |
| 拗音 き | `kya` `kyu` `kyo`→きゃきゅきょ |
| 拗音 し | `sha｜sya`→しゃ；`shu｜syu`→しゅ；`sho｜syo`→しょ |
| 拗音 ち | `cha｜tya`→ちゃ；`chu｜tyu`→ちゅ；`cho｜tyo`→ちょ |
| 拗音 に | `nya` `nyu` `nyo`→にゃにゅにょ |
| 拗音 ひ | `hya` `hyu` `hyo`→ひゃひゅひょ |
| 拗音 み | `mya` `myu` `myo`→みゃみゅみょ |
| 拗音 り | `rya` `ryu` `ryo`→りゃりゅりょ |
| 拗音 ぎ | `gya` `gyu` `gyo`→ぎゃぎゅぎょ |
| 拗音 じ | `ja｜zya`→じゃ；`ju｜zyu`→じゅ；`jo｜zyo`→じょ |
| 拗音 び | `bya` `byu` `byo`→びゃびゅびょ |
| 拗音 ぴ | `pya` `pyu` `pyo`→ぴゃぴゅぴょ |

**长音**：字符 `-`（半角连字符）→ 输出 `ー`。其余长音一律按上表正常解析（`ou`→おう、`uu`→うう）。

**未识别字符**：非 `[a-z]`、非 `-`、非 `'` 的字符（含汉字、数字、空格）→ 原样输出并计入 `unparsed`。

**明确不做的两件事**（不要"顺手加上"）：
- **不做助词特殊化**：不处理 は／へ／を 的读音例外。即 `konnichiwa` → `こんにちわ` 就是**正确输出**。
- **不做外来语扩展音节**：不实现 ファ／ティ／ウィ／シェ 之类；这些组合按规则 5 落到 `unparsed`。

规则锚点（口径示例，完整向量表在核验方）：

| 输入 | 期望 `kana` | 考察点 |
|---|---|---|
| `gakkou` | がっこう | 促音 |
| `kekka` | けっか | 促音 |
| `matcha` | まっちゃ | 促音特例 `tch` |
| `shashin` | しゃしん | 最长匹配（不能拆成 さ+は） |
| `shinjuku` | しんじゅく | 拨音 + 拗音 |
| `nippon` | にっぽん | 促音 + 词尾拨音 |
| `kanpai` | かんぱい | 拨音在 p 前 |
| `onna` | おんな | **`nn` 只吃一个 n** |
| `konnichiwa` | こんにちわ | **`nn` 只吃一个 n**（易错） |
| `honn` | ほん | 词尾 `nn` 合成一个 ん |
| `shin'you` | しんよう | `n'` 显式分隔 |
| `shinyou` | しにょう | `n` + `y` 不构成拨音 |
| `hon'ya` | ほんや | `n'` vs `nya` 的分界 |
| `honya` | ほにゃ | 同上，反向 |
| `hokkaidou` | ほっかいどう | 促音 + 长音 |
| `su-pa-` | すーぱー | `-` → ー |
| `gakusei` | がくせい | 普通音节 |
| `Gakkou` | がっこう | 大写输入 |
| `ｇａｋｋｏｕ` | がっこう | 全角字母输入 |

#### §5.6.3 语言归一化

**日语 `KanaNormalizer`**

```kotlin
object KanaNormalizer {
    fun toHalfWidthAlnum(s: String): String        // 全角字母数字 → 半角
    fun halfWidthToFullWidth(s: String): String    // 半角片假名 → 全角（含浊音符合并）
    fun katakanaToHiragana(s: String): String      // 片假名 → 平假名
    fun unifyLongVowelMark(s: String): String      // 各种横线统一为 ー
    fun normalize(s: String): String               // 依次执行上面四步
}
```

**硬性规定：`halfWidthToFullWidth` 必须用 `java.text.Normalizer.normalize(s, Normalizer.Form.NFKC)` 实现，禁止手写半角片假名映射表。**

- NFKC 会自动完成：半角片假名 → 全角、半角浊音点 `ﾞ`/半角半浊音点 `ﾟ` 与前一假名**合并**（`ｶ` + `ﾞ` → `ガ`）、全角字母数字 → 半角。手写表极易漏掉浊音符合并与 `ｦ`/`ｰ`/`ｯ` 这几个特例。
- `katakanaToHiragana`：逐字符，码点落在 `U+30A1..U+30F6` 的减去 `0x60`（得到 `U+3041..U+3096`）；其余（含 `U+30F7..U+30FA`、`U+30FB` 中点、`U+30FC` 长音）**保持不变**。
- `unifyLongVowelMark`：把 `U+002D`、`U+2010`、`U+2011`、`U+2212`、`U+FF0D` 统一替换为 `U+30FC`（ー）。`U+30FC` 自身不变。
- `normalize` 顺序固定为：`toHalfWidthAlnum` → `halfWidthToFullWidth` → `katakanaToHiragana` → `unifyLongVowelMark`。
  说明：`toHalfWidthAlnum` 可用 NFKC 的副产物实现（NFKC 本身就会把全角字母数字转半角），但**必须保留独立函数名**以便单测定位。

**英语 `EnNormalizer.normalize(s: String): String`**

严格按下列**顺序**执行：

1. `java.text.Normalizer.normalize(s, Normalizer.Form.NFKC)`
   - 会自动完成：全角字母数字 → 半角（`Ａ`→`A`、`１`→`1`）、全角标点 → 半角、全角空格 `U+3000` → `U+0020`
2. **弯引号/变体引号 → ASCII 直引号**（NFKC 不会做这一步，必须显式映射）：
   - → `'`（U+0027）：`U+2018` `U+2019` `U+201A` `U+201B` `U+2032` `U+02BC` `U+00B4` `` U+0060 ``
   - → `"`（U+0022）：`U+201C` `U+201D` `U+201E` `U+2033`
3. **各类连字符/破折号 → `-`（U+002D）**：`U+2010` `U+2011` `U+2012` `U+2013` `U+2014` `U+2015` `U+2212` `U+2043` `U+FE58` `U+FE63`
4. **剥离拉丁字母的变音符号**：逐字符处理 —— 对该字符做 NFD 分解，**若分解后的首字符属于 `[A-Za-z0-9]`，则只输出该首字符、丢弃其余组合记号**；否则**原样输出该字符**。
   - `é`→`e`、`ü`→`u`、`ñ`→`n`、`ç`→`c`、`ō`→`o`
   - **这条"否则原样输出"是必须的**：否则 `が`（NFD = `か` + 浊点）会被拆坏，混排的日文文本会被破坏。
5. **转小写**：必须用 `lowercase(java.util.Locale.ROOT)`。**禁止用默认 Locale** —— 在土耳其语 Locale 下 `I` 会变成 `ı`，导致同一批数据在不同机器上结果不同。
6. **空白折叠**：把连续空白（含制表、换行、`U+3000`）折叠为单个 `U+0020`，并去掉首尾空白。

> **明确不做**（不要"顺手加上"）：
> - **不做字母级变体替换**。`ß`（U+00DF）**没有** Unicode 分解映射，NFKC 后仍是 `ß`；`æ` `œ` `ø` `ł` `đ` `þ` 同理。所以 `Straße` 归一化后是 `straße`（**不是** `strasse`）。**不要为了"看起来更对"而加手写替换表。**
> - 不做词形还原、不做拼写纠正。

归一化锚点（核验方持完整向量表，以下为口径示例）：

| 输入 | 期望输出 | 考察点 |
|---|---|---|
| `"  CAFÉ  "` | `cafe` | 变音剥离 + 小写 + 去首尾空白 |
| `"naïve"` | `naive` | 变音剥离 |
| `"Zürich"` | `zurich` | 变音剥离 |
| `"resumé"` | `resume` | 变音剥离 |
| `"Straße"` | `straße` | **`ß` 不展开**（易错） |
| `"ＡＢＣ１２３"` | `abc123` | NFKC 全角→半角 |
| `"Ｈｅｌｌｏ　Ｗｏｒｌｄ"` | `hello world` | 全角字母 + 全角空格 |
| `"don’t"` | `don't` | 弯撇号 → 直撇号 |
| `"“Quote”"` | `"quote"` | 弯双引号 + 小写 |
| `"—dash—"` | `-dash-` | em dash → `-` |
| `"a   b\t\tc"` | `a b c` | 空白折叠 |
| `"日本語"` | `日本語` | **混排日文不得被破坏** |
| `"がっこう"` | `がっこう` | **同上（浊音符号不得被剥掉）** |
| `"3.14"` | `3.14` | 数字与小点保留 |
| `"U.S."` | `u.s.` | 缩写点保留 |
| `"  HELLO  "` | `hello` | 去首尾空白 + 小写 |

#### §5.6.4 英语：`EnTokenizer.tokenize(text: String): List<Token>`

```kotlin
data class Token(val kind: String, val text: String, val start: Int, val end: Int)
// kind ∈ {"WORD", "NUMBER", "PUNCT"}
// start/end 是以 UTF-16 code unit 计的半开区间 [start, end)，即 Kotlin String 的下标语义
```

**字符分类**（必须用 JDK 的 `Character` 方法，不要自己写 ASCII 判断）：
- **字母** = `Character.isLetter(c)`（含 `é`、`日本語` 等非 ASCII 字母）
- **数字** = `Character.isDigit(c)`
- **空白** = `c.isspace()` 等价实现（含 `U+0020`、`U+0009`、`U+000A`、`U+3000`）

**扫描算法**：

```
i = 0；跳过所有空白（空白不产生 token）
取 s[i] 决定 kind：
    字母 → "WORD"；数字 → "NUMBER"；其余 → "PUNCT"
```

**PUNCT 分支**：
- 若 `s[i]` 属于**引号类集合** `{ ' ’ ‘ ʼ " “ ” « » 「 」 『 』 ‹ › }` → 该 token **只含这一个字符**（引号永不与其他标点合并）
- 否则 → 向后吸收所有满足「非空白 **且** 非字母 **且** 非数字 **且** 不属于引号类集合」的字符
  → 所以 `...`、`!!!`、`?!` 各为一个 token，但 `,'` 是**两个** token（`,` 与 `'`）

**WORD / NUMBER 分支**（从 `i` 开始吸收「词内字符」）：
- 字母、数字 → 吸收
- 撇号（`'` `’` `‘` `ʼ`）→ **仅当其后一个字符是字母**时吸收（所以 `don't`、`it's`、`o'clock` 都是一个 token；而词首/词尾的 `'` 归 PUNCT）
- 连字符（`-` 及 §5.6.3 步骤 3 映射到 `-` 的那些）→ **仅当其后一个字符是字母或数字 _且_ 前一个字符是字母或数字**时吸收（所以 `well-known`、`e-mail`、`COVID-19` 是一个 token；而 `a--b` 会被切成 `a` / `--` / `b`）
- **仅当 kind == "NUMBER" 时**：`.` 或 `,` 若**两侧都是数字**→ 吸收（所以 `3.14`、`1,000.50` 是一个 token）
- 其余 → 停止
- 注意：`3rd` 的 kind 是 **NUMBER**（由起始字符决定），但会吸收后面的字母。

**明确不做**：
- **不特殊处理缩写点**。`U.S.` 会被切成 `U` / `.` / `S` / `.`。这是**有意的**：句末的 `.` 与缩写点在词法层无法区分，做例外表会让行为不可预测。需要在句末判断缩写的场景由 §5.6.5 处理。
- 不做词干还原、不做大小写折叠（token 保留原文大小写）。
- **偏移量必须按 UTF-16 code unit 计**，不要按码点数计（emoji 会算错）。

锚点（核验方持完整向量表，以下为口径示例；表中用 `[...]` 表示 token 列表）：

| 输入 | 期望 tokens（仅列 kind 与 text） |
|---|---|
| `I don't like it.` | `WORD I` `WORD don't` `WORD like` `WORD it` `PUNCT .` |
| `'Hello,' she said.` | `PUNCT '` `WORD Hello` `PUNCT ,` `PUNCT '` `WORD she` `WORD said` `PUNCT .` |
| `"Quoted"` | `PUNCT "` `WORD Quoted` `PUNCT "` |
| `well-known e-mail` | `WORD well-known` `WORD e-mail` |
| `COVID-19 vaccine` | `WORD COVID-19` `WORD vaccine` |
| `a--b` | `WORD a` `PUNCT --` `WORD b` |
| `U.S. stocks` | `WORD U` `PUNCT .` `WORD S` `PUNCT .` `WORD stocks` |
| `It costs 1,000.50 dollars` | `WORD It` `WORD costs` `NUMBER 1,000.50` `WORD dollars` |
| `3rd place` | `NUMBER 3rd` `WORD place` |
| `!!! Wait` | `PUNCT !!!` `WORD Wait` |
| `Wait... really?!` | `WORD Wait` `PUNCT ...` `WORD really` `PUNCT ?!` |
| `(hello)` | `PUNCT (` `WORD hello` `PUNCT )` |
| `it's a test` | `WORD it's` `WORD a` `WORD test` |
| `日本語 and English` | `WORD 日本語` `WORD and` `WORD English` |
| `café latte` | `WORD café` `WORD latte` |
| `a😀b`（偏移量） | `WORD a [0,1)` `PUNCT 😀 [1,3)` `WORD b [3,4)` |

#### §5.6.5 英语：`EnSentenceSplitter.split(text: String): List<String>`

**终止符集合** `TERM` = `{ . ! ? 。 ！ ？ … }`；**收尾符集合** `CLOSER` = `{ " ' ’ ” ) ] } 」 』 }`。

扫描 `text`，位置 `i`：

1. `s[i]` 不在 `TERM` → `i++` 继续。
2. 否则先**把连续的终止符合并成一段**：令 `j` 为终止符段结束后的位置（`Wait!!!` → 一段）。
3. 判定该段是否为真正的句末（`terminal`）：
   - 若终止符是 `!` `?` `！` `？` `…` → **是**
   - 若终止符是 `。` → **是**
   - 若终止符是 `.`：
     - `j >= s.length`（点就是文本最后一个字符）→ **是**
     - 否则看 `s[j]`：**若 `s[j]` 既不是空白、也不属于 `CLOSER`** → **否**
       > 这一条同时覆盖了 `3.14`（点后是数字）、`Ph.D`（点后是大写字母）、`U.S`（点后是字母）三种情况。
     - 否则**回看**：从 `i` 往左取**连续的非空白字符**作为 `core`（不含本次的终止符），再去掉 `core` 末尾所有的 `.`：
       - 若 `core`（小写后）命中**缩写表** → **否**
       - 否则若 `core` 含 `.` 且把 `.` 全去掉后**全是大写字母且长度在 1..4** → **否**（`U.S` / `U.K` / `U.N`）
       - 否则 → **是**
4. 若 `terminal`：从 `j` 开始**向右吸收所有 `CLOSER` 字符**得到 `e`；`s[start..e)` 去首尾空白后**非空才**加入结果；令 `start = e`，`i = e`。
5. 循环结束后，把 `s[start..)` 去首尾空白，非空则加入结果。

**缩写表**（必须精确按此表，**匹配时把 `core` 转小写**）：

```
mr  mrs  ms  dr  prof  st  jr  sr  vs  etc  e.g  i.e  a.m  p.m
fig  vol  approx  dept  est  inc  ltd  corp  blvd  ave  rd  apt  univ
gen  gov  sen  rep  capt  sgt  maj  lt  col
```

**明确接受的取舍（写进 REPORT 的「已知缺陷」即可，不要试图"修得更好"）**：
- 缩写（`etc.` / `U.S.`）出现在句末时，该点**不终止**，会与下一句合并为一条。例：`"He lives in the U.S. Really?"` → 切出 **2** 段，第二段是 `He lives in the U.S. Really?`。
- 只有 `“`/`”`/`'` 这类成对引号内的句末标点会被正确归入引号所在句（见锚点第 3 条）。

锚点（核验方持完整向量表，以下为口径示例）：

| 输入 | 期望输出（切出的句子列表） |
|---|---|
| `I met Mr. Smith. He likes tea.` | `["I met Mr. Smith.", "He likes tea."]` |
| `Pi is 3.14. OK?` | `["Pi is 3.14.", "OK?"]` |
| `She said "go." Then left.` | `["She said \"go.\"", "Then left."]` |
| `Dr. Smith has a Ph.D.` | `["Dr. Smith has a Ph.D."]` |
| `Wait!!! Really?` | `["Wait!!!", "Really?"]` |
| `U.S. stocks fell.` | `["U.S. stocks fell."]` |
| `A.B.C. then X. Y` | `["A.B.C. then X.", "Y"]` |
| `Mr. and Mrs. Lee are here.` | `["Mr. and Mrs. Lee are here."]` |
| `No terminator here` | `["No terminator here"]` |
| `今日はいい天気。明日は雨。` | `["今日はいい天気。", "明日は雨。"]` |
| `Hello, world.   ` | `["Hello, world."]` |
| `""`（空串） | `[]` |
| `"   "`（纯空白） | `[]` |

> 日语模块的 `splitSentences` 同样必须实现（按 `。！？` 切分即可），以便两种语言共用逐句朗读 UI。

#### §5.6.6 语言模块对资产映射的硬性规定

| | 日语 `JaModule` | 英语 `EnModule` |
|---|---|---|
| `answerHint` | `输入假名读法` | `拼写英文单词` |
| `replyTag` | `JA` | `EN` |
| `assetNames()` | `("kana.json", "n5_vocab.json", "scenarios_ja.json")` | `("en_phonemes.json", "en_words.json", "scenarios_en.json")` |
| `buildDeck` 的 `front` | `kanji` 为空则用 `kana` | **`meaningZh`** |
| `frontSub` | `"JLPT " + jlpt` | `level + " · " + pos` |
| `back` | `kana` | `word` |
| `backSub` | `romaji` | `ipa` |
| `meaningZh`（正面下方） | `meaningZh` | **空串**（英语的释义已经在正面了） |
| `speakText` | `kana` | `word` |
| 例句挖空 | 不做 | `example` 里**不区分大小写**地把 `word` 首次出现替换为 `____` |

---

### 5.7 作答判定（`AnswerJudge.judge`）

```kotlin
data class JudgeResult(val correct: Boolean, val normalizedInput: String, val reason: String?)
object AnswerJudge {
    fun judge(module: LanguageModule, expected: String, userInput: String): JudgeResult
}
```

规则：

1. 用 `module.normalizeForCompare()` 分别处理 `expected` 与 `userInput`。
   - **日语**：`KanaNormalizer.normalize()` → 删除所有空白（`U+0020`、`U+3000`、`U+0009`、`U+000A`、`U+000D`）→ 删除标点（`。` `、` `，` `．` `.` `！` `？` `!` `?` `,`）
   - **英语**：`EnNormalizer.normalize()` → 删除所有空白 → **删除所有非字母数字字符**（含 `-` 与 `'`）
2. `correct = (归一化输入 == 归一化答案)`
3. `normalizedInput` 必须返回归一化后的字符串（便于 UI 回显与调试）
4. 不相等时 `reason = "输入：<归一化输入>，期望：<归一化答案>"`
5. **大小写、全角半角、片假名平假名、长音符形式、空白、标点都不应影响判定结果**

英语判题锚点：

| expected | userInput | 结果 | 说明 |
|---|---|---|---|
| `well-known` | `  Well Known  ` | 判对 | 连字符与空格被抹平 |
| `Receive` | `receive` | 判对 | 大小写无关 |
| `restaurant` | `restaurant.` | 判对 | 标点被抹平 |
| `It's` | `its` | 判对 | **已知取舍**：撇号被抹平 |
| `beautiful` | `beutiful` | 判错 | 拼写错误不能放过 |
| `necessary` | `necessery` | 判错 | 同上 |

### 5.8 间隔重复（`Sm2Scheduler`，精确公式）

```kotlin
data class CardState(val easeFactor: Double = 2.5, val reps: Int = 0, val intervalDays: Long = 0)
data class ReviewOutcome(val state: CardState, val dueEpochDay: Long)

object Sm2Scheduler {
    fun review(state: CardState, quality: Int, todayEpochDay: Long): ReviewOutcome
}
```

`quality ∈ 0..5`。算法**必须严格按下列两步顺序**执行：

```
第 1 步 —— 计算新的易度因子 ef'
    ef' = ef + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
    若 ef' < 1.3 则 ef' = 1.3
    ef' 四舍五入保留 2 位小数

第 2 步 —— 计算新间隔 I 与重复次数 n'（注意：用【旧 ef】参与乘法）
    若 q < 3:
        n' = 0；I = 1
    否则:
        n' = reps + 1
        I  = n' == 1 -> 1
             n' == 2 -> 6
             否则     -> round(intervalDays * ef)      // 用旧 ef
    round 为四舍五入取整（half-up），I 最小为 1

输出：
    newState = CardState(easeFactor = ef', reps = n', intervalDays = I)
    dueEpochDay = todayEpochDay + I
```

> **顺序是关键。** SM-2 原文是「**先用当前 EF 算新间隔，再用本次评分更新 EF**」。若你先更新 EF 再算间隔，从第 3 次复习起结果会系统性偏大。`q < 3` 时 EF 仍按第 1 步公式更新（不是不变）。

锚点（口径示例）：初始 `ef = 2.5, reps = 0, intervalDays = 0`，连续取 `q = 4`，则前四次复习得到的 `intervalDays` 依次为 **1、6、15、38**。

### 5.9 上下文裁剪（`ContextTrimmer`）

```kotlin
object ContextTrimmer {
    const val MAX_HISTORY = 24        // 不含 system
    fun trim(system: String, history: List<ChatMessage>): List<ChatMessage>
}
```

规则：

1. 输出**第一条恒为** `ChatMessage("system", system)`。无论历史多长，system 都不得被丢弃。
2. `history` 只保留 `role ∈ {"user","assistant"}` 的项（其它 role 直接过滤掉）。
3. 若 `history.size <= 24` → 原样接在 system 之后。
4. 否则：**从最前面开始，每次成对移除 2 条**，直到剩余 `<= 24`。（不做「首条是 assistant 就先单独移除」的特殊处理——那会让奇数长度历史的裁剪结果不确定。）
5. 输入 `history` 不得被修改（不可变操作）。
6. 若历史被清空，只返回 system 一条。

锚点：`size = 24` → 24 条不变；`size = 25` → 剩 23 条；`size = 26` → 剩 24 条。

### 5.10 加密存储（`SecretStore` / `KeystoreSecretStore`）

```kotlin
interface SecretStore {
    fun read(): String?          // 返回明文 JSON，未初始化时返回 null
    fun write(plain: String)
    fun clear()
}
```

- Android 实现：`AndroidKeyStore` 提供 AES-256-GCM 密钥（`KeyGenParameterSpec`，`setBlockModes(GCM)`、`setEncryptionPaddings(NoPadding)`、`setUserAuthenticationRequired(false)`）
- IV 每次随机 12 字节；文件格式为 `[1 byte 版本号=1][12 byte IV][密文]`，路径 `filesDir/secure_settings.bin`
- **明文 JSON 结构**（注意 `langs` 是两层）：

  ```json
  {
    "version": 1,
    "activeLang": "ja",
    "autoPlayTts": true,
    "langs": {
      "ja": {
        "chat": {"baseUrl":"","apiKey":"","model":"","enabled":false},
        "asr":  {"baseUrl":"","apiKey":"","model":"","enabled":false},
        "tts":  {"baseUrl":"","apiKey":"","model":"","voice":"","enabled":false}
      },
      "en": {
        "chat": {"baseUrl":"","apiKey":"","model":"","enabled":false},
        "asr":  {"baseUrl":"","apiKey":"","model":"","enabled":false},
        "tts":  {"baseUrl":"","apiKey":"","model":"","voice":"","enabled":false}
      }
    }
  }
  ```

- 读取时若文件不存在、密钥丢失或解密失败 → **返回默认值（两个语言各三组均 disabled、`activeLang="ja"`），不得崩溃**；连续失败 3 次后删除该文件重建密钥
- `SettingsRepository(store: SecretStore)` 承担 JSON 的解析/生成与默认值填充，**必须可被 JVM 单测直接测试**（测试注入内存版 `SecretStore`）
- **局部自愈**：`langs` 缺失 → 补全两个语言；`langs.ja` 缺失 → 只补 `ja`；某个 endpoint 组缺 `voice`/`enabled` → 只补该字段。**任一处的损坏都不允许影响其它语言/其它组的已存配置。**
- `activeLang` 取值非法（既不是 `ja` 也不是 `en`）→ 回退为 `ja`

### 5.11 数据模型与持久化（含向后兼容 + 双语隔离）

**`filesDir/study_progress.json`（version 2 起按语言分层）**

```json
{
  "version": 2,
  "langs": {
    "ja": { "cards": { "v_001": { "ef": 2.5, "reps": 3, "intervalDays": 15, "dueEpochDay": 20450, "lastEpochDay": 20435 } } },
    "en": { "cards": { "e_001": { "ef": 2.3, "reps": 1,  "intervalDays": 1,  "dueEpochDay": 20451, "lastEpochDay": 20450 } } }
  }
}
```

**`filesDir/sessions/<sessionId>.json`**

```json
{
  "version": 2,
  "sessionId": "s_1758300000000",
  "langId": "en",
  "scenarioId": "coffee",
  "startedAt": 1758300000000,
  "turns": [
    { "role": "assistant", "text": "[EN] Good morning! What can I get you?\n[ZH] 早上好！您要点什么？\n[FIX] 无" },
    { "role": "user", "text": "I want a latte please" }
  ]
}
```

**向后兼容与自愈（硬性要求）**：

1. 文件不存在 → 返回空结构（`cards` 为空 Map / `turns` 为空 List），**不报错**
2. JSON 解析失败 → 把原文件重命名为 `<原名>.corrupt-<epochMillis>`，返回空结构，**不得抛异常**
3. **`version` 缺失或为 1**（旧格式：顶层直接是 `cards`，无 `langs`）→ 把顶层 `cards` 整体视为 **`langs.ja.cards`**，`langs.en.cards` 为空 Map。**不得丢弃旧数据。**
4. 单条记录缺字段 → 用默认值：`ef=2.5`、`reps=0`、`intervalDays=0`、`dueEpochDay=0`（视为立即到期）、`lastEpochDay=0`
5. `langs` 里缺少某个语言键 → 该语言视为空 Map（不影响另一语言）
6. 未知的多余字段 → 忽略
7. 会话文件的 `langId` 缺失 → 视为 `"ja"`

**写入**：每次评分/每轮对话结束时立即写盘（不是退出时才写）。写盘必须原子化：先写 `<文件名>.tmp`，再重命名覆盖。

---

## §6 安全与合规硬要求

### 6.1 API Key 处理（**核验时逐项扫描，任一项不通过即整体不通过**）

1. apiKey **不得**出现在源码、`BuildConfig`、`res/values/*.xml`、`assets/*.json`、任何测试文件里
2. apiKey **不得**以明文形式出现在任何落盘文件（含 `SharedPreferences` 的 XML、`filesDir` 下任何文件、`cacheDir`）
3. apiKey **不得**出现在 `Logcat` 输出中（含异常堆栈、OkHttp 拦截器日志）
4. 唯一合法存放位置：`filesDir/secure_settings.bin`，且必须是 §5.10 的加密格式
5. 本工程及产物中**不得**出现任何真实密钥。设置页初始状态必须为空

### 6.2 日志脱敏（`LogRedactor`）

```kotlin
object LogRedactor {
    fun mask(secret: String): String
}
```

- 规则：`secret.length < 8` → 返回 `"****"`；否则返回 `secret.take(3) + "****" + secret.takeLast(4)`
- 锚点：`sk-ZD5Lr...tbkS`（65 字符）→ `sk-****tbkS`
- 所有涉及请求头、请求体的日志，必须经过 `LogRedactor.mask` 处理；**禁止**直接 `Log.d(TAG, jsonBody)` 打印含 `Authorization` 头的内容
- Release 版本不得输出任何请求体日志（可用 `BuildConfig.DEBUG` 开关）

---

## §7 权限与清单

`app/src/main/AndroidManifest.xml` 只允许声明这两个权限 —— **不多不少**：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- 不得声明 `ACCESS_NETWORK_STATE`、`WRITE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE`、`CAMERA`、`READ_CONTACTS`、`ACCESS_FINE_LOCATION` 或任何其它权限。网络可用性判断靠捕获 `IOException` 完成。
- 运行时权限：进入对话页时才请求 `RECORD_AUDIO`。拒绝后：录音按钮置灰 + 一句提示「未获得麦克风权限，可先用文字输入」，**文本输入必须照常可用**。
- 不设置 `android:usesCleartextTraffic="true"`（targetSdk 35 默认禁止明文流量，与 §5.4 只允许 https 一致）。

---

## §8 依赖清单（版本钉死）

**已在本机 Gradle 缓存中确认存在**，可离线解析：

| 用途 | 坐标 |
|---|---|
| AndroidX 基础 | `androidx.core:core-ktx:1.15.0` |
| | `androidx.appcompat:appcompat:1.7.0` |
| | `com.google.android.material:material:1.12.0` |
| | `androidx.constraintlayout:constraintlayout:2.2.0` |
| | `androidx.activity:activity-ktx:1.8.0` |
| 列表 | `androidx.recyclerview:recyclerview:1.3.2` |
| 生命周期 | `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7` |
| 网络 | `com.squareup.okhttp3:okhttp:4.12.0` |
| 协程 | `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0` |
| **单测** | `junit:junit:4.13.2` |
| **单测（必须加）** | `org.json:json:20240303` |
| 构建插件 | AGP `8.7.3` / Kotlin `2.1.0`（根 `build.gradle.kts` 声明） |

**单测必须加 `org.json:json:20240303` 的原因**：`android.jar` 里的 `org.json` 全是抛 `Stub!` 的桩，不加这条真实实现，所有涉及 JSON 的单测都会挂。这是本机已踩过的坑。

**除此之外不要新增依赖。** 尤其：不加 `security-crypto`、`mockwebserver`、`navigation`、`fragment-ktx`、`kotlinx-serialization`（原因见 §2.3）。

`app/build.gradle.kts` 中必须包含：

```kotlin
android {
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    testOptions {
        unitTests.isReturnDefaultValues = true   // 见 §12 第 4 条，必须打开
    }
}
```

---

## §9 验收标准

| 编号 | 验收项 | 判定方式 |
|---|---|---|
| **V1** | 构建通过 | `:app:assembleDebug` 退出码 0，且 `app\build\outputs\apk\debug\app-debug.apk` 存在、修改时间晚于任务开始时间 |
| **V2** | 单测全绿 | `:app:testDebugUnitTest` 退出码 0；`app\build\test-results\testDebugUnitTest\*.xml` 汇总 `failures=0 errors=0 skipped=0`，且 **`tests >= 120`** |
| **V3** | 离线可跑 | 不配置任何凭证时：两个语言的 M1 卡片训练全流程可用；两个语言的 M2 都能通过 `MockProvider` 完成一轮对话，回复含主标签 / `[ZH]` / `[FIX]` 三段 |
| **V4** | 日语资产完整 | 单测断言：`kana.json` 含 46 个基础假名；`n5_vocab.json` ≥100 条且含 §4.4 的 10 个锚点词；`scenarios_ja.json` ≥5 个且每个 `goalExpressions` ≥5 条 |
| **V5** | 假名转换正确 | 单测覆盖促音、拨音（`nn`/`n'`/词尾/母音前）、拗音、长音、`shi/si` 等别名、未识别字符、大写、全角输入 |
| **V6** | 日语归一化正确 | 单测覆盖半角片假名（含 `ｶ`+`ﾞ` 合并）、片假名→平假名（含 `ヴ`→`ゔ`、`ヵ`/`ヶ`）、长音符统一、全角字母数字 |
| **V7** | 英语归一化正确 | 单测断言 §5.6.3 的 16 条锚点，**特别是 `Straße` → `straße`（不展开）与 `がっこう` 不被破坏** |
| **V8** | 英语分词正确 | 单测断言 §5.6.4 的 16 条锚点，含 `don't`/`well-known`/`COVID-19`/`1,000.50`/`3rd`/`,'` 拆两个/`U.S.` 拆四个/emoji 的 UTF-16 偏移 |
| **V9** | 英语分句正确 | 单测断言 §5.6.5 的 13 条锚点，含 `3.14`、`Mr.`、`Ph.D.`、`U.S.`、引号收尾、空串 |
| **V10** | 作答判定正确 | 单测断言：日语侧全角/半角/片假名/空白/标点差异**判对**、读音错误**判错**；英语侧 §5.7 表的 6 条 |
| **V11** | SM-2 正确 | 单测断言 `q=4` 序列 `1,6,15,38`；`q<3` 重置为 `reps=0, I=1`；`ef` 下限 1.3；**EF 更新顺序正确** |
| **V12** | 上下文裁剪正确 | 单测断言 24 不变 / 25→23 / 26→24；system 恒定在首位；输入 list 未被修改 |
| **V13** | 错误分类正确 | 单测注入假 `HttpTransport`，断言 `ioError`（timeout/其它）、`401`、`403`、`404`、`429`（含/不含 `Retry-After`）、`500`、`2xx 坏 JSON` 分别映射到**不同的 `AiError` 子类型** |
| **V14** | 请求构造正确 | 单测断言 `buildChatBody` 输出合法 JSON、**中日文未被 `\uXXXX` 转义**、换行被转义为 `\n`、消息顺序一致；`Authorization` 头为 `Bearer <key>`；STT 的 multipart 含 `language=ja` 与 `language=en` 两种 |
| **V15** | baseUrl 规范化正确 | 单测断言 §5.4 三条锚点；`http://` 输入返回失败 |
| **V16** | 密钥与日志安全 | 核验方全仓 `grep`：源码/资源/测试中无 32 位以上形如密钥的字符串；`filesDir` 与 `cacheDir` 下无明文 key；单测断言 `LogRedactor.mask` 结果 |
| **V17** | 权限最小化 | 解析 `AndroidManifest.xml`：`uses-permission` 恰好 2 条且为 `INTERNET`、`RECORD_AUDIO` |
| **V18** | 英语资产完整 | 单测断言：`en_words.json` ≥100 条、id 唯一、`word` 全部匹配 `^[a-z][a-z'\-]*$`、`pos`/`level` 取值合法、**每条 `example` 都包含该 `word`（忽略大小写）**、含 §4.4 的 10 个锚点词；`en_phonemes.json` ≥24 条且 vowel ≥12 / consonant ≥12；`scenarios_en.json` ≥5 个 |
| **V19** | 双语隔离 | 单测断言：`study_progress.json` 里 `ja` 与 `en` 的同名 `cardId` 是两条独立进度；`SettingsRepository` 存取两种语言的 6 组配置互不覆盖；`Sm2Scheduler` 对两种语言行为一致（同一纯函数） |
| **V20** | 数据兼容与自愈 | 单测断言：文件不存在返回空结构；坏 JSON 被重命名为 `.corrupt-*` 且返回空结构；缺字段用默认值；**version=1 的旧格式被迁到 `langs.ja` 且数据不丢**；`langs` 缺 `en` 不影响 `ja` |
| **V21** | 语言分支收口 | 核验方扫描 `app/src/main/java/com/langpartner/app/ui` 与 `.../ai`：不得出现 `KanaConverter` / `EnTokenizer` / `romaji` / `toHiragana` 等语言专有符号 |
| **V22** | 真机冒烟（人工） | 安装 APK 启动不崩；断网状态下两种语言各完成一轮卡片训练；不配凭证时两种语言各完成一轮 Mock 对话；切换语言后进度不串 |

---

## §10 自查（提交前必须执行并附上输出）

在项目根目录执行：

```powershell
cd "D:\AI project\lang-partner"
$env:JAVA_HOME = "D:\a\jbr"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

同时自查：

```powershell
# 1) 确认 APK 存在且是刚构建的
Get-Item "app\build\outputs\apk\debug\app-debug.apk" | Select-Object FullName, Length, LastWriteTime

# 2) 汇总单测结果
Get-ChildItem "app\build\test-results\testDebugUnitTest" -Filter *.xml |
  ForEach-Object { [xml]$x = Get-Content $_.FullName -Raw; "{0} tests={1} fail={2} err={3} skip={4}" -f `
    $x.testsuite.name, $x.testsuite.tests, $x.testsuite.failures, $x.testsuite.errors, $x.testsuite.skipped }

# 3) 权限检查（应恰好输出 2 行）
Select-String -Path "app\src\main\AndroidManifest.xml" -Pattern "uses-permission"

# 4) 确认没有明文密钥（应无输出）
Get-ChildItem -Recurse -File -Include *.kt,*.kts,*.xml,*.json,*.properties |
  Where-Object { $_.FullName -notmatch "\\build\\" } |
  Select-String -Pattern "sk-[A-Za-z0-9]{20,}"
```

---

## §11 禁止事项

1. **不得读取或修改 `D:\AI project` 下的其它工程**（复制 Gradle wrapper 文件除外，且只能读）
2. 不得使用 Compose、`security-crypto`、`mockwebserver`、`navigation`、`kotlinx-serialization` 插件（§2.3）
3. 不得把任何真实 API Key 写进代码、配置或资源
4. 不得声明 §7 之外的权限
5. 不得使用 `@Ignore` / `@Suppress` 掩盖失败测试；不得为了让测试通过而删减断言
6. 不得让构建依赖本机环境变量 `JAVA_HOME` 的当前值（必须在命令里显式指定 `D:\a\jbr`）
7. 不得引入 §8 之外的依赖
8. 不得用 Toast/Log 替代 §5.5 规定的错误分类提示
9. **不得把语言判断散落到 `ui/` / `ai/`**（§5.6.1 硬性要求、V21 会扫）
10. **不得为了"看起来更对"而给归一化加手写替换表**（§5.6.3 的「明确不做」）

---

## §12 已知失败模式（照着避坑）

| # | 症状 | 原因 | 规避 |
|---|---|---|---|
| 1 | `Android Gradle plugin requires Java 17 to run` | 本机 `JAVA_HOME` 是 JDK 11 | 每次 Gradle 前 `$env:JAVA_HOME = "D:\a\jbr"` |
| 2 | 构建卡在下载 Compose 编译器 | 本机缓存无 `compose-compiler-gradle-plugin` | 用 Views + XML（§2.3） |
| 3 | 单测里 JSON 相关全挂，报 `Stub!` | `android.jar` 的 `org.json` 是桩 | `testImplementation("org.json:json:20240303")` |
| 4 | 单测报 `Method ... not mocked` | 生产代码里有 `android.util.Log` | `unitTests.isReturnDefaultValues = true` |
| 5 | 半角假名归一化出错（`ｶﾞ` 变成 `カ` + 残留浊点） | 手写映射表 | 用 `Normalizer.Form.NFKC`（§5.6.3 强制） |
| 6 | SM-2 间隔从第 3 次起偏大 | 先更新 EF 再算间隔 | 严格按 §5.8 的两步顺序 |
| 7 | `R` 类找不到 | 打开了 `android.nonTransitiveRClass` | 不要打开（§2.4） |
| 8 | 源码里的日文/中文变乱码 | 编码未固定 | `gradle.properties` 加 `-Dfile.encoding=UTF-8`（§2.4） |
| 9 | `SDK location not found` | 缺 `local.properties` | 写 `sdk.dir=D\:/AndroidSdk` |
| 10 | 错误提示全是"网络错误" | 未做错误分类 | 实现 §5.5 纯函数并接进 UI |
| 11 | 日志里出现完整 Key | 直接打印请求头/体 | 统一走 `LogRedactor.mask`（§6.2） |
| 12 | 对话在无凭证时崩溃 | 未做 Mock 降级 | 实现 `MockProvider`（§M2-6 / §M3-2） |
| 13 | 撤销权限后录音崩溃 | 未处理权限拒绝路径 | §7 的降级要求 |
| 14 | 对话历史越来越长导致请求体膨胀 | 未做裁剪 | 走 `ContextTrimmer`（§5.9） |
| 15 | 报 `Failed to find Build Tools revision 35.0.0` | 本机 build-tools 只有 34.0.0 / 36.0.0 / 36.1.0 / 37.0.0 | 在 `android {}` 里显式加 `buildToolsVersion = "36.0.0"` |
| 16 | `nn` 转出来的假名少一个音节（`konnichiwa` → こんいちわ） | 拨音规则里 `nn` 错误地消耗了两个 `n` | 严格按 §5.6.2 规则 3：`nn` **只消耗一个** `n` |
| 17 | 以为 NFKC 会把 `ß` 变成 `ss`，于是测试写成 `strasse` | **`ß` 没有 Unicode 分解映射，NFKC 不动它** | 归一化后是 `straße`；不要把"常识"当规范（§5.6.3） |
| 18 | `,'`（逗号+收尾引号）被并成一个 PUNCT token | 标点合并时没排除引号类 | 引号类字符**单独**成 token（§5.6.4） |
| 19 | `3.14` 被拆成两句 / `Mr. Smith` 被拆成两句 / `Ph.D.` 被拆成两句 | 分句只看字符不看上下文 | 严格按 §5.6.5 的三条判定（点后非空白→不终止；缩写表；大写缩写） |
| 20 | 英语判题把 `beautiful` 和 `beutiful` 判成对的 | 归一化删得太多（例如做了模糊匹配） | §5.7 只删空白与非字母数字，**不做模糊/编辑距离匹配** |
| 21 | 换机器后英语归一化结果不同（`I` 变 `ı`） | 用了默认 Locale 转小写 | 必须 `lowercase(Locale.ROOT)`（§5.6.3 步骤 5） |
| 22 | 切换语言后日语进度里混进了英语卡 | 进度没按语言分层 | `study_progress.json` 用 `langs.<lang>.cards`（§5.11） |
| 23 | 升级到双语版后老用户进度全丢 | 没做 version=1 → version=2 迁移 | 顶层 `cards` 迁移为 `langs.ja.cards`（§5.11 第 3 条） |
| 24 | `en_words.json` 的 `example` 里根本没有那个词 | 例句与词条不匹配 | 单测断言 `example` 含 `word`（V18） |

---

## §13 交付物

1. **完整可构建工程**，位于 `D:\AI project\lang-partner`，目录结构符合 §3.1
2. **debug APK**：`app\build\outputs\apk\debug\app-debug.apk`
3. **单元测试**：`app\src\test\java\...`，`tests >= 120`，全绿
4. **自述报告**：`D:\AI project\lang-partner\REPORT-<模型名>.md`，必须包含：

   ```markdown
   # REPORT — <模型名>
   ## 1. 交付概览（构建命令 / 退出码 / APK 路径与大小 / 单测总数与通过数）
   ## 2. 功能完成度对照表（对照 §4.3 的 M0–M5，逐条标注 完成 / 部分完成 / 未完成，语言分别标注）
   ## 3. 关键实现说明（语言模块抽象、假名转换、英语归一化/分词/分句、SM-2、错误分类、加密存储 各 3–5 行）
   ## 4. 自查输出（§10 四条命令的原始输出，粘贴即可）
   ## 5. 未完成项与已知缺陷（没有就写「无」，不要留空；§5.6.5 的缩写取舍必须写上）
   ## 6. 自主决策（本文件未覆盖、由你自行决定的点，逐条列出并说明理由）
   ## 7. 环境适配记录（实际使用的 JAVA_HOME / 何时设的 / 遇到的构建报错与解决方式）
   ```

5. **不得**在交付物里包含临时脚本、日志文件、构建产物副本

---

## §14 完成定义（DoD）

以下全部满足才算完成：

- [ ] V1–V21 全部通过（V22 由交付方人工执行）
- [ ] §10 的四条自查命令输出已粘贴进 REPORT
- [ ] 无 `@Ignore`、无 `TODO()`、无占位实现（Mock 是设计要求，不算占位）
- [ ] 工程内无任何真实密钥
- [ ] `D:\AI project` 下的其它工程未被改动（核验方会校验其文件哈希）
- [ ] REPORT 第 5 节如实填写，不允许把「未完成」写成「已完成」

---

## 附录 A：日语场景 `systemPrompt` 模板（`scenarios_ja.json` 内每个场景都必须包含等价约束）

```
あなたは日本語学習者の会話練習相手です。あなたは必ず次の形式で回答してください：

[JA] <日本語で1〜3文。N5〜N4レベルの語彙と文法だけを使う>
[ZH] <上の日本語の中国語訳>
[FIX] <学習者の直前の発話にある文法・語彙の誤りを中国語で指摘。誤りがなければ「无」とだけ書く>

重要：
- [JA] は必ず最初の行に置くこと
- 敬体（です・ます）で話すこと
- 学習者が中国語で話しかけた場合は、日本語に訳して復唱してから会話を続けること
```

## 附录 B：英语场景 `systemPrompt` 模板（`scenarios_en.json` 内每个场景都必须包含等价约束）

```
You are a conversation partner for a Chinese learner of English. You MUST reply in exactly this format:

[EN] <1-3 sentences in English. Use only A2-B1 level vocabulary and grammar.>
[ZH] <Chinese translation of the English above>
[FIX] <Point out grammar or word-choice errors in the learner's last utterance, in Chinese. If there are no errors, write only: 无>

Important:
- [EN] MUST be the first line
- Keep the conversation natural and encourage the learner to speak more
- If the learner speaks Chinese, translate it into English, repeat it back, then continue the conversation
```

## 附录 C：`scenarios_*.json` 结构示例

```json
[
  {
    "id": "coffee",
    "titleTarget": "At the coffee shop",
    "titleZh": "咖啡店点单",
    "systemPrompt": "<附录 B 的模板内容>",
    "openingLine": "Hi! Welcome in. What can I get for you today?",
    "goalExpressions": ["I'd like a ...", "Can I have ...?", "For here or to go", "How much is it?", "That's all, thanks"]
  }
]
```

## 附录 D：关于测试向量

§5.6.2 表格里的 19 条假名锚点、§5.6.3 的 16 条英语归一化锚点、§5.6.4 的 16 条分词锚点、§5.6.5 的 13 条分句锚点、§5.4 的 3 条锚点、§5.7 的 6 条英语判题锚点、§5.8 的 SM-2 序列、§5.9 的裁剪结果，**均已由核验方用参考实现逐条验证过**，可作为口径基准直接用于你的单测。

核验方另持有一套更大的向量表（假名约 40 组、英语归一化约 30 组、分词约 35 组、分句约 30 组、baseUrl 约 12 组）用于独立复算，**不在本任务书内**。规则本身在 §5.4–§5.9 已完整给出，**按规则实现即可通过，不需要去猜具体向量**。

> 提示：不要在实现里针对锚点做**硬编码特判**。核验方使用向量表之外的输入做交叉验证；只覆盖示例而规则错误的实现会被识别出来。

---

**（任务书结束）**

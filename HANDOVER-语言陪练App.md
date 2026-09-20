# 交接文档 — LangPartner（语言陪练）Android App

> 文档版本：v1.0（2026-09-20）
> 交接对象：接手本工程的开发/维护人员
> 本文档的所有数字与结论均在 **2026-09-20 17:51** 现场复跑验证，命令与输出见 §2、§11。

---

## 0. 30 秒速览

| 项 | 值 |
|---|---|
| 应用名 / 包名 | 语言陪练 / `com.langpartner.app` |
| 版本 | `versionCode = 2`、`versionName = 1.0.0` |
| 形态 | **纯原生 Android（Kotlin + Views/XML + ViewBinding），单 module（`:app`）** |
| 语言 | 日语 + 英语双语，可切换，进度/配置全隔离 |
| 工程根目录 | `D:\AI project\lang-partner` |
| 主源码规模 | 62 个 `.kt`（5,355 行）+ 11 个布局 + 6 份 assets JSON |
| 测试 | **23 个测试类 / 321 个单测，全绿**（fail=0 err=0 skip=0） |
| 交付 APK | `LangPartner-v1.0.0-release.apk`（5,606,632 B，已签名）；debug 包 7,044,404 B |
| 构建命令 | 见 §2.2（**必须显式设置 `JAVA_HOME=D:\a\jbr`**） |
| 联网能力 | 4 种 AI 能力（对话 / 识别 / 合成 / 实时语音）；**未配置凭证时全部降级为本地 Mock，App 照常可跑完流程** |

**接手前必读的三件事：**

1. **`JAVA_HOME` 是本机最大的坑。** 本机默认 JDK 11 与 AGP 8.7.3 不兼容，每次构建前必须显式指向 `D:\a\jbr`（Android Studio 自带 JBR 21）。
2. **`keystore/` 与 `keystore.properties` 是发布身份的唯一凭据。** 丢失后**无法再向同一应用 ID 发布任何更新**（只能换包名重新上架）。必须立即异地备份，详见 §14.3。
3. **真机冒烟（V22）尚未执行。** 所有功能仅由 321 个 JVM 单测 + 离线构建覆盖，**没有任何一次真机运行记录**。§13.1 给了逐条可执行的验证清单，这是接手后的第一优先级。

---

## 1. 交付物清单

### 1.1 代码与配置（需纳入版本管理）

| 路径 | 说明 |
|---|---|
| `app/src/main/java/com/langpartner/app/` | 主源码（62 个 .kt），分层见 §3 |
| `app/src/test/java/com/langpartner/app/` | 单元测试（23 个类 / 321 例） |
| `app/src/main/assets/*.json` | 6 份内容资产（假名/词汇/音素/场景），见 §10 |
| `app/src/main/res/` | 11 布局 + 15 drawable + 3 values + 1 menu |
| `app/src/main/AndroidManifest.xml` | 7 个 Activity，恰好 2 条权限（INTERNET / RECORD_AUDIO） |
| `app/build.gradle.kts` | 模块构建脚本（含 release 签名配置） |
| `build.gradle.kts` / `settings.gradle.kts` | 根构建脚本（module 名 `LangPartner`，include `:app`） |
| `gradle.properties` | `-Dfile.encoding=UTF-8`、`android.useAndroidX=true` |
| `local.properties` | `sdk.dir=D\:/AndroidSdk`（**本机路径，换机需改**） |
| `gradle/wrapper/` + `gradlew` + `gradlew.bat` | Gradle 8.9 wrapper（从 `bottom-radar-android` 只读复制） |
| `app/proguard-rules.pro` | 存在但**当前未启用**（release 也 `isMinifyEnabled=false`） |

### 1.2 文档与预览

| 路径 | 说明 |
|---|---|
| `TASK-语言陪练App-从零实现.md` | 原始任务书（74 KB，含 §0–§14 与附录 A–D），**本次交接的验收依据** |
| `REPORT-step-5-preview.md` | 实现方的交付报告：需求对照表、自查原始输出、已知缺陷、三轮补充节 |
| `ui-preview.html` | 界面预览 v4（拾语风格，9 屏含实时语音准备/进行中态），浏览器直接打开 |
| `HANDOVER-语言陪练App.md` | **本文档** |

### 1.3 产物与凭据（**不入版本库，须单独备份**）

| 路径 | 大小 / 校验 | 说明 |
|---|---|---|
| `app/build/outputs/apk/release/app-release.apk` | 5,606,632 B | 构建产物（可重新生成） |
| `app/build/outputs/apk/debug/app-debug.apk` | 7,044,404 B | 构建产物（可重新生成） |
| `LangPartner-v1.0.0-release.apk` | 5,606,632 B | release 包副本（发版用，可重新复制） |
| `keystore/lang-partner-release.jks` | 2,754 B | **签名私钥（不可再生）** |
| `keystore.properties` | 239 B | 签名口令（含明文密码，见 §14.3） |

**签名信息（`apksigner verify --print-certs` 实测）：**

```
Signer #1 certificate DN: CN=LangPartner, OU=Dev, O=stepx, L=Beijing, ST=Beijing, C=CN
Signer #1 certificate SHA-256 digest: 8ab55206ff9214b762195202de01506a83f4ddfe2231d3b0026151bd36fb2e56
Signer #1 certificate SHA-1 digest:   3ffc0292a8c797376dafed93c5280a308585f133
Signer #1 certificate MD5 digest:     3e5519decd285029e8fdf84b23968668
```

> 上述指纹是**应用身份**。同一 SHA-256 才能覆盖升级同一应用 ID；换 key 就必须换包名。

---

## 2. 环境与构建

### 2.1 工具链（不可协商，与任务书 §2.1 一致）

| 项 | 值 | 备注 |
|---|---|---|
| Gradle | 8.9 | wrapper 已锁定，不要升级 |
| Android Gradle Plugin | 8.7.3 | |
| Kotlin | 2.1.0 | |
| compileSdk / targetSdk | 35 | 本机 platforms 只有 `android-35 / 36.1 / 37.0`，**没有 34** |
| minSdk | 26 | 依赖 `java.util.Base64`（实时语音 Base64）与 `AudioTrack.Builder` |
| buildToolsVersion | **36.0.0（必须显式声明）** | 本机**没有** AGP 默认的 35.0.0，不声明会报 `Failed to find Build Tools revision 35.0.0` |
| Java | 17（source/target/jvmTarget 均 17） | |
| JDK 路径 | **`D:\a\jbr`** | 本机默认 JDK 11 会直接报 `Android Gradle plugin requires Java 17 to run` |
| Android SDK | `D:\AndroidSdk` | 写在 `local.properties` |

**依赖清单（`app/build.gradle.kts`，全部来自本地缓存，无新增）：**

```
androidx.core:core-ktx:1.15.0          androidx.appcompat:appcompat:1.7.0
com.google.android.material:material:1.12.0   androidx.constraintlayout:constraintlayout:2.2.0
androidx.activity:activity-ktx:1.8.0   androidx.recyclerview:recyclerview:1.3.2
androidx.lifecycle:lifecycle-runtime-ktx:2.8.7  com.squareup.okhttp3:okhttp:4.12.0
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0
testImplementation: junit:4.13.2 + org.json:json:20240303   ← 后者**必须保留**（android.jar 的 org.json 是桩，删掉 JSON 单测全挂）
```

**未使用的组件（本机缓存里没有，不要引入）：** Jetpack Compose（无 compose-compiler 插件）、`androidx.security:security-crypto`（加密用 Android Keystore 自实现）、`lifecycle-viewmodel`（UI 状态用 Activity + 视图字段承载）。

### 2.2 构建 / 测试命令（照抄可用）

**PowerShell（推荐，任务书 §10 口径）：**

```powershell
cd "D:\AI project\lang-partner"
$env:JAVA_HOME = "D:\a\jbr"
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest --console=plain
```

**Git Bash（本机可用，注意需要补 PATH）：**

```bash
export PATH="/c/Program Files/Git/usr/bin:$PATH"     # 不补 PATH 时 ls/find/tail 都 command not found
cd "/d/AI project/lang-partner"
JAVA_HOME="D:/a/jbr" ./gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest --console=plain
```

**本次交接实测结果（2026-09-20 17:51）：**

```
BUILD SUCCESSFUL in 19s
84 actionable tasks: 3 executed, 81 up-to-date
app/build/outputs/apk/debug/app-debug.apk     7,044,404 B
app/build/outputs/apk/release/app-release.apk 5,606,632 B
```

增量构建约 20 s；首次/清理后构建含依赖解析约 55 s。

### 2.3 安装到设备

```bash
adb install -r "D:/AI project/lang-partner/LangPartner-v1.0.0-release.apk"
adb shell am start -n com.langpartner.app/.ui.study.StudyActivity
adb logcat -s LangPartner:*          # 目前 App 几乎没有自定义日志，主要看崩溃栈
```

本机测试机为**魅族 22**（`adb devices` 确认）。注意 release 包与 debug 包**签名不同，不能互相覆盖安装**，切换前先卸载。

### 2.4 本机环境坑（会真实卡住构建）

| 症状 | 原因 | 处理 |
|---|---|---|
| `Android Gradle plugin requires Java 17 to run` | 默认 `JAVA_HOME` 指向 JDK 11 | 显式 `JAVA_HOME=D:\a\jbr` |
| `Failed to find Build Tools revision 35.0.0` | 本机无 35.0.0 | `buildToolsVersion = "36.0.0"` |
| `dexBuilderDebug` 报 `AccessDeniedException: ...desugar_graph.bin` | 疑似企业 DLP（云枢）占用文件 | `.\gradlew.bat --stop` → 删除 `app/build/intermediates/**/desugar_graph*` → 重跑 |
| Git Bash 里 `ls/find/tail/dirname: command not found` | WorkBuddy 的 safe-bin shim 未把 Git 的 `usr/bin` 加入 PATH | `export PATH="/c/Program Files/Git/usr/bin:$PATH"` |
| 文件删不掉、报 `[safe-delete][SAFE_DELETE_FAIL_CLOSED]` | 本机 `Remove-Item` 被劫持为"送回收站"，中文路径转乱码导致失败 | 加 `-ErrorAction SilentlyContinue` 并重试；或直接用 `rm` |
| 剪贴板读写失败 | 企业 DLP 管控 | 不要尝试绕过；改用文件落盘传递内容 |

---

## 3. 工程结构与分层

### 3.1 目录树

```
app/src/main/java/com/langpartner/app/
├── AppGraph.kt                     ← 极简服务定位器：初始化 5 个单例，UI 只从这里取
├── core/                           ← 纯逻辑层：**禁止 import android.***（有测试强制守卫）
│   ├── lang/                       ← 语言模块（全部语言差异收口于此）
│   │   ├── LangId.kt               (JA / EN + displayName / sttLanguage.tag)
│   │   ├── LanguageModule.kt       ← 接口：normalizeForCompare / assetNames / build* / splitSentences
│   │   ├── LanguageRegistry.kt     ← 唯一按语言分发入口
│   │   ├── MockReplies.kt          ← Mock 预置文案（按 LangId 分发，让 ai/ 层零语言分支）
│   │   ├── ja/  JaModule · KanaConverter · KanaNormalizer · ScenarioParser
│   │   └── en/  EnModule · EnNormalizer · EnTokenizer · EnSentenceSplitter
│   ├── answer/AnswerJudge.kt        ← 判题（先各自归一化再比较）
│   ├── srs/Sm2Scheduler.kt          ← SM-2 间隔重复
│   ├── context/ContextTrimmer.kt    ← 上下文裁剪（上限 24 条）
│   ├── net/                         ← HttpTransport 接口 / HttpCall / HttpErrors / BaseUrlNormalizer / OpenAiWire
│   ├── chat/ReplyParser.kt          ← AI 回复三分段解析（[JA]/[EN] + [ZH] + [FIX]）
│   ├── log/LogRedactor.kt           ← 密钥脱敏
│   ├── audio/VadPolicy.kt           ← 能量 VAD（纯算法，无 android 依赖）
│   └── realtime/RealtimeProtocol.kt ← stepfun realtime 线协议（纯 JSON 编解码）
├── data/                           ← 模型与持久化
│   ├── model/  Card · ChartEntry · Scenario · ChatMessage · CardProgress
│   │           SessionRecord · ProviderConfig · LanguageConfig · RealtimeOptions
│   │           AppSettings · AiError
│   ├── store/  StudyStore · SessionStore · SettingsRepository
│   ├── secret/ SecretStore(接口) · KeystoreSecretStore(AES-256-GCM 实现)
│   └── asset/  AssetRepository.kt   ← 只负责把 assets 里的 JSON 原文读成 String
├── ai/                             ← Provider 抽象与实现
│   ├── AiProvider.kt               ← AiError / AiResult / Chat·Stt·Tts 三接口 / AiProviderFactory
│   ├── RealtimeVoiceProvider.kt    ← 第四能力：一条 WebSocket 承载听+说+字幕
│   ├── AiProviderRegistry.kt       ← 按语言+配置返回真实实现或 Mock（降级规则见 §6.2）
│   ├── mock/  MockProviders · MockRealtimeProvider
│   └── openai/ OkHttpTransport · OpenAiChatProvider · OpenAiSttProvider
│               OpenAiTtsProvider · StepfunRealtimeProvider
├── audio/                          ← **平台层**（依赖 android.media，因此不能放 core）
│   ├── AudioVadTrimmer.kt          ← m4a → PCM16 → 裁头尾静音 → WAV
│   └── RealtimeAudioController.kt  ← AudioRecord 采集 + 静音门控 + AudioTrack 播放 + barge-in
└── ui/                             ← Activity 层（无 ViewModel，状态在 Activity 字段）
    ├── Speaker.kt · AiErrorMessages.kt
    ├── study/StudyActivity.kt          ← Tab「今日」
    ├── chart/ChartActivity.kt          ← Tab「图表」
    ├── chat/ChatListActivity.kt        ← Tab「对话」（场景列表 + 实时语音入口）
    ├── chat/ChatActivity.kt            ← 单场景对话（录音/文字 → STT → LLM → TTS）
    ├── chat/MessageAdapter.kt
    ├── voice/VoiceChatActivity.kt      ← 实时语音（全屏深色沉浸页）
    ├── profile/ProfileActivity.kt      ← Tab「我的」
    └── settings/SettingsActivity.kt    ← 8 组 Provider 配置
```

### 3.2 分层铁律（改代码前务必遵守）

1. **`core/` 不得出现任何 `import android.*`。** 这条由 `SourcePolicyTest` 扫描源文件强制，违反即测试失败。要做平台相关的事，放到 `audio/` 或 `ui/`。
   > 历史教训：`AudioVadTrimmer` 第一版写在 `core/audio/`，被这条测试当场抓出并迁到 `app/audio/`。
2. **`ui/` 与 `ai/` 不得出现语言专有符号**（`KanaConverter` / `EnTokenizer` / `romaji` / `toHiragana` 等）。语言差异一律经 `LanguageModule`。同样由 `SourcePolicyTest` 守卫。
3. **`ai/` 层不依赖 OkHttp 具体实现**，只依赖 `core/net/HttpTransport` 接口 —— 单测因此可以用假 Transport 断言请求报文。
4. **UI 不直接 `new` 仓库**，统一走 `AppGraph`；但 `AppGraph.init(this)` 需要在每个 Activity 的 `onCreate` 里调一次（幂等）。

### 3.3 关键类职责速查

| 类 | 职责 | 关键约束 |
|---|---|---|
| `AppGraph` | 服务定位器 | 初始化后不可换实现；`init` 幂等 |
| `LanguageRegistry` | 按 `LangId` 返回 `LanguageModule` | 新增语言只改这里 + 新模块 |
| `SettingsRepository` | 8 组 Provider 配置 + 全局设置；JSON 读写自愈 | 纯 JVM 可测（注入内存 `SecretStore`） |
| `AiProviderRegistry` | 真实/Mock 选择 | 判定式：`enabled && baseUrl.isNotBlank()`；**不得抛异常** |
| `StudyStore` | 学习进度（按语言分层） | 原子写（`.tmp` → rename）；坏 JSON 隔离为 `.corrupt-*` |
| `SessionStore` | 会话记录（每 session 一文件） | 每轮 append 后立即落盘 |
| `KeystoreSecretStore` | 配置加密存储 | AES-256-GCM；连续 3 次解密失败删文件+删密钥重建 |
| `HttpErrors.classify` | HTTP 响应 → `AiError` 分类 | **纯函数**；顺序固定（见 §5.10） |
| `BaseUrlNormalizer` | baseUrl 规范化 | **`http://` 与 `ws://` 一律返回 null（拒绝）** |
| `VadPolicy` | 裁头尾静音区间决策 | 纯算法；全静音/裁剪比 ≥0.98 → 返回 null |
| `RealtimeProtocol` | 客户端/服务端事件编解码 | 纯 JSON；13 类服务端事件（见 §7.2） |

---

## 4. 功能清单与验收对照

### 4.1 需求完成度（任务书 §4.3）

| 编号 | 功能 | 状态 |
|---|---|---|
| M0-1 ~ M0-4 | 语言持久化、切换后各页面全切换、进度按语言隔离、切换不丢数据不崩溃 | ✅ |
| M1-1 ~ M1-7 | 今日排卡（due 升序 + 未学过补足 20）、卡片正反面、同输入框作答、4 按钮↔quality 2/3/4/5、先归一化再判题、SM-2 后立刻落盘、图表页点格系统 TTS 朗读 | ✅ |
| M2-1 ~ M2-9 | 场景 ≥5/语言、开场白+目标表达、录音→转写→对话→三段解析→逐句切分→TTS→落盘、标签解析容错、文字输入降级、无凭证 Mock 跑完一轮、录音参数固定、session/轮次落盘、逐句可点读 | ✅ |
| M3-1 ~ M3-6 | 每语言 3 组独立配置（实时改造后为 4 组）、未配置降级 Mock、OpenAI 兼容三 endpoint 精确报文、测试连接中文提示、接口注入解耦、配置局部自愈 | ✅ |
| M4 | JSON 落盘、缺字段默认、损坏自愈、语言隔离、无数据库 | ✅ |
| M5-1 ~ M5-3 | 按语言统计四项指标、二次确认清空三处数据、apiKey 默认掩码 | ✅ |
| **追加（任务书未要求）** | **A 档回环/计费防护**（录音期切断外放 + 上传前 VAD 裁静音） | ✅ |
| **追加（任务书未要求）** | **实时语音对话**（stepfun realtime，WebSocket 全双工） | ✅ |
| **追加（任务书未要求）** | **UI 按"拾语"设计稿重做**（暖纸/陶土/衬线，4 Tab + 中央麦克风 FAB） | ✅ |

### 4.2 验收标准对照（任务书 §9）

| 编号 | 验收项 | 状态 / 证据 |
|---|---|---|
| V1 | 构建通过 | ✅ `BUILD SUCCESSFUL`，退出码 0 |
| V2 | 单测全绿且 ≥120 | ✅ **321** 例，fail=0 err=0 skip=0（§11.2 明细） |
| V3 | 离线可跑 | ✅ 单测覆盖「无凭证完成一轮 Mock 对话且含三段」；**真机待验** |
| V4 | 日语资产完整 | ✅ kana 46 / n5_vocab 138（含 10 锚点）/ scenarios_ja 5×goalExpressions≥5 |
| V5 ~ V7 | 假名转换 / 日语归一化 / 英语归一化 | ✅ 单测 40 / 7 / 26 例 |
| V8 ~ V9 | 英语分词 / 分句 | ✅ 27 / 25 例 |
| V10 ~ V12 | 判题 / SM-2 / 上下文裁剪 | ✅ 17 / 11 / 10 例 |
| V13 ~ V15 | 错误分类 / 请求构造 / baseUrl 规范化 | ✅ 16 / 14 / 16 例（baseUrl 含 wss 6 例） |
| V16 | 密钥与日志安全 | ✅ `LogRedactorTest` 5 例 + 全仓扫描无密钥样式字符串 |
| V17 | 权限最小化 | ✅ `AndroidManifest.xml` 恰好 2 条：INTERNET、RECORD_AUDIO |
| V18 | 英语资产完整 | ✅ en_words 110（含 10 锚点）/ en_phonemes 40（vowel 16 + consonant 24）/ scenarios_en 5 |
| V19 | 双语隔离 | ✅ 同名 cardId 两条独立进度；8 组配置互不覆盖 |
| V20 | 数据兼容与自愈 | ✅ 缺文件/坏 JSON/缺字段/version 1→2 迁移/langs 缺 en 全部有断言 |
| V21 | 语言分支收口 | ✅ `SourcePolicyTest` 静态扫描 ui/ 与 ai/ |
| **V22** | **真机冒烟（人工）** | ❌ **未执行 —— 见 §13.1** |

---

## 5. 核心逻辑详解（改逻辑前必读）

### 5.1 语言模块抽象

`LanguageModule` 是全部语言差异的唯一出口，接口方法（任务书 §5.6.1 固定签名）：

```kotlin
interface LanguageModule {
    val id: LangId
    val answerHint: String            // 输入框占位：「输入假名读法」/「拼写英文单词」
    val replyTag: String              // AI 回复主标签："JA" / "EN"
    fun normalizeForCompare(s: String): String
    fun assetNames(): Triple<String, String, String>   // (chart, deck, scenarios)
    fun buildChart(json: String): List<ChartEntry>
    fun buildDeck(json: String): List<Card>
    fun buildScenarios(json: String): List<Scenario>
    fun splitSentences(text: String): List<String>
}
```

**新增一门语言的最小改动集：** 新 `XxModule` + 3 份 assets + `LanguageRegistry` 加一个分支 + `LangId` 加枚举 + `MockReplies` 加文案。**不需要**动 `ui/`、`ai/`、`data/`。

### 5.2 假名转换 `KanaConverter.toHiragana`（规则顺序不可调换）

1. **促音**：双辅音压缩（`kk/tt/pp/ss…` → `っ`），`n` **不**入促音集合；
2. **`tch` 特例**；
3. **拨音**：`nn` 只消耗一个 `n`、`n'` 为显式分隔、母音/y 前交查表；
4. **最长匹配**：3 → 2 → 1 字符，Hepburn + kunrei 别名全表；
5. **未识别字符**原样进 `unparsed`；`-` → `ー`（长音符）。

单测 40 例（19 条锚点 + 21 条扩展）。**改动此处务必先跑 `KanaConverterTest`。**

### 5.3 归一化（判题前的统一处理）

- **日语 `KanaNormalizer`**：半角片假名（含 `ｶ`+`ﾞ` 合并）→ 片假名 → 平假名（`ヴ`→`ゔ`、`ヵ`/`ヶ`）、长音符统一、全角字母数字。
- **英语 `EnNormalizer`**：NFKC → 弯引号/破折号显式映射 → 逐字符 NFD 剥离拉丁变音（**首字符非 `[A-Za-z0-9]` 则原样输出**，用于保护 `が` 等日文）→ `lowercase(Locale.ROOT)` → 空白折叠。

### 5.4 分词 / 分句

- `EnTokenizer`：`Character.isLetter/isDigit` 分类；引号类单独成 token；撇号/连字符/小数点按"后字符+前字符"条件吸收；**偏移按 UTF-16**（`[start, end)` 半开区间）。
- `EnSentenceSplitter`：终止符合并后逐段判定 —— 非点直接终止；点后非空白且非 CLOSER 不终止；否则回看 core 查缩写表与"1–4 位全大写"规则。

### 5.5 判题 `AnswerJudge`

先调 `module.normalizeForCompare` 再比较。日语侧全角/半角/片假名/空白/标点差异**判对**，读音错误**判错**；英语侧按 §5.7 表 6 条。

### 5.6 SM-2 `Sm2Scheduler`（**两步顺序是关键**）

```
第 1 步：newEf = oldEf + (0.1 - (5-q) * (0.08 + (5-q) * 0.02))
         round2(newEf)，下限 1.3
第 2 步：q < 3  → reps=0, interval=1（但 ef 仍按第 1 步更新）
         q >= 3  → reps' = reps+1
                   reps'=1 → 1 天；reps'=2 → 6 天
                   否则 → roundHalfUp(intervalDays * **旧 ef**)   ← 用旧 ef，不是新 ef
```

锚点：`q=4` 序列 `1, 6, 15, 38`。**单测里有专门一条证明乘法用的是旧 ef**（`q=3, interval=6, ef=2.5` → 15），别改错。

### 5.7 上下文裁剪 `ContextTrimmer`

`MAX_HISTORY = 24`。system 恒定在首位；history 只保留 user/assistant；超出时从最前面**每次成对移除 2 条**；不改动入参。锚点：24 不变 / 25→23 / 26→24。

### 5.8 回复三分段解析 `ReplyParser`

按**行首**标签解析，方括号内标签大小写不敏感、前后允许空格：

```
[JA] こんにちは。          ← 主标签由 module.replyTag 决定（英语为 [EN]）
[ZH] 你好。
[FIX] 「こんにちわ」应为「こんにちは」
```

任一标签都没匹配到 → 整段作为正文、不显示纠错块。**任何格式都不抛异常**（未知标签行按普通文本续接）。

### 5.9 baseUrl 规范化 `BaseUrlNormalizer`

顺序：trim → 不含 `://` 补 `https://` → **`http://`/`ws://` 返回 null** → 只允许 `https` / `wss` → 去尾 `/` → 去三个 endpoint 后缀（`/chat/completions`、`/audio/transcriptions`、`/audio/speech`）。

**`wss://` 特例：** 不做后缀裁剪、原样保留（实时端点形如 `wss://api.stepfun.com/v1/realtime`，末尾不是 endpoint 而是资源路径）。

### 5.10 错误分类 `HttpErrors.classify`（顺序固定，纯函数）

`ioError(timeout/其它)` → `401/403` → `404` → `429`（解析 `Retry-After` 正整数）→ `5xx`（body 截断 200 字符）→ 其它非 2xx → 2xx 但 JSON 坏 → `BadResponse`。

每种错误在 UI 有**独立**中文文案（`AiErrorMessages`）。**禁止**统一成"网络错误"（任务书 §5.5 硬要求）。

---

## 6. AI Provider 层

### 6.1 四种能力接口

| 接口 | 方法 | 真实实现 | Mock |
|---|---|---|---|
| `ChatProvider` | `chat(ChatRequest): AiResult<String>` | `OpenAiChatProvider` | `MockChatProvider` |
| `SpeechToTextProvider` | `transcribe(File, language): AiResult<String>` | `OpenAiSttProvider` | `MockSttProvider` |
| `TextToSpeechProvider` | `synthesize(text, voice?): AiResult<ByteArray>` | `OpenAiTtsProvider` | `MockTtsProvider` |
| `RealtimeVoiceProvider` | `connect(instructions)` + `sendAudio(pcm)` + 回调 | `StepfunRealtimeProvider` | `MockRealtimeProvider` |

`AiProviderFactory.realtime(instructions)` 是后加的第四种能力；`instructions` 传当前场景的 `systemPrompt`。

### 6.2 降级规则（`AiProviderRegistry`）

```
某组可用 ⟺ config.enabled == true 且 config.baseUrl.isNotBlank()
不可用 → 返回对应 Mock 实现；**不得抛异常、不得崩溃**
```

因此"没配任何凭证"是完全受支持的状态，App 会跑通全流程并提示"当前为本地模拟"。

### 6.3 三 endpoint 报文要点

| 能力 | 请求 | 关键点 |
|---|---|---|
| 对话 | `POST {base}/chat/completions` | body = `model / messages / temperature`（`OpenAiWire.buildChatBody`）；**org.json 不转义非 ASCII**，中日文原样保留；`Authorization: Bearer <key>`；取 `choices[0].message.content` |
| 识别 | `POST {base}/audio/transcriptions` | multipart：`file` + `model` + `language`（`ja`/`en`）；**mime 按扩展名推断**（默认 `audio.m4a`/`audio/mp4`，VAD 产物 `.wav` → `audio/wav`）；取 `text` |
| 合成 | `POST {base}/audio/speech` | body = `model / input / voice / response_format=mp3`；voice 空则回落 `alloy` |

### 6.4 Mock 文案位置

全部收口在 `core/lang/MockReplies.kt`（按 `LangId` 分发），`ai/` 层只拿字符串 —— 这是让 `ai/` 保持零语言分支的手段，别把文案塞回 `ai/`。

---

## 7. 实时语音（Realtime）专题

### 7.1 端到端链路

```
[麦克风]
   │ AudioRecord 16kHz / 单声道 / PCM16
   ▼
[RealtimeAudioController]  ← 20ms 帧；能量 VAD 门控
   │ 帧 RMS ≥ -45 dBFS → 视为人声（推流）
   │ 连续静音 ≥ 800ms → 暂停推流（speechActive=false，本帧起丢弃）
   ▼
[RealtimeVoiceProvider.sendAudio(pcm)]
   │ RealtimeProtocol.appendAudio()：{"type":"input_audio_buffer.append","audio":"<base64 pcm16>"}
   ▼
[WebSocket  wss://api.stepfun.com/v1/realtime]  握手头 Authorization: Bearer <apiKey>
   │
   ├─ 下行 response.audio.delta (base64 pcm16) ──→ AudioTrack 直接播放
   ├─ 下行 response.audio_transcript.delta ──────→ 字幕流（逐字追加气泡）
   ├─ 下行 input_audio_buffer.speech_started ───→ **barge-in：立即 interrupt() 停播**
   └─ 下行 conversation.item.input_audio_transcription.completed → 用户气泡 + turns++
```

### 7.2 线协议事件表（`core/realtime/RealtimeProtocol.kt`）

**客户端 → 服务端（7 个构造器）**

| 方法 | `type` | 备注 |
|---|---|---|
| `sessionUpdate(...)` | `session.update` | 连上后立即发一次，携带 instructions / voice / turn_detection |
| `appendAudio(pcm)` | `input_audio_buffer.append` | 音频做 Base64 |
| `commit()` | `input_audio_buffer.commit` | 非 server_vad 模式手动提交 |
| `clear()` | `input_audio_buffer.clear` | |
| `responseCreate()` | `response.create` | |
| `responseCancel()` | `response.cancel` | barge-in 清服务端缓冲 |
| （私有）`envelope` | — | 自动生成 `event_id = evt_<millis>_<seq>` |

**服务端 → 客户端（13 类，`ServerEvent` 密封类）**

`session.created`、`session.updated`、`input_audio_buffer.speech_started`、`input_audio_buffer.speech_stopped`、`response.audio.delta`（→ `AudioDelta(pcm)`）、`response.audio.done`、`response.audio_transcript.delta`、`response.audio_transcript.done`、`response.text.delta`、`conversation.item.input_audio_transcription.completed`（→ `UserTranscript(text)`）、`response.done`、`error`（→ `ErrorEvent(message, code)`）、其它 → `Unknown(type)`。

未识别事件**一律忽略**（保证服务端新增事件不会让 App 崩）。

### 7.3 `session.update` 参数与默认值

| 字段 | 默认（`RealtimeOptions`） | 范围钳制（设置页保存时） |
|---|---|---|
| `modalities` | `["text","audio"]` | 固定 |
| `instructions` | 当前场景 `systemPrompt` | — |
| `voice` | 配置值；空则 `wenrounansheng` | — |
| `input_audio_format` / `output_audio_format` | `pcm16` | 固定 |
| `turn_detection.type` | `server_vad`（开关关掉则整块不下发） | — |
| `prefix_padding_ms` | 500 | 0–5000 |
| `silence_duration_ms` | 100 | 0–5000 |
| `energy_awakeness_threshold` | 2500 | 0–5000 |

> 注意：**服务端 `silence_duration_ms` 默认 100ms 与端上 800ms 静音门控是两个独立机制**，不要混淆：
> - `silence_duration_ms`（服务端）= 判定"用户说完了"的静音时长，影响断句；
> - 800ms（端上，硬编码在 `RealtimeAudioController.SILENCE_GATE_MS`）= 判定"暂时没人在说话"从而**暂停推流省计费**。改动后者会直接影响计费行为。

### 7.4 音频格式约定（不匹配就是噪音）

- 采样率 **16000 Hz**、**单声道**、**PCM16 小端**；
- 上行帧长 **20 ms = 320 样本 = 640 字节**；
- 下行 `AudioTrack` 用同参数 `MODE_STREAM` 直写；
- WAV 裁剪产物固定 44 字节头 + PCM（`AudioVadTrimmer.writeWav`）。

### 7.5 回环（AEC）防护 —— 三层，均为"用逻辑规避"而非真正的 AEC

| 层 | 位置 | 做法 |
|---|---|---|
| 1 | `ChatActivity.startRecording()` | 录音前 `speaker.setSuppressed(true)` + `releasePlayer()`，**录音期间持续抑制一切朗读** |
| 2 | `ChatActivity.maybeAutoPlay()` | 若 `recording` 为真直接返回（第二道闸） |
| 3 | `VoiceChatActivity.onUserSpeechStarted()` | 服务端一说"用户开始说话"立刻 `audio.interrupt()`，丢掉已排队音频 |

> ⚠️ **明确的现状**：**没有实现真正的端上 AEC / 降噪**（未用 `AcousticEchoCanceler`、未引 WebRTC / rnnoise）。当前策略是"切断外放 + barge-in 收口"。外放（免提）场景下仍有残余回环风险，正式方案见 §13.2。

### 7.6 静音计费防护

| 场景 | 机制 |
|---|---|
| 回合制（录音→上传） | 上传前 `AudioVadTrimmer` 解码裁头尾静音（prefix 300ms / suffix 500ms padding，阈值 -45 dBFS，最短 200ms）；**全静音或裁剪比 ≥0.98 返回 null → 回退原文件并提示用户**；解码任何一步失败也回退原文件，不影响主链路 |
| 回合制（防遗忘） | 单段录音**最长 60 s**，到点自动停止并发送（`ChatActivity.RECORD_MAX_MS`） |
| 实时 | 端上 800ms 静音门控暂停 `append`（`RealtimeAudioController.SILENCE_GATE_MS`） |

### 7.7 `VoiceChatActivity` UI 状态

- **准备层**（`readyOverlay`）：语伴名（日语 Yuki / 英语 Emma）、`开始对话` 按钮、未配置实时服务时显示 Mock 提示；
- **进行中**：21 根波形柱（按帧振幅移位驱动）、计时器（500ms tick）、字幕滚动容器、`麦克风`（手动打断 = `interrupt()` + `cancelResponse()`）、`CC`（显隐字幕）、`提示`（显示第一条目标表达）、`结束`；
- **总结层**（`summaryOverlay`）：对话时长 + 互动轮次 + `再来一轮` / `返回首页`；
- 首次进入申请 `RECORD_AUDIO`；拒绝则直接 `finish()`。

### 7.8 接入其它实时厂商要改哪里

1. `ProviderConfig.baseUrl` 填 `wss://...`（`BaseUrlNormalizer` 已放行 wss，**不做任何路径改写**）；
2. 若事件名不同 → 改 `RealtimeProtocol.parse` / `sessionUpdate` 的字符串映射（**只改这一个文件，纯逻辑可单测**）；
3. 若握手头不是 `Authorization: Bearer` → 改 `StepfunRealtimeProvider` 的 `Request.Builder`；
4. 若音频格式不同 → 改 `RealtimeAudioController.SAMPLE_RATE` / 帧长（注意连带改 §7.4 的 WAV 与门控帧数换算）。

---

## 8. 数据与存储

### 8.1 文件清单

| 文件 | 位置 | 内容 | 加密 |
|---|---|---|---|
| `secure_settings.bin` | `filesDir/` | 8 组 Provider 配置 + 全局设置（明文 JSON） | ✅ AES-256-GCM（AndroidKeyStore） |
| `study_progress.json` | `filesDir/` | 学习进度，按语言分层 | ❌ 明文 |
| `sessions/<sessionId>.json` | `filesDir/sessions/` | 每个会话一条记录 | ❌ 明文 |
| `rec_*.m4a` | `cacheDir/` | 原始录音（上传后或裁剪后被删） | ❌ |
| `vad_*.wav` | `cacheDir/` | VAD 裁剪产物（用后即删） | ❌ |
| `tts_*.mp3` | `cacheDir/` | TTS 合成音频（播完即删） | ❌ |
| `probe_audio.m4a` | `cacheDir/` | 「测试连接」的 64 字节探针（用后即删） | ❌ |

密钥别名 `langpartner_settings_key`，文件格式 `[1B version=1][12B IV][密文]`。

### 8.2 `study_progress.json`（version 2）

```json
{ "version": 2,
  "langs": {
    "ja": { "cards": { "<cardId>": { "ef": 2.5, "reps": 0, "intervalDays": 0,
                                      "dueEpochDay": 0, "lastEpochDay": 0 } } },
    "en": { "cards": { } } } }
```

`dueEpochDay` 是 `LocalDate.toEpochDay()`（天序号，不是毫秒）。**同名 cardId 在 ja / en 下是两条独立进度**（双语隔离，V19）。

### 8.3 `sessions/<sessionId>.json`

```json
{ "version": 2, "sessionId": "s_<epochMillis>", "langId": "ja",
  "scenarioId": "cafe", "startedAt": 1758364800000,
  "turns": [ { "role": "assistant", "text": "いらっしゃいませ" },
             { "role": "user", "text": "コーヒーをください" } ] }
```

「累计对话轮数」口径 = **`role == "user"` 的条数**（assistant 开场白不计一轮）。

### 8.4 `secure_settings.bin` 的明文结构（version 2）

```json
{ "version": 2, "activeLang": "ja", "autoPlayTts": true,
  "langs": { "ja": { "chat":     { "baseUrl":"", "apiKey":"", "model":"", "enabled":false },
                     "asr":      { ... },
                     "tts":      { ..., "voice":"" },
                     "realtime": { ..., "voice":"" },
                     "realtimeOptions": { "serverVad":true, "silenceDurationMs":100,
                                          "energyAwakenessThreshold":2500, "prefixPaddingMs":500 } },
             "en": { 同上 } } }
```

### 8.5 自愈与迁移规则（**局部，互不影响**）

| 损坏类型 | 行为 |
|---|---|
| `secure_settings.bin` 不存在 / 解密失败 | 返回默认 `AppSettings()`，**不崩溃** |
| 连续 3 次解密失败 | 删除文件 + 删除 Keystore 密钥，下次写入重建 |
| `langs` 缺失 | 补全 ja / en 两个语言 |
| 缺某一门语言（如 `langs.en`） | **只补 en，ja 不受影响** |
| 缺单组 `realtime` / `realtimeOptions` | 只补该组默认值（**version 1 → 2 平滑升级路径**，无需手动迁移） |
| `activeLang` 非法 | 回退 `ja` |
| `study_progress.json` 坏 JSON | 复制重命名为 `*.corrupt-<millis>` 后返回空结构 |
| `study_progress.json` version 缺失或 =1 | 顶层 `cards` 整体迁到 `langs.ja.cards`（**不丢旧数据**） |
| 单条进度缺字段 | 默认值 `ef=2.5 / reps=0 / intervalDays=0 / due/lastEpochDay=0` |
| session 文件坏 JSON | 隔离为 `*.corrupt-*` 并跳过 |

写盘一律**原子写**：先写 `<name>.tmp` → 删除原文件 → rename；rename 失败退化为直接写。

### 8.6 「清空全部数据」的作用范围（`ProfileActivity.confirmClear`）

调 3 个 clear：`studyStore.clear()`（删 progress 文件）+ `sessionStore.clear()`（删 sessions 目录）+ `settingsRepository.clear()`（删配置文件 + 删 Keystore 密钥）。**不动 assets、不动 cacheDir。**

---

## 9. 设置页与配置指引

### 9.1 8 组配置

按「当前语言」分别配置 4 组：**对话 / 语音识别 / 语音合成 / 实时语音**。字段语义：

| 字段 | 适用组 | 说明 |
|---|---|---|
| `baseUrl` | 全部 | 保存时经 `BaseUrlNormalizer` 校验，失败弹「地址需以 https:// 或 wss:// 开头」 |
| `apiKey` | 全部 | 默认掩码显示（`LogRedactor.mask`：`take(3)+"****"+takeLast(4)`，<8 字符显示 `****`）；**掩码状态下不允许编辑，保存时保留原值** |
| `model` | 全部 | 模型名 |
| `voice` | TTS / REALTIME | 音色 |
| `enabled` | 全部 | 必须为真且 baseUrl 非空才会用真实实现 |
| `serverVad` + 3 个参数 | 仅 REALTIME | 见 §7.3 |

切语言或离开页面 → Key 回到掩码状态。

### 9.2 推荐配置样例

**实时语音（stepfun realtime）：**

```
接口地址：wss://api.stepfun.com/v1/realtime
模型名：  step-audio-2-mini
音色：    （留空 → 默认 wenrounansheng）
启用：    开
server_vad：开，静音断句 100，能量阈值 2500，前置填充 500
```

**回合制三能力（任意 OpenAI 兼容服务，如阶跃 / 火山 / 自建网关）：**

```
对话：      https://api.xxx.com/v1              model=xxx
语音识别：  https://api.xxx.com/v1              model=whisper-1
语音合成：  https://api.xxx.com/v1              model=tts-1   voice=alloy
```

baseUrl 可以带 `/chat/completions` 等后缀，保存时会被自动裁掉。

### 9.3 「测试连接」的行为

用**当前表单里的即时值**构造 Provider 发最小请求（**不必先保存**）：

| 组 | 探针 |
|---|---|
| CHAT | 发一条 `Hi` 的 chat 请求 |
| ASR | 写 64 字节 `probe_audio.m4a` 后上传，测完删除 |
| TTS | 合成 `"你好"` |
| REALTIME | 建 WebSocket 等 `session.created`，**8 秒超时**（超时看门狗线程） |

结果映射（`AiErrorMessages.describeTest`）：成功→「连接成功」；401/403→Key 无效；404→地址或模型不存在；429→请求过于频繁；5xx→服务端错误；网络/超时→网络连接失败；坏 JSON→响应格式异常；其它→请求失败。

---

## 10. 内容资产（`app/src/main/assets/`）

| 文件 | 条目数 | 大小 | 口径 / 约束 | 单测断言 |
|---|---|---|---|---|
| `kana.json` | **46** | 2,760 B | 日语基础假名（平/片/罗） | 必须恰好 46 |
| `n5_vocab.json` | **138** | 16,104 B | N5 词汇卡；含 §4.4 的 **10 个锚点词** | ≥100 且含锚点 |
| `scenarios_ja.json` | **5** | 5,563 B | 场景；每个 `goalExpressions` ≥5；含 `systemPrompt` | ≥5 |
| `en_phonemes.json` | **40** | 3,326 B | 音素（实测 vowel **16** / consonant **24**） | ≥24，且 vowel ≥12、consonant ≥12 |
| `en_words.json` | **110** | 22,162 B | 英语词汇；id 唯一、`word` 匹配 `^[a-z][a-z'\-]*$`、**每条 `example` 必须包含该 word（忽略大小写）**；含 10 锚点词 | ≥100 且逐条校验 |
| `scenarios_en.json` | **5** | 5,171 B | 英语场景，约束同日语 | ≥5 |

**改资产后必须重跑 `AssetContentTest`（9 例）** —— 上面所有约束都是它断言的，写错会在构建阶段就失败。

---

## 11. 测试

### 11.1 怎么跑

```powershell
$env:JAVA_HOME = "D:\a\jbr"; .\gradlew.bat :app:testDebugUnitTest --console=plain
# 报告：app\build\reports\tests\testDebugUnitTest\index.html
# 原始：app\build\test-results\testDebugUnitTest\*.xml
```

### 11.2 明细（23 类 / **321 例**，全绿）

| 测试类 | 例数 | | 测试类 | 例数 |
|---|---|---|---|---|
| `SourcePolicyTest` | 4 | | `core.net.BaseUrlNormalizerTest` | 16 |
| `ai.OpenAiProviderTest` | 12 | | `core.net.HttpErrorsTest` | 16 |
| `core.answer.AnswerJudgeTest` | 17 | | `core.net.OpenAiWireTest` | 14 |
| `core.audio.VadPolicyTest` | 10 | | `core.realtime.RealtimeProtocolTest` | 9 |
| `core.chat.ReplyParserTest` | 11 | | `core.srs.Sm2SchedulerTest` | 11 |
| `core.context.ContextTrimmerTest` | 10 | | `data.asset.AssetContentTest` | 9 |
| `core.lang.LanguageModuleTest` | 13 | | `data.store.SessionStoreTest` | 8 |
| `core.lang.MockProviderTest` | 7 | | `data.store.SettingsRepositoryTest` | 12 |
| `core.lang.en.EnNormalizerTest` | 26 | | `data.store.StudyStoreTest` | 12 |
| `core.lang.en.EnSentenceSplitterTest` | 25 | | `core.lang.ja.KanaConverterTest` | 40 |
| `core.lang.en.EnTokenizerTest` | 27 | | `core.lang.ja.KanaNormalizerTest` | 7 |
| `core.log.LogRedactorTest` | 5 | | **合计** | **321** |

### 11.3 测试设计约定

1. **`SourcePolicyTest` 是"测试化的静态扫描"**：ui/ai 无语言专有符号、无密钥样式字符串、manifest 恰好 2 条权限、core/data 无 `android.*` import。改分层先看它。
2. **测试源里的罕见字符统一用 `\uXXXX` 转义书写**（弯引号、半角片假名、`´`、`″`）。原因：开发期间真的发生过 2 次源文件字节损坏，转义后彻底规避。**新增测试请沿用这个习惯。**
3. 判题/协议类断言全部基于**纯函数 + 假 Transport**，不依赖设备与网络。
4. `unitTests.isReturnDefaultValues = true`（android.jar 方法默认返回，不抛 `not mocked`）。

---

## 12. 已知缺陷与设计取舍（诚实清单）

### 12.1 任务书明确取舍（**不要试图"修得更好"，它们是按规范字面实现的**）

1. **缩写句末不终止**：`He lives in the U.S. Really?` 会切为 1 段（§5.6.5 明示取舍）。
2. **`´`(U+00B4) 不被映射为 `'`**：NFKC 先把它分解为 `U+0020 + U+0301`，映射表在其后执行，命中不到。断言结果为 `it ́s`。
3. **`″`(U+2033) 映射为两个 `'`**：NFKC 先分解为两个 `′`(U+2032)，各自命中映射表。断言为 `"a''`。
4. **英文判题抹平撇号**：`It's` 与 `its` 判对（§5.7 明示取舍）。
5. **UI 未使用 ViewModel**：§8 钉死的依赖清单不含 `lifecycle-viewmodel`；状态由 Activity + `onSaveInstanceState` 承载。**配置变更（如横竖屏）会丢当前卡片状态**，这是已知代价。
6. **「累计对话轮数」按 user 消息计数**，assistant 开场白不计入（规范未给口径，实现方定的）。

### 12.2 自主决策（任务书未覆盖，由实现方决定）

| # | 决策 | 理由 |
|---|---|---|
| 1 | `Card` 增加可选字段 `exampleBlank`（默认 `""`） | 英语例句挖空需随卡传递，§5.6.1 的 7 字段无处安放 |
| 2 | `ChartEntry` 增加可选 `alt`/`note`/`section`（默认 `""`） | 承载平/片假名切换、英语例词中文、vowel/consonant 分节 |
| 3 | `HttpCall` 增加可选 `formFields`（默认空 Map） | STT multipart 除文件外还需 `model`/`language` 两个文本字段 |
| 4 | `AiError`/`AiResult` 真实声明在 `ai` 包，`data/model/AiError.kt` 用类型别名 | 泛型类型别名无法解析嵌套类（`AiResult.Ok`）。`ChatMessage` 相反（真类在 `data.model`，`ai` 包为别名） |
| 5 | `CardProgress`（存储）与 `CardState`（算法入参）分开 | 职责清晰，UI 评分时显式转换 |
| 6 | 底部导航用 3 个 Activity + `FLAG_ACTIVITY_REORDER_TO_FRONT` | 不用 Navigation 组件（§2.3 禁） |
| 7 | 逐句朗读用**系统 TTS**，不占 AI Provider 额度 | 与图表页朗读同一 rationale |
| 8 | 「测试连接」用当前表单值即时构造 Provider（不必保存） | 提升可用性 |
| 9 | `SettingsRepository` 读取只做内存层自愈、**不在 read 路径回写** | 避免读操作产生写盘副作用 |
| 10 | Mock 文案收口 `core/lang/MockReplies.kt` | 保持 `ai/` 零语言分支 |
| 11 | `SourcePolicyTest` 把 §9/§11 的可 grep 项变成可持续回归断言 | |
| 12 | 测试源罕见字符一律 `\u` 转义 | 规避跨工具写入的编码损坏 |
| 13 | release 也保持 `isMinifyEnabled = false` | 规避 JSON/反射解析在混淆下的运行时风险（**代价：无混淆保护，§13.4**） |

### 12.3 实时语音的局限（当前版本的真实边界）

- ❌ **无端上 AEC / 降噪**（只有"切断外放 + barge-in"的逻辑规避），免提外放场景仍有回环风险；
- ❌ **无重连/退避**：WebSocket 断开只回调 `onClosed`，不会自动重连；
- ❌ **无后台保活**：`onDestroy` 直接释放音频与连接，切后台即中断；
- ❌ **无音频焦点管理**（未申请 `AudioFocus`，可能与其他 App 抢音）；
- ❌ **音频格式写死** pcm16/16k/单声道，未做协商降级；
- ⚠️ **实时语音未做任何真机验证**（连"能否成功握手"都只在设置页「测试连接」里人工可验）；
- ⚠️ 实时会话**不落盘**（`VoiceChatActivity` 的对话内容不会进 `SessionStore`，"累计对话轮数"不统计实时语音）。

---

## 13. 未完成项与后续 TODO（按优先级）

### 13.1 P0 —— 真机冒烟清单（**接手第一件事，逐条做完打勾**）

| # | 步骤 | 预期 |
|---|---|---|
| 1 | `adb install -r LangPartner-v1.0.0-release.apk`，启动 | 不崩溃，进入「今日」页，显示语言切换条与底部 4 Tab |
| 2 | **飞行模式**下日语卡片训练：输入错误答案 → 确认 → 评分 4 | 翻面显示读音与「回答错误：<原因>」；下一张正常出现 |
| 3 | 切英语，做 3 张卡，评分 2/3/4/5 各一次 | 无崩溃；回到日语后进度未被污染（切回日语看"今日待复习"数字与英语不同） |
| 4 | 杀掉 App 重进 | 当前语言与进度保持（持久化生效） |
| 5 | **不配任何凭证**，进入对话 → 选场景 → 文字发送 | 收到 `(mock)` 三段式回复（主标签 / [ZH] / [FIX]），有"未配置对话服务"提示 |
| 6 | 点 AI 回复中的单句 | 系统 TTS 朗读该句（日语读日文、英语读英文） |
| 7 | 场景列表点「实时语音对话」卡片 | 进入深色全屏页；未配实时服务时显示"当前为本地模拟" |
| 8 | 点「开始对话」，授权麦克风 | 波形柱有反应；说话时 `voiceGateHint` 隐藏、静音 800ms 后显示"已静默暂停推流" |
| 9 | 点「结束对话」 | 弹出总结层，时长与轮次有数值 |
| 10 | 我的页 → 清空全部数据 → 确认 | 统计归零；重进后为初始状态（语言回到日语） |
| 11 | 配置一个真实 OpenAI 兼容服务，逐组「测试连接」 | 4 组各自给出预期提示（成功/Key 无效/地址不存在…） |
| 12 | **配 stepfun realtime 后重做 7–9** | WebSocket 握手成功；能听到 AI 语音；字幕逐字出现；用户一开口 AI 立刻停播（barge-in） |

### 13.2 P1 —— 音频质量（用户已明确提出过）

1. **接真正的 AEC**：优先用系统 `AcousticEchoCanceler` + `NoiseSuppressor`（零依赖，`AudioRecord` 会话 ID 关联），效果不足再考虑 WebRTC AudioProcessing（会引入 native 依赖，需评估包体）。
2. **自动增益 / 降噪**：`AutomaticGainControl` 可一并开启（注意会改变电平，需重新标定 `VadPolicy` 阈值）。
3. **上行的 VAD 阈值可配置化**：目前 `-45 dBFS` 在 `VadPolicy` 与 `RealtimeAudioController` **两处硬编码**（后者注释已标注"与 VadPolicy 一致"，改一处忘另一处会导致行为不一致）——建议抽成可配置项。

### 13.3 P1 —— 实时语音健壮性

1. WebSocket **自动重连 + 指数退避**（`onFailure` / `onClosed` 后重试，上限 N 次）；
2. **生命周期对齐**：`onPause` 时暂停采集、`onResume` 恢复，而不是直接销毁；
3. **音频焦点**：`AudioManager.requestAudioFocus` + 来电/其他 App 抢占时暂停；
4. **实时会话落盘**：把实时轮次写入 `SessionStore`，让"累计对话轮数"覆盖实时语音；
5. **上行节流**：目前每 20ms 一次 WebSocket 帧（50 帧/秒），可合并为 40–60ms 一帧降低开销。

### 13.4 P2/P3 —— 其他

- **P2 功能**：更多语言（N3 级词汇、新语种）、复习提醒通知、学习曲线图、离线 TTS（当前日语/英语依赖系统 TTS 引擎，设备无对应语音包时会静默失败）；
- **P3 工程化**：
  - **接入 Git**（当前工程**没有版本控制**，这是交接的最大风险点之一）；
  - **CI**：把 `assembleDebug + testDebugUnitTest` 跑起来，守住 321 例不退化；
  - **混淆**：若要开 `isMinifyEnabled=true`，必须写 `proguard-rules.pro` keep 规则，至少保住所有 `data.model` / `ai` 包里被 `org.json` 反射式读写的成员（当前 `proguard-rules.pro` 基本为空）；
  - **版本号策略**：见 §14.1。

---

## 14. 发布流程

### 14.1 升级版本号

改 `app/build.gradle.kts`：

```kotlin
defaultConfig {
    versionCode = 3          // ← 每次发版必须 +1（整数，不可回退）
    versionName = "1.0.1"    // ← 展示用
}
```

> 两个字段都必须改。`versionCode` 是升级判定依据；`versionName` 只是给人看的。

### 14.2 出 release 包

```powershell
cd "D:\AI project\lang-partner"
$env:JAVA_HOME = "D:\a\jbr"
.\gradlew.bat clean :app:assembleRelease :app:testDebugUnitTest --console=plain
Copy-Item app\build\outputs\apk\release\app-release.apk .\LangPartner-v<版本号>-release.apk
# 验签（可选但推荐）
& "D:\AndroidSdk\build-tools\36.0.0\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
```

签名配置已内置：`app/build.gradle.kts` 读 `keystore.properties`；**文件缺失时自动退化为未签名 release 包**（不会构建失败，但那样的包无法安装/上架，注意别误发）。

### 14.3 ⚠️ keystore 备份与丢失后果（**最重要的一条**）

`keystore.properties` 当前内容（**明文口令，勿外传、勿提交到版本库**）：

```properties
storeFile=keystore/lang-partner-release.jks
storePassword=LangPartner@2026
keyAlias=langpartner
keyPassword=LangPartner@2026
```

- keystore 生成于 2026-09-20，`RSA 2048`，有效期 **10000 天**（约 27 年），别名 `langpartner`。
- **一旦丢失**：
  - 无法再发布任何更新到 `com.langpartner.app`（Android 要求升级包签名一致）；
  - 只能改包名重新上架，**老用户无法升级**、数据无法继承；
  - 招回/下架也只能走应用商店流程。
- **必须做的动作**：把 `.jks` + 口令**异地备份 ≥2 份**（如加密压缩后放入公司密钥库/密码管理器），并在团队内指定至少 2 名知情人。
- **不要做的动作**：把 `.jks` 或 `keystore.properties` 提交到任何 Git 仓库；不要改别名/口令（会与已发布包不兼容）。

---

## 15. 排障速查表

| 症状 | 最可能原因 | 处理 |
|---|---|---|
| 构建报 `requires Java 17` | `JAVA_HOME` 指向 JDK 11 | 显式 `JAVA_HOME=D:\a\jbr` |
| 构建报 `Failed to find Build Tools revision 35.0.0` | 未声明 buildToolsVersion | 确认 `buildToolsVersion = "36.0.0"` |
| `dexBuilderDebug` → `AccessDeniedException ... desugar_graph` | DLP 占用文件 | `gradlew --stop` → 删 `app/build/intermediates/**/desugar_graph*` → 重跑 |
| JSON 单测全挂、报 `not mocked` | 缺 `org.json:json:20240303` 测试依赖 | 检查 `build.gradle.kts` 的 `testImplementation` |
| `<include>` 里的控件 binding 找不到 | `<include>` 未加 `android:id` | 加 `android:id="@+id/xxx"`，用 `binding.xxx.内部Id` 访问 |
| 资源链接失败、报非法属性 | 用了不存在的属性（如 `android:attr/minimumWidth`） | 删掉该属性 |
| 单测报 `Method ... should be void` | 表达式体 `= runBlocking { ... }` 推断出非 Unit | 改成块体或显式声明 `: Unit` |
| 实时语音连不上、UI 显示"未配置实时语音服务" | `enabled` 未开 或 `baseUrl` 为空 | 到设置页 REALTIME 组检查；baseUrl 必须是 `wss://` |
| 实时语音一进就退 | 未授权麦克风（`finish()`） | 系统设置里给「语言陪练」开麦克风权限 |
| 实时语音有回音 | 免提外放 + 无 AEC | 先用耳机；根治见 §13.2 |
| 逐句朗读无声（iOS/某些机型） | 系统 TTS 未装对应语言语音包 | 系统设置 → 语言与输入 → 文字转语音，安装日语/英语语音数据 |
| 切语言后数据"串了" | 不应发生（V19 已断言） | 收集 `study_progress.json` 与复现步骤，属回归 bug |

---

## 16. 交接确认表

请接手人逐项确认：

| # | 确认项 | ✅ |
|---|---|---|
| 1 | 已在本地复跑 `assembleDebug + assembleRelease + testDebugUnitTest`，看到 `BUILD SUCCESSFUL` 与 321 例全绿 | ☐ |
| 2 | 已知晓 `JAVA_HOME=D:\a\jbr` 是每次构建的前置条件 | ☐ |
| 3 | **已把 `keystore/lang-partner-release.jks` 与口令异地备份**，并明确丢失后果 | ☐ |
| 4 | 已读 §3.2 分层铁律（core 禁 android import；ui/ai 禁语言专有符号） | ☐ |
| 5 | 已读 §12 已知缺陷与取舍，理解 6 条"规范明示取舍"不应被"顺手修好" | ☐ |
| 6 | 已按 §13.1 完成 V22 真机冒烟 12 条，或明确排期 | ☐ |
| 7 | 已知晓实时语音**未做端上 AEC**，且**未做任何真机验证** | ☐ |
| 8 | 已知晓工程**没有版本控制**，接手后第一件事建议入 Git（.jks 除外） | ☐ |
| 9 | 已确认 `local.properties` 的 `sdk.dir` 在自己的机器上正确 | ☐ |
| 10 | 已知晓任务书 `TASK-语言陪练App-从零实现.md` 是唯一验收依据，`REPORT-step-5-preview.md` 是实现方结论 | ☐ |

---

## 附录 A：关键常量速查

| 常量 | 值 | 位置 |
|---|---|---|
| 今日任务卡片上限 | 20 | `StudyStore.selectToday(limit=20)` |
| 已掌握判定 | `reps >= 5` | `ProfileActivity` / `StudyActivity` |
| SM-2 ef 下限 / 初始值 | 1.3 / 2.5 | `Sm2Scheduler` |
| SM-2 前两次间隔 | 1 天 / 6 天 | `Sm2Scheduler` |
| 上下文历史上限 | 24 条 | `ContextTrimmer.MAX_HISTORY` |
| 单段录音上限 | 60 s | `ChatActivity.RECORD_MAX_MS` |
| VAD 阈值 | -45 dBFS | `VadPolicy.Params.thresholdDb` |
| VAD 前/后 padding | 300 ms / 500 ms | `VadPolicy.Params` |
| VAD 最短语音 | 200 ms | `VadPolicy.Params.minSpeechMs` |
| VAD 裁剪比上限 | 0.98 | `VadPolicy.Params.maxTrimmedRatio` |
| VAD 最长处理 | 60 s | `AudioVadTrimmer.MAX_SECONDS` |
| 实时采样率 / 帧长 | 16000 Hz / 20 ms（320 样本 / 640 B） | `RealtimeAudioController` |
| 实时静音门控 | 800 ms | `RealtimeAudioController.SILENCE_GATE_MS` |
| 实时 server_vad 默认 | 开 / 100 ms / 2500 / 500 ms | `RealtimeOptions` |
| 实时测试连接超时 | 8 s | `SettingsActivity.probeRealtime` |
| 默认音色 | `wenrounansheng` | `StepfunRealtimeProvider` |
| TTS 默认音色 | `alloy` | `OpenAiWire.buildTtsBody` |
| 密钥掩码 | `take(3)+"****"+takeLast(4)`；<8 字符 → `****` | `LogRedactor.mask` |
| 配置 version / 进度 version | 2 / 2 | `AppSettings` / `StudyStore` |

## 附录 B：任务书章节索引（定位规格时用）

`§2.1` 工具链 · `§2.2` JDK · `§2.3` 禁用组件 · `§2.5` 隔离要求 · `§3.1` 目录结构 · `§3.2` 应用标识 ·
`§4.3` 功能需求 M0–M5 · `§4.4` 资产要求 · `§5.1` 包结构 · `§5.2` Provider 接口 · `§5.3` 线协议 ·
`§5.4` baseUrl 规范化 · `§5.5` 错误分类 · `§5.6` 语言模块（含 .2 假名转换 / .3 归一化 / .4 分词 / .5 分句 / .6 资产映射）·
`§5.7` 判题 · `§5.8` SM-2 · `§5.9` 上下文裁剪 · `§5.10` 加密存储 · `§5.11` 数据持久化 · `§6` 安全合规 ·
`§7` 权限 · `§8` 依赖清单 · `§9` 验收标准 V1–V22 · `§10` 自查命令 · `§11` 禁止事项 · `§12` 已知失败模式 ·
`§13` 交付物 · `§14` 完成定义 · `附录 A/B` 场景 systemPrompt 模板 · `附录 C` 场景 JSON 结构 · `附录 D` 测试向量

---

*文档结束。如发现本文档与代码不一致，**以代码为准**，并在修正后同步本文档。*

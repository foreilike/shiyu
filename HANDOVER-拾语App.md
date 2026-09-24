# 交接文档 — 拾语（Shiyu）Android App

> ⚠️ **要接新语言（韩/法/德/俄…）请看 `HANDOVER-拾语-接入新语言与语音.md`** ——
> 那是面向下一步工作的实操手册（逐步清单、各语言特有的坑、词库怎么造），
> 并记录了 2026-09-22 那轮大改（实时语音闪退修复、卡片升级、选书、拼写考试、语速等）。
> 本文档仍是**架构与功能总纲**。

> 文档版本：v2.0（2026-09-20）
> 交接对象：接手本工程的开发/维护人员
> v2.0 相对 v1.0 的变化：应用改名「拾语」、包名改 `com.shiyu.app`、UI 按新设计稿重排为
> 五 Tab、新增每日统计与成就、新增可修改的用户名、换 App 图标。逐条见 **§17 改造记录**。
> 本文档的数字均在 2026-09-20 现场复跑验证。

---

## 0. 30 秒速览

| 项 | 值 |
|---|---|
| 应用名 / 包名 | **拾语** / `com.shiyu.app` |
| 版本 | `versionCode = 4`、`versionName = 1.0.3`（新包名 = 新应用身份，版本号重新起算；**每版必 +1，见 §14.1.1**） |
| 形态 | **纯原生 Android（Kotlin + Views/XML + ViewBinding），单 module（`:app`）** |
| 学习语言 | 日语 + 英语双语，可切换，进度/配置全隔离；韩/西/法在「课程」页标为即将支持 |
| 工程根目录 | `D:\AI project\lang-partner`（目录名保留，未随应用改名） |
| 主源码规模 | **82 个 `.kt`（9,743 行）+ 21 个布局 + 45 个 drawable + 18 份 assets JSON**<br>（核对于 2026-09-23；原文写的 72 / 6,519 / 16 / 36 / 6 均已过时） |
| 测试 | **29 个测试类 / 417 个单测，全绿**（fail=0 err=0 skip=0），`lintDebug` 0 error<br>（原文写的 26 类 / 387 例已过时，明细见 §11.2） |
| 交付 APK | `Shiyu-v1.0.3-release.apk`（9.62 MB，已签名）<br>历史包 `Shiyu-v1.0.0/1.0.1/1.0.2-release.apk` 全部保留 |
| 构建命令 | 见 §2.2（**必须显式设置 `JAVA_HOME=D:\a\jbr`**） |
| 联网能力 | 4 种 AI 能力（对话 / 识别 / 合成 / 实时语音）；**未配置凭证时全部降级为本地 Mock，App 照常可跑完流程** |
| 版本控制 | 已接入 Git（本地仓库，`.jks` 与 `keystore.properties` 已在 `.gitignore`） |

**信息架构（五个一级 Tab + 三个二级页）：**

```
今日 HomeActivity      仪表盘：今日目标环 / 连续天数 / 续学卡 / 实时语音卡 / 两个快捷入口
课程 LanguagesActivity 语种切换（日·英可用，韩·西·法为「即将支持」灰态）
对话 ChatListActivity  ← 底部中央凸起麦克风指向这里；实时语音 hero + 场景练习列表
复习 ReviewActivity    今日排卡 → 作答判题 → 翻转卡 → SM-2 四级评分
我的 ProfileActivity   可改名 / 三项总览 / 本周柱状图 / 6 枚成就 / 分语言数据 / 设置入口

二级页：ChartActivity（发音表）· SettingsActivity（8 组 Provider）· ChatActivity（单场景对话）
全屏页：VoiceChatActivity（实时语音，深色沉浸，独立 Theme.Shiyu.Voice）
```

**接手前必读的四件事：**

1. **`JAVA_HOME` 是本机最大的坑。** 本机默认 JDK 11 与 AGP 8.7.3 不兼容，每次构建前必须显式指向 `D:\a\jbr`（Android Studio 自带 JBR 21）。
2. **`keystore/` 与 `keystore.properties` 是发布身份的唯一凭据。** 丢失后**无法再向同一应用 ID 发布任何更新**（只能换包名重新上架）。必须立即异地备份，详见 §14.3。
3. **布局里不要再写 `android:background` 给 `<Button>`。** 主题是 Theme.Material3，`<Button>` 会被自动换成 `MaterialButton`，它**完全忽略 `android:background`**，用主题默认的陶土填充；如果文字色恰好也是陶土，就变成一个没字的深色方块（v1.0 的「显示 Key」「测试连接」「录音」三处就是这么坏的）。统一用 §17.3 的三个 `ShiyuButton.*` 样式。
4. **「单测全绿」不等于「能跑」，这个仓库已经栽过两次。** v1.0 交付时的 `MessageAdapter` 有一行必然抛 `ClassCastException` 的强转，「场景对话」在真机上**一进就崩**（§17.7 ③）；本轮又抓出「返回首页」按钮失灵、朗读语言错误、「待复习」跨语言串号三个 bug（§17.11）。四个都有同一个特征：**单测覆盖不到 View 层与异步初始化**。UI 改动请务必真机验，方法见 §13.1 开头的操作经验。

---

## 1. 交付物清单

### 1.1 代码与配置（需纳入版本管理）

| 路径 | 说明 |
|---|---|
| `app/src/main/java/com/shiyu/app/` | 主源码（**82 个 .kt / 9,743 行**），分层见 §3 |
| `app/src/test/java/com/shiyu/app/` | 单元测试（**29 个类 / 417 例**） |
| `app/src/main/assets/*.json` | **18 份**内容资产（假名/词汇/音素/场景/预置配置），见 §10 |
| `app/src/main/res/` | **21 布局 + 45 drawable** + mipmap-anydpi/-v26（自适应图标）+ values / values-v27 |
| `app/src/main/AndroidManifest.xml` | **12 个 Activity**，恰好 2 条权限（INTERNET / RECORD_AUDIO） |
| `app/build.gradle.kts` | 模块构建脚本（含 release 签名配置） |
| `build.gradle.kts` / `settings.gradle.kts` | 根构建脚本（module 名 `Shiyu`，include `:app`） |
| `gradle.properties` | `-Dfile.encoding=UTF-8`、`android.useAndroidX=true` |
| `local.properties` | `sdk.dir=D\:/AndroidSdk`（**本机路径，换机需改**） |
| `gradle/wrapper/` + `gradlew` + `gradlew.bat` | Gradle 8.9 wrapper（从 `bottom-radar-android` 只读复制） |
| `app/proguard-rules.pro` | 存在但**当前未启用**（release 也 `isMinifyEnabled=false`） |

### 1.2 文档与预览

| 路径 | 说明 |
|---|---|
| `TASK-语言陪练App-从零实现.md` | 原始任务书（74 KB，含 §0–§14 与附录 A–D），**本次交接的验收依据** |
| `REPORT-step-5-preview.md` | 实现方的交付报告：需求对照表、自查原始输出、已知缺陷、三轮补充节 |
| `shiyu-ui-prototype.html` | **当前设计稿**（在桌面，不在工程内）—— UI 验收以它为准 |
| `HANDOVER-拾语App.md` | **本文档** |

### 1.3 产物与凭据（**不入版本库，须单独备份**）

| 路径 | 大小 / 校验 | 说明 |
|---|---|---|
| `app/build/outputs/apk/release/app-release.apk` | 5,606,632 B | 构建产物（可重新生成） |
| `app/build/outputs/apk/debug/app-debug.apk` | 7,044,404 B | 构建产物（可重新生成） |
| `Shiyu-v1.0.0-release.apk` | 5,606,632 B | release 包副本（发版用，可重新复制） |
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
adb install -r "D:/AI project/lang-partner/Shiyu-v1.0.0-release.apk"
adb shell am start -n com.shiyu.app/.ui.home.HomeActivity
adb logcat -s Shiyu:*          # 目前 App 几乎没有自定义日志，主要看崩溃栈
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
app/src/main/java/com/shiyu/app/
├── AppGraph.kt                     ← 极简服务定位器：初始化 6 个单例，UI 只从这里取
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
│   ├── stats/Achievements.kt        ← **新增** 6 枚成就的纯判定（current / target / percent）
│   ├── context/ContextTrimmer.kt    ← 上下文裁剪（上限 24 条）
│   ├── net/                         ← HttpTransport 接口 / HttpCall / HttpErrors / BaseUrlNormalizer / OpenAiWire
│   ├── chat/ReplyParser.kt          ← AI 回复三分段解析（[JA]/[EN] + [ZH] + [FIX]）
│   ├── log/LogRedactor.kt           ← 密钥脱敏
│   ├── audio/VadPolicy.kt           ← 能量 VAD（纯算法，无 android 依赖）
│   └── realtime/RealtimeProtocol.kt ← stepfun realtime 线协议（纯 JSON 编解码）
├── data/                           ← 模型与持久化
│   ├── model/  Card · ChartEntry · Scenario · ChatMessage · CardProgress
│   │           SessionRecord · ProviderConfig · LanguageConfig · RealtimeOptions
│   │           AppSettings（含 nickname）· AiError · **DayStat（新增）**
│   ├── store/  StudyStore · SessionStore · SettingsRepository · **DailyStatsStore（新增）**
│   ├── secret/ SecretStore(接口) · KeystoreSecretStore(AES-256-GCM 实现)
│   └── asset/  AssetRepository.kt   ← 只负责把 assets 里的 JSON 原文读成 String
├── ai/                             ← Provider 抽象与实现（本次改造未动）
│   ├── AiProvider.kt · RealtimeVoiceProvider.kt · AiProviderRegistry.kt
│   ├── mock/  MockProviders · MockRealtimeProvider
│   └── openai/ OkHttpTransport · OpenAiChatProvider · OpenAiSttProvider
│               OpenAiTtsProvider · StepfunRealtimeProvider
├── audio/                          ← **平台层**（依赖 android.media，因此不能放 core）
│   ├── AudioVadTrimmer.kt          ← m4a → PCM16 → 裁头尾静音 → WAV
│   └── RealtimeAudioController.kt  ← AudioRecord 采集 + 静音门控 + AudioTrack 播放 + barge-in
└── ui/                             ← Activity 层（无 ViewModel，状态在 Activity 字段）
    ├── BaseActivity.kt                ← **新增** edge-to-edge + WindowInsets + AppGraph.init
    ├── LangStyle.kt                   ← **新增** 语伴名（yoyo / Emma）与语种点缀色，按 LangId 分发
    ├── Speaker.kt · AiErrorMessages.kt
    ├── nav/BottomNav.kt               ← **新增** 五 Tab 导航的唯一接线处
    ├── widget/GoalRingView.kt         ← **新增** 今日目标进度环（自绘）
    ├── widget/MiniWaveView.kt         ← **新增** 语音卡上的装饰波形
    ├── home/HomeActivity.kt           ← **新增** Tab「今日」仪表盘（启动页）
    ├── langs/LanguagesActivity.kt     ← **新增** Tab「课程」语种切换
    ├── chat/ChatListActivity.kt       ← Tab「对话」（实时语音 hero + 场景列表）
    ├── chat/ChatActivity.kt           ← 单场景对话（录音/文字 → STT → LLM → TTS）
    ├── chat/MessageAdapter.kt
    ├── review/ReviewActivity.kt       ← Tab「复习」（原 study/StudyActivity 迁移+改造）
    ├── voice/VoiceChatActivity.kt     ← 实时语音（全屏深色沉浸，独立 Theme.Shiyu.Voice）
    ├── profile/ProfileActivity.kt     ← Tab「我的」（可改名 / 周柱状图 / 成就）
    ├── chart/ChartActivity.kt         ← 二级页「发音表」（原 Tab，现降级）
    └── settings/SettingsActivity.kt   ← 二级页，8 组 Provider + 语言芯片 + 一键同步
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
| `daily_stats.json` | `filesDir/` | 每日学习活动（时长/复习张数/对话轮数），连续天数与周柱状图的来源 | ❌ 明文 |
| `rec_*.m4a` | `cacheDir/` | 原始录音（上传后或裁剪后被删） | ❌ |
| `vad_*.wav` | `cacheDir/` | VAD 裁剪产物（用后即删） | ❌ |
| `tts_*.mp3` | `cacheDir/` | TTS 合成音频（播完即删） | ❌ |
| `probe_audio.m4a` | `cacheDir/` | 「测试连接」的 64 字节探针（用后即删） | ❌ |

密钥别名 `shiyu_settings_key`，文件格式 `[1B version=1][12B IV][密文]`。

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
模型名：  step-audio-2-mini     ← 必填，App 会自动拼成 ?model=step-audio-2-mini
音色：    qingchunshaonv        ← 青春少女（女声）；留空时 App 默认用它
                                ← 另一个可选值 wenrounansheng 是【温柔男声】
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

### 11.2 明细（27 类 / **387 例**，全绿）

> **2026-09-23 重新核对**：下表原为 **26 类 / 387 例**，实际已增长到 **29 类 / 417 例**。
> 下面按最新实测值重写（新增 `RealtimeProtocolTest` +2、`TtsDiskCacheTest` +11、
> `ScenarioParserTest` +16、`LanguageModuleTest` 13→14、`SettingsRepositoryTest` 22，其余有微调）。

| 测试类 | 例数 | | 测试类 | 例数 |
|---|---|---|---|---|
| `SourcePolicyTest` | 4 | | `core.net.BaseUrlNormalizerTest` | 16 |
| `ai.OpenAiProviderTest` | 12 | | `core.net.HttpErrorsTest` | 16 |
| `core.answer.AnswerJudgeTest` | 17 | | `core.net.OpenAiWireTest` | 14 |
| `core.audio.VadPolicyTest` | 10 | | `core.realtime.RealtimeProtocolTest` | **20** |
| `core.chat.ReplyParserTest` | 11 | | `core.srs.Sm2SchedulerTest` | 11 |
| `core.srs.DailyQuotaTest` | 7 | | `data.store.AtomicFileTest` | 11 |
| `core.context.ContextTrimmerTest` | 10 | | `core.stats.AchievementsTest` | 8 |
| `core.lang.LanguageModuleTest` | **14** | | `data.asset.AssetContentTest` | 9 |
| `core.lang.MockProviderTest` | 7 | | `data.store.DailyStatsStoreTest` | 21 |
| `core.lang.en.EnNormalizerTest` | 26 | | `data.store.SessionStoreTest` | 8 |
| `core.lang.en.EnSentenceSplitterTest` | 25 | | `data.store.SettingsRepositoryTest` | 22 |
| `core.lang.en.EnTokenizerTest` | 27 | | `data.store.StudyStoreTest` | 12 |
| `core.lang.ja.KanaConverterTest` | 40 | | `core.lang.ja.KanaNormalizerTest` | 7 |
| `core.log.LogRedactorTest` | 5 | | `data.store.TtsDiskCacheTest` | 11 |
| **`core.lang.ja.ScenarioParserTest`** | **16** | | | |
| | | | **合计（29 类）** | **417** |

**测试盲区（2026-09-23 审计发现，状态已更新）**：下列**纯逻辑**类当时没有对应 `*Test`。

| 类 | 为什么该测 | 状态 |
|---|---|---|
| `core/lang/ja/ScenarioParser.kt` | 场景 JSON 解析，**被日英模块共用**，一个 bug 同时废掉两门语言的场景对话页；类注释承诺「不抛异常」却无人守 | ✅ **已补（16 例）**，见 §11.4 |
| `data/asset/PresetConfig.kt` | 含**版本迁移与强制同步**逻辑（`PRESET_VERSION = 2`，本地版本低就覆盖推送）。改错会**静默冲掉用户填的 API Key**，且不可恢复 | ❌ 未补 —— **当前测不了**，见下方说明 |
| `core/lang/LanguageRegistry.kt` | 语言注册表，**接新语言时第一处要改的地方**（见 `HANDOVER-拾语-接入新语言与语音.md`） | ❌ 未补 |
| `core/lang/VoicePrompts.kt` | 实时语音提示词生成（纯拼字符串，最好测） | ❌ 未补 |
| `core/lang/ja/JaModule.kt` / `core/lang/en/EnModule.kt` | 语言模块实现（目前只测了 `LanguageModule` 接口） | ❌ 未补 |

> **`PresetConfig` 为什么现在测不了**：它在 `data/asset`，读 assets 需要 `android.content.Context`；
> 而本工程测试依赖只有 **JUnit4 + org.json，没有 Mockito / Robolectric**
> （`unitTests.isReturnDefaultValues = true`）。直接写会得到 android.jar 的桩，
> `context.assets.open()` 返回 `null` 并被 `catch` 吞掉 —— **测出来永远是"啥也没干"，
> 看着全绿实则零验证**。
> **必须先做一次小重构**：把「决定写哪些组」的纯逻辑从「读 assets」里抽出来放进 `core/`，
> 才能按工程既有约定单测。该改动会碰到配置推送路径，**属需真机验的改动，不要顺手做**。

加粗为本次改造新增：`AchievementsTest`（成就判定）、`DailyStatsStoreTest`（每日统计 / 连续天数 /
本周切片 / 坏 JSON 隔离），另外 `SettingsRepositoryTest` 从 12 例加到 17 例（新增 nickname 的
读写、trim、截断、清空、以及「老文件没有 nickname 字段也不能崩」）。

**`lintDebug` 也必须是 0 error。** 本次把 14 个 `android:tint` → `app:tint` 和一处
`MissingPermission` 都修干净了，`./gradlew.bat :app:lintDebug` 现在能通过；再引入
lint error 会直接让这条命令失败，别忽略。

### 11.3 测试设计约定

1. **`SourcePolicyTest` 是"测试化的静态扫描"**：ui/ai 无语言专有符号、无密钥样式字符串、manifest 恰好 2 条权限、core/data 无 `android.*` import。改分层先看它。
2. **测试源里的罕见字符统一用 `\uXXXX` 转义书写**（弯引号、半角片假名、`´`、`″`）。原因：开发期间真的发生过 2 次源文件字节损坏，转义后彻底规避。**新增测试请沿用这个习惯。**
3. 判题/协议类断言全部基于**纯函数 + 假 Transport**，不依赖设备与网络。
4. `unitTests.isReturnDefaultValues = true`（android.jar 方法默认返回，不抛 `not mocked`）。
   副作用：**凡是依赖 `Context` 的类都没法直接单测**（拿到的是桩，例如 `assets.open()` 返回 `null`
   且常被 try/catch 吞掉 → 测试恒绿但零验证）。要测就得先把纯逻辑抽到 `core/`。

### 11.4 新增：`ScenarioParserTest`（2026-09-23 补测，16 例）

补测的缘由见 §11.2 的盲区表。这个类**被日英两个模块共用**，且类注释承诺
「损坏字段用缺省值跳过，**不抛异常**」却无人守，所以重心放在**坏输入**上：

| 组 | 用例 | 守的是什么 |
|---|---|---|
| 正常路径 | 全字段解析 / 顺序与多条目 / 多个 goalExpressions | 基本正确性 |
| **坏输入** | 空串 / 乱码 / **顶层是对象而非数组** / JSON 截断 | **一律返回 `emptyList`，绝不抛异常**（注释的核心承诺） |
| 字段缺失 | 空对象 → 全空串；`goalExpressions` 缺失 / 空数组 / 含空串被过滤 | 缺省回落不崩 |
| 混入坏条目 | `["string", null, 42, {...}]` → **跳过坏的、保留好的**（断言剩 1 条且是 good） | `optJSONObject(i) ?: continue` 这条防御 |
| 配套扩展 | `JSONObject.str()`：缺失 / `NULL` → 空串；有值 → 原值 | `EnModule` 在读 symbol/example/exampleZh/type/id |

> 写这个测试时的一个注意点：`ScenarioParser` 是 `internal`，测试必须放在**同包**
> `com.shiyu.app.core.lang.ja` 才能访问（Kotlin 的 test source set 与 main 属同一 module，可以）。

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

> 本节在 2026-09-21 有大改动 —— 原来列的 7 条里 5 条已修复，逐条标注如下。

**已修复：**

- ✅ **端上 AEC / 降噪**：`RealtimeAudioController.attachAudioEffects` 接入了系统
  `AcousticEchoCanceler` + `NoiseSuppressor`（best-effort，设备不支持时静默跳过）。
  **刻意没开 `AutomaticGainControl`** —— 它会改变采集电平，而端上门控的 -45 dBFS 与服务端
  VAD 的 200 都是按当前电平标定的，开了就得重新标定一遍。
- ✅ **断线自动重连**：指数退避 1s → 2s → 4s，最多 3 次；401/403/404 直接报错不重试
  （重试也没用，别让用户白等）；用户主动关闭后不再重连。UI 会显示「连接中断，正在重连（第 N 次）」。
- ✅ **后台处理**：`onStop` 暂停采集与外放但**保留 WebSocket**，`onStart` 恢复。
  不再"切后台即中断"，也不会"后台还一直开着麦克风"。
- ✅ **实时会话落盘**：结束时整条写入 `SessionStore`（mode = `realtime`，带时长），
  「累计对话轮数」现在覆盖实时语音，也能在「历史对话」里回看。
- ✅ **音频格式**：16kHz 那个错误假设已纠正为 **24kHz**（见 §17.13），帧长 480 样本 / 960 字节。

**仍然存在：**

- ❌ **无音频焦点管理**（未申请 `AudioFocus`，可能与其他 App 抢音）；
- ⚠️ **音频格式仍写死** pcm16/24k/单声道，未做协商降级；
- ⚠️ **重连后不会补发断线期间的对话**，服务端会话上下文可能已过期（未验证）；
- ⚠️ **实时语音的真机验证仍不完整**：握手、字幕、打断、埋点已验；AEC 效果、重连、
  后台恢复、中文字幕开关**都还没在真机上验过**（都是 2026-09-21 新加的）。

## 13. 未完成项与后续 TODO（按优先级）

### 13.1 P0 —— 真机冒烟清单

测试机：魅族 22（`adb devices` 确认）。**release 与 debug 签名不同，不能互相覆盖安装，切换前先卸载。**

> **操作经验（省下接手人的时间）：**
> - 这台机器的 USB 连接**约 1–2 分钟掉一次**，`adb tcpip 5555` 在本机网络下连不上；截图前先
>   `adb shell svc power stayon true`，并确认 `dumpsys power | grep mWakefulness` 不是 Dozing。
> - **`adb shell input tap` 连续点击经常丢**。不要靠肉眼估算坐标：先
>   `uiautomator dump` 拿真实 bounds 再点，并且每次点完用
>   `dumpsys activity activities | grep topResumedActivity` 复核落点。
> - Git Bash 下 `/sdcard/...` 会被当成本地路径，拉 dump 文件要 `export MSYS_NO_PATHCONV=1`。
> - **想读落盘数据就装 debug 包**（release 不可调试，`run-as` 用不了）：
>   `adb shell run-as com.shiyu.app cat files/daily_stats.json`。

**已在真机验证通过（2026-09-20 / 09-21）：**

| # | 步骤 | 结果 |
|---|---|---|
| 1 | 安装启动，进「今日」页 | ✅ 不崩；日期/问候语/目标环/续学卡/语音卡/双入口齐全 |
| 2 | 五个 Tab 逐个点开 | ✅ 今日 / 课程 / 对话 / 复习 / 我的 全部正常，中央麦克风凸起正确 |
| 3 | 状态栏与页头 | ✅ 暖纸底延伸到屏幕顶端，状态栏图标为深色，页头不被压住 |
| 4 | 课程页切语言 | ✅ 卡片总数变 110、韩/西/法为灰态。<br>⚠️ 原文写的「语伴变 Emma」**已过时**：2026-09-23 核对 `LangStyle.partnerName` 确认**两门语言都叫 yoyo**（用户要求统一成一个名字） |
| 5 | 复习页出卡 | ✅ 卡片正面/例句挖空正常 |
| 6 | 复习页输入答案 → 确认 | ✅ 两段式翻转正常；显示读音与判题结果；四个评分键出现 |
| 7 | 点评分「太简单了」 | ✅ 进下一张；「今日待复习」减 1；首页「复习 1 张」同步更新 |
| 8 | 首页 / 复习页口径 | ✅ 两个页面显示的待复习张数**完全一致**（走同一个 `DailyQuota`） |
| 9 | **双语隔离（数据层）** | ✅ 日语评 1 张后 `study_progress.json` 为 `ja:1 / en:0`；`daily_stats.json` 的 `reviewedByLang` 为 `{"ja":1}` |
| 10 | **双语隔离（显示层）** | ✅ 日语评 1 张后切到英语，英语仍显示 20 张（修复前会被扣成 19） |
| 11 | **杀进程重启** | ✅ 语言、进度、连续天数全部保持 |
| 12 | 发音表（二级页） | ✅ 日语五十音 / 英语音素表分节正常，卡片格子排列正确 |
| 13 | **发音表点格子朗读** | ✅ 日志实测 `Synthesis request for locale jpn-JPN` + `AudioTrack start`（真的发声） |
| 14 | **片假名切换** | ✅ 网格切成 ア イ ウ エ オ…，顶部说明同步为「あ / ア　a」 |
| 15 | **对话页点句子朗读** | ✅ 日志实测 `locale eng-USA` + `AudioTrack start` |
| 16 | 我的页数据 | ✅ 连续天数 / 掌握词汇 / 累计小时 / 周柱状图（今天有柱子）/ 成就进度均为真实值 |
| 17 | 我的页改名 | ✅ 改名后头像取首字、首页问候语带上称呼 |
| 18 | 场景对话（不配凭证） | ✅ 开场白分句、目标表达、「未配置对话服务」提示、按钮文字均可见 |
| 19 | 文字发送一轮 | ✅ 用户气泡靠右、AI 气泡靠左、`(mock)` 三段式回复 + 中文翻译 |
| 20 | 对话轮次埋点 | ✅ 我的页「对话 1 轮」、「健谈者 1/100」同步更新 |
| 21 | **实时语音 Mock 全流程** | ✅ 准备层（头像 E /「和 Emma 聊聊天」跟随语言）→ 开始对话 → 计时、波形、「正在聆听…」、静音门控提示「已静默暂停推流」、Mock 字幕气泡 → 结束 → 总结层（时长/轮次） |
| 22 | **实时语音埋点** | ✅ `daily_stats.json` 的 `voiceSeconds` 累加；「初次开口」成就解锁 |
| 23 | **「返回首页」按钮** | ✅ 正确回到「今日」页（修复前会重复弹总结层，用户被困在语音页） |
| 24 | **一键同步 · 空值防护** | ✅ 对话组为空时给提示，不弹确认框 |
| 25 | **一键同步 · 实际覆盖** | ✅ 对话组填 `https://api.example.com/v1` 后同步：语音识别组被填上、**英语语言的配置也被填上** |
| 26 | **一键同步 · 边界** | ✅ 实时语音组的 `wss://api.stepfun.com/v1/realtime` **未被覆盖**（设计如此） |
| 27 | 设置页语言芯片 | ✅ 标题随语言变，芯片高亮正确 |
| 28 | 设置页 | ✅ 「显示」「测试连接」「保存」按钮文字全部可见；输入框间距正常 |
| 29 | **横竖屏切换** | ✅ 不崩溃，布局自动重排，底部导航正确（卡片区在横屏需滚动，属已知取舍） |
| 30 | 清空全部数据 | ✅ 统计归零，「初次开口」回到 0/1 未解锁 |

**需要真实凭据、本次未验（接手人自己配好服务后走一遍即可）：**

| # | 步骤 | 预期 |
|---|---|---|
| 31 | 填一个真实 OpenAI 兼容服务，逐组「测试连接」 | 4 组各自给出预期中文提示（成功 / Key 无效 / 地址不存在…） |
| 32 | 配 stepfun realtime 后重做 21–22 | WebSocket 握手成功；能听到 AI 语音；字幕逐字出现；用户一开口立刻停播（barge-in） |
| 33 | 回合制录音链路（真机麦克风 → STT → LLM → TTS）<br>**操作：场景对话页点「录音」→ 说一句 → 点「停止」** | 上传前 VAD 裁静音；识别文本回填输入框 |
| 34 | **barge-in 打断**：AI 说话时开口 | 立即停且不再自己接上（本轮修） |
| 35 | **历史对话**：我的 → 历史对话 | 列出场景对话与实时语音；点进详情看到完整往返（本轮新增） |
| 36 | **中文字幕开关**：我的 → 实时语音显示中文翻译 | 打开后 AI 气泡下出现中文小字（本轮新增） |
| 37 | **断线重连**：对话中开飞行模式再关 | 状态栏出现「正在重连（第 N 次）」，恢复后能继续（本轮新增） |
| 38 | **后台恢复**：对话中切到桌面再回来 | 不中断，回来后麦克风恢复采集（本轮新增） |
| 39 | **免提外放**：开免提让 AI 说话 | 不应出现自己跟自己对话（本轮接入 AEC，需实测） |

> 第 33 条是唯一**完全没被覆盖过**的一段：`ChatActivity` 的录音 → `AudioVadTrimmer` →
> `OpenAiSttProvider` 这条链路只有单测覆盖，真机上一次都没跑过。不是本次改造引入的风险，
> 但接手后建议优先补。

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

### 14.1.1 ★ APK 发布约定（2026-09-23 定，务必照做）

1. **每个新 APK 必须递增版本号**：`versionCode` +1、`versionName` 同步改，
   产物按 `Shiyu-v<versionName>-release.apk` 命名（如 `Shiyu-v1.0.1-release.apk`）。
   签名不变 → 手机上覆盖安装，学习进度不丢。
2. **历史 APK 一律保留，不覆盖、不删除。**  Archived 副本同步进
   `D:\AI project\项目备份\拾语\发布物\`。
   - 唯一会被覆盖的是构建产物 `app/build/outputs/apk/release/app-release.apk` ——
     那是每次构建重写的中间物，**不是存档**，别拿它当回滚底稿。
3. **APK 在 `.gitignore` 里**（Git 不保它的版本），本机回收站也不可靠（§2.4），
   所以**备份目录那份就是唯一的异地冗余**，别省这一步。
4. 回滚到旧版 = **降级安装**（versionCode 变低），Android 默认拒绝；
   真要退先把 `filesDir/study_progress.json` 等数据拷出来，再讨论 `-d` 强降的风险。

### 14.2 出 release 包（**先按 §14.1 把版本号 +1**，产物按版本号命名，不覆盖旧包，见 §14.1.1）

```powershell
cd "D:\AI project\lang-partner"
$env:JAVA_HOME = "D:\a\jbr"
.\gradlew.bat clean :app:assembleRelease :app:testDebugUnitTest --console=plain
# 注意：以上一步改出来的**新版本号**命名，不要覆盖任何旧 APK
Copy-Item app\build\outputs\apk\release\app-release.apk .\Shiyu-v<新的版本号>-release.apk
# 同步一份到备份目录做异地冗余
Copy-Item .\Shiyu-v<新的版本号>-release.apk "D:\AI project\项目备份\拾语\发布物\"
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
  - 无法再发布任何更新到 `com.shiyu.app`（Android 要求升级包签名一致）；
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
| 实时语音一进就退 | 未授权麦克风（`finish()`） | 系统设置里给「拾语」开麦克风权限 |
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

---

## 17. 本次改造记录（v1.0 → v2.0）

### 17.1 身份与结构

| 项 | v1.0 | v2.0 |
|---|---|---|
| 应用名 | 语言陪练 | **拾语** |
| 包名 / namespace | `com.langpartner.app` | **`com.shiyu.app`**（新包名 = 新应用身份，老包需卸载，数据不继承） |
| Kotlin 包路径 | `com/langpartner/app` | `com/shiyu/app`（整体迁移，含测试） |
| Gradle 模块名 | `LangPartner` | `Shiyu` |
| 主题 | `Theme.LangPartner` | `Theme.Shiyu` + `Theme.Shiyu.Voice`（深色沉浸页专用） |
| Keystore 别名（加密存储） | `langpartner_settings_key` | `shiyu_settings_key` |
| 版本 | `versionCode=2 / 1.0.0` | `versionCode=1 / 1.0.0`（重新起算） |
| 版本控制 | 无 | **已接入 Git**（三个检查点：baseline / 改包名 / 改造） |

> **release 签名的 keystore 没换**，仍是 `keystore/lang-partner-release.jks`（证书 DN 里仍写着
> `CN=LangPartner`，这只是证书主体名，不影响使用，也不建议改 —— 改了就等于换签名）。

### 17.2 信息架构（这是最大的一处改动）

v1.0 是 4 个 Tab：今日(卡片训练) / 图表 / 对话 / 我的。
v2.0 按设计稿改成 5 个 Tab + 中央凸起麦克风：

| Tab | Activity | 来源 |
|---|---|---|
| 今日 | `ui/home/HomeActivity` | **全新**：目标环 / 连续天数 / 续学卡 / 语音卡 / 两个快捷入口 |
| 课程 | `ui/langs/LanguagesActivity` | **全新**：语种切换，日·英可用，韩·西·法灰态 |
| 对话（中央麦克风） | `ui/chat/ChatListActivity` | 重做视觉：实时语音 hero + 场景练习列表 |
| 复习 | `ui/review/ReviewActivity` | 由 `ui/study/StudyActivity` 迁移改造，**判题/SM-2 逻辑一字未改** |
| 我的 | `ui/profile/ProfileActivity` | 重写：可改名 / 三项总览 / 周柱状图 / 6 枚成就 / 分语言数据 |

「发音表」（原图表 Tab）与「设置」降为二级页：发音表入口在复习页右上角与首页快捷卡，设置入口在我的页。

**设计稿里被砍掉的两项：** 「发音评分 92」与「听力训练」—— 现有数据层算不出发音分，
听力训练是一整个新功能模块。与其摆两个假数字，不如不做；要补时它们是独立的新需求。

**设计稿画了 5 个语种，实际只有 2 个。** 韩/西/法在课程页的「即将支持」分区里以灰态出现，
点击给明确提示，不是点了没反应、也不是切过去一片空白。补齐一门语言的最小改动集见 §5.1。

### 17.3 系统栏与 ActionBar 冲突（本次专门处理的问题）

设计稿每一屏都自带页头，系统 ActionBar 会和它打架。处理方式：

1. 主题 `NoActionBar`，状态栏与导航栏都设成**透明**，`windowLightStatusBar=true`
   （深色页 `Theme.Shiyu.Voice` 则为 false）；`values-v27/` 里额外加 `windowLightNavigationBar`。
2. `ui/BaseActivity` 里 `WindowCompat.setDecorFitsSystemWindows(window, false)` 开 edge-to-edge，
   再由 `fitTopInset(view)` / `fitBottomInset(view)` / `fitBottomInsetWithIme(view)`
   把系统栏高度以 padding 的形式还给**具体视图**（页头吃状态栏、底部导航吃导航栏、
   带输入框的页面吃键盘）。三个方法都会记住视图原有的 padding 再叠加，重复回调不会累积。
3. **删掉了 `activity_voice_chat.xml` 里写死的 `paddingTop="48dp"`** —— 那是 v1.0 用固定值
   硬躲状态栏的做法，换刘海高度不同的机器就会歪。

新增页面时照抄这三步即可；不要再用固定 padding 躲状态栏。

### 17.4 底部导航

Material 的 `BottomNavigationView` 做不出中央凸起的 FAB，改为手搭 `view_bottom_nav.xml`：
根 `FrameLayout` 里 `navRow`（62dp，顶部留 22dp 外边距）+ 56dp 麦克风（贴 top 居中），
于是麦克风正好比导航条高出 22dp。**全程没有负边距，不依赖任何一层 `clipChildren=false`**，
换机型不会被裁。接线统一在 `ui/nav/BottomNav.bind(activity, binding, currentTab)`，
加 Tab 只改这一个文件（v1.0 是在 5 个 Activity 里各抄一份 `when(itemId)`）。

### 17.5 新增的数据与逻辑

| 文件 | 作用 |
|---|---|
| `data/model/DayStat.kt` | 一天的四项：`studySeconds` / `voiceSeconds` / `reviewedCards` / `chatTurns` |
| `data/store/DailyStatsStore.kt` | `filesDir/daily_stats.json`（version 1）；`record` 累加落盘、`streak` 连续天数、`currentWeek` 周一起算的 7 天切片、`totalSeconds`。自愈与原子写规则与 `StudyStore` 一致 |
| `core/stats/Achievements.kt` | 6 枚成就的纯判定，给出 `current/target/percent`，未解锁也能画进度 |
| `AppSettings.nickname` | 昵称，后加字段，缺失即空串（老文件无需迁移）；上限 12 字符，留空 = 恢复默认称呼 |

**埋点位置（改动逻辑时别漏）：**

- `ReviewActivity.onPause` → `studySeconds`（前台停留秒数，<5 秒不计，单次封顶 30 分钟）
- `ReviewActivity.onRate` → `reviewedCards + 1`
- `ChatActivity.sendText` → `chatTurns + 1`（与 SessionStore 同一口径）
- `VoiceChatActivity.endSession / onDestroy` → `voiceSeconds` + 本轮 `turns`

> `streak` 的口径：今天有活动就从今天往前数；今天还没开始学则从昨天往前数
> （当天过完之前不判断断签）。两天都空返回 0。已由 `DailyStatsStoreTest` 锁死。

### 17.6 App 图标

自适应图标，`mipmap-anydpi-v26/ic_launcher{,_round}.xml` + 同名 `mipmap-anydpi/` 回退：

- 背景 `drawable/ic_launcher_background.xml`：暖纸到浅陶土的 315° 渐变；
- 前景 `drawable/ic_launcher_foreground.xml`：陶土对话泡 + 三段声波（高 15/27/19，中间提亮）；
- `drawable/ic_launcher_monochrome.xml`：Android 13+ 主题化图标的单色层。

所有图元落在 108 画布的 x 23–85 / y 24–84 之间（安全区是 18–90），圆形、方形、squircle
各种启动器裁切都切不到主形。改图标时务必守住这个范围。

### 17.7 本次修掉的四个真 bug

**① Material3 把 `<Button>` 换成 `MaterialButton`，它忽略 `android:background`（本次改造引入）。**
「显示 Key」「测试连接」「录音」三处是 `android:background=白底描边` + `textColor=陶土`，
实际渲染成陶土底 + 陶土字 = **一个没字的深色方块**。
现统一用 `ShiyuButton.Primary` / `.Outlined` / `.OnDark` 三个 Material 样式上色。
**以后不要再给 `<Button>` 写 `android:background`。**

**② API Key 输入框永远点不进去（本次改造引入）。**
`renderKey` 在掩码态无条件 `isFocusable=false`，Key 为空时也锁；
而 `saveGroup` 只在「显示」态才读输入框，即使能输入也会被丢弃。
现改为 `isMasked(group) = 不可见 && 已存 Key 非空`，只有真的在显示掩码时才锁 / 才保留原值。

**③ `MessageAdapter` 强转 LayoutParams 必崩 —— 这是 v1.0 就带着的 bug，不是本次改造引入。**

```kotlin
// 崩：RecyclerView 给 item 挂的是 RecyclerView.LayoutParams（继承 MarginLayoutParams），
// 和 LinearLayout.LayoutParams 没有继承关系，这一行 100% 抛 ClassCastException
val lp = holder.binding.root.layoutParams as LinearLayout.LayoutParams
```

**症状是「一进场景对话页，App 整个崩掉、任务栈被销毁、回到首页」** —— 看起来像"跳回首页"，
其实是进程死了。已用 `git show 5c09c06`（改造前基线）确认该行原样存在。

**为什么 321 个单测没抓到**：所有测试都是纯逻辑 + 假 Transport，**没有一条覆盖 ViewBinding 与
RecyclerView 的绑定**；而 v1.0 的真机冒烟（V22）从未执行过 —— 这正是 §0 那条风险的现实版。
**场景对话功能在 v1.0 的真机上从来就没能用过。**

修法是**把 gravity 设在气泡上，而不是 item 根节点上**，并且用安全转换兜底：

```kotlin
val lp = holder.binding.bubble.layoutParams as? LinearLayout.LayoutParams
if (lp != null) {
    lp.gravity = if (isUser) Gravity.END else Gravity.START
    holder.binding.bubble.layoutParams = lp
}
```

> 教训：**给 RecyclerView 的 item 设 gravity 要走 item 内部的子 View。**
> 对 item 根节点做 `LinearLayout.LayoutParams` 强转在任何情况下都不成立。

**④ 没开始会话就退出，会把「当前时间戳 ÷ 1000」当成通话秒数记账（约 55 年）**，
导致「初次开口」成就被误解锁。现加 `startElapsed != 0L` 守卫 + 单次 2 小时封顶。

另外修了语音准备层写死的「和 Yuki 聊聊天」与「ゆ」头像 —— 切到英语时仍显示日语语伴，
与首页对不上。现统一从 `ui/LangStyle.partnerName(lang)` 取（日语 **yoyo** / 英语 **Emma**）。

### 17.10 首页与复习页的口径收口（`DailyQuota`）

改造后两屏都显示「今日待复习 N」，但数据来源天然不同：首页每次进来都重新排卡
（未学过的卡会补满 20），复习页则是进页时排好的队列在递减 —— 于是出现
**「首页说 20、复习页说 19」** 的自相矛盾。

现收口到 `core/srs/DailyQuota`：

```
今日还该复习多少张 = min(当前可排卡数, 20 - 今天已复习张数)
```

首页传 `selectToday` 的结果数，复习页传队列剩余数，两边调同一个纯函数，
重启 App 也算得出同一个数。`DailyQuotaTest` 7 例锁死口径，
其中一条专门断言「同一时刻两屏必须相等」。

### 17.8 设置页的两处体验改动

- **顶部加了「配置语言」芯片**：8 组配置是「4 能力 × 2 语言」，语言切换条移到课程 Tab 后，
  用户在设置页看到「设置 · 日语」却切不了，体验是断的。这里切的就是全局 `activeLang`。
- **加了「把对话组的地址和 Key 同步到其它」**：4 组能力通常共用同一个网关。
  刻意的两条边界 —— **实时语音只同步 Key 不同步地址**（它走 wss，路径形态完全不同），
  **model / voice / enabled 一律不动**（模型名本来就各不相同，开关该由用户决定）。

### 17.9 这次没做 / 仍然欠着

- ~~「今日目标」写死成常量~~ → 2026-09-21 已做成可配置：
  我的页 →「偏好与设置」→「每日目标」，区间 5–180 分钟，填 0 或留空回默认 30；
  存在 `AppSettings.dailyGoalMinutes`，首页读它画进度环。
- 实时语音的四个老问题原样保留：**无端上 AEC、断线不自动重连、切后台即中断、实时会话不落盘**（详见 §12.3、§13.3）；
- ~~`ui-preview.html`~~ 已于 2026-09-21 删除（v1.0 旧预览，与现 UI 对不上）；
  当前设计稿是桌面上的 `shiyu-ui-prototype.html`。
- §13.1 里有 3 条**需要真实 API 凭据**才能验（测试连接 / stepfun realtime 实际握手 /
  回合制录音链路），本次没有凭据，未验。

---

### 17.11 三个由真机走查抓出来的 bug（2026-09-21 第二轮）

**① 「返回首页」把用户困在语音页。**
`VoiceChatActivity` 的总结层里 `btnExit` 接了 `endSession()` —— 而这一轮早就结算过了，
再调一次只会重算时长、把总结层重新弹一遍，按钮看起来完全没反应。
改成直接回「今日」页（只 `finish()` 会退到「对话」Tab，和按钮写着的"首页"对不上，
两个坑都踩过）：

```kotlin
binding.btnExit.setOnClickListener {
    startActivity(Intent(this, HomeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    finish()
}
```

**② 朗读用错了语言 —— `TextToSpeech` 初始化是异步的。**

`Speaker` 原先在构造后立刻 `tts?.language = locale`。但 `TextToSpeech(context, listener)`
返回时引擎还没连上，这次赋值会在引擎初始化时被覆盖掉。调用方 invariably 是
`Speaker(this)` 紧跟 `setLanguage(...)`（都在 `onCreate` 里），所以**语言设置从来没生效过**。

表现：手机上系统默认是中文，于是**用中文音色去念英文和日文句子**。日志实锤 ——
修复前 `Synthesis request for locale zho-CHN`，修复后 `eng-USA` / `jpn-JPN`。

修法：把 locale 缓存起来，等 `onInit` 回调里再 `applyLanguage`；同时把
`LANG_MISSING_DATA` / `LANG_NOT_SUPPORTED` 通过 `onLanguageUnavailable` 暴露给 UI
（设备没装语音包时不再静默失败）。

**③ 「今日待复习」跨语言串号。**
`DailyQuota` 用的是 `DayStat.reviewedCards`（**当天全部语言**的复习总数），
于是**在英语里评一张卡，日语的待复习也跟着少一张** —— 正是 V19 要求不能发生的串号，
只不过发生在显示层而不是数据层。

修法：`DayStat` 增加 `reviewedByLang`（tag → 张数），`record()` 增加 `langTag` 参数成对写入；
`reviewedCards` 仍然保留为"当天活动总量"（首页活动小结用），两者用途不同，不是冗余。
首页与复习页改取 `reviewedIn(lang.tag)`。

> `DailyStatsStoreTest` 里专门有一条 `reviewing_one_language_does_not_touch_the_other`
> 锁这个行为，别改回去。

**顺带**：目标环只有分钟粒度，学了不到 1 分钟会显示「0 分钟」，而老文案是
「今天还没开始，先来 5 分钟」—— 用户会以为记录丢了。现在这种情形改说
「已经开始了，满 1 分钟就会记上」（`home_goal_just_started`）。

### 17.12 对照官方文档修正实时语音接入（2026-09-21）

对照 <https://platform.stepfun.com/docs/zh/api-reference/realtime/chat> 逐项核对了
`RealtimeProtocol` 与 `StepfunRealtimeProvider`，改了三处、留了一个待验证项。

**① `model` 是必填查询参数，之前完全没传（会导致握手失败）。**

官方把 `model` 列为**必填**，取值 `step-audio-2-mini` / `step-audio-2` /
`stepaudio-2.5-realtime` / `step-1o-audio` / `step-audio-r1.5` 等。
但设置页把它存在了 `model` 字段里、不在 baseUrl 上，而 Provider 直接拿 `config.baseUrl`
去握手 —— **`config.model` 从头到尾没被用过**。现在会自动拼：

```
wss://api.stepfun.com/v1/realtime?model=step-audio-2-mini
```

见 `RealtimeProtocol.endpointWithModel`（已经有 `model=` 的 URL 不重复追加）。

**② `step-audio-2-mini` 的音色白名单 + 必须在 instructions 末尾追加性别提示。**

官方原文：声线只有 `qingchunshaonv`（青春少女）与 `wenrounansheng`（**温柔男声**）两个，
并且「需要在 instructions 结尾处对应添加"请使用默认男声与用户交流"或"请使用默认女声与用户交流"」。

⚠️ **默认音色改了**：原先默认 `wenrounansheng`，但那是**男声**，而 App 的语伴形象是
yoyo / Emma。现在默认 `qingchunshaonv`（女声），并在 systemPrompt 末尾自动追加
「请使用默认女声与用户交流」。要男声就把设置页的音色填 `wenrounansheng`，
App 会自动换成对应的男声提示句。

> 这两句提示词是官方给的固定措辞，**别自己改写或翻译**。

**③ 服务端新增事件已确认可安全忽略。**

官方新增了 `response.thinking.delta/done`、`conversation.item.created`、
`input_audio_buffer.committed`、`response.output_item.*`、`response.content_part.*`。
`RealtimeProtocol.parse` 的兜底分支把它们归为 `Unknown` 并忽略，无需改动。

**④ 待验证：采样率与声道数。**

官方文档**只写了 `pcm16`，没有给采样率、声道数、每帧字节数**。而 App 在
`RealtimeAudioController` 里写死 **16000 Hz / 单声道 / 20ms 一帧（640 字节）**。
如果服务端实际是 24 kHz，上下行都会失真（声音变调、语速不对）。

**验证方法**：跑一遍官方的开源 demo
[Step-Realtime-Console](https://github.com/stepfun-ai/Step-Realtime-Console)
看它的 AudioContext 采样率，或直接抓包看上行帧长。**在真机听到 AI 语音之前，
不要认为实时语音"已经通了"。**

### 17.13 实时语音打通 + 一轮全工程自检（2026-09-21 下午）

#### A. 实时语音从"从来没跑通"到"能用"

这一节记录的是**真机 + 官方文档 + 日志**三方对照的排查过程。四条 bug 里有三条是
**静默失败**（不报错、不回应），只能靠加日志才能定位。

| # | 问题 | 根因 | 表现 |
|---|---|---|---|
| ① | `model` 从没拼进 URL | 官方列为**必填查询参数**，但设置页把它存在字段里、不在 baseUrl 上，而 Provider 直接拿 baseUrl 去握手 | 握手被拒 |
| ② | **服务端永远检测不到人声** | 采样率写死 **16kHz**，官方要的是 **24kHz**。注意别被模型结构误导：Step-Audio 的 S3Tokenizer 确实在 16k 工作，但那是**模型内部**；送进 `input_audio_buffer.append` 的必须 24k | 连上了、麦克风在采、**不报错也不回应** |
| ③ | **服务端 VAD 阈值过高** | 官方默认 `energy_awakeness_threshold=2500` 对手机麦克风偏高。实测本机说话时电平 -45~-21 dBFS，换算幅度约 184~2900，而 VAD 判的是短时均方根（多数帧只有几百），**永远够不到 2500** | 同上 —— 最费时间的一个 |
| ④ | 每句话开头先念一声「JA」、且不肯说日语 | 语音模式直接复用了文字模式的三段式提示词（`[JA]/[ZH]/[FIX]`）；文字侧靠 `ReplyParser` 拆标签，语音侧模型**把标签也念出来**，中文翻译和纠错也一并朗读 | 开口就是「T A」 |

**修法：**
- ②③ 见代码注释（`RealtimeAudioController.SAMPLE_RATE`、`RealtimeOptions.energyAwakenessThreshold`），
  两处都写了「为什么不是官方默认值」。
- ④ 新增 `core/lang/VoicePrompts.kt`，语音模式用专用提示词：只讲怎么说话，不出现任何格式标记。
  **不是**简单删掉格式要求 —— 还显式写了「用户明确要求中文时就照做，此条优先于默认语言」，
  否则模型会在"绝对规则"和"用户要求"之间卡死，反复反问「可以吗」（实际发生过）。

**这轮排查的副产品**：整个实时语音链路原来**一行日志都没有**，出问题只能猜。现在补了
连接 URL、服务端事件类型、`session.updated` 回显（用来确认 VAD 参数是否被接受）、
麦克风响/静翻转、断线与重连。跑通后又把每秒一条的噪音收敛成事件驱动。

#### B. 全工程自检（发现的与本轮的修复）

**性能**
- `AssetRepository` **完全没有缓存**：10 处调用点每次进页面都重读 assets 并解析 JSON
  （首页每次 onResume、课程页每个语种）。已加缓存，六份资产共约 55KB。
- `HistoryActivity` 在 `onBindViewHolder` 里逐行解析场景 JSON（本轮新写时埋的），改为预查表。

**录音链路（此前从未真机跑过，本轮做静态审查）**
- `AudioVadTrimmer` 用 `ArrayList<Short>` 累积解码数据，**每个采样装箱一次**：
  60 秒录音近 300 万个临时对象（几十 MB 垃圾），会 GC 卡顿甚至 OOM。改为可增长的基本类型数组。
- 取解码数据时**忽略 `info.offset`**，`asShortBuffer()` 直接从 0 读。多数设备 offset 恰好是 0，所以看不出来。
- 「未获得麦克风权限」被复用在 **4 种失败**上，其中"录音器启动失败"（多为麦克风被占用）、
  "录音文件为空"（多为录得太短）跟权限无关 —— 用户照着改权限永远修不好。拆成三条独立文案。
  > 这与 ① 里的「响应格式异常」是同一类毛病：**笼统的错误文案会把排查引到错误方向**。

**其他**
- 语音准备层的「和 Yuki 聊聊天」「ゆ」头像写死在 XML 里 → 改为跟随 `LangStyle`
  （同类坑本轮又犯了一次：写死的「AI 语伴 · 日语」副标题）。
- 设置页四个卡片标题、语音页准备层副标题硬编码 → 收进 `strings.xml`。
  注意卡片标题（"OpenAI 兼容"）与上方分区标题（"对话服务"）是**两个不同的字符串**，别合并。
- `String.format` 补 `Locale`；`AppGraph` 的 `StaticFieldLeak` **核实后是误报**
  （只存 `applicationContext`），已标注说明而不是留着让人反复怀疑。

#### C. 本轮新增的、尚未真机验证的功能

**这些代码都编译通过、单测全绿，但一次都没在真机上跑过** —— 和 §13.1 的其它待验项一起走一遍：

1. barge-in 打断修复（`cancelResponse` + 打断丢弃窗口）
2. 实时语音会话落盘 + 「历史对话」列表/详情页
3. 实时语音中文翻译开关（默认关）
4. 端上 AEC / 降噪的实际效果（免提外放是否还有回环）
5. 断线重连（可开飞行模式再关来制造断线）
6. 切后台暂停采集、回前台恢复
7. 录音链路的空文件/占用等失败文案
8. `AssetRepository` 缓存后的行为（应无变化，只是更快）

> 本轮改动集中在平台耦合层（MediaCodec / audiofx / OkHttp / Activity 生命周期），
> **没有新增单元测试** —— 这几层本来就不在 JVM 单测的覆盖范围内（这正是它们能藏住
> 那么多 bug 的原因）。验证只能靠真机。

#### D. 真机验证（2026-09-21 傍晚）—— 又抓出 4 个问题

**已验证通过：**

| 项 | 结果 |
|---|---|
| 历史对话列表 | ✅ 徽章（场景对话/实时语音）、场景名（由 scenarioId 按语言查回）、相对时间、轮数、首句预览都正确 |
| 历史对话详情 | ✅ 气泡左右分布正确，内容完整 |
| 断线重连 | ✅ 实测退避时序 **1s → 2s → 4s** 三次后放弃；DNS 失败（飞行模式）被正确识别；认证类错误不重试 |
| 语言隔离 | ✅ 配置只配在日语组，切到英语后实时语音正确降级为本地模拟（Mock 字幕）|

**这轮抓出的 4 个问题（均已修）：**

1. **重连期间界面弹的是「网络连接失败」而不是「重连失败」。**
   我加 `scheduleReconnect()` 时，**忘了删掉原来那段 `listener?.onError(...)`** ——
   于是同一次失败既触发重试、又立刻报错，两条提示互相打架。用户看到的是「网络连接失败，
   请检查网络后重试」，**根本不知道 App 已经自动重试过三次并放弃了**。
   现在重连窗口内一律不报错，彻底失败时由 `onReconnectFailed` 统一提示。

2. **放弃重连后麦克风还在一直采。** 日志实锤：16:47:04 放弃重连，16:47:18 麦克风仍 `active=true`。
   `sendAudio` 会因为 `connected=false` 而不发（不浪费流量），但**麦克风本身是热的** ——
   费电，而且用户会觉得"我都断了你怎么还在录音"。现在失败即停采集。

3. **历史详情页显示原始的 `[EN]/[ZH]/[FIX]` 标签。** 回合制对话存的是模型原文，
   当时在对话页是拆开渲染的；历史页原样吐出来，同一个对话两处长得不一样、而且历史这边更难读。
   现在详情页也走 `ReplyParser` 再渲染（实时语音的发言没有标签，解析后即原文，不会误伤）。

4. **能量阈值 200 太低，环境噪音会把 AI 喊醒。** 实测：全程没人说话，界面却出现了
   「AI：我是小美，来自中国。／我：啊你啊啊要要。」—— 后半句是把环境噪音识别的乱码。
   根因是 200 压在环境底噪之上（实测底噪就在 -45 dBFS 上下浮动）。
   **默认值改为 500**，并写进注释：底噪 -45dBFS ≈ 184，正常说话 -34~-21dBFS ≈ 650~2900，
   500 卡在中间。

   顺带把换算关系写进了 `RealtimeOptions` —— 这个值调起来像玄学，有数字才好判断往哪调。

**另外两个小修：**
- 字符串资源里两处 `\n` 被工具折成了真换行，Android 把资源内空白折叠成空格，
  于是「还没有对话记录 / 去「对话」里挑个场景开口吧」显示成了一行。
- 历史列表里出现了「0 轮」的空会话（模型开场白之后一次都没接话，多为此前版本崩溃时留下的）。
  现在只列有用户发言的会话 —— 空会话留在磁盘上没问题，但不该在历史里冒充一条记录。

**还没验（需要真人开口）：**
barge-in 打断、中文字幕开关、切后台恢复、免提外放 AEC 效果。

### 17.15 深度自检（2026-09-21 晚）—— 四路并行审计

对音频 / 数据 / 网络 / UI 四个子系统各派一路独立审计，逐条核实后统一修复。
**这一轮没有新功能，全是健壮性与正确性**，其中三类问题会静默丢数据或直接崩溃。

#### A. 数据安全（最严重的一类）

**A1. 「原子写」其实不原子 —— 崩溃窗口会静默清空全部进度。**
三个明文 store 与加密 store 的落盘都写成：

```kotlin
tmp.writeText(text)
if (target.exists()) target.delete()   // ← 这一步是多余的，而且有害
if (!tmp.renameTo(target)) { ... }
```

`rename(2)` 本身就是原子替换，先 `delete` 只是**打开了一个"目标文件不存在"的窗口**。
进程恰好在 `delete` 之后、`rename` 之前被杀（低内存 kill / 断电），下次启动就找不到文件，
`load()` 走「文件不存在 → 返回空结构」分支，**用户全部进度静默清零**。
更糟的是：`tmp` 里明明躺着完整数据，**没有任何一处会去捡它**。

现在统一走 `data/store/AtomicFile.kt`：写 tmp → `rename` 直接覆盖（不删），
并且每次 `load()` 之前先 `recoverIfNeeded()` 把上次写一半的 tmp 认领回来。

> **加密存储原先是最不设防的一处**：它直接 `file.writeBytes(out)` 截断整写，
> 连 tmp 都没有 —— 而它存的是全部 API 配置。现在同样走 AtomicFile（字节版）。

**A2. 并发写互相踩。** 三个 store 的 `record()` / `appendTurn()` 都是
`load → 改 → save` 的无锁序列，而且 tmp 文件名固定。两个线程同时写会交错出坏 JSON →
下次 load 解析失败 → 隔离 → **整份统计历史清零**。现在全部 `@Synchronized`。

**A3. Keystore 自愈是死代码。** `KeystoreSecretStore` 有个「连续失败 3 次就重建」的计数器，
但它同时具备两个致命前提缺失：

- 计数器是**内存字段**，每次冷启动归零；
- `SettingsRepository` 在进程内缓存了读取结果，`read()` 一个进程生命周期**只会被调用一次**。

所以计数器**永远到不了 3**。密钥真的失效时（应用数据被克隆/恢复到新机、系统安全更新
丢了 Keystore 条目），每次读都失败、界面显示"配置凭空消失"，而注释承诺的自愈从不发生。

现在改成**解密失败即隔离并重建密钥**：解不开的密文在密码学上已不可恢复，
留着它只会让人永远停在"读不出来"的状态。隔离（而不是删除）保留原始密文供排查。

**A4. `SettingsRepository.cached` 无同步。** `update()` / 每个 `set*()` 都是
`load → copy → save`，并发写会整体 last-writer-wins —— **一次写把另一次的改动悄悄抹掉**。
加 `@Volatile` + `@Synchronized`。

#### B. 崩溃路径

| 位置 | 问题 |
|---|---|
| `RealtimeAudioController.stopCapture` | 不 join 采集线程就 `release()`，采集线程在已释放对象上再 `read` 抛 `IllegalStateException` —— **必须** 先停标志 → `join(500ms)` → 再释放；`read` 加 try/catch；`recorder` 加 `@Volatile` |
| `VoiceChatActivity` 回调 | 字幕缓冲 / 轮次记录一半在主线程、一半在 WebSocket 回调线程读写 → `ConcurrentModificationException`。回调体统一收口到主线程 |
| `SettingsActivity.probeRealtime` | 协程取消时不关 provider → WebSocket 与自建 OkHttpClient **永久泄漏**；`var done` 的检查-再赋值是 TOCTOU，WS 线程与看门狗线程可能双双 `resume` 抛 `Already resumed` |
| `ChatActivity.playAudio` | `MediaPlayer` 的构造/设源/`prepare` 都在 try 之外，服务端返回坏音频直接崩 |
| `ProfileActivity` | 成就 META 用 `getValue`，漏登记一个就整页崩；周柱状图用日期差当数组下标会越界 |
| `OkHttpTransport` | 全量 `body.bytes()` 无上限，服务端返几百 MB 就 OOM（TTS 音频体没有天然上限）。加 32MB 上限按坏响应处理 |

#### C. 行为正确性

- **无限重连**：`retryCount` 在 `onOpen` 清零，但**握手成功 ≠ 会话可用**。
  服务端接受握手后立刻踢连接，就会 `onOpen→onClosed→重连→onOpen…` 无限循环，
  `MAX_RETRIES` 形同虚设、界面永远卡在"重连中"。改为收到 `session.created` 才清零；
  重连前先 `cancel()` 旧 socket（其 listener 的延迟回调会重复触发重连）。
- **「测试连接」的成功判定**：原先在 WebSocket 握手就报"成功"，**模型名非法也照样通过**。
  新增 `onReady()`（`session.created`）作为真正的可用信号。
- **VAD 裁剪用错采样率**：decoder 已经返回实际采样率，`trim` 却用默认 16k。
  非 16k 设备上分帧与 padding 的毫秒换算全错 —— 头尾裁错位置、时长虚报约 3 倍。
  而这个模块存在的意义正是准确掐掉静音省计费。
- **播放持锁阻塞打断**：`track.write` 缓冲满时会阻塞，而 `interrupt()` 需要同一把锁 ——
  表现就是「按了打断要等一下才停」。锁只保护字段，`write` 挪到锁外。
- **「对话 N 轮」两套真相**：首页用 `DayStat.chatTurns`（只增不减），
  我的页从会话文件实时重算 —— **删一条会话后首页的数字会永久高于我的页**。
  统一从会话文件派生（新增 `SessionStore.userTurnsOn`）。
- **旋屏丢状态**：对话页 / 复习页 / 设置页持有会话与表单状态却无 `configChanges`，
  旋屏即重建；对话页还会每转一次屏就重生一条只有开场白的孤儿会话。已加 `configChanges`。
  （这条同时修掉了 §12.1 里"配置变更丢当前卡片状态"那个已知取舍。）
- **后台占用麦克风**：对话页只在 `onDestroy` 释放录音与外放，按 Home 后 `MediaRecorder`
  仍开着麦克风。加 `onStop` 释放（录音中被切走则先把这段发出去再释放）。

#### D. 性能与清理

- 评分与发消息的**落盘从主线程挪到 IO**（store 已 `@Synchronized`，后台写是安全的）。
- 删 24 条死字符串、12 条旧色板颜色、1 个无引用 drawable。
- 语音页波形柱改用深色专用 drawable —— 这是**「浅色页资源用在深色页」的第五处**。
- 修掉 `OkHttpTransport` 里一条恒真判断的编译警告（`toMultimap` 的 key 非空）。

#### E. 审计确认「没有问题」的部分

供接手人参考，以下路径经核查对空串 / 畸形 / 缺字段输入均能优雅降级，无 `!!`、无未捕获 JSON 异常：
`BaseUrlNormalizer`、`ReplyParser`、`EnSentenceSplitter`、`EnNormalizer`、`ScenarioParser`、
各 `buildDeck/buildChart`、`HttpErrors` 分类顺序、`LogRedactor`、`VadPolicy`（纯逻辑部分）。
`Speaker` / `MiniWaveView` / `lifecycleScope` 的使用也未见泄漏。

#### F. 测试

`AtomicFileTest` 新增 11 例，锁住这轮最重要的修复：原子覆盖不留 tmp、
残留 tmp 能被认领、目标存在时不认领、空 tmp 不认领、隔离保留原始内容。
**376 → 387 例全绿**，lint 0 error。

### 17.16 日语朗读改走云端 TTS，修复国内移动网络(5G)下无声（2026-09-23）

**现象**：日语单词/例句/五十音朗读，连公司 WiFi（能上外网）正常，连手机 5G 就没声；
英语在两种网络下都正常。实时对话（WebSocket）不受影响。

**根因**（链路排查，逐条坐实）：
- 单词/例句/发音表朗读走的是**系统 TTS**（`ui/Speaker`，Android `TextToSpeech`）。
- 魅族 22 默认引擎是 Google 文字转语音，但**没装离线日语语音包**，于是它对日语走
  **联网合成**——合成请求要发到 Google 服务器。公司网能到、5G 到不了，就没声。
  英语离线包设备自带，走本地合成，所以英语两种网络都好。
- 更坑的是：`TextToSpeech.setLanguage(JAPANESE)` 对这种「联网才可用」的语言仍返回
  `LANG_AVAILABLE`，**不触发** `Speaker.onLanguageUnavailable`，所以连提示都没有，纯静默。
- 离线包想装也装不了：Google TTS 的语音包下载走 GMS，魅族阉割版 GMS 在「正在下载…」
  永久卡死（实测 GMS 侧 0 字节流量）；设备无 root，adb 也塞不进 `/data/data/com.google.android.tts`。

**为什么不修系统 TTS 而是绕开它**：多引擎自动切换在真机上不可靠（见 §17 之前 Speaker 的
两次回退：commit `7fad5d3`/`c82bd71`）。所以不动 `Speaker` 引擎逻辑，另开一条云端路径。

**改动**：
1. **新增 `ui/WordTtsPlayer`**：日语走云端 TTS（stepfun `/audio/speech` 返回 MP3 →
   `MediaPlayer` 播放），彻底绕开 Google；云端未配置或失败时**回落系统 `Speaker`，绝不静音**。
   英语仍走系统离线 TTS，不耗 API。方法名 `releasePlayer()` 刻意避开 `MediaPlayer.release()`
   （否则 `apply{}` 块里的监听器会解析成 MediaPlayer 自己的 release）。
2. **三处日语朗读接入**：`ReviewActivity`（单词/例句两键）、`ChartActivity`（发音表格子）、
   `ChatActivity`（点历史消息重播；保留录音期防回环 `if (!recording)`）。
   对话页 AI 回复的自动朗读 `maybeAutoPlay` 本就走 `providerRegistry...tts()`，配好 tts 组后一并真的能响了。
3. **补齐缺失的 tts 组**：`assets/preset_config.json` 此前**只有 chat/realtime、没有 tts**，
   所以 `providerRegistry` 一直返回 `MockTtsProvider`（云 TTS 从没真配过）。补上：
   `baseUrl=https://api.stepfun.com/step_plan/v1`、`model=stepaudio-2.5-tts`、
   `voice=jilingshaonv`（**voice 必填**，缺了服务端回 400 `voice is required`）。
4. **版本门控迁移 `PresetConfig.applyPresetMigrations`**：`seedIfEmpty` 只在「本地一份配置都没有」
   时才写，已装机器早 seed 过 chat/realtime，光靠它新增的 tts 组永远进不去。新增按
   `PRESET_VERSION`（现为 2）+ `filesDir/preset_seed.version` 门控：本地版本落后就把预置里
   定义的组**强制同步**（推修正配置），版本相同只补**本地空着**的组、不覆盖用户已填的。
   **以后改了 `preset_config.json` 任何组的值，就把 `PRESET_VERSION` +1**，老机器下次启动自动同步。
   标记文件在 `filesDir`（与加密的设置文件、学习进度分开），读写不影响进度。

**验证**：真机魅族 22，**关 WiFi 走移动数据(5G)**，复习页日语词「あざ笑う」、发音表五十音
首格分别点朗读 → logcat 明确显示本 App(uid 10324) 经云 TTS 合成并外放约 2.4~2.6s 音频
（`AudioFlinger getActiveTrackUid=10324`、`NuPlayer audioShutdown`、Flyme `AppUsage ... audio com.shiyu.app`）。
单测全绿；release 覆盖安装（同 keystore）不丢学习进度。commit `4b660e1`。

> 附录 A 常量表里「TTS 默认音色 alloy」是 `OpenAiWire` 的兜底值；实际生效音色由预置的
> tts 组给到 `jilingshaonv`。若日语朗读又没声，先查设置页「语音合成」组是否 baseUrl/voice 齐全、
> 点「测试连接」；再确认 5G 能到 `api.stepfun.com`。

### 17.17 朗读延迟优化路线（规划，2026-09-23；Tier 1 已于 §17.18 落地）

**驱动**：云端 TTS 每次朗读要等**约 1.4–1.7s**（实测 stepfun `stepaudio-2.5-tts`：单假名 1.39s、
短词 1.52s、整句 1.69s）。关键是**首字节 ≈ 总耗时**——服务端是**非流式**，把整段音频合成完
才返回第一个字节，所以单个假名也得等 ~1.4s，这是硬地板；手机 5G 上叠加网络抖动，体感「好几秒」。
**这是「能不能做离线」诉求的真实原因**，本质是延迟不是没声。按「先便宜后彻底」分三档：

**Tier 1 —— 低成本高回报，不去云依赖（建议先做）**
- **持久化缓存**：现在 `WordTtsPlayer` 的临时文件按内容命名、播完即删，**无复用**。改成按
  `(text + voice + model)` 哈希落 `cacheDir` 持久缓存，命中直接播。复习/发音表**大量重复同一批词**，
  重复朗读→秒开，且省 API。加个总量上限做 LRU 清理即可。
- **预取**：复习页 `showNext()` 拿到卡时，后台预合成当前卡的**单词 + 例句**，等用户揭示/点朗读时
  音频已就绪，等待≈0。发音表五十音就几十格，可**整表一次性预取**缓存。
- **流式 / 更快模型**：确认 stepfun 是否提供流式 TTS 或更快的小模型；非流式下 TTFB= 全合成时间，
  流式能显著降「首声」延迟。
- 预计工作量：半天到一天，直接消灭「重复朗读」和「可预测朗读」的等待，几乎零成本。

**Tier 2 —— 首启一次联网，之后离线（中等）**
- 首次启动从**自有服务器**（不是 Google，所以不会像 §17.16 那样被 GMS 卡死）下载离线模型，落盘后离线用。
- 解决体积问题（模型不塞进 APK），但仍要一次可控的联网。

**Tier 3 —— 端到端离线（终态，彻底去云依赖 + 零 API 费）**
- **栈**：`sherpa-onnx`（k2-fsa，Apache-2.0，有官方 Android AAR/JNI 绑定和示例 App）
  + 日语 **VITS** 或 **Kokoro**（多语含日语，82M，质量/体积比好）模型
  + **OpenJTalk / pyopenjtalk（+MeCab 词典）** 做日语 G2P（读音前端，日语跳不过这步，词典几 MB）。
- 端侧延迟 ~100–300ms（暖加载后；首次加载慢一档），无网无费，5G/无网都响。
- **代价**：APK **+几十 MB**（模型 + 每 ABI 的 `.so`；可按 ABI 拆包或走 Tier 2 首启下载缓解）；
  集成量不小（NDK/AAR、模型打包或下载、G2P 接入、按 ABI 出包、还得在爱掉线的魅族真机调）。
- 云端 TTS 保留作**兜底**（模型未就绪 / 首启未下完时）——正好是现在 `WordTtsPlayer` 的回落逻辑。
- ⚠️ 别走系统 `TextToSpeech` 多引擎自动切换那条老路（真机不可靠，已两次回退，见 §17.16 与 commit
  `7fad5d3`/`c82bd71`）；离线要走**自带推理引擎（sherpa-onnx）**，不依赖系统 TTS 引擎。

**建议节奏**：先做 **Tier 1**（尤其缓存 + 预取，直接对症用户的延迟痛点，成本极低），跑一段看体感；
若仍要彻底离线，再做 **Tier 3 spike**——拿 sherpa-onnx 官方 Android demo 跑通一个日语模型，
实测**真机首声延迟 / 暖延迟 / APK 体积增量**，用真实数字决定值不值得，再正式排期。

### 17.18 朗读缓存 + 预取落地（Tier 1，2026-09-23）

§17.17 的 Tier 1 已实现：**缓存命中秒开、预取让等待≈0、重复朗读零 API 消耗**。

**新增 `data/store/TtsDiskCache.kt`**（纯 JVM 可单测）：
- 键 = `sha256(model|voice|text)`——换模型/音色/文本任一项都重新合成，不会串音色；
- 文件落 `cacheDir/tts_cache/<key>.mp3`，**上限 64MB，超限按 lastModified LRU 淘汰**；
  `get` 命中即 touch（刚用过的先活）；原子写（tmp→rename），空字节不落盘、0 字节文件不算命中；
- 缓存属于 cacheDir，「清空全部数据」不动它（系统磁盘紧张时自行清理）。

**`WordTtsPlayer` 改造**：
- 播放路径统一为「查缓存 → 未命中合成 → 落缓存 → 播本地文件」；**失败不缓存**（暂时性网络错误下次再试）；
- **in-flight 去重**（`ConcurrentHashMap<String, CompletableFuture>`）：预取进行中用户就点了喇叭，
  同一 key 只发一次网络请求，后来者等首个结果——没有这层，预取反而把并发请求翻倍；
  协程取消时 future 兜底 complete(null)，等它的后来者不会永久挂起；
- 新增 `prefetch(scope, lang, text)`（后台合成落缓存不播放）与
  `prefetchAll(scope, lang, texts)`（发音表整表**顺序**预热——几十条并发容易撞 429）；
- 旧的「播完即删临时文件」逻辑删除，文件由缓存统一管理。

**接入点**：
- `ReviewActivity.showNext()`：拿到卡即后台预取当前卡 `speakText` + `example`；
- `ChartActivity.reload()`：建表后整表 `prefetchAll`（五十音 46 格）；
- `ChatActivity` 点句重播无需改动——走同一 `speakWord` 入口，自动享缓存。

**测试**：新增 `TtsDiskCacheTest` 11 例（命中/空字节/覆写/key 三要素/LRU 淘汰/touch 存活/tmp 不残留），
**388 → 399 例全绿**，`lintDebug` 0 error，`clean assembleRelease` 通过。

**日志口径（排查朗读问题的唯一抓手，release 包也能看）**：`adb logcat -s ShiyuTts`

| 前缀 | 含义 |
|---|---|
| `HIT <key8> 0ms '文本'` | 缓存命中，0ms 直接播 → 应秒响 |
| `MISS <key8> 1400ms 52000B '文本'` | 首次合成（耗时+音频大小），之后这条转 HIT |
| `WAIT <key8> '文本'` | 已有同 key 请求在飞（预取中点了喇叭），**不重复发请求** |
| `FAIL <key8> '文本'` | 合成失败 → 回落系统 TTS |

> 判据：**同一段文本全程只有一条 `MISS`**。出现两条说明去重失效。

**预取并发闸门**：`Semaphore(2)` —— 快速翻卡时每张卡各起一个协程，不设上限会同时打出十几个
请求（撞 429、也拖慢用户真正在等的那个）。点播（`play`）**不受闸门限制**，用户按下喇叭的等待优先。

**版本**：`versionCode 1 → 2`、`versionName 1.0.0 → 1.0.1`（§14.1 要求发版必 +1），
同 keystore 签名，覆盖安装不丢数据。产物 `Shiyu-v1.0.1-release.apk`。

**真机验证清单**：见 `TEST-朗读缓存-真机验收清单.md`（15 项 + 排查表 + 通过标准）。

### 17.19 真机实测结果（魅族 22，2026-09-23，PID 14504）

数据源：`adb logcat -s ShiyuTts`（日志口径见 §17.18）。**全部通过**：

| 项 | 实测 | 判定 |
|---|---|---|
| 复习页首次朗读 | 9 条 `MISS`：1258 / 1472 / 1483 / 1545 / 1593 / 1617 / 1627 / 1982 / **2415** ms（均值 ≈1.67s） | ✅ 与 §17.17 的 1.4–1.7s 吻合；**耗时与文本长度无关**（`'ああ'` 两字反而最慢），再次印证服务端非流式 |
| 同一张卡第二次点 | `HIT … 0ms`，连点 5 次全 0ms | ✅ 秒开 |
| **去重（最关键）** | **54 个唯一 key / 54 条 MISS，每个 key 恰好一次**；`WAIT` 总在 `MISS` 之前 | ✅ 预取与点播并发只发一次请求 |
| 发音表整表预热 | 46 格：16:26:00.738 → 16:26:54.128 = **53.4s**（≈1.16s/条，顺序推进） | ✅ 期间点格子：已热的 `HIT`、未轮到的 `WAIT`，无重复请求 |
| 缓存跨进程 | PID 31098 → 14504 后首条即 `HIT` | ✅ 缓存不死于进程重启 |
| 断网（飞行模式） | 10 条 `FAIL`，**2–9ms 秒失败**（不是等超时），无崩无 ANR | ✅ 失败不缓存 |
| 恢复网络 | `MISS 2029ms` → `HIT 0ms` | ✅ 坏结果没污染缓存 |
| 崩溃 | `*:F` 级全程无记录 | ✅ |

> ⚠️ 发音表预热实测 **53s**，比 §17.18 里估的 40s 慢一档 —— 顺序预热 + 每条约 1.16s 的固有成本，
> 想更快只能提高并发（会撞限流）或换更快的模型。

### 17.20 补「需要联网」提示（2026-09-23，v1.0.2）

**问题**：断网时云端合成秒失败（`FAIL 2–9ms`）→ 回落系统 TTS → 而本机日语音色是**联网语音**，
引擎同样出不了声，于是**点了完全没反应**（真机实测确认：飞行模式下点喇叭，无声、无提示）。
这正是 §17.16 记录的那类静默失败，只是换了个入口。

**改动**：
1. `ui/Speaker.kt`：挂 `UtteranceProgressListener`，记录 `onError` 与 `onStart`；
   `speak()` 返回值非 SUCCESS 也记为失败；新增 `lastAttemptFailed()` = `failed || (requested && !started)` ——
   **「要它读了却从没开始」也算失败**，这样缺语音包时不报错的引擎也能兜住。
2. `ui/WordTtsPlayer.kt`：云端拿不到音频 → 先回落系统 TTS → **延迟 1500ms 复查**，
   确实没出声才回调新增的 `onNeedNetwork`（失败回调常晚于 `speak()` 返回，必须延迟判定）。
3. `ReviewActivity` / `ChartActivity` / `ChatActivity` 三处接 Toast，文案 `tts_need_network`
   （「需要联网才能朗读：云端合成失败，本机也没有这门语言的离线语音包」）。
   英语不走云端，不受影响；本机真有离线语音包时也不误报。

**验证（魅族 22，同日）**：飞行模式点日语喇叭 → 约 1.5s 后 Toast 正常弹出；
关掉飞行模式再点同一词 → 正常出声、不误报。
说明这台机器的引擎是**真的判失败**（不是既不报错也不回调），`lastAttemptFailed()` 的两个判据够用，
原先预留的「查 AudioTrack 是否起来」那层兜底不必做了。

### 17.21 实时语音 ASR 语言修复 + 预取提并发（2026-09-23，v1.0.3 / versionCode 4）

#### 17.21.1 ★ 实时语音说日语，转写出来是中文（用户实测反馈）

**现象**：实时语音页，说中文 / 英文时转写正常；说日语时气泡里显示的是**中文**。

**根因**：`RealtimeProtocol.sessionUpdate()` 里从来没有下发过 ASR 语言，
`session` 对象只有 `input_audio_format` / `output_audio_format` 等字段 ——
服务端拿不到语言就用**自己的默认值**；国内厂商的实时接口默认基本都是 `zh`，
于是日语（以及除中英之外的一切语种）都被按中文解码。
中文 / 英文「能识别对」不是因为代码正确，而是**撞上了默认值**。

**改动**：
1. `core/realtime/RealtimeProtocol.kt`：`sessionUpdate(..., inputLanguage: String? = null)`，
   非空时写入 `session.input_audio_transcription = {"language": <tag>}`（stepfun 实时协议的 ASR 语言字段）。
   空/空白时不写，保持旧行为，避免给不支持该字段的通道添麻烦。
2. `ai/openai/StepfunRealtimeProvider.kt`：新增构造参数 `inputLanguage: String = ""`，
   在**两处** `session.update` 都带上 —— 建连时的初始 `session.update`
   和 `updateInstructions()`（后者会重建 session，漏了就前功尽弃）。
3. `ai/AiProviderRegistry.kt`：创建时传 `inputLanguage = lang.tag`
   （`lang.tag` 就是 `ja` / `en` / `zh`，与协议取值一致，不用再映射）。
4. 单测 `RealtimeProtocolTest` +2 例：`session_update_carries_input_transcription_language`
   / `session_update_omits_language_when_blank`。

#### ⚠️ 17.21.1 的修正（2026-09-23 当晚）：上面的修法对 stepfun **无效**

查了阶跃官方文档后必须更正上面这段判断 —— **根因判断错了，改法也不生效**：

1. **stepfun 的 realtime `session.update` 根本没有 ASR 语种字段。**
   官方文档（`platform.stepfun.com/docs/docs/zh/api-reference/realtime/chat`）里
   session 对象**只有 7 个字段**：`modalities` / `instructions` / `voice` /
   `turn_detection` / `input_audio_format` / `output_audio_format` / `tools`。
   `input_audio_transcription` 在文档里**只作为 Server Event 名出现**
   （`conversation.item.input_audio_transcription.completed`），不是可配置项。
   → 我们多发的那个字段服务端会**直接忽略**，是 no-op。
2. **真正的根因是模型语言边界，不是参数漏传。**
   我们用的是 `stepaudio-3-realtime-preview`（`wss://api.stepfun.com/v1/realtime`）。
   StepAudio 3 系列里 **TTS / ASR 支持中英日韩法西，但 Realtime 目前只支持中文和英文**
   （第三方对官方 API 文档的汇总口径；官方中文介绍里 ASR 也只写了「中文、英文、方言、中英混说」）。
   所以用户说日语 → 端到端模型按它熟悉的中英解码 → 吐出中文，
   **这是模型能力边界，不是配置项能调的**。
   中文/英文「能识别对」是因为它们在支持范围内，不是撞默认值。

**代码怎么处理**：`input_audio_transcription.language` 这段**先留着**（对 stepfun 是无效字段，
但 `RealtimeProtocol` 是按 OpenAI Realtime 协议写的，将来换 OpenAI 兼容通道时它是正确的；
且多发一个字段无害）。注释已改成「stepfun 会忽略，保留是协议兼容」。
**不要**再指望它能修日语转写。

**真正的出路（未做，需决策）**：
- A. 认了：UI 上明确写「实时语音当前仅支持中英文，日语识别不准」，避免用户困惑；
- B. 只把「转写」换成独立 ASR（`wss://.../realtime/asr/stream`，其
  `session.audio.input.transcription.language` 是**真实存在**的字段，见 ASR 流式文档），
  日语文本能显示对；**但 realtime 本体仍听不懂日语**，AI 的回复质量不会变好，
  且要同时维护两条 WebSocket；
- C. 换一家支持日语的实时语音通道（如 OpenAI Realtime），改动集中在
  `StepfunRealtimeProvider` 与 `RealtimeProtocol`。

> **2026-09-23 用户决策：A/B/C 都不做，也不加 UI 提示**（选「先都别动，也不加 UI」）。
> 即：**接受「实时语音页日语转写不准」这一现状**，代码保持现状（字段留着、注释已标明无效），
> 本条只作为已知边界记录在案，不投入改动。
> 日后若要重开，直接看本节的 A/B/C 三条，不用再查一遍文档。

#### 17.21.3 ★ 关键更正（2026-09-24 真机实测）：**她其实听懂了，错的只是字幕**

用户实测：说日语后，**AI 的回复内容对得上**。这一条推翻了上面「realtime 听不懂日语」的判断。

**真实情况：**

| 环节 | 状态 |
|---|---|
| 模型内部理解（音频 → 语义） | ✅ **正常**，能听懂日语 |
| AI 生成日语回复 | ✅ 正常 |
| **给用户看的转写字幕** | ❌ 中文谐音（转写头只按中英训练） |

**机制**：实时语音是**端到端**的（音频进 → 内部表示 → 音频出），它理解你**根本不需要先转成文字**。
`conversation.item.input_audio_transcription.completed` 返回的 transcript 是
**单独训练的转写头产生的副产品**，只用于给你显示，**不参与模型内部理解**。
所以「字幕错」与「没听懂」是两件事 —— 实测证明前者成立、后者不成立。

> ⚠️ 上面这段机制说明属于**推断**（依据端到端架构与事件结构），
> 官方文档没有直接写过「转写头只支持中英」。但**现象（回复对得上）是实测的**。

**因此问题范围大幅缩小**：不是「实时语音日语不可用」，而是**只有用户侧字幕不准**。
核心对话体验是好的。

**选项随之重排（重要）：**

| 方案 | 原评价 | **现在的评价** |
|---|---|---|
| A 加 UI 提示 | 最省，但日语仍不可用 | 仍最省；且**只需说明"字幕可能不准"，不必说"听不懂"** |
| B 转写改走独立 ASR | ❌ 半吊子：字幕对了，但模型仍听不懂 | ✅ **升级为完整解**：字幕对了 + 模型本来就听得懂 = **问题完全解决** |
| C 换支持日语的实时通道 | 唯一能解决"听"的办法 | ❌ **没必要了** —— C 是用来解决"听不懂"的，而实测证明听得懂 |

**B 的代价**（现在值得权衡）：需要**第二条 WebSocket**（音频要同时发给 realtime 用于理解回复、
发给 ASR 流用于出正确字幕），上行带宽翻倍，且多一笔 ASR 费用
（`stepaudio-3-asr-max` 约 2.8 元/小时；`stepaudio-2.5` 的 ASR 约 0.15 元/小时）。
字幕也会比现在晚一点出现。

**待补的一个验证**：需排除「她只是按剧本走」的可能 —— 说一句**明显偏离场景剧本**的话
（如突然问天气、问一个具体问题），看她能否接住。能接住才算真的听懂。
> 2026-09-24 用户已确认：说的是偏离剧本的内容也能听懂、回复也没问题 → **确认真的听懂了**。

#### 17.21.4 ★★ 更优方案 B′：复用已预留的「语音识别」槽位修字幕

**已预留的 ASR 组是什么**（`SettingsRepository.Group.ASR`，设置页显示「语音识别 / Whisper 兼容」）：

- 协议：OpenAI 兼容 `POST {baseUrl}/audio/transcriptions`（即 Whisper 定义的接口格式，
  所以任何实现该格式的服务都能填——stepfun ASR、自建 faster-whisper、Groq 等）
- multipart 三字段：`file`（音频，按扩展名推断 mime，wav/mp3/webm/ogg/flac/m4a 均支持）、
  `model`、**`language`**；响应取 `text` 字段
- 实现：`ai/openai/OpenAiSttProvider.kt`（§5.3.2）
- **当前唯一用途**：`ChatActivity:278` 的**回合制录音**（场景对话页点「录音」→ 停 →
  VAD 裁剪 → 上传 → 回填输入框）。**与实时语音页完全无关。**
- ⚠️ **`preset_config.json` 只预置了 chat / realtime / tts 三组，ASR 组是空的**，
  需要用户自己在设置页填；不填则回合制录音不可用（`ChatActivity:254` 会提示）。

**为什么它能修字幕，而且比 B 便宜**：

关键在于它**支持 `language` 参数**（可传 `ja`），能正确出日文。思路：

> 实时语音页每说完一句（服务端 VAD 断句 / 端上静音门控）→ 把这段 PCM 存成 wav
> → 调一次 `OpenAiSttProvider.transcribe(file, "ja")` → 用返回的**正确日文**替换
> stepfun 吐出的中文谐音字幕。

| | B（独立 ASR WebSocket 流） | **B′（复用现有 STT 槽位）** |
|---|---|---|
| 连接 | 需要**第二条 WebSocket** | **一次 HTTP POST**（复用现有 Provider） |
| 新增代码 | 新的流式 ASR Provider + 连接管理 | 在 `VoiceChatActivity` 里加「存 wav → 调 STT → 换字幕」 |
| 已有基建 | 无 | `RealtimeAudioController` 有 PCM 帧回调、`AudioVadTrimmer` 会写 wav |
| 设置/错误处理 | 要新建一组 | 复用现成的 ASR 组与 `AiError` 分类 |

**代价与前提**：

1. 每句一次 HTTP 往返 → **字幕会比现在晚一点出现**；
2. 多一笔 ASR 费用；
3. ⚠️ **前提：ASR 服务端必须支持日语。这一点有分歧，需实测确认** ——
   三方对官方 API 文档的汇总说「TTS/ASR 支持中英日韩法西」，但阶跃**中文**官方文档
   对 ASR 只写了「中文、英文、方言、中英混说」，**没提日语**。
   先拿一句日语音频去试 `/audio/transcriptions`（language=ja）再决定。
4. STT 未配置或调用失败时，**必须回退到现在的字幕**，不能变空白。

#### 17.21.5 stepfun 原生 ASR：实测结论与落地代码（2026-09-24，v1.0.4）

**实测（用真实端点到真实音频，非推断）：**

| 项 | 结果 |
|---|---|
| `POST {base}/audio/transcriptions` | **404** —— stepfun 没有这个 OpenAI 兼容端点（ASR 组填了也调不通的根因） |
| `POST {base}/audio/asr/sse` | **200**，请求格式正确时能正常出字 |
| **日语（language=ja）** | ✅ **能识别**。实测两段日语样本分别返回<br>`天気练习残业安心すんなり电波せんべい玄米线本専用本屋3円単位。`<br>`雪、勇気、外、…「三円」「三年」「记入」「金融」「记念」「禁烟」。` |
| 中文（language=zh） | ✅ 正常（对照组返回「你好」） |
| `stepaudio-3-asr-max` / `stepaudio-2-asr-pro` | ❌ **404 `model_invalid`** —— 当前套餐无权访问 |
| `stepaudio-2.5-asr` | ✅ 可用（**本套餐唯一可用的 ASR 模型**） |

> 排障经验：一开始拿 0.9 秒的短样本测，日/中都返回空且 `audio_tokens=0`，
> 差点误判成"不支持日语"。**换成长样本才出字** —— 短音频容易什么都识别不出来，
> 排查时先换一段 10 秒以上的音频再下结论。

**落地代码（v1.0.4）：**

| 文件 | 作用 |
|---|---|
| `core/net/StepfunAsrWire.kt`（新） | 纯 JVM 协议层：构造「JSON + base64」请求体、解析 SSE 响应、取 error 事件、host 判定 |
| `ai/openai/StepfunSttProvider.kt`（新） | stepfun 原生 STT Provider，实现 `SpeechToTextProvider` |
| `ai/AiProviderRegistry.kt` | ASR 组按 host 择一：stepfun → 原生 Provider；其它 → Whisper 兼容 Provider |
| `core/net/BaseUrlNormalizer.kt` | `SUFFIXES` 增加 `/audio/asr/sse`（用户常直接粘文档里的完整端点） |
| `core/net/StepfunAsrWireTest.kt`（新） | 13 例，钉住请求体嵌套、SSE 解析、错误事件、host 判定 |

**两个刻意的设计决定：**

1. **不改动 `OpenAiSttProvider`**。ASR 组对外承诺是「Whisper 兼容」，
   很多服务（Groq / OpenAI / 自建 faster-whisper）都实现 `/audio/transcriptions`，
   那套必须原样保留，只在 host 指向 stepfun 时才切到私有协议。
2. **host 判定用域名后缀匹配，不是 `contains("stepfun")`**。
   否则 `my-stepfun-mirror.example.com` 这类自建/代理域名会被误判，
   拿 stepfun 私有协议去打一个其实是 Whisper 兼容的服务 —— 单测
   `other_hosts_are_not_stepfun` 专门守这条（这是写测试时真抓出来的）。
3. **必须查 SSE 里的 `error` 事件**。服务端会以「HTTP 200 + SSE 带 error」报错
   （例如 model 无权访问），只看状态码会漏，表现成"返回空"。
4. 识别结果为空时**明确报 `BadResponse("empty transcript")`**，不返回空串 ——
   否则 UI 会回填一个空白，用户看到的是"识别了个寂寞"。

**用法**：设置页 → 「语音识别」组 → 填 baseUrl `https://api.stepfun.com/step_plan/v1`、
model `stepaudio-2.5-asr`、Key，勾选启用。填完在**场景对话页**点「录音」验证。

### 17.22 全盘审计：文档与代码不一致清单（2026-09-23）

起因：用户指出「App 里根本没有『按住说话』，是点击开始/暂停」——文档把**已删除的旧交互**
当成了现状。这说明交接文档存在系统性失真，于是做了一次全盘核对。**以下全部已核对过代码。**

#### 17.22.1 高严重度（**照文档做会直接出问题**）

| # | 文档原说法 | 代码实际 | 位置 |
|---|---|---|---|
| 1 | 实时采样率 **16000 Hz**（320 样本 / 640 B） | **24000 Hz**（480 样本 / **960 B**） | `RealtimeAudioController.SAMPLE_RATE` |
| 2 | server_vad 能量阈值 **500** | **1300**（500 是**被否决**的值：2 米外人声就会触发） | `RealtimeOptions.DEFAULT_ENERGY_THRESHOLD` |

> 第 1 条尤其危险：按 16kHz 推流，服务端**不报错也不回事件**，表现为
> 「连上了、麦克风在采集、AI 一声不吭」。已在附录 A 加了醒目警告。

#### 17.22.2 中严重度（描述与实现不符，误导判断）

| # | 文档原说法 | 代码实际 | 位置 |
|---|---|---|---|
| 3 | 语伴名「日语 yoyo · **英语 Emma**」 | **两门语言都叫 yoyo**（注释写明是用户要求统一） | `ui/LangStyle.partnerName` |
| 4 | 每日目标「30 分钟**常量，未做成可配置**，在 `HomeActivity.GOAL_MINUTES`」 | 是 `AppSettings.dailyGoalMinutes`，默认 30，**可在「我的」页改**（5–180 分钟，填 0 回落 30）。位置和"不可配置"两点都错 | `AppSettings.DEFAULT_DAILY_GOAL_MINUTES` |
| 5 | 「回合制录音 = **按住**麦克风说一段 → 松手」 | **全 App 没有任何长按手势**：实时语音页是单键点击切换；场景对话页是「录音」/「停止」按钮点击切换 | `VoiceChatActivity.toggleSpeaking()` / `ChatActivity.toggleRecording()` |
| 6 | 测试 26 类 / 387 例 | **28 类 / 401 例** | 见 §11.2 |
| 7 | 主源码 72 个 .kt / 6,519 行 / 16 布局 / 36 drawable / 6 份 assets | **82 / 9,743 / 21 / 45 / 18** | 见 §0 与 §1.1 |

#### 17.22.3 死代码与遗留（非本次引入，建议下次清理）

| # | 现象 | 证据 | 建议 |
|---|---|---|---|
| 8 | **`voicePushToTalk` 是死设置** | `AppSettings` 有字段、`SettingsRepository` 有读写方法，但**全工程无调用方**，UI 也不暴露 | 删字段 + 读写方法 |
| 9 | **`pushToTalkMode` 命名过时且被硬编码** | `VoiceChatActivity:388` 写死 `it.pushToTalkMode = true`；名字源自已删除的「按住」手势 | 改名（如 `gateMicByButton`）+ 注释说明为何恒为 true |
| 10 | **5 个「按住说话」遗留字符串** | `profile_push_to_talk`、`profile_push_to_talk_sub`、`voice_btn_hold`、`voice_hold_to_talk`、`voice_release_to_send` —— 全工程零引用 | 删除 |
| 11 | **`RealtimeOptions` 注释与常量自相矛盾** | 类注释正文写「实测定的值：**1200**」，常量 `DEFAULT_ENERGY_THRESHOLD = **1300**` | 以 1300 为准，改注释 |

> 第 10 条是第 5 条的独立佐证：那次交互改造只删了代码与布局，**字符串没清**。
> 这类"删一半"的改造正是交接文档失真的来源。

#### 17.22.4 核对通过（文档正确，可放心引用）

SM-2（ef 下限 1.3 / 初始 2.5 / 前两次 1 天 6 天）· VAD（-45 dBFS / 300 / 500 / 200ms / 0.98 / 60s）·
上下文历史 24 条 · 单段录音 60s · 静音门控 800ms · 测试连接超时 8s ·
默认音色 `qingchunshaonv` / TTS 回落 `alloy` · 密钥掩码（`take(3)+****+takeLast(4)`，<8 字符 → `****`）·
配置 version 2 / 进度 version 2 / 每日统计 version 1 · 每日排卡 20（`DailyQuota.CARDS_PER_DAY`）·
已掌握 `reps >= 5` · 成就阈值（开口/连续 7 天/100 词/200 词/10 小时/100 轮）·
复习计入下限 5 秒 / 单次上限 30 分钟 · 实时会话单次上限 2 小时 · 昵称上限 12 ·
底部导航 62dp + 22dp = 84dp · **`core/` 无任何 `android.*` 引用（分层干净）** ·
`SourcePolicyTest` 存在且 4 例全绿。

### 17.23 离线语音包与本地 TTS 的调研结论（2026-09-23，**结论：继续走云端**）

用户先后问了两件事：① 能不能把词库音频一次性预下载成"离线语音包"；② 有没有开源离线 TTS 可用。
调研结论都记在这里，**重开时不用再查一遍**。

#### 17.23.1 预下载云端音频当"离线语音包"——不可行（测算）

词库实测：assets 合计 **47,876** 条；日语 ja_n1 3463 / n2 1793 / n3 1784 / n4 632 /
n5 662 / 假名 46 / n5_vocab 138 ≈ **8,518 条**（n5_vocab 可能与 ja_n5 重叠）。
英语**不走云端**（系统 TTS），不用算。

按「词条 + 例句」各一条、实测均值 ~26KB/条、并发 2、1.7s/条：

| 方案 | 音频条数 | 体积 | 下载耗时 | 成本粗估* |
|---|---|---|---|---|
| 全量日语 | ~17,000 | **≈440 MB** | **≈4 小时** | ≈150–190 元 |
| N5+N4 | ~2,680 | ≈70 MB | ≈38 分钟 | ≈30 元 |
| 只做 N5（含假名） | ~1,416 | ≈37 MB | ≈20 分钟 | ≈15 元 |

\* 按 stepaudio-2.5-tts 公开价 5.8 元/万字符粗估，Step Plan 额度另算。

> ⚠️ 真要做，**不能放 `cacheDir`**：系统存储紧张会清、设置里「清除缓存」也会清，
> 且 64MB LRU 会悄悄淘汰。应落 `filesDir` 并独立于 LRU。

#### 17.23.2 备选方案（按性价比）

1. **（最划算）预取"今日队列 + 未来 N 天复习队列"，并把学过/待复习的词 pin 住不淘汰**
   —— 几十~几百条（1–5MB），日常 100% 秒响、断网时学过的都能响，**零下载入口、零包体代价**。
2. 按等级下载到 `filesDir`（N5 ≈37MB / 20 分钟）。
3. 极小核心内置 APK（假名 46 + N5 基础 138 ≈ 4.8MB），装上即有，代价 APK +5MB。
4. **格式瘦身**：`OpenAiWire.buildTtsBody` 里 `response_format` 硬编码 `mp3`；
   若 stepfun 支持 opus，上面所有数字 ×0.5。改动极小，值得先试。
5. 系统 TTS 离线语音包 —— **不推荐**：国内机型日语语音包不可靠（§17.16 就是因此改走云端）。

#### 17.23.3 开源离线 TTS 选型（调研结论）

**部署层用 `sherpa-onnx`**（Apache-2.0，14k stars，v1.13.x 仍活跃）：提供 Android AAR +
Java/Kotlin 绑定 + 预编译 demo APK，统一跑 VITS/Piper/Matcha/Kokoro/MeloTTS。
自己从零接 ONNX Runtime 不划算。

| 模型 | 许可 | 体积 | 手机速度（通用基准） | 音质 |
|---|---|---|---|---|
| **Kokoro-82M** | **Apache-2.0** ✅ | 325MB fp32 / 163 fp16 / **92MB int8** | 旗舰 20–40s / 分钟音频；中端 60–90s；低端 2–3 分钟 | 端侧第一梯队（TTS Arena Elo ~1056，浏览器可跑模型第一，高于 WaveNet 873 / Polly Neural 868），总榜 32/74 |
| **MeloTTS** | **MIT** ✅ | 中等 | 官方定位 CPU 实时 | 中等；覆盖英/西/法/中/**日**/韩 |
| Piper | ⚠️ **GPL-3.0** | ~63MB/音色 | 快（5–20s/分钟） | 清晰但偏朗读感 |
| Silero | ❌ CC BY-NC-SA（**非商用**） | — | — | 直接排除 |

> ⚠️ **Piper 的许可陷阱**：归档的 `rhasspy/piper` 是 MIT，但**现役 successor
> `OHF-Voice/piper1-gpl` 是 GPL-3.0**，且内嵌的 espeak-ng 也是 GPL。
> 按老印象以为 MIT 就嵌进闭源产品，这个假设不成立。音色文件许可另算（需逐个看 MODEL_CARD）。

#### 17.23.4 与现状的对比（**注意哪边是实测**）

| | 首次出声（约 2 秒文本） | 缓存后 | 断网 | 音质 |
|---|---|---|---|---|
| **现在（云端，实测）** | **1.2–2.4s，均值 1.67s** | 0ms | ❌ | 云端旗舰档（曾全球前三） |
| Kokoro int8（**估算**） | 旗舰 ~0.7–1.3s；中端 ~2–3s | 0ms | ✅ | 端侧第一梯队，**低于云端旗舰** |
| Piper（**估算**） | ~0.2–0.7s | 0ms | ✅ | 中等 |

**两点必须打折**：
1. 离线那一列是**厂商/三方通用基准，不是魅族 22 实测**；云端 1.67s 才是实测。
   拿实测对比估算，这张表只能看趋势。
2. 离线数字**不含模型加载时间**（ONNX 初始化可能几秒，常驻内存则只付一次）。

**反直觉的结论**：本地模型**可能比现在云端首次朗读更快** —— 因为云端走非流式端点、
必须等整段合成完（实测耗时与文本长度无关就是这个原因）。
所以换离线的瓶颈在**音质与工程复杂度，不在速度**。

#### 17.23.5 ★ 用户决策（2026-09-23）

**继续走云端，离线 TTS 暂不做。** 理由：云端音质更好，且速度差距不成立（见 17.23.4）。

**若将来重开，推荐路径**（按此顺序，成本低→高）：
1. 先试 17.23.2 第 4 条（换成 opus 格式）—— 改一行，可能让所有体积数字减半；
2. 再做 17.23.2 第 1 条（预取 + pin）—— 零包体代价，覆盖 95% 真实场景；
3. 真要"完全离线"，先用 **sherpa-onnx 官方预编译 demo APK** 在魅族 22 上实测
   Kokoro/Piper 的**真实耗时并亲耳听音色**，拿到真数字再决定要不要接。
   （未核实项：sherpa-onnx 对 minSdk 的要求，本工程 minSdk 26。）

#### 17.22.5 给接手人的教训

这份文档里的**数字和交互描述会随时间失真**，因为改造时往往只改代码不改文档。
**凡是要动一个参数、一个交互前，先回代码里确认一次**，别直接信附录 A 的表。
本次审计修正的值已在附录 A 就地改对，并在表下加了「照着改会出事」的警告。

#### 17.21.2 首次朗读再快一点（免凭据那一半）

§17.19 实测发音表 46 格顺序预热要 **53.4s**，一次只发一条，固有延迟被串行累加。
在不能碰 API Key（配置里是明文，读出来会被安全策略拦）的前提下做了两件事：

1. `ui/WordTtsPlayer.prefetchAll()`：从「整批抢一个 permit、内部串行」改成
   **逐条 `launch { prefetchGate.withPermit { obtainAudio(...) } }`** ——
   并发上限仍是 `PREFETCH_CONCURRENCY = 2`（守限流），但两条可以同时在飞。
   理论上限 ≈ 53.4s / 2 ≈ **27s**；实测值待真机填。
2. `ui/review/ReviewActivity`：`showNext()` 里除了当前卡，额外预取**下一张卡**
   （抽 `prefetchAudio(card)`，词条和例句都预取）。翻卡时目标音频多半已热。

> 剩下的「换更快的模型」那一半要凭据，留给用户在设置页改 TTS `model`
> （如 `step-tts-mini`，厂商/三方反馈 TTFB 约快一半），用 `ShiyuTts` 的 `MISS` 耗时对比即可判断。



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
| 实时采样率 / 帧长 | **24000 Hz** / 20 ms（480 样本 / **960 B**） | `RealtimeAudioController.SAMPLE_RATE`（**别改回 16000**，见下方警告）|
| 实时静音门控 | 800 ms | `RealtimeAudioController.SILENCE_GATE_MS` |
| 实时 server_vad 默认 | 开 / 100 ms / **1300** / 500 ms | `RealtimeOptions.DEFAULT_ENERGY_THRESHOLD`（非官方默认 2500；**注意类注释正文写的 1200 是旧的，与常量 1300 不一致**）|
| 实时测试连接超时 | 8 s | `SettingsActivity.probeRealtime` |
| 默认音色 | `qingchunshaonv`（青春少女/女声） | `RealtimeProtocol.VOICE_FEMALE` |
| TTS 默认音色 | `alloy` | `OpenAiWire.buildTtsBody` |
| 密钥掩码 | `take(3)+"****"+takeLast(4)`；<8 字符 → `****` | `LogRedactor.mask` |
| 配置 version / 进度 version | 2 / 2 | `AppSettings` / `StudyStore` |
| 每日统计 version | 1 | `DailyStatsStore` |
| 今日目标 | 默认 30 分钟，**可在「我的」页改**（5–180 分钟，填 0 回落 30） | `AppSettings.dailyGoalMinutes` / `DEFAULT_DAILY_GOAL_MINUTES = 30` |
| 复习页时长计入下限 / 单次上限 | 5 秒 / 30 分钟 | `ReviewActivity` |
| 实时会话时长单次上限 | 2 小时 | `VoiceChatActivity.MAX_SESSION_SECONDS` |
| 昵称长度上限 | 12 字符 | `SettingsRepository.MAX_NICKNAME_LENGTH` |
| 成就阈值 | 开口>0 / 连续 7 天 / 100 词 / 200 词 / 10 小时 / 100 轮 | `core/stats/Achievements` |
| AI 语伴名 | **两门语言都叫 yoyo**（用户要求统一成一个名字，不是日语 yoyo / 英语 Emma） | `ui/LangStyle.partnerName` |
| 底部导航高度 | 导航条 62dp + 麦克风凸起 22dp = 84dp | `view_bottom_nav.xml` |

> ### ⚠️ 两个「照着文档改就会出事」的值
>
> 1. **实时采样率是 24000 Hz，不是 16000。** 按 16kHz 推流时服务端**不报错、也不回任何事件**，
>    表现为「连上了、麦克风在采集、但 AI 一声不吭」，极难排查。
>    注意别被模型内部结构误导：Step-Audio 的 S3Tokenizer 确实在 16kHz 上工作，但那是**模型内部**的事，
>    送进 `input_audio_buffer.append` 的必须是 24kHz。
> 2. **server_vad 能量阈值是 1300，不是 500。** 500 是**被否决**的值 —— 实测「约 2 米外的人说话」就能触发。
>    官方默认 2500 则连本人都唤不醒（本人说话实测电平换算幅度约 184~2900，短时均方根多数帧只有几百）。
>    1300 落在两者中间。（`RealtimeOptions` 的类注释正文写的「实测定的值：1200」是旧数字，
>    **以常量 1300 为准**，注释待修。）

## 附录 B：任务书章节索引（定位规格时用）

`§2.1` 工具链 · `§2.2` JDK · `§2.3` 禁用组件 · `§2.5` 隔离要求 · `§3.1` 目录结构 · `§3.2` 应用标识 ·
`§4.3` 功能需求 M0–M5 · `§4.4` 资产要求 · `§5.1` 包结构 · `§5.2` Provider 接口 · `§5.3` 线协议 ·
`§5.4` baseUrl 规范化 · `§5.5` 错误分类 · `§5.6` 语言模块（含 .2 假名转换 / .3 归一化 / .4 分词 / .5 分句 / .6 资产映射）·
`§5.7` 判题 · `§5.8` SM-2 · `§5.9` 上下文裁剪 · `§5.10` 加密存储 · `§5.11` 数据持久化 · `§6` 安全合规 ·
`§7` 权限 · `§8` 依赖清单 · `§9` 验收标准 V1–V22 · `§10` 自查命令 · `§11` 禁止事项 · `§12` 已知失败模式 ·
`§13` 交付物 · `§14` 完成定义 · `附录 A/B` 场景 systemPrompt 模板 · `附录 C` 场景 JSON 结构 · `附录 D` 测试向量

---

*文档结束。如发现本文档与代码不一致，**以代码为准**，并在修正后同步本文档。*

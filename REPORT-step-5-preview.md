# REPORT — step-5-preview

## 1. 交付概览

| 项 | 值 |
|---|---|
| 工程位置 | `D:\AI project\lang-partner` |
| 构建命令 | `cd "D:\AI project\lang-partner"; $env:JAVA_HOME = "D:\a\jbr"; .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain` |
| 退出码 | **0（BUILD SUCCESSFUL）** |
| APK 路径 | `D:\AI project\lang-partner\app\build\outputs\apk\debug\app-debug.apk` |
| APK 大小 | 6,973,144 字节（约 6.65 MB） |
| APK 时间 | 2026/9/20 13:52:18（晚于任务开始时间 12:19） |
| 单测总数 | **295**（21 个测试类） |
| 通过 | **295**；失败 0；错误 0；跳过 0（验收要求 tests ≥ 120，实际 295） |
| 代码规模 | main 53 个 .kt（3,939 行）+ test 21 个 .kt（2,759 行）+ 6 份 assets + 10 个布局 |

工具链严格按 §2.1 锁定：Gradle 8.9 / AGP 8.7.3 / Kotlin 2.1.0 / compileSdk·targetSdk 35 / minSdk 26 / Java 17 / buildToolsVersion 36.0.0 显式声明；`local.properties` 写 `sdk.dir=D\:/AndroidSdk`。

## 2. 功能完成度对照表

| 编号 | 功能 | 日语 | 英语 | 状态 |
|---|---|---|---|---|
| M0-1 | 当前语言持久化，重启保持 | ✅ | ✅ | 完成 |
| M0-2 | 切换语言后卡组/进度/场景/Provider/图表/统计全切换不串数据 | ✅ | ✅ | 完成 |
| M0-3 | 进度按语言隔离（同名 cardId 两条独立进度，单测断言） | ✅ | ✅ | 完成 |
| M0-4 | 切换语言不丢进度不崩溃 | ✅ | ✅ | 完成 |
| M1-1 | 今日任务：due≤今天升序优先，不足 20 用未学过补足，完成提示 | ✅ | ✅ | 完成 |
| M1-2 | 卡片正面：日语 kanji/kana + JLPT + 释义；英语释义 + A1·n. + 例句挖空 | ✅ | ✅ | 完成 |
| M1-3 | 同输入框作答，占位提示由语言模块给出，确认后翻转显示答案/读音/判定 | 假名读法 | 英文拼写 | 完成 |
| M1-4 | 4 按钮 ↔ quality 2/3/4/5 固定映射 | ✅ | ✅ | 完成 |
| M1-5 | AnswerJudge 先各自归一化再比较 | ✅ | ✅ | 完成 |
| M1-6 | 每次评分 SM-2 排卡并立刻落盘（原子写） | ✅ | ✅ | 完成 |
| M1-7 | 图表页点格系统 TTS 朗读（不占 Provider） | 五十音 | IPA | 完成 |
| M2-1 | 场景 ≥5/语言，资产含 goalExpressions ≥5 | ✅ | ✅ | 完成 |
| M2-2 | 进入场景显示 openingLine 与目标表达清单 | ✅ | ✅ | 完成 |
| M2-3 | 录音→转写→裁剪→对话→三分段解析→逐句切分→TTS→整轮落盘 | ✅ | ✅ | 完成 |
| M2-4 | 按行首标签解析 [TAG]/[ZH]/[FIX]，大小写不敏感，缺失留空，不抛异常 | JA | EN | 完成 |
| M2-5 | 文本输入降级：拒权限/未配置识别/识别失败均可用文字且给明确提示 | ✅ | ✅ | 完成 |
| M2-6 | 无凭证 Mock 路径跑完一轮，回复含三段且正文带 (mock) | ✅ | ✅ | 完成 |
| M2-7 | 录音参数 MPEG_4/AAC/16000/单声道/64000，文件 cacheDir/rec_*.m4a | ✅ | ✅ | 完成 |
| M2-8 | 每次进入新建 sessionId，每轮追加 turn 并落盘 | ✅ | ✅ | 完成 |
| M2-9 | AI 正文逐句切分逐句可点读（两语言共用） | ✅ | ✅ | 完成 |
| M3-1 | 每语言 3 组独立配置共 6 组，可指不同厂商 | ✅ | ✅ | 完成 |
| M3-2 | 未配置即降级 Mock，不抛异常不崩溃 | ✅ | ✅ | 完成 |
| M3-3 | OpenAI 兼容协议三 endpoint 精确报文 | ✅ | ✅ | 完成 |
| M3-4 | 测试连接按 §5.5 映射中文提示，不统一"网络错误" | ✅ | ✅ | 完成 |
| M3-5 | 三能力接口注入，UI/其它层不依赖 OkHttp | ✅ | ✅ | 完成 |
| M3-6 | SettingsRepository 局部自愈，互不影响 | ✅ | ✅ | 完成 |
| M4 | JSON 落盘、缺字段默认、损坏自愈、语言隔离、无数据库 | ✅ | ✅ | 完成 |
| M5-1 | 按语言统计总卡/今日待复习/已掌握(reps≥5)/累计轮数 | ✅ | ✅ | 完成 |
| M5-2 | 二次确认清空三处数据回初始状态 | ✅ | ✅ | 完成 |
| M5-3 | apiKey 默认掩码，点显示才明文，切语言/离开回掩码 | ✅ | ✅ | 完成 |

**未完成项**：仅 V22（真机冒烟，需交付方人工执行）；详见第 5 节。

## 3. 关键实现说明

- **语言模块抽象**：`LanguageModule` 接口固定按 §5.6.1 实现；`JaModule`/`EnModule` 收口全部语言差异（归一化、判题基准、资产构建、分句）；`LanguageRegistry` 是唯一按语言分发入口。`ui/`、`ai/`、`data/` 零语言专有分支（`SourcePolicyTest` 镜像核验方的 grep，扫描 `KanaConverter`/`EnTokenizer`/`romaji`/`toHiragana`/`katakana`/`hiragana`/`tokenize` 等符号，测试通过）。
- **假名转换**（`KanaConverter`）：规则 1 促音（双辅音，`n` 不入集合）→ 规则 2 `tch` 特例 → 规则 3 拨音（`nn` 只消耗一个 `n`、`n'` 显式分隔、母音/y 前交给查表）→ 规则 4 最长匹配（3→2→1，Hepburn+kunrei 别名全表）→ 规则 5 未识别原样入 `unparsed`，`-` → `ー`。19 条锚点 + 21 条扩展共 40 个单测全绿。
- **英语归一化**（`EnNormalizer`）：NFKC → 弯引号/破折号显式映射 → 逐字符 NFD 剥离拉丁变音（首字符非 `[A-Za-z0-9]` 则原样输出，保护 `が`/日本語）→ `lowercase(Locale.ROOT)` → 空白折叠。16 条锚点全过，`Straße`→`straße` 不展开、混排日文不破坏。
- **分词/分句**：`EnTokenizer` 用 `Character.isLetter/isDigit` 分类，引号类单独成 token，撇号/连字符/数字小数点按"后字符+前字符"条件吸收，偏移按 UTF-16（emoji `[1,3)` 断言通过）。`EnSentenceSplitter` 终止符合并后逐段判定：非点直接终止；点后非空白非 CLOSER 不终止；否则回看 core 查缩写表与"1–4 位全大写"缩写规则。
- **SM-2**（`Sm2Scheduler`）：严格两步——先用旧 ef 算新 ef（下限 1.3、2 位小数 half-up），再用旧 ef 算间隔（n'=1→1，n'=2→6，否则 `round(intervalDays*旧ef)`）。`q=4` 序列断言 1,6,15,38；`q=3` 且 `interval=6,ef=2.5` 断言 interval=15（证明乘法用旧 ef）。
- **错误分类**（`HttpErrors.classify`）：纯函数，ioError(timeout/其它)→401/403→404→429(Retry-After 正整数)→5xx(截断 200)→其它非 2xx，顺序固定；每种错误在 UI 有独立中文文案（`AiErrorMessages`）。
- **加密存储**（`KeystoreSecretStore`）：AndroidKeyStore AES-256-GCM，`[1B 版本][12B IV][密文]` 落 `filesDir/secure_settings.bin`；读失败回退默认值，连续失败 3 次删文件删密钥重建；`SettingsRepository` 纯 JVM 可测（内存版 `SecretStore` 注入），6 组配置局部自愈（缺 `langs.en` 只补 en、缺 `voice` 只补该字段、`activeLang` 非法回退 ja）。

## 4. 自查输出（§10 命令原始输出）

```text
### CMD1: gradlew assembleDebug + testDebugUnitTest
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest UP-TO-DATE

BUILD SUCCESSFUL in 15s
42 actionable tasks: 42 up-to-date

### CMD2: APK info
FullName                                                              Length LastWriteTime
--------                                                              ------ -------------
D:\AI project\lang-partner\app\build\outputs\apk\debug\app-debug.apk 6973144 2026/9/20 13:52:18

### CMD3: unit test results
com.langpartner.app.ai.OpenAiProviderTest tests=12 fail=0 err=0 skip=0
com.langpartner.app.core.answer.AnswerJudgeTest tests=17 fail=0 err=0 skip=0
com.langpartner.app.core.chat.ReplyParserTest tests=11 fail=0 err=0 skip=0
com.langpartner.app.core.context.ContextTrimmerTest tests=10 fail=0 err=0 skip=0
com.langpartner.app.core.lang.en.EnNormalizerTest tests=26 fail=0 err=0 skip=0
com.langpartner.app.core.lang.en.EnSentenceSplitterTest tests=25 fail=0 err=0 skip=0
com.langpartner.app.core.lang.en.EnTokenizerTest tests=27 fail=0 err=0 skip=0
com.langpartner.app.core.lang.ja.KanaConverterTest tests=40 fail=0 err=0 skip=0
com.langpartner.app.core.lang.ja.KanaNormalizerTest tests=7 fail=0 err=0 skip=0
com.langpartner.app.core.lang.LanguageModuleTest tests=13 fail=0 err=0 skip=0
com.langpartner.app.core.lang.MockProviderTest tests=7 fail=0 err=0 skip=0
com.langpartner.app.core.log.LogRedactorTest tests=5 fail=0 err=0 skip=0
com.langpartner.app.core.net.BaseUrlNormalizerTest tests=11 fail=0 err=0 skip=0
com.langpartner.app.core.net.HttpErrorsTest tests=16 fail=0 err=0 skip=0
com.langpartner.app.core.net.OpenAiWireTest tests=14 fail=0 err=0 skip=0
com.langpartner.app.core.srs.Sm2SchedulerTest tests=11 fail=0 err=0 skip=0
com.langpartner.app.data.asset.AssetContentTest tests=9 fail=0 err=0 skip=0
com.langpartner.app.data.store.SessionStoreTest tests=8 fail=0 err=0 skip=0
com.langpartner.app.data.store.SettingsRepositoryTest tests=10 fail=0 err=0 skip=0
com.langpartner.app.data.store.StudyStoreTest tests=12 fail=0 err=0 skip=0
com.langpartner.app.SourcePolicyTest tests=4 fail=0 err=0 skip=0

### CMD4: uses-permission
app\src\main\AndroidManifest.xml:4:    <uses-permission android:name="android.permission.INTERNET" />
app\src\main\AndroidManifest.xml:5:    <uses-permission android:name="android.permission.RECORD_AUDIO" />

### CMD5: secret scan (no output = clean)
（无输出）
```

> 注：CMD1 为增量构建输出（源码未变动，任务全 UP-TO-DATE）；此前一次完整构建同样 `BUILD SUCCESSFUL`，退出码 0，单测 295/295 通过。`Select-String` 汇总：tests 合计 295、failures 合计 0、errors 合计 0、skipped 合计 0。

## 5. 未完成项与已知缺陷

**未完成项**

1. **V22 真机冒烟（人工）未执行**：安装启动、断网两种语言卡片训练、无凭证 Mock 对话、切语言进度不串——均需在真机人工验证；已按 V3 的要求用单测覆盖了「无凭证完成一轮 Mock 对话且回复含三段」的链路。
2. 语音合成自动播放依赖真机 MediaPlayer 与网络 Provider，未在设备上验证。

**已知缺陷（按规范明示的取舍，未试图「修得更好」）**

1. **缩写句末不终止**：`etc.` / `U.S.` 等出现在"句末"时该点不终止，会与下一句并为一条（§5.6.5 明确取舍）。例：`He lives in the U.S. Really?` 切出 1 段 `He lives in the U.S. Really?`。
2. **`´`(U+00B4) 不被映射为 `'`**：规范第 1 步 NFKC 会把它分解为「U+0020 + U+0301」，映射表在其后执行，命中不到。单测按规范字面顺序断言结果为 `it ́s`（空格 + 组合锐音符）。
3. **`″`(U+2033) 映射为两个 `'`**：NFKC 先把 `″` 分解为两个 `′`(U+2032)，各自命中撇号映射表。单测按规范字面顺序断言为 `"a''`。
4. **英文判题抹平撇号**：`It's` 与 `its` 判对（§5.7 明示取舍）。
5. **UI 未用 ViewModel**：§8 钉死的依赖清单不含 `lifecycle-viewmodel`，按「不得引入清单外依赖」以 Activity + `onSaveInstanceState` 承载状态；`ui/` 子包（study/chat/chart/profile/settings）结构符合 §5.1。
6. **「累计对话轮数」按 user 消息计数**（assistant 开场白不计入一轮）；规范未给出口径，此处按「一轮 = 用户说一句」计。

## 6. 自主决策（任务书未覆盖、由实现方决定的点）

1. **`Card` 增加可选字段 `exampleBlank`（默认 `""`）**：英语例句挖空结果需要随卡传递，而 §5.6.1 的 7 字段 `Card` 无处安放。用默认参数扩展，不破坏既有构造签名；§5.6.6 的 `meaningZh=""`（英语）与例句挖空两者同时满足。
2. **`ChartEntry` 增加可选字段 `alt`/`note`/`section`（默认 `""`）**：分别承载日语平/片假名切换、英语例词中文、英语图表 vowel/consonant 分节；原有 3 参构造签名不变。
3. **`HttpCall` 增加可选字段 `formFields`（默认空 Map）**：§5.2 的 `HttpCall` 只带单个 `MultipartFile`，而 STT multipart 还需 `model`/`language` 两个文本字段。由真实 Provider 构造、假 Transport 在单测中直接断言（V14 的 `language=ja`/`language=en` 两种即由此断言）。
4. **`AiError`/`AiResult` 真实声明放在 `com.langpartner.app.ai`（§5.2 的接口签名包），`data/model/AiError.kt` 以类型别名兼容 §5.1 文件布局**：泛型类型别名无法解析嵌套类（`AiResult.Ok`），两个包路径都通过构造/import 可用。`ChatMessage` 相反：真实类在 `data.model`（§5.1），`ai` 包为类型别名（非泛型，构造调用正常）。
5. **`CardProgress`（data.model，存储字段）与 `CardState`（core.srs，算法入参）分开**：UI 评分时显式转换，不混用。
6. **底部导航用三个 Activity + `FLAG_ACTIVITY_REORDER_TO_FRONT` 切换**（不用 Navigation 组件，符合 §2.3）；语言切换条（RadioGroup）以 `<include android:id>` 方式复用，`onResume` 同步当前语言。
7. **逐句朗读用系统 TTS**（`android.speech.tts`，与图表页 M1-7 同一 rationale，不占 AI Provider 额度）；AI 整段回复的自动播放走配置的 TTS Provider。
8. **「测试连接」用当前表单值即时构造 Provider 发起最小请求**（未保存也可测），ASR 用 64 字节探针文件；结果按 §5.5 表映射中文文案。
9. **`SettingsRepository` 读取时只做内存层自愈，不在 read 路径回写**（避免读操作产生副作用写盘）；持久化发生在 `save/update/setActiveLang`。
10. **Mock 预置文案收口在 `core/lang/MockReplies.kt`**（按 `LangId` 分发），`ai/` 层只拿字符串，从而 `ai/` 包零语言分支。
11. **`SourcePolicyTest` 作为单测镜像核验方的静态扫描**（ui/ai 无语言专有符号、无密钥样式字符串、manifest 恰好 2 条权限、core/data 纯逻辑无 android import），把 §9/§11 的可 grep 项变成可持续回归的断言。
12. **测试源码中的罕见字符（弯引号、半角片假名、´ ″ 等）统一用 `\u` 转义书写**：规避源码文件在跨工具写入时的编码损坏风险（本轮开发中实际发生过两次半角片假名/弯引号字节损坏，转义后彻底规避）。

## 7. 环境适配记录

| 项 | 记录 |
|---|---|
| JAVA_HOME | 每次 Gradle 调用前显式 `$env:JAVA_HOME = "D:\a\jbr"`（本机默认 JDK 11 会触发 `Android Gradle plugin requires Java 17 to run`） |
| build-tools | 显式 `buildToolsVersion = "36.0.0"`（本机无 35.0.0） |
| compileSdk | 35（本机 platforms 有 android-35/36.1/37.0，无 34） |
| wrapper | `gradle/wrapper/` 与 `gradlew`/`gradlew.bat` 从 `D:\AI project\bottom-radar-android` 只读复制（distributionUrl 即 gradle-8.9-bin.zip，与 §2.1 一致） |
| org.json | `testImplementation("org.json:json:20240303")`（§8 要求：android.jar 的 org.json 是桩，不加则 JSON 单测全挂） |
| unitTests | `unitTests.isReturnDefaultValues = true`（§12 第 4 条） |
| 编码 | `gradle.properties` 含 `-Dfile.encoding=UTF-8`；测试源罕见字符改 `\u` 转义（见自主决策 12） |
| Gradle 守护进程 | 首次构建约 55s（含 AndroidX 依赖联网解析），后续增量 15–48s |

**构建过程中遇到并已解决的报错**（全部归因于实现问题，无环境遗留）：

1. `Unresolved reference 'Ok'/'Err'` —— 泛型类型别名不能解析嵌套类，将 `AiResult`/`AiError` 真实声明移入 `ai` 包（见自主决策 4）。
2. `Unresolved reference 'katakanaSwitch'/'langGroup'/'bottomNav'` —— `<include>` 未带 `android:id` 时其内部 ID 不会暴露到父 binding；改为 `<include android:id="@+id/langBar">` 后经 `binding.langBar.langGroup` 访问。
3. `No value passed for parameter 'cards'` —— `LangProgress` 构造缺省值补齐。
4. `Argument type mismatch: CardProgress vs CardState` —— 评分链路显式转换 data.model ↔ core.srs。
5. `Method ... should be void` —— `= runBlocking { ... tmp.delete() }` 表达体推断出 Boolean 返回，改为显式 `: Unit`。
6. 单测断言类问题（实现方自纠）：`KanaNormalizer` 不转小写（预期应为 `ABC`）；`ァ`(U+30A1) 按规则转为 `ぁ`(U+3041)；LogRedactor 65 字符键的构造（`sk-ZD5Lr` 是 8 字符）；空白例只替换首次出现；`´`/`″` 的 NFKC 取舍改为按规范字面顺序断言。

**隔离性确认**：未读取/修改 `D:\AI project` 下任何其它工程（wrapper 文件为只读复制）；全部产出仅落在 `D:\AI project\lang-partner`。

---

## 补充（当日第二轮）：A 档防护 + 实时语音两步改造 + 拾语风 UI 重做

### 第一步 · 配置链路
- BaseUrlNormalizer 支持 wss://（scheme 统一小写、ws:// 拒绝、wss 不做 endpoint 后缀裁剪），新增 6 条单测。
- SettingsRepository 新增 Group.REALTIME，LanguageConfig 增加 realtime + realtimeOptions 字段；version 1→2，旧文件缺字段按默认值自愈（server_vad=true, silence=100, energy=2500, prefix=500），新增 2 条单测。
- 设置页改为每语言 4 组；REALTIME 组含 wss 端点、Key（掩码/显示）、模型、音色、server_vad 开关与三个高级参数；「测试连接」改为 WebSocket 握手等 session.created（8s 超时）。

### 第二步 · 实时语音核心
- core/realtime/RealtimeProtocol.kt（纯 JSON，org.json + java.util.Base64）：session.update / append / commit / clear / response.create|cancel 与服务端 12 类事件解析；10 条单测。
- ai/openai/StepfunRealtimeProvider.kt：OkHttp WebSocket，Authorization: Bearer 握手头（stepfun realtime：wss://api.stepfun.com/v1/realtime，pcm16+Base64）。
- audio/RealtimeAudioController.kt：AudioRecord 16k/mono/PCM16 采集，20ms 帧能量 VAD 门控（复用 VadPolicy，静音 800ms 暂停 append 省计费），AudioTrack 播放 pcm16 delta，interrupt() barge-in。
- MockRealtimeProvider 无凭证降级；AiProviderFactory 增加 realtime(instructions)；Registry 增加 isRealtimeEnabled。
- ui/voice/VoiceChatActivity.kt：全屏深色沉浸页（波形柱、字幕流、计时、准备层、总结层），barge-in 停播。

### UI 重做（拾语设计稿）
- 新色板（paper #FAF7F2 / clay #A34E28 / 语种点缀 日 #C4586B 英 #4A5D9E）、衬线标题、白卡细描边、底部导航 4 项、bg_card/bg_btn_clay/bg_input/bubble_* 等重绘、FieldInput/MiniButton 样式。
- 全部 6 个布局重排；对话列表新增深色实时语音入口卡；新增 activity_voice_chat.xml。
- ui-preview.html 重做为 v4（拾语风，9 屏含实时语音准备/进行中）。

### 结果
- BUILD SUCCESSFUL；321 个单测全绿（305 + 本轮新增 16）；APK 8,058,530 B。
- 排障记录：dexBuilderDebug 的 desugar_graph AccessDenied（疑似 DLP 干扰）→ gradlew --stop + 清理 desugar_graph 后恢复；android:attr/minimumWidth 非法属性导致资源链接失败。

---

## 补充（第三轮）：release 签名版
- 生成 keystore：keystore/lang-partner-release.jks（RSA 2048，validity 10000 天，alias=langpartner，CN=LangPartner/OU=Dev/O=stepx），凭据存于 keystore.properties（store/key 同密码）。
- app/build.gradle.kts：新增 signingConfigs.release（读 keystore.properties，缺失时退化为未签名包）；release  signingConfig 接入；versionCode 2 / versionName 1.0.0；保持 isMinifyEnabled=false（规避 JSON/反射混淆风险）。
- 产物：app/build/outputs/apk/release/app-release.apk（5,606,632 B），另复制到工程根目录 LangPartner-v1.0.0-release.apk。
- 签名核验（apksigner verify --print-certs）：DN=CN=LangPartner, OU=Dev, O=stepx；SHA-256=8ab55206ff9214b762195202de01506a83f4ddfe2231d3b0026151bd36fb2e56。
- 注意：keystore 与 keystore.properties 是发布身份唯一凭据，丢失则无法再发新版到同一应用 ID 下，请务必备份。

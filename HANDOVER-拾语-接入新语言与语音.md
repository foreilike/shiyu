# 拾语 · 交接文档 —— 接入新语言

> 配套文档：`HANDOVER-拾语App.md`（架构与功能总纲，权威）。本文档是**面向下一步工作**的实操手册：
> 你要给拾语接新语言（韩 / 法 / 德 / 俄…）时照本文做即可；也包含 2026-09-22 那轮大改的全部记录。
>
> 本文写于 **2026-09-22**，对应 `master` 分支。

---

## 0. 这份文档怎么用

| 你要做的事 | 看哪节 |
|---|---|
| 先把项目跑起来 | §1 |
| 搞清楚现在有什么、改到哪了 | §2 |
| 改代码前熟悉结构 | §3 |
| **接一门新语言（韩/法/德/俄…）** | **§4** |
| **新语言的词库从哪来、怎么造** | **§5** |
| 别重复踩坑 | §6 |
| 还欠什么 | §7 |
| 数据文件格式规格 | §8 |
| 常见小改动怎么改（配方） | §9 |
| 出问题怎么排查 | §10 |
| 新语言验收清单 | §11 |
| **接一门语言的实操 Checklist（含耗时）** | **§12** |

---

## 1. 30 秒上手

### 1.1 环境（不可协商）

- **JDK 必须用 `D:/a/jbr`**（本机 Android Studio 自带）。不设就会因 Java 版本不对编译失败。
- Android SDK：`D:/AndroidSdk`（`local.properties` 已指好）。
- Kotlin **2.1.0**，AGP 8.7.3，compileSdk 35，minSdk 26。
- 真机：魅族 22（USB 每 1–2 分钟掉一次，是常态，不是故障）。

### 1.2 命令（Git Bash 里照抄）

```bash
cd "/d/AI project/lang-partner"

# 编译
JAVA_HOME="D:/a/jbr" ./gradlew :app:compileReleaseKotlin --console=plain

# 跑单测（388 例）
JAVA_HOME="D:/a/jbr" ./gradlew :app:testDebugUnitTest --console=plain

# 出 release 包（已配签名）
JAVA_HOME="D:/a/jbr" ./gradlew :app:clean :app:assembleRelease --console=plain
# 产物：app/build/outputs/apk/release/app-release.apk

# 装机
"D:/AndroidSdk/platform-tools/adb.exe" install -r app/build/outputs/apk/release/app-release.apk
```

> ⚠️ **加了接口方法/改了 assets 之后，务必 `clean` 再构建。** 见 §6 第 1 条，这是本项目栽过的最大一个坑。

### 1.3 关键标识

| 项 | 值 |
|---|---|
| 应用名 / 包名 | 拾语 / `com.shiyu.app` |
| 版本 | 1.0.0（versionCode 1） |
| 签名 | `keystore/lang-partner-release.jks`，证书 CN=LangPartner |
| 出厂预置配置 | `app/src/main/assets/preset_config.json`（明文 API Key，**勿外传安装包**） |

---

## 2. 当前状态（截至 2026-09-22）

### 2.1 内容规模

| 语言 | 词书 | 词数 | 来源 |
|---|---|---|---|
| 英语 | 基础 / CET4 / CET6 / 考研 / 托福 / 雅思 / GRE | 110 + 39,208 | kajweb/dict（跬步同款） |
| 日语 | N5 / N4 / N3 / N2 / N1 | 8,334 | OpenJLPT（CC-BY-SA，中文为机翻） |

每张卡片带：**词、中文释义、读音（音标 / 假名+罗马音）、例句、例句中文译文**，且**词与例句都能点读**。

> 合规提醒：OpenJLPT 是 CC-BY-SA-4.0，kajweb/dict 授权跬步自己也标注为「上架前待核实」。
> **自用无碍**；公开上架前需在 App 内补数据来源署名。

### 2.2 2026-09-22 这轮改了什么（全部已合并进 master）

**① 修掉「实时语音页进入即闪退」** —— 见 §6 第 1 条，根因很反直觉，务必读。

**② 深度自检整改 8 条**（四路并行审计查出）：
1. `StepfunRealtimeProvider.closedByUser` 补 `@Volatile` + `reconnectTask` 复查它 —— 原来挂断后可能重开一条没人关的连接（实时语音按量计费）。
2. `RealtimeAudioController` 加 `released` 标志，释放后迟到的音频包不再新建 AudioTrack。
3. 静音门控的 `continue` 只在非「按住说话」模式生效。
4. 语音页 `turns++` 移入 `isNotBlank` 块（空白转写不再虚计一轮）。
5. `ChatActivity` 助手轮落盘挪到 `Dispatchers.IO`。
6. 语音页返回键图标换成 chevron、文案对齐行为。
7. 「我的」页头像按 codePoint 取字（emoji 不再切半）。
8. 「我的」页统计磁盘读合并为一次并挪到后台（原来进页面要全目录扫 4 遍，会话多了会 ANR）。

**③ 卡片升级为「发音 + 例句 + 译文」**（参照跬步）：新增 `Card.example/exampleZh`，
`Card.back` 语义统一为「读音」。**英日字段约定此前是相反的，这轮统一了**（见 §3.3）。

**④ 选书功能**：一门语言多本词书，`LanguageModule.books()` 返回 `WordBook` 列表，
当前书存 `AppSettings.activeBooks`，复习页头部胶囊切换，各书进度独立（卡片 id 按书前缀）。

**⑤ 复习改为跬步式自评**：**不再要求打字**（对初学者，「打出片假名读法」不现实）。
正面只给外语词，点卡片揭示释义/读音/例句，再用四级自评（完全不会 / 有点模糊 / 想起来了 / 太简单了）。

**⑥ 新增拼写考试**（进阶，首页第三张入口卡）：从当前词书随机抽 20 词，看释义拼外语词，
逐题判对错、末尾给分。成绩只记本次会话，不写 SM-2 进度。日语假名与汉字写法都算对。

**⑦ 朗读语速**：`Speaker` 统一管理 —— 单词 **0.5**、例句英语 **0.3** / 日语 **0.4**
（都是实测听感调出来的；默认 1.0 对初学者太快）。卡片上有两个喇叭：词一个、例句一个。

**⑧ 实时语音改单键**：中间大按钮点「开始说话」= 打断并开麦，再点「暂停说话」= 推静音让服务端断句。
删掉了旧的「自动 / 按住」两模式和设置页开关。

### 2.3 单测

**388 例全绿**。注意：这个仓库「单测全绿」**不等于「能跑」**——历史上栽过两次，
真机走查抓出过 4 个单测覆盖不到的 bug。**UI 改动必须真机验。**

---

## 3. 架构地图（改代码前必读）

### 3.1 分层铁律

```
ui/     界面。**不得出现任何语言专有符号**（KanaConverter、EnModule、romaji…）
ai/     AI 能力抽象（chat / stt / tts / realtime 四接口）。同上，不得有语言专有符号
core/   纯逻辑，可单测。语言差异全部收口在 core/lang/
data/   存储。被 SourcePolicyTest 禁止 import android.*（保持纯 JVM 可测）
audio/  平台音频（AudioRecord / AudioTrack）
```

`app/src/test/java/com/shiyu/app/SourcePolicyTest.kt` 会**扫源码强制这条规矩**
（`ui/` 与 `ai/` 里出现 `KanaConverter` / `romaji` / `EnModule` 等词直接测试失败）。
加新语言时，语言细节只能写在 `core/lang/<语言>/` 里。

被禁的符号（**完整列表**，来自 `SourcePolicyTest`）：`KanaConverter`、`KanaNormalizer`、
`EnTokenizer`、`EnSentenceSplitter`、`EnNormalizer`、`JaModule`、`EnModule`、`toHiragana`、
`romaji`、`katakana`、`hiragana`、`tokenize`。

⚠️ 注意 `romaji` / `tokenize` 这类**通用词也在禁用之列** —— 新语言的模块要引用它们时，
只能在 `core/lang/` 里用，别让这些名字漏进 `ui/` 或 `ai/`（比如 UI 代码里命名一个变量叫
`romajiText` 就会让测试挂掉）。

### 3.2 包结构

```
com.shiyu.app
├─ AppGraph.kt              服务定位器：各 store / registry 从这里取
├─ ai/                      四能力接口 + Registry
│  ├─ AiProvider.kt         ChatProvider / SpeechToTextProvider / TextToSpeechProvider / AiProviderFactory
│  ├─ RealtimeVoiceProvider.kt   实时语音接口（含 Listener 回调）
│  ├─ AiProviderRegistry.kt      按语言取工厂 + 降级规则（isChatEnabled / isRealtimeEnabled）
│  ├─ openai/               三个 HTTP endpoint 的实现
│  └─ mock/                 未配置时的本地假实现
├─ audio/RealtimeAudioController.kt   采集 / VAD 门控 / 播放
├─ core/
│  ├─ answer/AnswerJudge          拼写判题（考试用）
│  ├─ audio/VadPolicy             离线静音裁剪
│  ├─ chat/ReplyParser            AI 回复三段式解析
│  ├─ context/ContextTrimmer      上下文裁剪
│  ├─ lang/                       ★ 语言抽象，加语言全在这里
│  ├─ net/                        HTTP 传输 / baseUrl 规范化 / 错误分类
│  ├─ realtime/RealtimeProtocol   实时线协议（事件名映射，换厂商改这里）
│  ├─ srs/                        Sm2Scheduler（复习间隔）/ DailyQuota（每日配额）
│  └─ stats/Achievements          成就徽章
├─ data/
│  ├─ asset/       AssetRepository（读 assets，带缓存）/ PresetConfig（出厂预置）
│  ├─ model/       Card / SessionRecord / DayStat / AppSettings / ProviderConfig / Scenario…
│  ├─ secret/      SecretStore / KeystoreSecretStore（API Key 加密存储）
│  └─ store/       SettingsRepository / StudyStore / SessionStore / DailyStatsStore / AtomicFile
└─ ui/             各页面 + Speaker（TTS）+ LangStyle（语言的 UI 表现）+ BaseActivity
```

### 3.3 ★ `Card` 字段约定（2026-09-22 统一，务必照此）

此前英语和日语是**相反**的（英语把释义放正面、单词放背面；日语反过来），
那种约定只在「打字作答」时讲得通。改成「看词回忆释义」的自评模式后必须统一：

| 字段 | 含义 |
|---|---|
| `front` | 正面展示的**外语词**（英语单词 / 日语汉字或假名） |
| `frontSub` | 副标题（`"A1 · n."` / `"JLPT N5"`） |
| `back` | **读音**（英语音标 / 日语假名） |
| `backSub` | 罗马音（日语）；英语为空 |
| `meaningZh` | 中文释义（**揭示后**才显示） |
| `example` / `exampleZh` | 例句与中文译文（揭示后显示） |
| `speakText` | 点喇叭朗读的文本 |
| `exampleBlank` | 例句挖空（**历史字段，自评模式已不展示**） |

### 3.4 资产文件（`app/src/main/assets/`）

| 文件 | 用途 |
|---|---|
| `en_phonemes.json` / `kana.json` | 发音表（英语 IPA / 日语五十音） |
| `en_words.json` | 英语「基础」词书（110 词） |
| `en_{cet4,cet6,kaoyan,toefl,ielts,gre}.json` | 英语六本词书，共 39,208 词 |
| `ja_n{5,4,3,2,1}.json` | 日语五级词书，共 8,334 词 |
| `scenarios_{en,ja}.json` | 对话场景 |
| `preset_config.json` | 出厂预置（明文 API Key，**勿外传安装包**） |

### 3.5 当前词书是怎么选出来的

```
LanguageModule.books() → List<WordBook>(id, langId, title, asset)
        ↓
SettingsRepository.activeBook(lang) → 存的 bookId（没设过 → 空串）
        ↓
AppGraph.activeBook(module) → 命中则用，否则回退 books().first()
        ↓
AppGraph.activeDeckJson(module) → AssetRepository 读该 asset
```
复习页 / 首页 / 课程页都走 `AppGraph.activeDeckJson(module)`，**不要绕过它直接读 assets**。

---

## 4. ★ 接入一门新语言（韩 / 法 / 德 / 俄 …）

设计稿里 KO / ES / FR 已经在「即将支持」灰态（`LanguagesActivity.COMING`）。
接一门语言的完整清单如下，**顺序不要变**（后面的步骤依赖前面的）。

### 步骤 1：加语言标识 `core/lang/LangId.kt`

```kotlin
enum class LangId(val tag: String, val displayName: String, val sttLanguage: String) {
    JA("ja", "日语", "ja"),
    EN("en", "英语", "en"),
    KO("ko", "韩语", "ko")      // ← 新增
}
```

> 🔴 **同时必须改 `fromTag`。** 现在是硬编码的：
> ```kotlin
> fun fromTag(tag: String?): LangId = if (tag == "en") EN else JA   // ← 加语言后这里就错了
> ```
> 不改的话，新语言的 `tag` 会被**静默识别成日语**（历史记录、进度全串到日语桶里）。
> 正确写法：
> ```kotlin
> fun fromTag(tag: String?): LangId = entries.firstOrNull { it.tag == tag } ?: JA
> ```

### 步骤 2：写语言模块 `core/lang/<语言>/XxModule.kt`

照抄 `JaModule.kt` / `EnModule.kt`，实现 `LanguageModule` 的 11 个成员：

| 成员 | 作用 | 备注 |
|---|---|---|
| `id` | 返回对应 `LangId` | |
| `answerHint` | 输入框占位（"拼写英文单词"） | 考试页在用 |
| `replyTag` | AI 回复标签（"EN"/"JA"） | |
| `normalizeForCompare(s)` | 判题前归一化（大小写/全半角/假名） | 考试判题用 |
| `assetNames()` | `Triple(chart, deck, scenarios)` 文件名 | |
| `books()` | 该语言的可选词书列表 | 见 §3.5 |
| `buildChart(json)` | 发音表条目 | |
| `buildDeck(json)` | 词书 → `List<Card>` | **照 §3.3 的字段约定** |
| `buildScenarios(json)` | 对话场景 | 可直接复用 `ScenarioParser` |
| `splitSentences(text)` | 分句朗读 | 按该语言的句末标点切 |
| `examAnswers(card)` | 拼写考试可接受的答案 | 默认 `listOf(card.front)`；若该语言「写法和读法不同」应覆写（日语就是覆写成假名+汉字都收） |

### 步骤 3：注册 `core/lang/LanguageRegistry.kt`

```kotlin
private val modules: Map<LangId, LanguageModule> = mapOf(
    LangId.JA to JaModule(),
    LangId.EN to EnModule(),
    LangId.KO to KoModule()      // ← 新增，漏了会在切语言时 error("unsupported lang")
)
```

### 步骤 4：🔴 补两处「编译器不会提醒你」的分支

这两处**不会报编译错**，忘了就是静默 bug：

1. **`ui/Speaker.kt` 的 `setLanguage`**
   ```kotlin
   val target = if (langId == LangId.JA) Locale.JAPANESE else Locale.ENGLISH
   //                                                                  ↑ 新语言会静默用英语音色念
   ```
   改成 `when (langId) { JA -> Locale.JAPANESE; EN -> Locale.ENGLISH; KO -> Locale.KOREAN }`。
   （历史上本项目就栽过 TTS 用错语言念外文的坑。）

2. **`LangId.fromTag`**（见步骤 1）。

### 步骤 5：会**编译报错**的地方（编译器帮你抓，逐个补分支即可）

- `ui/LangStyle.kt` 的 `accentRes`：`when (lang)` 是穷尽表达式，加枚举后必编译失败 → 加分支。
- 需要在 `res/values/colors.xml` 里加 `lang_ko` 之类的颜色（`COMING` 列表已经在引用 `R.color.lang_ko`）。

### 步骤 6：把语言从「即将支持」挪出来

`ui/langs/LanguagesActivity.kt` 的 `COMING` 列表删掉对应条目（它现在只是灰态占位）。

### 步骤 7：准备资产（三份 JSON）

`assets/` 下按 `assetNames()` 返回的文件名放好。字段格式**参考现有文件照抄**：
- 词书：见 §8.1
- 发音表：见 `kana.json` / `en_phonemes.json`
- 场景：见 `scenarios_ja.json`

> 大 JSON **一定用脚本生成，不要手写**（本机大块手写会乱码）。
> 日语那 8,334 词的翻译流程可参考 `docs/ja-translation/`：里面有 PRD、
> 翻译输入文件、以及按 id 合并回填的 `merge.py`，整套是现成可复用的。

### 步骤 8：检查这些按语言分桶的地方

加语言后要确认它们不会互相串（`LangId.entries` 已经被这些地方消费）：

- `SettingsRepository` — 每门语言各自 8 组 API 配置（`AppSettings.langs`）
- `AppSettings.activeBooks` — 每门语言当前选的书
- `DailyStatsStore` — `reviewedByLang` 按语言分桶
- `StudyStore` — 进度按 `(lang, cardId)`
- `SessionStore` — 会话按 `langId`
- 「我的」页统计 — 按 `LangId.entries` 汇总
- `preset_config.json` — **预置配置对所有语言生效**，新语言会自动拿到同一套网关

### 步骤 9：测试

- `LanguageModuleTest` 里 `registry_returns_modules` / `lang_id_metadata` 等断言要补新语言。
- `SourcePolicyTest` 会拦住「把语言细节写进 ui/ 或 ai/」。
- 加完跑 `:app:testDebugUnitTest`，再**真机走查**（切语言 → 首页数字 → 复习一卡 → 考试一题）。

### 4.10 ★ 新语言的卡片字段怎么填

`Card` 的字段约定见 §3.3。关键是**照哪门现有语言抄**：

| 目标语言 | 抄谁 | 字段映射 | 理由 |
|---|---|---|---|
| **法语 / 德语 / 俄语 / 西语** 等拉丁、西里尔字母语言 | **`EnModule`** | `front`=单词、`back`=IPA 音标、`backSub`=""、`meaningZh`=中文、`example`/`exampleZh`=例句与译文 | 和英语同构：看到词 → 回忆中文 |
| **韩语** | **`JaModule`** | `front`=韩文（한글）、`back`=罗马字转写、`backSub`=""、`meaningZh`=中文 | 韩文本身表音，需要一个「怎么读」的转写位；与日语同构 |

> 一句话原则：**`front` 放「你要学习者看到就想起意思」的那个词形，`back` 放读音/转写，`meaningZh` 放中文。**
> 拼写考试的可接受答案由 `examAnswers()` 决定 —— 拉丁/西里尔语言用默认的 `front` 即可；
> 韩语建议**韩文与罗马字都收**（参考日语「假名与汉字都算对」的做法）。

### 4.11 ★ 各语言特有的坑（接之前先看）

**① 变音符号与 Unicode 归一化（法/德/俄 最大的坑）**

字符串比较是**码点级**的。法语 `é` 可能是单个码点 `U+00E9`（NFC），也可能是
`e` + `U+0301`（NFD，组合字符）——**手机上输入法打出来的和词库文件里存的很可能不是同一种**，
于是判题会假阴性：明明拼对了却判错。

**在 `normalizeForCompare` 里先做归一化**（`java.text.Normalizer`，`Form.NFC`），
再接你原有的处理。然后再决定「要不要忽略重音」——

- **建议不忽略**：法语 `é/è/ê`、德语 `ä/ö/ü`、俄语 `ё` 都是**独立字母**，拼错就是拼错；
- 但如果实测下来用户频繁因为重音被卡，可以退一步做「重音不敏感」比较（去掉组合记号再比）。

**② 各语言的具体音变**

- **德语**：`ß` 与 `ss` 建议视为等价（历史上就是一回事，输入法也可能打不出 `ß`）；名词首字母大写有语法意义。
- **法语**：`œ`（如 `cœur`）常被写成 `oe`，建议等价；连字符、撇号（`l'eau`）在归一化里要去掉。
- **俄语**：`ё` 常被写成 `е`，建议等价。
- **大小写**：这些语言都该在归一化时转小写（德语名词大写在判题上无需区分）。

**③ 分句与分词**

法/德/俄用西文标点，可直接复用 `EnSentenceSplitter` 的逻辑。但**别直接 import `En*` 类**
（名字会误导后来人，也不符合 §3.1 的分层意图）——把公共逻辑抽成一个
`core/lang/common/WesternSentenceSplitter`，让 EN 和新语言都调它。
分词同理（这些语言都有空格分词，`EnTokenizer` 的思路可复用）。

**④ TTS 与语音识别**

- TTS locale：`Locale.KOREAN` / `Locale.FRENCH` / `Locale.GERMAN` / `Locale.RUSSIAN`。
  **记得在 `Speaker.setLanguage` 加分支**（见步骤 4，这是编译器不提醒的静默坑）。
- `sttLanguage` 填 `ko` / `fr` / `de` / `ru`（语音识别接口的 language 参数）。
- 设备**不一定装了对应语音包** —— `Speaker` 已有 `onLanguageUnavailable` 回调会 Toast 提示，不用另做。
- 语速：新语言先沿用 `SLOW_RATE = 0.5`；例句语速按语言分（现有 EN 0.3 / JA 0.4），
  新语言接完**用耳朵调一次**再加进 `Speaker` 的分支。

**⑤ 语法性别 / 变位（法/德/俄 绕不开的产品问题）**

`Card` 是「一词一义」的结构，但德语名词有性别（`der/die/das`）、法语有阴阳性、
俄语有六个格、动词还有变位。**这是产品决策不是技术问题**，建议：

- 名词**把冠词写进 `front`**（如 `der Hund`），让学习者从一开始就记住性别；
- 动词先只收原形（不定式），变位作为后续增强；
- 别急着改 `Card` 结构 —— 现有字段（`frontSub` / `example`）已经够承载这些信息。

**⑥ 未做但迟早要面对**

`ui/langs/LanguagesActivity.kt` 的 `COMING` 里还有韩/西/法三个灰态占位，接完一门就从里面删一门。
另外 `res/values/colors.xml` 里的 `lang_ko` / `lang_es` / `lang_fr` **已经定义好了**，
接新语言不用新建颜色。

---


### 4.12 端到端示例：接韩语（模块骨架）

以韩语为例把 §4.10 的映射落地。**这是骨架**，`buildDeck` / `buildChart` 照抄现有模块即可：

```kotlin
package com.shiyu.app.core.lang.ko

class KoModule : LanguageModule {

    override val id: LangId = LangId.KO
    override val answerHint: String = "拼写韩语单词"   // 考试页输入框占位
    override val replyTag: String = "KO"

    /**
     * 判题归一化。**先做 Unicode NFC** —— 韩文/拉丁的变音符既可能是单码点，
     * 也可能是组合字符；手机上打出来的与词库里存的很可能不是同一种码点序列，
     * 不归一化就会「明明拼对了却判错」。
     */
    override fun normalizeForCompare(s: String): String {
        val nfc = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)
        val sb = StringBuilder()
        for (ch in nfc) {
            if (ch.isWhitespace()) continue
            if (ch in PUNCT) continue
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /** Triple(发音表, **默认词书**, 场景) —— 第二个要与 books() 的第一本一致 */
    override fun assetNames(): Triple<String, String, String> =
        Triple("ko_chart.json", "ko_topik1.json", "scenarios_ko.json")

    override fun books(): List<WordBook> = listOf(
        WordBook("topik1", LangId.KO, "TOPIK I",  "ko_topik1.json"),
        WordBook("topik2", LangId.KO, "TOPIK II", "ko_topik2.json")
    )

    /** 韩语：front=韩文、back=罗马字转写，两种写法都算对（同日语「假名与汉字都算对」） */
    override fun examAnswers(card: Card): List<String> =
        listOf(card.front, card.back).filter { it.isNotBlank() }.distinct()

    /**
     * 词书 JSON → Card。映射规则见 §4.10：
     *   front = 韩文原词       frontSub  = 级别/词性
     *   back  = 罗马字转写      backSub   = ""（韩语不需要第二个读音位）
     *   meaningZh = 中文释义    example / exampleZh = 例句与译文
     */
    override fun buildDeck(json: String): List<Card> { /* 照抄 EnModule，换字段名 */ }

    override fun buildChart(json: String): List<ChartEntry> { /* 照抄 kana.json 那套 */ }

    override fun buildScenarios(json: String): List<Scenario> = ScenarioParser.parse(json)

    /** 西文标点分句 */
    override fun splitSentences(text: String): List<String> = WesternSentenceSplitter.split(text)
}
```

> 🔧 **顺手该做的一处小重构**：`ScenarioParser` 现在放在 `core/lang/ja/` 包里，
> 但它**语言无关** —— 现在的 `EnModule` 就已经在跨包 import
> `com.shiyu.app.core.lang.ja.ScenarioParser`（还有那个 `str` JSON 取值助手）。
> 接第三门语言之前，建议把它们挪到 `core/lang/common/`，
> 否则每加一门语言就多一处这种别扭的跨包引用。
> `WesternSentenceSplitter` 同理，应放 `common/` 由 EN 和新语言共用。

---

### 4.13 改动文件速查表

加一门语言要动的**全部**文件（★ = 只写本语言内容；⚠️ = 容易漏）：

| 文件 | 改什么 |
|---|---|
| `core/lang/LangId.kt` | ⚠️ 加枚举项 **+ 把 `fromTag` 改成通用查找**（步骤 1） |
| `core/lang/<语言>/XxModule.kt` | ★ 新建，实现 11 个成员（步骤 2 / §4.12） |
| `core/lang/<语言>/XxNormalizer.kt` | ★ 新建，判题归一化 |
| `core/lang/LanguageRegistry.kt` | 注册新模块（步骤 3） |
| `ui/Speaker.kt` | ⚠️ `setLanguage` 加 locale 分支（步骤 4） |
| `ui/LangStyle.kt` | `accentRes` 的 `when` 加分支（编译器会报错提醒） |
| `ui/langs/LanguagesActivity.kt` | 从 `COMING` 列表移除 |
| `res/values/colors.xml` | 多数已存在（`lang_ko`/`lang_es`/`lang_fr`），缺了才加 |
| `assets/` | ★ 发音表 + 词书（可多本）+ 场景，共三份 JSON |
| `app/src/test/.../LanguageModuleTest.kt` | 补新语言的模块与 `LangId` 断言 |
| `app/src/test/.../SourcePolicyTest.kt` | **不用改**，但新代码要能过它（禁用词表见 §3.1） |

---

## 5. 新语言的词库从哪来、怎么造
接一门新语言，**代码半天就改完，真正的工作量在词库**。英语和日语这次是怎么来的，可以照搬：

### 5.1 两条现成的路

**路 A：直接找带中文的开放词库（最省事，可遇不可求）**
- 英语就是这么做的 —— `kajweb/dict`，六本考试词书每条都自带**音标 + 释义 + 例句 + 例句中文译文**，导入即用，零翻译。
- 这类资源主要存在于「英语考试词书」，其他语种很少见。

**路 B：找只有外文的开放词库，中文自己补（通用解）**
- 日语就是这么做的 —— `OpenJLPT` 给了 N5–N1 的**词、假名读音、真实例句**，但释义/例句译文只有英文。
- 中文层由模型翻译补齐（见 5.3）。

### 5.2 候选数据源（**授权请自行核实**，尤其要公开发布时）

| 来源 | 说明 | 许可 |
|---|---|---|
| **kaikki.org** | Wiktionary 的**机器可读导出**，按语言分文件、JSON 行格式，**每条含词性 / 释义 / IPA / 例句** —— 多语种都能用，脚本可直接处理 | 继承 Wiktionary，CC BY-SA |
| **Wiktionary** | 全语种覆盖，释义+例句+发音都有 | CC BY-SA |
| **Tatoeba** | 大量真实例句（日语的例句就来自这里） | CC-BY |
| 分级考试官方/社区词表 | 韩语 **TOPIK**、法语 **DELF/DALF**、德语 **歌德 A1–C2**、俄语 **ТРКИ** —— 天然带级别，方便做成「多本书」 | 各自不同，务必核实 |
| Lexique（法语） | 开放，含词频 / 词性 / 音标 | 需核实 |

> **建议顺序**：先试 kaikki.org 拿「词 + IPA + 例句」，再用考试大纲词表**筛出级别**（这样能像英语那样分出多本书）。

### 5.3 复用现成的翻译流水线 —— `docs/ja-translation/`

日语那 8,334 词的中文就是这套流程做出来的，**它是语种无关的，换个输入就能用在韩/法/德/俄上**：

```
docs/ja-translation/
├─ PRD-日语翻译.md          翻译任务书（字段契约 / 质量标准 / 分批建议 / 自检清单）
├─ input/ja_n{5..1}.json    翻译输入：id + 外文 + 英文释义 + 外文例句 + 英文例句
├─ output/ja_n{5..1}.out.json  模型产出：id + 中文释义 + 例句中文译文
└─ merge.py                 按 id 回填合并 → 生成 assets/ja_*.json（含覆盖率/空值校验）
```

**换一门语言的做法**：
1. 抓词表 → 转成 `input/xx_all.json`（字段照抄 `ja_n5.json` 的结构，**id 必须唯一且稳定**）；
2. 把 `PRD-日语翻译.md` 里的语种说明改成目标语言，交给模型跑（或本机小模型批量翻）；
3. 改 `merge.py` 里的 `LEVELS` 和文件名，指向新语言 → 输出 `assets/xx_all.json`。

> 这套流程的关键设计：**只让模型回 `{id, meaningZh, exampleZh}` 三个字段**，一进一出、id 不变。
> 合并时按 id 对齐，缺的/错位的会直接报出来 —— 这是让「机翻 + 合并」不出错的核心。

### 5.4 罗马字 / 转写怎么写

韩语需要一个「韩文 → 罗马字」的转写位（照 §4.10 抄 `JaModule`）。
韩语有标准的**文化观光部 2000 年式罗马字**，规则明确、可脚本实现 ——
参照日语那套 `kana → romaji` 的写法（那次是在导入脚本里内嵌了一张假名映射表，逐字符转换 + 拗音/促音/长音特判）。
**建议同样用脚本生成，别手工录**。

### 5.5 分级怎么做成「多本书」

拾语已经支持一门语言多本词书（`books()` + 选书页）。新语言若有多档考试，直接一档一本书：
- 韩语：TOPIK I（1–2级）/ TOPIK II（3–6级），或按 1–6 级各一本
- 法语：A1 / A2 / B1 / B2 …
- 德语：A1 / A2 / B1 / B2 / C1
- 俄语：A1 / A2 / B1 / B2 …

**卡片 id 记得带书前缀**（如 `topik1_00001`），保证跨书不串进度 —— 这是现有约定的硬要求。

---

## 6. 踩过的坑（照做能避雷）

### 6.1 🔴🔴 Kotlin 接口默认方法 + 增量编译 = 运行时 `AbstractMethodError`

**这是本项目最凶险的一个坑，症状是「实时语音页进入即闪退」，但根因完全不在语音代码里。**

**现象**：服务端回 `session.created` 时，`listener?.onReady()` 抛
`java.lang.AbstractMethodError: abstract method "...RealtimeVoiceProvider$Listener.onReady()"`，
崩在 OkHttp 线程上没人接 → 整个进程挂掉。

**根因**：`Listener.onReady()` 是**带默认实现的接口方法**。Kotlin 2.1 的 jvm-default 默认是 `disable` 模式，
这种方法在字节码里会变成「**abstract 方法 + 一个 `Listener$DefaultImpls`**」，
没写它的实现类要靠**编译器生成的桥接方法**去转发。而**增量编译没有重编那个匿名实现类**时，
桥接方法就缺失了 —— 编出来的 APK 里，接口有 `onReady`、调用点有 `listener?.onReady()`，
但实现类里没有这个方法。

**怎么发现的**：把手机上的 APK pull 下来 `dexdump`，数实现类的虚方法表，发现确实没有 `onReady`。

**怎么修的**：在匿名 listener 里**显式写一个** `override fun onReady() {}` ——
这个类就不再依赖编译器生成的桥接方法了。

**规矩（重要）**：
- **凡是给接口加「带默认实现的方法」，就要考虑实现类是否会被增量编译漏掉。**
- 更稳的做法：**接口里的默认方法，实现类也显式覆写一遍**（哪怕实现是空的）。
- **改了接口 / 加了方法之后，一定要 `./gradlew :app:clean :app:assembleRelease`**，别用增量产物。
- 排查手法备忘：`dexdump` + grep 实现类的虚方法表，能机械验证「桥接方法在不在」。
  本次顺手把**整个 APK** 的所有 `$DefaultImpls` 实现类都扫了一遍，确认没有第二处。

### 6.2 真机相关

- **魅族 22 的 USB 每 1–2 分钟掉一次**，是常态。`adb devices` 空了就重试/重插。
- **`input tap` 连点常丢**：先用 `uiautomator dump` 拿真实坐标再点，点完复核落点。
  另外 dump 有时抓到的是**上一个窗口**的层级，必要时多 dump 一次。
- **读落盘数据要装 debug 包**（release 不可调试，`run-as` 用不了）。

### 6.3 音频 / TTS

- **语速是引擎「状态」不是每句参数。** 读完慢速例句不重设，下一个单词也会变慢。
  所以 `Speaker.speak()` **每次都显式 `setSpeechRate`**。
- **Android 11+ 用 TTS 必须在 manifest 声明 `<queries>` 的 `TTS_SERVICE`**，
  否则部分 ROM 上引擎不可见、朗读**静默失败**（已经加好了，别删）。
- TTS 的 `setLanguage` 要在引擎就绪后再应用，否则会被引擎初始化覆盖（`Speaker` 里已缓存 locale 处理）。

### 6.4 工程约定

- **大块代码 / JSON 一律用脚本生成，不要手写**：本机大块多行写入会被打乱，
  写完立刻 `python -c "import json; json.load(...)"` 校验。
- **深色页不要用浅色页的资源**（这个项目反复栽过：白底卡片、近白波形柱…），
  深色页有专用的 `bg_*_dark`。
- **Material3 的 `<Button>` 会无视 `android:background`**（被换成 MaterialButton），
  会做出「同色字印同色底」的隐形按钮 —— 用样式或用 `TextView`。
- `.bat` 要存 **GBK 无 BOM**；PowerShell 脚本要存 **UTF-8 带 BOM**（方向相反，别弄反）。

---

## 7. 已知问题与待办（诚实清单）

### 7.1 数据质量

- **日语 8,334 词的中文释义与例句译文是机翻**，只做了**抽检**（N5–N4–N3 抽过，N2–N1 也抽过），
  **没有逐句校对**。抽检结果是好的（惯用语、上下文义都译对了），但长尾里一定有错。
  要更稳的话：按 §5.3 的 PRD 让更强的模型重跑一遍、按 id 合并覆盖即可。
- 英语词库里有少量条目**缺音标或缺例句**（TOEFL/GRE 各约一成），卡片会优雅留空，不影响使用。

### 7.2 合规（**公开发布前必须处理**）

- `OpenJLPT` 是 **CC-BY-SA-4.0**：App 内需要**数据来源署名**，衍生数据也需同样标注。
- `kajweb/dict`（英语六本词书）的授权**跬步自己都标注为「上架前待核实」**，用之前要确认。
- 目前 App 内**没有**任何来源说明页 —— 自用无碍，上架前补一个。

### 7.3 代码上的欠账

- `Card.exampleBlank` 与 `AnswerJudge`：前者在自评模式下**已无展示入口**（历史字段）；
  后者现只被拼写考试使用。若将来不做拼写模式了，两者可一并清理。
- **首页与复习页仍有少量主线程磁盘读**：首页是单次全目录扫描（比原先「我的」页的 4 遍轻得多），
  复习页 `flushStudyTime` 是**有意保留同步写**的（挪到异步反而有丢记录风险）。会话攒到几百条后可能变慢。
- **没有做真正的 AEC（回声消除）**，靠三层逻辑规避：按住说话 / 录音期间抑制朗读 / 打断。
  开着外放又用自动模式时，仍可能把 AI 的声音录进去。
- 版本号仍是 **1.0.0 / versionCode 1**，一直没动过。

### 7.4 下一步（你要做的）

1. 选一门语言（建议**先韩语**：TOPIK 分级清晰、有标准罗马字、词表好找）；
2. 按 §5 把词库搞出来（这是主要工作量）；
3. 按 §4 的 9 步接代码（半天）；
4. **真机走查**：切语言 → 首页数字 → 复习一卡（点开看释义/例句/朗读）→ 考试一题 → 切回原语言确认没串。

---

## 8. 附：数据文件格式规格

### 8.1 词书（`assets/en_*.json` / `assets/ja_*.json`）

数组，每元素一个卡片。**英语**：

```json
{
  "id": "cet4_00001",
  "word": "abruptly",
  "ipa": "/əˈbrʌptli/",
  "pos": "adv.",
  "meaningZh": "adv. 突然地",
  "example": "The path ends off abruptly.",
  "exampleZh": "这条路突然到头了。",
  "book": "CET4"
}
```

**日语**：

```json
{
  "id": "n5_0001",
  "kanji": "私",
  "kana": "わたし",
  "romaji": "watashi",
  "meaningZh": "我",
  "example": "あれは私の本です。",
  "exampleZh": "那是我的书。",
  "jlpt": "N5"
}
```

> 字段名不是硬约定 —— 解析在各自的 `XxModule.buildDeck()` 里，**新语言可以定义自己的字段**，
> 只要 `buildDeck` 把它映射到 §3.3 的 `Card` 结构即可。

### 8.2 设置与进度（运行时，非 assets）

| 文件 | 内容 |
|---|---|
| `secure_settings.bin` | 加密的配置：8 组 API 配置、`activeLang`、**`activeBooks`（每语言当前词书）**、昵称、每日目标、开关 |
| `study_progress.json` | 每门语言的 SM-2 进度，key 是卡片 id |
| `sessions/<id>.json` | 对话/语音会话记录（含 `langId`，按语言分桶统计） |
| `daily_stats.json` | 每日统计（`reviewedByLang` 按语言分桶） |

「清空全部数据」**不会清 API 配置**（这条是刻意的：清学习记录的人不该丢掉 50 位 Key）。

### 8.3 出厂预置（`assets/preset_config.json`）

首次启动、且本地一份配置都没有时写入。**对所有语言生效** —— 新语言会自动拿到同一套网关。
里面是**明文 API Key**：只适合自用/内部分发，**不要外传安装包**。

---

## 9. 常见改动配方

想改这些小地方时，直接从这张表找入口。

### 9.1 调整每日新词 / 复习量
改 `core/srs/DailyQuota.kt`。**注意**：首页与复习页都从这里派生，改一处两边同步。
别绕过它自己算——历史上出现过「首页说 20 张、复习页说 0 张」的两套真相。

### 9.2 改朗读语速
改 `ui/Speaker.kt` 的三个常量：`SLOW_RATE`（单词）、`EXAMPLE_RATE_EN`、`EXAMPLE_RATE_JA`。
**这是唯一改语速的地方**，各页面都调 `Speaker`，没有第二处。
例句语速按语言分，加语言时在 `exampleRateFor` 加分支。

### 9.3 加一档自评 / 改自评文案
- 文案：`res/values/strings.xml` 的 `quality_forgot` / `quality_vague` / `quality_recalled` / `quality_easy`
- 档位本身：`ui/review/ReviewActivity.kt` 里 `btnQ2..btnQ5` → `onRate(quality)`，
  quality 直接喂给 `Sm2Scheduler.review(...)`（SM-2 的 quality 取值见 `core/srs/Sm2Scheduler.kt`）。
  加减档位要同时改布局 `activity_review.xml` 的 `ratingRow`。

### 9.4 加一个成就徽章
1. `core/stats/Achievements.kt` —— 加 `Id` 枚举项 + 在 `evaluate` 里加一条判定；
2. `ui/profile/ProfileActivity.kt` 的 `BADGE_META` 映射 —— **必须加**，
   否则该徽章不显示（代码用 `?:` 兜底，不会崩，只是少显示一个）；
3. `res/values/strings.xml` 加徽章名与描述。

### 9.5 改拼写考试题量 / 行为
`ui/exam/ExamActivity.kt` 的 `QUESTION_COUNT`（当前 20）。
抽题规则是「过滤掉没有中文释义的卡 → 洗牌 → 取前 N」，改这里即可。

### 9.6 加一本新词书（语言已有）
只需要两步，**不用改任何 UI**：
1. 数据文件放进 `assets/`；
2. 在该语言的 `XxModule.books()` 里加一条 `WordBook(...)`。
   选书页与复习页会自动出现新书。**卡片 id 记得带书前缀**（跨书唯一）。

### 9.7 改语伴名字 / 语言配色
`ui/LangStyle.kt`：`partnerName()`（现在两门语言都是 `yoyo`）、`accentRes()`。

### 9.8 加一个设置开关
1. `data/model/AppSettings.kt` 加字段（**给默认值**，老配置文件就能自愈）；
2. `data/store/SettingsRepository.kt`：加存取方法 + 在 `parse` / `toJson` 里读写
   （**后加字段必须能容忍缺失**，这是本项目的既定约定）；
3. `res/layout/activity_profile.xml` 加一行 + `ProfileActivity` 接线。

---

## 10. 排障速查表

| 症状 | 最可能的原因 | 怎么办 |
|---|---|---|
| **语音页一进去就闪退** | Kotlin 接口默认方法的桥接在增量编译下丢失 → `AbstractMethodError` | 见 §6.1：实现类显式 `override` + `clean` 重新构建 |
| 实时语音「连上了但 AI 一声不吭」 | 推流采样率不是 24kHz（服务端不报错、静默当没人说话） | 查 `RealtimeAudioController.SAMPLE_RATE` 必须是 `24000` |
| 「测试连接」一直转圈 | 模型名非法 / Key 无效 —— 握手成功 ≠ 会话可用 | 看 logcat 有没有 `session.created`；`onReady` 才是真连上 |
| 朗读**完全没声音** | 设备缺该语言语音包 / 忘了 manifest 的 `<queries>` TTS 声明 | 看有没有「缺少语音包」Toast；查 `AndroidManifest.xml` |
| 朗读**用错语言**念 | `Speaker.setLanguage` 的时机或分支问题 | §4 步骤 4；注意它是**静默** bug，不报错 |
| 新语言的记录跑进了日语 | `LangId.fromTag` 没改成通用查找 | §4 步骤 1，这是硬编码遗留 |
| 首页与「我的」页数字对不上 | 统计有两套真相 | 两边都必须从会话文件 / `DailyQuota` 派生，见 §9.1 |
| 卡片上有一条空白 | 该卡本来就没有例句或译文 | **正常**，代码已做容错（空则隐藏，不留空行） |
| `input tap` 点了没反应 | 魅族 22 上连续点击常丢 | 先 `uiautomator dump` 拿真实坐标再点，点完复核 |
| 单测全绿但装到手机上崩 | 单测覆盖不到 UI | 这个项目**栽过两次**，UI 改动必须真机验 |
| 构建报找不到符号 / 行为诡异 | 用了增量产物 | `./gradlew :app:clean` 再来 |

---

## 11. 新语言验收清单（真机走查）

接完一门新语言，**逐条点一遍**才算完成：

- [ ] 课程页能在语言列表里看到新语言，点进去能切换
- [ ] 切过去后，首页的语伴名、语言名、配色都跟着变
- [ ] 首页「今日待复习 / 对话轮数」没有串到其它语言的数字
- [ ] 复习页：正面只显示外语词（**不是中文**）
- [ ] 点卡片 → 翻面揭示：中文释义 + 读音 + 例句 + 例句中文译文都正确
- [ ] 卡片上的喇叭能读**正确语言**的音（不是英语音色）
- [ ] 「听例句」能读整句
- [ ] 选书页能列出该语言的所有词书，切换后卡片确实换了
- [ ] 自评四个按钮点下去能推进到下一张，进度正常累加
- [ ] 拼写考试：看释义能拼出词 → 判对 / 判错都正确 → 能走到结算页
- [ ] 实时语音：能连上、AI 能开口、能打断（**别一进页面就崩**）
- [ ] 场景对话能进、AI 用新语言回
- [ ] **切回原来的语言，一切照旧**（没被新语言的改动影响）
- [ ] 杀掉进程重开，配置与进度都还在
- [ ] 「我的」页各项统计数字合理（没有负数、没有暴涨）
- [ ] `./gradlew :app:testDebugUnitTest` 全绿

---

## 12. 附：接一门新语言的实操 Checklist（以韩语为例）

> 把 §4 的清单摊成可打勾的待办，**带预估耗时和验收标准**。
> 总预估：**代码 4–6 小时可跑通**；真正的时间花在词库与翻译上（取决于目标词量）。

### 阶段 0：开工前的决策（约 0.5 小时）

- [ ] **确认目标语言与分级**：韩语建议按 **TOPIK I（1–2 级）/ TOPIK II（3–6 级）** 分，各做一本词书
- [ ] **确认字段映射**：韩语照 §4.10 走 `JaModule` 路线 —— `front`=韩文、`back`=罗马字、`meaningZh`=中文
- [ ] **确认词量目标**：自用阶段建议先 **500–1000 词**（TOPIK I 的高频词），够用且翻译量可控
- [ ] **确认数据源并核实授权**（§5.2 的候选表），记下来源与许可，将来要写进 App 的署名

> 💡 **别一上来就追求 8000 词。** 先用 **20 个词**把整条链路（数据 → 代码 → 真机）跑通，
> 再接全量数据。这样万一字段映射或 UI 有问题，你不会是在 8000 词的数据上才发现。

### 阶段 1：造最小样例数据（约 1 小时）

- [ ] 写脚本抓/整理 **20 个韩语词**，字段照 §8.1 的格式
- [ ] 每条都要有：韩文词、罗马字转写、中文释义、一条例句 + 例句中文译文
- [ ] 存成 `app/src/main/assets/ko_topik1.json`
- [ ] **立刻校验**：`python -c "import json; json.load(open('...','encoding=utf-8'))"` 能过
- [ ] 发音表 `ko_chart.json`：先放 20 个基本字母（谚文），格式照 `kana.json`
- [ ] 场景 `scenarios_ko.json`：先抄 `scenarios_ja.json` 改 2–3 个场景的文案

> ⚠️ 大 JSON **一律脚本生成**，不要手写（本机大块手写会乱码）。

### 阶段 2：代码（约 3–5 小时）

- [ ] **步骤 1** `core/lang/LangId.kt`：加 `KO("ko", "韩语", "ko")`
- [ ] 🔴 **同时把 `fromTag` 改成** `entries.firstOrNull { it.tag == tag } ?: JA` ← **最容易漏**
- [ ] **步骤 2** 新建 `core/lang/ko/KoModule.kt`（照 §4.12 的骨架）
- [ ] 新建 `core/lang/ko/KoNormalizer.kt`（**记得先做 Unicode NFC**，见 §4.11 ①）
- [ ] **步骤 3** `LanguageRegistry` 注册 `LangId.KO to KoModule()`
- [ ] **步骤 4** 🔴 `ui/Speaker.kt` 的 `setLanguage` 加 `Locale.KOREAN` 分支 ← **最容易漏**
- [ ] **步骤 5** `ui/LangStyle.kt` 的 `accentRes` 加 `LangId.KO -> R.color.lang_ko`（编译器会报错提醒）
- [ ] 确认 `res/values/colors.xml` 里 `lang_ko` 已存在（**已经有了**）
- [ ] **步骤 6** `ui/langs/LanguagesActivity.kt`：把 `KO` 从 `COMING` 里删掉
- [ ] **步骤 7** 编译：`./gradlew :app:compileReleaseKotlin` 通过

> 🔧 顺手把 `ScenarioParser` / `str` / `WesternSentenceSplitter` 挪到 `core/lang/common/`
> （见 §4.12 的重构提示），否则跨包 import 会越来越别扭。

### 阶段 3：真机跑通样例（约 1 小时）

- [ ] `./gradlew :app:clean :app:assembleRelease` —— **必须 clean**（§6.1）
- [ ] 装机
- [ ] 课程页能看到「韩语」，能切过去
- [ ] 首页：语伴名/语言名/配色都变了，数字**没有**串到日语
- [ ] 复习页：正面是韩文词 → 点开有中文释义 + 罗马字 + 例句 + 译文
- [ ] 点喇叭，读的是**韩语音色**（不是英语）→ 这一步专门验 §4 步骤 4 那个坑
- [ ] 拼写考试：拼一个词，判对、判错都正常
- [ ] 切回日语，一切照旧

> ⛔ **这一步不过，不要进入阶段 4。** 20 个词上发现的问题，在 8000 个词上是同样的修法，
> 但排查成本高得多。

### 阶段 4：灌全量数据 + 翻译（耗时取决于词量）

- [ ] 抓全量词表 → 转成 `input/ko_all.json`（结构照 `docs/ja-translation/input/ja_n5.json`）
- [ ] 改 `docs/ja-translation/PRD-日语翻译.md` 的语种说明，交给模型跑
- [ ] 改 `merge.py` 指向韩语 → 生成 `assets/ko_topik1.json`
- [ ] **校验**：id 全覆盖、无空释义、空译文恰好对应「本来就没例句」的词
- [ ] **抽检 15–20 条**（各级都抽）：释义准、例句通顺、无英文残留、无错位
- [ ] 放进 assets，`books()` 里登记（多本词书就多条 `WordBook`）

### 阶段 5：终验与收尾（约 0.5 小时）

- [ ] 跑 **§11 的 16 项验收清单**，逐条真机点过
- [ ] `./gradlew :app:testDebugUnitTest` 全绿（记得补 `LanguageModuleTest` 的断言）
- [ ] `./gradlew :app:clean :app:assembleRelease` 出包
- [ ] 提交，并**把新文档/代码同步进 `D:\AI project\项目备份\拾语\`**
- [ ] 在 App 内补**数据来源署名**（如果打算公开发布）

### 一句话总结优先级

**先跑通链路，再堆数据。** 代码部分有本章两份清单兜着，不太会翻车；
真正会拖垮你的是「用 8000 词的数据调试一个字段映射错误」。

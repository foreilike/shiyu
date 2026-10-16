# 拾语（Shiyu）· 语言陪练 App

> 一个「开口就能聊」的日语 / 英语口语陪练 Android 应用。
> 实时语音对话 · 场景练习 · 单词 SRS 复习

**拾语** 取「拾起语言」之意。它把「开口说话」做成一个按钮 —— 点一下就能和 AI 语伴对话，
说错了不会被嘲笑，说中文也不会卡住。

---

## 界面预览

<table>
  <tr>
    <td align="center" width="33%"><img src="docs/screenshots/01-home.png" width="230"><br><sub><b>首页</b> · 今日目标 · 连续天数</sub></td>
    <td align="center" width="33%"><img src="docs/screenshots/02-scenarios.png" width="230"><br><sub><b>场景练习</b> · 5 个高频场景</sub></td>
    <td align="center" width="33%"><img src="docs/screenshots/03-voice-chat.png" width="230"><br><sub><b>实时语音对话</b> · 字幕 + 中文翻译</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/04-voice-ready.png" width="230"><br><sub><b>实时语音</b> · 不选语言，直接开口</sub></td>
    <td align="center"><img src="docs/screenshots/05-review-card.png" width="230"><br><sub><b>单词复习</b> · 释义/读音/例句 + 记忆评分</sub></td>
    <td align="center"><img src="docs/screenshots/06-stats.png" width="230"><br><sub><b>学习统计</b> · 周条形图与成就</sub></td>
  </tr>
</table>

> 上图为 2026-10 在真机（魅族 22）上的实际运行截图。

---

## 核心特性

### 🎙️ 实时语音对话（主打）

- **自动跟随语种**：不用先选"练日语还是练英语"。你说中文它用中文回、说英语用英语回、
  说日语用日语回 —— **同一场对话里换语种，它会跟着换**。
- **双向 WebSocket 流式**：语音进、语音出 + 同步字幕，边说边播。
- **打字兜底**：不方便说话时可打字，走同一个会话。
- **提示按钮**：卡壳时给一个随机话题方向（目标语言 + 中文）。

### 🗣️ 场景对话练习

5 个高频生活场景 × 2 门语言，AI 扮演角色（店员 / 面试官 / 路人……）：

| 日语 | 英语 |
|---|---|
| 自己紹介 · コンビニで買い物 · 道を聞く · レストランで注文 · 面接 | Self introduction · At the coffee shop · Asking for directions · Job interview · Making an appointment |

### 📚 单词与题型训练

- **间隔重复（SM-2 算法）**：按记忆强度安排复习，只复习「快忘的」。
- **12 本词书 / 题库**（约 11 MB，全部离线内置）：日语 N5–N1、英语 CET4 / CET6 / 考研 /
  雅思 / 托福 / GRE，另含五十音图与英语音标。

### 🔊 朗读与学习数据

词条、例句均可朗读（云端 TTS + **磁盘缓存 + 预取**）；首页有连续天数 / 今日目标 / 待复习数，
另有统计图表、成就体系、历史会话回看。

---

## 技术栈

| 项 | 选型 |
|---|---|
| 语言 / 平台 | Kotlin，Android 原生（未用跨平台框架） |
| 构建 | Gradle 8.9 · AGP 8.7.3 · Kotlin 2.1.0 |
| SDK | minSdk 26（Android 8.0）/ targetSdk 35 |
| 网络 | OkHttp（HTTP + WebSocket 双向流） |
| 存储 | JSON + AtomicFile 原子写；API Key 走 Android Keystore 加密 |

### 分层设计（可测性优先）

```
core/    纯 JVM 逻辑：语言规则、协议编解码、算法、音频处理
         —— 单测守门：禁止 import android.*（SourcePolicyTest 强制）
data/    持久化：设置、学习进度、会话记录、音频缓存
ai/      Provider 抽象：chat / realtime / tts / asr 四组能力
ui/      页面与交互
```

绝大部分业务逻辑是纯函数，**可以脱离真机在电脑上跑单测**——
对一个「改一次要装一次包」的 Android 项目，这是决定性的效率差异。

### 能力抽象：按 host 自动择协议

四组能力都是接口 + 多实现，**协议按配置里 baseUrl 的 host 自动择一**，判断只此一处：

| 能力 | host 判据 | 协议 |
|---|---|---|
| 语音识别 | `*.volces.com` | 火山方舟（两步：上传 → 多模态对话） |
| 语音识别 | `*.stepfun.com` | StepFun 原生 SSE |
| 语音识别 | 其它 | Whisper 兼容 `/audio/transcriptions` |
| 实时语音 | `wss://…/v1/realtime` | 双向流式 |

换 / 加服务商**只改配置、不动代码**；设置页还会按实际 host **动态显示**当前协议名。

---

## 快速开始

### 1. 克隆与准备配置

```bash
git clone <this-repo>
cd lang-partner

# 复制脱敏样例，填入你自己的 API Key
cp app/src/main/assets/preset_config.example.json app/src/main/assets/preset_config.json
```

> ⚠️ **仓库里不含任何 API Key。** `preset_config.json` 已被 `.gitignore` 排除，
> 仓库里只有不含密钥的 `preset_config.example.json`。
> 不填 Key 也能编译运行，但所有需要模型的功能会走本地模拟（Mock）。

`preset_config.json` 的结构：

```jsonc
{
  "groups": {                 // 对所有语言生效
    "chat":     { "baseUrl": "...", "apiKey": "", "model": "...", "enabled": true },
    "realtime": { "baseUrl": "wss://...", "apiKey": "", "model": "...", "enabled": true },
    "tts":      { "baseUrl": "...", "apiKey": "", "model": "...", "voice": "", "enabled": true }
  },
  "langGroups": {             // 按语言覆盖，只作用于该语言
    "ja": { "asr": { "baseUrl": "...", "apiKey": "", "model": "...", "enabled": true } }
  }
}
```

也可以不写预置文件，直接在 App 的「我的 → API 设置」里按组填写，支持改完即生效。

### 2. 构建

```bash
# 需要 JDK 17+
export JAVA_HOME=/path/to/jdk17

./gradlew :app:assembleDebug        # 调试包
./gradlew :app:testDebugUnitTest    # 跑单测（498 例）
./gradlew :app:lintDebug            # 静态检查
```

Release 包需要在 `keystore.properties` 里配置自己的签名（文件同样已被 `.gitignore` 排除）。

---

## 工程质量

| 指标 | 值 |
|---|---|
| 单元测试 | **498 例 / 40 个测试类，0 失败**（纯 JVM，不依赖真机） |
| 静态检查 | **lint 0 error** |
| 主代码 | 93 个 Kotlin 文件 / 约 10,900 行 |
| 测试代码 | 38 个文件 / 约 5,100 行（≈ 主代码的 47%） |

**发版门槛是硬性的**：单测全绿 + lint 0 error 才允许出包。

---

## 项目文档

仓库里保留了完整的开发过程记录，可作为工程方法的参考：

| 文档 | 内容 |
|---|---|
| [`HANDOVER-拾语App.md`](HANDOVER-拾语App.md) | 完整技术交接：架构、坑位、逐项决策记录 |
| [`HANDOVER-拾语-接入新语言与语音.md`](HANDOVER-拾语-接入新语言与语音.md) | 如何新增一门语言 |
| [`TASK-实时语音自动语种切换.md`](TASK-实时语音自动语种切换.md) | 最近一项改造的完整调查与实测记录 |
| [`TASK-语言陪练App-从零实现.md`](TASK-语言陪练App-从零实现.md) | 从零实现的原始任务书 |
| `TEST-*.md` | 真机验收清单 |

---

## 一些值得记录的工程决策

- **语音识别不是加参数就能修的**：带口音的日语会被某家 ASR 判成中文并输出谐音，
  实测 `language` / `prompt` / `hotwords` 三个参数都改不动输出文字体系（6 种组合），
  最终按语言分流到另一条通路，并把新通路的延迟从 137 秒压到 1~2 秒。
- **提示词要"点名坏行为"**：只写"要自然"不够。实时语音最初复用了场景练习的提示词，
  AI 每次开口都问「你叫什么名字」；解法是直接点名禁止该类提问，并给正例（话题要经常换）。
- **配置变更要能精确控制范围**：预置配置的版本号若全局递增，会连带覆盖用户自己调过的设置；
  因此拆出了**按语言的独立版本号**，让"只推这一项"成为可能。
- **日志即判据**：TTS 记 `HIT / MISS / WAIT / FAIL` + 耗时，语音字幕打印「原文 vs 纠正」，
  排障时先取证、再下结论。

---

## 许可证

[MIT](LICENSE)

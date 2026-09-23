# MEMORY.md —— 拾语（lang-partner）长期项目约定

## 发布与 APK 管理（用户明确要求，2026-09-23）

- **每出一个新 APK 必须递增版本号**：`versionCode` +1，`versionName` 同步改（`app/build.gradle.kts`）。
  同 keystore 签名 → 覆盖安装不丢学习进度。
- **历史 APK 一律保留，不覆盖、不删除**：按 `Shiyu-v<versionName>-release.apk` 命名落到项目根目录，
  并同步一份到 `D:\AI project\项目备份\拾语\发布物\`。
  - 唯一允许被覆盖的是构建产物 `app/build/outputs/apk/release/app-release.apk`（每次构建重写，
    不是存档，别拿它当底稿）。
- **APK 被 `.gitignore` 忽略**，版本安全只靠文件本身，回收站也不可靠（本机回收站是僵尸状态），
  所以必须**异地留备份目录那份**。
- 回滚到旧版本 = 降级安装，Android 默认拒绝；真要退先把 `study_progress.json` 拷出来再说。

## 答卷式交付偏好（用户习惯）

- 交付 = 「清单 + 证据」：真机验证给可勾选清单 + logcat 判据，而不是「感觉好了」。
- 手机（魅族 22）常不在位（USB 每 1–2 分钟掉一次）：先把「不依赖设备的部分全部做完」
  （单测 / lint / clean 出包 / 文档 / 提交 / 同步备份），再交清单让用户自己测。
- 主观感受要变成可观测指标：例如 TTS 延迟优化加了 `ShiyuTts` 日志（HIT/MISS/WAIT/FAIL + 耗时）。

## 工程事实（速查）

- 构建必须显式 `JAVA_HOME=D:\a\jbr`；buildToolsVersion 锁 `36.0.0`；Gradle 8.9 / AGP 8.7.3 / Kotlin 2.1.0。
- 改了接口方法或 assets 必须 `clean` 再构建（否则可能踩 `AbstractMethodError` 的桥接丢失，见 HANDOVER §6.1）。
- 单测 399 例全绿 + `lintDebug` 0 error 是发版门槛；UI 改动必须真机验（历史上栽过两次）。
- 源码/文档同步需要做决议：`D:\AI project\项目备份\拾语\`（结构与工程一致）。

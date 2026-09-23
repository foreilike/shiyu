package com.shiyu.app.ui

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.shiyu.app.core.lang.LangId
import java.util.Locale

/**
 * 系统 TTS 朗读助手（发音表 / 复习卡 / 逐句朗读用，不占用 AI Provider）。
 *
 * ## 为什么需要「多引擎回退」
 *
 * 一台手机上可能装了多个 TTS 引擎，而**没有一个能同时读好所有语言**。实测（魅族 22）：
 * - **Google 语音服务**：英语有本地音色（`en-us-x-iog-lstm-embedded`，瞬时）；
 *   **日语只有联网音色**（`ja-jp-x-jad-server`），网络一断就静默失败。
 * - **高通语音 AI**：**日语离线可用**（实测有声音）；**英语读不出来**。
 *
 * 只看「系统默认引擎」的话，用户切到哪个都会瘸一条腿：默认设成 Google → 日语哑；
 * 默认设成高通 → 英语哑。而默认引擎是**全系统**设置，为一个 App 改它还会影响别的 App。
 *
 * 所以这里的做法是：**按目标语言，在已安装的引擎里挨个试**，挑一个真能出声的，
 * 并把结果**按语言缓存**（每种语言只探测一轮）。用户不用动系统设置。
 *
 * **判据只有一个：实际合成能不能出声。** 别信 `setLanguage` 的返回码、也别信音色列表
 * （两者都有反例，见 [onInit] 里的注释）；失败就换下一个引擎重读，全试完才算真不行。
 *
 * ## 两个 API 上的坑（都踩过）
 *
 * 1. `getEngines()` / `getDefaultEngine()` 是 **TextToSpeech 的实例方法**，不是静态方法 ——
 *    所以枚举必须在**持有实例时**做；一旦 `shutdown()` 就取不到了。
 *    因此失败换引擎的顺序是「**先用当前实例枚举出下一个，再释放当前实例**」。
 * 2. 构造是**异步**的：`TextToSpeech(context, listener)` 返回时引擎还没连上，
 *    此时赋值 `tts.language` 会被引擎初始化覆盖掉。所以 locale 要缓存到 [onInit] 再应用 ——
 *    不这么做的话，中文 ROM 上会用中文音色去念英文/日文句子。
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    /** 期望的朗读语言；引擎就绪前先存着，就绪后再应用 */
    private var locale: Locale? = null

    /** 当前朗读语言；例句语速要按语言分，所以得记住 */
    private var currentLang: LangId? = null

    /** 已经为哪个语言挑好引擎了（避免每次进页面都重新探测一圈） */
    private var appliedForLang: LangId? = null

    /** 本轮已试过的引擎包名（含 [KEY_SYSTEM_DEFAULT]），避免重复试 */
    private val triedEngines = mutableSetOf<String>()

    /** 最近一次朗读的文本 —— 合成失败换引擎后要用它重读一遍 */
    private var lastSpokenText: String? = null

    /** 最近一次合成错误码（仅用于日志） */
    private var lastErrorCode = 0

    /** 语言 → 挑中的引擎包名。挑好一次就缓存，下次同语言直接用 */
    private val chosenEngine = mutableMapOf<LangId, String>()

    /** 语音包缺失时的回调（**所有引擎都试过仍不行**），供 UI 提示 */
    var onLanguageUnavailable: ((Locale) -> Unit)? = null

    /** 合成失败（引擎 onError）时的回调，参数为引擎错误码 */
    var onSpeechError: ((Int) -> Unit)? = null

    /** 抑制开关：录音期间置 true，阻断一切朗读，从根上避免「外放被录进麦克风」的回环。 */
    private var suppressed = false

    /**
     * 单词朗读语速。系统默认 1.0，对学习者偏快 —— 音节糊在一起，跟不上也听不清。
     * 0.5 是实测听感调出来的值（0.7 仍偏快）。
     */
    private var rate = SLOW_RATE

    /**
     * 回调统一从**主线程**发出。
     *
     * 必须这么做：`onSpeechError` 是 TTS 引擎从 **Binder 线程**回调上来的，调用方在里面碰 UI
     * （比如弹 Toast）会抛 `Can't toast on a thread that has not called Looper.prepare()` ——
     * 而且那个异常被系统吞掉，表现是「提示根本没出现」，比崩溃还难查（真机上踩过）。
     * 在源头收口，所有调用方就不必各自记住这件事。
     */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun postToMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    init {
        // 先挂上系统默认引擎；setLanguage 时若它读不了这门语言会自动换
        tts = TextToSpeech(appContext, this)
        triedEngines.add(KEY_SYSTEM_DEFAULT)
    }

    // ---------- 对外 ----------

    fun setLanguage(langId: LangId) {
        currentLang = langId
        val target = if (langId == LangId.JA) Locale.JAPANESE else Locale.ENGLISH
        locale = target

        // 这门语言已经挑好引擎、实例也在 → 直接用，不重复探测
        if (appliedForLang == langId && ready && tts != null) {
            applyLanguage(target)
            return
        }
        appliedForLang = null
        pendingText = null
        triedEngines.clear()
        triedEngines.add(KEY_SYSTEM_DEFAULT)
        buildWith(chosenEngine[langId] ?: KEY_SYSTEM_DEFAULT)
    }

    fun speak(text: String) = speak(text, rate)

    /**
     * 整句例句朗读：语速**按语言分**（英语 0.3、日语 0.4，均为实测听感调出的值）。
     * 整句更长、连读更多，跟单词一个速度来不及听清。
     */
    fun speakExample(text: String) = speak(text, exampleRateFor(currentLang))

    /**
     * 指定语速朗读。
     *
     * 每次都显式 setSpeechRate：引擎的语速是「状态」而非「每句参数」，
     * 不重设的话上一句的语速会漏到下一句（读完慢速例句，单词也跟着变慢）。
     */
    fun speak(text: String, rate: Float) {
        if (text.isBlank()) return
        if (suppressed) return
        try {
            tts?.setSpeechRate(rate)
        } catch (e: Exception) {
            // 忽略：设不上按引擎当前语速读，不该打断朗读
        }
        if (ready) speakNow(text) else pendingText = text
    }

    fun shutdown() {
        releaseTts()
        appliedForLang = null
    }

    // ---------- 引擎探测 ----------

    /** 用指定引擎重建实例。engine 为 [KEY_SYSTEM_DEFAULT] 时交给系统挑 */
    private fun buildWith(engine: String) {
        releaseTts()
        triedEngines.add(engine)
        tts = try {
            if (engine == KEY_SYSTEM_DEFAULT) TextToSpeech(appContext, this)
            else TextToSpeech(appContext, this, engine)
        } catch (e: Exception) {
            null
        }
        if (tts == null) tryNextEngine()
    }

    /**
     * 换下一个没试过的引擎。**必须在释放当前实例之前枚举** ——
     * `getEngines()` 是实例方法，实例没了就问不出来。
     */
    private fun tryNextEngine() {
        val all = try {
            tts?.getEngines()?.map { it.name }
        } catch (e: Exception) {
            null
        } ?: emptyList()
        android.util.Log.i(
            TAG,
            "TTS 换引擎：已装=${all.joinToString(",").ifEmpty { "(枚举为空)" }} " +
                "已试=${triedEngines.joinToString(",")} 语言=$locale"
        )
        val next = all.firstOrNull { it !in triedEngines }
        if (next == null) {
            // 都试完了：确实没有引擎能出声，如实报错。
            releaseTts()
            locale?.let { target -> postToMain { onLanguageUnavailable?.invoke(target) } }
            return
        }
        buildWith(next)
    }

    private fun releaseTts() {
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            // 忽略
        }
        tts = null
        ready = false
    }

    override fun onInit(status: Int) {
        android.util.Log.i(TAG, "TTS onInit status=$status 引擎=${activeEngineName()} 语言=$locale")
        if (status != TextToSpeech.SUCCESS) {
            tryNextEngine()
            return
        }
        ready = true
        attachListeners()
        applyRate(rate)

        val target = locale ?: return
        val result = try {
            tts?.setLanguage(target)
        } catch (e: Exception) {
            null
        }
        // ★ 这个引擎读不了这门语言（缺语音包）→ 换下一个。
        // 「系统默认是 Google、日语读不了」就是从这个分支走出去、落到高通上的。
        android.util.Log.i(
            TAG,
            "TTS setLanguage($target) 返回=$result（-1=缺数据 -2=不支持 1=成功）"
        )
        // ⚠️ **不要因为 setLanguage 返回「缺数据/不支持」就换引擎。**
        // 实测：高通的引擎 setLanguage(日语) 返回 -2(LANG_NOT_SUPPORTED)，
        // 但它作为系统默认引擎时读日语完全正常 —— 这个返回码在部分引擎上不可信。
        // 所以这里只记一笔日志，继续尝试朗读；**换引擎一律以「实际合成失败」为准**
        // （见 handleSynthesisError）——那是唯一可靠的事实。
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            android.util.Log.i(TAG, "TTS setLanguage 返回 $result，但该引擎可能仍能朗读，继续尝试")
        }

        // ★★ 用「查音色列表」判断该不该换引擎 —— **也删掉了**，同样是坑。
        //
        // 两种猜测式判据都验证过、都不可靠：
        // ① `setLanguage` 的返回码：高通读日语正常，`setLanguage(日语)` 却返回 -2(NOT_SUPPORTED)；
        // ② `voices` 里有没有 `isNetworkConnectionRequired=false` 的音色：高通这类**内嵌引擎压根
        //    不暴露 voices 列表**（查不到 → 被误判成"没有离线音色"），而 Google 的日语反过来
        //    有个 `ja-jp-x-jad-local` 幽灵条目（列着、实际没数据、一合成仍走网络）。
        //
        // 结论：**换引擎只以「实际合成失败」为准**（见 [handleSynthesisError]）——
        // 那是唯一来自事实的信号。有离线音色就用它（延迟低、离线可用），没有也照样往下走。
        embeddedVoiceFor(target)?.let { applyVoice(it) }

        currentLang?.let { chosenEngine[it] = activeEngineName() }
        appliedForLang = currentLang

        val text = pendingText
        pendingText = null
        if (text != null) speakNow(text)
    }

    /** 当前实例实际绑定的引擎包名 */
    private fun activeEngineName(): String = try {
        tts?.defaultEngine ?: KEY_SYSTEM_DEFAULT
    } catch (e: Exception) {
        KEY_SYSTEM_DEFAULT
    }

    private fun attachListeners() {
        // 合成失败要能看见：默认的静默失败会让人以为「这个按钮坏了」
        try {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = Unit

                @Deprecated("引擎仍会调旧签名", ReplaceWith(""))
                override fun onError(utteranceId: String?) = handleSynthesisError(-1)

                override fun onError(utteranceId: String?, errorCode: Int) =
                    handleSynthesisError(errorCode)
            })
        } catch (e: Exception) {
            // 忽略：拿不到错误回调不影响朗读本身
        }
    }

    /**
     * 找这门语言的**离线**音色；没有就返回 null。
     *
     * 为什么要专门找离线的：同一个语言在同一引擎里可能既有联网音色也有本地音色，
     * 默认给的不一定是本地那个 —— 实测 Google 的日语就只给到联网音色，网络不通时一声不响。
     * 本地音色合成是瞬时的、离线可用，学习场景明显更合适。
     */
    private fun embeddedVoiceFor(target: Locale): android.speech.tts.Voice? {
        val voices = try {
            tts?.voices
        } catch (e: Exception) {
            null
        } ?: return null
        return voices
            .filter { it.locale.language == target.language }
            .firstOrNull { !it.isNetworkConnectionRequired }
    }

    private fun applyVoice(voice: android.speech.tts.Voice) {
        try {
            tts?.voice = voice
        } catch (e: Exception) {
            // 换不过去就用引擎默认的，不该影响已有的朗读能力
        }
    }

    /** 同一语言内复用实例时重新应用一次（换语言走 [setLanguage] → [buildWith]） */
    private fun applyLanguage(target: Locale) {
        val result = try {
            tts?.setLanguage(target)
        } catch (e: Exception) {
            null
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            postToMain { onLanguageUnavailable?.invoke(target) }
            return
        }
        embeddedVoiceFor(target)?.let { applyVoice(it) }
    }

    /** 语速：引擎就绪后才能设，设不上也不影响朗读（失败静默按默认走） */
    private fun applyRate(value: Float) {
        rate = value
        try {
            tts?.setSpeechRate(value)
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun setSuppressed(suppressed: Boolean) {
        this.suppressed = suppressed
        if (suppressed) tts?.stop()
    }

    private fun speakNow(text: String) {
        lastSpokenText = text
        val params: Bundle? = null
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance-${text.hashCode()}")
    }

    /**
     * 合成失败的处理：**先试着换个引擎重读这句，实在没得换才报错**。
     *
     * 为什么靠「失败」而不是靠「查音色列表」来判断该换引擎：
     * Google 的日语在音色列表里**有一个 `ja-jp-x-jad-local`，且 `isNetworkConnectionRequired=false`** ——
     * 看着像离线可用，实际语音数据没下载，一合成照样走网络并超时。
     * **这种「幽灵音色」光查列表查不出来，只能以实际合成结果为准。**
     *
     * 每个引擎每种语言只试一次（[triedEngines]），所以不会来回打转。
     */
    private fun handleSynthesisError(errorCode: Int) {
        lastErrorCode = errorCode
        postToMain {
            if (switchEngineAfterFailure()) return@postToMain
            onSpeechError?.invoke(errorCode)
        }
    }

    /** 还能换到没试过的引擎就换，并把刚才那句排进待读队列；否则返回 false */
    private fun switchEngineAfterFailure(): Boolean {
        val all = try {
            tts?.getEngines()?.map { it.name }
        } catch (e: Exception) {
            null
        } ?: return false
        val next = all.firstOrNull { it !in triedEngines } ?: return false

        android.util.Log.i(TAG, "TTS 合成失败（code=$$lastErrorCode）→ 换引擎重试：$next")
        pendingText = lastSpokenText
        ready = false
        buildWith(next)
        return true
    }

    companion object {
        private const val TAG = "ShiyuTTS"

        /** 候选里的哨兵：表示「用系统默认引擎」（不要改用 null —— 会和「没有候选了」混淆） */
        private const val KEY_SYSTEM_DEFAULT = "__system_default__"

        /** 单词朗读语速：0.5 = 系统默认的一半。默认 1.0 对初学者太快，0.7 仍偏快（实测反馈） */
        const val SLOW_RATE = 0.5f

        /** 例句语速——英语。整句更长、连读更多，比单词再慢一档才听得清 */
        const val EXAMPLE_RATE_EN = 0.3f

        /** 例句语速——日语。日语音节短、语速本身快，0.3 太拖，0.4 更自然（实测听感） */
        const val EXAMPLE_RATE_JA = 0.4f

        private fun exampleRateFor(langId: LangId?): Float =
            if (langId == LangId.JA) EXAMPLE_RATE_JA else EXAMPLE_RATE_EN
    }
}

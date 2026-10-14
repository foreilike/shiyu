package com.shiyu.app.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.ai.AiResult
import com.shiyu.app.ai.ChatMessage
import com.shiyu.app.ai.ChatRequest
import com.shiyu.app.ai.RealtimeVoiceProvider
import com.shiyu.app.audio.RealtimeAudioController
import com.shiyu.app.core.audio.Pcm16
import com.shiyu.app.core.audio.PcmUtteranceBuffer
import com.shiyu.app.core.audio.VadPolicy
import com.shiyu.app.core.audio.WavWriter
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageModule
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.core.lang.TextScript
import com.shiyu.app.core.lang.VoicePrompts
import com.shiyu.app.data.store.SettingsRepository
import com.shiyu.app.data.model.SessionTurn
import com.shiyu.app.databinding.ActivityVoiceChatBinding
import com.shiyu.app.ui.AiErrorMessages
import com.shiyu.app.ui.BaseActivity
import com.shiyu.app.ui.LangStyle
import com.shiyu.app.ui.home.HomeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * 实时语音对话页（拾语设计稿：全屏深色沉浸）。
 *
 * 链路：RealtimeAudioController 采集 pcm16（端上 VAD 静音门控）
 *   → RealtimeVoiceProvider.appendAudio（WebSocket）
 *   → response.audio.delta → AudioTrack 播放；audio_transcript.delta → 字幕流。
 * barge-in：服务端 speech_started → interrupt() 立即停播，避免录到自己的声音。
 */
class VoiceChatActivity : BaseActivity() {

    private lateinit var binding: ActivityVoiceChatBinding
    private lateinit var module: LanguageModule
    private var provider: RealtimeVoiceProvider? = null
    // WS 回调线程（onAudioDelta）会读它，主线程 startSession/endSession 写它 —— 加 @Volatile，
    // 否则 onDestroy 置 null 后回调线程可能仍读到过期的非 null 引用，往已释放的控制器推音频。
    @Volatile private var audio: RealtimeAudioController? = null
    private var running = false

    private val handler = Handler(Looper.getMainLooper())
    private var startElapsed = 0L
    private var turns = 0
    private var recorded = false

    /** 单键收音开关：true=正在收音（麦克风热），false=已暂停（推静音等 AI 回复） */
    private var speaking = false

    /** 本轮的完整往返，结束时整条写入 SessionStore（实时语音不逐轮落盘） */
    private val recordedTurns = mutableListOf<SessionTurn>()

    /** 字幕当前是否显示（默认开） */
    private var subtitlesOn = true

    private val bars = arrayOfNulls<View>(21)
    private var aiBubble: TextView? = null
    private val aiBuffer = StringBuilder()
    private var userBubble: TextView? = null
    private val userBuffer = StringBuilder()

    // ---------- 日语字幕修正：语种判据 = **模型回复用的语言** ----------
    //
    // 实时通道的转写头只按中英训练，说日语会被解成中文谐音（すみません →「是密码谁」）。
    // 独立 ASR 能把日语转对，但它必须收一个确定的 `language`。
    //
    // **2026-10-14 上午删过一版**：那时是句末**无条件**送 ASR（按 `activeLang` 传 language），
    // 自动跟随语种后这么干是错的 —— 真机实测它拿着 language=ja 干的是**翻译**而不是转写：
    //   中文「我今天有点累」    → 「今日はちょっと疲れました。」
    //   中文「你刚才说了什么？」 → 「なに」
    //   英语「Do you like music?」→ 原样保留
    //   日语「かわいいですね」   → 修对了
    // 即：用户说中文，字幕显示日语。无条件开着比关掉更错，所以当时整条删了。
    //
    // **下午换了判据接回来**：模型判语种走音频，实测极准（转写给出阿拉伯文乱码、甚至空字符串时，
    // 它照样用日语回答且内容接得上）。所以反过来用它当语种判据：
    // **它用日语回你，就说明你刚才那句说的是日语** → 只有这一句才送 ASR。
    // 它用中文/英语回答时一律不送 —— 那正是上面那四行错误的来源。
    //
    // 再加一道保险：修回来的结果**必须含假名**才采用，否则保留原文。
    // 这样即使语种判错，也不会把一句中文字幕换成日语。
    //
    // 代价：每句日语多一次 ASR 调用，字幕先出谐音、约 1.5~2.5s 后原地替换成正确日语。

    /** 用户这一句的采集音频（来自 onAudioFrame，只在真的开着麦时留） */
    private val utteranceBuffer = PcmUtteranceBuffer()

    /** 等着看"模型用什么语言回"的那一句：音频 + 它的气泡 + 它在 recordedTurns 里的下标 */
    private class PendingUtterance(
        val pcm: ShortArray,
        val bubble: TextView,
        val turnIndex: Int,
        val rawText: String
    )

    private var pendingUtterance: PendingUtterance? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startSession() else finish()
        }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val secs = (System.currentTimeMillis() - startElapsed) / 1000
            binding.voiceTimer.text = "%02d:%02d".format(secs / 60, secs % 60)
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val lang = AppGraph.settingsRepository.activeLang()
        module = LanguageRegistry.module(lang)

        fitTopInset(binding.voiceTopBar)
        // 底部容器整体吃「导航栏 / 键盘」两者较大的那个：edge-to-edge 下
        // adjustResize 不生效，不手动吃 inset 键盘会盖住打字输入框
        fitBottomInsetWithIme(binding.bottomBar)

        // 语伴名/头像一律从 LangStyle 取，不要写回布局。
        // 之前准备层的「和 Yuki 聊聊天」和「ゆ」头像是硬编码在 XML 里的，
        // 切到英语后仍然显示日语语伴，和首页对不上。
        val partner = LangStyle.partnerName(lang)
        binding.voicePartnerName.text = partner
        // 顶栏副标题**不带语言**：实时语音已改成自动跟随用户说的语种，
        // 标任何单一语言都是错的 —— 写「· 英语 ·」会让人以为现在只能说英语，
        // 而这正是取消分入口要消除的印象。
        binding.voicePartnerSub.text = getString(
            R.string.voice_partner_sub,
            getString(R.string.voice_mode_free_talk)
        )
        binding.readyTitle.text = getString(R.string.voice_ready_title, partner)
        binding.readyAvatar.text = partner.firstOrNull()?.uppercase().orEmpty()

        buildWave()

        // 中间大圆按钮：单键切换收音。点「开始说话」= 麦克风开始收音（并打断 AI）；
        // 再点「暂停说话」= 停止收音、推静音让服务端断句并回复。合并了旧的「自动/按住」两套模式。
        binding.micLabel.text = getString(R.string.voice_btn_start)

        binding.btnBack.setOnClickListener { endSession() }
        binding.btnStartVoice.setOnClickListener { requestMicAndStart() }
        binding.btnMic.setOnClickListener { toggleSpeaking() }
        // 字幕开关：关掉字幕 = 纯听不看，适合练听力。按钮要能看出当前状态，
        // 否则用户点完发现"什么都没发生"（其实只是字幕区没了，而它本来就可能是空的）
        binding.btnCc.setOnClickListener { toggleSubtitles() }
        binding.btnHint.setOnClickListener {
            // 自由聊天没有"目标表达"了，提示改成给一个**话题方向** —— 学习者卡壳时最需要的
            // 是"能说什么"，而不是"该练哪句"。每次随机抽一条，避免反复看到同一句。
            val hint = VoicePrompts.freeTalkHints(module.id).random()
            appendAiLine(getString(R.string.voice_hint_line, hint.first, hint.second))
        }
        binding.btnAgain.setOnClickListener { resetSession() }
        binding.btnHangup.setOnClickListener { endSession() }
        binding.btnType.setOnClickListener { toggleTypeRow() }
        binding.btnTypeClose.setOnClickListener { toggleTypeRow() }
        binding.btnTypeSend.setOnClickListener { sendTypedText() }
        binding.typeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendTypedText()
                true
            } else {
                false
            }
        }
        // 总结层里的「返回首页」：这一轮已经结算过了，直接退出并回到「今日」。
        // 两个坑都踩过：原先接 endSession() 会重算时长、把总结层再弹一次，按钮像失灵；
        // 只 finish() 则会退到进入语音页的那一层（「对话」Tab），和按钮写着的"首页"对不上。
        binding.btnExit.setOnClickListener {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            finish()
        }

        if (AppGraph.settingsRepository.realtimeTranslate() &&
            !AppGraph.providerRegistry.isChatEnabled(module.id)
        ) {
            appendAiLine(getString(R.string.voice_translate_unavailable))
        }

        if (!AppGraph.providerRegistry.isRealtimeEnabled(module.id)) {
            binding.voiceState.text = getString(R.string.voice_mock_note)
        }
    }

    /** 字幕开关：关掉后转写区整块隐藏，图标变暗表示已关 */
    private fun toggleSubtitles() {
        subtitlesOn = !subtitlesOn
        binding.transcriptScroll.visibility = if (subtitlesOn) View.VISIBLE else View.GONE
        binding.ccIcon.alpha = if (subtitlesOn) 1f else 0.35f
        binding.ccLabel.alpha = if (subtitlesOn) 1f else 0.35f
    }

    // ---------- 打字输入 ----------

    /**
     * 打字输入：走协议的 `conversation.item.create`（input_text）+ `response.create`。
     *
     * 和语音**共用同一条会话** —— 所以 AI 的回复照样带语音和字幕，历史记录里也是同一条，
     * 不需要另开一条纯文字链路。适合不方便出声的场合（地铁、开会、周围太吵）。
     */
    private fun toggleTypeRow() {
        val show = binding.typeRow.visibility != View.VISIBLE
        binding.typeRow.visibility = if (show) View.VISIBLE else View.GONE
        // 打字时收起控制栏：键盘已经占了下半屏，再摞一排按钮既挤又容易误触
        binding.voiceControls.visibility = if (show) View.GONE else View.VISIBLE
        if (show) binding.typeInput.requestFocus()
        else {
            binding.typeInput.clearFocus()
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.hideSoftInputFromWindow(binding.typeInput.windowToken, 0)
    }

    private fun sendTypedText() {
        val text = binding.typeInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.typeInput.setText("")

        // 本地先把这条显示出来，别等服务端回声 —— 打字本来就该立刻看到自己说了什么
        val bubble = newUserBubble()
        bubble.text = text
        recordedTurns.add(SessionTurn("user", text))
        turns++

        provider?.sendUserText(text)
    }

    // ---------- 中文翻译 ----------

    /**
     * 给刚说完的一句配一条中文小字。
     *
     * **为什么另起一次调用，而不是让实时模型自己带翻译**：实时协议的 modalities 固定为
     * ["text","audio"]，模型输出的文字和语音是同一份 —— 让它说中文，它就一定会把中文念出来
     * （这正是之前「开口先说一声 JA」的成因）。所以翻译只能走旁路：
     * 复用设置页里已经配好的「对话服务」，悄悄译一遍挂上去，实时语音链路完全不参与。
     *
     * 未开启开关、没配对话服务、或翻译失败时都静默跳过 —— 字幕是锦上添花，不该打断对话。
     *
     * **本来就是中文的直接不译**（2026-10-14）：自动跟随语种后模型经常用中文回答，
     * 再"翻译"一遍只会让同一句话在气泡里印两遍（真机实测，连着五个气泡都是重复的）。
     * 这里提前返回，顺带省掉一次没意义的 API 调用。
     */
    private fun translateInto(bubble: TextView?, original: String) {
        if (bubble == null) return
        if (!AppGraph.settingsRepository.realtimeTranslate()) return
        if (original.isBlank()) return
        if (TextScript.isChineseOnly(original)) return
        if (!AppGraph.providerRegistry.isChatEnabled(module.id)) return

        lifecycleScope.launch {
            val provider = AppGraph.providerRegistry.factory(module.id).chat()
            val request = ChatRequest(
                model = AppGraph.settingsRepository.groupFor(
                    module.id, SettingsRepository.Group.CHAT
                ).model,
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        // 不写死源语言：自动跟随语种后，这句可能是日语，也可能是英语。
                        // （中文的那种情况上面已经提前返回，根本不会走到这里）
                        content = "你是翻译。把用户给的句子翻译成简体中文。" +
                            "只输出译文本身，不要解释、不要加引号、不要重复原文。"
                    ),
                    ChatMessage(role = "user", content = original)
                ),
                temperature = 0.2
            )
            // 失败重试一次：实测这条路会偶发失败（同一句话上一轮译得出来、这一轮就没有），
            // 而它整条是静默的，用户看到的只是"有的句子没译文"。重试一次能挡掉大部分抖动。
            val first = provider.chat(request)
            val result = if (first is AiResult.Err) {
                android.util.Log.w(TAG, "翻译失败，重试一次：${first.error}")
                provider.chat(request)
            } else {
                first
            }
            when (result) {
                is AiResult.Ok -> {
                    val zh = result.value.trim()
                    // 兜底：模型偶尔把原文原样吐回来，照样会变成重复两遍
                    if (zh.isNotEmpty() && zh != original) {
                        bubble.text = bubble.text.toString() + "\n\n" + zh
                    } else {
                        android.util.Log.i(TAG, "翻译返回空或与原文相同，不追加译文")
                    }
                }
                // 翻译失败不打扰用户：对话本身是正常的。但**必须留日志** ——
                // 这条路以前一行日志都没有，出问题时只能靠猜（已经猜过一次了）。
                is AiResult.Err -> android.util.Log.w(TAG, "翻译重试后仍失败：${result.error}")
            }
        }
    }

    // ---------- 波形 ----------

    private fun buildWave() {
        binding.waveContainer.removeAllViews()
        for (i in bars.indices) {
            val bar = View(this)
            val lp = LinearLayout.LayoutParams(6, 4).apply {
                marginStart = 3
                marginEnd = 3
            }
            bar.layoutParams = lp
            // 深色页上别用 bg_soft（近白暖底）—— 夜幕上一排近白柱，和"安静的纸/暖金"不搭。
            // 这是本工程反复出现的「浅色页资源用在深色页」第五处。
            bar.setBackgroundResource(R.drawable.bg_wave_bar_dark)
            binding.waveContainer.addView(bar)
            bars[i] = bar
        }
    }

    private fun pushAmplitude(amp: Int) {
        // 移位柱状：最新的在中间
        val mid = bars.size / 2
        for (i in bars.indices) {
            val src = amp - kotlin.math.abs(i - mid) * 6
            val h = (4 + src.coerceAtLeast(0) * 0.6).toInt().coerceIn(4, 64)
            bars[i]?.let { bar ->
                val lp = bar.layoutParams
                lp.height = h
                bar.layoutParams = lp
            }
        }
    }

    private fun resetWave() {
        for (bar in bars) {
            bar ?: continue
            val lp = bar.layoutParams
            lp.height = 4
            bar.layoutParams = lp
        }
    }

    // ---------- 会话控制 ----------

    private fun requestMicAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startSession()
        }
    }

    private fun startSession() {
        // 二次确认麦克风授权：用户可能在后台把权限撤回来了，
        // 不加这道闸会在 startCapture 处抛 SecurityException 直接崩。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicAndStart()
            return
        }
        binding.readyOverlay.visibility = View.GONE
        binding.summaryOverlay.visibility = View.GONE
        running = true
        startElapsed = System.currentTimeMillis()
        turns = 0
        recorded = false
        aiBuffer.clear()
        userBuffer.clear()
        recordedTurns.clear()
        aiBubble = null
        userBubble = null
        binding.transcriptContainer.removeAllViews()
        binding.voiceState.text = getString(R.string.voice_tap_start)
        handler.post(ticker)

        // 语音模式必须用专门的提示词：场景自带的 systemPrompt 是给文字对话写的，
        // 要求输出 [JA]/[ZH]/[FIX] 三段式，语音下会被整个念出来（见 VoicePrompts 注释）。
        // 而且实时语音是**自由聊天**，不绑场景 —— 绑场景会变成"问卷"（§17.24）
        //
        // **提示词与 activeLang 无关**（2026-10-14）：说什么语言由模型按用户的发音自己判。
        // `module` 在这里只剩一个用途 —— 决定用哪一组 API 配置（key/baseUrl/model 是按语言存的），
        // 它**不再决定模型用什么语言说话**。
        val voicePrompt = VoicePrompts.forFreeTalk()
        utteranceBuffer.clear()
        pendingUtterance = null
        android.util.Log.i(
            TAG_STT,
            "日语字幕修正可用=${AppGraph.providerRegistry.isSttEnabled(module.id)}" +
                "（识别组按语言取，当前=${module.id.tag}）"
        )

        val p = AppGraph.providerRegistry.factory(module.id).realtime(voicePrompt)
        provider = p
        p.setListener(listener)

        // 单键切换模式：始终按「按住说话」的底层链路走（pushing 控制真实音频/静音），
        // 由 toggleSpeaking 切换 pushing。初始不收音，等用户点「开始说话」。
        speaking = false
        binding.micLabel.text = getString(R.string.voice_btn_start)
        audio = RealtimeAudioController(
            // 只在**用户真的开着麦**时留副本：关麦时链路推的是等长静音帧（为了让服务端断句），
            // 那些零值帧混进来会让 VadPolicy 把整段判成"几乎全是静音"，修正直接作废。
            onAudioFrame = { pcm ->
                p.sendAudio(pcm)
                if (audio?.pushing == true) utteranceBuffer.offer(pcm)
            },
            onSpeechStateChanged = { active ->
                runOnUiThread {
                    binding.voiceGateHint.visibility = if (active) View.GONE else View.VISIBLE
                }
            },
            onAmplitude = { amp -> runOnUiThread { pushAmplitude(amp) } }
        ).also {
            it.pushToTalkMode = true
            it.pushing = false
        }
        p.connect(voicePrompt)
        audio?.startCapture()
    }

    /**
     * 单键切换收音。点一下开始说话（打断 AI + 开麦收音），再点一下暂停说话
     * （关麦，推静音让服务端断句并回复）。合并了旧的「自动打断 / 按住说话」两套交互。
     */
    private fun toggleSpeaking() {
        if (!running) return
        speaking = !speaking
        if (speaking) {
            // 开始说话：先打断 AI 当前播放与生成，再开麦
            audio?.interrupt()
            provider?.cancelResponse()
            audio?.pushing = true
            binding.micLabel.text = getString(R.string.voice_btn_pause)
            binding.voiceState.text = getString(R.string.voice_speaking)
        } else {
            // 暂停说话：关麦，推静音，交给服务端断句 → AI 回复
            audio?.pushing = false
            binding.micLabel.text = getString(R.string.voice_btn_start)
            binding.voiceState.text = getString(R.string.voice_thinking)
        }
    }

    private fun resetSession() {
        provider?.close()
        audio?.release()
        startSession()
    }

    private fun endSession() {
        val started = startElapsed != 0L
        running = false
        handler.removeCallbacks(ticker)
        provider?.close()
        provider = null
        audio?.release()
        audio = null
        resetWave()
        if (!started) {
            // 还没点「开始对话」就按了返回：没有会话可结算，直接退出
            finish()
            return
        }
        val secs = (System.currentTimeMillis() - startElapsed) / 1000
        recordSession(secs)
        binding.sumTime.text = "%02d:%02d".format(secs / 60, secs % 60)
        binding.sumTurns.text = turns.toString()
        binding.summaryOverlay.visibility = View.VISIBLE
    }

    /**
     * 把这一轮实时语音计入当日统计（首页目标环 / 我的页周柱状图 / 「初次开口」成就）。
     *
     * 两道闸：
     * - [recorded] 保证幂等 —— endSession 与 onDestroy 都会调，只算一次；
     * - [startElapsed] 为 0 表示会话从未开始，此时 now-0 会算出五十多年，绝不能入账。
     * 另外单次封顶 [MAX_SESSION_SECONDS]，防止用户开着页面睡着导致时长虚高。
     */
    private fun recordSession(seconds: Long) {
        val users = recordedTurns.count { it.role == "user" }
        android.util.Log.i(
            TAG,
            "recordSession seconds=$seconds recorded=$recorded users=$users"
        )
        if (recorded || startElapsed == 0L || seconds <= 0L) return
        recorded = true
        val capped = seconds.coerceAtMost(MAX_SESSION_SECONDS)
        AppGraph.dailyStatsStore.record(
            LocalDate.now().toEpochDay(),
            voiceSeconds = capped,
            chatTurns = turns
        )
        // 实时语音以前完全不落盘，聊完即消失；现在写进会话记录，
        // 「历史对话」里能看到，也让「累计对话轮数」覆盖到实时语音。
        // 自由聊天没有场景，用一个保留字当 scenarioId；标题直接给中文名，
        // 否则历史页会回退显示裸的 "free_talk"
        AppGraph.sessionStore.saveRealtimeSession(
            module.id,
            FREE_TALK_SCENARIO_ID,
            getString(R.string.voice_mode_free_talk),
            recordedTurns.toList(),
            capped
        )
    }

    /**
     * 切到后台：**直接结算这一轮**（落盘 + 释放连接）。
     *
     * 这里返工过一次，记下为什么：
     * 起初的做法是「只暂停采集、保留 WebSocket，回前台接着聊」，假设用户会回来。
     * **实测这个假设不成立** —— `HomeActivity` 是启动页，从桌面点图标回来会带
     * `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`，任务栈被重置回根节点，语音页被弹掉；
     * 而 Activity 并没有销毁（没有 onDestroy），于是：
     *   · 连接成了僵尸，服务端会话一直挂着（很可能还在计费）；
     *   · 这一轮永远等不到结算路径，**整段对话直接丢失**（历史里什么都没有）。
     *
     * 与其留一个用户回不去的连接，不如老实结束：数据落盘、连接释放。
     * 要接着聊就重新开一轮，历史里能看到上一轮。
     */
    override fun onStop() {
        android.util.Log.i(TAG, "onStop running=$running")
        if (running) endSession()
        super.onStop()
    }

    override fun onDestroy() {
        android.util.Log.i(
            TAG,
            "onDestroy running=$running recorded=$recorded turns=${recordedTurns.size}"
        )
        if (running) {
            recordSession((System.currentTimeMillis() - startElapsed) / 1000)
        }
        running = false
        handler.removeCallbacks(ticker)
        provider?.close()
        provider = null
        audio?.release()
        audio = null
        super.onDestroy()
    }

    // ---------- Provider 事件 → UI ----------

    private val listener = object : RealtimeVoiceProvider.Listener {
        /** 新一轮响应开始 → 关闭打断丢弃窗口，正常播放 */
        override fun onResponseStarted() {
            audio?.resumeAudio()
            // **AI 开始回复 = 用户这一轮结束了，必须收麦。**
            //
            // 不收的话麦克风一直开着，AI 的外放会从自己的麦克风回到服务端（本项目没有真 AEC），
            // 服务端判为「用户在说话」→ 触发 barge-in → **把 AI 正在说的后半句掐掉**。
            // 真机上「AI 回复后半句没声音」就是这么来的。
            //
            // 触发点选「AI 开始回复」而不是「服务端报 speech_stopped」：
            // VAD 的静音判定很敏感，用户句中稍一停顿就可能报 speech_stopped，
            // 拿它当收麦信号会把用户的话吃掉。而 AI 都开始回复了，这一轮确定已经结束。
            // 用户想打断 AI，照旧点「开始说话」（会重新开麦并打断）。
            if (audio?.pushing == true) {
                android.util.Log.i(TAG, "AI 开始回复 → 自动收麦（防止外放回环触发打断）")
                audio?.pushing = false
                runOnUiThread {
                    speaking = false
                    binding.micLabel.text = getString(R.string.voice_btn_start)
                }
            }
        }

        override fun onReconnecting(attempt: Int) {
            runOnUiThread {
                binding.voiceState.text = getString(R.string.voice_reconnecting, attempt)
            }
        }

        override fun onReconnectFailed() {
            // 重连彻底失败：连接已经没了，**必须把采集也停掉**。
            // 否则麦克风会一直开着往一个死掉的 socket 里推（sendAudio 会 no-op，
            // 但麦克风本身是热的），既费电又让人不放心。
            //
            // 注意这个回调来自网络线程：stopCapture 会 join 采集线程并释放 AudioRecord，
            // 必须在主线程做 —— 否则和主线程的 stopCapture/startCapture 撞在一起。
            runOnUiThread {
                audio?.stopCapture()
                audio?.stopPlayback()
                binding.voiceState.text = getString(R.string.voice_reconnect_failed)
            }
        }

        /**
         * 服务端确认会话可用（收到 `session.created`）。语音页不需要额外动作，但**必须显式写出来**。
         *
         * 接口里 [RealtimeVoiceProvider.Listener.onReady] 带默认空实现，而 Kotlin 在
         * jvm-default=disable 下会把它编成「abstract 方法 + `Listener$DefaultImpls`」，
         * 没写它的实现类得靠编译器生成的桥接方法来转发。实测这个桥接会因为增量编译
         * 没重编本文件而缺失（829e65e 引入 onReady 时正是如此），运行时
         * `listener?.onReady()` 抛 AbstractMethodError，而且崩在 OkHttp 线程上没人接，
         * 直接带崩整个进程。显式写一个空的，本类就再也不依赖编译器桥接。
         */
        override fun onReady() {}

        override fun onConnected() {
            runOnUiThread { binding.voiceState.text = getString(R.string.voice_listening) }
        }

        override fun onUserSpeechStarted() {
            // **只有我们真的在推麦克风时，才把 speech_started 当作「用户打断」。**
            //
            // 本项目没有真 AEC：喇叭外放的声音会被自己的麦克风录进去。若用户点过「开始说话」
            // 之后一直没收麦，AI 回复时它的声音就从这个口子回到服务端，服务端判定「用户在说话」
            // → 我们又调 interrupt() → **把 AI 正在说的后半句掐掉**。这是真机上「AI 回复后半句没声音」的根因。
            // 没在收音（pushToTalkMode 下推的是静音帧）时服务端还能听到人声，那就不是用户 ——
            // 一律不当打断处理。
            if (audio?.pushing != true) {
                android.util.Log.i(TAG, "忽略 speech_started：当前未在收音，判为外放回环")
                return
            }
            // barge-in：用户一开口就停播，并让服务端也停掉当前这轮生成。
            // 只调 interrupt() 是不够的 —— 服务端还在往下推流，
            // 残留的音频包会立刻把播放重新拉起来（这就是"打断没反应"）。
            audio?.interrupt()
            provider?.cancelResponse()
            runOnUiThread { binding.voiceState.text = getString(R.string.voice_listening) }
        }

        override fun onUserSpeechStopped() {
            // 这里**只更新文案、不收麦**。服务端报「用户停下了」不代表用户真的说完了 ——
            // VAD 静音判定很敏感，句中停顿也会报，在这里收麦会把用户后半句吃掉。
            // 收麦放在 onResponseStarted（AI 真开始回复时），见那里的注释。
            runOnUiThread { binding.voiceState.text = getString(R.string.voice_thinking) }
        }

        override fun onAudioDelta(pcm: ByteArray) {
            audio?.playPcm(pcm)
        }

        override fun onTranscriptDelta(text: String) {
            // **整个回调体都进主线程**：aiBuffer 是普通 StringBuilder，
            // 而 onResponseDone 也会读它 —— 两个回调都在 WS 线程其实没事，
            // 但 sendTypedText / recordSession 在主线程序列化访问同一批容器，
            // 一半在主线程一半在 WS 线程就会 ConcurrentModificationException。
            // 统一收口到主线程是这里唯一可靠的解法。
            runOnUiThread {
                val bubble = aiBubble ?: newAiBubble()
                aiBuffer.append(text)
                bubble.text = aiBuffer.toString()
                binding.transcriptScroll.post {
                    binding.transcriptScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        override fun onUserTranscript(text: String) {
            runOnUiThread {
                // 字幕先直出实时通道给什么显示什么。日语这时候会是中文谐音
                // （すみません →「是密码谁」），等模型回复回来、确认它用的是日语，
                // 再由 maybeFixJapaneseSubtitle 原地换成正确日语。
                var turnIndex = -1
                if (text.isNotBlank()) {
                    recordedTurns.add(SessionTurn("user", text))
                    turnIndex = recordedTurns.lastIndex
                    // turns 计数必须与 recordedTurns 同步递增：服务端偶发回一条空/纯静音
                    // 转写时，之前 turns++ 在 if 外照加，结算页轮数与 chatTurns 会虚高于会话文件。
                    turns++
                }
                val bubble = newUserBubble()
                userBuffer.append(text)
                bubble.text = userBuffer.toString()

                // 把这一句的音频**现在就取走**：等到 onResponseDone 再取，用户可能已经开始说下一句了
                val pcm = Pcm16.toShorts(utteranceBuffer.drain())
                pendingUtterance = if (pcm.isNotEmpty()) {
                    PendingUtterance(pcm, bubble, turnIndex, text)
                } else {
                    null
                }

                binding.transcriptScroll.post {
                    binding.transcriptScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        override fun onResponseDone() {
            runOnUiThread {
                // 一轮 AI 回复结束，把攒下的字幕整句记下来
                // （增量是逐字来的，只有 done 时才是完整句）
                val said = aiBuffer.toString().trim()
                if (said.isNotEmpty()) {
                    recordedTurns.add(SessionTurn("assistant", said))
                    translateInto(aiBubble, said)
                }
                // 模型用什么语言回的，决定要不要去修用户那句的字幕
                maybeFixJapaneseSubtitle(said)
                aiBuffer.clear()
                aiBubble = null
                userBuffer.clear()
                userBubble = null
                // AI 回复完 → 提示用户点「开始说话」继续（单键模型下不自动收音）
                binding.voiceState.text = getString(R.string.voice_tap_start)
            }
        }

        override fun onError(error: com.shiyu.app.data.model.AiError) {
            runOnUiThread {
                binding.voiceState.text = AiErrorMessages.describe(this@VoiceChatActivity, error)
            }
        }

        override fun onClosed() {
            runOnUiThread { resetWave() }
        }
    }

    /**
     * 模型用日语回了 → 说明用户刚才那句说的就是日语 → 把那句的字幕从谐音修成正确日语。
     *
     * 判据为什么是"模型回复用的语言"：它判语种走音频，实测极准 ——
     * 转写给出阿拉伯文乱码、甚至空字符串时，它照样用日语回答且内容接得上。
     * 而两个文本源（实时转写给谐音中文、独立 ASR 把什么都翻成日语）都认不出语种。
     *
     * 失败、超时、结果不含假名，一律**保留原文**，绝不把气泡改坏 ——
     * 宁可显示谐音，也不能把一句中文换成日语。
     */
    private fun maybeFixJapaneseSubtitle(aiText: String) {
        val p = pendingUtterance ?: return
        pendingUtterance = null
        if (!TextScript.hasKana(aiText)) return
        if (TextScript.hasKana(p.rawText)) return   // 已经是日语了，不用修
        if (!AppGraph.providerRegistry.isSttEnabled(module.id)) {
            android.util.Log.i(TAG_STT, "SKIP 识别组不可用 → 保留原文")
            return
        }

        // 端上先裁静音省一次上传；裁不出来也不放弃，整段送过去让服务端判断
        val trimmed = VadPolicy.trim(
            p.pcm,
            VadPolicy.Params(sampleRate = RealtimeAudioController.SAMPLE_RATE)
        )
        val speech = if (trimmed != null) {
            p.pcm.copyOfRange(trimmed.startSample, trimmed.endSample)
        } else {
            p.pcm
        }
        val wav = WavWriter.mono16(Pcm16.toBytes(speech), RealtimeAudioController.SAMPLE_RATE)
        android.util.Log.i(
            TAG_STT,
            "SEND ${speech.size} 样本 原文='${p.rawText.take(20)}'（模型用日语回的）"
        )

        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            // 文件名带纳秒：连续两句的识别可能重叠，共用一个名字会互相删掉对方的文件
            val file = File(cacheDir, "rt_utterance_${System.nanoTime()}.wav")
            val result = withContext(Dispatchers.IO) {
                try {
                    file.writeBytes(wav)
                    AppGraph.providerRegistry.factory(module.id)
                        .stt().transcribe(file, LangId.JA.sttLanguage)
                } finally {
                    file.delete()
                }
            }
            val cost = SystemClock.elapsedRealtime() - started
            when (result) {
                is AiResult.Ok -> {
                    val fixed = result.value.trim()
                    // **必须含假名才采用**：这是语种判错时的最后一道保险 ——
                    // 结果要是一句中文，说明 ASR 并没有把它当日语转，换上去只会更错。
                    if (fixed.isNotEmpty() && TextScript.hasKana(fixed)) {
                        // 前缀「≈」= **这行是机器猜的**。
                        //
                        // 真机实测 8 次修正 5 对 3 错，而且错的时候编出来的是像模像样的假日语
                        // （ありがとう → なりたいと / あれから / なにかと）。
                        // 比起原来那串一眼就知道坏了的谐音（「奥利给阿杜」），这种"体面的错"
                        // 对学习者更有害 —— 他会以为自己真说出了那句话。
                        //
                        // 又挑不出能预测对错的特征（音频长短、原转写长什么样都试过，都不相关），
                        // 也就没法在客户端判断哪次该信。既然这行永远只是猜测，就得标成猜测。
                        val marked = "≈ $fixed"
                        p.bubble.text = marked
                        // recordedTurns 里那条也要一起改：结算页与会话落盘读的是它。
                        // **存的是不带「≈」的原文** —— 标记是给眼睛看的，不该进数据。
                        if (p.turnIndex in recordedTurns.indices &&
                            recordedTurns[p.turnIndex].role == "user"
                        ) {
                            recordedTurns[p.turnIndex] = SessionTurn("user", fixed)
                        }
                        android.util.Log.i(
                            TAG_STT,
                            "FIXED ${cost}ms '${p.rawText.take(16)}' → '${fixed.take(16)}'"
                        )
                    } else {
                        android.util.Log.i(TAG_STT, "结果无假名或为空 ${cost}ms → 保留原文")
                    }
                }
                is AiResult.Err -> android.util.Log.w(
                    TAG_STT, "FAIL ${cost}ms ${result.error} → 保留原文"
                )
            }
            binding.transcriptScroll.post {
                binding.transcriptScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun newAiBubble(): TextView =        TextView(this).apply {
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.cream))
            // 深色页专用气泡底。这里以前用的是 bg_card（浅色页的白底卡片），
            // 奶油色字印白底 = 在夜幕上糊成一片白影，等于看不清。
            setBackgroundResource(R.drawable.bg_bubble_ai_dark)
            setPadding(28, 20, 28, 20)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.START
                bottomMargin = 24
            }
            layoutParams = lp
            binding.transcriptContainer.addView(this)
            aiBubble = this
            this@VoiceChatActivity.aiBubble = this
        }

    private fun newUserBubble(): TextView =
        TextView(this).apply {
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.cream))
            setBackgroundResource(R.drawable.bg_bubble_user)
            setPadding(28, 20, 28, 20)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.END
                bottomMargin = 24
            }
            layoutParams = lp
            binding.transcriptContainer.addView(this)
            userBubble = this
            this@VoiceChatActivity.userBubble = this
        }

    private fun appendAiLine(text: String) {
        val tv = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFC9B8A4.toInt())
            setPadding(4, 8, 4, 8)
        }
        tv.text = text
        binding.transcriptContainer.addView(tv)
    }

    companion object {
        private const val TAG = "ShiyuVoice"

        /** 日语字幕修正专用的日志标签，便于真机验证：SEND / FIXED / SKIP / FAIL */
        private const val TAG_STT = "ShiyuStt"

        /**
         * 自由聊天的会话在记录里用的保留 scenarioId。
         *
         * 实时语音不绑场景（绑了会变成"问卷"，见 [VoicePrompts] 注释），
         * 但会话落盘需要一个 id —— 用这个保留字，它不会和任何真实场景撞上，
         * 于是「对话」页的场景轮数统计也不会把自由聊天的轮数算进某个场景。
         */
        const val FREE_TALK_SCENARIO_ID = "free_talk"

        /** 单次实时会话最多计 2 小时，防止挂着页面不管导致统计虚高 */
        private const val MAX_SESSION_SECONDS = 2L * 3600L

        /** 实时语音不需要场景：进去就是自由聊天 */
        fun intent(context: android.content.Context): Intent =
            Intent(context, VoiceChatActivity::class.java)
    }
}

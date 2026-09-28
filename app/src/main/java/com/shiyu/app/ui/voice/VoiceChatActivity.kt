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
import com.shiyu.app.core.lang.VoicePrompts
import com.shiyu.app.data.model.Scenario
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
    private lateinit var scenario: Scenario

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

    /** 本轮的基础系统提示（不随语言开关变化），与当前语言模式 */
    private var basePrompt = ""
    private var languageMode = VoicePrompts.LanguageMode.TARGET

    /** 字幕当前是否显示（默认开） */
    private var subtitlesOn = true

    private val bars = arrayOfNulls<View>(21)
    private var aiBubble: TextView? = null
    private val aiBuffer = StringBuilder()
    private var userBubble: TextView? = null
    private val userBuffer = StringBuilder()

    // ---------- B′：用独立语音识别纠正实时通道的转写（§17.21.5）----------
    //
    // 背景：stepfun realtime 的转写头只按中英训练，说日语会显示成中文谐音；
    // 但模型本身听得懂（实测回复是对的），错的只是给你看的字幕。
    // 做法：把这一句的采集音频另存一份，句末送一次语音识别，拿正确语种覆盖字幕。
    // 英语不需要（实时通道本来就准），只有 LangId.realtimeTranscriptReliable=false 才走。

    /** 用户这一句的采集音频（来自 onAudioFrame） */
    private val utteranceBuffer = PcmUtteranceBuffer()
    /** 本次会话是否需要「另送识别纠错」：语言实时转写不可信 + 识别组已配置 */
    private var correctTranscripts = false

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
        val scenarioId = intent.getStringExtra(EXTRA_SCENARIO_ID)
        val loaded = module.buildScenarios(AppGraph.assetRepository.scenariosJson(module))
            .firstOrNull { it.id == scenarioId }
        if (loaded == null) {
            finish()
            return
        }
        scenario = loaded

        fitTopInset(binding.voiceTopBar)
        // 底部容器整体吃「导航栏 / 键盘」两者较大的那个：edge-to-edge 下
        // adjustResize 不生效，不手动吃 inset 键盘会盖住打字输入框
        fitBottomInsetWithIme(binding.bottomBar)

        // 语伴名/头像一律从 LangStyle 取，不要写回布局。
        // 之前准备层的「和 Yuki 聊聊天」和「ゆ」头像是硬编码在 XML 里的，
        // 切到英语后仍然显示日语语伴，和首页对不上。
        val partner = LangStyle.partnerName(lang)
        binding.voicePartnerName.text = partner
        binding.voicePartnerSub.text = "AI 语伴 · " + lang.displayName
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
            appendAiLine("提示：" + scenario.goalExpressions.firstOrNull().orEmpty())
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

    /**
     * 学习者说「用中文 / 翻译一下」时，**在客户端把系统提示改掉**并重发 session.update。
     *
     * 为什么不交给提示词：实测 `step-audio-2-mini` 面对「默认日语」+「用户要求中文就照做」
     * 两条规则时几乎总守前者，换了两版措辞都无效。改成由客户端直接改写系统提示，
     * 模型看到的就从"可选规则"变成"现在就用中文"。
     * 详见 `VoicePrompts.detectLanguageSwitch` 的注释与单测。
     */
    private fun applyLanguageSwitchIfRequested(userText: String) {
        val wanted = VoicePrompts.detectLanguageSwitch(userText) ?: return
        if (wanted == languageMode) return
        languageMode = wanted
        provider?.updateInstructions(VoicePrompts.withLanguageMode(basePrompt, wanted))
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
        applyLanguageSwitchIfRequested(text)

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
     */
    private fun translateInto(bubble: TextView?, original: String) {
        if (bubble == null) return
        if (!AppGraph.settingsRepository.realtimeTranslate()) return
        if (original.isBlank()) return
        if (!AppGraph.providerRegistry.isChatEnabled(module.id)) return

        val target = module.id.displayName
        lifecycleScope.launch {
            val provider = AppGraph.providerRegistry.factory(module.id).chat()
            val request = ChatRequest(
                model = AppGraph.settingsRepository.groupFor(
                    module.id, SettingsRepository.Group.CHAT
                ).model,
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        content = "你是翻译。把用户给的$target 句子翻译成简体中文。只输出译文本身，不要解释、不要加引号、不要重复原文。"
                    ),
                    ChatMessage(role = "user", content = original)
                ),
                temperature = 0.2
            )
            when (val result = provider.chat(request)) {
                is AiResult.Ok -> {
                    val zh = result.value.trim()
                    if (zh.isNotEmpty()) {
                        bubble.text = bubble.text.toString() + "\n\n" + zh
                    }
                }
                // 翻译失败不打扰用户：对话本身是正常的
                is AiResult.Err -> Unit
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
        // 要求输出 [JA]/[ZH]/[FIX] 三段式，语音下会被整个念出来（见 VoicePrompts 注释）
        val voicePrompt = VoicePrompts.forScenario(
            module.id, scenario.titleZh, scenario.goalExpressions
        )
        basePrompt = voicePrompt
        languageMode = VoicePrompts.LanguageMode.TARGET

        // 是否需要「另送识别纠错」（§17.21.5 B′）：
        // 实时通道对这门语言的转写可信（如英语）就不必多花一次请求与等待；
        // 日语不可信，且识别组已配置时才启用 —— 否则回退到实时通道自己的转写。
        correctTranscripts = !module.id.realtimeTranscriptReliable &&
            AppGraph.providerRegistry.isSttEnabled(module.id)
        utteranceBuffer.clear()
        // 无条件记一条：这条日志是「到底有没有启用纠错」的唯一客观依据，
        // 排查时不必再靠猜（曾因为没这条，先怀疑了配置、又怀疑了版本）
        android.util.Log.i(
            TAG_STT,
            "纠错开关=$correctTranscripts（语言=${module.id.tag}" +
                " 实时转写可信=${module.id.realtimeTranscriptReliable}" +
                " 识别组可用=${AppGraph.providerRegistry.isSttEnabled(module.id)}）"
        )

        val p = AppGraph.providerRegistry.factory(module.id).realtime(voicePrompt)
        provider = p
        p.setListener(listener)

        // 单键切换模式：始终按「按住说话」的底层链路走（pushing 控制真实音频/静音），
        // 由 toggleSpeaking 切换 pushing。初始不收音，等用户点「开始说话」。
        speaking = false
        binding.micLabel.text = getString(R.string.voice_btn_start)
        audio = RealtimeAudioController(
            // 只在**用户真的开着麦**时留副本。
            //
            // 关键：关麦时实时链路会推「等长静音帧」（为了让服务端断句），那些零值帧
            // 一旦混进缓冲，VadPolicy 看到的就成了「一大段全零 + 一小段人声」，
            // 很容易超过 maxTrimmedRatio(0.98) 而判定「几乎全是静音」→ 直接放弃纠错，
            // 表现成「什么都没做」。按 pushing 过滤就只剩用户真实说话的那一段。
            onAudioFrame = { pcm ->
                p.sendAudio(pcm)
                if (correctTranscripts && audio?.pushing == true) utteranceBuffer.offer(pcm)
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
        AppGraph.sessionStore.saveRealtimeSession(
            module.id, scenario.id, recordedTurns.toList(), capped
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
                if (correctTranscripts) {
                    // 实时通道给的是「谐音中文」（日语场景），先占位，
                    // 由 recognizeUtterance 用正确语种覆盖（§17.21.5 B′）
                    val bubble = newUserBubble()
                    bubble.text = getString(R.string.voice_transcript_pending)
                    userBuffer.append(text)
                    val turnIndex = if (text.isNotBlank()) {
                        recordedTurns.add(SessionTurn("user", text))
                        turns++
                        recordedTurns.lastIndex
                    } else {
                        -1
                    }
                    applyLanguageSwitchIfRequested(text)
                    recognizeUtterance(bubble, turnIndex, text)
                } else {
                    if (text.isNotBlank()) {
                        recordedTurns.add(SessionTurn("user", text))
                        applyLanguageSwitchIfRequested(text)
                        // turns 计数必须与 recordedTurns 同步递增：服务端偶发回一条空/纯静音
                        // 转写时，之前 turns++ 在 if 外照加，结算页轮数与 chatTurns 会虚高于会话文件。
                        turns++
                    }
                    val bubble = newUserBubble()
                    userBuffer.append(text)
                    bubble.text = userBuffer.toString()
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
     * 把「用户这一句」的采集音频另送一次语音识别，用正确语种覆盖字幕（§17.21.5 B′）。
     *
     * 背景：stepfun 实时通道的转写头只按中英训练，说日语会显示成中文谐音；
     * 但模型**本身听得懂**（实测她的回复是对的），错的只是给你看的这行字幕。
     *
     * 失败一律**回退到实时通道给的原文本**，绝不把气泡留成占位文案 ——
     * 宁可显示不完美的谐音，也不能让用户看到「识别中…」卡住不动。
     */
    private fun recognizeUtterance(bubble: TextView, turnIndex: Int, rawText: String) {
        val samples = Pcm16.toShorts(utteranceBuffer.drain())
        if (samples.isEmpty()) {
            bubble.text = rawText
            android.util.Log.i(TAG_STT, "SKIP 没缓冲到音频（这一句麦没开过）→ 沿用原文")
            return
        }
        // 端上先裁静音：省一次计费上传，也避免把纯静音送去识别。
        // 但**裁不出来也不放弃** —— 宁可整段送过去让服务端判断，也比直接退回谐音原文好。
        // （曾经就是在这里 return 掉，用户看到的效果是「功能完全没生效」）
        val trimmed = VadPolicy.trim(
            samples,
            VadPolicy.Params(sampleRate = RealtimeAudioController.SAMPLE_RATE)
        )
        val speech = if (trimmed != null) {
            samples.copyOfRange(trimmed.startSample, trimmed.endSample)
        } else {
            android.util.Log.i(TAG_STT, "裁剪判定无有效语音（${samples.size} 样本）→ 改送整段")
            samples
        }
        val wav = WavWriter.mono16(Pcm16.toBytes(speech), RealtimeAudioController.SAMPLE_RATE)
        android.util.Log.i(
            TAG_STT,
            "SEND ${speech.size} 样本（原 ${samples.size}）原文='${rawText.take(30)}'"
        )

        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            // 文件名带纳秒：连续两句的识别可能重叠，共用一个名字会互相删掉对方的文件
            val file = File(cacheDir, "rt_utterance_${System.nanoTime()}.wav")
            val result = withContext(Dispatchers.IO) {
                try {
                    file.writeBytes(wav)
                    AppGraph.providerRegistry.factory(module.id)
                        .stt().transcribe(file, module.id.sttLanguage)
                } finally {
                    file.delete()
                }
            }
            val cost = SystemClock.elapsedRealtime() - started
            when (result) {
                is AiResult.Ok -> {
                    val corrected = result.value.trim()
                    if (corrected.isEmpty()) {
                        bubble.text = rawText
                        android.util.Log.i(TAG_STT, "EMPTY ${cost}ms → 回退原文本")
                    } else {
                        bubble.text = corrected
                        // recordedTurns 里那条也要一起改：结算页与会话落盘读的是它
                        if (turnIndex in recordedTurns.indices &&
                            recordedTurns[turnIndex].role == "user"
                        ) {
                            recordedTurns[turnIndex] = SessionTurn("user", corrected)
                        }
                        android.util.Log.i(TAG_STT, "CORRECTED ${cost}ms len=${corrected.length}")
                    }
                }
                is AiResult.Err -> {
                    bubble.text = rawText
                    android.util.Log.w(TAG_STT, "FAIL ${cost}ms ${result.error} → 回退原文本")
                }
            }
            binding.transcriptScroll.post {
                binding.transcriptScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun newAiBubble(): TextView =
        TextView(this).apply {
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

        /** 用户字幕纠错专用的日志标签，便于真机验证：SEND / CORRECTED / EMPTY / FAIL / SKIP */
        private const val TAG_STT = "ShiyuStt"

        const val EXTRA_SCENARIO_ID = "extra_scenario_id"

        /** 单次实时会话最多计 2 小时，防止挂着页面不管导致统计虚高 */
        private const val MAX_SESSION_SECONDS = 2L * 3600L

        fun intent(context: android.content.Context, scenarioId: String): Intent =
            Intent(context, VoiceChatActivity::class.java)
                .putExtra(EXTRA_SCENARIO_ID, scenarioId)
    }
}

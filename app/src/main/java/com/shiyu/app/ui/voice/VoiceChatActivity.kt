package com.shiyu.app.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.launch
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
    private var audio: RealtimeAudioController? = null
    private var running = false

    private val handler = Handler(Looper.getMainLooper())
    private var startElapsed = 0L
    private var turns = 0
    private var recorded = false

    /** 本轮的完整往返，结束时整条写入 SessionStore（实时语音不逐轮落盘） */
    private val recordedTurns = mutableListOf<SessionTurn>()

    /** 本轮的基础系统提示（不随语言开关变化），与当前语言模式 */
    private var basePrompt = ""
    private var languageMode = VoicePrompts.LanguageMode.TARGET

    private val bars = arrayOfNulls<View>(21)
    private var aiBubble: TextView? = null
    private val aiBuffer = StringBuilder()
    private var userBubble: TextView? = null
    private val userBuffer = StringBuilder()

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
        fitBottomInset(binding.voiceControls)

        // 语伴名/头像一律从 LangStyle 取，不要写回布局。
        // 之前准备层的「和 Yuki 聊聊天」和「ゆ」头像是硬编码在 XML 里的，
        // 切到英语后仍然显示日语语伴，和首页对不上。
        val partner = LangStyle.partnerName(lang)
        binding.voicePartnerName.text = partner
        binding.voicePartnerSub.text = "AI 语伴 · " + lang.displayName
        binding.readyTitle.text = getString(R.string.voice_ready_title, partner)
        binding.readyAvatar.text = partner.firstOrNull()?.uppercase().orEmpty()

        buildWave()

        binding.btnBack.setOnClickListener { endSession() }
        binding.btnStartVoice.setOnClickListener { requestMicAndStart() }
        binding.btnMic.setOnClickListener { toggleListening() }
        binding.btnCc.setOnClickListener {
            binding.transcriptScroll.visibility =
                if (binding.transcriptScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.btnHint.setOnClickListener {
            appendAiLine("提示：" + scenario.goalExpressions.firstOrNull().orEmpty())
        }
        binding.btnAgain.setOnClickListener { resetSession() }
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
            bar.setBackgroundResource(R.drawable.bg_soft)
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
        binding.voiceState.text = getString(R.string.voice_listening)
        handler.post(ticker)

        // 语音模式必须用专门的提示词：场景自带的 systemPrompt 是给文字对话写的，
        // 要求输出 [JA]/[ZH]/[FIX] 三段式，语音下会被整个念出来（见 VoicePrompts 注释）
        val voicePrompt = VoicePrompts.forScenario(
            module.id, scenario.titleZh, scenario.goalExpressions
        )
        basePrompt = voicePrompt
        languageMode = VoicePrompts.LanguageMode.TARGET
        val p = AppGraph.providerRegistry.factory(module.id).realtime(voicePrompt)
        provider = p
        p.setListener(listener)

        audio = RealtimeAudioController(
            onAudioFrame = { pcm -> p.sendAudio(pcm) },
            onSpeechStateChanged = { active ->
                runOnUiThread {
                    binding.voiceGateHint.visibility = if (active) View.GONE else View.VISIBLE
                }
            },
            onAmplitude = { amp -> runOnUiThread { pushAmplitude(amp) } }
        )
        p.connect(voicePrompt)
        audio?.startCapture()
    }

    private fun toggleListening() {
        if (!running) return
        // 手动打断：停播 + 清服务端音频缓冲，立即回到聆听
        android.util.Log.i(TAG, "tap 打断：audio=${audio != null} provider=${provider != null}")
        audio?.interrupt()
        provider?.cancelResponse()
        binding.voiceState.text = getString(R.string.voice_listening)
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
            audio?.stopCapture()
            audio?.stopPlayback()
            runOnUiThread {
                binding.voiceState.text = getString(R.string.voice_reconnect_failed)
            }
        }

        override fun onConnected() {
            runOnUiThread { binding.voiceState.text = getString(R.string.voice_listening) }
        }

        override fun onUserSpeechStarted() {
            // barge-in：用户一开口就停播，并让服务端也停掉当前这轮生成。
            // 只调 interrupt() 是不够的 —— 服务端还在往下推流，
            // 残留的音频包会立刻把播放重新拉起来（这就是"打断没反应"）。
            audio?.interrupt()
            provider?.cancelResponse()
            runOnUiThread { binding.voiceState.text = getString(R.string.voice_listening) }
        }

        override fun onUserSpeechStopped() {
            runOnUiThread { binding.voiceState.text = "思考中…" }
        }

        override fun onAudioDelta(pcm: ByteArray) {
            audio?.playPcm(pcm)
        }

        override fun onTranscriptDelta(text: String) {
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
            if (text.isNotBlank()) {
                recordedTurns.add(SessionTurn("user", text))
                applyLanguageSwitchIfRequested(text)
            }
            runOnUiThread {
                turns++
                val bubble = newUserBubble()
                userBuffer.append(text)
                bubble.text = userBuffer.toString()
                binding.transcriptScroll.post {
                    binding.transcriptScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        override fun onResponseDone() {
            // 一轮 AI 回复结束，把攒下的字幕整句记下来（增量是逐字来的，只有 done 时才是完整句）
            val said = aiBuffer.toString().trim()
            if (said.isNotEmpty()) {
                recordedTurns.add(SessionTurn("assistant", said))
                translateInto(aiBubble, said)
                aiBubble = null
            }
            aiBuffer.clear()
            runOnUiThread {
                aiBuffer.clear()
                aiBubble = null
                userBuffer.clear()
                userBubble = null
                binding.voiceState.text = getString(R.string.voice_listening)
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

    private fun newAiBubble(): TextView =
        TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.cream))
            setBackgroundResource(R.drawable.bg_card)
            setPadding(28, 20, 28, 20)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
            layoutParams = lp
            binding.transcriptContainer.addView(this)
            aiBubble = this
            this@VoiceChatActivity.aiBubble = this
        }

    private fun newUserBubble(): TextView =
        TextView(this).apply {
            textSize = 14f
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
            setTextColor(0xFFA79684.toInt())
            setPadding(4, 8, 4, 8)
        }
        tv.text = text
        binding.transcriptContainer.addView(tv)
    }

    companion object {
        private const val TAG = "ShiyuVoice"

        const val EXTRA_SCENARIO_ID = "extra_scenario_id"

        /** 单次实时会话最多计 2 小时，防止挂着页面不管导致统计虚高 */
        private const val MAX_SESSION_SECONDS = 2L * 3600L

        fun intent(context: android.content.Context, scenarioId: String): Intent =
            Intent(context, VoiceChatActivity::class.java)
                .putExtra(EXTRA_SCENARIO_ID, scenarioId)
    }
}

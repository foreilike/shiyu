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
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.ai.AiResult
import com.shiyu.app.ai.RealtimeVoiceProvider
import com.shiyu.app.audio.RealtimeAudioController
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.lang.LanguageModule
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.data.model.Scenario
import com.shiyu.app.databinding.ActivityVoiceChatBinding
import com.shiyu.app.ui.AiErrorMessages
import com.shiyu.app.ui.BaseActivity
import com.shiyu.app.ui.LangStyle
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

        binding.voicePartnerName.text = LangStyle.partnerName(lang)
        binding.voicePartnerSub.text = "AI 语伴 · " + lang.displayName

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
        binding.btnExit.setOnClickListener { endSession() }

        if (!AppGraph.providerRegistry.isRealtimeEnabled(module.id)) {
            binding.voiceState.text = getString(R.string.voice_mock_note)
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
        aiBubble = null
        userBubble = null
        binding.transcriptContainer.removeAllViews()
        binding.voiceState.text = getString(R.string.voice_listening)
        handler.post(ticker)

        val p = AppGraph.providerRegistry.factory(module.id).realtime(scenario.systemPrompt)
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
        p.connect(scenario.systemPrompt)
        audio?.startCapture()
    }

    private fun toggleListening() {
        if (!running) return
        // 手动打断：停播 + 清服务端音频缓冲，立即回到聆听
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
        if (recorded || startElapsed == 0L || seconds <= 0L) return
        recorded = true
        AppGraph.dailyStatsStore.record(
            LocalDate.now().toEpochDay(),
            voiceSeconds = seconds.coerceAtMost(MAX_SESSION_SECONDS),
            chatTurns = turns
        )
    }

    override fun onDestroy() {
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
        override fun onConnected() {
            runOnUiThread { binding.voiceState.text = getString(R.string.voice_listening) }
        }

        override fun onUserSpeechStarted() {
            // barge-in：用户一开口就停播，杜绝回环
            audio?.interrupt()
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
        const val EXTRA_SCENARIO_ID = "extra_scenario_id"

        /** 单次实时会话最多计 2 小时，防止挂着页面不管导致统计虚高 */
        private const val MAX_SESSION_SECONDS = 2L * 3600L

        fun intent(context: android.content.Context, scenarioId: String): Intent =
            Intent(context, VoiceChatActivity::class.java)
                .putExtra(EXTRA_SCENARIO_ID, scenarioId)
    }
}

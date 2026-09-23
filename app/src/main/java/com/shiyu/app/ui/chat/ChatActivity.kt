package com.shiyu.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.ai.AiResult
import com.shiyu.app.ai.ChatMessage
import com.shiyu.app.ai.ChatRequest
import com.shiyu.app.audio.AudioVadTrimmer
import com.shiyu.app.core.chat.ParsedReply
import com.shiyu.app.core.chat.ReplyParser
import com.shiyu.app.core.context.ContextTrimmer
import com.shiyu.app.core.lang.LanguageModule
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.data.model.Scenario
import com.shiyu.app.data.store.SettingsRepository
import com.shiyu.app.databinding.ActivityChatBinding
import com.shiyu.app.ui.AiErrorMessages
import com.shiyu.app.ui.BaseActivity
import java.time.LocalDate
import com.shiyu.app.ui.Speaker
import com.shiyu.app.ui.WordTtsPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 对话页（M2）：语音/文字输入 → 转写 → 大模型 → 解析（正文/翻译/纠错）→ 逐句可朗读 + TTS 自动播放。
 * 必须具备的降级：麦克风权限被拒 / 未配置识别 / 识别失败 → 文字输入照常可用；
 * 三个 Provider 都未配置 → Mock 跑完整一轮。
 */
class ChatActivity : BaseActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var module: LanguageModule
    private lateinit var scenario: Scenario
    private var sessionId: String = ""

    private val items = mutableListOf<MsgItem>()
    private lateinit var adapter: MessageAdapter
    private var speaker: Speaker? = null

    /** 云优先朗读：日语点句重播走云端 TTS（避开系统 TTS 联网找 Google 在国内网络的静默失败），失败回落 [speaker]。 */
    private var ttsPlayer: WordTtsPlayer? = null

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recording = false
    private var player: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable {
        if (recording) stopRecordingAndSend()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.btnRecord.isEnabled = true
                hideHint()
            } else {
                binding.btnRecord.isEnabled = false
                showHint(getString(R.string.chat_no_mic_permission))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 页头吃状态栏、输入区吃导航栏/键盘（设计稿每屏自带页头，没有系统 ActionBar）
        fitTopInset(binding.scenarioHeader)
        fitBottomInsetWithIme(binding.inputRow)

        speaker = Speaker(this)

        val lang = AppGraph.settingsRepository.activeLang()
        module = LanguageRegistry.module(lang)
        speaker?.setLanguage(module.id)
        ttsPlayer = WordTtsPlayer(this, speaker!!)
        ttsPlayer?.onNeedNetwork = {
            Toast.makeText(this, R.string.tts_need_network, Toast.LENGTH_SHORT).show()
        }

        val scenarioId = intent.getStringExtra(EXTRA_SCENARIO_ID)
        val loaded = module.buildScenarios(AppGraph.assetRepository.scenariosJson(module))
            .firstOrNull { it.id == scenarioId }
        if (loaded == null) {
            finish()
            return
        }
        scenario = loaded

        binding.scenarioHeader.text = scenario.titleTarget + " · " + scenario.titleZh
        binding.goalText.text =
            getString(R.string.chat_goal) + "：" + scenario.goalExpressions.joinToString("　")
        binding.goalText.visibility = View.VISIBLE

        // 点历史消息重播：录音期间不外放（回环双保险）；日语走云端 TTS，失败回落系统 Speaker
        adapter = MessageAdapter(items) { sentence ->
            if (!recording) ttsPlayer?.speakWord(lifecycleScope, module.id, sentence)
        }
        binding.messageList.layoutManager = LinearLayoutManager(this)
        binding.messageList.adapter = adapter

        // 进入场景：新建 sessionId，写入 AI 开场白
        sessionId = AppGraph.sessionStore.createSession(module.id, scenario.id).sessionId
        appendAssistantText(scenario.openingLine, ParsedReply(scenario.openingLine, "", ""))
        AppGraph.sessionStore.appendTurn(sessionId, "assistant", scenario.openingLine)

        binding.btnSend.setOnClickListener { sendText(binding.messageInput.text.toString()) }
        binding.btnRecord.setOnClickListener { toggleRecording() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        if (!AppGraph.providerRegistry.isChatEnabled(module.id)) {
            showHint(getString(R.string.chat_mock_note))
        }
    }

    /**
     * 切后台就释放录音与外放。
     *
     * 原先只在 onDestroy 释放 —— 录音中按 Home 键，MediaRecorder 会继续开着麦克风
     * （费电、隐私观感也差），MediaPlayer 还在后台外放。
     *
     * 录音中被切走的话，先把这一段发出去再释放 —— 用户按 Home 通常不是想取消这句话。
     */
    override fun onStop() {
        if (recording) {
            stopRecordingAndSend()
        } else {
            releaseRecorder()
            releasePlayer()
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStopRunnable)
        releaseRecorder()
        releasePlayer()
        ttsPlayer?.releasePlayer()
        ttsPlayer = null
        speaker?.shutdown()
        super.onDestroy()
    }

    // ---------- 输入 ----------

    private fun sendText(text: String) {
        if (text.isBlank()) return
        binding.messageInput.setText("")
        appendUserText(text)
        // 落盘挪到 IO：发消息是输入框回调，主线程同步写会话文件 + 统计会卡输入响应
        val sid = sessionId
        lifecycleScope.launch(Dispatchers.IO) {
            AppGraph.sessionStore.appendTurn(sid, "user", text)
            AppGraph.dailyStatsStore.record(LocalDate.now().toEpochDay(), chatTurns = 1)
        }
        runTurn()
    }

    private fun toggleRecording() {
        if (recording) {
            stopRecordingAndSend()
        } else {
            startRecording()
        }
    }

    // ---------- 录音 ----------

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showHint(getString(R.string.chat_no_mic_permission))
            return
        }
        // 回环防护：录音前切断一切外放（TTS 合成音频 + 系统 TTS），录音期间持续抑制
        speaker?.setSuppressed(true)
        releasePlayer()
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        recordingFile = file
        recorder = try {
            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(64000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }.also {
                recording = true
                binding.btnRecord.setText(R.string.chat_stop)
                showHint(getString(R.string.chat_recording))
                // 计费防护：最长 60 秒，到点自动停止并发送，防遗忘导致超长音频上传
                handler.postDelayed(autoStopRunnable, RECORD_MAX_MS)
            }
        } catch (e: Exception) {
            recording = false
            recorder = null
            speaker?.setSuppressed(false)
            // 这里失败绝大多数不是权限问题（权限在函数开头已经查过），
            // 而是麦克风被别的 App 占用 / 机型不支持该参数组合。
            // 一律提示"未获得麦克风权限"会让用户去改权限却永远修不好。
            showHint(getString(R.string.chat_recorder_failed))
            null
        }
    }

    private fun stopRecordingAndSend() {
        val file = recordingFile
        handler.removeCallbacks(autoStopRunnable)
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // 录音过短等情况：按失败处理
        }
        releaseRecorder()
        recording = false
        speaker?.setSuppressed(false)
        binding.btnRecord.setText(R.string.chat_record)
        binding.hintText.visibility = View.GONE

        if (file == null || !file.exists() || file.length() == 0L) {
            // 同上：录到空文件通常是按得太快、录音时长过短，跟权限无关
            showHint(getString(R.string.chat_recording_too_short))
            return
        }
        if (!AppGraph.providerRegistry.isSttEnabled(module.id)) {
            showHint(getString(R.string.chat_no_stt))
            return
        }
        // 智能转换不穿透非内联 lambda，固化为非空局部变量
        val original: File = file
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            // 计费防护：上传前裁掉头尾静音（纯端上处理，失败/全静音自动回退原文件）
            var uploadFile: File = original
            val trimmed = withContext(Dispatchers.IO) {
                AudioVadTrimmer.trim(original, cacheDir)?.also { outcome ->
                    original.delete()
                    uploadFile = outcome.file
                }
            }
            if (trimmed != null) {
                showHint(
                    "已裁剪静音 ${trimmed.trimmedMs / 1000}s" +
                        "（${trimmed.originalMs / 1000}s → ${trimmed.keptMs / 1000}s）"
                )
            }
            val result = withContext(Dispatchers.IO) {
                AppGraph.providerRegistry.factory(module.id)
                    .stt().transcribe(uploadFile, module.id.sttLanguage)
            }
            binding.progressBar.visibility = View.GONE
            if (uploadFile !== original) uploadFile.delete()
            when (result) {
                is AiResult.Ok -> {
                    val text = result.value.trim()
                    if (text.isEmpty()) {
                        showHint(getString(R.string.chat_stt_empty))
                    } else {
                        sendText(text)
                    }
                }
                is AiResult.Err -> showHint(
                    getString(R.string.chat_no_stt) + "（" + AiErrorMessages.describe(this@ChatActivity, result.error) + "）"
                )
            }
        }
    }

    private fun releaseRecorder() {
        try {
            recorder?.release()
        } catch (e: Exception) {
            // 忽略
        }
        recorder = null
    }

    // ---------- 对话链路 ----------

    private fun runTurn() {
        val request = ChatRequest(
            model = AppGraph.settingsRepository.groupFor(module.id, SettingsRepository.Group.CHAT).model,
            messages = ContextTrimmer.trim(scenario.systemPrompt, history())
        )
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = withContext(Dispatchers.IO) {
                AppGraph.providerRegistry.factory(module.id).chat().chat(request)
            }
            binding.progressBar.visibility = View.GONE
            when (result) {
                is AiResult.Ok -> {
                    val parsed = ReplyParser.parse(module, result.value)
                    appendAssistantText(result.value, parsed)
                    // 落盘挪到 IO：appendTurn 会 load+整文件重写会话 JSON（随对话变长 O(n)），
                    // 主线程同步写会卡 UI。用户轮早已挪到 IO，助手轮之前漏了。
                    withContext(Dispatchers.IO) {
                        AppGraph.sessionStore.appendTurn(sessionId, "assistant", result.value)
                    }
                    maybeAutoPlay(parsed)
                }
                is AiResult.Err -> showHint(AiErrorMessages.describe(this@ChatActivity, result.error))
            }
        }
    }

    private fun history(): List<ChatMessage> =
        items.map { ChatMessage(it.role, it.raw) }

    private fun appendUserText(text: String) {
        items.add(MsgItem("user", text, listOf(text), "", ""))
        adapter.notifyItemInserted(items.size - 1)
        scrollToBottom()
    }

    private fun appendAssistantText(raw: String, parsed: ParsedReply) {
        val sentences = if (parsed.body.isNotEmpty()) {
            module.splitSentences(parsed.body)
        } else {
            listOf(raw)
        }
        items.add(MsgItem("assistant", raw, sentences, parsed.translation, parsed.fix))
        adapter.notifyItemInserted(items.size - 1)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        if (items.isNotEmpty()) {
            binding.messageList.smoothScrollToPosition(items.size - 1)
        }
    }

    private fun showHint(text: String) {
        binding.hintText.text = text
        binding.hintText.visibility = View.VISIBLE
    }

    private fun hideHint() {
        binding.hintText.visibility = View.GONE
    }

    // ---------- 朗读 ----------

    private fun maybeAutoPlay(parsed: ParsedReply) {
        if (!AppGraph.settingsRepository.autoPlayTts()) return
        if (recording) return // 录音期间不外放：回环双保险（Speaker.suppressed 之外的第二道闸）
        if (!AppGraph.providerRegistry.isTtsEnabled(module.id)) return // 静默跳过
        if (parsed.body.isEmpty()) return
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val result = withContext(Dispatchers.IO) {
                AppGraph.providerRegistry.factory(module.id).tts().synthesize(parsed.body, null)
            }
            binding.progressBar.visibility = View.GONE
            when (result) {
                is AiResult.Ok -> playAudio(result.value)
                is AiResult.Err -> { /* 朗读失败不打扰对话，静默忽略 */ }
            }
        }
    }

    private fun playAudio(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        releasePlayer()
        val file = File(cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        try {
            file.writeBytes(bytes)
        } catch (e: IOException) {
            Toast.makeText(this, R.string.chat_play_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // MediaPlayer 的构造/设源/prepare 都可能抛（服务端返回截断或非音频字节时），
        // 原先只有 writeBytes 在 try 里，这三步在外面 —— 坏音频直接把 App 崩掉。
        try {
            player = MediaPlayer().apply {
                setOnCompletionListener { releasePlayer(); file.delete() }
                setOnErrorListener { _, _, _ -> releasePlayer(); file.delete(); true }
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            releasePlayer()
            try { file.delete() } catch (ignored: Exception) { /* 忽略 */ }
            Toast.makeText(this, R.string.chat_play_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (e: Exception) {
            // 忽略
        }
        player = null
    }

    companion object {
        const val EXTRA_SCENARIO_ID = "extra_scenario_id"

        /** 单段录音最长 60 秒：到点自动停止发送，防遗忘与超长音频计费。 */
        private const val RECORD_MAX_MS = 60_000L
    }
}

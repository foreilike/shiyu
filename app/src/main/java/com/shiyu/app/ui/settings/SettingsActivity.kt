package com.shiyu.app.ui.settings

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.ai.AiResult
import com.shiyu.app.ai.ChatMessage
import com.shiyu.app.ai.ChatRequest
import com.shiyu.app.ai.RealtimeVoiceProvider
import com.shiyu.app.ai.openai.OkHttpTransport
import com.shiyu.app.ai.openai.OpenAiChatProvider
import com.shiyu.app.ai.openai.OpenAiSttProvider
import com.shiyu.app.ai.openai.OpenAiTtsProvider
import com.shiyu.app.ai.openai.StepfunRealtimeProvider
import com.shiyu.app.core.lang.LangId
import com.shiyu.app.core.log.LogRedactor
import com.shiyu.app.core.net.BaseUrlNormalizer
import com.shiyu.app.data.model.ProviderConfig
import com.shiyu.app.data.model.RealtimeOptions
import com.shiyu.app.data.store.SettingsRepository
import com.shiyu.app.databinding.ActivitySettingsBinding
import com.shiyu.app.ui.AiErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 设置页（M3 / M5-3）：按语言分别配置 8 组 Provider（对话/识别/合成/实时语音 × 2 语言）。
 * apiKey 默认掩码，点击「显示」才明文；离开/切语言回到掩码。
 * 「测试连接」按 §5.5 映射中文提示；REALTIME 组的测试 = WebSocket 握手等 session.created。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val transport = OkHttpTransport()
    private val realKeys = mutableMapOf<SettingsRepository.Group, String>()
    private val keyVisible = mutableMapOf<SettingsRepository.Group, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.langBar.langGroup.setOnCheckedChangeListener { _, _ ->
            val lang = selectedLang()
            if (lang != AppGraph.settingsRepository.activeLang()) {
                AppGraph.settingsRepository.setActiveLang(lang)
                loadForm()
            }
        }
        binding.btnSave.setOnClickListener { saveForm() }

        setupKeyToggle(SettingsRepository.Group.CHAT, binding.chatApiKey, binding.chatKeyToggle)
        setupKeyToggle(SettingsRepository.Group.ASR, binding.asrApiKey, binding.asrKeyToggle)
        setupKeyToggle(SettingsRepository.Group.TTS, binding.ttsApiKey, binding.ttsKeyToggle)
        setupKeyToggle(SettingsRepository.Group.REALTIME, binding.realtimeApiKey, binding.realtimeKeyToggle)

        binding.chatTest.setOnClickListener { testGroup(SettingsRepository.Group.CHAT) }
        binding.asrTest.setOnClickListener { testGroup(SettingsRepository.Group.ASR) }
        binding.ttsTest.setOnClickListener { testGroup(SettingsRepository.Group.TTS) }
        binding.realtimeTest.setOnClickListener { testGroup(SettingsRepository.Group.REALTIME) }
    }

    override fun onResume() {
        super.onResume()
        syncLangBar()
        loadForm()
    }

    private fun selectedLang(): LangId =
        if (binding.langBar.radioEn.isChecked) LangId.EN else LangId.JA

    private fun syncLangBar() {
        val lang = AppGraph.settingsRepository.activeLang()
        binding.langBar.langGroup.setOnCheckedChangeListener(null)
        binding.langBar.radioJa.isChecked = lang == LangId.JA
        binding.langBar.radioEn.isChecked = lang == LangId.EN
        binding.langBar.langGroup.setOnCheckedChangeListener { _, _ ->
            val selected = selectedLang()
            if (selected != AppGraph.settingsRepository.activeLang()) {
                AppGraph.settingsRepository.setActiveLang(selected)
                loadForm()
            }
        }
    }

    // ---------- 表单 ----------

    private fun loadForm() {
        val lang = AppGraph.settingsRepository.activeLang()
        keyVisible.clear()
        for (group in SettingsRepository.Group.values()) keyVisible[group] = false

        loadGroup(
            SettingsRepository.Group.CHAT,
            binding.chatBaseUrl, binding.chatApiKey, binding.chatModel,
            binding.chatKeyToggle, null, binding.switchChat
        )
        loadGroup(
            SettingsRepository.Group.ASR,
            binding.asrBaseUrl, binding.asrApiKey, binding.asrModel,
            binding.asrKeyToggle, null, binding.switchAsr
        )
        loadGroup(
            SettingsRepository.Group.TTS,
            binding.ttsBaseUrl, binding.ttsApiKey, binding.ttsModel,
            binding.ttsKeyToggle, binding.ttsVoice, binding.switchTts
        )
        loadGroup(
            SettingsRepository.Group.REALTIME,
            binding.realtimeBaseUrl, binding.realtimeApiKey, binding.realtimeModel,
            binding.realtimeKeyToggle, binding.realtimeVoice, binding.switchRealtime
        )

        val opts = AppGraph.settingsRepository.realtimeOptionsFor(lang)
        binding.switchServerVad.isChecked = opts.serverVad
        binding.realtimeSilence.setText(opts.silenceDurationMs.toString())
        binding.realtimeEnergy.setText(opts.energyAwakenessThreshold.toString())
        binding.realtimePrefix.setText(opts.prefixPaddingMs.toString())
    }

    private fun loadGroup(
        group: SettingsRepository.Group,
        baseUrlField: EditText,
        keyField: EditText,
        modelField: EditText,
        keyToggle: android.widget.Button,
        voiceField: EditText?,
        enabled: Switch
    ) {
        val lang = AppGraph.settingsRepository.activeLang()
        val config = AppGraph.settingsRepository.groupFor(lang, group)
        realKeys[group] = config.apiKey
        baseUrlField.setText(config.baseUrl)
        modelField.setText(config.model)
        voiceField?.setText(config.voice)
        enabled.isChecked = config.enabled
        renderKey(group, keyField, keyToggle)
    }

    private fun renderKey(
        group: SettingsRepository.Group,
        keyField: EditText,
        keyToggle: android.widget.Button
    ) {
        val key = realKeys[group] ?: ""
        if (keyVisible[group] == true) {
            keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            keyField.setText(key)
            keyToggle.setText(R.string.settings_key_hide)
        } else {
            keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            keyField.setText(if (key.isEmpty()) "" else LogRedactor.mask(key))
            keyField.isFocusable = false
            keyField.isFocusableInTouchMode = false
            keyField.isCursorVisible = false
            keyToggle.setText(R.string.settings_key_show)
        }
    }

    private fun setupKeyToggle(
        group: SettingsRepository.Group,
        keyField: EditText,
        keyToggle: android.widget.Button
    ) {
        keyToggle.setOnClickListener {
            val nowVisible = keyVisible[group] != true
            keyVisible[group] = nowVisible
            if (nowVisible) {
                keyField.isFocusable = true
                keyField.isFocusableInTouchMode = true
                keyField.isCursorVisible = true
            }
            renderKey(group, keyField, keyToggle)
        }
    }

    private fun saveForm() {
        val lang = AppGraph.settingsRepository.activeLang()
        val groups = listOf(
            Triple(SettingsRepository.Group.CHAT, binding.chatBaseUrl, binding.chatModel),
            Triple(SettingsRepository.Group.ASR, binding.asrBaseUrl, binding.asrModel),
            Triple(SettingsRepository.Group.TTS, binding.ttsBaseUrl, binding.ttsModel),
            Triple(SettingsRepository.Group.REALTIME, binding.realtimeBaseUrl, binding.realtimeModel)
        )
        // baseUrl 校验：非空时必须能规范化（https / wss）
        val normalizedUrls = mutableMapOf<SettingsRepository.Group, String>()
        for ((group, baseUrlField, _) in groups) {
            val raw = baseUrlField.text.toString().trim()
            if (raw.isEmpty()) {
                normalizedUrls[group] = ""
                continue
            }
            val normalized = BaseUrlNormalizer.normalize(raw)
            if (normalized == null) {
                Toast.makeText(this, R.string.settings_https_only, Toast.LENGTH_LONG).show()
                return
            }
            normalizedUrls[group] = normalized
        }

        saveGroup(
            SettingsRepository.Group.CHAT, normalizedUrls,
            binding.chatApiKey, binding.chatModel, null, binding.switchChat
        )
        saveGroup(
            SettingsRepository.Group.ASR, normalizedUrls,
            binding.asrApiKey, binding.asrModel, null, binding.switchAsr
        )
        saveGroup(
            SettingsRepository.Group.TTS, normalizedUrls,
            binding.ttsApiKey, binding.ttsModel, binding.ttsVoice, binding.switchTts
        )
        saveGroup(
            SettingsRepository.Group.REALTIME, normalizedUrls,
            binding.realtimeApiKey, binding.realtimeModel, binding.realtimeVoice, binding.switchRealtime
        )

        // 实时语音附加参数（钳制到合法范围）
        val silence = binding.realtimeSilence.text.toString().trim().toIntOrNull() ?: 100
        val energy = binding.realtimeEnergy.text.toString().trim().toIntOrNull() ?: 2500
        val prefix = binding.realtimePrefix.text.toString().trim().toIntOrNull() ?: 500
        AppGraph.settingsRepository.updateRealtimeOptions(
            lang,
            RealtimeOptions(
                serverVad = binding.switchServerVad.isChecked,
                silenceDurationMs = silence.coerceIn(0, 5000),
                energyAwakenessThreshold = energy.coerceIn(0, 5000),
                prefixPaddingMs = prefix.coerceIn(0, 5000)
            )
        )
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        loadForm()
    }

    private fun saveGroup(
        group: SettingsRepository.Group,
        normalizedUrls: Map<SettingsRepository.Group, String>,
        keyField: EditText,
        modelField: EditText,
        voiceField: EditText?,
        enabled: Switch
    ) {
        val lang = AppGraph.settingsRepository.activeLang()
        val existing = AppGraph.settingsRepository.groupFor(lang, group)
        val key = if (keyVisible[group] == true) {
            keyField.text.toString().trim()
        } else {
            existing.apiKey // 掩码状态不允许编辑，保持原值
        }
        val config = ProviderConfig(
            baseUrl = normalizedUrls[group] ?: existing.baseUrl,
            apiKey = key,
            model = modelField.text.toString().trim(),
            voice = voiceField?.text?.toString()?.trim() ?: existing.voice,
            enabled = enabled.isChecked
        )
        AppGraph.settingsRepository.update(lang, group, config)
        realKeys[group] = key
    }

    // ---------- 测试连接 ----------

    private fun testGroup(group: SettingsRepository.Group) {
        val rawBase = when (group) {
            SettingsRepository.Group.CHAT -> binding.chatBaseUrl.text.toString()
            SettingsRepository.Group.ASR -> binding.asrBaseUrl.text.toString()
            SettingsRepository.Group.TTS -> binding.ttsBaseUrl.text.toString()
            SettingsRepository.Group.REALTIME -> binding.realtimeBaseUrl.text.toString()
        }.trim()
        val base = BaseUrlNormalizer.normalize(rawBase)
        if (base == null) {
            Toast.makeText(this, R.string.settings_https_only, Toast.LENGTH_LONG).show()
            return
        }
        val key = realKeys[group] ?: ""
        val model = when (group) {
            SettingsRepository.Group.CHAT -> binding.chatModel.text.toString().trim()
            SettingsRepository.Group.ASR -> binding.asrModel.text.toString().trim()
            SettingsRepository.Group.TTS -> binding.ttsModel.text.toString().trim()
            SettingsRepository.Group.REALTIME -> binding.realtimeModel.text.toString().trim()
        }
        val voice = binding.ttsVoice.text.toString().trim()

        val config = ProviderConfig(
            baseUrl = base,
            apiKey = key,
            model = model,
            voice = voice,
            enabled = true
        )

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { runProbe(group, config) }
            Toast.makeText(
                this@SettingsActivity,
                AiErrorMessages.describeTest(this@SettingsActivity, error),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 返回 null 表示成功；否则返回分类后的错误 */
    private suspend fun runProbe(
        group: SettingsRepository.Group,
        config: ProviderConfig
    ): com.shiyu.app.ai.AiError? {
        return when (group) {
            SettingsRepository.Group.CHAT -> {
                val provider = OpenAiChatProvider(config, transport)
                val result = provider.chat(
                    ChatRequest(model = config.model, messages = listOf(ChatMessage("user", "Hi")))
                )
                (result as? AiResult.Err)?.error
            }
            SettingsRepository.Group.ASR -> {
                val probe = File(cacheDir, "probe_audio.m4a")
                probe.writeBytes(ByteArray(64) { 0 })
                val provider = OpenAiSttProvider(config, transport)
                val result = provider.transcribe(probe, langStt())
                probe.delete()
                (result as? AiResult.Err)?.error
            }
            SettingsRepository.Group.TTS -> {
                val provider = OpenAiTtsProvider(config, transport)
                val result = provider.synthesize("你好", config.voice.ifBlank { null })
                (result as? AiResult.Err)?.error
            }
            SettingsRepository.Group.REALTIME -> probeRealtime(config)
        }
    }

    /** REALTIME 测试：建连并等 session.created；8 秒超时。 */
    private suspend fun probeRealtime(config: ProviderConfig): com.shiyu.app.ai.AiError? =
        suspendCancellableCoroutine { cont ->
            val provider = StepfunRealtimeProvider(config, RealtimeOptions())
            var done = false
            fun finish(error: com.shiyu.app.ai.AiError?) {
                if (!done) {
                    done = true
                    provider.close()
                    if (cont.isActive) cont.resume(error)
                }
            }
            provider.setListener(object : RealtimeVoiceProvider.Listener {
                override fun onConnected() = finish(null)
                override fun onUserSpeechStarted() {}
                override fun onUserSpeechStopped() {}
                override fun onAudioDelta(pcm: ByteArray) {}
                override fun onTranscriptDelta(text: String) {}
                override fun onUserTranscript(text: String) {}
                override fun onResponseDone() {}
                override fun onError(error: com.shiyu.app.ai.AiError) = finish(error)
                override fun onClosed() {}
            })
            provider.connect("连接测试")
            // 超时看门狗
            Thread {
                Thread.sleep(8000)
                finish(com.shiyu.app.ai.AiError.Timeout(8000))
            }.start()
        }

    private fun langStt(): String =
        com.shiyu.app.core.lang.LanguageRegistry.module(selectedLang()).id.sttLanguage
}

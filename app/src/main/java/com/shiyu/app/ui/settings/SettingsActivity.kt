package com.shiyu.app.ui.settings

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
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
import com.shiyu.app.ui.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 设置页（M3 / M5-3，二级页）：按语言分别配置 8 组 Provider（对话/识别/合成/实时语音 × 2 语言）。
 * apiKey 默认掩码，点击「显示」才明文；离开/切语言回到掩码。
 * 「测试连接」按 §5.5 映射中文提示；REALTIME 组的测试 = WebSocket 握手等 session.created。
 *
 * 改造后语言切换条移到「课程」Tab，这里只配置当前语言，标题上标明是哪一门。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val transport = OkHttpTransport()
    private val realKeys = mutableMapOf<SettingsRepository.Group, String>()
    private val keyVisible = mutableMapOf<SettingsRepository.Group, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitTopInset(binding.headerBar.subHeader)
        fitBottomInsetWithIme(binding.settingsScroll)
        binding.headerBar.btnBack.setOnClickListener { finish() }

        binding.btnSave.setOnClickListener { saveForm() }
        binding.btnSyncAll.setOnClickListener { confirmSyncAll() }
        binding.langChipJa.setOnClickListener { switchConfigLang(LangId.JA) }
        binding.langChipEn.setOnClickListener { switchConfigLang(LangId.EN) }

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
        val lang = AppGraph.settingsRepository.activeLang()
        binding.headerBar.subTitle.text =
            "${getString(R.string.settings_title)} · ${lang.displayName}"
        renderLangChips(lang)
        loadForm()
    }

    // ---------- 配置语言切换 ----------

    /**
     * 设置页里也放一组语言芯片：8 组配置是「4 能力 × 2 语言」，
     * 只在「课程」Tab 能切语言的话，用户在这页会看到「设置 · 日语」却切不了，体验是断的。
     * 这里切的就是全局 activeLang，与课程页同一个状态。
     */
    private fun switchConfigLang(lang: LangId) {
        if (lang == AppGraph.settingsRepository.activeLang()) return
        AppGraph.settingsRepository.setActiveLang(lang)
        binding.headerBar.subTitle.text =
            "${getString(R.string.settings_title)} · ${lang.displayName}"
        renderLangChips(lang)
        loadForm()
        Toast.makeText(
            this,
            getString(R.string.settings_switch_lang_hint, lang.displayName),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun renderLangChips(active: LangId) {
        paintChip(binding.langChipJa, active == LangId.JA)
        paintChip(binding.langChipEn, active == LangId.EN)
    }

    private fun paintChip(chip: android.widget.TextView, selected: Boolean) {
        chip.setBackgroundResource(
            if (selected) R.drawable.bg_btn_clay else R.drawable.bg_btn_ghost
        )
        chip.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                this,
                if (selected) R.color.cream else R.color.ink2
            )
        )
    }

    // ---------- 一键同步 ----------

    /**
     * 把「对话」组当前表单里的接口地址与 API Key 复制到其它组。
     *
     * 刻意的两条边界：
     * - **实时语音只同步 Key、不同步地址** —— 它走 wss，地址形如
     *   `wss://api.stepfun.com/v1/realtime`，把 https 的网关地址盖上去必然连不上；
     * - **model / voice / enabled 一律不动** —— 各能力的模型名本来就不同，
     *   开关状态更应该由用户自己决定。
     */
    private fun confirmSyncAll() {
        val rawUrl = binding.chatBaseUrl.text.toString().trim()
        val normalized = if (rawUrl.isEmpty()) "" else BaseUrlNormalizer.normalize(rawUrl)
        if (rawUrl.isNotEmpty() && normalized == null) {
            Toast.makeText(this, R.string.settings_https_only, Toast.LENGTH_LONG).show()
            return
        }
        val chatKey = if (isMasked(SettingsRepository.Group.CHAT)) {
            realKeys[SettingsRepository.Group.CHAT] ?: ""
        } else {
            binding.chatApiKey.text.toString().trim()
        }
        if (normalized.isNullOrEmpty() && chatKey.isEmpty()) {
            Toast.makeText(this, R.string.settings_sync_empty, Toast.LENGTH_LONG).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_sync_all)
            .setMessage(R.string.settings_sync_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ -> applySyncAll(normalized ?: "", chatKey) }
            .show()
    }

    private fun applySyncAll(baseUrl: String, apiKey: String) {
        var touched = 0
        for (lang in LangId.entries) {
            for (group in SettingsRepository.Group.entries) {
                val existing = AppGraph.settingsRepository.groupFor(lang, group)
                val isRealtime = group == SettingsRepository.Group.REALTIME
                val updated = existing.copy(
                    baseUrl = if (isRealtime) existing.baseUrl else baseUrl.ifEmpty { existing.baseUrl },
                    apiKey = apiKey.ifEmpty { existing.apiKey }
                )
                if (updated == existing) continue
                AppGraph.settingsRepository.update(lang, group, updated)
                touched++
            }
        }
        loadForm()
        Toast.makeText(
            this,
            getString(R.string.settings_sync_done, touched),
            Toast.LENGTH_SHORT
        ).show()
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
            binding.realtimeKeyToggle, binding.realtimeVoice, binding.switchRealtime,
            defaultBaseUrl = getString(R.string.realtime_default_url)
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
        enabled: Switch,
        defaultBaseUrl: String? = null
    ) {
        val lang = AppGraph.settingsRepository.activeLang()
        val config = AppGraph.settingsRepository.groupFor(lang, group)
        realKeys[group] = config.apiKey
        // 实时语音的地址没配过时**预填**官方默认值，而不是只留一行灰色提示 ——
        // 提示文字和已填内容长得太像，用户会以为已经填好了，然后对着
        // 「未配置实时语音服务」百思不得其解。
        baseUrlField.setText(config.baseUrl.ifEmpty { defaultBaseUrl.orEmpty() })
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
            keyToggle.setText(R.string.settings_key_show)
        }
        // 只有「已存 Key 且正显示掩码」才锁编辑 —— 锁的目的是防止把掩码文本当成
        // 真 Key 存回去。Key 为空时没有掩码可言，必须能直接输入。
        setEditable(keyField, !isMasked(group))
    }

    /** 当前这一组的输入框里显示的是不是掩码（而不是可编辑的真值/空值） */
    private fun isMasked(group: SettingsRepository.Group): Boolean =
        keyVisible[group] != true && (realKeys[group] ?: "").isNotEmpty()

    private fun setEditable(field: EditText, editable: Boolean) {
        field.isFocusable = editable
        field.isFocusableInTouchMode = editable
        field.isCursorVisible = editable
        field.isLongClickable = editable
    }

    private fun setupKeyToggle(
        group: SettingsRepository.Group,
        keyField: EditText,
        keyToggle: android.widget.Button
    ) {
        keyToggle.setOnClickListener {
            val nowVisible = keyVisible[group] != true
            keyVisible[group] = nowVisible
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
        // 显示掩码时保持原值，其余情况（明文态 / 原本为空刚输入）一律以输入框为准。
        // 只看 keyVisible 会把"空 Key 首次手输"的内容丢掉。
        val key = if (isMasked(group)) existing.apiKey else keyField.text.toString().trim()
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
        com.shiyu.app.core.lang.LanguageRegistry
            .module(AppGraph.settingsRepository.activeLang()).id.sttLanguage
}

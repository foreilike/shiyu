package com.shiyu.app.ui.settings

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
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
        binding.btnCopyLang.setOnClickListener { confirmCopyToOtherLang() }
        binding.langChipJa.setOnClickListener { switchConfigLang(LangId.JA) }
        binding.langChipEn.setOnClickListener { switchConfigLang(LangId.EN) }

        setupKeyToggle(SettingsRepository.Group.CHAT, binding.chatApiKey, binding.chatKeyToggle)
        setupKeyToggle(SettingsRepository.Group.ASR, binding.asrApiKey, binding.asrKeyToggle)
        setupKeyToggle(SettingsRepository.Group.TTS, binding.ttsApiKey, binding.ttsKeyToggle)
        setupKeyToggle(SettingsRepository.Group.REALTIME, binding.realtimeApiKey, binding.realtimeKeyToggle)

        binding.chatClear.setOnClickListener { confirmClearGroup(SettingsRepository.Group.CHAT) }
        binding.asrClear.setOnClickListener { confirmClearGroup(SettingsRepository.Group.ASR) }
        binding.ttsClear.setOnClickListener { confirmClearGroup(SettingsRepository.Group.TTS) }
        binding.realtimeClear.setOnClickListener { confirmClearGroup(SettingsRepository.Group.REALTIME) }

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
        // 按钮文案跟着当前语言走。放在这里而不是 onResume —— 切换语言芯片也要刷新，
        // 否则会出现「当前是英语，按钮却写着复制到英语」这种自相矛盾（实际发生过）。
        val other = if (active == LangId.JA) LangId.EN else LangId.JA
        binding.btnCopyLang.text = getString(R.string.settings_copy_to_other, other.displayName)
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
    private fun confirmCopyToOtherLang() {
        val from = AppGraph.settingsRepository.activeLang()
        val to = if (from == LangId.JA) LangId.EN else LangId.JA
        val fromConfig = AppGraph.settingsRepository.configFor(from)
        val hasAny = listOf(fromConfig.chat, fromConfig.asr, fromConfig.tts, fromConfig.realtime)
            .any { it.baseUrl.isNotBlank() || it.apiKey.isNotBlank() }
        if (!hasAny) {
            Toast.makeText(this, R.string.settings_copy_empty, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_copy_to_other, to.displayName))
            .setMessage(
                getString(R.string.settings_copy_confirm, from.displayName, to.displayName)
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ -> copyToOtherLang(from, to) }
            .show()
    }

    private fun copyToOtherLang(from: LangId, to: LangId) {
        val src = AppGraph.settingsRepository.configFor(from)
        var n = 0
        for (group in SettingsRepository.Group.entries) {
            val config = when (group) {
                SettingsRepository.Group.CHAT -> src.chat
                SettingsRepository.Group.ASR -> src.asr
                SettingsRepository.Group.TTS -> src.tts
                SettingsRepository.Group.REALTIME -> src.realtime
            }
            AppGraph.settingsRepository.update(to, group, config)
            n++
        }
        AppGraph.settingsRepository.updateRealtimeOptions(to, src.realtimeOptions)
        Toast.makeText(
            this,
            getString(R.string.settings_copy_done, n, to.displayName),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 清空单组配置。
     *
     * 「清空全部数据」在「我的」页，粒度太粗 —— 想换掉对话服务的 Key 却不想动实时语音的配置，
     * 之前只能一个个字段手动删。这里给每组一个独立的清空，互不影响。
     */
    private fun confirmClearGroup(group: SettingsRepository.Group) {
        val label = groupLabel(group)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_clear_group_title, label))
            .setMessage(R.string.settings_clear_group_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val lang = AppGraph.settingsRepository.activeLang()
                AppGraph.settingsRepository.update(lang, group, ProviderConfig())
                loadForm()
                Toast.makeText(
                    this,
                    getString(R.string.settings_clear_group_done, label),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
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
        // **不要预填任何"官方默认地址"**。两个理由：
        //  1) 预填会让"清空全部数据"看起来没清干净（用户实际踩过）；
        //  2) App 可能被分享给别人用，自带一个指向厂商的默认值等于替人做了选择。
        // 空着就好，配没配由标题旁的状态徽章说清楚，不靠默认值暗示。
        baseUrlField.setText(config.baseUrl)
        modelField.setText(config.model)
        voiceField?.setText(config.voice)
        enabled.isChecked = config.enabled
        renderGroupStatus(group, config.enabled, config.baseUrl)
        enabled.setOnCheckedChangeListener { _, _ -> renderGroupStatus(group, enabled.isChecked, baseUrlField.text.toString()) }
        renderKey(group, keyField, keyToggle)
    }

    /** 标题旁的状态：一眼看出这组配没配、开没开 */
    private fun renderGroupStatus(
        group: SettingsRepository.Group,
        enabled: Boolean,
        baseUrl: String
    ) {
        val view = statusViewFor(group) ?: return
        val configured = baseUrl.isNotBlank()
        view.text = getString(
            when {
                !configured -> R.string.settings_status_unset
                enabled -> R.string.settings_status_ready
                else -> R.string.settings_status_disabled
            }
        )
        view.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                this,
                if (configured && enabled) R.color.ok_green else R.color.mute
            )
        )
    }

    private fun statusViewFor(group: SettingsRepository.Group): android.widget.TextView? = when (group) {
        SettingsRepository.Group.CHAT -> binding.chatStatus
        SettingsRepository.Group.ASR -> binding.asrStatus
        SettingsRepository.Group.TTS -> binding.ttsStatus
        SettingsRepository.Group.REALTIME -> binding.realtimeStatus
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

    private fun groupLabel(group: SettingsRepository.Group): String = getString(
        when (group) {
            SettingsRepository.Group.CHAT -> R.string.provider_chat
            SettingsRepository.Group.ASR -> R.string.provider_asr
            SettingsRepository.Group.TTS -> R.string.provider_tts
            SettingsRepository.Group.REALTIME -> R.string.provider_realtime
        }
    )

    private fun keyFieldFor(group: SettingsRepository.Group): EditText = when (group) {
        SettingsRepository.Group.CHAT -> binding.chatApiKey
        SettingsRepository.Group.ASR -> binding.asrApiKey
        SettingsRepository.Group.TTS -> binding.ttsApiKey
        SettingsRepository.Group.REALTIME -> binding.realtimeApiKey
    }

    /**
     * 当前这一组的 Key 该取哪个值。
     *
     * **保存和测试连接必须走同一个函数**：显示掩码时用已存的值（掩码不是真 Key，
     * 存回去就毁了）；其余情况一律以输入框为准。
     *
     * 之前测试连接单独写成 `realKeys[group] ?: ""`，于是「刚填进去、还没保存」的 Key
     * 它根本看不见 —— 拿空字符串去请求，服务端回 401，界面显示「API Key 无效」，
     * 用户对着自己刚复制的 Key 百思不得其解。文档 §12.2 第 8 条要求"测试连接用当前
     * 表单值即时构造"，这条意图在 Key 上从来没落实过。
     */
    private fun currentKey(group: SettingsRepository.Group): String =
        if (isMasked(group)) (realKeys[group] ?: "")
        else keyFieldFor(group).text.toString().trim()

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
            // 从「明文」切回「掩码」之前，先把输入框里的内容收进 realKeys。
            // 掩码是按 realKeys 重新生成的，不收的话用户在明文态改的 Key 会被旧值覆盖 ——
            // 而且界面上看不出任何异常，只是保存后才发现 Key 还是老的。
            if (!nowVisible && !isMasked(group)) {
                realKeys[group] = keyField.text.toString().trim()
            }
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
                // 说清楚是哪一组：保存是整体操作，任何一组地址不合法都会让**所有**改动都不落盘，
                // 只提示一句"地址需以 https:// 开头"，用户根本不知道去看哪个输入框。
                Toast.makeText(
                    this,
                    getString(R.string.settings_https_only_group, groupLabel(group)),
                    Toast.LENGTH_LONG
                ).show()
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
        // 与「测试连接」走同一套判定，避免两条路径对"当前 Key 是什么"理解不一致
        val key = currentKey(group)
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
        val key = currentKey(group)
        val model = when (group) {
            SettingsRepository.Group.CHAT -> binding.chatModel.text.toString().trim()
            SettingsRepository.Group.ASR -> binding.asrModel.text.toString().trim()
            SettingsRepository.Group.TTS -> binding.ttsModel.text.toString().trim()
            SettingsRepository.Group.REALTIME -> binding.realtimeModel.text.toString().trim()
        }
        // 音色必须按组取：原先写死 ttsVoice，于是「测试连接」测实时语音时
        // 拿的是语音合成那组填的音色 —— 表单里明明写着别的，请求却用这一组的。
        val voice = when (group) {
            SettingsRepository.Group.TTS -> binding.ttsVoice.text.toString().trim()
            SettingsRepository.Group.REALTIME -> binding.realtimeVoice.text.toString().trim()
            else -> ""
        }

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

    /**
     * REALTIME 测试：建连并等 `session.created`；8 秒超时。
     *
     * 三个必须做对的点（原先都漏了）：
     * 1. **成功判定要等 `session.created`**，不是 WebSocket 握手成功 ——
     *    后者只说明 TCP + Bearer 通过了，模型名非法照样会被服务端随后拒绝，
     *    那时候已经报了"连接成功"。
     * 2. **协程取消时要关掉 provider**：用户在 8 秒里离开设置页，lifecycleScope 会取消，
     *    不注册 invokeOnCancellation 的话 WebSocket 和它自建的 OkHttpClient 永不释放。
     * 3. **完成标志要用 CAS**：回调来自 WS 线程，超时看门狗来自另一个线程，
     *    `var done` 的检查-再赋值是 TOCTOU，两边可能都 resume —— 第二次抛
     *    `Already resumed` 直接崩。
     */
    private suspend fun probeRealtime(config: ProviderConfig): com.shiyu.app.ai.AiError? =
        suspendCancellableCoroutine { cont ->
            val provider = StepfunRealtimeProvider(config, RealtimeOptions())
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            // 超时看门狗用主线程 postDelayed，不再裸开 Thread.sleep
            val watchdog = Runnable { finishProbe(cont, done, provider, com.shiyu.app.ai.AiError.Timeout(8000)) }
            cont.invokeOnCancellation {
                mainHandler.removeCallbacks(watchdog)
                provider.close()
            }
            provider.setListener(object : RealtimeVoiceProvider.Listener {
                // 握手成功不算数，什么都不做，等 onReady
                override fun onConnected() {}
                // 握手成功不算成功，等 session.created（provider 内部会在那时回调这个）
                override fun onReady() {
                    mainHandler.removeCallbacks(watchdog)
                    finishProbe(cont, done, provider, null)
                }
                override fun onUserSpeechStarted() {}
                override fun onUserSpeechStopped() {}
                override fun onAudioDelta(pcm: ByteArray) {}
                override fun onTranscriptDelta(text: String) {}
                override fun onUserTranscript(text: String) {}
                override fun onResponseDone() {}
                override fun onError(error: com.shiyu.app.ai.AiError) {
                    mainHandler.removeCallbacks(watchdog)
                    finishProbe(cont, done, provider, error)
                }
                override fun onClosed() {}
            })
            provider.connect("连接测试")
            mainHandler.postDelayed(watchdog, 8000)
        }

    /** CAS 保证只结算一次；provider 一定关掉 */
    private fun finishProbe(
        cont: kotlinx.coroutines.CancellableContinuation<com.shiyu.app.ai.AiError?>,
        done: java.util.concurrent.atomic.AtomicBoolean,
        provider: StepfunRealtimeProvider,
        error: com.shiyu.app.ai.AiError?
    ) {
        if (!done.compareAndSet(false, true)) return
        try {
            provider.close()
        } catch (e: Exception) {
            // 忽略
        }
        if (cont.isActive) cont.resume(error)
    }

    private fun langStt(): String =
        com.shiyu.app.core.lang.LanguageRegistry
            .module(AppGraph.settingsRepository.activeLang()).id.sttLanguage
}

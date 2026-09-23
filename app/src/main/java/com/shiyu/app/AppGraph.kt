package com.shiyu.app

import android.content.Context
import com.shiyu.app.ai.AiProviderRegistry
import com.shiyu.app.core.lang.LanguageModule
import com.shiyu.app.core.lang.WordBook
import com.shiyu.app.data.asset.AssetRepository
import com.shiyu.app.data.asset.PresetConfig
import com.shiyu.app.data.secret.KeystoreSecretStore
import com.shiyu.app.data.store.DailyStatsStore
import com.shiyu.app.data.store.SessionStore
import com.shiyu.app.data.store.SettingsRepository
import com.shiyu.app.data.store.StudyStore
import java.io.File

/**
 * 简易服务定位器：UI 层通过它拿各仓库/注册表，不直接 new。
 */
@Suppress("StaticFieldLeak") // 只存 applicationContext，不会泄漏 Activity
object AppGraph {

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var studyStore: StudyStore
        private set
    lateinit var sessionStore: SessionStore
        private set
    lateinit var dailyStatsStore: DailyStatsStore
        private set
    lateinit var assetRepository: AssetRepository
        private set
    lateinit var providerRegistry: AiProviderRegistry
        private set

    fun init(context: Context) {
        if (::settingsRepository.isInitialized) return
        val app = context.applicationContext
        settingsRepository = SettingsRepository(KeystoreSecretStore(app))
        studyStore = StudyStore(File(app.filesDir, "study_progress.json"))
        sessionStore = SessionStore(File(app.filesDir, "sessions"))
        dailyStatsStore = DailyStatsStore(File(app.filesDir, "daily_stats.json"))
        assetRepository = AssetRepository(app)
        providerRegistry = AiProviderRegistry(settingsRepository)
        // 出厂预置配置：只在本地一份配置都没有时写入（不会覆盖用户已改的设置）
        PresetConfig.seedIfEmpty(app, settingsRepository)
        // 预置迁移：版本升级时把修正过的组（如新增的 tts）同步到已装机器（见 WordTtsPlayer）
        PresetConfig.applyPresetMigrations(app, settingsRepository)
    }

    /** 某语言当前选中的词书（未设或非法 → 该语言 books() 的第一本）。 */
    fun activeBook(module: LanguageModule): WordBook {
        val id = settingsRepository.activeBook(module.id)
        val books = module.books()
        return books.firstOrNull { it.id == id } ?: books.first()
    }

    /** 当前词书的牌堆 JSON 原文。 */
    fun activeDeckJson(module: LanguageModule): String =
        assetRepository.deckJson(activeBook(module).asset)
}

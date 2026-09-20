package com.shiyu.app

import android.content.Context
import com.shiyu.app.ai.AiProviderRegistry
import com.shiyu.app.data.asset.AssetRepository
import com.shiyu.app.data.secret.KeystoreSecretStore
import com.shiyu.app.data.store.DailyStatsStore
import com.shiyu.app.data.store.SessionStore
import com.shiyu.app.data.store.SettingsRepository
import com.shiyu.app.data.store.StudyStore
import java.io.File

/**
 * 简易服务定位器：UI 层通过它拿各仓库/注册表，不直接 new。
 */
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
    }
}

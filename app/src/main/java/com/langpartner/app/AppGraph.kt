package com.langpartner.app

import android.content.Context
import com.langpartner.app.ai.AiProviderRegistry
import com.langpartner.app.data.asset.AssetRepository
import com.langpartner.app.data.secret.KeystoreSecretStore
import com.langpartner.app.data.store.SessionStore
import com.langpartner.app.data.store.SettingsRepository
import com.langpartner.app.data.store.StudyStore
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
        assetRepository = AssetRepository(app)
        providerRegistry = AiProviderRegistry(settingsRepository)
    }
}

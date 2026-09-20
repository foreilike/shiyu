package com.langpartner.app.ui.study

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.langpartner.app.AppGraph
import com.langpartner.app.R
import com.langpartner.app.core.answer.AnswerJudge
import com.langpartner.app.core.lang.LanguageModule
import com.langpartner.app.core.lang.LanguageRegistry
import com.langpartner.app.core.lang.LangId
import com.langpartner.app.core.srs.CardState
import com.langpartner.app.core.srs.Sm2Scheduler
import com.langpartner.app.data.model.Card
import com.langpartner.app.ui.Speaker
import com.langpartner.app.ui.chart.ChartActivity
import com.langpartner.app.ui.chat.ChatListActivity
import com.langpartner.app.ui.profile.ProfileActivity
import com.langpartner.app.databinding.ActivityStudyBinding
import java.time.LocalDate

/**
 * 学习页（M1，纯离线）：今日排卡 → 卡片正反面 → 判题 → SM-2 评分 → 立刻落盘。
 */
class StudyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudyBinding
    private lateinit var module: LanguageModule
    private var deck: List<Card> = emptyList()
    private var queue: List<String> = emptyList()
    private var queueIndex = 0
    private var currentCard: Card? = null
    private var flipped = false
    private var speaker: Speaker? = null

    private val today: Long
        get() = LocalDate.now().toEpochDay()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        binding = ActivityStudyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speaker = Speaker(this)

        binding.btnChart.setOnClickListener {
            startActivity(Intent(this, ChartActivity::class.java))
        }
        binding.btnConfirm.setOnClickListener { onConfirm() }
        binding.btnQ2.setOnClickListener { onRate(2) }
        binding.btnQ3.setOnClickListener { onRate(3) }
        binding.btnQ4.setOnClickListener { onRate(4) }
        binding.btnQ5.setOnClickListener { onRate(5) }

        binding.langBar.langGroup.setOnCheckedChangeListener { _, _ ->
            val lang = selectedLang()
            if (lang != AppGraph.settingsRepository.activeLang()) {
                AppGraph.settingsRepository.setActiveLang(lang)
                reload()
            }
        }

        binding.bottomNavBar.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chart -> {
                    startActivity(
                        Intent(this, ChartActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    true
                }
                R.id.nav_chat -> {
                    startActivity(
                        Intent(this, ChatListActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    true
                }
                R.id.nav_profile -> {
                    startActivity(
                        Intent(this, ProfileActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    true
                }
                else -> true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncLangBar()
        reload()
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
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
                reload()
            }
        }
    }

    private fun reload() {
        val lang = AppGraph.settingsRepository.activeLang()
        module = LanguageRegistry.module(lang)
        speaker?.setLanguage(lang)
        deck = module.buildDeck(AppGraph.assetRepository.deckJson(module))
        queue = AppGraph.studyStore.selectToday(module.id, deck.map { it.id }, today)
        queueIndex = 0
        renderProgress()
        showNext()
    }

    private fun renderProgress() {
        val progress = AppGraph.studyStore.progressFor(module.id)
        val due = progress.values.count { it.dueEpochDay <= today }
        val mastered = progress.values.count { it.reps >= 5 }
        binding.progressText.text = getString(R.string.today_due) + " $due / " +
            getString(R.string.mastered) + " $mastered"
    }

    private fun showNext() {
        flipped = false
        if (queueIndex >= queue.size) {
            currentCard = null
            binding.cardFront.text = getString(R.string.today_finished)
            binding.cardFrontSub.text = ""
            binding.cardMeaning.text = ""
            binding.cardBack.visibility = android.view.View.GONE
            binding.cardBackSub.visibility = android.view.View.GONE
            binding.judgeResult.visibility = android.view.View.GONE
            binding.ratingRow.visibility = android.view.View.GONE
            binding.btnConfirm.isEnabled = false
            return
        }
        val card = deck.firstOrNull { it.id == queue[queueIndex] }
        if (card == null) {
            queueIndex++
            showNext()
            return
        }
        currentCard = card
        binding.cardFront.text = card.front
        binding.cardFrontSub.text = card.frontSub
        binding.cardMeaning.text = card.exampleBlank.ifEmpty { card.meaningZh }
        binding.cardBack.visibility = android.view.View.GONE
        binding.cardBackSub.visibility = android.view.View.GONE
        binding.judgeResult.visibility = android.view.View.GONE
        binding.ratingRow.visibility = android.view.View.GONE
        binding.answerInput.setText("")
        binding.answerInput.hint = module.answerHint
        binding.btnConfirm.isEnabled = true
    }

    private fun onConfirm() {
        val card = currentCard ?: return
        if (flipped) return
        val input = binding.answerInput.text.toString()
        val result = AnswerJudge.judge(module, card.back, input)
        flipped = true
        binding.cardBack.text = card.back
        binding.cardBackSub.text = card.backSub
        binding.cardBack.visibility = android.view.View.VISIBLE
        binding.cardBackSub.visibility = android.view.View.VISIBLE
        binding.judgeResult.visibility = android.view.View.VISIBLE
        binding.judgeResult.text = if (result.correct) {
            getString(R.string.correct_answer)
        } else {
            getString(R.string.wrong_answer) + "：" + result.reason
        }
        binding.judgeResult.setTextColor(
            ContextCompat.getColor(
                this,
                if (result.correct) R.color.right_green else R.color.wrong_red
            )
        )
        binding.ratingRow.visibility = android.view.View.VISIBLE
        binding.btnConfirm.isEnabled = false
    }

    private fun onRate(quality: Int) {
        val card = currentCard ?: return
        val lang = module.id
        val previousProgress = AppGraph.studyStore.progressFor(lang)[card.id]
        val previousState = if (previousProgress != null) {
            CardState(
                easeFactor = previousProgress.ef,
                reps = previousProgress.reps,
                intervalDays = previousProgress.intervalDays
            )
        } else {
            CardState()
        }
        val outcome = Sm2Scheduler.review(previousState, quality, today)
        AppGraph.studyStore.recordReview(lang, card.id, outcome, today)
        queueIndex++
        renderProgress()
        showNext()
    }
}

package com.shiyu.app.ui.review

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.answer.AnswerJudge
import com.shiyu.app.core.lang.LanguageModule
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.core.srs.CardState
import com.shiyu.app.core.srs.DailyQuota
import com.shiyu.app.core.srs.Sm2Scheduler
import com.shiyu.app.data.model.Card
import com.shiyu.app.databinding.ActivityReviewBinding
import com.shiyu.app.ui.BaseActivity
import com.shiyu.app.ui.chart.ChartActivity
import com.shiyu.app.ui.nav.BottomNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 复习（Tab 4）。
 *
 * 逻辑与改造前的 StudyActivity 完全一致（今日排卡 → 作答判题 → SM-2 评分 → 立刻落盘），
 * 只换了两件事：
 * 1. 视觉改成设计稿的翻转卡（rotationY 两段式，避免 180° 时内容镜像）；
 * 2. 学习时长与复习张数写入 DailyStatsStore，首页的目标环和我的页的周柱状图靠它吃饭。
 */
class ReviewActivity : BaseActivity() {

    private lateinit var binding: ActivityReviewBinding
    private lateinit var module: LanguageModule
    private var deck: List<Card> = emptyList()
    private var queue: List<String> = emptyList()
    private var queueIndex = 0
    private var currentCard: Card? = null
    private var flipped = false

    /** 本次停留在复习页的计时起点（elapsedRealtime，不受系统改时间影响） */
    private var enteredAt = 0L

    private val today: Long
        get() = LocalDate.now().toEpochDay()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitTopInset(binding.header)
        fitBottomInset(binding.bottomNavBar.navRow)
        fitBottomInsetWithIme(binding.contentColumn)
        BottomNav.bind(this, binding.bottomNavBar, BottomNav.Tab.REVIEW)

        binding.flipCard.cameraDistance = FLIP_CAMERA_DISTANCE * resources.displayMetrics.density

        binding.btnChart.setOnClickListener {
            startActivity(Intent(this, ChartActivity::class.java))
        }
        binding.btnBook.setOnClickListener { showBookPicker() }
        binding.btnConfirm.setOnClickListener { onConfirm() }
        binding.answerInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onConfirm()
                true
            } else {
                false
            }
        }
        binding.btnQ2.setOnClickListener { onRate(2) }
        binding.btnQ3.setOnClickListener { onRate(3) }
        binding.btnQ4.setOnClickListener { onRate(4) }
        binding.btnQ5.setOnClickListener { onRate(5) }
    }

    override fun onResume() {
        super.onResume()
        enteredAt = SystemClock.elapsedRealtime()
        reload()
    }

    override fun onPause() {
        flushStudyTime()
        super.onPause()
    }

    /** 把这次前台停留的秒数计入今天；不足 5 秒视为误触，不计 */
    private fun flushStudyTime() {
        if (enteredAt == 0L) return
        val seconds = (SystemClock.elapsedRealtime() - enteredAt) / 1000L
        enteredAt = 0L
        if (seconds < MIN_TRACKED_SECONDS) return
        AppGraph.dailyStatsStore.record(today, studySeconds = seconds.coerceAtMost(MAX_SESSION_SECONDS))
    }

    /** 选词书：单选对话框，切换后立刻重排今日队列。只有一本时也允许查看。 */
    private fun showBookPicker() {
        val books = module.books()
        if (books.isEmpty()) return
        val titles = books.map { it.title }.toTypedArray()
        val curId = AppGraph.activeBook(module).id
        val checked = books.indexOfFirst { it.id == curId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.review_pick_book)
            .setSingleChoiceItems(titles, checked) { dialog, which ->
                val picked = books[which]
                AppGraph.settingsRepository.setActiveBook(module.id, picked.id)
                dialog.dismiss()
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun reload() {
        val lang = AppGraph.settingsRepository.activeLang()
        module = LanguageRegistry.module(lang)
        binding.bookLabel.text = AppGraph.activeBook(module).title
        deck = module.buildDeck(AppGraph.activeDeckJson(module))
        queue = AppGraph.studyStore.selectToday(module.id, deck.map { it.id }, today)
        queueIndex = 0
        renderChips()
        showNext()
    }

    /**
     * 两枚胶囊。「今日待复习」走 [DailyQuota]，与首页
     * [com.shiyu.app.ui.home.HomeActivity] 完全同源，两屏显示的数字永远一致。
     *
     * 不能用「进度表里 due<=today 的条数」—— 那个对从没学过的新卡恒为 0，
     * 会出现首页说 20 张、复习页说 0 张的矛盾。
     */
    private fun renderChips() {
        val progress = AppGraph.studyStore.progressFor(module.id)
        val remaining = DailyQuota.remaining(
            availableCards = queue.size - queueIndex,
            reviewedToday = AppGraph.dailyStatsStore.dayOf(today).reviewedIn(module.id.tag)
        )
        val mastered = progress.values.count { it.reps >= MASTERED_REPS }
        binding.chipDue.text = "${getString(R.string.today_due)} $remaining"
        binding.chipMastered.text = "${getString(R.string.mastered)} $mastered"
    }

    private fun showNext() {
        flipped = false
        binding.flipCard.rotationY = 0f
        if (queueIndex >= queue.size) {
            currentCard = null
            binding.cardSource.visibility = View.GONE
            binding.cardFront.text = getString(R.string.today_finished)
            binding.cardFront.textSize = 24f
            binding.cardFrontSub.text = ""
            binding.cardMeaning.text = ""
            binding.cardBack.visibility = View.GONE
            binding.cardBackSub.visibility = View.GONE
            binding.judgeResult.visibility = View.GONE
            binding.ratingRow.visibility = View.GONE
            binding.tapHint.visibility = View.GONE
            binding.inputRow.visibility = View.GONE
            binding.chipIndex.text = ""
            return
        }
        val card = deck.firstOrNull { it.id == queue[queueIndex] }
        if (card == null) {
            queueIndex++
            showNext()
            return
        }
        currentCard = card
        binding.inputRow.visibility = View.VISIBLE
        binding.cardSource.visibility = View.VISIBLE
        binding.cardSource.text = module.id.displayName
        binding.cardFront.textSize = 38f
        binding.cardFront.text = card.front
        binding.cardFrontSub.text = card.frontSub
        binding.cardMeaning.text = card.exampleBlank.ifEmpty { card.meaningZh }
        binding.cardBack.visibility = View.GONE
        binding.cardBackSub.visibility = View.GONE
        binding.cardExample.visibility = View.GONE
        binding.cardExampleZh.visibility = View.GONE
        binding.judgeResult.visibility = View.GONE
        binding.ratingRow.visibility = View.GONE
        binding.tapHint.visibility = View.VISIBLE
        binding.answerInput.setText("")
        binding.answerInput.hint = module.answerHint
        binding.btnConfirm.isEnabled = true
        binding.btnConfirm.alpha = 1f
        binding.chipIndex.text =
            getString(R.string.review_progress, queueIndex + 1, queue.size)
    }

    private fun onConfirm() {
        val card = currentCard ?: return
        if (flipped) return
        val input = binding.answerInput.text.toString()
        val result = AnswerJudge.judge(module, card.back, input)
        flipped = true
        binding.btnConfirm.isEnabled = false
        binding.btnConfirm.alpha = 0.5f

        flip {
            binding.cardBack.text = card.back
            binding.cardBackSub.text = card.backSub
            binding.cardBack.visibility = View.VISIBLE
            binding.cardBackSub.visibility = View.VISIBLE
            // 翻面后给出完整例句 + 中文译文（参照跬步：发音在 backSub，例句在这里）。
            // 没有例句的卡（旧数据）就不显示，不留空行。
            if (card.example.isNotBlank()) {
                binding.cardExample.text = card.example
                binding.cardExample.visibility = View.VISIBLE
                if (card.exampleZh.isNotBlank()) {
                    binding.cardExampleZh.text = card.exampleZh
                    binding.cardExampleZh.visibility = View.VISIBLE
                }
            }
            binding.tapHint.visibility = View.GONE
            binding.judgeResult.visibility = View.VISIBLE
            binding.judgeResult.text = if (result.correct) {
                getString(R.string.correct_answer)
            } else {
                getString(R.string.wrong_answer) + "：" + result.reason
            }
            binding.judgeResult.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (result.correct) R.color.ok_green else R.color.bad_red
                )
            )
            binding.ratingRow.visibility = View.VISIBLE
        }
    }

    /**
     * 两段式翻转：先转到 90°（此时卡片侧对镜头、内容不可见）换内容，
     * 再从 -90° 转回 0°。直接一路转到 180° 会让文字整体镜像。
     */
    private fun flip(swapContent: () -> Unit) {
        binding.flipCard.animate()
            .rotationY(90f)
            .setDuration(FLIP_HALF_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    swapContent()
                    binding.flipCard.rotationY = -90f
                    binding.flipCard.animate()
                        .rotationY(0f)
                        .setDuration(FLIP_HALF_MS)
                        .setListener(null)
                        .start()
                }
            })
            .start()
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
        // 落盘挪到 IO：这是评分按钮的回调，主线程同步写两个文件会掉帧；
        // 两个 store 现在都是 @Synchronized，后台写是安全的。
        lifecycleScope.launch(Dispatchers.IO) {
            AppGraph.studyStore.recordReview(lang, card.id, outcome, today)
            AppGraph.dailyStatsStore.record(today, reviewedCards = 1, langTag = lang.tag)
        }
        queueIndex++
        renderChips()
        showNext()
    }

    private companion object {
        const val FLIP_HALF_MS = 160L
        const val FLIP_CAMERA_DISTANCE = 8000f

        /** 与 HomeActivity / ProfileActivity 同一口径 */
        const val MASTERED_REPS = 5

        /** 少于 5 秒不计时长（切错页面、误触返回） */
        const val MIN_TRACKED_SECONDS = 5L

        /** 单次前台最多计 30 分钟，防止忘记锁屏导致时长虚高 */
        const val MAX_SESSION_SECONDS = 30L * 60L
    }
}

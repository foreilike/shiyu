package com.shiyu.app.ui.exam

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import com.shiyu.app.AppGraph
import com.shiyu.app.R
import com.shiyu.app.core.answer.AnswerJudge
import com.shiyu.app.core.lang.LanguageModule
import com.shiyu.app.core.lang.LanguageRegistry
import com.shiyu.app.data.model.Card
import com.shiyu.app.databinding.ActivityExamBinding
import com.shiyu.app.ui.BaseActivity

/**
 * 拼写考试（二级页，进阶模式）。
 *
 * 与「复习」页的自评模式**刻意分开**：那边是初学者路径（看词 → 点开看释义 → 自评），
 * 不打字；这里是给自己出的题 —— 看中文释义，把外语词拼出来，答完给分数。
 *
 * 抽题：从**当前词书**里随机 [QUESTION_COUNT] 个有释义的卡（洗牌后取前 N，不重复）。
 * 判题复用 [AnswerJudge]，语言差异交给各 LanguageModule 的 normalizeForCompare /
 * examAnswers（日语假名与汉字写法都算对）。
 * 成绩只记在本次会话里，不写 SM-2 进度 —— 考试是检验，不该反过来改复习计划。
 */
class ExamActivity : BaseActivity() {

    private lateinit var binding: ActivityExamBinding
    private lateinit var module: LanguageModule

    private var questions: List<Card> = emptyList()
    private var index = 0
    private var correctCount = 0

    /** 当前题是否已判过（判过后输入框锁住，按钮变「下一题」） */
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fitTopInset(binding.headerBar.subHeader)
        fitBottomInset(binding.examScroll)

        binding.headerBar.subTitle.text = getString(R.string.exam_title)
        binding.headerBar.btnBack.setOnClickListener { finish() }

        binding.examSubmit.setOnClickListener {
            if (answered) nextQuestion() else submitAnswer()
        }
        binding.examInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == IME_ACTION_DONE) {
                if (answered) nextQuestion() else submitAnswer()
                true
            } else {
                false
            }
        }
        binding.btnExamAgain.setOnClickListener { startExam() }
    }

    override fun onResume() {
        super.onResume()
        if (questions.isEmpty()) startExam()
    }

    /** 重新抽题开考 */
    private fun startExam() {
        val lang = AppGraph.settingsRepository.activeLang()
        module = LanguageRegistry.module(lang)
        val book = AppGraph.activeBook(module)
        val deck = module.buildDeck(AppGraph.activeDeckJson(module))

        binding.examLabel.text = getString(R.string.exam_sub, module.answerHint)
        binding.examBook.text = book.title
        binding.resultPanel.visibility = View.GONE
        binding.questionCard.visibility = View.VISIBLE
        binding.examInput.visibility = View.VISIBLE
        binding.examSubmit.visibility = View.VISIBLE

        // 只考「有中文释义」的卡：释义是题面，没有它就没法出题
        questions = deck.filter { it.meaningZh.isNotBlank() }
            .shuffled()
            .take(QUESTION_COUNT)

        if (questions.isEmpty()) {
            binding.questionCard.visibility = View.GONE
            binding.examInput.visibility = View.GONE
            binding.examSubmit.visibility = View.GONE
            binding.examProgress.text = ""
            binding.examResult.visibility = View.VISIBLE
            binding.examResult.setTextColor(ContextCompat.getColor(this, R.color.mute))
            binding.examResult.text = getString(R.string.exam_empty)
            return
        }
        index = 0
        correctCount = 0
        showQuestion()
    }

    private fun showQuestion() {
        val card = questions[index]
        answered = false
        binding.examProgress.text =
            getString(R.string.exam_progress, index + 1, questions.size)
        binding.examPrompt.text = card.meaningZh
        binding.examHintLine.text = card.frontSub
        binding.examResult.visibility = View.GONE
        binding.examInput.setText("")
        binding.examInput.isEnabled = true
        binding.examInput.hint = module.answerHint
        binding.examSubmit.text = getString(R.string.exam_submit)
        binding.examInput.requestFocus()
    }

    private fun submitAnswer() {
        val card = questions.getOrNull(index) ?: return
        if (answered) return
        val input = binding.examInput.text.toString().trim()
        if (input.isEmpty()) return

        // 任一可接受写法命中即算对（英语=单词；日语=假名或汉字）
        val ok = module.examAnswers(card).any { AnswerJudge.judge(module, it, input).correct }
        answered = true
        if (ok) correctCount++

        val accent = ContextCompat.getColor(this, if (ok) R.color.ok_green else R.color.bad_red)
        binding.examResult.visibility = View.VISIBLE
        binding.examResult.setTextColor(accent)
        binding.examResult.text = if (ok) {
            getString(R.string.exam_correct)
        } else {
            // 答错时把标准答案摊开：日语给假名读音，英语给单词
            val expected = module.examAnswers(card).firstOrNull().orEmpty()
            getString(R.string.exam_wrong, expected)
        }
        binding.examInput.isEnabled = false
        binding.examSubmit.text = getString(R.string.exam_next)
    }

    private fun nextQuestion() {
        index++
        if (index >= questions.size) {
            showResult()
        } else {
            showQuestion()
        }
    }

    private fun showResult() {
        binding.questionCard.visibility = View.GONE
        binding.examInput.visibility = View.GONE
        binding.examSubmit.visibility = View.GONE
        binding.examResult.visibility = View.GONE
        binding.examProgress.text = ""
        binding.resultPanel.visibility = View.VISIBLE
        binding.scoreText.text = getString(R.string.exam_score, correctCount, questions.size)
        binding.scoreComment.text = getString(
            when {
                correctCount * 10 >= questions.size * 8 -> R.string.exam_score_praise
                correctCount * 10 >= questions.size * 5 -> R.string.exam_score_ok
                else -> R.string.exam_score_low
            }
        )
    }

    private companion object {
        /** 每次考试的题量 */
        const val QUESTION_COUNT = 20

        /** IME_ACTION_DONE：输入法回车 = 提交/下一题 */
        const val IME_ACTION_DONE = EditorInfo.IME_ACTION_DONE
    }
}

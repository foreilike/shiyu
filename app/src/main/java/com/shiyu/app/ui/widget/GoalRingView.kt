package com.shiyu.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.shiyu.app.R

/**
 * 今日目标进度环（首页）。
 *
 * 用自绘而不是 ProgressBar + 旋转 drawable：后者在不同厂商 ROM 上圆角端点表现不一致，
 * 自绘 8 行代码就能保证到处一样。进度从 12 点方向顺时针增长。
 */
class GoalRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val strokeWidth = 7f * resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@GoalRingView.strokeWidth
        color = ContextCompat.getColor(context, R.color.track)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@GoalRingView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.clay)
    }

    private val oval = RectF()

    /** 0f..1f，超出自动钳制 */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = strokeWidth / 2f
        oval.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(oval, -90f, 360f * progress, false, progressPaint)
        }
    }
}

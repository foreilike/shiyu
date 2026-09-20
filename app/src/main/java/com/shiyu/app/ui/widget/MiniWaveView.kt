package com.shiyu.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * 首页语音卡上的小波形（纯装饰）。
 *
 * 12 根暖金细柱按正弦相位起伏，帧率压到 ~20fps —— 这是首页唯一的动效，
 * 再快既费电又和"安静的纸"的调子冲突。页面不可见时由 onDetachedFromWindow
 * 停掉回调，不会在后台空转。
 */
class MiniWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val barCount = 12
    private val barWidth = 3f * density
    private val gap = 4f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC89B72.toInt()
        style = Paint.Style.FILL
    }

    private val rect = RectF()
    private var phase = 0f
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            phase += 0.22f
            invalidate()
            postDelayed(this, 50L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val totalWidth = barCount * barWidth + (barCount - 1) * gap
        var x = (width - totalWidth) / 2f
        val centerY = height / 2f
        val maxHalf = height / 2f - 1f
        for (i in 0 until barCount) {
            val amplitude = (sin(phase + i * 0.55f) * 0.5f + 0.5f) * 0.78f + 0.22f
            val half = maxHalf * amplitude
            rect.set(x, centerY - half, x + barWidth, centerY + half)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
            x += barWidth + gap
        }
    }
}

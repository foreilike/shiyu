package com.shiyu.app.data.model

/**
 * 单张卡片的复习进度（SM-2 状态）。字段缺省值即 §5.11 规定的默认值。
 */
data class CardProgress(
    val ef: Double = 2.5,
    val reps: Int = 0,
    val intervalDays: Long = 0,
    val dueEpochDay: Long = 0,
    val lastEpochDay: Long = 0
)

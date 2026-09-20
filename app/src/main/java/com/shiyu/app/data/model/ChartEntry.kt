package com.shiyu.app.data.model

/**
 * 图表页单元格。
 * alt：日语平/片假名切换用的另一种写法（英语为空串）；
 * note：点击格子时展示的补充说明（英语为例词中文；日语为空串）；
 * section：英语图表按 vowel/consonant 分节的节名（日语为空串）。
 */
data class ChartEntry(
    val cell: String,
    val sub: String,
    val speak: String,
    val alt: String = "",
    val note: String = "",
    val section: String = ""
)

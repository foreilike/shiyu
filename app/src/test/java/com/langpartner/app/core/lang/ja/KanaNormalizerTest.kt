package com.langpartner.app.core.lang.ja

import org.junit.Assert.assertEquals
import org.junit.Test

/** KanaNormalizer（§5.6.3） */
class KanaNormalizerTest {

    @Test
    fun toHalfWidthAlnum_basic() {
        assertEquals("ABC123", KanaNormalizer.toHalfWidthAlnum("ＡＢＣ１２３"))
        assertEquals("a1", KanaNormalizer.toHalfWidthAlnum("ａ１"))
        assertEquals("ABC", KanaNormalizer.toHalfWidthAlnum("ＡＢＣ"))
    }

    @Test
    fun toHalfWidthAlnum_leaves_kana() {
        assertEquals("ｶ", KanaNormalizer.toHalfWidthAlnum("ｶ"))
        assertEquals("あ", KanaNormalizer.toHalfWidthAlnum("あ"))
    }

    @Test
    fun halfWidthToFullWidth_merges_dakuten() {
        // ｶ + ﾞ 合并为 ガ（NFKC 自动完成）
        assertEquals("ガ", KanaNormalizer.halfWidthToFullWidth("ｶﾞ"))
        assertEquals("パ", KanaNormalizer.halfWidthToFullWidth("ﾊﾟ"))
        assertEquals("ボク", KanaNormalizer.halfWidthToFullWidth("ﾎﾞｸ"))
        assertEquals("ポケット", KanaNormalizer.halfWidthToFullWidth("ﾎﾟｹｯﾄ"))
    }

    @Test
    fun halfWidthToFullWidth_special_chars() {
        assertEquals("ヲ", KanaNormalizer.halfWidthToFullWidth("ｦ"))
        assertEquals("ー", KanaNormalizer.halfWidthToFullWidth("ｰ"))
    }

    @Test
    fun katakanaToHiragana_ranges() {
        assertEquals("が", KanaNormalizer.katakanaToHiragana("ガ"))
        assertEquals("ぱ", KanaNormalizer.katakanaToHiragana("パ"))
        // ヴ U+30F4 → ゔ
        assertEquals("ゔ", KanaNormalizer.katakanaToHiragana("ヴ"))
        // ヵ U+30F5 → ゕ；ヶ U+30F6 → ゖ
        assertEquals("ゕ", KanaNormalizer.katakanaToHiragana("ヵ"))
        assertEquals("ゖ", KanaNormalizer.katakanaToHiragana("ヶ"))
        // U+30F7..U+30FA、中点、长音保持不变
        assertEquals("ヷヸヹヺー・", KanaNormalizer.katakanaToHiragana("ヷヸヹヺー・"))
    }

    @Test
    fun unifyLongVowelMark_variants() {
        val variants = "-\u2010\u2011\u2212\uFF0D"
        assertEquals("ーーーーー", KanaNormalizer.unifyLongVowelMark(variants))
        // U+30FC 自身不变
        assertEquals("ー", KanaNormalizer.unifyLongVowelMark("ー"))
        // 其它横线（〜）不在表中，保持不变
        assertEquals("〜", KanaNormalizer.unifyLongVowelMark("〜"))
    }

    @Test
    fun normalize_full_pipeline() {
        // 半角片假名 + 半角浊点 → 合并 → 平假名
        assertEquals("ぼく", KanaNormalizer.normalize("ﾎﾞｸ"))
        assertEquals("ぱん", KanaNormalizer.normalize("ﾊﾟﾝ"))
        assertEquals("ばーげん", KanaNormalizer.normalize("ﾊﾞｰｹﾞﾝ"))
        // 全角字母数字 → 半角（KanaNormalizer 不转小写）
        assertEquals("ABC123", KanaNormalizer.normalize("ＡＢＣ１２３"))
        // 片假名 → 平假名（含 ヴ→ゔ、ァ→ぁ：U+30A1-0x60=U+3041）
        assertEquals("ゔぁいおりん", KanaNormalizer.normalize("ヴァイオリン"))
        // 长音统一
        assertEquals("すーぱー", KanaNormalizer.normalize("スーパー"))
        assertEquals("こーひー", KanaNormalizer.normalize("コーヒー"))
        // 全流程：半角片假名含浊点、长音
        assertEquals("ちょーけーしょん", KanaNormalizer.normalize("ﾁｮｰｹｰｼｮﾝ"))
    }
}

package com.shiyu.app.core.lang.en

import org.junit.Assert.assertEquals
import org.junit.Test

/** EnNormalizer（§5.6.3）：16 条锚点 + 边界扩展 */
class EnNormalizerTest {

    @Test
    fun anchor_cafe_strip_diacritics_trim_lower() {
        assertEquals("cafe", EnNormalizer.normalize("  CAFÉ  "))
    }

    @Test
    fun anchor_naive() = assertEquals("naive", EnNormalizer.normalize("naïve"))

    @Test
    fun anchor_zurich() = assertEquals("zurich", EnNormalizer.normalize("Zürich"))

    @Test
    fun anchor_resume() = assertEquals("resume", EnNormalizer.normalize("resumé"))

    @Test
    fun anchor_strasse_not_expanded() {
        // ß 没有 Unicode 分解映射，NFKC 不动它
        assertEquals("straße", EnNormalizer.normalize("Straße"))
    }

    @Test
    fun anchor_fullwidth_ascii() = assertEquals("abc123", EnNormalizer.normalize("ＡＢＣ１２３"))

    @Test
    fun anchor_fullwidth_hello_world() =
        assertEquals("hello world", EnNormalizer.normalize("Ｈｅｌｌｏ　Ｗｏｒｌｄ"))

    @Test
    fun anchor_curly_apostrophe() = assertEquals("don't", EnNormalizer.normalize("don’t"))

    @Test
    fun anchor_curly_quotes() = assertEquals("\"quote\"", EnNormalizer.normalize("“Quote”"))

    @Test
    fun anchor_em_dash() = assertEquals("-dash-", EnNormalizer.normalize("—dash—"))

    @Test
    fun anchor_whitespace_collapse() =
        assertEquals("a b c", EnNormalizer.normalize("a   b\t\tc"))

    @Test
    fun anchor_japanese_not_broken() =
        assertEquals("日本語", EnNormalizer.normalize("日本語"))

    @Test
    fun anchor_japanese_dakuten_not_stripped() =
        assertEquals("がっこう", EnNormalizer.normalize("がっこう"))

    @Test
    fun anchor_decimal_kept() = assertEquals("3.14", EnNormalizer.normalize("3.14"))

    @Test
    fun anchor_abbreviation_dot_kept() = assertEquals("u.s.", EnNormalizer.normalize("U.S."))

    @Test
    fun anchor_hello_upper() = assertEquals("hello", EnNormalizer.normalize("  HELLO  "))

    @Test
    fun extra_all_dash_variants() {
        assertEquals("a-b-c", EnNormalizer.normalize("a\u2013b\u2014c"))
        assertEquals("a-b", EnNormalizer.normalize("a\u2212b"))
    }

    @Test
    fun extra_all_apostrophe_variants() {
        // 弯撇号/变体撇号 → ASCII 直撇号（全部用 \u 转义，避免源码编码歧义）
        assertEquals("it's", EnNormalizer.normalize("it\u2018s"))
        assertEquals("it's", EnNormalizer.normalize("it\u2019s"))
        assertEquals("it's", EnNormalizer.normalize("it\u02BCs"))
        assertEquals("it's", EnNormalizer.normalize("it\u0060s"))
        // ´(U+00B4) 的 NFKC 分解是「空格 + 组合锐音符」，按规范顺序（先 NFKC 再映射）不会被映射表命中
        assertEquals("it \u0301s", EnNormalizer.normalize("it\u00B4s"))
    }

    @Test
    fun extra_double_quote_variants() {
        assertEquals("\"a\"", EnNormalizer.normalize("\u201Ea\u201C"))
        // ″(U+2033) 的 NFKC 分解是两个 ′(U+2032)，各自按撇号表映射为 '
        assertEquals("\"a''", EnNormalizer.normalize("\u201Da\u2033"))
    }

    @Test
    fun extra_multi_accent_words() {
        assertEquals("underage", EnNormalizer.normalize("\u00FB\u00F1d\u00E9r\u00E4g\u00E9"))
        assertEquals("cafe creme", EnNormalizer.normalize("caf\u00E9 cr\u00E8me"))
    }

    @Test
    fun extra_special_letters_kept() {
        // æ ø ł þ 没有分解映射，保持原样
        assertEquals("\u0153uvre", EnNormalizer.normalize("\u0152uvre"))
        assertEquals("\u00F8re", EnNormalizer.normalize("\u00D8re"))
    }

    @Test
    fun extra_locale_lowercase_stable() {
        // 大小写折叠必须与运行环境 Locale 无关（Locale.ROOT）
        assertEquals("title", EnNormalizer.normalize("TITLE"))
    }

    @Test
    fun extra_newline_collapsed() {
        assertEquals("a b", EnNormalizer.normalize("a\n\nb"))
        assertEquals("a b", EnNormalizer.normalize("a\u3000b"))
    }

    @Test
    fun extra_empty_and_blank() {
        assertEquals("", EnNormalizer.normalize(""))
        assertEquals("", EnNormalizer.normalize("   \t\n "))
    }

    @Test
    fun extra_roman_numeral_nfkc() {
        // NFKC 会把罗马数字 Ⅻ 展开为 XII
        assertEquals("xii", EnNormalizer.normalize("\u216B"))
    }

    @Test
    fun extra_fullwidth_punctuation() {
        // Ａ，Ｂ：Ｃ → a,b:c（全角标点 NFKC 到半角，再小写）
        assertEquals("a,b:c", EnNormalizer.normalize("\uFF21\uFF0C\uFF22\uFF1A\uFF23"))
    }
}

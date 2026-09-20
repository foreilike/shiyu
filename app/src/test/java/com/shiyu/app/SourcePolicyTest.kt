package com.shiyu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V21 / V16 的静态自查（镜像核验方的 grep）：
 * - ui/ 与 ai/ 不得出现语言专有符号（KanaConverter / EnTokenizer / romaji / toHiragana 等）
 * - 源码/资源/测试中不得出现 32 位以上形如密钥的字符串
 */
class SourcePolicyTest {

    private fun filesUnder(dir: String): List<File> {
        val root = File(dir)
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .toList()
    }

    @Test
    fun ui_and_ai_have_no_language_specific_symbols() {
        val banned = listOf(
            "KanaConverter", "KanaNormalizer", "EnTokenizer", "EnSentenceSplitter",
            "EnNormalizer", "JaModule", "EnModule", "toHiragana", "romaji",
            "katakana", "hiragana", "tokenize"
        )
        for (dir in listOf("src/main/java/com/shiyu/app/ui", "src/main/java/com/shiyu/app/ai")) {
            for (file in filesUnder(dir)) {
                val text = file.readText()
                for (symbol in banned) {
                    assertFalse(
                        "${file.name} contains language-specific symbol: $symbol",
                        text.contains(symbol)
                    )
                }
            }
        }
    }

    @Test
    fun no_secret_like_strings_in_sources() {
        val secretRegex = Regex("sk-[A-Za-z0-9]{20,}")
        // 只扫本工程相关源码与配置，排除 build 产物与 wrapper jar
        val scanRoots = listOf(
            "src/main/java", "src/main/res", "src/main/assets", "src/test",
            "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
            "local.properties", "../app/build.gradle.kts"
        )
        for (path in scanRoots) {
            val f = File(path)
            if (!f.exists()) continue
            val files = if (f.isFile) listOf(f) else filesUnder(path)
            for (file in files) {
                if (file.path.contains(File.separator + "build" + File.separator)) continue
                if (file.extension == "jar" || file.extension == "bin" || file.extension == "apk") continue
                val text = try {
                    file.readText()
                } catch (e: Exception) {
                    continue
                }
                assertFalse("secret-like string found in ${file.path}", secretRegex.containsMatchIn(text))
            }
        }
    }

    @Test
    fun manifest_has_exactly_two_permissions() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val regex = Regex("uses-permission[^>]*android:name=\"([^\"]+)\"")
        val names = regex.findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.RECORD_AUDIO"),
            names
        )
        assertFalse(manifest.contains("usesCleartextTraffic"))
    }

    @Test
    fun core_logic_free_of_android_imports() {
        // core/ 的纯逻辑层除 Context 外不得 import android.*
        val offenders = mutableListOf<String>()
        for (file in filesUnder("src/main/java/com/shiyu/app/core")) {
            val text = file.readText()
            val badImport = Regex("^import android\\.", RegexOption.MULTILINE)
            if (badImport.containsMatchIn(text)) {
                offenders.add(file.path)
            }
        }
        for (file in filesUnder("src/main/java/com/shiyu/app/data/store")) {
            val text = file.readText()
            if (Regex("^import android\\.", RegexOption.MULTILINE).containsMatchIn(text)) {
                offenders.add(file.path)
            }
        }
        assertTrue("android imports found in pure logic: $offenders", offenders.isEmpty())
    }
}

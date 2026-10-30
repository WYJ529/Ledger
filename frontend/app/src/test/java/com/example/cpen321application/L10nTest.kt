package com.example.cpen321application

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

class L10nTest {
    private val translations = mapOf("zh-Hans" to ZH_HANS, "zh-Hant" to ZH_HANT, "fr" to FR)
    private val placeholder = Regex("""%(\d+\$[sd]|%)""")

    @Test fun everyWrappedLiteralHasATranslation() {
        val sources = File("src/main/java/com/example/cpen321application").listFiles { file ->
            file.extension == "kt" && !file.name.startsWith("L10n")
        }.orEmpty()
        assertTrue("source files not found", sources.isNotEmpty())
        val call = Regex("""(?<![A-Za-z0-9_.])tf?\(\s*"((?:[^"\\]|\\.)*)"""")
        val keys = sources.flatMap { file -> call.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .filterNot { "\${" in it }
            .map { it.replace("\\$", "$").replace("\\\"", "\"").replace("\\\\", "\\") }
            .toSet()
        assertTrue(keys.size > 200)
        translations.forEach { (language, map) ->
            val missing = keys - map.keys
            assertTrue("$language is missing ${missing.size}: ${missing.take(5)}", missing.isEmpty())
        }
    }

    @Test fun languagesShareKeysAndPlaceholders() {
        translations.forEach { (language, map) ->
            assertEquals(language, ZH_HANS.keys, map.keys)
            map.forEach { (key, value) ->
                assertEquals("$language: $key", placeholders(key), placeholders(value))
                if (placeholders(key).isNotEmpty()) assertFalse("$language: $value", '%' in placeholder.replace(value, ""))
            }
        }
    }

    @Test fun translationFallsBackToEnglish() {
        assertEquals("退出登录", L10n.translate("Log out", AppLanguage.SimplifiedChinese))
        assertEquals("登出", L10n.translate("Log out", AppLanguage.TraditionalChinese))
        assertEquals("Se déconnecter", L10n.translate("Log out", AppLanguage.French))
        assertEquals("Log out", L10n.translate("Log out", AppLanguage.English))
        assertEquals("Not a known message", L10n.translate("Not a known message", AppLanguage.French))
    }

    @Test fun systemLocalesMapToSupportedLanguages() {
        assertEquals(AppLanguage.SimplifiedChinese, L10n.fromLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals(AppLanguage.TraditionalChinese, L10n.fromLocale(Locale.TRADITIONAL_CHINESE))
        assertEquals(AppLanguage.TraditionalChinese, L10n.fromLocale(Locale.forLanguageTag("zh-HK")))
        assertEquals(AppLanguage.TraditionalChinese, L10n.fromLocale(Locale.forLanguageTag("zh-Hant-CN")))
        assertEquals(AppLanguage.French, L10n.fromLocale(Locale.CANADA_FRENCH))
        assertEquals(AppLanguage.English, L10n.fromLocale(Locale.JAPANESE))
    }

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()
}

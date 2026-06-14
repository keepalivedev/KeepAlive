package io.keepalive.android

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards against translation drift between the master strings.xml and the
 * locale variants, without needing an emulator or Robolectric:
 *
 *  1. A locale must not define a key that the master no longer has
 *     (catches stale/renamed keys).
 *  2. Shared keys must use the same format specifiers (%1$s etc.) so a
 *     translated string can't crash String.format at alert time.
 *  3. A master translatable key missing from a locale must be on the
 *     documented allowlist below — adding a new English string without
 *     translations fails CI until it's translated or allowlisted.
 */
class LocaleStringParityTest {

    private val locales = listOf(
        "values-de-rDE", "values-fr-rCA", "values-it-rIT",
        "values-pl-rPL", "values-ru-rRU", "values-zh-rCN"
    )

    // Keys known to be missing from some locales. Shrink this list by
    // translating; never grow it without a conscious decision.
    private val knownMissing: Map<String, Set<String>> = mapOf()

    private data class StringRes(val value: String, val translatable: Boolean)

    private fun findResDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val direct = File(dir, "src/main/res")
            if (File(direct, "values/strings.xml").exists()) return direct
            val viaApp = File(dir, "app/src/main/res")
            if (File(viaApp, "values/strings.xml").exists()) return viaApp
            dir = dir.parentFile
                ?: error("could not locate src/main/res from ${System.getProperty("user.dir")}")
        }
    }

    private fun parseStrings(file: File): Map<String, StringRes> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, StringRes>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            result[el.getAttribute("name")] = StringRes(
                value = el.textContent,
                translatable = el.getAttribute("translatable") != "false"
            )
        }
        return result
    }

    // positional (%1$s) and bare (%s/%d/%f) java format tokens, sorted so
    // ordering differences between languages don't matter
    private fun formatTokens(value: String): List<String> =
        Regex("%(\\d+\\$)?[sdf]").findAll(value).map { it.value }.sorted().toList()

    @Test fun `master and locale files parse and are non-trivial`() {
        // guards the other tests against passing vacuously if the res path
        // or XML parsing ever silently breaks
        val resDir = findResDir()
        val master = parseStrings(File(resDir, "values/strings.xml"))
        assertTrue("master parsed only ${master.size} strings", master.size > 100)
        for (locale in locales) {
            val translated = parseStrings(File(resDir, "$locale/strings.xml"))
            assertTrue("$locale parsed only ${translated.size} strings", translated.size > 100)
        }
    }

    @Test fun `locales contain no keys missing from master`() {
        val resDir = findResDir()
        val master = parseStrings(File(resDir, "values/strings.xml"))
        val problems = StringBuilder()

        for (locale in locales) {
            val translated = parseStrings(File(resDir, "$locale/strings.xml"))
            val extras = translated.keys - master.keys
            if (extras.isNotEmpty()) {
                problems.append("$locale has keys not in master: $extras\n")
            }
        }
        assertTrue(problems.toString(), problems.isEmpty())
    }

    @Test fun `format specifiers match master in every locale`() {
        val resDir = findResDir()
        val master = parseStrings(File(resDir, "values/strings.xml"))
        val problems = StringBuilder()

        for (locale in locales) {
            val translated = parseStrings(File(resDir, "$locale/strings.xml"))
            for ((key, res) in translated) {
                val masterRes = master[key] ?: continue
                val masterTokens = formatTokens(masterRes.value)
                val localeTokens = formatTokens(res.value)
                if (masterTokens != localeTokens) {
                    problems.append(
                        "$locale/$key: master uses $masterTokens but locale uses $localeTokens\n"
                    )
                }
            }
        }
        assertTrue(problems.toString(), problems.isEmpty())
    }

    @Test fun `every translatable master key is translated or allowlisted`() {
        val resDir = findResDir()
        val master = parseStrings(File(resDir, "values/strings.xml"))
        val translatableKeys = master.filterValues { it.translatable }.keys
        val problems = StringBuilder()

        for (locale in locales) {
            val translated = parseStrings(File(resDir, "$locale/strings.xml"))
            val missing = translatableKeys - translated.keys - (knownMissing[locale] ?: emptySet())
            if (missing.isNotEmpty()) {
                problems.append("$locale is missing translations for: ${missing.sorted()}\n")
            }
        }
        assertTrue(
            "new untranslated strings found (translate them or add to knownMissing):\n$problems",
            problems.isEmpty()
        )
    }
}

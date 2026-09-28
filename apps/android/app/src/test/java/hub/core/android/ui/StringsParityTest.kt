package hub.core.android.ui

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every user string exists in Arabic and English, with the same placeholders (AGENTS.md). The
 * strings live in apps/android/i18n (`<lang>.json` and `<area>.<lang>.json`, ADR 0028) and the
 * string resources are generated from them at build time; translators never edit XML.
 */
class StringsParityTest {
    private val dir = File(System.getProperty("corehub.repoRoot") ?: "../../..", "apps/android/i18n")

    private fun files(lang: String) =
        dir.listFiles { f -> f.name == "$lang.json" || f.name.endsWith(".$lang.json") }!!.sorted()

    /** A catalogue flattened: a plural group becomes `key.one`, `key.other`, …. */
    private fun flat(file: File): Map<String, String> {
        val json = JSONObject(file.readText())
        val out = linkedMapOf<String, String>()
        for (key in json.keys()) {
            when (val value = json.get(key)) {
                is JSONObject -> value.keys().forEach { out["$key.$it"] = value.getString(it) }
                else -> out[key] = value.toString()
            }
        }
        return out
    }

    private fun strings(lang: String): Map<String, String> = files(lang).flatMap { flat(it).toList() }.toMap()

    @Test fun `the phone-parity strings are read too`() {
        assertTrue(strings("en").containsKey("workflows_run"))
        assertTrue(strings("ar").containsKey("workflows_run"))
    }

    private fun placeholders(text: String) = Regex("%\\d+\\$(\\.\\d+)?[sdf]").findAll(text).map { it.value }.sorted().toList()

    @Test fun `arabic and english have the same keys and placeholders`() {
        val en = strings("en")
        val ar = strings("ar")
        // A plural string: Arabic says all six forms, English the ones it uses (`other` always).
        val plain = { m: Map<String, String> -> m.filterKeys { !it.contains('.') } }
        assertEquals(plain(en).keys.sorted(), plain(ar).keys.sorted())
        val groups = { m: Map<String, String> -> m.keys.filter { it.contains('.') }.map { it.substringBefore('.') }.toSortedSet() }
        assertEquals(groups(en), groups(ar))
        for (group in groups(en)) {
            assertTrue("$group.other in English", en.containsKey("$group.other"))
            assertTrue("$group.other in Arabic", ar.containsKey("$group.other"))
        }
        for ((key, value) in plain(en)) {
            assertEquals("placeholders of $key", placeholders(value), placeholders(ar.getValue(key)))
            assertTrue("$key is empty", value.isNotBlank() && ar.getValue(key).isNotBlank())
        }
    }

    /**
     * A batch keeps its strings in a file of its own (`<area>.<lang>.json`, docs/clients/phone-pages.md):
     * every English file has its Arabic twin with the same keys, and no key is in two files.
     */
    @Test fun `every strings file has its arabic twin and no key is in two files`() {
        val english = files("en")
        assertTrue(english.size > 1)
        val seen = mutableMapOf<String, String>()
        for (en in english) {
            val ar = File(dir, en.name.removeSuffix("en.json") + "ar.json")
            assertTrue("${ar.name} is missing", ar.exists())
            val keys = JSONObject(en.readText()).keys().asSequence().toList().sorted()
            assertEquals("the keys of ${en.name}", keys, JSONObject(ar.readText()).keys().asSequence().toList().sorted())
            for (key in keys) {
                assertTrue("$key is in ${seen[key]} and ${en.name}", key !in seen)
                seen[key] = en.name
            }
        }
        val arOnly = files("ar").map { it.name.removeSuffix("ar.json") } - english.map { it.name.removeSuffix("en.json") }.toSet()
        assertTrue("Arabic files without an English twin: $arOnly", arOnly.isEmpty())
    }

    @Test fun `the ui says profile, never workspace`() {
        val all = strings("en") + strings("ar").mapKeys { "ar:" + it.key }
        for ((key, value) in all) {
            assertTrue("$key says workspace", !value.contains("workspace", ignoreCase = true) && !value.contains("مساحة العمل"))
        }
    }
}

package hub.core.android.parity

import hub.core.android.data.HubApis
import hub.core.android.data.HubError
import hub.core.android.ui.screens.AgentCardRules
import hub.core.android.ui.screens.AgentToolErrors
import hub.core.android.ui.screens.AgentToolOps
import hub.core.android.ui.screens.MemoryRules
import hub.core.android.ui.screens.PluginRules
import hub.core.android.ui.screens.SkillRules
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Agent
import hub.core.client.model.JobStatus
import hub.core.client.model.MemoryItem
import hub.core.client.model.SkillCategory
import hub.core.client.model.SkillLibrary
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Agents I on Android (apps batch 8): the rules of skills, memory, plugins and the cards, and their calls against a scripted hub. */
class AgentToolsTest {
    private val json = Serializer.kotlinxSerializationJson
    private val agentId = "01J8QK3ZR2W7M5N4P6T8V9X0AG"

    private fun skillJson(key: String, source: String, name: String = key, description: String? = null, library: String? = null, pinned: Boolean = false) =
        """{"key":"$key","name":"$name","description":${description?.let { "\"$it\"" } ?: "null"},"enabled":true,"pinned":$pinned,"source":"$source",
            "use_count":0,"updated_at":null,"content":null,"library":${library?.let { "\"$it\"" } ?: "null"}}"""

    private fun category(key: String, vararg skills: String) =
        json.decodeFromString(SkillCategory.serializer(), """{"key":"$key","name":"$key","description":null,"skills":[${skills.joinToString(",")}]}""")

    private fun agent(kind: String = "acp", status: String = "available", source: String = "managed", runtime: String = "not_applicable",
                      update: Boolean = false, latest: String? = null, pinned: String? = null, auto: Boolean = false, autoSupported: Boolean = true) =
        json.decodeFromString(
            Agent.serializer(),
            """{"id":"$agentId","profile":"work","owner_id":"u1","created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z",
                "slug":"codex","name":"Codex","kind":"$kind","avatar":{"kind":"generated","url":null,"seed":"c"},"status":"$status","enabled":true,
                "install":{"source":"$source","update_available":$update,"latest_version":${latest?.let { "\"$it\"" } ?: "null"},
                  "pinned_version":${pinned?.let { "\"$it\"" } ?: "null"},"newer_than_tested":false,"auto_update":$auto,"auto_update_supported":$autoSupported,"version":"1.0.0"},
                "runtime":{"state":"$runtime"},"capabilities":[],"sections":[],"limited":false,"subagents":"none","vendor":null}""",
        )

    // ------------------------------------------------------------------ skills

    @Test fun `the search and the chip narrow the skills, and empty categories drop out`() {
        val categories = listOf(
            category("user", skillJson("web", "user", "Web research", "Search and summarise"), skillJson("mine", "external")),
            category("core-hub", skillJson("image-generate", "library", library = "edited")),
            category("devops", skillJson("docker", "builtin", description = "Containers")),
        )
        assertEquals(4, SkillRules.total(categories))
        assertEquals(listOf("web"), SkillRules.narrow(categories, "SUMMAR", SkillRules.Filter.ALL).flatMap { c -> c.skills.map { it.key } })
        assertEquals(listOf("user"), SkillRules.narrow(categories, "", SkillRules.Filter.YOURS).map { it.key })
        assertEquals(listOf("core-hub"), SkillRules.narrow(categories, "", SkillRules.Filter.LIBRARY).map { it.key })
        assertEquals(listOf("devops"), SkillRules.narrow(categories, "contain", SkillRules.Filter.BUILTIN).map { it.key })
        assertTrue(SkillRules.narrow(categories, "docker", SkillRules.Filter.YOURS).isEmpty())
    }

    @Test fun `hermes's own skills are read-only, an edited library skill restores, a new key is a folder name`() {
        val builtin = category("x", skillJson("docker", "builtin")).skills.single()
        val edited = category("x", skillJson("image", "library", library = "edited", pinned = true)).skills.single()
        val broken = category("x", skillJson("bad", "user", description = "[unreadable: no front matter]")).skills.single()
        assertEquals(listOf(SkillRules.Action.OPEN, SkillRules.Action.PIN), SkillRules.actions(builtin))
        assertEquals(listOf(SkillRules.Action.OPEN, SkillRules.Action.UNPIN, SkillRules.Action.RESTORE, SkillRules.Action.DELETE), SkillRules.actions(edited))
        assertTrue(SkillRules.broken(broken))
        assertTrue(SkillRules.validKey("web-research.v2"))
        assertFalse(SkillRules.validKey("Web"))
        assertFalse(SkillRules.validKey("-web"))
        assertFalse(SkillRules.validKey(""))
        assertTrue(SkillRules.importable("pack.ZIP") && SkillRules.importable("SKILL.md") && SkillRules.importable("x.skill"))
        assertFalse(SkillRules.importable("notes.txt"))
    }

    @Test fun `the library card says on, not yet or off, and offers Install when some are missing`() {
        assertEquals(SkillRules.LibraryLine.OFF, SkillRules.libraryLine(SkillLibrary(false, 12, 0, 0)))
        assertEquals(SkillRules.LibraryLine.NONE, SkillRules.libraryLine(SkillLibrary(true, 12, 0, 0)))
        assertEquals(SkillRules.LibraryLine.ON, SkillRules.libraryLine(SkillLibrary(true, 12, 12, 1)))
        assertTrue(SkillRules.libraryMissing(SkillLibrary(true, 12, 11, 0)))
        assertFalse(SkillRules.libraryMissing(SkillLibrary(false, 12, 0, 0)))
    }

    // ------------------------------------------------------------------ memory

    private fun memory(content: String, limit: Int? = 30, entries: String? = null) = json.decodeFromString(
        MemoryItem.serializer(),
        """{"id":"memory","kind":"document","title":"MEMORY.md","content":${kotlinx.serialization.json.JsonPrimitive(content)},
            "tags":[],"revision":7,"entries":${entries ?: "null"},"char_limit":${limit ?: "null"},"char_count":null}""",
    )

    @Test fun `memory entries split on a section line, count code points, and are replaced, added or removed alone`() {
        val item = memory("short answers\n§\nEnglish commits")
        val entries = MemoryRules.listOf(item)
        assertEquals(listOf("short answers", "English commits"), entries)
        assertEquals("short answers\n§\nEnglish commits".length, MemoryRules.lengthOf(entries))
        assertEquals(1, MemoryRules.lengthOf(listOf("😀")))
        assertEquals(listOf("short answers", "a", "b"), MemoryRules.withEntry(entries, 1, "a\n  §  \nb"))
        assertEquals(listOf("short answers", "English commits", "new"), MemoryRules.withEntry(entries, null, " new "))
        assertEquals(listOf("English commits"), MemoryRules.without(entries, 0))
        assertTrue(MemoryRules.isList(item))
        assertFalse(MemoryRules.isList(item.copy(id = "soul")))
        assertEquals(listOf("from the hub"), MemoryRules.listOf(memory("x", entries = """["from the hub"]""")))
    }

    @Test fun `the budget warns from 80 percent and refuses growing past it, never shrinking`() {
        assertEquals(MemoryRules.Tone.NORMAL, MemoryRules.tone(10, 100))
        assertEquals(MemoryRules.Tone.WARNING, MemoryRules.tone(80, 100))
        assertEquals(MemoryRules.Tone.DANGER, MemoryRules.tone(101, 100))
        assertEquals(MemoryRules.Tone.NORMAL, MemoryRules.tone(500, null))
        assertTrue(MemoryRules.fits(100, 90, 100))
        assertFalse(MemoryRules.fits(101, 90, 100))
        assertTrue("an over-full list may shrink", MemoryRules.fits(110, 120, 100))
        assertFalse(MemoryRules.fits(121, 120, 100))
    }

    // ------------------------------------------------------------------ plugins and cards

    @Test fun `a plugin to install is one word that is not an option`() {
        assertTrue(PluginRules.validIdentifier(" owner/repo "))
        assertTrue(PluginRules.validIdentifier("https://github.com/a/b.git"))
        assertFalse(PluginRules.validIdentifier("--force"))
        assertFalse(PluginRules.validIdentifier("two words"))
        assertFalse(PluginRules.validIdentifier("   "))
    }

    @Test fun `a card installs what is missing, takes an update, restarts Hermes, and keeps the rest in its menu`() {
        assertEquals(AgentCardRules.Action.INSTALL, AgentCardRules.primary(agent(status = "not_installed", source = "none")))
        assertTrue(AgentCardRules.menu(agent(status = "not_installed", source = "none")).isEmpty())

        val upToDate = agent()
        assertNull(AgentCardRules.primary(upToDate))
        assertEquals(listOf(AgentCardRules.Action.CHECK_UPDATE, AgentCardRules.Action.AUTO_UPDATE_ON, AgentCardRules.Action.UNINSTALL), AgentCardRules.menu(upToDate))

        val behind = agent(update = true, latest = "1.2.0", pinned = "1.1.0", auto = true)
        assertEquals(AgentCardRules.Action.UPGRADE, AgentCardRules.primary(behind))
        assertEquals("1.2.0", AgentCardRules.update(behind))
        assertTrue(AgentCardRules.updateUntested(behind))
        assertEquals(listOf(AgentCardRules.Action.CHECK_UPDATE, AgentCardRules.Action.AUTO_UPDATE_OFF, AgentCardRules.Action.UNINSTALL), AgentCardRules.menu(behind))

        val hermes = agent(kind = "hermes", source = "builtin", runtime = "running")
        assertEquals(AgentCardRules.Action.RESTART, AgentCardRules.primary(hermes))
        assertTrue("an image's own agent is neither updated nor removed here", AgentCardRules.menu(hermes).isEmpty())
        assertNull("a Hermes the hub only found is not restarted", AgentCardRules.primary(agent(kind = "hermes", source = "builtin")))
        assertEquals(listOf(AgentCardRules.Action.CHECK_UPDATE), AgentCardRules.menu(agent(source = "user_cli", autoSupported = false)))
    }

    @Test fun `refusals the hub names read in our words, an import's own sentence follows`() {
        assertEquals(hub.core.android.R.string.agents_import_skill_exists to true, AgentToolErrors.lead(HubError(409, "conflict", "x", reason = "skill_exists")))
        assertEquals(hub.core.android.R.string.agents_skill_bundled_refused to false, AgentToolErrors.lead(HubError(409, "conflict", "x", reason = "skill_bundled")))
        assertNull(AgentToolErrors.lead(HubError(500, "internal", "boom")))
    }

    // ------------------------------------------------------------------ the calls, against a scripted hub

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private var jobReads = 0

    private fun ok(body: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private fun job(status: String, percent: String = "null", result: String = "null") =
        """{"id":"01J8QK3ZR2W7M5N4P6T8V9X0JB","profile":"work","owner_id":"u1","created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z",
            "kind":"install","status":"$status","progress":{"percent":$percent,"message":null},"resource":null,"result":$result,"error":null,
            "started_at":null,"finished_at":null}"""

    private fun attachment(id: String) =
        """{"id":"$id","profile":"work","owner_id":"u1","created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z",
            "name":"SKILL.md","mime":"text/markdown","size_bytes":10,"purpose":"skill","url":"/x","kind":"file","sha256":"abc"}"""

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/attachments") && request.method == "POST" -> ok(attachment("01J8QK3ZR2W7M5N4P6T8V9X0A${requests.size}"), 201)
                    path.contains("/attachments/") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path.endsWith("/agents/$agentId/skills") && request.method == "POST" ->
                        ok("""{"error":"exists","code":"conflict","details":{"reason":"skill_exists","skill":"web"}}""", 409)
                    path.endsWith("/memory/memory") -> ok(memoryJson())
                    path.endsWith("/agents/$agentId/plugins") && request.method == "POST" -> ok("""{"job_id":"01J8QK3ZR2W7M5N4P6T8V9X0JB"}""", 202)
                    path.endsWith("/agents/$agentId/update") -> ok("""{"job_id":"01J8QK3ZR2W7M5N4P6T8V9X0JB"}""", 202)
                    path.endsWith("/jobs/01J8QK3ZR2W7M5N4P6T8V9X0JB") -> {
                        jobReads++
                        if (jobReads < 2) ok(job("running", "40")) else ok(job("succeeded", "100", """{"version":"1.2.0","name":"chrome"}"""))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private fun memoryJson() = """{"id":"memory","kind":"document","title":"MEMORY.md","content":"b","tags":[],"revision":8}"""

    @After fun stop() = server.shutdown()

    private fun ops() = AgentToolOps({ HubApis(server.url("/").toString().trimEnd('/'), OkHttpClient()) }, "work", agentId)

    @Test fun `an import uploads each pack for a skill, installs them, and deletes the uploads even when refused`() = runTest {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val files = listOf(File(dir, "SKILL.md").apply { writeText("---\nname: web\n---\nhi") }, File(dir, "pack.zip").apply { writeBytes(byteArrayOf(1, 2)) })
        val refused = ops().importSkills(files).exceptionOrNull() as HubError
        assertEquals(409, refused.status)
        assertEquals("skill_exists", refused.reason)
        val uploads = requests.filter { it.method == "POST" && it.requestUrl!!.encodedPath.endsWith("/attachments") }
        assertEquals(2, uploads.size)
        assertTrue(uploads.all { it.getHeader("X-Hub-Profile") == "work" && it.body.readUtf8().contains("skill") })
        val import = requests.single { it.requestUrl!!.encodedPath.endsWith("/skills") }
        val body = import.body.readUtf8()
        assertTrue(body, body.contains("attachment_ids") && body.contains("01J8QK3ZR2W7M5N4P6T8V9X0A1"))
        assertEquals(2, requests.count { it.method == "DELETE" && it.requestUrl!!.encodedPath.contains("/attachments/") })
        dir.deleteRecursively()
    }

    @Test fun `removing an entry writes the list without it, at the revision it was read`() = runTest {
        val item = memory("first\n§\nsecond\n§\nthird")
        assertTrue(ops().removeEntry(item, 1).isSuccess)
        val put = requests.last()
        assertEquals("PUT", put.method)
        assertEquals("work", put.getHeader("X-Hub-Profile"))
        val body = put.body.readUtf8()
        assertTrue(body, body.contains("\"content\":\"first\\n§\\nthird\"") && body.contains("\"revision\":7"))
    }

    @Test fun `a plugin install and an update are jobs followed to their end`() = runTest {
        val id = ops().installPlugin(" owner/repo ").getOrThrow()
        assertTrue(requests.last().body.readUtf8().contains("\"identifier\":\"owner/repo\""))
        val seen = mutableListOf<JobStatus>()
        val done = ops().follow(id!!, everyMs = 1) { seen += it.status }
        assertEquals(listOf(JobStatus.RUNNING, JobStatus.SUCCEEDED), seen)
        assertEquals("1.2.0", AgentCardRules.resultVersion(done))
        assertEquals(0.4f, AgentCardRules.progress(json.decodeFromString(hub.core.client.model.Job.serializer(), job("running", "40"))))

        val upgrade = ops().act(AgentCardRules.Action.UPGRADE).getOrThrow()
        assertEquals("01J8QK3ZR2W7M5N4P6T8V9X0JB", upgrade)
        assertEquals("POST", requests.last().method)
        assertTrue(requests.last().requestUrl!!.encodedPath.endsWith("/agents/$agentId/update"))
    }
}

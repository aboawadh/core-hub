package hub.core.android.parity

import hub.core.android.chat.AttachmentHandOff
import hub.core.android.chat.AttachmentTray
import hub.core.android.nav.Route
import hub.core.android.ui.screens.BackgroundOps
import hub.core.android.ui.screens.BackgroundRules
import hub.core.client.api.JobsApi
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Attachment
import hub.core.client.model.BackgroundItem
import hub.core.client.model.ContentBlock
import java.time.OffsetDateTime
import kotlinx.coroutines.test.TestScope
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

/**
 * The phone leftovers of the apps night (Android): a profile file handed to a chat's composer
 * ready (no second upload), and the Background sheet's rules and calls against a scripted hub.
 * iOS's LeftoversTests is the twin.
 */
class LeftoversTest {
    private val json = Serializer.kotlinxSerializationJson

    private fun attachment(id: String, kind: String = "file", profile: String = "work") = json.decodeFromString(
        Attachment.serializer(),
        """{"id":"$id","profile":"$profile","owner_id":"01J8QK3ZR2W7M5N4P6T8V9X0HM","created_at":"2026-09-25T10:14:50Z",
            "updated_at":"2026-09-25T10:14:50Z","name":"plan.md","mime":"text/markdown","size_bytes":7,"kind":"$kind",
            "url":"https://hub.example/$id","purpose":"message","width":null,"height":null,"duration_ms":null,
            "sha256":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"}""",
    )

    // ------------------------------------------------------------------ the hand-off

    @Test fun `a handed-off file is taken once, by the composer of its own profile`() {
        var now = 1_000L
        val slot = AttachmentHandOff { now }
        slot.put("work", listOf(attachment("01J8QK3ZR2W7M5N4P6T8V9X0A1")))
        assertTrue(slot.take("home").isEmpty())
        assertEquals(listOf("01J8QK3ZR2W7M5N4P6T8V9X0A1"), slot.take("work").map { it.id })
        assertTrue(slot.take("work").isEmpty())
        slot.put("work", listOf(attachment("01J8QK3ZR2W7M5N4P6T8V9X0A2")))
        now += AttachmentHandOff.TTL_MS + 1
        assertTrue("an old hand-off does not surprise a chat opened much later", slot.take("work").isEmpty())
    }

    @Test fun `a ready attachment sits in the tray and goes with the next message without an upload`() = runTest {
        var uploads = 0
        val tray = AttachmentTray(TestScope(testScheduler), upload = { uploads++; error("no upload") }, discard = {})
        val file = attachment("01J8QK3ZR2W7M5N4P6T8V9X0A1")
        tray.addReady(file)
        tray.addReady(file)
        assertEquals(1, tray.items.value.size)
        assertFalse(tray.uploading)
        val blocks = tray.message("Read this").blocks()
        assertEquals(listOf(ContentBlock.Type.TEXT, ContentBlock.Type.FILE), blocks.map { it.type })
        assertEquals("01J8QK3ZR2W7M5N4P6T8V9X0A1", blocks[1].attachmentId)
        tray.addReady(attachment("01J8QK3ZR2W7M5N4P6T8V9X0A3", kind = "image"))
        assertTrue(tray.items.value.last().isImage)
        assertEquals(0, uploads)
    }

    // ------------------------------------------------------------------ Background: the rules

    private fun item(
        kind: String,
        session: String? = null,
        jobKind: String? = null,
        resource: String? = null,
        started: String? = "2026-09-27T10:00:00Z",
        finished: String? = null,
        title: String = "Plan",
        profile: String = "work",
        stoppable: Boolean = true,
    ) = json.decodeFromString(
        BackgroundItem.serializer(),
        """{"id":"x:$kind","kind":"$kind","job_kind":${jobKind?.let { "\"$it\"" } ?: "null"},"title":"$title","profile":"$profile",
            "status":"${if (finished == null) "running" else "succeeded"}","started_at":${started?.let { "\"$it\"" } ?: "null"},
            "finished_at":${finished?.let { "\"$it\"" } ?: "null"},"stoppable":$stoppable,
            "session_id":${session?.let { "\"$it\"" } ?: "null"},"resource":$resource}""",
    )

    @Test fun `each item opens where it lives`() {
        assertEquals(Route.Tasks, BackgroundRules.routeOf(item("task_run", session = "01J8QK3ZR2W7M5N4P6T8V9X0YA")))
        assertEquals(Route.Schedules, BackgroundRules.routeOf(item("workflow_run", resource = """{"kind":"workflow_run","id":"01J8QK3ZR2W7M5N4P6T8V9X0WR"}""")))
        assertEquals(Route.Chat("01J8QK3ZR2W7M5N4P6T8V9X0YA", "home"), BackgroundRules.routeOf(item("subagent", session = "01J8QK3ZR2W7M5N4P6T8V9X0YA", profile = "home")))
        assertEquals(Route.Chat("01J8QK3ZR2W7M5N4P6T8V9X0YA", "work"), BackgroundRules.routeOf(item("chat_run", session = "01J8QK3ZR2W7M5N4P6T8V9X0YA")))
        assertEquals(Route.SettingsPage("workspaces"), BackgroundRules.routeOf(item("job", jobKind = "export")))
        assertEquals(Route.Agents, BackgroundRules.routeOf(item("job", jobKind = "plugin_install", resource = """{"kind":"agent","id":"01J8QK3ZR2W7M5N4P6T8V9X0AG"}""")))
        assertEquals(Route.SettingsPage("webhooks"), BackgroundRules.routeOf(item("job", jobKind = "webhook_test")))
        assertNull(BackgroundRules.routeOf(item("job", jobKind = "run")))
    }

    @Test fun `time is counted in Latin digits, and a queued item has none yet`() {
        val now = OffsetDateTime.parse("2026-09-27T10:12:05Z").toInstant().toEpochMilli()
        assertEquals("12:05", BackgroundRules.clock(BackgroundRules.elapsedMs(item("chat_run"), now)!!))
        assertEquals("1:00", BackgroundRules.clock(BackgroundRules.elapsedMs(item("job", finished = "2026-09-27T10:01:00Z"), now)!!))
        assertNull(BackgroundRules.elapsedMs(item("chat_run", started = null), now))
        assertEquals("1:02:09", BackgroundRules.clock(3_729_000))
    }

    @Test fun `a row without words says what it is`() {
        assertEquals("export", BackgroundRules.title(item("job", jobKind = "export", title = " "), "Untitled"))
        assertEquals("Untitled", BackgroundRules.title(item("chat_run", title = ""), "Untitled"))
        assertEquals("Plan", BackgroundRules.title(item("chat_run"), "Untitled"))
    }

    // ------------------------------------------------------------------ Background: the calls

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/background") -> ok("""{"running":[],"finished":[]}""")
                    path.contains("/background/") && path.endsWith("/stop") -> ok(
                        """{"id":"run:01J8QK3ZR2W7M5N4P6T8V9X0RN","kind":"chat_run","job_kind":null,"title":"Plan","profile":"home","status":"running",
                            "started_at":"2026-09-27T10:00:00Z","finished_at":null,"stoppable":false,"session_id":null,"resource":null}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun ops() = BackgroundOps({ "work" }, { JobsApi(server.url("/").toString().trimEnd('/'), OkHttpClient()) })

    @Test fun `the list covers every profile, and a stop goes to the item's own profile`() = runTest {
        ops().list().getOrThrow()
        assertEquals("all", requests[0].requestUrl!!.queryParameter("profiles"))
        assertEquals("work", requests[0].getHeader("X-Hub-Profile"))
        ops().stop(item("chat_run", profile = "home").copy(id = "run:01J8QK3ZR2W7M5N4P6T8V9X0RN")).getOrThrow()
        assertEquals("POST", requests[1].method)
        assertEquals(listOf("background", "run:01J8QK3ZR2W7M5N4P6T8V9X0RN", "stop"), requests[1].requestUrl!!.pathSegments.takeLast(3))
        assertEquals("home", requests[1].getHeader("X-Hub-Profile"))
    }
}

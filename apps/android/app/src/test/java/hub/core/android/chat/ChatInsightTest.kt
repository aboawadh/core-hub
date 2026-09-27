package hub.core.android.chat

import hub.core.android.data.HubApis
import hub.core.android.realtime.Envelope
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.ContextUsage
import hub.core.client.model.Model
import hub.core.client.model.ModelKind
import hub.core.client.model.Money
import hub.core.client.model.Run
import hub.core.client.model.RunFileChangeKind
import hub.core.client.model.RunFileDiffState
import hub.core.client.model.RunStatus
import hub.core.client.model.RunTrigger
import hub.core.client.model.SessionContextCategory
import hub.core.client.model.Subagent
import hub.core.client.model.SubagentStatus
import hub.core.client.model.SubagentSupport
import hub.core.client.model.Usage
import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The chat insight (apps batch 6): the context ring and its sheet, compress with a focus, the chat's
 * runs, the subagents tree, the changed files' diffs, and the calls behind them. iOS
 * ChatInsightTests checks the same rules.
 */
class ChatInsightTest {
    private val server = MockWebServer()
    private val json = Serializer.kotlinxSerializationJson
    private val base = OffsetDateTime.parse("2026-09-27T10:00:00Z")
    private val session = "01J8QK3ZR2W7M5N4P6T8V9X0S1"

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    private fun hub() = HubApis(server.url("/").toString(), OkHttpClient())

    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun run(id: String, started: Long, input: Int, output: Int, cost: Money? = null, status: RunStatus = RunStatus.SUCCEEDED) = Run(
        id = id, profile = "work", ownerId = "u1", createdAt = base, updatedAt = base, sessionId = session, jobId = "job-$id",
        status = status, trigger = RunTrigger(RunTrigger.Kind.USER), interrupted = false,
        usage = Usage(input, output, cost), startedAt = base.plusSeconds(started), finishedAt = base.plusSeconds(started + 65),
    )

    @Test fun `the ring says how full the window is and where the figure came from`() {
        val reported = ChatInsight.use(ContextUsage(48_210, 200_000), null, emptyList())!!
        assertEquals(ChatInsight.Source.REPORTED, reported.source)
        assertEquals(24, ChatInsight.percent(reported))
        assertEquals(ChatInsight.Band.NORMAL, ChatInsight.band(reported))

        val rough = ChatInsight.use(ContextUsage(180_000, null, estimated = true), 200_000, emptyList())!!
        assertEquals(ChatInsight.Source.AGENT_ESTIMATE, rough.source)
        assertEquals("the catalogue's window fills in one the agent did not say", 200_000, rough.window)
        assertEquals(ChatInsight.Band.DANGER, ChatInsight.band(rough))

        val runs = listOf(run("a", 0, 1_000, 100), run("b", 100, 140_000, 2_000))
        val estimate = ChatInsight.use(null, 200_000, runs)!!
        assertEquals(ChatInsight.Source.ESTIMATE, estimate.source)
        assertEquals("the last counted turn, not the first", 142_000, estimate.used)
        assertEquals(ChatInsight.Band.WARNING, ChatInsight.band(estimate))

        assertNull("no window known is no ring", ChatInsight.use(null, null, runs))
        assertNull("nothing counted is no ring", ChatInsight.use(null, 200_000, emptyList()))
        assertEquals("99.6 % is not 100 %", 99, ChatInsight.percent(ChatInsight.use(ContextUsage(199_300, 200_000), null, emptyList())!!))
    }

    @Test fun `the breakdown shares the whole window or their sum, in Latin digits`() {
        val categories = listOf(SessionContextCategory("system_prompt", "System", 50), SessionContextCategory("conversation", "Chat", 150))
        assertEquals(listOf(0.05, 0.15), ChatInsight.shares(categories, 1_000))
        assertEquals(listOf(0.25, 0.75), ChatInsight.shares(categories, 100))
        assertEquals("48,210", ChatInsight.number(48_210))
        assertTrue("tool_definitions" in ChatInsight.KNOWN_CATEGORIES)
    }

    @Test fun `the catalogue carries the model's window for the ring`() {
        val model = Model(
            key = "anthropic/claude", providerId = "anthropic", provider = "Anthropic", model = "claude", kind = ModelKind.CHAT,
            visible = true, custom = false, preview = false, disabled = false, capabilities = emptyList(), contextWindow = 200_000,
        )
        assertEquals(200_000, ChatControls.models(listOf(model)).single().window)
    }

    @Test fun `compress sends the focus, or an empty body for the whole chat`() = runTest {
        val actions = ChatActions(hub())
        val answer = """{"status":"compressed","before_tokens":90000,"after_tokens":12000,"before_messages":40,"after_messages":6,"context":null,"message":null}"""
        server.enqueue(ok(answer))
        actions.compress(session, "work", "  keep the test plan  ")
        val focused = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("keep the test plan", focused["focus"]?.jsonPrimitive?.content)

        server.enqueue(ok(answer))
        actions.compress(session, "work", " \n ")
        assertFalse("nothing typed compresses the whole chat", "focus" in json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject)
        assertEquals(2000, ChatInsight.compressRequest("x".repeat(2_500)).focus?.length)
    }

    @Test fun `runs read newest first with their time, tokens and cost`() = runTest {
        assertEquals(listOf("b", "a"), ChatInsight.history(listOf(run("a", 0, 1, 1), run("b", 300, 1, 1))).map { it.id })
        assertEquals(65_000L, ChatInsight.elapsedMs(base, base.plusSeconds(65)))
        assertNull(ChatInsight.elapsedMs(null, null))
        assertEquals("1:05", ChatInsight.clock(65_000))
        assertEquals("1:02:03", ChatInsight.clock(3_723_000))
        assertEquals("0.13 USD", ChatInsight.cost(Money("0.1310", "USD")))
        assertEquals("0.0004 USD", ChatInsight.cost(Money("0.000420", "USD")))
        assertEquals("0 USD", ChatInsight.cost(Money("0", "USD")))
        assertNull("no price is no line", ChatInsight.cost(null))
        assertEquals(ChatInsight.RunTone.RUNNING, ChatInsight.tone(RunStatus.WAITING))

        val runJson = json.encodeToString(Run.serializer(), run("01J8QK3ZR2W7M5N4P6T8V9X0R1", 0, 2300, 410, Money("0.0131", "USD")))
        server.enqueue(ok("""{"items":[$runJson],"next_cursor":"next"}"""))
        val page = ChatInsightApi(hub()).runs(session, "work", null)
        assertEquals(2300, page.items.single().usage?.inputTokens)
        assertEquals("next", page.nextCursor)
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/sessions/$session/runs"))
        assertEquals("30", request.requestUrl?.queryParameter("limit"))
    }

    private fun subagent(id: String, parent: String? = null, depth: Int = 0, status: SubagentStatus = SubagentStatus.RUNNING, started: Long, finished: Long? = null) =
        Subagent(
            id = id, sessionId = session, depth = depth, goal = "goal $id", status = status, startedAt = base.plusSeconds(started),
            acceptingSteer = true, tools = emptyList(), parentId = parent, finishedAt = finished?.let { base.plusSeconds(it) },
        )

    @Test fun `subagents run as a tree, the finished newest first, and stop, steer and tail reach the hub`() = runTest {
        val items = listOf(
            subagent("child", parent = "root", depth = 1, started = 5),
            subagent("root", started = 1),
            subagent("orphan", parent = "gone", depth = 2, started = 3),
            subagent("old", status = SubagentStatus.COMPLETED, started = 0, finished = 10),
            subagent("new", status = SubagentStatus.FAILED, started = 0, finished = 20),
        )
        val (running, finished) = ChatInsight.split(items)
        assertEquals(listOf("root", "child", "orphan"), running.map { it.subagent.id })
        assertEquals("a subagent whose parent is not running keeps its depth", listOf(0, 1, 2), running.map { it.indent })
        assertEquals(listOf("new", "old"), finished.map { it.id })
        val stopped = items[1].copy(status = SubagentStatus.INTERRUPTED)
        assertEquals(SubagentStatus.INTERRUPTED, ChatInsight.upsert(items, stopped).first { it.id == "root" }.status)
        assertEquals(items.size, ChatInsight.upsert(items, stopped).size)
        assertEquals("only the server tests", ChatInsight.steerText("  only the server tests "))
        assertNull(ChatInsight.steerText("   "))

        val api = ChatInsightApi(hub())
        val one = json.encodeToString(Subagent.serializer(), items[1])
        server.enqueue(ok("""{"support":"full","items":[$one]}"""))
        assertEquals(SubagentSupport.FULL, api.subagents(session, "work").support)
        assertTrue(server.takeRequest().path!!.endsWith("/sessions/$session/subagents"))
        server.enqueue(ok("""{"status":"queued"}"""))
        api.steer(session, "work", "root", "focus on the server")
        val steer = server.takeRequest()
        assertTrue(steer.path!!.endsWith("/subagents/root/steer"))
        assertEquals("focus on the server", json.parseToJsonElement(steer.body.readUtf8()).jsonObject["text"]?.jsonPrimitive?.content)
        server.enqueue(ok(one))
        api.interrupt(session, "work", "root")
        assertTrue(server.takeRequest().path!!.endsWith("/subagents/root/interrupt"))
        server.enqueue(ok("""{"available":true,"text":"read tests","truncated":false}"""))
        assertEquals("read tests", api.tail(session, "work", "root").text)
        assertTrue(server.takeRequest().path!!.endsWith("/subagents/root/tail"))
    }

    @Test fun `a unified diff reads into hunks with line numbers`() {
        val hunks = ChatInsight.parseDiff("@@ -1,2 +1,3 @@\n # notes\n-old\n+new\n+another\n\\ No newline at end of file\n")
        assertEquals(1, hunks.size)
        val lines = hunks.single().lines
        assertEquals(
            listOf(ChatInsight.DiffKind.CONTEXT, ChatInsight.DiffKind.DEL, ChatInsight.DiffKind.ADD, ChatInsight.DiffKind.ADD, ChatInsight.DiffKind.NOTE),
            lines.map { it.kind },
        )
        assertEquals(listOf(1, 2, null, null, null), lines.map { it.old })
        assertEquals(listOf(1, null, 2, 3, null), lines.map { it.new })
        assertEquals("another", lines[3].text)
        assertEquals(12, ChatInsight.parseDiff("@@ -10 +12 @@ func\n x\n").single().lines.single().new)
        assertTrue(ChatInsight.parseDiff("no hunk here").isEmpty())
    }

    @Test fun `changed files say what they can show, and the live run's changes, diff and files reach the hub`() = runTest {
        assertEquals("+14 −2", ChatInsight.counts(14, 2))
        assertNull(ChatInsight.counts(null, null))
        assertNull(ChatInsight.noDiff(RunFileDiffState.AVAILABLE, live = false))
        assertEquals("a live run's diff is not recorded yet", ChatInsight.NoDiff.LIVE, ChatInsight.noDiff(RunFileDiffState.AVAILABLE, live = true))
        assertEquals(ChatInsight.NoDiff.TOO_LARGE, ChatInsight.noDiff(RunFileDiffState.TOO_LARGE, live = false))
        assertFalse(ChatInsight.canOpen(RunFileChangeKind.DELETED))
        assertEquals("app.ts", ChatInsight.fileName("src/app.ts"))
        assertEquals("a,b", ChatInsight.changesRevision(listOf("b", "a")))

        val api = ChatInsightApi(hub())
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"error":"not found","code":"not_found"}"""))
        assertNull("a run with nothing to compare is no live card", api.liveChanges(session, "work", "01J8QK3ZR2W7M5N4P6T8V9X0R1"))
        assertTrue(server.takeRequest().path!!.endsWith("/runs/01J8QK3ZR2W7M5N4P6T8V9X0R1/changes"))

        server.enqueue(ok("""{"run_id":"01J8QK3ZR2W7M5N4P6T8V9X0R1","source":"snapshot","complete":true,"files_changed":1,"additions":2,"deletions":1,"truncated":false,"recorded_at":"2026-09-27T10:00:00Z","live":true,"files":[{"path":"notes.md","old_path":null,"change":"modified","additions":2,"deletions":1,"binary":false,"diff":"available"}]}"""))
        val live = api.liveChanges(session, "work", "01J8QK3ZR2W7M5N4P6T8V9X0R1")
        assertEquals(true, live?.live)
        server.takeRequest()

        server.enqueue(ok("""{"run_id":"01J8QK3ZR2W7M5N4P6T8V9X0R1","path":"notes.md","old_path":null,"change":"modified","additions":2,"deletions":1,"binary":false,"diff":"available","truncated":false,"text":"@@ -1,2 +1,3 @@\n # x\n-a\n+b\n"}"""))
        val diff = api.diff(session, "work", "01J8QK3ZR2W7M5N4P6T8V9X0R1", "docs/notes.md")
        assertNotNull(diff.text)
        assertEquals("docs/notes.md", server.takeRequest().requestUrl?.queryParameter("path"))

        server.enqueue(ok("""{"items":[],"next_cursor":null}"""))
        api.changes(session, "work", null)
        assertEquals("20", server.takeRequest().requestUrl?.queryParameter("limit"))

        server.enqueue(ok("""{"working_dir":"/w/trip","truncated":false,"items":[]}"""))
        assertEquals("/w/trip", api.files(session, "work").workingDir)
    }

    @Test fun `the open chat keeps the window the agent reports`() {
        val state = ChatState(session = ChatSessionInfo(session, "work", "Trip", "01J8QK3ZR2W7M5N4P6T8V9X0AG"))
        val envelope = Envelope.parse(
            """{"event":"context.updated","namespace":"/rt/sessions","profile":"work","ts":"2026-09-27T10:00:00Z","seq":7,
               "payload":{"session_id":"$session","context":{"used_tokens":47210,"window_tokens":256000},"usage":null}}""",
        )!!
        val next = ChatReducer.apply(state, envelope, 0)
        assertEquals(47210, next.session?.context?.usedTokens)
        assertEquals(256000, next.session?.context?.windowTokens)
    }
}

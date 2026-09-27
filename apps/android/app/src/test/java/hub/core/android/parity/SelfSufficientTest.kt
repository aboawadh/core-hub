package hub.core.android.parity

import hub.core.android.data.HubApis
import hub.core.android.data.HubError
import hub.core.android.nav.Focus
import hub.core.android.nav.FocusItem
import hub.core.android.nav.HubLinks
import hub.core.android.nav.InAppLink
import hub.core.android.nav.Route
import hub.core.android.terminal.TerminalAck
import hub.core.android.terminal.TerminalEvent
import hub.core.android.terminal.TerminalKeys
import hub.core.android.terminal.TerminalScreen
import hub.core.android.ui.screens.ArchiveFilter
import hub.core.android.ui.screens.ChannelPairRules
import hub.core.android.ui.screens.ChannelTranscript
import hub.core.android.ui.screens.ChatGroup
import hub.core.android.ui.screens.ChatGroupsOps
import hub.core.android.ui.screens.ChatGroupsRules
import hub.core.android.ui.screens.HubDisplay
import hub.core.android.ui.screens.LinkedHubsOps
import hub.core.android.ui.screens.LinkedHubsRules
import hub.core.android.ui.screens.ProbeRules
import hub.core.android.ui.screens.RuntimeRules
import hub.core.android.ui.screens.StepFilter
import hub.core.android.ui.screens.TrajectoryRules
import hub.core.android.ui.screens.UpdatesOps
import hub.core.android.ui.screens.UpdatesRules
import hub.core.android.ui.screens.WorkflowDraft
import hub.core.android.ui.screens.WorkflowDraftRules
import hub.core.android.ui.screens.WorkflowEditOps
import hub.core.android.ui.screens.WorktreeRules
import hub.core.client.api.DevicesApi
import hub.core.client.api.UpdatesApi
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Agent
import hub.core.client.model.ChannelConversation
import hub.core.client.model.ChannelLoginRequest
import hub.core.client.model.ChannelMessage
import hub.core.client.model.Job
import hub.core.client.model.Peer
import hub.core.client.model.PeerPatch
import hub.core.client.model.Preferences
import hub.core.client.model.ProviderKind
import hub.core.client.model.ProviderProbeResult
import hub.core.client.model.Release
import hub.core.client.model.ResourceRef
import hub.core.client.model.RunCreate
import hub.core.client.model.RuntimeCheck
import hub.core.client.model.RuntimeReport
import hub.core.client.model.Session
import hub.core.client.model.SessionCategory
import hub.core.client.model.TrajectoryStep
import hub.core.client.model.UpdateSettings
import hub.core.client.model.WorkflowEdge
import hub.core.client.model.WorkflowNode
import hub.core.client.model.Worktree
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every section of the Android app works on its own (2026-09-27): the pages and flows that used
 * to open the web — Updates, Linked hubs, the terminal, WhatsApp's QR pairing, a hub link in a
 * reply — and the sections the phone lacked — chat categories, channel conversations, the
 * trajectory, workflow editing, the Runtime checks, Fetch in Add provider, a task's worktree, the
 * Display preferences applied — checked by their rules and, where they call the hub, against a
 * scripted one.
 */
class SelfSufficientTest {
    private val json = Serializer.kotlinxSerializationJson
    private val at = "2026-09-27T10:00:00Z"

    // ------------------------------------------------------------------ fixtures

    private fun session(id: String, profile: String = "work", source: String = "chat", channel: String? = null, category: String? = null) =
        json.decodeFromString(
            Session.serializer(),
            """{"id":"$id","profile":"$profile","owner_id":"01J8QK3ZR2W7M5N4P6T8V9X0HM","created_at":"$at","updated_at":"$at",
               "agent_id":"01J8QK3ZR2W7M5N4P6T8V9X0AG","title":"T $id","source":"$source","origin":null,
               "channel":${channel?.let { "\"$it\"" } ?: "null"},"model":null,"provider":null,"reasoning_effort":null,"working_dir":null,
               "pinned":false,"archived":false,"category_id":${category?.let { "\"$it\"" } ?: "null"},"preview":null,"message_count":0,"usage":null,
               "context":null,"status":"idle","active_run_id":null,"parent_session_id":null,"notify":false,"last_message_at":"$at","match":null}""",
        )

    private fun category(id: String, profile: String = "work", position: Int = 0, name: String = "C $id") = json.decodeFromString(
        SessionCategory.serializer(),
        """{"id":"$id","profile":"$profile","owner_id":"u1","created_at":"$at","updated_at":"$at","name":"$name","position":$position,
            "session_count":0,"color":"#3b82f6"}""",
    )

    private fun conversation(id: String, channel: String = "telegram", hidden: Boolean = false, last: String = at, peer: String? = "Sara") = json.decodeFromString(
        ChannelConversation.serializer(),
        """{"id":"$id","profile":"work","channel":"$channel","message_count":3,"started_at":"$at","last_message_at":"$last",
            "title":"Hermes title","peer_name":${peer?.let { "\"$it\"" } ?: "null"},"peer_id":"123","chat_type":"private",
            "last_message":{"role":"assistant","text":"Hi there"},"preview":"old preview","hidden":$hidden}""",
    )

    private fun agent(id: String, slug: String, status: String = "available", enabled: Boolean = true) = json.decodeFromString(
        Agent.serializer(),
        """{"id":"$id","profile":"work","owner_id":"u1","created_at":"$at","updated_at":"$at","slug":"$slug","name":"$slug","kind":"hermes",
           "vendor":null,"avatar":{"kind":"generated","url":null,"seed":"a"},"status":"$status","enabled":$enabled,
           "install":{"source":"managed","path":null,"package":null,"command":null,"version":null,"latest_version":null,
             "update_available":false,"pinned_version":null,"newer_than_tested":false,"auto_update":false,
             "auto_update_supported":false,"checked_at":null,"error":null},
           "runtime":{"state":"running","url":null,"error":null},"capabilities":["streaming"],"sections":[],"limited":false,"subagents":"none","default_model":null}""",
    )

    private fun peer(status: String, enabled: Boolean = true) = json.decodeFromString(
        Peer.serializer(),
        """{"id":"P1","name":"Office","hub_name":"Office hub","url":"https://office.example","direction":"inbound","status":"$status",
            "enabled":$enabled,"fingerprint":"ab:cd","asks_per_hour":20,"created_at":"$at"}""",
    )

    private fun release(id: String, published: String, size: Long = 5_051_877) = json.decodeFromString(
        Release.serializer(),
        """{"id":"$id","platform":"android","channel":"test","version":"1.1.$id","build":104,"notes":{"ar":"ع","en":"e"},"size_bytes":$size,
            "sha256":"abc","mandatory":false,"download_url":"https://x.example/a.apk","published_at":"$published"}""",
    )

    private val settingsJson = """{"default_channel":"stable","source":{"kind":"github_release","repo":"twuijri/core-hub","token":"[stored]"},"auto_publish":false}"""

    private fun preferences(link: String = "in_app", busy: String = "next", scale: String = "1.2", reasoning: Boolean = false) = json.decodeFromString(
        Preferences.serializer(),
        """{"theme":"system","locale":"ar","text_scale":$scale,"link_target":"$link","busy_input_mode":"$busy","streaming":true,"compact":true,
            "show_reasoning":$reasoning,"show_tool_calls":false,"show_cost":false,"inline_diffs":false,"sound_on_complete":false,
            "notify_on_complete":false,"notify_on_approval":true,
            "voice":{"input_mode":"device","dictation_language":"auto","output_mode":"device","auto_speak":false},"reasoning_effort":null}""",
    )

    private fun step(id: String, kind: String, lane: String, ms: Int?, text: String? = null, tool: String? = null, status: String = "succeeded") = json.decodeFromString(
        TrajectoryStep.serializer(),
        """{"id":"$id","kind":"$kind","lane":"$lane","exchange":1,"status":"$status","tool_call_only":false,
            "started_at":"2026-09-27T10:00:00Z","ended_at":"2026-09-27T10:00:0${(ms ?: 0) / 1000}Z","duration_ms":${ms ?: "null"},
            "text":${text?.let { "\"$it\"" } ?: "null"},
            "tool_call":${tool?.let { """{"id":"c$id","name":"$it","status":"succeeded","output_truncated":false,"preview":"search cats","output":"found them"}""" } ?: "null"}}""",
    )

    // ------------------------------------------------------------------ Updates

    @Test fun `updates - the source, repository and token are written as the hub takes them`() {
        val settings = json.decodeFromString(UpdateSettings.serializer(), settingsJson)
        assertTrue(UpdatesRules.fromSource(settings))
        assertTrue(UpdatesRules.tokenStored(settings))
        assertTrue(UpdatesRules.repoValid(" twuijri/core-hub "))
        assertFalse(UpdatesRules.repoValid("core-hub"))
        assertFalse(UpdatesRules.repoValid("https://github.com/a/b"))
        assertNull(UpdatesRules.token("   "))
        assertEquals("tok", UpdatesRules.token(" tok ")!!.source!!.token)
        assertEquals("twuijri/core-hub", UpdatesRules.repo(" twuijri/core-hub ").source!!.repo)
        assertEquals("5 MB", UpdatesRules.size(5_051_877))
        assertEquals("1 MB", UpdatesRules.size(10))
        assertEquals("0 MB", UpdatesRules.size(0))
        val ordered = UpdatesRules.ordered(listOf(release("1", "2026-09-20T10:00:00Z"), release("2", "2026-09-27T10:00:00Z")))
        assertEquals(listOf("2", "1"), ordered.map { it.id })
    }

    // ------------------------------------------------------------------ Linked hubs

    @Test fun `linked hubs - a link to ask for, a limit to save, and the page's own words for a refusal`() {
        assertNull(LinkedHubsRules.request("  ", "x"))
        val req = LinkedHubsRules.request(" https://other.example/peer-invite/abc ", "  ")!!
        assertEquals("https://other.example/peer-invite/abc", req.url.toString())
        assertNull(req.name)
        assertEquals("Home", LinkedHubsRules.request("https://a.example/i", " Home ")!!.name)
        assertEquals(30, LinkedHubsRules.limit("30", 20))
        assertNull(LinkedHubsRules.limit("20", 20))
        assertNull(LinkedHubsRules.limit("0", 20))
        assertNull(LinkedHubsRules.limit("1001", 20))
        assertTrue(LinkedHubsRules.awaitingMe(peer("pending")))
        assertFalse(LinkedHubsRules.usable(peer("pending")))
        assertFalse(LinkedHubsRules.usable(peer("linked", enabled = false)))
        assertTrue(LinkedHubsRules.usable(peer("linked")))
        assertEquals("peer_asks", LinkedHubsRules.reason(HubError(429, "rate_limited", null, reason = "rate_limited", peerCode = "peer_asks")))
        assertEquals("rate_limited", LinkedHubsRules.reason(HubError(429, "rate_limited", null)))
        assertEquals("invite_refused", LinkedHubsRules.reason(HubError(409, "conflict", null, reason = "invite_refused")))
        assertNull(LinkedHubsRules.reason(HubError(500, "internal", null, reason = "something_else")))
    }

    // ------------------------------------------------------------------ Chat groups

    @Test fun `chat groups - categories first in their order, then channels (Telegram first), then the rest`() {
        val cats = listOf(category("C2", position = 1), category("C1", position = 0))
        val sessions = listOf(
            session("S1", category = "C1"), session("S2", source = "channel", channel = "whatsapp"),
            session("S3"), session("S4", category = "gone"), session("S5", source = "channel", channel = "whatsapp", category = "C2"),
        )
        val groups = ChatGroupsRules.group(sessions, cats, listOf(conversation("T1", "telegram"), conversation("T2", "whatsapp")))
        assertEquals(listOf("category:C1", "category:C2", "channel:telegram", "channel:whatsapp", "rest"), groups.map { it.key })
        assertEquals(listOf("S1"), groups[0].items.map { it.id })
        assertEquals("a chosen category wins over the channel", listOf("S5"), groups[1].items.map { it.id })
        assertEquals(listOf("S2"), groups[3].items.map { it.id })
        assertEquals(listOf("T2"), (groups[3] as ChatGroup.Channel).conversations.map { it.id })
        assertEquals("an unknown category is no group", listOf("S3", "S4"), groups.last().items.map { it.id })
        val filtered = ChatGroupsRules.group(listOf(session("S3")), cats, keepEmpty = false)
        assertEquals(listOf("rest"), filtered.map { it.key })
    }

    @Test fun `chat groups - moving, the other party's name, hidden ones and the agent a continuation goes to`() {
        val s = session("S1", category = "C1")
        assertNull(ChatGroupsRules.move(s, "C1"))
        val out = ChatGroupsRules.move(s, null)!!
        assertTrue("out of a category is an explicit null", hub.core.client.model.SessionPatch.Clearable.CATEGORY_ID in out.sendNull)
        assertEquals("C2", ChatGroupsRules.move(s, "C2")!!.categoryId)
        assertEquals(listOf("C1"), ChatGroupsRules.movable(listOf(category("C1"), category("C9", profile = "home")), "work").map { it.id })
        assertEquals(1, ChatGroupsRules.lastPosition(listOf(category("C1"), category("C2", position = 1), category("C9", profile = "home")), category("C1")))

        val c = conversation("T1")
        assertEquals("Sara", ChatGroupsRules.title(c))
        assertEquals("Hermes title", ChatGroupsRules.title(conversation("T2", peer = null)))
        assertEquals("Agent: Hi there", ChatGroupsRules.preview(c, "Agent"))
        assertTrue(ChatGroupsRules.matches(c, "SAR"))
        assertFalse(ChatGroupsRules.matches(c, "nothing"))
        val list = listOf(conversation("T1"), conversation("T2", hidden = true))
        assertEquals(listOf("T1"), ChatGroupsRules.visible(list, false, ArchiveFilter.ACTIVE, "").map { it.id })
        assertEquals(listOf("T1", "T2"), ChatGroupsRules.visible(list, true, ArchiveFilter.ACTIVE, "").map { it.id })
        assertTrue("the archive has no channel conversations", ChatGroupsRules.visible(list, true, ArchiveFilter.ARCHIVED, "").isEmpty())
        assertEquals(1, ChatGroupsRules.hiddenCount(list, ArchiveFilter.ACTIVE))
        assertEquals(0, ChatGroupsRules.hiddenCount(list, ArchiveFilter.ARCHIVED))
        assertNull(ChatGroupsRules.name("  "))
        assertNull(ChatGroupsRules.name("x".repeat(61)))
        assertEquals("Clients", ChatGroupsRules.name(" Clients "))
        val agents = listOf(agent("A1", "claude"), agent("A2", "hermes"), agent("A3", "off", enabled = false))
        assertEquals("A2", ChatGroupsRules.continueAgent(agents)!!.id)
        assertEquals("A1", ChatGroupsRules.continueAgent(listOf(agent("A1", "claude")))!!.id)
        assertNull(ChatGroupsRules.continueAgent(listOf(agent("A3", "off", enabled = false))))
    }

    @Test fun `a channel transcript keeps older pages before the latest, without repeats`() {
        fun m(id: String) = json.decodeFromString(ChannelMessage.serializer(), """{"id":"$id","role":"user","text":"t","created_at":"$at"}""")
        assertEquals(listOf("a", "b", "c", "d"), ChannelTranscript.merge(listOf(m("a"), m("b"), m("c")), listOf(m("c"), m("d"))).map { it.id })
    }

    // ------------------------------------------------------------------ links and focus

    @Test fun `a link to this hub's own page opens in the app, a room invite opens Join, anything else the browser`() {
        val hub = "https://hub.example"
        assertEquals("/chat/S1?profile=work", HubLinks.pathOf("https://hub.example/chat/S1?profile=work", hub))
        assertEquals("/tasks", HubLinks.pathOf("/tasks", hub))
        assertNull(HubLinks.pathOf("https://evil.example/chat/S1", hub))
        assertNull(HubLinks.pathOf("http://hub.example/chat/S1", hub))
        assertNull(HubLinks.pathOf("https://hub.example:8443/chat/S1", hub))
        assertEquals("/chat/S1", HubLinks.pathOf("http://192.168.1.10:8787/chat/S1", "http://192.168.1.10:8787"))
        assertEquals(InAppLink.Page(Route.Chat("S1", "work")), HubLinks.target("https://hub.example/chat/S1?profile=work", hub, "home"))
        assertEquals(InAppLink.Page(Route.ChannelChat("T1", "home")), HubLinks.target("https://hub.example/chat/T1?source=channel", hub, "home"))
        assertEquals(InAppLink.Page(Route.Tasks), HubLinks.target("https://hub.example/tasks", hub, "home"))
        assertEquals(InAppLink.Join("AB12CD34"), HubLinks.target("https://hub.example/join/ab12cd34", hub, "home"))
        assertNull(HubLinks.target("https://hub.example/join/x", hub, "home"))
        assertNull("a page with no screen goes to the browser", HubLinks.target("https://hub.example/nowhere/at/all", hub, "home"))
    }

    @Test fun `Open leads to the task, the schedule or the workflow run itself, once`() {
        assertEquals(FocusItem(FocusItem.Kind.TASK, "K1", "work"), Focus.of(ResourceRef(ResourceRef.Kind.TASK, "K1"), "work"))
        assertEquals(FocusItem.Kind.WORKFLOW_RUN, Focus.of(ResourceRef(ResourceRef.Kind.WORKFLOW_RUN, "R1"), "work")!!.kind)
        assertNull(Focus.of(ResourceRef(ResourceRef.Kind.AGENT, "A1"), "work"))
        assertNull(Focus.of(ResourceRef(ResourceRef.Kind.TASK, "K1"), null))
        Focus.item.value = FocusItem(FocusItem.Kind.TASK, "K1", "work")
        assertNull("another page does not take it", Focus.take(FocusItem.Kind.SCHEDULE))
        assertEquals("K1", Focus.take(FocusItem.Kind.TASK)!!.id)
        assertNull("it opens once", Focus.take(FocusItem.Kind.TASK))
    }

    // ------------------------------------------------------------------ display preferences

    @Test fun `the display preferences are applied - send while busy, text size, what a reply shows, where links open`() {
        assertEquals(RunCreate.When.QUEUE, HubDisplay.busyWhen(null))
        assertEquals(RunCreate.When.NEXT, HubDisplay.busyWhen(preferences(busy = "next")))
        assertEquals(RunCreate.When.INTERRUPT, HubDisplay.busyWhen(preferences(busy = "interrupt")))
        assertEquals(1f, HubDisplay.textScale(null))
        assertEquals(1.2f, HubDisplay.textScale(preferences(scale = "1.2")), 0.001f)
        val chat = HubDisplay.chat(preferences(reasoning = false))
        assertFalse(chat.showReasoning)
        assertFalse(chat.showToolCalls)
        assertTrue(chat.compact)
        assertTrue(HubDisplay.chat(null).showReasoning)
        assertTrue(HubDisplay.linksInApp(null))
        assertTrue(HubDisplay.linksInApp(preferences(link = "in_app")))
        assertFalse(HubDisplay.linksInApp(preferences(link = "browser")))
    }

    // ------------------------------------------------------------------ trajectory

    @Test fun `trajectory - turns and calls filter, duration orders, search reads the tool, durations read as people do`() {
        val steps = listOf(
            step("s1", "input", "input", null, text = "Find cats"),
            step("s2", "turn", "model", 1200, text = "Looking"),
            step("s3", "tool", "tools", 4000, tool = "web_search"),
            step("s4", "reasoning", "model", 300, text = "hmm"),
        )
        assertEquals(4, TrajectoryRules.filter(steps, StepFilter(), 0).size)
        assertEquals(listOf("s2", "s4"), TrajectoryRules.filter(steps, StepFilter(turns = true), 0).map { it.id })
        assertEquals(listOf("s3"), TrajectoryRules.filter(steps, StepFilter(calls = true), 0).map { it.id })
        assertEquals(listOf("s3", "s2", "s4", "s1"), TrajectoryRules.filter(steps, StepFilter(byDuration = true), 0).map { it.id })
        assertEquals(listOf("s3"), TrajectoryRules.filter(steps, StepFilter(query = "FOUND"), 0).map { it.id })
        assertEquals("search cats", TrajectoryRules.summary(steps[2]))
        val units = Triple("ms", "s", "min")
        assertEquals("850 ms", TrajectoryRules.format(850, units))
        assertEquals("4.2 s", TrajectoryRules.format(4_200, units))
        assertEquals("3 min 12 s", TrajectoryRules.format(192_000, units))
        assertEquals("2 min", TrajectoryRules.format(120_000, units))
        assertNotNull(TrajectoryRules.span(steps[1], 0))
    }

    // ------------------------------------------------------------------ WhatsApp by QR

    private fun loginJob(status: String, result: String) = json.decodeFromString(
        Job.serializer(),
        """{"id":"J1","profile":"work","owner_id":"u1","created_at":"$at","updated_at":"$at","kind":"channel_login","status":"$status",
            "progress":{"percent":null,"message":"Scan the code"},"result":$result}""",
    )

    @Test fun `pairing by QR - the code and when it ends, then who was linked and how it applies`() {
        val waiting = ChannelPairRules.state(loginJob("running", """{"status":"waiting","qr":"2@abc","expires_at":"2026-09-27T10:10:00Z"}"""))
        assertEquals("2@abc", waiting.qr)
        assertNotNull(waiting.expiresAt)
        assertFalse(ChannelPairRules.finished(loginJob("running", "null")))
        val done = loginJob("succeeded", """{"status":"connected","account_name":"Sara","account_phone":"+966","mode":"self-chat","applies":"now"}""")
        assertTrue(ChannelPairRules.finished(done))
        val state = ChannelPairRules.state(done)
        assertEquals("Sara · +966", ChannelPairRules.account(state))
        assertEquals("done_as_self_now", ChannelPairRules.doneKey(state, ChannelLoginRequest.Mode.SELF_MINUS_CHAT))
        assertEquals("done_now", ChannelPairRules.doneKey(ChannelPairRules.state(loginJob("succeeded", """{"applies":"now","mode":"bot"}""")), ChannelLoginRequest.Mode.BOT))
        assertEquals("done", ChannelPairRules.doneKey(ChannelPairRules.state(loginJob("succeeded", "{}")), ChannelLoginRequest.Mode.BOT))
    }

    // ------------------------------------------------------------------ workflows

    @Test fun `workflows - steps are added after the last, connected once per route, removed with their connections`() {
        var d = WorkflowDraft(name = " Daily ")
        d = WorkflowDraftRules.add(d, WorkflowNode.Kind.AGENT, "Research", "A1")
        d = WorkflowDraftRules.add(d, WorkflowNode.Kind.CONDITION, "Found?")
        d = WorkflowDraftRules.add(d, WorkflowNode.Kind.NOTIFY, "Tell me")
        assertEquals(listOf("agent_1", "condition_1", "notify_1"), d.nodes.map { it.id })
        assertEquals("A1", d.nodes[0].agentId)
        assertNull("only an agent step names an agent", d.nodes[1].agentId)
        assertEquals("input exists", d.nodes[1].input)
        assertTrue(d.nodes[1].position.x > d.nodes[0].position.x)
        d = WorkflowDraftRules.connect(d, "agent_1", "condition_1", WorkflowEdge.Route.SUCCESS)
        d = WorkflowDraftRules.connect(d, "agent_1", "condition_1", WorkflowEdge.Route.SUCCESS)
        d = WorkflowDraftRules.connect(d, "condition_1", "notify_1", WorkflowEdge.Route.FAILURE)
        d = WorkflowDraftRules.connect(d, "notify_1", "notify_1", WorkflowEdge.Route.ALWAYS)
        assertEquals(listOf("e1", "e2"), d.edges.map { it.id })
        assertEquals(listOf("agent_1", "condition_1"), WorkflowDraftRules.upstreamOf(d, "notify_1").map { it.id })
        val write = WorkflowDraftRules.toWrite(d, editing = true)
        assertEquals("Daily", write.name)
        assertTrue(hub.core.client.model.WorkflowWrite.Clearable.DESCRIPTION in write.sendNull)
        assertTrue(WorkflowDraftRules.canSave(d, null))
        assertFalse(WorkflowDraftRules.canSave(d.copy(name = " "), null))
        d = WorkflowDraftRules.remove(d, "condition_1")
        assertEquals(listOf("agent_1", "notify_1"), d.nodes.map { it.id })
        assertTrue("its connections go with it", d.edges.isEmpty())
        assertEquals("agent_2", WorkflowDraftRules.nextNodeId(WorkflowNode.Kind.AGENT, d.nodes))
    }

    @Test fun `workflows - a condition splits into its parts and back, a wait is 0 to 3600 seconds`() {
        assertEquals(Triple("input", "exists", ""), WorkflowDraftRules.splitCondition("input exists"))
        assertEquals(Triple("steps.a.output", "contains", "done it"), WorkflowDraftRules.splitCondition("steps.a.output contains \"done it\""))
        assertEquals(Triple("trigger.count", ">=", "3"), WorkflowDraftRules.splitCondition("trigger.count >= 3"))
        assertNull(WorkflowDraftRules.splitCondition("a and b"))
        assertEquals("input == \"yes\"", WorkflowDraftRules.joinCondition(" input ", "==", "yes"))
        assertEquals("n > 5", WorkflowDraftRules.joinCondition("n", ">", "5"))
        assertEquals("input empty", WorkflowDraftRules.joinCondition("input", "empty", "ignored"))
        assertEquals(90, WorkflowDraftRules.delaySeconds("90", minutes = false))
        assertEquals(600, WorkflowDraftRules.delaySeconds("10", minutes = true))
        assertNull(WorkflowDraftRules.delaySeconds("61", minutes = true))
        assertNull(WorkflowDraftRules.delaySeconds("x", minutes = false))
    }

    // ------------------------------------------------------------------ runtime, probe, worktree

    @Test fun `runtime checks - failures first, a pending restart is amber, all passing is one line`() {
        val report = json.decodeFromString(
            RuntimeReport.serializer(),
            """{"agent":"hermes","mode":"managed","ready":false,"checks":[{"id":"gateway_reloaded","ok":false},{"id":"runtime_writable","ok":true},{"id":"model_selected","ok":true}]}""",
        )
        assertEquals(listOf(RuntimeCheck.Id.GATEWAY_RELOADED, RuntimeCheck.Id.RUNTIME_WRITABLE, RuntimeCheck.Id.MODEL_SELECTED), RuntimeRules.failingFirst(report.checks).map { it.id })
        assertTrue(RuntimeRules.onlyRestart(report))
        assertTrue(RuntimeRules.needsRestart(report))
        assertFalse(RuntimeRules.allOk(report))
        assertEquals(2, RuntimeRules.passed(report))
    }

    @Test fun `fetch in add provider - the preset, the address and a typed key, and image-only models are not chat defaults`() {
        assertNull(ProbeRules.request(null, "  ", "", ProviderKind.LLM))
        val r = ProbeRules.request("openrouter", " https://llm.example/v1 ", " ", ProviderKind.LLM)!!
        assertEquals("openrouter", r.preset)
        assertEquals("https://llm.example/v1", r.baseUrl)
        assertNull(r.apiKey)
        val result = json.decodeFromString(
            ProviderProbeResult.serializer(),
            """{"ok":true,"duration_ms":120,"models":[{"id":"gpt-x","label":"GPT X"},{"id":"img","label":"Img","image_only":true}]}""",
        )
        assertEquals(listOf("gpt-x"), ProbeRules.chatModels(result))
        assertTrue(ProbeRules.chatModels(json.decodeFromString(ProviderProbeResult.serializer(), """{"ok":false,"duration_ms":1,"models":[],"message":"401"}""")).isEmpty())
    }

    @Test fun `a task's worktree - counts while ready or dirty, Remove not while the task runs in it`() {
        fun wt(status: String) = json.decodeFromString(
            Worktree.serializer(),
            """{"path":"/w/t","branch":"task/1","base_branch":"main","status":"$status","ahead":2,"behind":0,"changed_files":3,"updated_at":"$at"}""",
        )
        assertTrue(WorktreeRules.showsCounts(wt("dirty")))
        assertFalse(WorktreeRules.showsCounts(wt("merged")))
        assertTrue(WorktreeRules.removable(wt("ready"), running = false))
        assertFalse(WorktreeRules.removable(wt("ready"), running = true))
        assertFalse(WorktreeRules.removable(wt("removed"), running = false))
    }

    // ------------------------------------------------------------------ terminal

    @Test fun `terminal - a prompt, a command and its output, carriage returns and erasing`() {
        val screen = TerminalScreen(40, 5)
        screen.feed("user@hub:~$ ls\r\nfile-a  file-b\r\nuser@hub:~$ ")
        assertEquals(listOf("user@hub:~$ ls", "file-a  file-b", "user@hub:~$"), screen.text())
        assertEquals(2 to 12, screen.cursor())
        screen.feed("typo\b\b\b\b\u001b[K")
        assertEquals("user@hub:~$", screen.text().last())
        screen.feed("\rprogress 10%\rprogress 99%")
        assertEquals("progress 99%", screen.text().last())
        screen.feed("\u001b[2J\u001b[H\u001b[1;31mred\u001b[0m")
        assertEquals("colours are dropped, the screen cleared", "red", screen.text().first { it.isNotEmpty() })
        screen.feed("\u001b]0;title\u0007 ok")
        assertTrue(screen.text().any { it.startsWith("red ok") })
    }

    @Test fun `terminal - long lines wrap, the screen scrolls into the scrollback, cursor moves land where asked`() {
        val screen = TerminalScreen(10, 4, scrollback = 3)
        screen.feed("0123456789AB")
        assertEquals(listOf("0123456789", "AB"), screen.text())
        repeat(10) { screen.feed("\r\nline $it") }
        val text = screen.text()
        assertEquals("line 9", text.last())
        assertTrue("the scrollback is bounded", text.size <= 3 + 4)
        screen.feed("\u001b[2;3HX")
        assertEquals((screen.text().size - 4 + 1) to 3, screen.cursor().let { (r, c) -> r to c })
        assertEquals(TerminalKeys.line("ls"), "ls\r")
    }

    @Test fun `terminal - acks and events are read from the socket's JSON`() {
        val ack = TerminalAck.parse(JSONObject("""{"ok":true,"session":{"id":"01J8QK3ZR2W7M5N4P6T8V9X0TM","profile":"default","cwd":"/data","cols":80,"rows":24,
            "attached":true,"started_at":"$at","last_active_at":"$at"},"backlog":"hello"}"""))
        assertTrue(ack.ok)
        assertEquals("01J8QK3ZR2W7M5N4P6T8V9X0TM", ack.session!!.id)
        assertEquals("hello", ack.backlog)
        val refused = TerminalAck.parse(JSONObject("""{"ok":false,"error":"too many","code":"conflict","details":{"reason":"terminal_limit"}}"""))
        assertFalse(refused.ok)
        assertEquals("terminal_limit", refused.reason)
        assertFalse(TerminalAck.parse(null).ok)
        val out = TerminalEvent.parse("terminal.output", JSONObject("""{"event":"terminal.output","payload":{"terminal_id":"T1","data":"ls\r\n"}}"""))
        assertEquals(TerminalEvent.Output("T1", "ls\r\n"), out)
        assertEquals(TerminalEvent.Exited("T1", "idle"), TerminalEvent.parse("terminal.exited", JSONObject("""{"payload":{"terminal_id":"T1","reason":"idle","exit_code":null}}""")))
        assertNull(TerminalEvent.parse("terminal.output", JSONObject("""{"payload":{}}""")))
    }

    // ------------------------------------------------------------------ the calls

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private fun ok(body: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/updates/settings") -> ok(settingsJson)
                    path.endsWith("/updates/releases") -> ok("""{"items":[],"next_cursor":null}""")
                    path.contains("/updates/releases/") -> MockResponse().setResponseCode(204)
                    path.endsWith("/peers") && request.method == "POST" -> ok(peerJson, 201)
                    path.endsWith("/peers/P1") && request.method == "PATCH" -> ok(peerJson)
                    path.endsWith("/peer-shares") && request.method == "PUT" ->
                        ok("""{"profile":"work","agent_id":"A1","name":"Hermes","shared":true}""")
                    path.endsWith("/ask") -> ok("""{"answer":"Forty-two"}""")
                    path.endsWith("/channel-conversations") -> ok("""{"items":[],"unavailable":[{"reason":"hermes_unreachable"}],"has_more":false}""")
                    path.endsWith("/continue") -> ok("""{"session":${sessionJson()},"first_message":[{"type":"text","text":"Here is the chat"}]}""", 201)
                    path.contains("/sessions/") && request.method == "PATCH" -> ok(sessionJson())
                    path.endsWith("/workflows/validate") -> ok("""{"valid":false,"problems":[{"code":"agent_missing","message":"no agent","node_id":"agent_1"}],"warnings":[]}""")
                    path.endsWith("/rerun") -> ok("""{"job_id":"J2","workflow_run_id":"R2"}""", 202)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private val base get() = server.url("/").toString().trimEnd('/')
    private val peerJson = """{"id":"P1","name":"Office","hub_name":"Office hub","url":"https://office.example","direction":"outbound","status":"waiting",
        "enabled":true,"fingerprint":"ab:cd","asks_per_hour":20,"created_at":"$at"}"""

    private fun sessionJson() = json.encodeToString(Session.serializer(), session("01J8QK3ZR2W7M5N4P6T8V9X0S9", category = null))
    private fun body(request: RecordedRequest) = json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test fun `updates - reads the settings and the shelf, writes a change, deletes a build`() = runTest {
        val ops = UpdatesOps { UpdatesApi(hub.core.android.data.apiBase(base), OkHttpClient()) }
        assertEquals("twuijri/core-hub", ops.settings().getOrThrow().source.repo)
        ops.save(UpdatesRules.repo("a/b")).getOrThrow()
        val sent = body(requests[1])
        assertEquals("github_release", sent["source"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("a/b", sent["source"]!!.jsonObject["repo"]!!.jsonPrimitive.content)
        assertEquals("PUT", requests[1].method)
        ops.releases().getOrThrow()
        assertEquals("50", requests[2].requestUrl!!.queryParameter("limit"))
        ops.delete("R1").getOrThrow()
        assertEquals("DELETE", requests[3].method)
        assertTrue(requests[3].requestUrl!!.encodedPath.endsWith("/updates/releases/R1"))
    }

    @Test fun `linked hubs - asks to link, approves, shares an agent and asks it a question`() = runTest {
        val ops = LinkedHubsOps { DevicesApi(hub.core.android.data.apiBase(base), OkHttpClient()) }
        ops.request(LinkedHubsRules.request("https://other.example/peer-invite/abc", "")!!).getOrThrow()
        val asked = body(requests[0])
        assertEquals("https://other.example/peer-invite/abc", asked["url"]!!.jsonPrimitive.content)
        assertFalse("an empty name is left out", "name" in asked)
        ops.update("P1", PeerPatch(approve = true)).getOrThrow()
        assertEquals("true", body(requests[1])["approve"]!!.jsonPrimitive.content)
        val share = json.decodeFromString(hub.core.client.model.PeerShare.serializer(), """{"profile":"work","agent_id":"A1","name":"Hermes","shared":false}""")
        assertTrue(ops.share(share, true).getOrThrow().shared)
        assertEquals("A1", body(requests[2])["agent_id"]!!.jsonPrimitive.content)
        assertEquals("Forty-two", ops.ask("P1", "S1", "  What?  ").getOrThrow())
        assertEquals("What?", body(requests[3])["prompt"]!!.jsonPrimitive.content)
    }

    @Test fun `chat groups - conversations include hidden ones for every profile, a move out sends null, a continuation carries its note`() = runTest {
        val ops = ChatGroupsOps { HubApis(base, OkHttpClient()) }
        val page = ops.conversations("work", all = true, limit = 200).getOrThrow()
        assertTrue(ChatGroupsRules.unreachable(page.unavailable))
        val url = requests[0].requestUrl!!
        assertEquals("include", url.queryParameter("hidden"))
        assertEquals("all", url.queryParameter("profiles"))
        assertEquals("200", url.queryParameter("limit"))
        assertEquals("work", requests[0].getHeader("X-Hub-Profile"))
        ops.move(session("S1", category = "C1"), null)!!.getOrThrow()
        assertEquals(JsonNull, body(requests[1])["category_id"])
        val made = ops.continueIn("home", "T1", "A2", "  ").getOrThrow()
        assertEquals("Here is the chat", made.firstMessage.single().text)
        val sent = body(requests[2])
        assertEquals("A2", sent["agent_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, sent["note"])
        assertEquals("home", requests[2].getHeader("X-Hub-Profile"))
    }

    @Test fun `workflows - a condition's rules are shown and kept when the phone saves`() {
        val json = hub.core.client.infrastructure.Serializer.kotlinxSerializationJson
        val rules = json.decodeFromString(
            hub.core.client.model.WorkflowRules.serializer(),
            """{"match":"any","items":[{"path":"trigger.event","operator":"==","value":"taskCreated"},{"path":"trigger.task_id","operator":"exists","value":null}]}""",
        )
        val (match, lines) = WorkflowDraftRules.ruleLines(rules)
        assertEquals("any", match)
        assertEquals(listOf("trigger.event == \"taskCreated\"", "trigger.task_id exists"), lines)
        assertTrue(WorkflowDraftRules.ruleLines(null).second.isEmpty())
        var d = WorkflowDraftRules.add(WorkflowDraft(name = "Filter"), WorkflowNode.Kind.CONDITION, "Only new")
        d = WorkflowDraftRules.update(d, "condition_1") { it.copy(rules = rules) }
        assertEquals(rules, WorkflowDraftRules.toWrite(d).nodes!![0].rules)
        assertEquals(rules, WorkflowDraftRules.toCheck(d).nodes!![0].rules)
        assertEquals("https://hub.example/hooks/T1", WorkflowDraftRules.triggerUrl("https://hub.example/", "/hooks/T1"))
    }

    @Test fun `workflows - the drawing is checked by the hub, and a run starts again from a step`() = runTest {
        val ops = WorkflowEditOps { HubApis(base, OkHttpClient()) }
        val d = WorkflowDraftRules.add(WorkflowDraft(name = "W"), WorkflowNode.Kind.AGENT, "Research")
        val v = ops.validate("work", WorkflowDraftRules.toCheck(d)).getOrThrow()
        assertEquals("agent_missing", v.problems.single().code)
        assertFalse(WorkflowDraftRules.canSave(d, v))
        val checked = body(requests[0])
        assertEquals("agent_1", checked["nodes"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["id"]!!.jsonPrimitive.content })
        // The check leaves the name out: it never reads it, and an older hub refused an empty one.
        assertFalse("name" in checked)
        assertNull(WorkflowDraftRules.toCheck(d.copy(name = "")).name)
        assertEquals("R2", ops.rerun("work", "R1", "agent_1").getOrThrow().workflowRunId)
        assertEquals("agent_1", body(requests[1])["from_node_id"]!!.jsonPrimitive.content)
    }
}

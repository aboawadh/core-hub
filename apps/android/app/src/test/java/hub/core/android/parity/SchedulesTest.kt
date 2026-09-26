package hub.core.android.parity

import hub.core.android.R
import hub.core.android.data.HubApis
import hub.core.android.data.HubError
import hub.core.android.ui.components.TriggerRules
import hub.core.android.ui.screens.ScheduleOps
import hub.core.android.ui.screens.ScheduleProblem
import hub.core.android.ui.screens.ScheduleRules
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Agent
import hub.core.client.model.JobStatus
import hub.core.client.model.Schedule
import hub.core.client.model.ScheduleOverlap
import hub.core.client.model.ScheduleRun
import hub.core.client.model.ScheduleTarget
import hub.core.client.model.ScheduleTrigger
import hub.core.client.model.ScheduleWrite
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Schedules on the phone (batch 3): what the form sends for a new schedule and for an edit, the
 * web's "Common schedules", who decides the run options, Hermes's refusals and the zone it asks
 * for, a run's time — and the calls against a scripted hub, whose trigger and target carry the
 * `null` fields the contract requires. iOS's SchedulesTests is the twin.
 */
class SchedulesTest {
    private val json = Serializer.kotlinxSerializationJson
    private val id = "01J8QK3ZR2W7M5N4P6T8V9X0SC"
    private val hermesId = "01J8QK3ZR2W7M5N4P6T8V9X0AH"
    private val claudeId = "01J8QK3ZR2W7M5N4P6T8V9X0AC"

    private fun agent(id: String, slug: String): Agent = json.decodeFromString(
        Agent.serializer(),
        """{"id":"$id","profile":"work","owner_id":"u1","created_at":"2026-09-20T09:00:00Z","updated_at":"2026-09-20T09:00:00Z",
        "slug":"$slug","name":"${slug.replaceFirstChar { it.uppercase() }}","vendor":null,"kind":"hermes","avatar":{"kind":"generated","seed":"a"},
        "status":"available","enabled":true,"install":{"source":"managed","update_available":false,"newer_than_tested":false,
        "auto_update":false,"auto_update_supported":false},"runtime":{"state":"running"},"capabilities":[],"sections":[],
        "default_model":null,"limited":false,"subagents":"none"}""",
    )

    private fun scheduleJson(external: Boolean = false) = """{"id":"$id","profile":"work","owner_id":"u1",
        "created_at":"2026-09-20T09:00:00Z","updated_at":"2026-09-21T10:00:00Z","name":"Daily report",
        "trigger":{"kind":"cron","expression":"0 9 * * *","every_minutes":null,"run_at":null,"timezone":"Asia/Riyadh","display":"Every day at 09:00"},
        "target":{"kind":"agent_prompt","agent_id":"$claudeId","prompt":"Summarise the day","model":null,"provider":null,"skills":[],"workflow_id":null,"input":null},
        "delivery":{"kind":"none","room_id":null,"channel":null,"address":null},"repeat":{"limit":null,"completed":3},"enabled":true,
        "state":"scheduled","next_run_at":"2026-09-22T06:00:00Z","last_run_at":null,"last_status":null,"last_error":null,
        "last_delivery_error":null,"run_if_missed":${if (external) "null" else "false"},"overlap":${if (external) "null" else "\"wait\""},
        "external":${if (external) """{"source":"hermes","id":"job1"}""" else "null"}}"""

    private fun schedule(external: Boolean = false): Schedule = json.decodeFromString(Schedule.serializer(), scheduleJson(external))

    private fun run(status: String, waiting: Boolean = false, finished: String? = "2026-09-21T06:02:05Z") = json.decodeFromString(
        ScheduleRun.serializer(),
        """{"id":"R$status","schedule_id":"$id","job_id":"J1","run_id":null,"session_id":"01J8QK3ZR2W7M5N4P6T8V9X0YF","workflow_run_id":null,
        "status":"$status","trigger":"schedule","waiting":$waiting,"output_preview":"Done.","output":null,"output_size_bytes":5,"error":null,
        "delivery_status":"none","delivery_error":null,"started_at":"2026-09-21T06:00:00Z","finished_at":${finished?.let { "\"$it\"" } ?: "null"}}""",
    )

    // ------------------------------------------------------------------ the rules

    @Test fun `a new schedule asks Hermes first and leaves its run options to it`() {
        val agents = listOf(agent(claudeId, "claude-code"), agent(hermesId, "hermes"))
        var draft = ScheduleRules.newDraft(agents, zone = "Asia/Riyadh")
        assertEquals(hermesId, draft.agentId)
        assertTrue(ScheduleRules.isHermes(draft.agentId, agents))
        assertEquals(ScheduleProblem.Name, ScheduleRules.problem(draft, editing = false))
        draft = draft.copy(name = "  Morning brief ", prompt = "  What changed overnight? ")
        val hermes = ScheduleRules.create(draft, hermes = true)!!
        assertEquals("Morning brief", hermes.name)
        assertEquals("0 9 * * *", hermes.trigger?.expression)
        assertEquals("Asia/Riyadh", hermes.trigger?.timezone)
        assertEquals(ScheduleTarget.Kind.AGENT_PROMPT, hermes.target?.kind)
        assertEquals(hermesId, hermes.target?.agentId)
        assertEquals("What changed overnight?", hermes.target?.prompt)
        assertNull("Hermes refuses run options in the body", hermes.runIfMissed)
        assertNull(hermes.overlap)
        // Any other agent: the hub fires it and keeps the two options, off and «wait» by default.
        val hub = ScheduleRules.create(draft.copy(agentId = claudeId), hermes = false)!!
        assertEquals(false, hub.runIfMissed)
        assertEquals(ScheduleOverlap.WAIT, hub.overlap)
        // A time the hub could not read is refused before it is sent.
        val bad = draft.copy(trigger = draft.trigger.copy(cron = "0 9 *"))
        assertEquals(ScheduleProblem.Trigger, ScheduleRules.problem(bad, editing = false))
        assertNull(ScheduleRules.create(bad, hermes = false))
        assertEquals(ScheduleProblem.Agent, ScheduleRules.problem(draft.copy(agentId = null), editing = false))
    }

    @Test fun `the common schedules fill the kind and its value`() {
        assertEquals(listOf("every_hour", "daily_8", "weekdays_9", "monday_9", "monthly_1", "every_15"), ScheduleRules.templates.map { it.id })
        val start = TriggerRules.draft(null, "UTC")
        val monthly = ScheduleRules.apply(ScheduleRules.templates[4], start)
        assertEquals("0 9 1 * *", TriggerRules.build(monthly)?.expression)
        val every = ScheduleRules.apply(ScheduleRules.templates[5], start)
        assertEquals(ScheduleTrigger.Kind.INTERVAL, every.kind)
        assertEquals(15, TriggerRules.build(every)?.everyMinutes)
    }

    @Test fun `an edit sends only what changed`() {
        val saved = schedule()
        var draft = ScheduleRules.draft(saved)
        assertEquals("Asia/Riyadh", draft.trigger.timezone)
        assertNull("nothing changed", ScheduleRules.update(saved, draft))
        assertNull("spaces are not a change", ScheduleRules.update(saved, draft.copy(name = "Daily report ")))
        draft = draft.copy(prompt = "Summarise the week", overlap = ScheduleOverlap.SKIP)
        val write = ScheduleRules.update(saved, draft)!!
        assertNull(write.name)
        assertNull(write.trigger)
        assertEquals("Summarise the week", write.target?.prompt)
        assertEquals("the agent stays", claudeId, write.target?.agentId)
        assertEquals(ScheduleOverlap.SKIP, write.overlap)
        assertNull(write.runIfMissed)
        val every = ScheduleRules.draft(saved).let { it.copy(trigger = ScheduleRules.apply(ScheduleRules.templates[5], it.trigger)) }
        assertEquals(15, ScheduleRules.update(saved, every)?.trigger?.everyMinutes)
    }

    @Test fun `a Hermes job keeps no run options of its own`() {
        val hermes = schedule(external = true)
        assertFalse(ScheduleRules.hasRunOptions(hermes))
        assertTrue(ScheduleRules.hasRunOptions(schedule()))
        assertTrue(ScheduleRules.fromHermes(hermes))
        val draft = ScheduleRules.draft(hermes).copy(overlap = ScheduleOverlap.PARALLEL, runIfMissed = true)
        assertNull("never sent for Hermes, which refuses them", ScheduleRules.update(hermes, draft))
        assertEquals(R.string.sched_hermes_fired, ScheduleRules.fired(hermes).res)
        assertEquals(listOf("Daily report"), ScheduleRules.fired(schedule()).args)
    }

    @Test fun `Hermes refusals are said in words and the zone it asks for is offered`() {
        val body = """{"error":"Conflict","code":"conflict","details":{"reason":"hermes_timezone","field":"trigger.timezone","timezone":"UTC"}}"""
        val error = HubError(409, "conflict", "Conflict", HubError.reasonOf(body), timezone = HubError.timezoneOf(body))
        assertEquals("UTC", error.timezone)
        assertEquals("UTC", ScheduleRules.askedZone(error))
        assertEquals(R.string.sched_hermes_timezone, ScheduleRules.refusal(error)?.res)
        assertEquals(listOf("UTC"), ScheduleRules.refusal(error)?.args)
        assertEquals("UTC", ScheduleRules.withZone(ScheduleRules.newDraft(emptyList(), "Asia/Riyadh"), "UTC").trigger.timezone)
        val prompt = error.copy(reason = "hermes_prompt_required")
        assertNull(ScheduleRules.askedZone(prompt))
        assertEquals(R.string.sched_hermes_prompt_required, ScheduleRules.refusal(prompt)?.res)
        assertNull("the hub's own words otherwise", ScheduleRules.refusal(HubError(400, "validation_failed", "Bad")))
    }

    @Test fun `a run says how long it took in Latin digits`() {
        val done = run("succeeded")
        assertEquals(125L, ScheduleRules.seconds(done))
        assertEquals(listOf("41"), ScheduleRules.duration(41).args)
        assertEquals(R.string.sched_dur_m, ScheduleRules.duration(125).res)
        assertEquals(listOf("2", "5"), ScheduleRules.duration(125).args)
        assertEquals(listOf("1", "3"), ScheduleRules.duration(3_780).args)
        val going = run("running", finished = null)
        assertNull(ScheduleRules.seconds(going))
        assertTrue(ScheduleRules.live(listOf(done, going)))
        assertFalse(ScheduleRules.live(listOf(done)))
        assertEquals(JobStatus.QUEUED, run("queued", waiting = true, finished = null).status)
    }

    // ------------------------------------------------------------------ the calls, against a scripted hub

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private val bodies = mutableListOf<String>()

    private fun ok(body: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                bodies += request.body.readUtf8()
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/schedules/preview") -> ok("""{"timezone":"UTC","next_runs":["2026-09-22T09:00:00Z","2026-09-23T09:00:00Z"]}""")
                    path.endsWith("/schedules") && request.method == "POST" ->
                        if (bodies.last().contains("Asia/Riyadh")) ok("""{"error":"Conflict","code":"conflict","details":{"reason":"hermes_timezone","timezone":"UTC"}}""", 409)
                        else ok(scheduleJson(), 201)
                    path.endsWith("/schedules") -> ok("""{"items":[${scheduleJson()}],"next_cursor":"c2"}""")
                    path.endsWith("/schedules/$id/runs") ->
                        if (request.requestUrl!!.queryParameter("cursor") == null) ok("""{"items":[${json.encodeToString(ScheduleRun.serializer(), run("succeeded"))}],"next_cursor":"r2"}""")
                        else ok("""{"items":[],"next_cursor":null}""")
                    path.endsWith("/schedules/$id/run") ->
                        ok("""{"job_id":"J1","schedule_run_id":"R1","session_id":"01J8QK3ZR2W7M5N4P6T8V9X0YF","run_id":null,"workflow_run_id":null}""", 202)
                    path.endsWith("/schedules/$id") && request.method == "PATCH" -> ok(scheduleJson())
                    path.endsWith("/schedules/$id") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun ops() = ScheduleOps { HubApis(server.url("/").toString().trimEnd('/'), OkHttpClient()) }

    @Test fun `a new schedule goes to the selector's profile with every field the contract requires`() = runTest {
        val draft = ScheduleRules.newDraft(emptyList(), "UTC").copy(name = "Report", agentId = claudeId, prompt = "Go")
        val made = ops().create("home", ScheduleRules.create(draft, hermes = false)!!).getOrThrow()
        assertEquals(id, made.id)
        assertEquals("POST", requests.last().method)
        assertEquals("home", requests.last().getHeader("X-Hub-Profile"))
        val sent = kotlinx.serialization.json.Json.parseToJsonElement(bodies.last()).jsonObject
        val trigger = sent["trigger"] as JsonObject
        assertEquals(JsonNull, trigger["every_minutes"])
        assertEquals(JsonNull, trigger["run_at"])
        val target = sent["target"] as JsonObject
        assertEquals(JsonNull, target["model"])
        assertEquals(JsonNull, target["workflow_id"])
        assertEquals("wait", sent["overlap"]!!.jsonPrimitive.content)
    }

    @Test fun `Hermes's zone comes back with its refusal`() = runTest {
        val draft = ScheduleRules.newDraft(emptyList(), "Asia/Riyadh").copy(name = "Report", agentId = hermesId)
        val refused = ops().create("work", ScheduleRules.create(draft, hermes = true)!!).exceptionOrNull() as HubError
        assertEquals(409, refused.status)
        assertEquals("UTC", ScheduleRules.askedZone(refused))
    }

    @Test fun `the preview reads the hub's next runs with a whole trigger`() = runTest {
        val next = ops().preview("work", TriggerRules.build(TriggerRules.draft(null, "UTC"))!!)
        assertEquals(2, next.size)
        assertTrue(bodies.last(), bodies.last().contains("\"every_minutes\":null"))
    }

    @Test fun `an edit carries its trigger and target whole, and a plain patch gains no nulls`() = runTest {
        // Contract decision §114: the generated client writes a required nullable field as
        // `null` (no per-app interceptor any more), and leaves out what a patch did not name.
        val write = ScheduleWrite(
            name = "Every 15",
            trigger = ScheduleTrigger(kind = ScheduleTrigger.Kind.INTERVAL, timezone = "UTC", everyMinutes = 15),
            target = ScheduleTarget(kind = ScheduleTarget.Kind.AGENT_PROMPT, skills = emptyList(), agentId = claudeId, prompt = "Ping"),
        )
        ops().update(schedule(), write).getOrThrow()
        assertEquals("PATCH", requests.last().method)
        val sent = kotlinx.serialization.json.Json.parseToJsonElement(bodies.last()).jsonObject
        assertEquals(setOf("name", "trigger", "target"), sent.keys)
        val trigger = sent["trigger"]!!.jsonObject
        assertEquals(JsonNull, trigger["expression"])
        assertEquals(JsonNull, trigger["run_at"])
        assertEquals("15", trigger["every_minutes"]!!.jsonPrimitive.content)
        assertFalse("read-only and optional: left out", "display" in trigger)
        val target = sent["target"]!!.jsonObject
        listOf("model", "provider", "workflow_id", "input").forEach { assertEquals(it, JsonNull, target[it]) }
        assertEquals("Ping", target["prompt"]!!.jsonPrimitive.content)

        ops().setEnabled(schedule(), false).getOrThrow()
        assertEquals("""{"enabled":false}""", bodies.last())
    }

    @Test fun `the list, the history, run now, pause, edit and delete each go to the schedule's profile`() = runTest {
        val page = ops().list(null).getOrThrow()
        assertEquals("c2", page.nextCursor)
        assertEquals("all", requests.last().requestUrl!!.queryParameter("profiles"))
        val saved = page.items.single()
        val first = ops().runs(saved, null).getOrThrow()
        assertEquals("r2", first.nextCursor)
        assertEquals(ScheduleRules.RUNS_PAGE.toString(), requests.last().requestUrl!!.queryParameter("limit"))
        assertEquals(0, ops().runs(saved, "r2").getOrThrow().items.size)
        assertEquals("r2", requests.last().requestUrl!!.queryParameter("cursor"))
        assertEquals("01J8QK3ZR2W7M5N4P6T8V9X0YF", ops().runNow(saved).getOrThrow().sessionId)
        assertTrue(ops().setEnabled(saved, false).isSuccess)
        assertEquals("""{"enabled":false}""", bodies.last())
        assertTrue(ops().update(saved, ScheduleWrite(overlap = ScheduleOverlap.SKIP)).isSuccess)
        assertEquals("""{"overlap":"skip"}""", bodies.last())
        assertTrue(ops().delete(saved).isSuccess)
        assertEquals("DELETE", requests.last().method)
        assertTrue(requests.drop(1).all { it.getHeader("X-Hub-Profile") == "work" })
    }
}

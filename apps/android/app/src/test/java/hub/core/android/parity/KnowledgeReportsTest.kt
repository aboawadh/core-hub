package hub.core.android.parity

import hub.core.android.ui.screens.HubDataApis
import hub.core.android.ui.screens.HubDataOps
import hub.core.android.ui.screens.KnowledgeRules
import hub.core.android.ui.screens.NotifyWebhookRules
import hub.core.android.ui.screens.SkillsUsageRules
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.ActiveAgent
import hub.core.client.model.Job
import hub.core.client.model.SkillUsageReport
import hub.core.client.model.Webhook
import hub.core.client.model.WebhookDelivery
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
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
 * Apps batch 10: Knowledge, Skills usage, hub Plugins and Webhooks — the rules the pages follow and
 * the calls they make, against a scripted hub. iOS's HubDataRulesTests is the twin.
 */
class KnowledgeReportsTest {
    private val json = Serializer.kotlinxSerializationJson

    private fun hook(secret: Boolean = true, profiles: String = "[]") = json.decodeFromString(
        Webhook.serializer(),
        """{"id":"W1","name":"Ops","url":"https://example.com/hook","events":["run.completed","task.created"],"profiles":$profiles,
            "enabled":true,"secret":${if (secret) "\"[stored]\"" else "null"},"include_content":false,"allow_private_network":false,
            "max_retries":3,"stats":{"delivered":4,"failed":1,"last_delivery_at":null,"last_error":null},
            "created_at":"2026-09-27T10:00:00Z","updated_at":"2026-09-27T10:00:00Z"}""",
    )

    private fun delivery(status: String, next: String? = null) = json.decodeFromString(
        WebhookDelivery.serializer(),
        """{"id":"D1","webhook_id":"W1","event":"run.completed","status":"$status","attempts":2,"response_status":500,"error":"boom",
            "created_at":"2026-09-27T10:00:00Z","delivered_at":null,"next_attempt_at":${next?.let { "\"$it\"" } ?: "null"}}""",
    )

    private fun job(status: String, result: String = "null", error: String = "null") = json.decodeFromString(
        Job.serializer(),
        """{"id":"J1","profile":"work","owner_id":"u1","created_at":"2026-09-27T10:00:00Z","updated_at":"2026-09-27T10:00:00Z",
            "kind":"webhook_test","status":"$status","progress":{"percent":30,"message":null},"result":$result,"error":$error}""",
    )

    private val reportJson = """{"period":{"from":"2026-09-25","to":"2026-09-27","days":3},"generated_at":"2026-09-27T10:00:00Z",
        "profiles":["work"],"counting_since":"2026-09-20T08:00:00Z","agents":[{"agent_id":"A1","name":"Hermes","reports_usage":true}],
        "totals":{"uses":7,"distinct_skills":3,"top_skill":{"skill":"web-research","uses":4},"never_used_count":null},
        "top_series":["web-research","docker"],
        "by_day":[{"date":"2026-09-25","uses":3,"skills":{"web-research":2,"docker":1},"other":0},
                  {"date":"2026-09-26","uses":0,"skills":{},"other":0},
                  {"date":"2026-09-27","uses":4,"skills":{"web-research":2,"docker":0},"other":2}],
        "top_skills":[{"skill":"web-research","uses":4,"share":0.5714,"last_used_at":"2026-09-27T09:00:00Z"}],"never_used":["pdf"]}"""

    private fun report() = json.decodeFromString(SkillUsageReport.serializer(), reportJson)

    // ------------------------------------------------------------------ the rules

    @Test fun `the knowledge kind and search are sent only when there is one`() {
        assertNull(KnowledgeRules.kind(null))
        assertEquals("journal", KnowledgeRules.kind("journal")!!.value)
        assertNull(KnowledgeRules.kind("video"))
        assertNull(KnowledgeRules.query("   "))
        assertEquals("tax", KnowledgeRules.query("  tax "))
        assertEquals(listOf(null, "journal", "note", "file"), KnowledgeRules.KINDS)
    }

    @Test fun `the skills chart lists the busy days newest first, with other last`() {
        val days = SkillsUsageRules.activeDays(report())
        assertEquals(listOf("2026-09-27", "2026-09-25"), days.map { it.date.toString() })
        assertEquals(listOf("web-research" to 2, null to 2), SkillsUsageRules.daySkills(days[0]))
        assertEquals(listOf("web-research" to 2, "docker" to 1), SkillsUsageRules.daySkills(days[1]))
        assertEquals(1f, SkillsUsageRules.fraction(4, 4))
        assertEquals(0.75f, SkillsUsageRules.fraction(3, 4))
        assertEquals(0f, SkillsUsageRules.fraction(3, 0))
    }

    @Test fun `a share is written with one decimal and Latin digits`() {
        assertEquals("57.1%", SkillsUsageRules.percent(BigDecimal("0.5714")))
        assertEquals("50%", SkillsUsageRules.percent(BigDecimal("0.5")))
        assertEquals("100%", SkillsUsageRules.percent(BigDecimal("1")))
        assertEquals("0%", SkillsUsageRules.percent(BigDecimal("0")))
    }

    @Test fun `a chosen agent gone from the period stays choosable`() {
        val agents = listOf(ActiveAgent("A1", true, "Hermes"))
        assertEquals(agents, SkillsUsageRules.agentChoices(agents, null))
        assertEquals(agents, SkillsUsageRules.agentChoices(agents, "A1"))
        assertEquals(listOf("A1", "A9"), SkillsUsageRules.agentChoices(agents, "A9").map { it.agentId })
        assertEquals(180, SkillsUsageRules.utcOffset(ZoneId.of("Asia/Riyadh"), Instant.parse("2026-09-27T10:00:00Z")))
    }

    @Test fun `a webhook sheet starts signed and checks the address and the profiles`() {
        val fresh = NotifyWebhookRules.draft(null)
        assertEquals(NotifyWebhookRules.SecretChoice.NEW, fresh.secret)
        assertTrue(fresh.allProfiles)
        assertFalse(NotifyWebhookRules.ready(fresh))
        val typed = fresh.copy(name = "Ops", url = "ftp://example.com")
        assertTrue(NotifyWebhookRules.badUrl(typed.url))
        assertFalse(NotifyWebhookRules.ready(typed))
        assertTrue(NotifyWebhookRules.ready(typed.copy(url = "https://example.com/x")))
        assertFalse(NotifyWebhookRules.badUrl(""))
        val some = typed.copy(url = "https://example.com/x", allProfiles = false)
        assertTrue(NotifyWebhookRules.noProfile(some))
        assertFalse(NotifyWebhookRules.ready(some))
        assertTrue(NotifyWebhookRules.ready(some.copy(profiles = setOf("work"))))

        val signed = NotifyWebhookRules.draft(hook())
        assertEquals(NotifyWebhookRules.SecretChoice.KEEP, signed.secret)
        assertEquals("3", signed.retries)
        assertEquals(listOf(NotifyWebhookRules.SecretChoice.KEEP, NotifyWebhookRules.SecretChoice.NEW, NotifyWebhookRules.SecretChoice.NONE), NotifyWebhookRules.secretChoices(hook()))
        assertEquals(NotifyWebhookRules.SecretChoice.NONE, NotifyWebhookRules.draft(hook(secret = false)).secret)
        assertEquals(listOf(NotifyWebhookRules.SecretChoice.NEW, NotifyWebhookRules.SecretChoice.NONE), NotifyWebhookRules.secretChoices(hook(secret = false)))
        assertFalse(NotifyWebhookRules.draft(hook(profiles = """["work"]""")).allProfiles)
    }

    @Test fun `retries keep to 0-10 and a new secret is 32 random bytes in hex`() {
        assertEquals(10, NotifyWebhookRules.retries("99"))
        assertEquals(0, NotifyWebhookRules.retries("0"))
        assertEquals(5, NotifyWebhookRules.retries(""))
        val secret = NotifyWebhookRules.newSecret { bytes -> bytes.indices.forEach { bytes[it] = it.toByte() } }
        assertEquals("whsec_" + (0 until 32).joinToString("") { "%02x".format(it) }, secret)
        assertEquals(6 + 64, NotifyWebhookRules.newSecret().length)
    }

    @Test fun `a failed delivery with no retry left can be sent again`() {
        assertTrue(NotifyWebhookRules.canRedeliver(delivery("dead")))
        assertTrue(NotifyWebhookRules.canRedeliver(delivery("failed")))
        assertFalse(NotifyWebhookRules.canRedeliver(delivery("failed", next = "2026-09-27T10:05:00Z")))
        assertFalse(NotifyWebhookRules.canRedeliver(delivery("delivered")))
        assertTrue(NotifyWebhookRules.waiting(listOf(delivery("queued"))))
        assertTrue(NotifyWebhookRules.waiting(listOf(delivery("failed", next = "2026-09-27T10:05:00Z"))))
        assertFalse(NotifyWebhookRules.waiting(listOf(delivery("dead"))))
    }

    @Test fun `a test job says how the delivery went once it is over`() {
        assertNull(NotifyWebhookRules.outcome(job("running")))
        assertEquals(NotifyWebhookRules.TestOutcome(true, 204, null), NotifyWebhookRules.outcome(job("succeeded", """{"delivered":true,"status":204,"error":null}""")))
        assertEquals(NotifyWebhookRules.TestOutcome(false, 500, null), NotifyWebhookRules.outcome(job("succeeded", """{"delivered":false,"status":500}""")))
        assertEquals(
            NotifyWebhookRules.TestOutcome(false, 0, "refused"),
            NotifyWebhookRules.outcome(job("failed", error = """{"error":"refused","code":"bad_request"}""")),
        )
        assertEquals(NotifyWebhookRules.UrlRefusal.PRIVATE, NotifyWebhookRules.urlRefusal("url_private"))
        assertNull(NotifyWebhookRules.urlRefusal("profile_not_allowed"))
    }

    // ------------------------------------------------------------------ the calls

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private fun ok(body: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private val hookJson = """{"id":"W1","name":"Ops","url":"https://example.com/hook","events":["run.completed"],"profiles":[],
        "enabled":true,"secret":"[stored]","include_content":false,"allow_private_network":false,"max_retries":3,
        "stats":{"delivered":0,"failed":0,"last_delivery_at":null,"last_error":null},"created_at":"2026-09-27T10:00:00Z","updated_at":"2026-09-27T10:00:00Z"}"""
    private var jobPolls = 0

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/knowledge/items") -> ok(
                        """{"items":[{"id":"K1","profile":"work","owner_id":"u1","created_at":"2026-09-27T10:00:00Z","updated_at":"2026-09-27T10:00:00Z",
                            "kind":"note","title":"Tax","content":"File by March","date":null,"mood":null,"tags":["money"],"attachment_ids":[]}],"next_cursor":"c2"}""",
                    )
                    path.endsWith("/plugins") -> ok(
                        """{"items":[{"id":"P1","slug":"searx","name":"SearX","version":"1.2.0","kind":"docker","status":"running","url":null,
                            "created_at":"2026-09-27T10:00:00Z","updated_at":"2026-09-27T10:00:00Z"}]}""",
                    )
                    path.endsWith("/audit/skills") -> ok(reportJson)
                    path.endsWith("/notify/webhook-events") -> ok("""{"items":[{"name":"run.completed","description":{"ar":"اكتمل","en":"Run completed"}}]}""")
                    path.endsWith("/notify/webhooks") && request.method == "GET" -> ok("""{"items":[$hookJson]}""")
                    path.endsWith("/notify/webhooks") && request.method == "POST" -> ok(hookJson, 201)
                    path.endsWith("/test") -> ok("""{"job_id":"J1"}""", 202)
                    path.contains("/jobs/") -> {
                        jobPolls++
                        ok(
                            """{"id":"J1","profile":"work","owner_id":"u1","created_at":"2026-09-27T10:00:00Z","updated_at":"2026-09-27T10:00:00Z",
                            "kind":"webhook_test","status":"${if (jobPolls < 2) "running" else "succeeded"}","progress":{"percent":30},
                            "result":${if (jobPolls < 2) "null" else """{"delivered":true,"status":200,"error":null}"""}}""",
                        )
                    }
                    path.endsWith("/redeliver") -> ok(
                        """{"id":"D2","webhook_id":"W1","event":"run.completed","status":"queued","attempts":0,"created_at":"2026-09-27T10:00:00Z"}""", 202,
                    )
                    path.endsWith("/deliveries") -> ok("""{"items":[]}""")
                    path.contains("/notify/webhooks/") && request.method == "PATCH" -> ok(hookJson)
                    path.contains("/notify/webhooks/") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun ops() = HubDataOps("work") { HubDataApis(server.url("/").toString().trimEnd('/'), OkHttpClient(), "work") }
    private fun body(request: RecordedRequest) = json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test fun `knowledge asks for the kind, the search and the next page in the profile`() = runTest {
        val page = ops().knowledge("note", "  tax ", "c1").getOrThrow()
        assertEquals("Tax", page.items.single().title)
        assertEquals("c2", page.next)
        val url = requests[0].requestUrl!!
        assertEquals("note", url.queryParameter("kind"))
        assertEquals("tax", url.queryParameter("q"))
        assertEquals("c1", url.queryParameter("cursor"))
        assertEquals("work", requests[0].getHeader("X-Hub-Profile"))
        ops().knowledge(null, "", null).getOrThrow()
        assertNull(requests[1].requestUrl!!.queryParameter("kind"))
        assertNull(requests[1].requestUrl!!.queryParameter("q"))
    }

    @Test fun `plugins are listed and a skills report names its period, profiles and agent`() = runTest {
        assertEquals("SearX", ops().plugins().getOrThrow().single().name)
        val report = ops().skillUsage(90, "work", everyProfile = true, agent = "A1", offset = 180).getOrThrow()
        assertEquals(7, report.totals.uses)
        val url = requests[1].requestUrl!!
        assertEquals("90", url.queryParameter("days"))
        assertEquals("all", url.queryParameter("profiles"))
        assertEquals("A1", url.queryParameter("agent_id"))
        assertEquals("180", url.queryParameter("utc_offset_minutes"))
        ops().skillUsage(7, "home", everyProfile = false, agent = null, offset = 0).getOrThrow()
        assertNull(requests[2].requestUrl!!.queryParameter("profiles"))
        assertEquals("home", requests[2].getHeader("X-Hub-Profile"))
    }

    @Test fun `webhooks carry the profile, a new one is signed, stop signing sends null`() = runTest {
        assertEquals("Ops", ops().webhooks().getOrThrow().single().name)
        assertEquals("work", requests[0].getHeader("X-Hub-Profile"))
        val events = ops().events().getOrThrow().map { it.name }

        val draft = NotifyWebhookRules.draft(null).copy(name = " Ops ", url = "https://example.com/hook", events = setOf("run.completed", "gone.event"), retries = "4")
        ops().create(NotifyWebhookRules.write(draft, events, "whsec_abc")).getOrThrow()
        val created = body(requests[2])
        assertEquals("Ops", created["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("run.completed"), created["events"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("whsec_abc", created["secret"]!!.jsonPrimitive.content)
        assertEquals(4, created["max_retries"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, created["profiles"]!!.jsonArray.size)

        val stop = NotifyWebhookRules.draft(hook()).copy(secret = NotifyWebhookRules.SecretChoice.NONE)
        ops().update("W1", NotifyWebhookRules.write(stop, events, null)).getOrThrow()
        val patched = body(requests[3])
        assertEquals(JsonNull, patched["secret"])
        assertEquals("work", requests[3].getHeader("X-Hub-Profile"))

        val keep = NotifyWebhookRules.draft(hook())
        ops().update("W1", NotifyWebhookRules.write(keep, events, null)).getOrThrow()
        assertFalse("secret" in body(requests[4]))

        ops().setEnabled("W1", false).getOrThrow()
        val toggled = body(requests[5])
        assertEquals(setOf("enabled"), toggled.keys)
    }

    @Test fun `a test is followed to its end, deliveries are read and one is sent again, and a webhook deleted`() = runTest {
        val outcome = ops().test("W1", pause = {}).getOrThrow()
        assertEquals(NotifyWebhookRules.TestOutcome(true, 200, null), outcome)
        assertEquals(2, jobPolls)
        assertTrue(requests.any { it.requestUrl!!.encodedPath.endsWith("/jobs/J1") && it.getHeader("X-Hub-Profile") == "work" })

        assertTrue(ops().deliveries("W1").getOrThrow().isEmpty())
        assertEquals("10", requests.last().requestUrl!!.queryParameter("limit"))
        assertEquals(WebhookDelivery.Status.QUEUED, ops().redeliver("W1", "D1").getOrThrow().status)
        assertEquals("POST", requests.last().method)
        ops().delete("W1").getOrThrow()
        assertEquals("DELETE", requests.last().method)
        assertTrue(requests.last().requestUrl!!.encodedPath.endsWith("/W1"))
    }
}

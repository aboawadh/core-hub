package hub.core.android.shots

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.AppLanguage
import hub.core.android.MainActivity
import hub.core.android.data.StoredSession
import hub.core.android.data.StoredUser
import hub.core.android.data.TokenKind
import hub.core.android.graph
import hub.core.android.repoRoot
import hub.core.android.ui.theme.ThemeChoice
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Apps batch 10: Knowledge, Skills usage, the hub's Plugins and Webhooks, against the demo hub with
 * answers of their own, light English and dark Arabic, photographed to
 * `apps/android/app/build/shots/knowledge/android-<page>-<theme>-<lang>.png`. The webhooks shot also
 * sends a test and opens the deliveries, and checks the hub was asked.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi", application = ShotsApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KnowledgeShots {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var hub: DemoHub
    private val out = File(repoRoot, "apps/android/app/build/shots/knowledge").apply { mkdirs() }

    private val stamp = """"created_at":"2026-09-26T08:00:00Z","updated_at":"2026-09-26T08:00:00Z""""
    private val answers = mapOf(
        "knowledge.listItems" to """{"items":[
            {"id":"01K5DM000000000000000000K1","profile":"work","owner_id":"u1",$stamp,"kind":"journal","title":"Friday","content":"Shipped the phone pages; the webhook tests pass.","date":"2026-09-26","mood":null,"tags":["release"],"attachment_ids":[]},
            {"id":"01K5DM000000000000000000K2","profile":"work","owner_id":"u1",$stamp,"kind":"note","title":"ملاحظة الضرائب","content":"قدّم الإقرار قبل نهاية مارس.","date":null,"mood":null,"tags":["money","tax"],"attachment_ids":["A1","A2"]},
            {"id":"01K5DM000000000000000000K3","profile":"work","owner_id":"u1",$stamp,"kind":"file","title":"report-q3.pdf","content":null,"date":null,"mood":null,"tags":[],"attachment_ids":["A3"]}],
            "next_cursor":null}""",
        "audit.getSkillUsage" to """{"period":{"from":"2026-08-28","to":"2026-09-26","days":30},"generated_at":"2026-09-26T10:00:00Z",
            "profiles":["work"],"counting_since":"2026-09-20T08:00:00Z",
            "agents":[{"agent_id":"01K5DM000000000000000000A1","name":"Hermes","reports_usage":true}],
            "totals":{"uses":11,"distinct_skills":4,"top_skill":{"skill":"web-research","uses":6},"never_used_count":null},
            "top_series":["web-research","docker","pdf"],
            "by_day":[{"date":"2026-09-24","uses":3,"skills":{"web-research":2,"docker":1},"other":0},
                      {"date":"2026-09-25","uses":2,"skills":{"pdf":2},"other":0},
                      {"date":"2026-09-26","uses":6,"skills":{"web-research":4,"docker":1},"other":1}],
            "top_skills":[{"skill":"web-research","uses":6,"share":0.5454,"last_used_at":"2026-09-26T09:00:00Z"},
                          {"skill":"pdf","uses":2,"share":0.1818,"last_used_at":"2026-09-25T14:00:00Z"},
                          {"skill":"docker","uses":2,"share":0.1818,"last_used_at":"2026-09-26T08:30:00Z"}],
            "never_used":["image-generate","maps"]}""",
        "plugins.list" to """{"items":[
            {"id":"P1","slug":"searxng","name":"SearXNG","version":"2025.9.1","kind":"docker","status":"running","url":null,$stamp},
            {"id":"P2","slug":"browser-mcp","name":"Browser MCP","version":"0.4.0","kind":"mcp","status":"error","url":null,$stamp}]}""",
        "notify.listWebhooks" to """{"items":[
            {"id":"01K5DM000000000000000000W1","name":"Ops alerts","url":"https://ops.example.com/hooks/core-hub","events":["run.failed","approval.requested"],
             "profiles":[],"enabled":true,"secret":"[stored]","include_content":false,"allow_private_network":false,"max_retries":5,
             "stats":{"delivered":42,"failed":2,"last_delivery_at":"2026-09-26T09:00:00Z","last_error":null},$stamp},
            {"id":"01K5DM000000000000000000W2","name":"Home automation","url":"http://192.168.1.20:8123/hooks/hub","events":["task.created"],
             "profiles":["work"],"enabled":false,"secret":null,"include_content":true,"allow_private_network":true,"max_retries":3,
             "stats":{"delivered":0,"failed":0,"last_delivery_at":null,"last_error":null},$stamp}]}""",
        "notify.listWebhookDeliveries" to """{"items":[
            {"id":"D1","webhook_id":"01K5DM000000000000000000W1","event":"run.failed","status":"delivered","attempts":1,"response_status":200,"error":null,"created_at":"2026-09-26T09:00:00Z","delivered_at":"2026-09-26T09:00:01Z","next_attempt_at":null},
            {"id":"D2","webhook_id":"01K5DM000000000000000000W1","event":"approval.requested","status":"dead","attempts":6,"response_status":502,"error":"Bad gateway","created_at":"2026-09-25T09:00:00Z","delivered_at":null,"next_attempt_at":null}]}""",
        "notify.testWebhook" to """{"job_id":"01K5DM000000000000000000J1"}""",
        "jobs.get" to """{"id":"01K5DM000000000000000000J1","profile":"work","owner_id":"u1",$stamp,"kind":"webhook_test","status":"succeeded",
            "progress":{"percent":100},"result":{"delivered":true,"status":200,"error":null}}""",
    )

    @Before fun start() {
        hub = DemoHub(answers).start()
    }

    @After fun stop() {
        hub.missed.sorted().forEach { println("demo hub: no answer for $it") }
        hub.stop()
    }

    @Test fun lightEnglish() = shoot(ThemeChoice.LIGHT, AppLanguage.EN)
    @Test fun darkArabic() = shoot(ThemeChoice.DARK, AppLanguage.AR)

    private fun shoot(theme: ThemeChoice, language: AppLanguage) {
        val graph = ApplicationProvider.getApplicationContext<android.content.Context>().graph
        graph.prefs.language = language
        graph.prefs.setTheme(theme)
        graph.store.save(
            StoredSession(
                hub = hub.base, kind = TokenKind.APP, accessToken = "demo",
                user = StoredUser("01K5DM00000000000000000001", "sara", "Sara", "owner", listOf("work", "personal"), "work"),
                profile = "work",
            ),
        )
        val name = "${theme.name.lowercase()}-${if (language == AppLanguage.AR) "ar" else "en"}"

        open("/settings/knowledge") { activity ->
            waitFor("knowledge.item.01K5DM000000000000000000K2")
            save(activity, "knowledge-$name")
        }
        open("/settings/skills-usage") { activity ->
            waitFor("skills.total.top")
            waitFor("skills.daily")
            save(activity, "skills-usage-$name")
            compose.onNodeWithTag("skills.page").performScrollToNode(hasTestTag("skills.never"))
            waitFor("skills.never")
            save(activity, "skills-usage-top-$name")
        }
        open("/settings/plugins") { activity ->
            waitFor("plugin.browser-mcp")
            save(activity, "plugins-$name")
        }
        open("/settings/webhooks") { activity ->
            val w1 = "webhook.01K5DM000000000000000000W1"
            waitFor(w1)
            save(activity, "webhooks-$name")
            compose.onNodeWithTag("$w1.test", useUnmergedTree = true).performClick()
            waitFor("$w1.outcome")
            compose.onNodeWithTag("$w1.deliveries", useUnmergedTree = true).performClick()
            waitFor("delivery.D2.redeliver")
            save(activity, "webhooks-deliveries-$name")
            val asked = generateSequence { hub.server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .map { "${it.method} ${it.requestUrl?.encodedPath} ${it.getHeader("X-Hub-Profile")}" }.toList()
            assertTrue("the test was sent in the profile: $asked", asked.any { it.startsWith("POST") && it.contains("/test") && it.endsWith(" work") })
            assertTrue("the deliveries were read: $asked", asked.any { it.startsWith("GET") && it.contains("/deliveries") })
        }
    }

    private fun open(path: String, block: (Activity) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).setData(Uri.parse("corehub://open$path"))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var activity: Activity? = null
            scenario.onActivity { activity = it }
            block(activity!!)
        }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun save(activity: Activity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, "android-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

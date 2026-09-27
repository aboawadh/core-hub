package hub.core.android.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.data.HubApis
import hub.core.android.repoRoot
import hub.core.android.ui.components.RowAction
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.screens.ScheduleCard
import hub.core.android.ui.screens.ScheduleDetailBody
import hub.core.android.ui.screens.ScheduleEditorBody
import hub.core.android.ui.screens.ScheduleOps
import hub.core.android.ui.theme.CoreHubTheme
import hub.core.android.ui.theme.LocalTokens
import hub.core.android.ui.theme.ThemeChoice
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Agent
import hub.core.client.model.Schedule
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Schedules (batch 3): the new-schedule form, a schedule on its own with its history, and a card of
 * the list, drawn against a scripted hub, poked, and photographed to
 * `apps/android/app/build/shots/schedules/android-<name>.png`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SchedulesShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File(repoRoot, "apps/android/app/build/shots/schedules").apply { mkdirs() }
    private val json = Serializer.kotlinxSerializationJson
    private val server = MockWebServer()
    private val posted = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun agent(id: String, slug: String, name: String): Agent = json.decodeFromString(
        Agent.serializer(),
        """{"id":"$id","profile":"work","owner_id":"u1","created_at":"2026-09-20T09:00:00Z","updated_at":"2026-09-20T09:00:00Z",
        "slug":"$slug","name":"$name","kind":"hermes","avatar":{"kind":"generated","seed":"a"},"status":"available","enabled":true,
        "install":{"source":"managed","update_available":false,"newer_than_tested":false,"auto_update":false,"auto_update_supported":false},
        "runtime":{"state":"running"},"capabilities":[],"sections":[],"limited":false,"subagents":"none"}""",
    )

    private val agents = listOf(agent("01J8QK3ZR2W7M5N4P6T8V9X0AH", "hermes", "Hermes"), agent("01J8QK3ZR2W7M5N4P6T8V9X0AC", "claude-code", "Claude Code"))

    private val scheduleJson = """{"id":"01J8QK3ZR2W7M5N4P6T8V9X0SC","profile":"work","owner_id":"u1",
        "created_at":"2026-09-20T09:00:00Z","updated_at":"2026-09-21T10:00:00Z","name":"Morning brief",
        "trigger":{"kind":"cron","expression":"0 9 * * 1-5","timezone":"Asia/Riyadh","display":"Weekdays at 09:00"},
        "target":{"kind":"agent_prompt","agent_id":"01J8QK3ZR2W7M5N4P6T8V9X0AC","prompt":"What changed overnight in the repository? Three lines.","skills":[]},
        "delivery":{"kind":"channel","channel":"telegram","address":"123"},"repeat":{"completed":12},"enabled":true,
        "state":"scheduled","next_run_at":"2026-09-28T06:00:00Z","last_run_at":"2026-09-27T06:00:00Z","last_status":"succeeded",
        "run_if_missed":true,"overlap":"wait"}"""
    private val schedule: Schedule = json.decodeFromString(Schedule.serializer(), scheduleJson)

    private fun runJson(id: String, status: String, trigger: String, started: String, finished: String?, preview: String?, error: String? = null) =
        """{"id":"$id","schedule_id":"01J8QK3ZR2W7M5N4P6T8V9X0SC","job_id":"J$id","session_id":"01J8QK3ZR2W7M5N4P6T8V9X0YF",
        "status":"$status","trigger":"$trigger","waiting":false,"output_size_bytes":10,"delivery_status":"delivered",
        ${preview?.let { "\"output_preview\":\"$it\"," } ?: ""}${error?.let { "\"error\":\"$it\"," } ?: ""}
        "started_at":"$started"${finished?.let { ",\"finished_at\":\"$it\"" } ?: ""}}"""

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (request.method == "POST") posted += request.body.readUtf8()
                fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                return when {
                    path.endsWith("/preview") -> ok("""{"timezone":"Asia/Riyadh","next_runs":["2026-09-28T06:00:00Z","2026-09-29T06:00:00Z","2026-09-30T06:00:00Z"]}""")
                    path.endsWith("/runs") -> ok(
                        """{"items":[${runJson("R3", "succeeded", "manual", "2026-09-27T09:12:00Z", "2026-09-27T09:12:41Z", "Two fixes merged; the build is green.")},
                        ${runJson("R2", "succeeded", "schedule", "2026-09-27T06:00:00Z", "2026-09-27T06:02:05Z", "Nothing new overnight.")},
                        ${runJson("R1", "failed", "schedule", "2026-09-26T06:00:00Z", "2026-09-26T06:00:09Z", null, "The model did not answer.")}],"next_cursor":"more"}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private val ops by lazy { ScheduleOps { HubApis(server.url("/").toString().trimEnd('/'), OkHttpClient()) } }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, "android-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun newSchedule() {
        compose.setContent {
            CoreHubTheme(ThemeChoice.LIGHT) {
                Box(Modifier.fillMaxSize().background(LocalTokens.current.bgRaised).padding(16.dp)) {
                    ScheduleEditorBody(null, "work", "New schedules are made in Work.", agents, ops, onDismiss = {}, onSaved = {})
                }
            }
        }
        compose.onNodeWithTag("schedule.form.name").performTextReplacement("Morning brief")
        compose.waitUntil(5_000) { posted.any { it.contains("\"every_minutes\":null") } }
        compose.waitUntilAtLeastOneExists(hasTestTag("schedule.form.trigger.next"), 5_000)
        compose.onNodeWithText("Hermes decides these two for its own jobs, so they are not set here.").assertExists()
        save("schedule-new")
        // Another agent: the hub fires it, so its two run options show.
        compose.onNodeWithTag("schedule.form.agent.claude-code").performClick()
        compose.onNodeWithTag("schedule.form.missed").assertExists()
        compose.onNodeWithTag("schedule.form.templates").performClick()
        compose.onNodeWithText("Every 15 minutes").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("schedule.form.trigger.every").assertExists()
        save("schedule-new-options")
    }

    private fun detail(theme: ThemeChoice) = compose.setContent {
        CoreHubTheme(theme) {
            Box(Modifier.fillMaxSize().background(LocalTokens.current.bgRaised).padding(16.dp)) {
                Column {
                    ScheduleCard(schedule, "Work", listOf(RowAction("Run now", Lucide.Play) {})) {}
                    ScheduleDetailBody(
                        schedule, ops, "Work", agentName = { "Claude Code" }, onChanged = {}, onEdit = {}, onDelete = {}, onOpenChat = {},
                    )
                }
            }
        }
    }

    @Test fun scheduleDetail() {
        detail(ThemeChoice.LIGHT)
        compose.waitUntilAtLeastOneExists(hasTestTag("schedule.run.R3"), 5_000)
        compose.onNodeWithText("took 41s", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("schedule.history.more").assertExists()
        save("schedule-detail")
    }

    @Test fun scheduleDetailDark() {
        detail(ThemeChoice.DARK)
        compose.waitUntilAtLeastOneExists(hasTestTag("schedule.run.R1"), 5_000)
        compose.onNodeWithTag("schedule.enabled").assertExists()
        save("schedule-detail-dark")
    }
}

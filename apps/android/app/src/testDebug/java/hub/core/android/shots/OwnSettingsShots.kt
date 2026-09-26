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
 * The person's own pages (batch 4): Account, Display, Privacy and the inbox, against the demo hub
 * with a few answers of their own, light English and dark Arabic, photographed to
 * `apps/android/app/build/shots/own-settings/android-<name>.png`. Each asserts what it is about; the
 * inbox also marks a notice read and checks the hub was told.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi", application = ShotsApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OwnSettingsShots {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var hub: DemoHub
    private val out = File(repoRoot, "apps/android/app/build/shots/own-settings").apply { mkdirs() }

    private val answers = mapOf(
        "auth.getPreferences" to """{"theme":"system","locale":"en","text_scale":1.1,"link_target":"in_app","busy_input_mode":"queue",
            "streaming":true,"compact":false,"show_reasoning":true,"show_tool_calls":false,"show_cost":false,"inline_diffs":true,
            "sound_on_complete":false,"notify_on_complete":true,"notify_on_approval":true,"reasoning_effort":null,
            "voice":{"input_mode":"device","dictation_language":"app","output_mode":"device","auto_speak":false}}""",
        "auth.listAppTokens" to """{"items":[
            {"id":"01K5DM000000000000000000T1","name":"Sara's iPhone","scopes":["read","write","device"],"device_id":"01K5DM000000000000000000D1",
             "last_used_at":"2026-09-26T08:00:00Z","expires_at":"2026-12-25T08:00:00Z","created_at":"2026-09-20T08:00:00Z"},
            {"id":"01K5DM000000000000000000T2","name":"Backup script","scopes":["read"],"device_id":null,
             "last_used_at":null,"expires_at":null,"created_at":"2026-09-21T08:00:00Z"}]}""",
        "auth.listMyChannelIdentities" to """{"items":[{"id":"01K5DM000000000000000000C1","user_id":"01K5DM00000000000000000001",
            "platform":"telegram","sender_id":"48213377","linked_at":"2026-09-24T10:00:00Z","last_used_at":"2026-09-26T19:30:00Z"}]}""",
        "notify.listNotices" to """{"items":[
            {"id":"01K5DM000000000000000000N1","user_id":"01K5DM00000000000000000001","profile":"work","kind":"approval_requested",
             "title":"Hermes waits for your approval","body":"Send the status update to the team channel?","resource":{"kind":"session","id":"01K5DM00000000000000000034"},
             "read_at":null,"created_at":"2026-09-26T09:40:00Z"},
            {"id":"01K5DM000000000000000000N2","user_id":"01K5DM00000000000000000001","profile":"work","kind":"run_completed",
             "title":"Hermes finished the reply","body":"Weekly status — 3 tasks moved.","resource":{"kind":"session","id":"01K5DM00000000000000000034"},
             "read_at":null,"created_at":"2026-09-26T09:34:00Z"},
            {"id":"01K5DM000000000000000000N3","user_id":"01K5DM00000000000000000001","profile":"work","kind":"schedule_failed",
             "title":"A schedule failed","body":"Morning digest: the provider did not answer.","resource":{"kind":"schedule_run","id":"01K5DM000000000000000000R1"},
             "read_at":"2026-09-26T07:00:00Z","created_at":"2026-09-26T06:30:00Z"}],
            "next_cursor":null,"unread_count":2}""",
        "agents.getSettings" to """{"sections":[{"key":"privacy","title":{"en":"Privacy","ar":"الخصوصية"},"restart_required":false,
            "note":{"en":"On WhatsApp, Telegram, Signal and BlueBubbles, ids are hashed and phone numbers left out.","ar":"في واتساب وتيليجرام وسيجنال وبلوببلز تُموَّه المعرّفات وتُحذف أرقام الهواتف."},
            "fields":[{"key":"redact_pii","label":{"en":"Hide ids and phone numbers from the model","ar":"أخفِ المعرّفات وأرقام الهواتف عن النموذج"},
            "kind":"toggle","value":true,"options":[],"min":null,"max":null,"hint":null,"default":false}]}]}""",
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

        open("/settings/account") { activity ->
            waitFor("account.page")
            waitFor("account.change_password")
            save(activity, "account-$name")
            compose.onNodeWithTag("account.page").performScrollToNode(hasTestTag("channels.card"))
            waitFor("channels.link")
            save(activity, "account-channels-$name")
        }
        open("/settings/display") { activity ->
            waitFor("display.hub")
            waitFor("display.text_scale")
            save(activity, "display-$name")
        }
        open("/settings/privacy") { activity ->
            waitFor("privacy.token.01K5DM000000000000000000T1")
            waitFor("privacy.redact")
            save(activity, "privacy-$name")
            compose.onNodeWithTag("privacy.revoke.01K5DM000000000000000000T1", useUnmergedTree = true).performClick()
            // The confirm asks first (a dialog is its own window, so it is not in the picture).
            waitFor("dialog.confirm")
        }
        open("/settings/notifications") { activity ->
            waitFor("notices.unread")
            waitFor("notice.01K5DM000000000000000000N1")
            save(activity, "inbox-$name")
            val before = hub.server.requestCount
            compose.onNodeWithTag("notice.01K5DM000000000000000000N2.toggle", useUnmergedTree = true).performClick()
            compose.waitUntil(5_000) { hub.server.requestCount > before }
            compose.waitForIdle()
            val asked = generateSequence { hub.server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .map { "${it.method} ${it.requestUrl?.encodedPath}" }.toList()
            assertTrue("the notice was marked read at the hub: $asked", asked.any { it.startsWith("PATCH") && it.endsWith("/01K5DM000000000000000000N2") })
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

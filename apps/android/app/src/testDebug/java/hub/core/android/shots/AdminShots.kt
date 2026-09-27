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
 * Apps batches 12 and 14 (admin): People (with the messaging links and the lockouts), Profiles and the
 * push senders on Device connections (their setup sheet opened), against the demo hub, light English
 * and dark Arabic, photographed to `apps/android/app/build/shots/admin/android-<page>-<theme>-<lang>.png`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi", application = ShotsApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminShots {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var hub: DemoHub
    private val out = File(repoRoot, "apps/android/app/build/shots/admin").apply { mkdirs() }

    private val me = "01K5DM00000000000000000001"
    private val stamp = """"created_at":"2026-09-26T08:00:00Z","updated_at":"2026-09-26T08:00:00Z""""
    private fun user(id: String, username: String, name: String, role: String, status: String, profiles: String) =
        """{"id":"$id","username":"$username","display_name":"$name","role":"$role","status":"$status","locale":"ar",
            "avatar":{"kind":"generated","url":null,"seed":"$username"},"profiles":$profiles,"default_profile":"work",$stamp,"last_login_at":null}"""

    private val answers = mapOf(
        "auth.listUsers" to """{"items":[
            ${user(me, "sara", "Sara", "owner", "active", """["work","personal"]""")},
            ${user("01K5DM00000000000000000002", "omar", "عمر", "admin", "active", """["work","personal"]""")},
            ${user("01K5DM00000000000000000003", "lina", "Lina", "member", "active", """["work"]""")},
            ${user("01K5DM00000000000000000004", "guest", "Guest", "member", "disabled", "[]")}],"next_cursor":null}""",
        "auth.listLockouts" to """{"items":[{"ip":"203.0.113.7","kind":"password","failures":5,"locked_until":"2026-09-27T11:30:00Z"}]}""",
        "auth.listChannelIdentities" to """{"items":[
            {"id":"01K5DM000000000000000000L1","user_id":"01K5DM00000000000000000003","platform":"telegram","sender_id":"123456789","linked_at":"2026-09-20T08:00:00Z","last_used_at":"2026-09-26T09:00:00Z"},
            {"id":"01K5DM000000000000000000L2","user_id":"$me","platform":"whatsapp","sender_id":"966500000000@s.whatsapp.net","linked_at":"2026-09-21T08:00:00Z","last_used_at":null}]}""",
        "devices.listPushSenders" to """{"items":[
            {"provider":"webpush","state":"ready","source":"generated","missing":[],"details":{"public_key":"BNc"},"devices":3,"last_error":null},
            {"provider":"fcm","state":"not_configured","source":"none","missing":["service_account"],"details":{},"devices":0,"last_error":null},
            {"provider":"apns","state":"ready","source":"settings","missing":[],"details":{"key_id":"ABCDE12345","team_id":"TEAM123456","bundle_id":"hub.core.app","environment":"production","private_key":"[stored]"},"devices":1,"last_error":null}]}""",
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
                user = StoredUser(me, "sara", "Sara", "owner", listOf("work", "personal"), "work"),
                profile = "work",
            ),
        )
        val name = "${theme.name.lowercase()}-${if (language == AppLanguage.AR) "ar" else "en"}"

        open("/settings/users") { activity ->
            waitFor("person.lina")
            save(activity, "people-$name")
            compose.onNodeWithTag("people.list").performScrollToNode(hasTestTag("people.lockouts"))
            waitFor("lockout.203.0.113.7")
            save(activity, "people-lockouts-$name")
        }
        open("/settings/workspaces") { activity ->
            waitFor("profiles.page")
            save(activity, "profiles-$name")
            compose.onNodeWithTag("profiles.add", useUnmergedTree = true).performClick()
            // A sheet is its own window (not in the picture): it is checked to open.
            waitFor("profile.origin")
        }
        open("/settings/devices") { activity ->
            compose.onNodeWithTag("devices.page").performScrollToNode(hasTestTag("push.senders"))
            waitFor("push.senders.toggle")
            compose.onNodeWithTag("push.senders.toggle", useUnmergedTree = true).performClick()
            waitFor("push.sender.apns.edit")
            compose.onNodeWithTag("devices.page").performScrollToNode(hasTestTag("push.senders"))
            save(activity, "push-senders-$name")
            compose.onNodeWithTag("push.sender.apns.edit", useUnmergedTree = true).performClick()
            waitFor("push.form.key_id")
        }
        val asked = generateSequence { hub.server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .map { "${it.method} ${it.requestUrl?.encodedPath}" }.toList()
        assertTrue("the lockouts were read: $asked", asked.any { it.startsWith("GET") && it.endsWith("/auth/lockouts") })
        assertTrue("the senders were read: $asked", asked.any { it.startsWith("GET") && it.endsWith("/push/senders") })
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

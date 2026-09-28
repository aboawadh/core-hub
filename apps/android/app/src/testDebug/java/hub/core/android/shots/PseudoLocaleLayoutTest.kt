package hub.core.android.shots

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.AppLanguage
import hub.core.android.MainActivity
import hub.core.android.data.StoredSession
import hub.core.android.data.StoredUser
import hub.core.android.data.TokenKind
import hub.core.android.graph
import hub.core.android.phone.VoiceSource
import hub.core.android.repoRoot
import hub.core.android.ui.theme.ThemeChoice
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main screens in the four test-only pseudo-locales (ADR 0028): `en-XA` (accented, at least
 * 40% longer), `ar-XB` (long right-to-left), `zh-XC` (full-width CJK) and `th-XD` (stacked Thai
 * marks). Their words exist in the debug build only (`generateSharedSources`), so no one can pick
 * them. On each screen every laid-out text is read back (`GetTextLayoutResult`) and the test fails
 * on text cut off without an ellipsis — a label with `maxLines` and no `TextOverflow.Ellipsis`,
 * or one squeezed out of its box — and on a wrapped short label that leaves a lone letter on its
 * last line. Pictures go to `apps/android/app/build/pseudo/<locale>/`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = ShotsApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PseudoLocaleLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var hub: DemoHub
    private val out = File(repoRoot, "apps/android/app/build/pseudo")

    @Before fun start() {
        hub = DemoHub(ExtraFixtures.answers).start()
    }

    @After fun stop() {
        hub.stop()
    }

    @Test fun `the pseudo-locales are there in the debug build and nowhere in a picker`() {
        for (code in listOf("en-XA", "ar-XB", "zh-XC", "th-XD")) {
            assertTrue(code, AppLanguage.of(code) != null)
            assertTrue(code, AppLanguage.entries.none { it.tag == code })
        }
        assertTrue(AppLanguage.of("ar-XB")!!.rtl)
        assertEquals("en", AppLanguage.of("en-XA")!!.hubLocale)
    }

    @Test fun enXA() = walk("en-XA")
    @Test fun arXB() = walk("ar-XB")
    @Test fun zhXC() = walk("zh-XC")
    @Test fun thXD() = walk("th-XD")

    private fun walk(code: String) {
        val graph = ApplicationProvider.getApplicationContext<android.content.Context>().graph
        graph.prefs.language = AppLanguage.of(code)
        graph.prefs.setTheme(ThemeChoice.LIGHT)
        graph.device.update { it.copy(voiceSource = VoiceSource.HUB) }
        graph.store.save(
            StoredSession(
                hub = hub.base, kind = TokenKind.APP, accessToken = "demo",
                user = StoredUser("01K5DM00000000000000000001", "sara", "Sara", "owner", listOf("work", "personal"), "work"),
                profile = "work",
            ),
        )
        val dir = File(out, code).apply { mkdirs() }
        val findings = mutableListOf<String>()
        val screens = listOf(
            Triple("/chat/${hub.chatId}", "message.agent", "chat"),
            Triple("/new", "screen.new_chat", "new-chat"),
            Triple("/agents", "agents.list", "agents"),
            Triple("/tasks", "tasks.board", "tasks"),
            Triple("/schedules", "schedules.list", "schedules"),
            Triple("/search", "screen.search", "search"),
            Triple("/settings", "settings.list", "settings"),
            Triple("/settings/this-device", "device.page", "this-device"),
            Triple("/settings/notifications", "notices.list", "notifications"),
        )
        for ((path, tag, name) in screens) {
            open(path) { activity ->
                waitFor(tag)
                save(activity, dir, name)
                findings += audit().map { "$path: $it" }
            }
        }
        graph.store.save(null)
        open(null) { activity ->
            waitFor("screen.sign_in")
            save(activity, dir, "sign-in")
            findings += audit().map { "sign-in: $it" }
        }
        assertTrue("layout findings in $code:\n" + findings.distinct().joinToString("\n"), findings.isEmpty())
    }

    private fun audit(): List<String> = TextAudit.of(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())

    private fun open(path: String?, block: (Activity) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
        if (path != null) intent.data = Uri.parse("corehub://open$path")
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

    private fun save(activity: Activity, dir: File, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

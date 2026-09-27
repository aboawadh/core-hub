package hub.core.android.shots

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Google Play listing's phone screenshots (docs/store/google/README.md): the real app against
 * the demo hub ([DemoHub], the iOS store shots' fixtures) — never a real hub — in the light theme,
 * English and Arabic. Play wants 9:16 and no transparency, so the phone is 440 × 782 dp at 3× (the iOS shots' width)
 * (1320 × 2346 px) and each picture is written without an alpha channel.
 *
 * The PNGs go to `apps/android/app/build/play-shots/<play locale>/NN-name.png`;
 * `node apps/android/scripts/play-listing.mjs --take-shots` copies them into
 * `apps/android/fastlane/metadata/android/<locale>/images/phoneScreenshots/`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h782dp-xxhdpi", application = ShotsApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayStoreShots {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var hub: DemoHub
    private val out = File(repoRoot, "apps/android/app/build/play-shots")

    @Before fun start() {
        hub = DemoHub(ExtraFixtures.answers).start()
    }

    @After fun stop() {
        hub.stop()
    }

    @Test fun english() = shoot(AppLanguage.EN, "en-US")
    @Test fun arabic() = shoot(AppLanguage.AR, "ar")

    private fun shoot(language: AppLanguage, locale: String) {
        val graph = ApplicationProvider.getApplicationContext<android.content.Context>().graph
        graph.prefs.language = language
        graph.prefs.setTheme(ThemeChoice.LIGHT)
        graph.device.update { it.copy(voiceSource = VoiceSource.HUB) }
        graph.store.save(
            StoredSession(
                hub = hub.base, kind = TokenKind.APP, accessToken = "demo",
                user = StoredUser("01K5DM00000000000000000001", "sara", "Sara", "owner", listOf("work", "personal"), "work"),
                profile = "work",
            ),
        )
        val dir = File(out, locale).apply { deleteRecursively(); mkdirs() }

        open("/chat/${hub.chatId}") { activity ->
            waitFor("message.agent")
            waitFor("topbar.subtitle")
            save(activity, dir, "01-chat")
            compose.onNodeWithTag("shell.menu").performClick()
            waitFor("chat.row.${hub.chatId}")
            save(activity, dir, "02-chats")
        }
        open("/tasks") { activity ->
            waitFor("tasks.board")
            save(activity, dir, "03-tasks")
        }
        open("/agents") { activity ->
            waitFor("agents.list")
            save(activity, dir, "04-agents")
        }
        open("/schedules") { activity ->
            waitFor("schedules.list")
            save(activity, dir, "05-schedules")
        }
        open("/new") { activity ->
            waitFor("screen.new_chat")
            waitForPrefix("chat.agent.")
            save(activity, dir, "06-new-chat")
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

    private fun tagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") { node ->
        node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith(prefix) == true
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        settle()
    }

    private fun waitForPrefix(prefix: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(tagPrefix(prefix), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        settle()
    }

    /** Let images, fonts and the last frames settle. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    /** Play takes 24-bit PNGs only: the bitmap is marked opaque, so the file has no alpha channel. */
    private fun save(activity: Activity, dir: File, name: String) {
        val view = activity.window.decorView
        assertEquals("Play's 9:16 phone size", 1320 to 2346, view.width to view.height)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK)
        view.draw(Canvas(bitmap))
        bitmap.setHasAlpha(false)
        assertFalse(bitmap.hasAlpha())
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

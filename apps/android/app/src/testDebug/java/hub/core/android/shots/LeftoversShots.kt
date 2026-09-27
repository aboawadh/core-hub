package hub.core.android.shots

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.AppLanguage
import hub.core.android.MainActivity
import hub.core.android.R
import hub.core.android.data.StoredSession
import hub.core.android.data.StoredUser
import hub.core.android.data.TokenKind
import hub.core.android.graph
import hub.core.android.repoRoot
import hub.core.android.ui.theme.ThemeChoice
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone leftovers of the apps night, against the demo hub, photographed to
 * `apps/android/app/build/shots/leftovers/android-<shot>-<theme>-<lang>.png`:
 * - Files → a file's «⋯» → «Attach to chat…»: the sheet (a new chat, this profile's recent chats);
 *   choosing one makes the attachment on the hub and the chat opens with it ready in the composer —
 *   nothing is downloaded to the phone or uploaded again;
 * - the Background button in the new chat's top bar (the contract's example has two running) and its sheet.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi", application = ShotsApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LeftoversShots {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var hub: DemoHub
    private val out = File(repoRoot, "apps/android/app/build/shots/leftovers").apply { mkdirs() }
    private val seen = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()

    private val folder = """{"profile":"work","path":"","truncated":false,
        "limits":{"max_upload_bytes":26214400,"max_edit_bytes":1048576,"max_archive_bytes":209715200,"max_archive_entries":20000},
        "entries":[{"name":"q3-summary.pdf","path":"q3-summary.pdf","kind":"file","link":false,"size_bytes":183422,
        "modified_at":"2026-09-26T14:05:00Z","mime":"application/pdf","editable":false}]}"""

    @Before fun start() {
        hub = DemoHub().start()
        hub.raw = { request ->
            seen += request
            val url = request.requestUrl!!
            if (request.method == "GET" && url.encodedPath.endsWith("/workspace-files")) {
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(folder)
            } else {
                null
            }
        }
    }

    @After fun stop() {
        hub.missed.sorted().forEach { println("demo hub: no answer for $it") }
        hub.stop()
    }

    @Test fun attachLightEnglish() = attach(ThemeChoice.LIGHT, AppLanguage.EN, recent = false)
    @Test fun attachDarkArabic() = attach(ThemeChoice.DARK, AppLanguage.AR, recent = true)
    @Test fun backgroundLightEnglish() = background(ThemeChoice.LIGHT, AppLanguage.EN)
    @Test fun backgroundDarkArabic() = background(ThemeChoice.DARK, AppLanguage.AR)

    private fun signIn(theme: ThemeChoice, language: AppLanguage): String {
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
        return "${theme.name.lowercase()}-${if (language == AppLanguage.AR) "ar" else "en"}"
    }

    private fun attach(theme: ThemeChoice, language: AppLanguage, recent: Boolean) {
        val name = signIn(theme, language)
        open("/settings/files") { activity ->
            waitFor("files.entry.q3-summary.pdf")
            compose.onNodeWithTag("files.entry.q3-summary.pdf.actions", useUnmergedTree = true).performClick()
            compose.onNodeWithText(activity.getString(R.string.files_page_attach)).performClick()
            waitFor("files.attach.new")
            waitFor(chatRow)
            saveSheet("attach-sheet-$name")
            if (recent) {
                compose.onAllNodes(chatRow, useUnmergedTree = true)[0].performClick()
                waitFor("screen.chat")
            } else {
                compose.onNodeWithTag("files.attach.new", useUnmergedTree = true).performClick()
                waitFor("screen.new_chat")
            }
            // The contract's example attachment (summary.pdf) waits in the composer, ready.
            waitFor("composer.attachments")
            compose.onNodeWithText("summary.pdf", useUnmergedTree = true).assertExists()
            save(activity, "attach-chat-$name")
            val paths = seen.map { "${it.method} ${it.requestUrl!!.encodedPath}" }
            assertTrue("the hub made the attachment: $paths", paths.any { it.startsWith("POST") && it.endsWith("/workspace-files/attach") })
            val attach = seen.first { it.requestUrl!!.encodedPath.endsWith("/workspace-files/attach") }
            assertEquals("work", attach.getHeader("X-Hub-Profile"))
            assertTrue(attach.body.clone().readUtf8().contains("\"q3-summary.pdf\""))
            assertFalse("nothing was downloaded: $paths", paths.any { it.endsWith("/workspace-files/content") })
            assertFalse("nothing was uploaded again: $paths", paths.any { it.startsWith("POST") && it.contains("/attachments") })
        }
    }

    private fun background(theme: ThemeChoice, language: AppLanguage) {
        val name = signIn(theme, language)
        open("/new") { activity ->
            waitFor("background.open")
            save(activity, "background-button-$name")
            compose.onNodeWithTag("background.open", useUnmergedTree = true).performClick()
            waitFor("background.sheet")
            saveSheet("background-sheet-$name")
            val asked = generateSequence { hub.server.takeRequest(1, TimeUnit.MILLISECONDS) }
                .filter { it.requestUrl?.encodedPath?.endsWith("/background") == true }.toList()
            assertTrue("the list covers every profile", asked.isNotEmpty() && asked.all { it.requestUrl!!.queryParameter("profiles") == "all" })
        }
    }

    private val chatRow = SemanticsMatcher("a recent chat") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("files.attach.chat.") == true }

    private fun open(path: String, block: (Activity) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).setData(Uri.parse("corehub://open$path"))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var activity: Activity? = null
            scenario.onActivity { activity = it }
            block(activity!!)
        }
    }

    private fun waitFor(tag: String) = waitFor(hasTestTag(tag))

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(10_000) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    /** A sheet is a window of its own: drawn from the latest one, not the activity's. */
    private fun saveSheet(name: String) {
        val window = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        window.draw(Canvas(bitmap))
        File(out, "android-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun save(activity: Activity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, "android-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

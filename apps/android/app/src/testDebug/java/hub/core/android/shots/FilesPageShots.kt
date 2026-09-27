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
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Apps batches 11 and 13: Settings → Files against the demo hub with a profile folder of its own,
 * light English and dark Arabic, photographed to
 * `apps/android/app/build/shots/files-page/android-<page>-<theme>-<lang>.png`: the top folder, then a
 * folder opened by a tap (the trail, «Up»); and checks the hub was asked for each folder in the profile.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi", application = ShotsApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilesPageShots {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var hub: DemoHub
    private val out = File(repoRoot, "apps/android/app/build/shots/files-page").apply { mkdirs() }

    private val limits = """"limits":{"max_upload_bytes":26214400,"max_edit_bytes":1048576,"max_archive_bytes":209715200,"max_archive_entries":20000}"""
    private fun entry(name: String, path: String, kind: String, size: Long?, mime: String?, editable: Boolean, link: Boolean = false) =
        """{"name":"$name","path":"$path","kind":"$kind","link":$link,"size_bytes":${size ?: "null"},"modified_at":"2026-09-26T14:05:00Z",
            "mime":${mime?.let { "\"$it\"" } ?: "null"},"editable":$editable}"""

    private val top = """{"profile":"work","path":"","truncated":false,$limits,"entries":[
        ${entry("reports", "reports", "directory", null, null, false)},
        ${entry("session-01K5DM", "session-01K5DM", "directory", null, null, false)},
        ${entry("خطة الإطلاق.md", "خطة الإطلاق.md", "file", 1834, "text/markdown", true)},
        ${entry("cover.png", "cover.png", "file", 482133, "image/png", false)},
        ${entry("budget-2026.xlsx", "budget-2026.xlsx", "file", 38912, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false)},
        ${entry("shared", "shared", "link", null, null, false, link = true)}]}"""

    private val reports = """{"profile":"work","path":"reports","truncated":false,$limits,"entries":[
        ${entry("2026", "reports/2026", "directory", null, null, false)},
        ${entry("q3-summary.pdf", "reports/q3-summary.pdf", "file", 183422, "application/pdf", false)},
        ${entry("notes.txt", "reports/notes.txt", "file", 212, "text/plain", true)}]}"""

    @Before fun start() {
        hub = DemoHub().start()
        hub.raw = { request ->
            val url = request.requestUrl!!
            if (request.method == "GET" && url.encodedPath.endsWith("/workspace-files")) {
                val body = if (url.queryParameter("path").isNullOrEmpty()) top else reports
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
            } else {
                null
            }
        }
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

        open("/settings/files") { activity ->
            waitFor("files.entry.cover.png")
            save(activity, "files-$name")
            compose.onNodeWithTag("files.entry.reports", useUnmergedTree = true).performClick()
            waitFor("files.entry.reports/q3-summary.pdf")
            waitFor("files.up")
            save(activity, "files-folder-$name")
            val asked = generateSequence { hub.server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .filter { it.requestUrl?.encodedPath?.endsWith("/workspace-files") == true }
                .map { "${it.requestUrl?.queryParameter("path").orEmpty()}@${it.getHeader("X-Hub-Profile")}" }.toList()
            assertTrue("each folder was read in the profile: $asked", asked.contains("@work") && asked.contains("reports@work"))
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

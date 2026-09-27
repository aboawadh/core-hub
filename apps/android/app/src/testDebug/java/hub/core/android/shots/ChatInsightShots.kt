package hub.core.android.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.chat.ChatInsight
import hub.core.android.repoRoot
import hub.core.android.ui.components.ContextRing
import hub.core.android.ui.components.DiffBody
import hub.core.android.ui.components.RunRow
import hub.core.android.ui.theme.CoreHubTheme
import hub.core.android.ui.theme.LocalTokens
import hub.core.android.ui.theme.ThemeChoice
import hub.core.client.model.ContextUsage
import hub.core.client.model.Money
import hub.core.client.model.Run
import hub.core.client.model.RunStatus
import hub.core.client.model.RunTrigger
import hub.core.client.model.Usage
import java.io.File
import java.time.OffsetDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chat insight's pieces (apps batch 6): the context ring in its three bands, two runs of the run
 * history, and a unified diff, photographed to `apps/android/app/build/shots/chat-insight/`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatInsightShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File(repoRoot, "apps/android/app/build/shots/chat-insight").apply { mkdirs() }
    private val base = OffsetDateTime.parse("2026-09-27T10:00:00Z")

    private fun run(id: String, status: RunStatus, input: Int, output: Int, cost: String?) = Run(
        id = id, profile = "work", ownerId = "u1", createdAt = base, updatedAt = base, sessionId = "s1", jobId = "j$id",
        status = status, trigger = RunTrigger(RunTrigger.Kind.USER), interrupted = false, model = "anthropic/claude-sonnet-4-5",
        usage = Usage(input, output, cost?.let { Money(it, "USD") }), startedAt = base, finishedAt = base.plusSeconds(83),
    )

    private fun shoot(theme: ThemeChoice, name: String) {
        val diff = ChatInsight.parseDiff("@@ -1,4 +1,5 @@\n # Notes\n-old line that the run replaced\n+the new line, long enough to scroll sideways on a phone screen\n+another line\n context\n")
        compose.setContent {
            CoreHubTheme(theme) {
                Column(
                    Modifier.fillMaxSize().background(LocalTokens.current.bg).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf(48_210, 150_000, 190_000).forEach { used ->
                            ContextRing(ChatInsight.use(ContextUsage(used, 200_000), null, emptyList())!!, 28.dp)
                        }
                    }
                    Text(ChatInsight.number(48_210) + " / " + ChatInsight.number(200_000), color = LocalTokens.current.text)
                    RunRow(run("r1", RunStatus.SUCCEEDED, 23_000, 4_100, "0.1310"))
                    RunRow(run("r2", RunStatus.FAILED, 1_200, 0, null))
                    DiffBody(diff)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("diff.body").assertExists()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height / 2, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun lightInsight() = shoot(ThemeChoice.LIGHT, "android-insight-light.png")

    @Test fun darkInsight() = shoot(ThemeChoice.DARK, "android-insight-dark.png")
}

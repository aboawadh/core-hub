package hub.core.android.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.chat.ChatControls
import hub.core.android.repoRoot
import hub.core.android.ui.components.ComposerChips
import hub.core.android.ui.theme.CoreHubTheme
import hub.core.android.ui.theme.LocalTokens
import hub.core.android.ui.theme.ThemeChoice
import hub.core.client.model.Choice
import hub.core.client.model.WorkingDir
import hub.core.client.model.WorkingDirs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer's chips (apps batch 1): drawn, each sheet opened and a choice made, and the row
 * photographed to `apps/android/app/build/shots/chat-controls/android-chips.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatControlsShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File(repoRoot, "apps/android/app/build/shots/chat-controls").apply { mkdirs() }

    @Test fun chipsOpenTheirSheetsAndChoose() {
        val models = listOf(
            ChatControls.ModelOption("openai/gpt-5", "GPT-5", "OpenAI"),
            ChatControls.ModelOption("anthropic/claude-sonnet", "Claude Sonnet", "Anthropic"),
        )
        val approval = ChatControls.ApprovalField("approvals", "approvals_mode", "smart", listOf(Choice("manual", "Manual"), Choice("smart", "Smart"), Choice("off", "Off")))
        val dirs = WorkingDirs("/var/lib/corehub/workspaces/work", listOf(WorkingDir("corehub", "/var/lib/corehub/workspaces/work/corehub")))
        var model: String? = "unset"
        var mode: String? = null
        var folder: String? = "unset"
        compose.setContent {
            CoreHubTheme(ThemeChoice.LIGHT) {
                Box(Modifier.fillMaxSize().background(LocalTokens.current.bg).padding(vertical = 16.dp)) {
                    ComposerChips(
                        models, true, "anthropic/claude-sonnet", { model = it }, approval, isAdmin = true, onApproval = { mode = it },
                        allowDefault = true, folder = null, dirs = dirs, onFolder = { folder = it },
                    )
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height / 6, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, "android-chips.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }

        compose.onNodeWithTag("composer.model").performClick()
        compose.onNodeWithTag("model.search").performTextReplacement("gpt")
        compose.onNodeWithTag("model.openai/gpt-5").performClick()
        compose.waitForIdle()
        assertEquals("openai/gpt-5", model)

        compose.onNodeWithTag("composer.approvals").performClick()
        compose.onNodeWithTag("approval.manual").performClick()
        compose.waitForIdle()
        assertEquals("manual", mode)

        compose.onNodeWithTag("composer.folder").performClick()
        compose.onNodeWithTag("folder.new").performTextReplacement("reports")
        compose.onNodeWithTag("folder.use").performClick()
        compose.waitForIdle()
        assertEquals("reports", folder)
    }
}

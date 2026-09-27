package hub.core.android.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.repoRoot
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.screens.MemoryCard
import hub.core.android.ui.screens.PluginRow
import hub.core.android.ui.screens.SkillLibraryCard
import hub.core.android.ui.screens.SkillRow
import hub.core.android.ui.screens.SkillRules
import hub.core.android.ui.theme.CoreHubTheme
import hub.core.android.ui.theme.LocalTokens
import hub.core.android.ui.theme.ThemeChoice
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.AgentPlugin
import hub.core.client.model.MemoryItem
import hub.core.client.model.Skill
import hub.core.client.model.SkillLibrary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Agents I (apps batch 8): the Core Hub library card, skill rows with their marks, a memory list with
 * its budget, and a plugin row, drawn to `apps/android/app/build/shots/agents/android-tools.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentToolsShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File(repoRoot, "apps/android/app/build/shots/agents").apply { mkdirs() }
    private val json = Serializer.kotlinxSerializationJson

    private fun skill(key: String, name: String, source: String, description: String?, library: String? = null, pinned: Boolean = false, enabled: Boolean = true) =
        json.decodeFromString(
            Skill.serializer(),
            """{"key":"$key","name":"$name","description":${description?.let { "\"$it\"" } ?: "null"},"enabled":$enabled,"pinned":$pinned,
                "source":"$source","use_count":0,"updated_at":null,"content":null,"library":${library?.let { "\"$it\"" } ?: "null"}}""",
        )

    private val memory = json.decodeFromString(
        MemoryItem.serializer(),
        """{"id":"memory","kind":"document","title":"MEMORY.md","content":"x","tags":[],"revision":7,
            "entries":["Prefers short answers","Writes commit messages in English","يحب الردود المختصرة"],"char_limit":120,"char_count":98}""",
    )

    private val plugin = json.decodeFromString(
        AgentPlugin.serializer(),
        """{"key":"chrome-profiles","name":"chrome-profiles","kind":"standalone","source":"user","status":"not_enabled","version":"0.3.1",
            "description":"Opens Chrome with a chosen profile.","author":null,"configured":true,"enabled":false,"manageable":true,"removable":true,
            "provides_tools":[],"provides_hooks":[],"requires_env":[],"entries":[]}""",
    )

    @Test fun libraryCardSkillsMemoryAndPlugin() {
        var opened: SkillRules.Action? = null
        var removed: Int? = null
        compose.setContent {
            CoreHubTheme(ThemeChoice.LIGHT) {
                Column(
                    Modifier.fillMaxSize().background(LocalTokens.current.bg).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SkillLibraryCard(SkillLibrary(enabled = true, available = 12, installed = 11, edited = 1), onSwitch = {}, onInstall = {})
                    GroupedList(title = "Your skills") {
                        Custom { SkillRow(skill("web-research", "Web research", "user", "Search and summarise with citations", pinned = true), {}, { opened = it }) }
                        Custom { SkillRow(skill("image-generate", "image-generate", "library", "Generate an image from a prompt", library = "edited"), {}, {}) }
                        Custom { SkillRow(skill("docker", "docker", "builtin", "Containers", enabled = false), {}, {}) }
                    }
                    MemoryCard(memory, onEdit = {}, onAdd = {}, onEditEntry = {}, onRemoveEntry = { removed = it })
                    PluginRow(plugin, busy = false, onSwitch = {}, onRemove = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("skills.library.install").assertIsDisplayed()
        compose.onNodeWithTag("memory.budget").assertIsDisplayed()
        compose.onNodeWithTag("plugin.chrome-profiles.remove").assertIsDisplayed()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, "android-tools.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }

        compose.onNodeWithTag("skill.web-research").performClick()
        compose.waitForIdle()
        assertEquals(SkillRules.Action.OPEN, opened)
        compose.onNodeWithTag("memory.memory.entry.1.more").performClick()
        compose.onNodeWithTag("memory.memory.entry.1.remove").performClick()
        compose.waitForIdle()
        assertEquals(1, removed)
    }
}

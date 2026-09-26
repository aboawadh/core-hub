package hub.core.android.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.repoRoot
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.FormBody
import hub.core.android.ui.components.FormField
import hub.core.android.ui.components.FormKind
import hub.core.android.ui.components.FormOption
import hub.core.android.ui.components.ListFilter
import hub.core.android.ui.components.ListPage
import hub.core.android.ui.components.ListScaffold
import hub.core.android.ui.components.ListRow
import hub.core.android.ui.components.RowAction
import hub.core.android.ui.components.TextEditorBody
import hub.core.android.ui.components.TriggerDraft
import hub.core.android.ui.components.TriggerEditor
import hub.core.android.ui.components.deleteTitle
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.components.rememberPagedList
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.CoreHubTheme
import hub.core.android.ui.theme.LocalTokens
import hub.core.android.ui.theme.ThemeChoice
import hub.core.client.model.ScheduleTrigger
import java.io.File
import java.math.BigDecimal
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared page pieces the night's batches build with (docs/clients/phone-pages.md): each is
 * drawn, poked, and photographed to `apps/android/app/build/shots/page-kit/android-<name>.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageKitShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File(repoRoot, "apps/android/app/build/shots/page-kit").apply { mkdirs() }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        CoreHubTheme(ThemeChoice.LIGHT) {
            Box(Modifier.fillMaxSize().background(LocalTokens.current.bg).padding(16.dp).testTag("shot")) { content() }
        }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, "android-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun formSheet() {
        var saved: Map<String, String>? = null
        val fields = listOf(
            FormField("name", "Name", required = true, placeholder = "Daily report"),
            FormField("prompt", "Prompt", FormKind.Multiline, help = "What the agent is asked each time."),
            FormField("port", "Port", FormKind.Number, integer = true, min = BigDecimal.ONE, max = BigDecimal(65535)),
            FormField("on", "Enabled", FormKind.Toggle),
            FormField("mode", "Mode", FormKind.Choice, options = listOf(FormOption("ask", "Ask"), FormOption("auto", "Auto"))),
            FormField("key", "API key", FormKind.Secret, required = true),
        )
        show { FormBody(fields, mapOf("on" to "true", "mode" to "ask", "port" to "0"), onDone = {}, onSave = { saved = it; Result.success(Unit) }) }
        compose.onNodeWithTag("form.save").performClick()
        compose.onAllNodesWithText("Required").assertCountEquals(2)
        compose.onNodeWithText("At least 1").assertExists()
        save("form-problems")
        compose.onNodeWithTag("form.name").performTextReplacement("Daily report")
        compose.onNodeWithTag("form.port").performTextReplacement("8080")
        compose.onNodeWithTag("form.key").performTextReplacement("sk-123")
        compose.onNodeWithTag("form.key.reveal").performClick()
        save("form-filled")
        compose.onNodeWithTag("form.save").performClick()
        compose.waitForIdle()
        assertEquals("Daily report", saved?.get("name"))
        assertEquals("8080", saved?.get("port"))
        assertEquals("true", saved?.get("on"))
    }

    @Test fun listScaffold() {
        val all = (1..30).map { "Item $it" }
        var deleted: String? = null
        show {
            var query by remember { mutableStateOf("") }
            var filter by remember { mutableStateOf<String?>(null) }
            val list = rememberPagedList(query, filter, key = { it: String -> it }) { cursor ->
                val start = cursor?.toInt() ?: 0
                val matching = all.filter { it.contains(query, ignoreCase = true) && (filter == null || it.endsWith("0")) }
                ListPage(matching.drop(start).take(10), (start + 10).takeIf { it < matching.size }?.toString())
            }
            val confirm = rememberConfirmDelete<String>()
            ListScaffold(
                list, key = { it }, query = query, onQuery = { query = it },
                filters = listOf(ListFilter(null, "All"), ListFilter("tens", "Tens")), filter = filter, onFilter = { filter = it },
                emptyIcon = Lucide.Inbox,
                actions = { item -> listOf(RowAction("Delete", Lucide.Trash, danger = true) { confirm.ask(item) }) },
                swipeAction = { item -> RowAction("Delete", Lucide.Trash, danger = true) { confirm.ask(item) } },
            ) { item -> ListRow(item, subtitle = "A row of the list") }
            ConfirmDeleteDialog(confirm, { deleteTitle(it) }, onDelete = { deleted = it; list.remove(it); Result.success(Unit) })
        }
        compose.onNodeWithText("Item 1").assertExists()
        save("list")
        compose.onNodeWithTag("list.filter.tens").performClick()
        compose.onNodeWithText("Item 10").assertExists()
        compose.onNodeWithTag("list.search").performTextReplacement("nothing")
        compose.onNodeWithTag("list.empty").assertExists()
        save("list-empty")
    }

    @Test fun confirmDelete() {
        var deleted = false
        show {
            val confirm = rememberConfirmDelete<String>()
            remember { confirm.ask("Daily report"); 0 }
            ConfirmDeleteDialog(confirm, { deleteTitle(it) }, onDelete = { deleted = true; Result.success(Unit) })
        }
        compose.onNodeWithText("Delete “Daily report”?").assertExists()
        save("confirm-delete")
        compose.onNodeWithTag("dialog.confirm").performClick()
        compose.waitForIdle()
        assertTrue(deleted)
    }

    @Test fun textEditor() {
        var saved: String? = null
        show { Column { TextEditorBody("# Notes\n\nThe **first** line.", onDone = {}, onSave = { saved = it; Result.success(Unit) }) } }
        save("editor-edit")
        compose.onNodeWithTag("editor.preview").performClick()
        save("editor-preview")
        compose.onNodeWithTag("editor.preview").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("editor.text").performTextReplacement("# Notes\n\nChanged.")
        compose.onNodeWithTag("editor.save").performClick()
        compose.waitForIdle()
        assertEquals("# Notes\n\nChanged.", saved)
    }

    @Test fun triggerEditor() {
        var last: TriggerDraft? = null
        show {
            var draft by remember { mutableStateOf(TriggerDraft(timezone = "Asia/Riyadh")) }
            Column(Modifier.fillMaxWidth().height(700.dp)) {
                TriggerEditor(draft, { draft = it; last = it }, preview = { listOf(OffsetDateTime.parse("2026-09-28T06:00:00Z"), OffsetDateTime.parse("2026-09-29T06:00:00Z")) })
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        save("trigger-cron")
        compose.onNodeWithTag("trigger.kind.interval").performClick()
        compose.onNodeWithTag("trigger.every").performTextReplacement("3")
        assertEquals(ScheduleTrigger.Kind.INTERVAL, last?.kind)
        save("trigger-every")
        compose.onNodeWithTag("trigger.kind.once").performClick()
        compose.onNodeWithTag("trigger.date").performTextReplacement("2026-10-01")
        save("trigger-once")
        assertEquals("2026-10-01", last?.date)
    }
}

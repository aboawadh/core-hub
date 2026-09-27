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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.repoRoot
import hub.core.android.ui.screens.CheckLines
import hub.core.android.ui.screens.CheckLinesPart
import hub.core.android.ui.screens.CommentsPart
import hub.core.android.ui.screens.HermesHistoryPart
import hub.core.android.ui.screens.SubtasksPart
import hub.core.android.ui.screens.TaskCard
import hub.core.android.ui.theme.CoreHubTheme
import hub.core.android.ui.theme.LocalTokens
import hub.core.android.ui.theme.ThemeChoice
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.HermesCardHistory
import hub.core.client.model.Subtask
import hub.core.client.model.TaskCheckItem
import hub.core.client.model.TaskDetail
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tasks II (batch 5): a task's checklist, definition of done, constraints and comments, a Hermes
 * card's history, and a card ticked while the board selects — drawn, poked, and photographed to
 * `apps/android/app/build/shots/tasks/android-<name>.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TasksTwoShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File(repoRoot, "apps/android/app/build/shots/tasks").apply { mkdirs() }
    private val json = Serializer.kotlinxSerializationJson

    private val detail = json.decodeFromString(
        TaskDetail.serializer(),
        """{"id":"T1","profile":"work","owner_id":"me","created_at":"2026-09-20T09:00:00Z","updated_at":"2026-09-21T10:00:00Z",
        "project_id":"P1","title":"Add the settings page to the phone","description":null,"status":"review","priority":"high","tags":[],
        "assignee":null,"auto_start":true,"position":"a0","blocked_reason":null,"status_reason":null,"subtask_counts":{"total":3,"done":1},
        "depends_on":[],"waiting_on":[],"stuck_since":null,"worktree":null,"session_id":null,"last_run":{"id":null,"status":null,"finished_at":null},
        "attempt_count":1,"latest_summary":null,"due_at":null,"started_at":null,"completed_at":null,"archived_at":null,"attachment_ids":[],
        "definition_of_done":[{"text":"Every tab opens on the phone","checked":true},{"text":"Arabic and English strings","checked":false}],
        "constraints":[{"text":"No new dependencies","checked":false}],
        "subtasks":[
          {"id":"S1","task_id":"T1","index":0,"title":"Account tab","status":"done","note":null,"blocked_reason":null,"completed_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z"},
          {"id":"S2","task_id":"T1","index":1,"title":"Display tab","status":"todo","note":null,"blocked_reason":null,"completed_at":null,"updated_at":"2026-09-21T10:00:00Z"},
          {"id":"S3","task_id":"T1","index":2,"title":"تبويب الخصوصية","status":"todo","note":null,"blocked_reason":null,"completed_at":null,"updated_at":"2026-09-21T10:00:00Z"}],
        "comments":[
          {"id":"C1","task_id":"T1","author":{"kind":"agent","id":"A1","name":"Claude Code"},"content":"Account tab is done; **Display** next.","created_at":"2026-09-21T10:00:00Z"},
          {"id":"C2","task_id":"T1","author":{"kind":"user","id":"me","name":"Sara"},"content":"شكرًا، كمّل.","created_at":"2026-09-21T10:05:00Z"}],
        "runs":[]}""",
    )

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, "android-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun lists(
        theme: ThemeChoice,
        onTick: (Subtask) -> Unit = {},
        onAdd: (String) -> Unit = {},
        onDelete: (Subtask) -> Unit = {},
        onDone: (List<TaskCheckItem>) -> Unit = {},
        onSay: (String) -> Unit = {},
    ) = compose.setContent {
        CoreHubTheme(theme) {
            Column(
                Modifier.fillMaxSize().background(LocalTokens.current.bgRaised).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SubtasksPart(detail.subtasks, editable = true, onTick = onTick, onAdd = { onAdd(it); true }, onDelete = onDelete, onReorder = {})
                CheckLinesPart(CheckLines.Kind.DONE, detail.definitionOfDone.orEmpty(), reviewing = true, enabled = true, onSave = onDone)
                CheckLinesPart(CheckLines.Kind.CONSTRAINTS, detail.constraints.orEmpty(), reviewing = true, enabled = true) {}
                CommentsPart(detail.comments) { onSay(it); true }
            }
        }
    }

    @Test fun lists() {
        var ticked: Subtask? = null
        var added: String? = null
        var deleted: Subtask? = null
        var done: List<TaskCheckItem>? = null
        var said: String? = null
        lists(ThemeChoice.LIGHT, { ticked = it }, { added = it }, { deleted = it }, { done = it }, { said = it })
        compose.onNodeWithTag("task.subtask.grip.0").assertExists()
        save("lists")
        compose.onNodeWithTag("task.subtask.tick.1").performClick()
        assertEquals("S2", ticked?.id)
        compose.onNodeWithTag("task.subtask.input").performTextInput("Privacy tab")
        compose.onNodeWithTag("task.subtask.add").performClick()
        compose.waitForIdle()
        assertEquals("Privacy tab", added)
        compose.onNodeWithTag("task.subtask.delete.2").performClick()
        assertEquals("S3", deleted?.id)
        // In review the reviewer ticks the definition of done; the whole list is saved.
        compose.onNodeWithTag("task.dod.tick.1").performScrollTo().performClick()
        assertEquals(listOf(true, true), done?.map { it.checked })
        compose.onNodeWithTag("task.comment.input").performScrollTo().performTextInput("On it")
        compose.onNodeWithTag("task.comment.send").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("On it", said)
    }

    @Test fun listsDark() {
        lists(ThemeChoice.DARK)
        compose.onNodeWithTag("task.comments").assertExists()
        save("lists-dark")
    }

    @Test fun hermesHistory() {
        val history = json.decodeFromString(
            HermesCardHistory.serializer(),
            """{"runs":[{"id":2,"profile":"coder","status":"done","outcome":"completed","summary":"Opened the pull request.","error":null,
            "started_at":"2026-09-21T09:00:00Z","ended_at":"2026-09-21T09:40:00Z"}],
            "events":[{"id":7,"kind":"completed","run_id":2,"payload":null,"created_at":"2026-09-21T09:40:00Z"},
            {"id":6,"kind":"claimed","run_id":2,"payload":null,"created_at":"2026-09-21T09:00:00Z"}]}""",
        )
        compose.setContent {
            CoreHubTheme(ThemeChoice.LIGHT) {
                Column(Modifier.fillMaxSize().background(LocalTokens.current.bgRaised).padding(16.dp)) { HermesHistoryPart(history) }
            }
        }
        compose.onNodeWithTag("task.hermes.runs").assertExists()
        compose.onNodeWithTag("task.hermes.events").assertExists()
        save("hermes-history")
    }

    @Test fun selectedCard() {
        val task = json.decodeFromString(hub.core.client.model.Task.serializer(), json.encodeToString(TaskDetail.serializer(), detail))
        compose.setContent {
            CoreHubTheme(ThemeChoice.LIGHT) {
                Column(Modifier.fillMaxSize().background(LocalTokens.current.bg).padding(16.dp)) {
                    TaskCard(task, badges = false, profileName = { it }, onClick = {}, selected = true)
                }
            }
        }
        compose.onNodeWithTag("task.tick.T1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("task.subtasks.T1", useUnmergedTree = true).assertExists()
        save("card-selected")
    }
}

package hub.core.android.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.repoRoot
import hub.core.android.ui.screens.BoardRules
import hub.core.android.ui.screens.TaskDetailBody
import hub.core.android.ui.screens.TaskFacts
import hub.core.android.ui.theme.CoreHubTheme
import hub.core.android.ui.theme.LocalTokens
import hub.core.android.ui.theme.ThemeChoice
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.TaskDetail
import hub.core.client.model.TaskStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A task opened on its own (batch 2, Tasks I), drawn from a hub answer, poked, and photographed
 * to `apps/android/app/build/shots/tasks/android-<name>.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskDetailShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File(repoRoot, "apps/android/app/build/shots/tasks").apply { mkdirs() }

    private val detail = Serializer.kotlinxSerializationJson.decodeFromString(
        TaskDetail.serializer(),
        """{"id":"01J8QK3ZR2W7M5N4P6T8V9X0TK","profile":"work","owner_id":"01J8QK3ZR2W7M5N4P6T8V9X0HM",
        "created_at":"2026-09-20T09:00:00Z","updated_at":"2026-09-21T10:00:00Z","project_id":"01J8QK3ZR2W7M5N4P6T8V9X0PJ",
        "title":"Add the settings page to the phone","description":"Build the **settings tabs** as in the navigation map.\n\n- Account\n- Display",
        "status":"running","priority":"high","tags":[],"assignee":{"kind":"agent","id":"01J8QK3ZR2W7M5N4P6T8V9X0AC","name":"Claude Code"},
        "auto_start":false,"position":"a0","blocked_reason":null,"status_reason":null,"subtask_counts":{"total":0,"done":0},
        "depends_on":["01J8QK3ZR2W7M5N4P6T8V9X0D1","01J8QK3ZR2W7M5N4P6T8V9X0D2"],"waiting_on":[],"stuck_since":null,"worktree":null,
        "session_id":"01J8QK3ZR2W7M5N4P6T8V9X0YE","last_run":{"id":null,"status":null,"finished_at":null},"attempt_count":1,
        "latest_summary":"Made the branch and started on the account tab.","due_at":"2026-09-30T15:00:00Z","started_at":null,
        "completed_at":null,"archived_at":null,"attachment_ids":[],"subtasks":[],"comments":[],
        "runs":[{"id":"01J8QK3ZR2W7M5N4P6T8V9X0RS","profile":"work","owner_id":"01J8QK3ZR2W7M5N4P6T8V9X0HM",
        "created_at":"2026-09-21T09:30:00Z","updated_at":"2026-09-21T10:00:00Z","session_id":"01J8QK3ZR2W7M5N4P6T8V9X0YE",
        "room_id":null,"seat_id":null,"job_id":"01J8QK3ZR2W7M5N4P6T8V9X0JT","status":"running","queue_position":null,
        "trigger":{"kind":"task","id":"01J8QK3ZR2W7M5N4P6T8V9X0TK"},"input_message_id":null,"output_message_id":null,"model":null,
        "provider":null,"reasoning_effort":null,"interrupted":false,"error":null,"usage":null,"started_at":"2026-09-21T09:30:01Z","finished_at":null}]}""",
    )

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, "android-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun draw(theme: ThemeChoice, onMove: (BoardRules.Drop) -> Unit = {}) = compose.setContent {
        CoreHubTheme(theme) {
            Box(Modifier.fillMaxSize().background(LocalTokens.current.bgRaised).padding(16.dp)) {
                TaskDetailBody(
                    TaskFacts.of(detail), detail, "Core Hub", showProfile = true, profileName = { "Work" }, busy = false, error = null, notice = null,
                    onMove = onMove, onStop = {}, onAssign = {}, onUnassign = {}, onEdit = {}, onDelete = {}, onOpenChat = {},
                )
            }
        }
    }

    @Test fun detail() {
        var moved: BoardRules.Drop? = null
        draw(ThemeChoice.LIGHT) { moved = it }
        compose.onNodeWithTag("task.detail.title").assertExists()
        compose.onNodeWithText("Claude Code · agent").assertExists()
        compose.onNodeWithTag("task.stop").assertExists()
        compose.onNodeWithTag("task.unassign").assertExists()
        compose.onNodeWithText("2 of them done").assertExists()
        save("detail")
        compose.onNodeWithTag("task.move").performClick()
        compose.onNodeWithTag("task.move.review").performClick()
        compose.waitForIdle()
        assertEquals(TaskStatus.REVIEW, moved?.to)
    }

    @Test fun detailDark() {
        draw(ThemeChoice.DARK)
        compose.onNodeWithTag("task.detail.description").assertExists()
        save("detail-dark")
    }
}

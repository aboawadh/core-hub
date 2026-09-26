package hub.core.android.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.repoRoot
import hub.core.android.rooms.RoomManage
import hub.core.android.rooms.RoomState
import hub.core.android.ui.components.FormBody
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.screens.HandoffStrip
import hub.core.android.ui.screens.RoomMemoryCard
import hub.core.android.ui.theme.CoreHubTheme
import hub.core.android.ui.theme.LocalTokens
import hub.core.android.ui.theme.ThemeChoice
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.HandoffChain
import hub.core.client.model.RoomMemory
import hub.core.client.model.Seat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Managing a room (apps batch 7): the handoff strip with its one more round, the summary card, and
 * the seat form, drawn and photographed to `apps/android/app/build/shots/rooms/android-manage.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoomManageShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File(repoRoot, "apps/android/app/build/shots/rooms").apply { mkdirs() }
    private val json = Serializer.kotlinxSerializationJson
    private val room = "01J8QK3ZR2W7M5N4P6T8V9X0RM"
    private val planner = "01J8QK3ZR2W7M5N4P6T8V9X0ST"
    private val coder = "01J8QK3ZR2W7M5N4P6T8V9X0SU"

    private fun seat(id: String, name: String) = json.decodeFromString(
        Seat.serializer(),
        """{"id":"$id","room_id":"$room","agent_id":"01J8QK3ZR2W7M5N4P6T8V9X0AG","name":"$name","description":"Plans the work",
            "avatar":{"kind":"generated","url":null,"seed":"s"},"model":null,"provider":null,"reasoning_effort":null,
            "instructions":null,"preset_id":null,"status":"idle","executor":{"kind":"server","device_id":null},
            "created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z"}""",
    )

    private val stopped = json.decodeFromString(
        HandoffChain.serializer(),
        """{"id":"c1","room_id":"$room","from_seat_id":"$planner","to_seat_id":"$coder","status":"stopped","stop_reason":"max_depth",
            "depth":3,"max_depth":3,"continue_used":false,"error":null,"updated_at":"2026-09-21T10:00:00Z"}""",
    )

    @Test fun stripSummaryAndSeatForm() {
        val state = RoomState(seats = listOf(seat(planner, "Planner"), seat(coder, "Coder")), handoffs = listOf(stopped))
        val memory = RoomMemory(status = RoomMemory.Status.IDLE, summarizedTurnCount = 12, summary = "We ship on Friday. Sara owns the release notes.")
        val labels = RoomManage.SeatLabels(
            "Agent", "Name in the room", "People mention it as @name.", "Empty for the agent's own name.", "Role",
            "Instructions", "Given to this agent on every turn in this room.", "Model", "Leave empty for the agent's own model.",
        )
        var continued: String? = null
        compose.setContent {
            CoreHubTheme(ThemeChoice.LIGHT) {
                Column(
                    Modifier.fillMaxSize().background(LocalTokens.current.bg).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HandoffStrip(state) { continued = it }
                    HubCard(padding = 12.dp) { RoomMemoryCard(memory, canManage = true, onRefresh = {}, onSave = { Result.success(Unit) }) }
                    FormBody(
                        RoomManage.seatFields(false, emptyList(), labels),
                        RoomManage.seatValues(seat(planner, "Planner"), emptyList()),
                        onDone = {}, onSave = { Result.success(Unit) }, tag = "room.seat",
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("room.handoff.stopped").assertIsDisplayed()
        compose.onNodeWithTag("room.memory.refresh").assertIsDisplayed()
        compose.onNodeWithTag("room.seat.name").assertIsDisplayed()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(out, "android-manage.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }

        compose.onNodeWithTag("room.handoff.continue").performClick()
        compose.waitForIdle()
        assertEquals("c1", continued)
    }
}

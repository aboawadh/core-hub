package hub.core.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.chat.Outgoing
import hub.core.android.chat.SlashCommands
import hub.core.android.ui.screens.MessageQueueRules
import hub.core.android.ui.screens.MessageQueueStrip
import hub.core.android.ui.screens.SlashMenu
import hub.core.android.ui.screens.TrajectoryBody
import hub.core.android.ui.theme.CoreHubTheme
import hub.core.android.ui.theme.ThemeChoice
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.AgentCapability
import hub.core.client.model.Trajectory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The follow-ups rendered: the `/` menu and its skills, the message queue strip, a timeline bar opening its step. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FollowUpUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `typing slash offers the commands, picking one writes it, and after skill the skills come`() {
        var draft by mutableStateOf("/")
        var ran = ""
        val skills = listOf(SlashCommands.SkillChoice("code-review", "Code review", "Reviews a diff"))
        compose.setContent {
            CoreHubTheme(ThemeChoice.LIGHT) {
                SlashMenu(
                    draft, SlashCommands.available(listOf(AgentCapability.SKILL_COMMANDS, AgentCapability.COMPRESS)), skills,
                    onCommand = { c -> SlashCommands.picked(c)?.let { draft = it } ?: run { ran = c.name } },
                    onSkill = { draft = SlashCommands.pickSkill(it) },
                )
            }
        }
        compose.onNodeWithTag("chat.slash.command.compress").assertExists()
        compose.onNodeWithTag("chat.slash.command.fork").performScrollTo().performClick()
        assertEquals("a command without words runs at once", "fork", ran)
        compose.onNodeWithTag("chat.slash.command.skill").performScrollTo().performClick()
        assertEquals("/skill ", draft)
        compose.waitForIdle()
        compose.onNodeWithTag("chat.slash.skills").assertExists()
        compose.onNodeWithTag("chat.slash.skill.code-review").performClick()
        assertEquals("/skill code-review ", draft)
        compose.waitForIdle()
        compose.onNodeWithTag("chat.slash.skills").assertDoesNotExist()
    }

    @Test fun `each waiting message can be sent now, steer the turn, or be removed`() {
        val items = listOf(MessageQueueRules.queued(Outgoing("first thing"), null), MessageQueueRules.queued(Outgoing("second"), null))
        val done = mutableListOf<String>()
        compose.setContent {
            CoreHubTheme(ThemeChoice.DARK) {
                MessageQueueStrip(items, onSendNow = { done += "now:" + it.preview }, onSteer = { done += "steer:" + it.preview }, onRemove = { done += "remove:" + it.preview })
            }
        }
        compose.onNodeWithText("first thing").assertExists()
        compose.onNodeWithTag("chat.queue.send_now.0").performClick()
        compose.onNodeWithTag("chat.queue.steer.1").performClick()
        compose.onNodeWithTag("chat.queue.remove.1").performClick()
        assertEquals(listOf("now:first thing", "steer:second", "remove:second"), done)
    }

    @Test fun `a tap on a timeline bar opens its step`() {
        val at = "2026-09-27T10:00:00Z"
        val trajectory = Serializer.kotlinxSerializationJson.decodeFromString(
            Trajectory.serializer(),
            """{"session_id":"01J8QK3ZR2W7M5N4P6T8V9X0S1","generated_at":"$at","live":false,"timing":"full","started_at":"$at","ended_at":"2026-09-27T10:00:10Z",
                "steps":[
                  {"id":"s1","kind":"tool","lane":"tools","exchange":1,"status":"succeeded","tool_call_only":false,"started_at":"$at","ended_at":"2026-09-27T10:00:10Z","duration_ms":10000,
                   "tool_call":{"id":"c1","name":"web_search","status":"succeeded","output_truncated":false,"preview":"cats","output":"found the answer"}}],
                "metrics":{"exchanges":1,"turns":0,"steps":1,"tool_calls":1,"failed_tool_calls":0}}""",
        )
        compose.setContent { CoreHubTheme(ThemeChoice.LIGHT) { TrajectoryBody(trajectory, now = 0) } }
        compose.onNodeWithText("found the answer").assertDoesNotExist()
        compose.onNodeWithTag("trajectory.lane.tools").performTouchInput { click(center) }
        compose.onNodeWithText("found the answer").assertExists()
    }
}

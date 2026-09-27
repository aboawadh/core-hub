package hub.core.android.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.ui.screens.PeerCard
import hub.core.android.ui.screens.ReleaseCard
import hub.core.android.ui.screens.RuntimeCardView
import hub.core.android.ui.screens.TrajectoryBody
import hub.core.android.ui.screens.UpdateSourceCard
import hub.core.android.ui.theme.CoreHubTheme
import hub.core.android.ui.theme.ThemeChoice
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Peer
import hub.core.client.model.Release
import hub.core.client.model.RuntimeReport
import hub.core.client.model.Trajectory
import hub.core.client.model.UpdateSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The pages the phone draws itself since 2026-09-27, rendered: the hub's update shelf, a linked
 * hub's card, the Runtime card and the trajectory (SelfSufficientTest checks their rules and calls).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SelfSufficientUiTest {
    @get:Rule val compose = createComposeRule()
    private val json = Serializer.kotlinxSerializationJson
    private val at = "2026-09-27T10:00:00Z"

    @Test fun `the update source shows the repository and token fields once it reads from GitHub`() {
        val settings = json.decodeFromString(
            UpdateSettings.serializer(),
            """{"default_channel":"stable","source":{"kind":"github_release","repo":"twuijri/core-hub","token":null},"auto_publish":false}""",
        )
        compose.setContent { CoreHubTheme(ThemeChoice.LIGHT) { UpdateSourceCard(settings, ops = null, onSaved = {}) } }
        compose.onNodeWithTag("updates.repo").assertExists()
        compose.onNodeWithTag("updates.repo.save").assertIsNotEnabled()
        compose.onNodeWithTag("updates.repo").performTextReplacement("twuijri/other")
        compose.onNodeWithTag("updates.repo.save").assertIsEnabled()
        compose.onNodeWithTag("updates.token.save").assertIsNotEnabled()
        compose.onNodeWithTag("updates.token").performTextReplacement("ghp_x")
        compose.onNodeWithTag("updates.token.save").assertIsEnabled()
    }

    @Test fun `a manual shelf hides the repository fields`() {
        val settings = json.decodeFromString(
            UpdateSettings.serializer(), """{"default_channel":"test","source":{"kind":"manual"},"auto_publish":false}""",
        )
        compose.setContent { CoreHubTheme(ThemeChoice.DARK) { UpdateSourceCard(settings, ops = null, onSaved = {}) } }
        compose.onNodeWithTag("updates.from_source").assertExists()
        compose.onNodeWithTag("updates.repo").assertDoesNotExist()
    }

    @Test fun `a build on the shelf names its version and offers Delete`() {
        var deleted = 0
        val release = json.decodeFromString(
            Release.serializer(),
            """{"id":"R1","platform":"android","channel":"test","version":"1.1.4","build":104,"notes":{"ar":"ع","en":"e"},"size_bytes":5051877,
                "sha256":"abc","mandatory":true,"download_url":"https://x.example/a.apk","published_at":"$at"}""",
        )
        compose.setContent { CoreHubTheme(ThemeChoice.LIGHT) { ReleaseCard(release) { deleted++ } } }
        compose.onNodeWithText("1.1.4 (104)").assertExists()
        compose.onNodeWithTag("release.R1.actions").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }

    @Test fun `a hub waiting for this owner is approved or refused, and cannot be asked yet`() {
        var unlinked = 0
        val peer = json.decodeFromString(
            Peer.serializer(),
            """{"id":"P1","name":"Office","hub_name":"Office hub","url":"https://office.example","direction":"inbound","status":"pending",
                "enabled":true,"fingerprint":"ab:cd","asks_per_hour":20,"created_at":"$at"}""",
        )
        compose.setContent { CoreHubTheme(ThemeChoice.LIGHT) { PeerCard(peer, ops = null, onChanged = {}, onUnlink = { unlinked++ }) } }
        compose.onNodeWithTag("peer.P1.approve").assertExists()
        compose.onNodeWithTag("peer.P1.agents").assertIsNotEnabled()
        compose.onNodeWithText("Refuse").performClick()
        assertEquals(1, unlinked)
        compose.onNodeWithTag("peer.P1.limit.save").assertIsNotEnabled()
        compose.onNodeWithTag("peer.P1.limit").performTextReplacement("50")
        compose.onNodeWithTag("peer.P1.limit.save").assertIsEnabled()
    }

    @Test fun `the Runtime card opens by itself on a failure and offers the restart`() {
        val failing = json.decodeFromString(
            RuntimeReport.serializer(),
            """{"agent":"hermes","mode":"managed","ready":false,"checks":[{"id":"runtime_writable","ok":true},{"id":"gateway_reloaded","ok":false}]}""",
        )
        var restarts = 0
        compose.setContent { CoreHubTheme(ThemeChoice.LIGHT) { RuntimeCardView(failing, canRestart = true) { restarts++ } } }
        compose.onNodeWithTag("runtime.check.gateway_reloaded").assertExists()
        compose.onNodeWithTag("runtime.restart").performClick()
        assertEquals(1, restarts)
    }

    @Test fun `all checks passing is one line that opens the list`() {
        val fine = json.decodeFromString(
            RuntimeReport.serializer(),
            """{"agent":"hermes","mode":"managed","ready":true,"checks":[{"id":"runtime_writable","ok":true},{"id":"model_selected","ok":true}]}""",
        )
        compose.setContent { CoreHubTheme(ThemeChoice.LIGHT) { RuntimeCardView(fine, canRestart = true) } }
        compose.onNodeWithTag("runtime.check.model_selected").assertDoesNotExist()
        compose.onNodeWithText("Runtime ready · 2/2 checks").performClick()
        compose.onNodeWithTag("runtime.check.model_selected").assertExists()
        compose.onNodeWithTag("runtime.restart").assertDoesNotExist()
    }

    @Test fun `the trajectory filters its steps to the tool calls`() {
        val trajectory = json.decodeFromString(
            Trajectory.serializer(),
            """{"session_id":"01J8QK3ZR2W7M5N4P6T8V9X0S1","generated_at":"$at","live":false,"timing":"full","started_at":"$at","ended_at":"2026-09-27T10:00:05Z",
                "steps":[
                  {"id":"s1","kind":"turn","lane":"model","exchange":1,"status":"succeeded","tool_call_only":false,"started_at":"$at","ended_at":"2026-09-27T10:00:01Z","duration_ms":1000,"text":"Looking"},
                  {"id":"s2","kind":"tool","lane":"tools","exchange":1,"status":"failed","tool_call_only":false,"started_at":"2026-09-27T10:00:01Z","ended_at":"2026-09-27T10:00:05Z","duration_ms":4000,
                   "tool_call":{"id":"c1","name":"web_search","status":"failed","output_truncated":false,"preview":"cats","output":"boom"}}],
                "metrics":{"exchanges":1,"turns":1,"steps":2,"tool_calls":1,"failed_tool_calls":1,"model_ms":1000,"tool_ms":4000}}""",
        )
        compose.setContent { CoreHubTheme(ThemeChoice.LIGHT) { TrajectoryBody(trajectory, now = 0) } }
        compose.onNodeWithTag("trajectory.metrics").assertExists()
        compose.onNodeWithTag("trajectory.timeline").assertExists()
        compose.onNodeWithTag("trajectory.step.s1").assertExists()
        compose.onNodeWithTag("trajectory.filter.calls").performScrollTo().performClick()
        compose.onNodeWithTag("trajectory.step.s1").assertDoesNotExist()
        compose.onNodeWithTag("trajectory.step.s2").performScrollTo().performClick()
        compose.onNodeWithText("boom").assertExists()
    }
}

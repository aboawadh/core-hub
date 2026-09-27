package hub.core.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.errorText
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent

/* What the Skills, Memory and Plugins pages and the agent cards draw alike (apps batch 8). */

@Composable
internal fun rememberToolOps(agent: Agent, profile: String): AgentToolOps {
    val context = LocalContext.current
    return remember(agent.id, profile) { AgentToolOps({ context.graph.store.current?.let(context.graph::apis) }, profile, agent.id) }
}

/** A refusal in our words where the hub names why (an import's own sentence follows its lead), else the hub's. */
@Composable
internal fun toolErrorText(error: HubError): String {
    val lead = AgentToolErrors.lead(error) ?: return errorText(error)
    val words = stringResource(lead.first)
    return if (lead.second && !error.text.isNullOrBlank()) "$words: ${error.text}" else words
}

@Composable
internal fun ToolErrorNotice(error: HubError?, modifier: Modifier = Modifier) {
    if (error != null) NoticeBox(toolErrorText(error), BadgeTone.Danger, modifier)
}

/** A line after an action: our words in a tone, or a refusal. */
internal data class ToolNote(val text: String? = null, val tone: BadgeTone = BadgeTone.Success, val error: HubError? = null)

@Composable
internal fun ToolNoteView(note: ToolNote?, modifier: Modifier = Modifier) {
    when {
        note == null -> Unit
        note.error != null -> ToolErrorNotice(note.error, modifier)
        note.text != null -> NoticeBox(note.text, note.tone, modifier)
    }
}

/** «1,234 of 2,200 characters» as a line and a thin bar, warning from 80 %, danger past the limit. */
@Composable
internal fun BudgetMeter(count: Int, limit: Int, modifier: Modifier = Modifier, tag: String = "memory.budget") {
    val t = LocalTokens.current
    val tone = MemoryRules.tone(count, limit)
    val color = when (tone) {
        MemoryRules.Tone.DANGER -> t.danger
        MemoryRules.Tone.WARNING -> t.warningSoftText
        MemoryRules.Tone.NORMAL -> t.accent
    }
    val share = (count.toFloat() / limit).coerceIn(0.01f, 1f)
    Column(modifier.fillMaxWidth().testTag(tag)) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(t.surface2, CircleShape)) {
            Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(color, CircleShape))
        }
        Text(
            stringResource(if (tone == MemoryRules.Tone.DANGER) R.string.agents_memory_budget_over else R.string.agents_memory_budget, "%,d".format(java.util.Locale.ROOT, count), "%,d".format(java.util.Locale.ROOT, limit)),
            fontSize = FontTokens.sizeXs.sp, color = if (tone == MemoryRules.Tone.DANGER) t.danger else t.textMuted,
        )
    }
}

package hub.core.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.chat.Outgoing
import hub.core.android.generated.FontTokens
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.ItemShape
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.ContentBlock
import hub.core.client.model.Preferences
import hub.core.client.model.RunCreate
import java.util.concurrent.atomic.AtomicInteger

/*
 * What you typed while the agent was still working (the owner's decision for the web, 2026-09-22,
 * as on iOS): with «Send while the agent works = wait in line», a message sent during a live turn
 * waits here on the phone, not in the hub's queue, where it can still be changed: «Send now»
 * (`next`: after the live turn), «Steer» (`interrupt`: stop the live turn and take this instead),
 * «Remove» (never sent). When the turn ends the queue empties itself in order, one message per
 * turn. It lives on this chat's screen only: leaving it drops what was never sent. Since 2026-09-27.
 */

/** One waiting message. [key] is the phone's own, never seen by the hub. */
data class QueuedMessage(val key: Int, val outgoing: Outgoing, val replyTo: String?, val preview: String)

object MessageQueueRules {
    /** How long a released message may go unheard before the next one is let go anyway. */
    const val HOLD_MS = 15_000L

    private val counter = AtomicInteger()

    /** Only «wait in line» holds anything back, and only while something is going. */
    fun holdsBack(prefs: Preferences?, busy: Boolean): Boolean = busy && HubDisplay.busyWhen(prefs) == RunCreate.When.QUEUE

    /** The next one goes when nothing runs, nothing is being sent, and the last one released was heard. */
    fun shouldDrain(running: Boolean, sending: Boolean, holding: Boolean, queue: List<QueuedMessage>): Boolean =
        !running && !sending && !holding && queue.isNotEmpty()

    /** A few words of what was typed: enough to tell two waiting messages apart (the web's previewOfBlocks). */
    fun preview(blocks: List<ContentBlock>): String {
        val text = blocks.filter { it.type == ContentBlock.Type.TEXT }.joinToString(" ") { it.text.orEmpty() }.replace(Regex("\\s+"), " ").trim()
        if (text.isNotEmpty()) return if (text.length > 80) text.take(80) + "…" else text
        return blocks.firstOrNull { it.type != ContentBlock.Type.TEXT }?.name.orEmpty()
    }

    fun queued(outgoing: Outgoing, replyTo: String?): QueuedMessage =
        QueuedMessage(counter.incrementAndGet(), outgoing, replyTo, preview(outgoing.blocks()))
}

/** The strip above the composer: each waiting message, numbered, with Send now, Steer and Remove. */
@Composable
fun MessageQueueStrip(items: List<QueuedMessage>, onSendNow: (QueuedMessage) -> Unit, onSteer: (QueuedMessage) -> Unit, onRemove: (QueuedMessage) -> Unit) {
    if (items.isEmpty()) return
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(t.surface2, ItemShape).padding(8.dp).testTag("chat.queue"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(stringResource(R.string.queue_title), tone = BadgeTone.Info, dot = true)
            Text(items.size.toString(), Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        items.forEachIndexed { index, item ->
            Row(Modifier.testTag("chat.queue.item.$index"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((index + 1).toString(), fontSize = FontTokens.sizeXs.sp, color = t.textFaint)
                Text(
                    item.preview, Modifier.weight(1f), fontSize = FontTokens.sizeSm.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(textDirection = TextDirection.Content),
                )
                HubIconButton(Lucide.Send, stringResource(R.string.queue_send_now), { onSendNow(item) }, size = 30.dp, iconSize = 14.dp, modifier = Modifier.testTag("chat.queue.send_now.$index"))
                HubButton(stringResource(R.string.queue_steer), { onSteer(item) }, kind = ButtonKind.Ghost, size = ControlSize.Sm, modifier = Modifier.testTag("chat.queue.steer.$index"))
                HubIconButton(Lucide.X, stringResource(R.string.queue_remove), { onRemove(item) }, size = 30.dp, iconSize = 14.dp, modifier = Modifier.testTag("chat.queue.remove.$index"))
            }
        }
    }
}

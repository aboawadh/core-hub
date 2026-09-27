package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.generated.FontTokens
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Worktree

/*
 * A task's git worktree (the web's TaskDialog WorktreeSection): where the task's work is on disk,
 * on which branch, how it stands, and Remove (git keeps the branch). On the phone since
 * 2026-09-27; before, the task sheet did not show it at all.
 */

object WorktreeRules {
    /** Changes are counted only while the worktree is ready or has changes. */
    fun showsCounts(worktree: Worktree): Boolean = worktree.status == Worktree.Status.READY || worktree.status == Worktree.Status.DIRTY

    /** Remove is offered for a worktree still on disk, and not while the task runs in it. */
    fun removable(worktree: Worktree, running: Boolean): Boolean = !running && worktree.status != Worktree.Status.REMOVED && worktree.status != Worktree.Status.CREATING

    fun tone(status: Worktree.Status): BadgeTone = when (status) {
        Worktree.Status.READY -> BadgeTone.Success
        Worktree.Status.DIRTY -> BadgeTone.Warning
        Worktree.Status.CREATING -> BadgeTone.Info
        Worktree.Status.ERROR -> BadgeTone.Danger
        else -> BadgeTone.Neutral
    }
}

@Composable
private fun statusText(status: Worktree.Status): String = stringResource(
    when (status) {
        Worktree.Status.CREATING -> R.string.taskwt_status_creating
        Worktree.Status.READY -> R.string.taskwt_status_ready
        Worktree.Status.DIRTY -> R.string.taskwt_status_dirty
        Worktree.Status.MERGED -> R.string.taskwt_status_merged
        Worktree.Status.REMOVED -> R.string.taskwt_status_removed
        Worktree.Status.ERROR -> R.string.taskwt_status_error
    },
)

private val ltr = TextStyle(textDirection = TextDirection.Ltr)

@Composable
internal fun WorktreePart(worktree: Worktree, running: Boolean, busy: Boolean, onRemove: () -> Unit) {
    val t = LocalTokens.current
    var asking by remember { mutableStateOf(false) }
    GroupedList(Modifier.testTag("task.worktree"), title = stringResource(R.string.taskwt_title)) {
        Custom {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(statusText(worktree.status), tone = WorktreeRules.tone(worktree.status), dot = true)
            }
            Text(stringResource(R.string.taskwt_branch), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            Text(worktree.branch, fontSize = FontTokens.sizeSm.sp, fontFamily = FontFamily.Monospace, style = ltr, modifier = Modifier.testTag("task.worktree.branch"))
            Text(stringResource(R.string.taskwt_path), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            Text(worktree.path, fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, style = ltr)
            if (WorktreeRules.showsCounts(worktree)) {
                Text(stringResource(R.string.taskwt_changes), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                Text(
                    stringResource(R.string.taskwt_counts, worktree.changedFiles.toString(), worktree.ahead.toString(), worktree.baseBranch),
                    fontSize = FontTokens.sizeSm.sp,
                )
            }
            worktree.error?.let { Text(it, fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = t.danger, style = ltr) }
            if (worktree.status != Worktree.Status.REMOVED) {
                HubButton(
                    stringResource(R.string.taskwt_remove), { asking = true }, kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.Trash,
                    enabled = WorktreeRules.removable(worktree, running) && !busy, modifier = Modifier.testTag("task.worktree.remove"),
                )
                if (running) Text(stringResource(R.string.taskwt_running), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            }
        }
    }
    if (asking) {
        ConfirmDialog(
            title = stringResource(R.string.taskwt_confirm_remove),
            body = stringResource(R.string.taskwt_confirm_remove_body, worktree.branch),
            confirm = stringResource(R.string.taskwt_remove),
            onConfirm = { asking = false; onRemove() },
            onDismiss = { asking = false },
            danger = true,
        )
    }
}


package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import hub.core.android.R
import hub.core.android.chat.ChatControls
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.RenameDialog
import hub.core.android.ui.components.deleteTitle
import hub.core.android.ui.components.rememberAgents
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.IconKind
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuDivider
import hub.core.android.ui.kit.MenuItem
import hub.core.client.model.AgentCapability
import kotlinx.coroutines.launch

/**
 * The conversation's «⋮» (apps batch 1): rename, name it automatically (a chat with a title), pin, archive, fork into a new chat, compress
 * (an agent that can), export the Markdown transcript, and delete — asked first. The global agent's
 * conversation keeps only compress and export (ChatControls.actions).
 */
@Composable
fun ChatMenuButton(
    shell: ShellViewModel,
    sessionId: String,
    profile: String,
    title: String,
    onOpenChat: (String, String) -> Unit,
    onGone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = rememberChatViewModel(sessionId, profile)
    val ui by vm.ui.collectAsState()
    val session = ui.chat.session
    val agent = rememberAgents(profile).firstOrNull { it.id == session?.agentId }
    var open by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val deleting = rememberConfirmDelete<String>()
    val background = rememberBackground()
    val actions = ChatControls.actions(
        pinned = session?.pinned == true,
        archived = session?.archived == true,
        globalAgent = session?.globalAgent == true,
        canCompress = AgentCapability.COMPRESS in agent?.capabilities.orEmpty(),
        titled = !session?.title.isNullOrEmpty(),
    )
    Box {
        HubIconButton(Lucide.Ellipsis, stringResource(R.string.chat_more), { open = true }, kind = IconKind.Glass, modifier = Modifier.testTag("chat.more"))
        HubMenu(open, { open = false }) {
            // The chat's insight (apps batch 6): context, runs, subagents, changed files, files.
            hub.core.android.ui.components.ChatInsightMenuItems(sessionId, profile) { open = false }
            // What works in the background, in every profile (the top bar shows it only while something runs).
            MenuItem(stringResource(R.string.background_title), { open = false; background.show() }, Modifier.testTag("chat.background"), icon = Lucide.Activity)
            MenuDivider()
            actions.forEach { action ->
                val tag = Modifier.testTag("chat.${action.name.lowercase()}")
                when (action) {
                    ChatControls.Action.RENAME ->
                        MenuItem(stringResource(R.string.chat_controls_rename), { open = false; renaming = true }, tag, icon = Lucide.Pencil)
                    ChatControls.Action.AUTO_TITLE, ChatControls.Action.PIN, ChatControls.Action.UNPIN, ChatControls.Action.ARCHIVE, ChatControls.Action.UNARCHIVE -> {
                        val (label, icon) = when (action) {
                            ChatControls.Action.AUTO_TITLE -> R.string.chat_controls_auto_title to Lucide.Sparkles
                            ChatControls.Action.PIN -> R.string.chat_controls_pin to Lucide.Pin
                            ChatControls.Action.UNPIN -> R.string.chat_controls_unpin to Lucide.PinOff
                            ChatControls.Action.ARCHIVE -> R.string.chat_controls_archive to Lucide.Archive
                            else -> R.string.chat_controls_unarchive to Lucide.ArchiveRestore
                        }
                        MenuItem(stringResource(label), {
                            open = false
                            ChatControls.patch(action)?.let { patch -> vm.change(patch) { if (action == ChatControls.Action.ARCHIVE) onGone() } }
                        }, tag, icon = icon)
                    }
                    ChatControls.Action.FORK ->
                        MenuItem(stringResource(R.string.chat_controls_fork), {
                            open = false
                            vm.fork(null) { onOpenChat(it.id, it.profile) }
                        }, tag, icon = Lucide.GitFork)
                    ChatControls.Action.COMPRESS ->
                        MenuItem(
                            stringResource(if (ui.chat.running) R.string.chat_controls_compress_wait else R.string.chat_controls_compress),
                            { open = false; vm.compress() }, tag, icon = Lucide.Shrink, enabled = !ui.chat.running && !ui.compressing,
                        )
                    ChatControls.Action.EXPORT ->
                        MenuItem(stringResource(R.string.chat_export), {
                            open = false
                            scope.launch {
                                val file = shell.exportChat(context, sessionId, profile, title)
                                if (file == null) failed = true
                                else hub.core.android.ui.components.AttachmentFiles.share(context, file, "text/markdown")
                            }
                        }, tag, icon = Lucide.Share2)
                    ChatControls.Action.DELETE -> {
                        MenuDivider()
                        MenuItem(stringResource(R.string.chat_controls_delete), { open = false; deleting.ask(title) }, tag, icon = Lucide.Trash, danger = true)
                    }
                }
            }
        }
    }
    if (renaming) {
        RenameDialog(session?.title.orEmpty(), onDismiss = { renaming = false }) { typed ->
            renaming = false
            vm.rename(typed)
        }
    }
    ConfirmDeleteDialog(
        deleting, { deleteTitle(it) }, onDelete = { vm.delete() }, onDeleted = { onGone() },
        body = stringResource(R.string.chat_controls_delete_body),
    )
    if (failed) {
        HubDialog({ failed = false }) {
            Text(stringResource(R.string.chat_export_failed))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                HubButton(stringResource(R.string.ok), { failed = false }, size = ControlSize.Md)
            }
        }
    }
}

package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.generated.FontTokens
import hub.core.android.rooms.RoomManage
import hub.core.android.ui.components.ConfirmDelete
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.FormField
import hub.core.android.ui.components.FormSheet
import hub.core.android.ui.components.Notice
import hub.core.android.ui.components.TextEditorSheet
import hub.core.android.ui.components.Tone
import hub.core.android.ui.components.deleteTitle
import hub.core.android.ui.components.rememberAgents
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.IconKind
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.HandoffChain
import hub.core.client.model.HandoffPolicy
import hub.core.client.model.Project
import hub.core.client.model.RoomMemory
import hub.core.client.model.RoomPatch
import hub.core.client.model.Seat

/*
 * Managing a room on the phone, as on the web (DECISIONS §69): the room's «⋯» in the top bar
 * (rename, settings, clear the context, archive, delete — or leave, for a member), the seat form
 * (add an agent, edit its name, role, instructions and model), the summary, and the strip that says
 * an agent passed the turn. The rules are in rooms/RoomManage.kt.
 */

/** Words and icons of the room actions, shared by the room's menu and the rooms list. */
object RoomActionLabel {
    fun text(action: RoomManage.Action): Int = when (action) {
        RoomManage.Action.RENAME -> R.string.rooms_manage_rename
        RoomManage.Action.SETTINGS -> R.string.rooms_manage_settings
        RoomManage.Action.CLEAR_CONTEXT -> R.string.rooms_manage_clear
        RoomManage.Action.ARCHIVE -> R.string.rooms_manage_archive
        RoomManage.Action.UNARCHIVE -> R.string.rooms_manage_unarchive
        RoomManage.Action.DELETE -> R.string.rooms_manage_delete
        RoomManage.Action.LEAVE -> R.string.rooms_manage_leave
    }

    fun icon(action: RoomManage.Action): Int = when (action) {
        RoomManage.Action.RENAME -> Lucide.Pencil
        RoomManage.Action.SETTINGS -> Lucide.SlidersHorizontal
        RoomManage.Action.CLEAR_CONTEXT -> Lucide.RotateCcw
        RoomManage.Action.ARCHIVE -> Lucide.Archive
        RoomManage.Action.UNARCHIVE -> Lucide.ArchiveRestore
        RoomManage.Action.DELETE -> Lucide.Trash
        RoomManage.Action.LEAVE -> Lucide.LogOut
    }

    fun danger(action: RoomManage.Action) = action == RoomManage.Action.DELETE || action == RoomManage.Action.LEAVE
}

/** The room's «⋯»: archive and bring back act at once; the rest open their sheet or question. */
@Composable
fun RoomMenuButton(vm: RoomViewModel, ui: RoomUi) {
    val room = ui.state.room ?: return
    val actions = RoomManage.actions(room.canManage, room.archived, vm.isOwner)
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<RoomManage.Action?>(null) }
    val scope = rememberCoroutineScope()
    Box {
        HubIconButton(Lucide.Ellipsis, stringResource(R.string.rooms_manage_more), { open = true }, kind = IconKind.Glass, modifier = Modifier.testTag("room.menu"))
        HubMenu(open, { open = false }) {
            actions.forEach { action ->
                MenuItem(
                    stringResource(RoomActionLabel.text(action)), {
                        open = false
                        when (action) {
                            RoomManage.Action.ARCHIVE -> vm.change(RoomPatch(archived = true))
                            RoomManage.Action.UNARCHIVE -> vm.change(RoomPatch(archived = false))
                            RoomManage.Action.SETTINGS -> scope.launch {
                                vm.loadProjects()
                                pending = action
                            }
                            else -> pending = action
                        }
                    },
                    Modifier.testTag("room.menu.${action.name.lowercase()}"), icon = RoomActionLabel.icon(action), danger = RoomActionLabel.danger(action),
                )
            }
        }
    }
    val done = { pending = null }
    when (pending) {
        RoomManage.Action.RENAME -> RoomRenameSheet(room.name, done) { vm.update(it) }
        RoomManage.Action.SETTINGS -> RoomSettingsSheet(room.canMentionAll, room.handoff, vm.projects, vm.linkedProject, done, vm::saveSettings)
        RoomManage.Action.CLEAR_CONTEXT -> RoomQuestion(
            stringResource(R.string.rooms_manage_clear_title), stringResource(R.string.rooms_manage_clear_body),
            stringResource(R.string.rooms_manage_clear_confirm), done,
        ) { vm.clearContext(); Result.success(Unit) }
        RoomManage.Action.LEAVE -> RoomQuestion(
            stringResource(R.string.rooms_manage_leave_title), stringResource(R.string.rooms_manage_leave_body, room.name),
            stringResource(R.string.rooms_manage_leave), done,
        ) { vm.leave(); Result.success(Unit) }
        RoomManage.Action.DELETE -> RoomQuestion(deleteTitle(room.name), stringResource(R.string.kit_delete_body), stringResource(R.string.kit_delete), done) { vm.delete() }
        else -> Unit
    }
}

/** A yes/no before something that cannot be taken back (the kit's confirm, with the room's words). */
@Composable
fun RoomQuestion(title: String, body: String, confirm: String, onDone: () -> Unit, onYes: suspend () -> Result<*>) {
    val state = remember { ConfirmDelete<Unit>().also { it.ask(Unit) } }
    // Cancel, a tap outside and a done «yes» all empty the question: then it is over.
    LaunchedEffect(state.pending) { if (state.pending == null) onDone() }
    ConfirmDeleteDialog(state, { title }, { onYes() }, body = body, confirm = confirm)
}

/** A room's new name. */
@Composable
fun RoomRenameSheet(current: String, onDismiss: () -> Unit, onSave: suspend (RoomPatch) -> Result<*>) {
    FormSheet(
        stringResource(R.string.rooms_manage_rename_title),
        listOf(FormField(RoomManage.NAME, stringResource(R.string.rooms_manage_name), required = true)),
        mapOf(RoomManage.NAME to current),
        onDismiss,
        { values -> RoomManage.renamed(values[RoomManage.NAME].orEmpty(), current)?.let { onSave(it) } ?: Result.success(Unit) },
        tag = "room.rename",
    )
}

/** How the room behaves: `@all`, and whether and how far agents pass the turn. */
@Composable
private fun RoomSettingsSheet(
    canMentionAll: Boolean, handoff: HandoffPolicy?, projects: List<Project>?, linkedProject: String?,
    onDismiss: () -> Unit, onSave: suspend (Map<String, String>) -> Result<*>,
) {
    FormSheet(
        stringResource(R.string.rooms_manage_settings),
        RoomManage.settingsFields(
            stringResource(R.string.rooms_manage_settings_mention_all), stringResource(R.string.rooms_manage_settings_handoff),
            stringResource(R.string.rooms_manage_settings_handoff_hint), stringResource(R.string.rooms_manage_settings_max_depth),
            stringResource(R.string.rooms_manage_settings_max_depth_hint),
            projects,
            RoomManage.ProjectLabels(
                stringResource(R.string.rooms_manage_settings_project), stringResource(R.string.rooms_manage_settings_project_none),
                stringResource(R.string.rooms_manage_settings_project_hint),
            ),
        ),
        RoomManage.settingsValues(canMentionAll, handoff, linkedProject),
        onDismiss,
        onSave,
        tag = "room.settings",
    )
}

/** The seat form: which agent (when adding), its name in the room, role, instructions and model. */
@Composable
fun SeatFormSheet(vm: RoomViewModel, seat: Seat?, onDismiss: () -> Unit) {
    val agents = rememberAgents(vm.profile)
    val labels = RoomManage.SeatLabels(
        agent = stringResource(R.string.rooms_manage_seat_agent),
        name = stringResource(R.string.rooms_manage_seat_name),
        nameHint = stringResource(R.string.rooms_manage_seat_name_hint),
        nameAddHint = stringResource(R.string.rooms_manage_seat_name_add_hint),
        role = stringResource(R.string.rooms_manage_seat_role),
        instructions = stringResource(R.string.rooms_manage_seat_instructions),
        instructionsHint = stringResource(R.string.rooms_manage_seat_instructions_hint),
        model = stringResource(R.string.rooms_manage_seat_model),
        modelHint = stringResource(R.string.rooms_manage_seat_model_hint),
    )
    // The form reads its values once: it waits for the profile's agents before it opens.
    if (seat == null && agents.isEmpty()) {
        hub.core.android.ui.kit.HubSheet(onDismiss = onDismiss, title = stringResource(R.string.rooms_manage_seat_add_title)) {
            Notice(stringResource(R.string.rooms_manage_no_agents), Tone.INFO)
        }
        return
    }
    FormSheet(
        stringResource(if (seat == null) R.string.rooms_manage_seat_add_title else R.string.rooms_manage_seat_edit_title),
        RoomManage.seatFields(seat == null, agents, labels),
        RoomManage.seatValues(seat, agents),
        onDismiss,
        { values ->
            if (seat != null) vm.updateSeat(seat, RoomManage.seatPatch(values))
            else RoomManage.seatConfig(values, agents)?.let { vm.addSeat(it) } ?: Result.success(Unit)
        },
        saveLabel = if (seat == null) stringResource(R.string.rooms_manage_seat_add) else stringResource(R.string.save),
        intro = stringResource(R.string.rooms_manage_seat_intro),
        tag = "room.seat",
    )
}

/** The strip over the composer: an agent passing the turn now, or a stopped pass with its one more round. */
@Composable
fun HandoffStrip(state: hub.core.android.rooms.RoomState, onContinue: (String) -> Unit) {
    val line = RoomManage.handoffLine(state.activeChains, state.handoffs, state.seats) ?: return
    val t = LocalTokens.current
    when (line) {
        is RoomManage.HandoffLine.Active -> Text(
            stringResource(R.string.rooms_manage_handoff_active, line.from, line.to, line.depth),
            fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.fillMaxWidth().testTag("room.handoff.active"),
        )
        is RoomManage.HandoffLine.Stopped -> Column(Modifier.fillMaxWidth().testTag("room.handoff.stopped"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val text = when (line.reason) {
                HandoffChain.StopReason.MAX_DEPTH -> stringResource(R.string.rooms_manage_handoff_stopped_max_depth, line.from, line.to)
                HandoffChain.StopReason.LOOP_DETECTED -> stringResource(R.string.rooms_manage_handoff_stopped_loop_detected, line.from, line.to)
                HandoffChain.StopReason.INTERRUPTED -> stringResource(R.string.rooms_manage_handoff_stopped_interrupted, line.from, line.to)
                HandoffChain.StopReason.ERROR -> stringResource(R.string.rooms_manage_handoff_stopped_error, line.to)
            }
            Notice(text, Tone.WARNING)
            if (line.more && state.room?.archived != true) {
                HubButton(
                    stringResource(R.string.rooms_manage_handoff_continue), { onContinue(line.id) }, kind = ButtonKind.Secondary,
                    size = ControlSize.Sm, icon = Lucide.Play, modifier = Modifier.testTag("room.handoff.continue"),
                )
            }
        }
    }
}

/**
 * The room's summary: what agents are told of what came before. The manager may have it rewritten
 * now or write it by hand.
 */
@Composable
fun RoomMemoryCard(memory: RoomMemory?, canManage: Boolean, onRefresh: () -> Unit, onSave: suspend (String) -> Result<*>) {
    val t = LocalTokens.current
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("room.memory"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rooms_manage_memory_title), fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.SemiBold, color = t.textMuted)
            if (memory?.status == RoomMemory.Status.SUMMARIZING) Badge(stringResource(R.string.rooms_manage_memory_working), tone = BadgeTone.Info)
            if (memory?.status == RoomMemory.Status.ERROR) Badge(stringResource(R.string.rooms_manage_memory_failed), tone = BadgeTone.Danger)
        }
        Text(
            memory?.summary ?: stringResource(R.string.rooms_manage_memory_none),
            fontSize = FontTokens.sizeSm.sp, color = if (memory?.summary == null) t.textMuted else t.text,
            style = TextStyle(textDirection = TextDirection.Content), modifier = Modifier.testTag("room.memory.text"),
        )
        memory?.error?.takeIf { memory.status == RoomMemory.Status.ERROR }?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.danger) }
        if (canManage) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubButton(
                    stringResource(R.string.rooms_manage_memory_refresh), onRefresh, kind = ButtonKind.Secondary, size = ControlSize.Sm,
                    icon = Lucide.RefreshCw, enabled = memory?.status != RoomMemory.Status.SUMMARIZING, modifier = Modifier.testTag("room.memory.refresh"),
                )
                HubButton(
                    stringResource(R.string.rooms_manage_memory_edit), { editing = true }, kind = ButtonKind.Ghost, size = ControlSize.Sm,
                    icon = Lucide.Pencil, modifier = Modifier.testTag("room.memory.edit"),
                )
            }
        }
    }
    if (editing) {
        TextEditorSheet(
            stringResource(R.string.rooms_manage_memory_edit), memory?.summary.orEmpty(), { editing = false },
            onSave, markdown = false, tag = "room.memory.editor",
        )
    }
}

/** A seat's second line under its `@name`. */
@Composable
fun seatLine(seat: Seat): String = RoomManage.seatLine(seat, stringResource(R.string.rooms_manage_default_model))

package hub.core.android.ui.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import hub.core.android.R
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.MarkdownView
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCheckbox
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Comment
import hub.core.client.model.HermesCardHistory
import hub.core.client.model.Subtask
import hub.core.client.model.TaskCheckItem
import kotlinx.coroutines.launch

/*
 * The parts Tasks II (batch 5) adds to a task's sheet (TaskDetail.kt): its checklist (subtasks:
 * tick, add, hold the grip and drag into another order, delete), its definition of done and
 * constraints (§104, ticked by the reviewer while it is in review), what was said on it, and — for
 * a card on Hermes's board — Hermes's own history of it (§103). The calls are the sheet's; the
 * rules are TaskLists.kt. iOS's twin is TaskDetailSections.swift.
 */

/** The task's checklist. A long press on a line's grip lifts it; let go where it goes. */
@Composable
fun SubtasksPart(
    lines: List<Subtask>,
    editable: Boolean,
    onTick: (Subtask) -> Unit,
    onAdd: suspend (String) -> Boolean,
    onDelete: (Subtask) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val t = LocalTokens.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    var draft by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val title = if (lines.isEmpty()) stringResource(R.string.taskl_list_title)
    else stringResource(R.string.taskl_list_title_count, SubtaskRules.doneCount(lines), lines.size)
    fun send() {
        val words = SubtaskRules.title(draft) ?: return
        if (adding) return
        adding = true
        scope.launch {
            if (onAdd(words)) draft = ""
            adding = false
        }
    }
    GroupedList(Modifier.testTag("task.subtasks"), title = title) {
        lines.forEachIndexed { index, line ->
            val lifted = dragging == index
            Custom(
                Modifier.onSizeChanged { rowHeight = it.height.toFloat() }
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer { translationY = if (lifted) dy else 0f; shadowElevation = if (lifted) 8f else 0f }
                    .testTag("task.subtask.$index"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (editable && lines.size > 1) {
                        LucideIcon(
                            Lucide.GripVertical, stringResource(R.string.taskl_list_drag, line.title), size = 18.dp, tint = t.textFaint,
                            modifier = Modifier.testTag("task.subtask.grip.$index").pointerInput(line.id, index, lines.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); dragging = index; dy = 0f },
                                    onDrag = { change, amount -> change.consume(); dy += amount.y },
                                    onDragEnd = {
                                        val to = SubtaskRules.landing(index, dy, rowHeight, lines.size)
                                        dragging = null
                                        dy = 0f
                                        if (to != index) onReorder(SubtaskRules.order(lines.map { it.id }, index, to))
                                    },
                                    onDragCancel = { dragging = null; dy = 0f },
                                )
                            },
                        )
                    }
                    val done = line.status == Subtask.Status.DONE
                    HubCheckbox(done, if (editable) { _ -> onTick(line) } else null, Modifier.testTag("task.subtask.tick.$index"))
                    InContentDirection(line.title) {
                        Text(
                            line.title, Modifier.weight(1f), fontSize = FontTokens.sizeMd.sp, color = if (done) t.textMuted else t.text,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                        )
                    }
                    if (editable) {
                        HubIconButton(
                            Lucide.X, stringResource(R.string.taskl_list_delete, line.title), { onDelete(line) },
                            size = 32.dp, iconSize = 16.dp, tint = t.textMuted, modifier = Modifier.testTag("task.subtask.delete.$index"),
                        )
                    }
                }
            }
        }
        if (editable) {
            Custom {
                AddLine(draft, { draft = it }, stringResource(R.string.taskl_list_placeholder), stringResource(R.string.taskl_list_add), SubtaskRules.title(draft) != null && !adding, "task.subtask", ::send)
            }
        }
    }
    if (editable && lines.size > 1) Text(stringResource(R.string.taskl_list_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
}

/** A field and a + that adds what was typed; Enter adds too. */
@Composable
private fun AddLine(value: String, onValue: (String) -> Unit, placeholder: String, label: String, enabled: Boolean, tag: String, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HubTextField(
            value, onValue, Modifier.weight(1f), placeholder = placeholder, size = ControlSize.Md, fieldTag = "$tag.input",
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (enabled) onAdd() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        )
        HubIconButton(Lucide.Plus, label, onAdd, size = 36.dp, enabled = enabled, modifier = Modifier.testTag("$tag.add"))
    }
}

/** A definition of done or the constraints: lines written here, ticked here by the reviewer. */
@Composable
fun CheckLinesPart(kind: CheckLines.Kind, items: List<TaskCheckItem>, reviewing: Boolean, enabled: Boolean, onSave: (List<TaskCheckItem>) -> Unit) {
    val t = LocalTokens.current
    val tag = if (kind == CheckLines.Kind.DONE) "task.dod" else "task.constraints"
    var draft by remember { mutableStateOf("") }
    val name = stringResource(if (kind == CheckLines.Kind.DONE) R.string.taskl_dod_title else R.string.taskl_constraints_title)
    val title = if (items.isEmpty()) name else "$name · ${stringResource(R.string.taskl_dod_ticked, items.count { it.checked }, items.size)}"
    GroupedList(Modifier.testTag(tag), title = title) {
        items.forEachIndexed { index, item ->
            Custom(Modifier.testTag("$tag.line.$index")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubCheckbox(item.checked, if (reviewing && enabled) { on -> onSave(CheckLines.ticking(items, index, on)) } else null, Modifier.testTag("$tag.tick.$index"))
                    InContentDirection(item.text) { Text(item.text, Modifier.weight(1f), fontSize = FontTokens.sizeSm.sp) }
                    HubIconButton(
                        Lucide.X, stringResource(R.string.taskl_list_delete, item.text), { onSave(CheckLines.removing(items, index)) },
                        size = 32.dp, iconSize = 16.dp, tint = t.textMuted, enabled = enabled, modifier = Modifier.testTag("$tag.delete.$index"),
                    )
                }
            }
        }
        if (items.size < CheckLines.LINES_MAX) {
            Custom {
                val add = { CheckLines.adding(items, draft)?.let { draft = ""; onSave(it) }; Unit }
                AddLine(
                    draft, { draft = it },
                    stringResource(if (kind == CheckLines.Kind.DONE) R.string.taskl_dod_placeholder else R.string.taskl_constraints_placeholder),
                    stringResource(if (kind == CheckLines.Kind.DONE) R.string.taskl_dod_add else R.string.taskl_constraints_add),
                    enabled && CheckLines.adding(items, draft) != null, tag, add,
                )
            }
        }
    }
    Text(
        stringResource(if (reviewing) R.string.taskl_dod_review_hint else if (kind == CheckLines.Kind.DONE) R.string.taskl_dod_hint else R.string.taskl_constraints_hint),
        fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
    )
}

/** What was said on the task, oldest first, and a field to say something. */
@Composable
fun CommentsPart(comments: List<Comment>, onSend: suspend (String) -> Boolean) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    GroupedList(Modifier.testTag("task.comments"), title = stringResource(R.string.taskl_comments_title)) {
        if (comments.isEmpty()) Item(stringResource(R.string.taskl_comments_none))
        comments.forEach { comment ->
            Custom(Modifier.testTag("task.comment")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(comment.author.name, Modifier.weight(1f), fontSize = FontTokens.sizeXs.sp, fontWeight = FontWeight.SemiBold, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(localTime(comment.createdAt), fontSize = FontTokens.sizeXs.sp, color = t.textFaint)
                }
                MarkdownView(comment.content, Modifier.fillMaxWidth())
            }
        }
        Custom {
            HubTextField(
                draft, { draft = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.taskl_comments_placeholder),
                singleLine = false, minLines = 2, maxLines = 6, size = ControlSize.Md, fieldTag = "task.comment.input",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                HubButton(
                    stringResource(R.string.taskl_comments_send), {
                        sending = true
                        scope.launch {
                            if (onSend(draft.trim())) draft = ""
                            sending = false
                        }
                    },
                    kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.ArrowUp, loading = sending,
                    enabled = draft.isNotBlank() && !sending, modifier = Modifier.testTag("task.comment.send"),
                )
            }
        }
    }
}

/** A Hermes card's own history, read from Hermes as the card opened (§103), newest first. */
@Composable
fun HermesHistoryPart(history: HermesCardHistory) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (history.runs.isNotEmpty()) {
            GroupedList(Modifier.testTag("task.hermes.runs"), title = stringResource(R.string.taskl_hermes_runs)) {
                history.runs.forEach { run ->
                    Item(
                        run.outcome ?: run.status, subtitle = (run.error ?: run.summary)?.takeIf { it.isNotBlank() },
                        value = localTime(run.startedAt), danger = run.error != null,
                    )
                }
            }
        }
        GroupedList(Modifier.testTag("task.hermes.events"), title = stringResource(R.string.taskl_hermes_events)) {
            if (history.events.isEmpty()) Item(stringResource(R.string.taskl_hermes_no_events))
            history.events.forEach { event -> Item(event.kind, value = localTime(event.createdAt)) }
        }
    }
}

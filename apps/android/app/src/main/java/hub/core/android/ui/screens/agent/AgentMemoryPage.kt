package hub.core.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.TextEditorSheet
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.Hairline
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.MemoryItem
import kotlinx.coroutines.launch

/** An entry being written: which document, and which entry (null adds one at the end). */
private data class EntryEdit(val item: MemoryItem, val index: Int?)

/**
 * An agent's memory, as the web's page (apps batch 8): the persona, what it keeps and what it knows
 * about you. The two memories are lists of entries within a budget (decision §102): each entry is
 * edited or removed (after asking) on its own, a new one is added, and the whole list can be edited
 * at once; the budget is drawn as a bar. The documents are emptied by editing, never deleted.
 */
@Composable
private fun MemoryPage(agent: Agent, profile: String) {
    val ops = rememberToolOps(agent, profile)
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var entry by remember { mutableStateOf<EntryEdit?>(null) }
    var removing by remember { mutableStateOf<EntryEdit?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val load = rememberLoad(agent.id, profile) { ops.memory().getOrThrow() }
    LoadView(load) { items ->
        LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.memory")) {
            item(key = "note") { Text(stringResource(R.string.agents_memory_note), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
            item(key = "error") { ToolErrorNotice(error) }
            if (items.isEmpty()) item(key = "none") { EmptyState(stringResource(R.string.agent_nothing), icon = Lucide.Brain) }
            items(items, key = { it.id }) { item ->
                MemoryCard(
                    item,
                    onEdit = { editing = item },
                    onAdd = { entry = EntryEdit(item, null) },
                    onEditEntry = { entry = EntryEdit(item, it) },
                    onRemoveEntry = { removing = EntryEdit(item, it) },
                )
            }
        }
    }
    editing?.let { item ->
        TextEditorSheet(
            title = memoryTitle(item), initial = item.content.orEmpty(), onDismiss = { editing = null },
            onSave = { text -> ops.saveMemory(item, text).onSuccess { load.reload() } },
            subtitle = if (MemoryRules.isList(item)) stringResource(R.string.agents_memory_entries_hint) else memoryAbout(item),
            onReload = { editing = null; load.reload() }, tag = "memory.editor",
        )
    }
    entry?.let { edit -> EntrySheet(edit, onDismiss = { entry = null }, onSave = { text ->
        val next = MemoryRules.withEntry(MemoryRules.listOf(edit.item), edit.index, text)
        ops.saveMemory(edit.item, MemoryRules.join(next)).onSuccess { load.reload() }
    }) }
    removing?.let { edit ->
        ConfirmDialog(
            stringResource(R.string.agents_memory_remove_title), MemoryRules.listOf(edit.item).getOrNull(edit.index ?: -1),
            stringResource(R.string.agents_memory_remove), {
                removing = null
                scope.launch { ops.removeEntry(edit.item, edit.index ?: return@launch).onSuccess { error = null; load.reload() }.onFailure { error = it as HubError } }
            },
            { removing = null }, danger = true,
        )
    }
}

@Composable
private fun memoryTitle(item: MemoryItem): String = when (item.id) {
    "soul" -> stringResource(R.string.agents_memory_doc_soul)
    "memory" -> stringResource(R.string.agents_memory_doc_memory)
    "user" -> stringResource(R.string.agents_memory_doc_user)
    else -> item.title
}

@Composable
private fun memoryAbout(item: MemoryItem): String? = when (item.id) {
    "soul" -> stringResource(R.string.agents_memory_about_soul)
    "memory" -> stringResource(R.string.agents_memory_about_memory)
    "user" -> stringResource(R.string.agents_memory_about_user)
    else -> null
}

@Composable
private fun memoryEmpty(item: MemoryItem): String = when (item.id) {
    "soul" -> stringResource(R.string.agents_memory_empty_soul)
    "memory" -> stringResource(R.string.agents_memory_empty_memory)
    "user" -> stringResource(R.string.agents_memory_empty_user)
    else -> stringResource(R.string.agents_memory_empty)
}

/** One document: its name and file, its budget, and its entries (or its text). */
@Composable
internal fun MemoryCard(
    item: MemoryItem,
    onEdit: () -> Unit,
    onAdd: () -> Unit,
    onEditEntry: (Int) -> Unit,
    onRemoveEntry: (Int) -> Unit,
) {
    val t = LocalTokens.current
    val list = MemoryRules.isList(item)
    val entries = if (list) MemoryRules.listOf(item) else emptyList()
    val empty = if (list) entries.isEmpty() else item.content.isNullOrBlank()
    HubCard(Modifier.testTag("memory.${item.id}"), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(memoryTitle(item), fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold)
            if (MemoryRules.known(item)) Text(item.title, fontSize = FontTokens.sizeXs.sp, color = t.textFaint)
            if (item.id == "soul") Badge(stringResource(R.string.agents_memory_persona), tone = BadgeTone.Accent)
            Box(Modifier.weight(1f))
            if (list) HubIconButton(Lucide.Plus, stringResource(R.string.agents_memory_add_entry), onAdd, size = 32.dp, iconSize = 16.dp, modifier = Modifier.testTag("memory.${item.id}.add"))
            HubButton(
                stringResource(if (empty) R.string.agents_memory_write else if (list) R.string.agents_memory_edit_all else R.string.agents_memory_edit),
                onEdit, kind = ButtonKind.Ghost, size = ControlSize.Sm, modifier = Modifier.testTag("memory.${item.id}.edit"),
            )
        }
        val limit = item.charLimit
        if (list && limit != null && limit > 0) BudgetMeter(item.charCount ?: MemoryRules.lengthOf(entries), limit)
        when {
            empty -> Text(memoryEmpty(item), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
            list -> entries.forEachIndexed { index, text ->
                if (index > 0) Hairline()
                EntryRow(text, onEdit = { onEditEntry(index) }, onRemove = { onRemoveEntry(index) }, tag = "memory.${item.id}.entry.$index")
            }
            else -> item.content?.let { c -> InContentDirection(c) { Text(c, fontSize = FontTokens.sizeSm.sp, color = t.text, maxLines = 12) } }
        }
    }
}

/** One entry: its text (tap to edit, long press or «⋯» for Edit and Remove). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(text: String, onEdit: () -> Unit, onRemove: () -> Unit, tag: String) {
    val t = LocalTokens.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag(tag).combinedClickable(onClick = onEdit, onLongClick = { menu = true }),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f)) { InContentDirection(text) { Text(text, fontSize = FontTokens.sizeSm.sp, color = t.text) } }
        Box {
            HubIconButton(Lucide.Ellipsis, stringResource(R.string.kit_more_actions), { menu = true }, size = 28.dp, iconSize = 14.dp, modifier = Modifier.testTag("$tag.more"))
            HubMenu(menu, { menu = false }) {
                MenuItem(stringResource(R.string.agents_memory_edit_entry), { menu = false; onEdit() }, icon = Lucide.Pencil)
                MenuItem(stringResource(R.string.agents_memory_remove), { menu = false; onRemove() }, icon = Lucide.Trash, danger = true, modifier = Modifier.testTag("$tag.remove"))
            }
        }
    }
}

/** One entry written or rewritten on its own, with what the list would count against its budget. */
@Composable
private fun EntrySheet(edit: EntryEdit, onDismiss: () -> Unit, onSave: suspend (String) -> Result<*>) {
    val scope = rememberCoroutineScope()
    val entries = MemoryRules.listOf(edit.item)
    var draft by remember(edit) { mutableStateOf(edit.index?.let { entries.getOrNull(it) }.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val next = MemoryRules.withEntry(entries, edit.index, draft)
    val count = MemoryRules.lengthOf(next)
    val current = edit.item.charCount ?: MemoryRules.lengthOf(entries)
    val limit = edit.item.charLimit
    val fits = MemoryRules.fits(count, current, limit)
    val name = memoryTitle(edit.item)
    HubSheet(onDismiss = onDismiss, title = stringResource(if (edit.index == null) R.string.agents_memory_add_entry_title else R.string.agents_memory_edit_entry_title, name)) {
        memoryAbout(edit.item)?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted) }
        ToolErrorNotice(error)
        HubTextField(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), singleLine = false, minLines = 5, maxLines = 12, size = ControlSize.Md, fieldTag = "memory.entry.text")
        if (limit != null && limit > 0) BudgetMeter(count, limit, tag = "memory.entry.budget")
        if (!fits) NoticeBox(stringResource(R.string.agents_memory_too_long, count.toString(), (limit ?: 0).toString()), BadgeTone.Danger)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubButton(
                stringResource(R.string.save), {
                    saving = true
                    scope.launch {
                        onSave(draft).onSuccess { onDismiss() }.onFailure { error = it as HubError }
                        saving = false
                    }
                },
                size = ControlSize.Md, icon = Lucide.Check, loading = saving,
                enabled = fits && !(edit.index == null && MemoryRules.entriesOf(draft).isEmpty()), modifier = Modifier.testTag("memory.entry.save"),
            )
            HubButton(stringResource(R.string.cancel), onDismiss, kind = ButtonKind.Ghost, size = ControlSize.Md)
        }
    }
}

internal val agentMemoryPage = AgentPageEntry("agent_memory") { agent, profile -> MemoryPage(agent, profile) }

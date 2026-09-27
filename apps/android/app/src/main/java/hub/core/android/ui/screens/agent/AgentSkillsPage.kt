package hub.core.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.DocumentField
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.MarkdownView
import hub.core.android.ui.components.PickedFiles
import hub.core.android.ui.components.TextEditorSheet
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.Chip
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubSwitch
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.Skill
import hub.core.client.model.SkillLibrary
import hub.core.client.model.SkillSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the skill sheet shows: a new skill to name and write, or one read with its text. */
private sealed interface SkillSheet {
    data object New : SkillSheet
    data class Open(val skill: Skill) : SkillSheet
}

/**
 * An agent's skills, as the web's page (apps batch 8): search and a chip for whose skills, the Core
 * Hub library card, the skills by category with their switch, and each skill opened to read or edit
 * its `SKILL.md`; pin, restore an edited library skill and delete (after asking) from its menu; a new
 * skill, and a pack imported from the phone's files.
 */
@Composable
private fun SkillsPage(agent: Agent, profile: String) {
    val ops = rememberToolOps(agent, profile)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SkillRules.Filter.ALL) }
    var note by remember { mutableStateOf<ToolNote?>(null) }
    var sheet by remember { mutableStateOf<SkillSheet?>(null) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Skill?>(null) }
    var restoring by remember { mutableStateOf<Skill?>(null) }
    var libraryOff by remember { mutableStateOf(false) }
    val load = rememberLoad(agent.id, profile) { ops.skills().getOrThrow() }

    val notPack = stringResource(R.string.agents_skill_import_not_pack)
    val importedText = stringResource(R.string.agents_skill_imported)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { PickedFiles.copy(context, it) } }
            note = if (files.isEmpty() || files.any { !SkillRules.importable(it.name) }) {
                ToolNote(notPack, BadgeTone.Danger)
            } else {
                ops.importSkills(files).fold(
                    { skills -> load.reload(); ToolNote(importedText.format(skills.size.toString(), skills.joinToString(", ") { it.name })) },
                    { ToolNote(error = it as HubError) },
                )
            }
            withContext(Dispatchers.IO) { files.forEach(File::delete) }
            importing = false
        }
    }

    fun act(block: suspend () -> Result<*>, done: String? = null) {
        scope.launch {
            block().onSuccess { note = done?.let { ToolNote(it) }; load.reload() }.onFailure { note = ToolNote(error = it as HubError) }
        }
    }

    fun open(skill: Skill) {
        scope.launch { ops.skill(skill.key).onSuccess { sheet = SkillSheet.Open(it) }.onFailure { note = ToolNote(error = it as HubError) } }
    }

    LoadView(load) { answer ->
        val shown = SkillRules.narrow(answer.categories, query, filter)
        val total = SkillRules.total(answer.categories)
        LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.skills")) {
            item(key = "search") {
                HubTextField(
                    query, { query = it }, placeholder = stringResource(R.string.agents_skill_search), leadingIcon = Lucide.Search,
                    size = ControlSize.Md, fieldTag = "skills.search",
                )
            }
            item(key = "bar") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SkillRules.Filter.entries.forEach { f ->
                            Chip(
                                filterLabel(f), f == filter, { filter = f }, size = ControlSize.Sm,
                                modifier = Modifier.testTag("skills.filter.${f.name.lowercase()}"),
                            )
                        }
                    }
                    HubIconButton(
                        Lucide.Download, stringResource(R.string.agents_skill_import), { picker.launch(arrayOf("*/*")) },
                        size = 36.dp, iconSize = 18.dp, enabled = !importing, modifier = Modifier.testTag("skills.import"),
                    )
                    HubIconButton(Lucide.Plus, stringResource(R.string.agents_skill_new), { sheet = SkillSheet.New }, size = 36.dp, iconSize = 18.dp, modifier = Modifier.testTag("skills.new"))
                }
            }
            if (importing) item(key = "importing") { NoticeBox(stringResource(R.string.agents_skill_importing), BadgeTone.Info) }
            item(key = "note") { ToolNoteView(note) }
            answer.library?.let { library ->
                item(key = "library") {
                    SkillLibraryCard(library, onSwitch = { on -> if (on) act({ ops.setLibrary(true) }) else libraryOff = true }, onInstall = { act({ ops.setLibrary(true) }) })
                }
            }
            when {
                total == 0 -> item(key = "none") {
                    EmptyState(
                        stringResource(R.string.agents_skill_none),
                        body = listOfNotNull(stringResource(R.string.agents_skill_none_body), answer.home).joinToString("\n"), icon = Lucide.Sparkles,
                    )
                }
                shown.isEmpty() -> item(key = "nomatch") { EmptyState(stringResource(R.string.kit_no_matches), icon = Lucide.Search) }
            }
            shown.forEach { category ->
                item(key = "c:" + category.key) {
                    GroupedList(title = categoryTitle(category.key, category.name)) {
                        category.skills.forEach { skill ->
                            Custom {
                                SkillRow(
                                    skill,
                                    onSwitch = { on -> act({ ops.switchSkill(skill.key, on) }) },
                                    onAction = { action ->
                                        when (action) {
                                            SkillRules.Action.OPEN -> open(skill)
                                            SkillRules.Action.PIN -> act({ ops.pinSkill(skill.key, true) })
                                            SkillRules.Action.UNPIN -> act({ ops.pinSkill(skill.key, false) })
                                            SkillRules.Action.RESTORE -> restoring = skill
                                            SkillRules.Action.DELETE -> deleting = skill
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { skill ->
        ConfirmDialog(
            stringResource(R.string.agents_skill_delete_title, skill.name), stringResource(R.string.agents_skill_delete_body),
            stringResource(R.string.agents_skill_delete), { deleting = null; act({ ops.deleteSkill(skill.key) }) }, { deleting = null }, danger = true,
        )
    }
    restoring?.let { skill ->
        val restored = stringResource(R.string.agents_skill_restored, skill.name)
        ConfirmDialog(
            stringResource(R.string.agents_skill_restore_title, skill.name), stringResource(R.string.agents_skill_restore_body),
            stringResource(R.string.agents_skill_restore), { restoring = null; act({ ops.restoreSkill(skill.key) }, restored) }, { restoring = null },
        )
    }
    if (libraryOff) {
        ConfirmDialog(
            stringResource(R.string.agents_skill_library_off_title), stringResource(R.string.agents_skill_library_off_body),
            stringResource(R.string.agents_skill_library_off_confirm), { libraryOff = false; act({ ops.setLibrary(false) }) }, { libraryOff = false }, danger = true,
        )
    }
    when (val s = sheet) {
        null -> Unit
        SkillSheet.New -> NewSkillSheet(onDismiss = { sheet = null }, onSave = { key, text -> ops.saveSkill(key, text).onSuccess { load.reload() } })
        is SkillSheet.Open -> {
            val skill = s.skill
            if (SkillRules.readOnly(skill)) {
                HubSheet(onDismiss = { sheet = null }, title = skill.name) {
                    Text(stringResource(R.string.agents_skill_builtin_hint), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted)
                    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).testTag("skill.view")) {
                        val text = skill.content.orEmpty()
                        InContentDirection(text) { MarkdownView(text) }
                    }
                    HubButton(stringResource(R.string.agents_skill_close), { sheet = null }, kind = ButtonKind.Secondary, size = ControlSize.Md)
                }
            } else {
                TextEditorSheet(
                    title = skill.name, initial = skill.content.orEmpty(), onDismiss = { sheet = null },
                    onSave = { text -> ops.saveSkill(skill.key, text).onSuccess { load.reload() } },
                    subtitle = stringResource(if (skill.source == SkillSource.LIBRARY) R.string.agents_skill_library_editor_note else R.string.agents_skill_editor_note),
                    onReload = { sheet = null; open(skill) }, tag = "skill.editor",
                )
            }
        }
    }
}

@Composable
private fun filterLabel(filter: SkillRules.Filter): String = when (filter) {
    SkillRules.Filter.ALL -> stringResource(R.string.kit_filter_all)
    SkillRules.Filter.YOURS -> stringResource(R.string.agents_skill_filter_yours)
    SkillRules.Filter.LIBRARY -> stringResource(R.string.agents_skill_filter_library)
    SkillRules.Filter.BUILTIN -> stringResource(R.string.agents_skill_filter_builtin)
}

@Composable
private fun categoryTitle(key: String, name: String): String = when (key) {
    "user" -> stringResource(R.string.agents_skill_category_user)
    SkillRules.LIBRARY_CATEGORY -> stringResource(R.string.agents_skill_library)
    else -> name
}

/** One skill: its name with its marks, its description, its switch, and «⋯» (also a long press). Tapping opens it. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun SkillRow(skill: Skill, onSwitch: (Boolean) -> Unit, onAction: (SkillRules.Action) -> Unit) {
    val t = LocalTokens.current
    var menu by remember(skill.key) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag("skill.${skill.key}")
            .combinedClickable(onClick = { onAction(SkillRules.Action.OPEN) }, onLongClick = { menu = true }),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InContentDirection(skill.name) { Text(skill.name, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.Medium, color = t.text) }
                if (skill.pinned) LucideIcon(Lucide.Pin, stringResource(R.string.agents_skill_pinned), Modifier.padding(top = 3.dp), size = 14.dp, tint = t.textMuted)
                if (SkillRules.broken(skill)) Badge(stringResource(R.string.agents_skill_broken), tone = BadgeTone.Warning)
                if (SkillRules.readOnly(skill)) Badge(stringResource(R.string.agents_skill_builtin))
                if (skill.source == SkillSource.LIBRARY) Badge(stringResource(R.string.agents_skill_library), tone = BadgeTone.Accent)
                if (SkillRules.edited(skill)) Badge(stringResource(R.string.agents_skill_edited), tone = BadgeTone.Warning)
            }
            skill.description?.takeIf { it.isNotBlank() && !SkillRules.broken(skill) }?.let { d ->
                InContentDirection(d) { Text(d, fontSize = FontTokens.sizeSm.sp, color = t.textMuted, maxLines = 2) }
            }
        }
        HubSwitch(skill.enabled, onSwitch, modifier = Modifier.testTag("skill.${skill.key}.switch"))
        Box {
            HubIconButton(Lucide.Ellipsis, stringResource(R.string.kit_more_actions), { menu = true }, size = 32.dp, iconSize = 16.dp, modifier = Modifier.testTag("skill.${skill.key}.more"))
            HubMenu(menu, { menu = false }) {
                SkillRules.actions(skill).forEach { action ->
                    val (label, icon) = when (action) {
                        SkillRules.Action.OPEN -> stringResource(R.string.agents_skill_open) to Lucide.FileText
                        SkillRules.Action.PIN -> stringResource(R.string.agents_skill_pin) to Lucide.Pin
                        SkillRules.Action.UNPIN -> stringResource(R.string.agents_skill_unpin) to Lucide.PinOff
                        SkillRules.Action.RESTORE -> stringResource(R.string.agents_skill_restore) to Lucide.RotateCcw
                        SkillRules.Action.DELETE -> stringResource(R.string.agents_skill_delete) to Lucide.Trash
                    }
                    MenuItem(
                        label, { menu = false; onAction(action) }, icon = icon, danger = action == SkillRules.Action.DELETE,
                        modifier = Modifier.testTag("skill.${skill.key}.${action.name.lowercase()}"),
                    )
                }
            }
        }
    }
}

/** Core Hub's library in this profile: how many are here, how many were edited, Install, and the switch. */
@Composable
internal fun SkillLibraryCard(library: SkillLibrary, onSwitch: (Boolean) -> Unit, onInstall: () -> Unit) {
    val t = LocalTokens.current
    HubCard(Modifier.testTag("skills.library"), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LucideIcon(Lucide.Sparkles, null, size = 18.dp, tint = t.accent)
            Text(stringResource(R.string.agents_skill_library), fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (library.edited > 0) Badge(stringResource(R.string.agents_skill_edited_count, library.edited.toString()), tone = BadgeTone.Warning)
            HubSwitch(library.enabled, onSwitch, modifier = Modifier.testTag("skills.library.switch"))
        }
        Text(
            when (SkillRules.libraryLine(library)) {
                SkillRules.LibraryLine.ON -> stringResource(R.string.agents_skill_library_on, library.installed.toString(), library.available.toString())
                SkillRules.LibraryLine.NONE -> stringResource(R.string.agents_skill_library_none, library.available.toString())
                SkillRules.LibraryLine.OFF -> stringResource(R.string.agents_skill_library_off)
            },
            fontSize = FontTokens.sizeSm.sp, color = t.textMuted,
        )
        if (SkillRules.libraryMissing(library)) {
            HubButton(stringResource(R.string.agents_skill_library_install), onInstall, kind = ButtonKind.Secondary, size = ControlSize.Sm, modifier = Modifier.testTag("skills.library.install"))
        }
    }
}

/** A new skill: its key (the folder's name) and its `SKILL.md`, starting from the front matter Hermes needs. */
@Composable
private fun NewSkillSheet(onDismiss: () -> Unit, onSave: suspend (String, String) -> Result<*>) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var text by remember { mutableStateOf(SkillRules.TEMPLATE) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val bad = key.isNotEmpty() && !SkillRules.validKey(key)
    HubSheet(onDismiss = onDismiss, title = stringResource(R.string.agents_skill_new)) {
        Text(stringResource(R.string.agents_skill_editor_note), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted)
        ToolErrorNotice(error)
        HubTextField(
            key, { key = it.trim() }, label = stringResource(R.string.agents_skill_key), placeholder = "my-skill", mono = true,
            size = ControlSize.Md, error = if (bad) stringResource(R.string.agents_skill_key_hint) else null, fieldTag = "skill.new.key",
        )
        DocumentField(text, { text = it }, markdown = false, Modifier.heightIn(min = 200.dp), tag = "skill.new.text", minLines = 8, maxLines = 16)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubButton(
                stringResource(R.string.save), {
                    saving = true
                    scope.launch {
                        onSave(key, text).onSuccess { onDismiss() }.onFailure { error = it as HubError }
                        saving = false
                    }
                },
                size = ControlSize.Md, icon = Lucide.Check, loading = saving, enabled = SkillRules.validKey(key), modifier = Modifier.testTag("skill.new.save"),
            )
            HubButton(stringResource(R.string.cancel), onDismiss, kind = ButtonKind.Ghost, size = ControlSize.Md)
        }
    }
}

internal val agentSkillsPage = AgentPageEntry("agent_skills") { agent, profile -> SkillsPage(agent, profile) }

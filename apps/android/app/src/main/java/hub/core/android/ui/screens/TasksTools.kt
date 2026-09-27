package hub.core.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.FormBody
import hub.core.android.ui.components.FormField
import hub.core.android.ui.components.FormKind
import hub.core.android.ui.components.FormOption
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.Chip
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Project
import hub.core.client.model.ProjectStatus
import hub.core.client.model.ProjectWrite
import hub.core.client.model.TaskBulkUpdatePatch
import kotlinx.coroutines.launch

/*
 * Tasks II (batch 5) on the board: the project filter, "Select" and what is done to the ticked
 * cards (a priority, the same comment, the archive, delete — §103, one call per profile), and the
 * projects sheet — the selector's profile's projects, made, renamed, paused or archived, their git
 * repository set (the web's project settings), deleted after saying that their tasks go with them.
 * iOS's twins are TasksBoard.swift (boardTools, bulkBar) and ProjectsSheet.swift.
 */

/** The row above the columns: which project, Select, and the projects sheet. */
@Composable
internal fun BoardTools(ui: TasksUi, vm: TasksViewModel) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (ui.projects.size > 1 || ui.projectId != null) {
            Box {
                Chip(
                    ui.projects.firstOrNull { it.id == ui.projectId }?.name ?: stringResource(R.string.taskl_filter_all),
                    selected = ui.projectId != null, onClick = { open = true }, size = ControlSize.Sm,
                    modifier = Modifier.testTag("tasks.project_filter"),
                    leading = { LucideIcon(Lucide.ListFilter, null, size = 14.dp) },
                )
                HubMenu(open, { open = false }) {
                    MenuItem(stringResource(R.string.taskl_filter_all), { open = false; vm.filter(null) }, checked = ui.projectId == null, modifier = Modifier.testTag("tasks.project_filter.all"))
                    ui.projects.forEach { project ->
                        MenuItem(project.name, { open = false; vm.filter(project.id) }, checked = ui.projectId == project.id, modifier = Modifier.testTag("tasks.project_filter.${project.id}"))
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Chip(
            stringResource(if (ui.selecting) R.string.taskl_bulk_done else R.string.taskl_bulk_select), selected = ui.selecting,
            onClick = { vm.selecting(!ui.selecting) }, size = ControlSize.Sm, modifier = Modifier.testTag("tasks.select"),
        )
        HubIconButton(Lucide.Folder, stringResource(R.string.taskl_projects_title), { vm.managing(true) }, size = 32.dp, iconSize = 16.dp, modifier = Modifier.testTag("tasks.projects"))
    }
    ui.bulkRefused?.let { (changed, refused) ->
        NoticeBox(stringResource(R.string.taskl_bulk_refused, changed, refused), BadgeTone.Warning, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

/** What is done to every ticked card at once. */
@Composable
internal fun BulkBar(ui: TasksUi, vm: TasksViewModel) {
    val t = LocalTokens.current
    val chosen = vm.tickedTasks()
    val enabled = chosen.isNotEmpty() && !ui.bulkBusy
    var priorities by remember { mutableStateOf(false) }
    var commenting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(t.bgRaised).padding(horizontal = 16.dp, vertical = 8.dp).testTag("tasks.bulk.bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.taskl_bulk_count, chosen.size), Modifier.weight(1f).testTag("tasks.bulk.count"),
            fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.SemiBold,
        )
        if (ui.bulkBusy) Spinner(18.dp)
        Box {
            HubIconButton(Lucide.Gauge, stringResource(R.string.taskl_bulk_priority), { priorities = true }, enabled = enabled, modifier = Modifier.testTag("tasks.bulk.priority"))
            HubMenu(priorities, { priorities = false }) {
                TaskRules.PRIORITIES.forEach { priority ->
                    MenuItem(priorityText(priority), { priorities = false; vm.bulk(TaskBulkUpdatePatch(priority = priority)) }, modifier = Modifier.testTag("tasks.bulk.priority.${priority.value}"))
                }
            }
        }
        HubIconButton(Lucide.MessagesSquare, stringResource(R.string.taskl_bulk_comment), { commenting = true }, enabled = enabled, modifier = Modifier.testTag("tasks.bulk.comment"))
        if (BulkRules.archivable(chosen)) {
            HubIconButton(Lucide.Archive, stringResource(R.string.taskl_bulk_archive), { vm.bulk(TaskBulkUpdatePatch(archived = true)) }, enabled = enabled, modifier = Modifier.testTag("tasks.bulk.archive"))
        }
        HubIconButton(Lucide.Trash, stringResource(R.string.kit_delete), { deleting = true }, enabled = enabled, tint = t.danger, modifier = Modifier.testTag("tasks.bulk.delete"))
    }
    if (commenting) {
        var words by remember { mutableStateOf("") }
        HubDialog({ commenting = false }, stringResource(R.string.taskl_bulk_comment_title, chosen.size)) {
            HubTextField(words, { words = it }, placeholder = stringResource(R.string.taskl_comments_placeholder), singleLine = false, maxLines = 6, fieldTag = "tasks.bulk.comment.input")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                HubButton(stringResource(R.string.cancel), { commenting = false }, kind = ButtonKind.Secondary, size = ControlSize.Md)
                HubButton(
                    stringResource(R.string.taskl_comments_send), { commenting = false; vm.bulk(TaskBulkUpdatePatch(comment = words.trim())) },
                    size = ControlSize.Md, enabled = words.isNotBlank(), modifier = Modifier.testTag("tasks.bulk.comment.ok"),
                )
            }
        }
    }
    if (deleting) {
        ConfirmDialog(
            stringResource(R.string.taskl_bulk_delete_confirm, chosen.size), stringResource(R.string.kit_delete_body), stringResource(R.string.kit_delete),
            onConfirm = { deleting = false; vm.bulkDelete() }, onDismiss = { deleting = false }, danger = true,
        )
    }
}

/** What the projects sheet shows in place of the list while a form is open. */
private sealed interface ProjectMode {
    data object List : ProjectMode
    data object New : ProjectMode
    data class Edit(val project: Project) : ProjectMode
}

/** The selector's profile's projects: active and paused, then the archive. */
@Composable
fun ProjectsSheet(profile: String, title: String, ops: TaskOps, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var lists by remember(profile) { mutableStateOf<Pair<List<Project>, List<Project>>?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    var mode by remember { mutableStateOf<ProjectMode>(ProjectMode.List) }
    var deleting by remember { mutableStateOf<Project?>(null) }
    suspend fun load() {
        ops.allProjects(profile).onSuccess { lists = it; error = null }.onFailure { error = it as? HubError; if (lists == null) lists = emptyList<Project>() to emptyList() }
    }
    LaunchedEffect(profile) { load() }
    fun act(block: suspend () -> Result<*>) {
        scope.launch {
            block().onSuccess { error = null }.onFailure { error = it as? HubError ?: HubError(-1, null, it.message) }
            load()
            onChanged()
        }
    }
    HubSheet(onDismiss = onDismiss, title = title) {
        when (val m = mode) {
            ProjectMode.List -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("projects.sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ErrorNotice(error)
                val loaded = lists
                if (loaded == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner(24.dp) }
                    return@Column
                }
                GroupedList {
                    if (loaded.first.isEmpty()) Item(stringResource(R.string.taskl_projects_none))
                    loaded.first.forEach { ProjectRow(it, { mode = ProjectMode.Edit(it) }, { act { ops.updateProject(profile, it.id, ProjectWrite(status = ProjectRules.toggledArchive(it.status))) } }, { deleting = it }) }
                }
                Text(stringResource(R.string.taskl_projects_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                if (loaded.second.isNotEmpty()) {
                    GroupedList(title = stringResource(R.string.taskl_projects_archived)) {
                        loaded.second.forEach { ProjectRow(it, { mode = ProjectMode.Edit(it) }, { act { ops.updateProject(profile, it.id, ProjectWrite(status = ProjectRules.toggledArchive(it.status))) } }, { deleting = it }) }
                    }
                }
                HubButton(stringResource(R.string.taskl_projects_new), { mode = ProjectMode.New }, size = ControlSize.Md, icon = Lucide.Plus, modifier = Modifier.testTag("projects.new"))
            }
            ProjectMode.New, is ProjectMode.Edit -> {
                val original = (m as? ProjectMode.Edit)?.project
                val initial = ProjectRules.values(original)
                Text(stringResource(if (original == null) R.string.taskl_projects_new else R.string.taskl_projects_edit), fontSize = FontTokens.sizeLg.sp, fontWeight = FontWeight.SemiBold)
                FormBody(
                    projectFields(original != null), initial,
                    onDone = { mode = ProjectMode.List },
                    onSave = { values ->
                        val result = if (original == null) ops.createProject(profile, ProjectRules.create(values))
                        else ProjectRules.patch(initial, values)?.let { ops.updateProject(profile, original.id, it) } ?: Result.success(Unit)
                        result.onSuccess { load(); onChanged() }
                    },
                    saveLabel = if (original == null) stringResource(R.string.taskd_form_create) else stringResource(R.string.save),
                    tag = "project.form",
                )
            }
        }
    }
    deleting?.let { project ->
        ConfirmDialog(
            stringResource(R.string.kit_delete_confirm, project.name), stringResource(R.string.taskl_projects_delete_body, project.counts.total), stringResource(R.string.kit_delete),
            onConfirm = { deleting = null; act { ops.deleteProject(profile, project.id) } }, onDismiss = { deleting = null }, danger = true,
        )
    }
}

@Composable
private fun hub.core.android.ui.kit.GroupScope.ProjectRow(project: Project, onEdit: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Item(
        project.name, tag = "project.${project.id}", icon = Lucide.Folder,
        subtitle = listOfNotNull(stringResource(R.string.taskl_projects_count, project.counts.total), project.workingDir).joinToString(" · "),
        onClick = onEdit,
        trailing = {
            if (project.status != ProjectStatus.ACTIVE) Badge(projectStatusText(project.status), tone = if (project.status == ProjectStatus.PAUSED) BadgeTone.Warning else BadgeTone.Neutral)
            Box {
                HubIconButton(Lucide.EllipsisVertical, stringResource(R.string.kit_more_actions), { menu = true }, size = 32.dp, iconSize = 16.dp, modifier = Modifier.testTag("project.menu.${project.id}"))
                HubMenu(menu, { menu = false }) {
                    MenuItem(stringResource(R.string.kit_edit), { menu = false; onEdit() }, icon = Lucide.Pencil)
                    MenuItem(
                        stringResource(if (project.status == ProjectStatus.ARCHIVED) R.string.taskl_projects_restore else R.string.taskl_projects_archive),
                        { menu = false; onArchive() }, icon = if (project.status == ProjectStatus.ARCHIVED) Lucide.ArchiveRestore else Lucide.Archive,
                        modifier = Modifier.testTag("project.archive.${project.id}"),
                    )
                    MenuItem(stringResource(R.string.kit_delete), { menu = false; onDelete() }, icon = Lucide.Trash, danger = true, modifier = Modifier.testTag("project.delete.${project.id}"))
                }
            }
        },
    )
}

@Composable
private fun projectStatusText(status: ProjectStatus): String = stringResource(
    when (status) {
        ProjectStatus.ACTIVE -> R.string.taskl_projects_status_active
        ProjectStatus.PAUSED -> R.string.taskl_projects_status_paused
        ProjectStatus.ARCHIVED -> R.string.taskl_projects_status_archived
    },
)

@Composable
private fun projectFields(editing: Boolean): List<FormField> = buildList {
    add(FormField("name", stringResource(R.string.taskl_projects_name), required = true))
    if (editing) {
        add(
            FormField(
                "status", stringResource(R.string.taskl_projects_status), FormKind.Choice, required = true,
                options = ProjectRules.STATUSES.map { FormOption(it.value, projectStatusText(it)) },
            ),
        )
    }
    add(FormField("repository", stringResource(R.string.taskl_projects_repository), help = stringResource(R.string.taskl_projects_repository_help), mono = true))
    add(FormField("branch", stringResource(R.string.taskl_projects_branch), help = stringResource(R.string.taskl_projects_branch_help), mono = true))
}

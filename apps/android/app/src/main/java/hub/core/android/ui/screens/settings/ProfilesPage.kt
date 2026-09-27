package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubRadio
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Profile
import kotlinx.coroutines.launch

/**
 * Profiles (admin): the separate rooms this hub keeps (ADR 0005), each a Hermes profile (ADR 0014).
 * The drawer's switcher changes which one the person is in; this page decides what exists: a new
 * one (from scratch or as a copy), a new name (never a new id), archive, export and import.
 */
@Composable
private fun ProfilesPage(current: String, isAdmin: Boolean, shell: ShellViewModel) {
    if (!isAdmin) return AdminOnlyNote()
    val ops = rememberAdminTwoOps(current)
    val t = LocalTokens.current
    val profiles = rememberLoad("admin-profiles") { ops.profiles().getOrThrow() }
    var menu by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var exporting by remember { mutableStateOf<Profile?>(null) }
    var imported by remember { mutableStateOf<String?>(null) }
    val archiving = rememberConfirmDelete<Profile>()
    fun changed() { profiles.reload(); shell.loadProfiles() }
    LoadView(profiles) { list ->
        LazyColumn(contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("profiles.page")) {
            item { Text(stringResource(R.string.admin_profiles_note), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    HubButton(stringResource(R.string.admin_profiles_import), { importing = true }, kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.File, modifier = Modifier.testTag("profiles.import"))
                    HubButton(stringResource(R.string.admin_profiles_add), { adding = true }, size = ControlSize.Sm, icon = Lucide.Plus, modifier = Modifier.testTag("profiles.add"))
                }
            }
            imported?.let { name -> item { NoticeBox(stringResource(R.string.admin_profiles_import_done, name), BadgeTone.Success) } }
            item {
                GroupedList {
                    list.forEach { p ->
                        Item(
                            p.name, subtitle = "${p.slug} · " + stringResource(R.string.admin_profiles_counts, p.agentCount, p.sessionCount),
                            icon = Lucide.LayoutGrid, tag = "profile.${p.slug}",
                            trailing = {
                                if (p.slug == current) Badge(stringResource(R.string.admin_profiles_current), tone = BadgeTone.Accent)
                                if (p.slug == "default") Badge(stringResource(R.string.admin_profiles_default))
                                Box {
                                    HubIconButton(Lucide.Ellipsis, stringResource(R.string.chat_more), { menu = p.id }, size = 32.dp, iconSize = 16.dp, modifier = Modifier.testTag("profile.${p.slug}.more"))
                                    HubMenu(menu == p.id, { menu = null }) {
                                        MenuItem(stringResource(R.string.admin_profiles_rename), { menu = null; renaming = p }, icon = Lucide.Pencil)
                                        MenuItem(stringResource(R.string.admin_profiles_export), { menu = null; exporting = p }, icon = Lucide.Download)
                                        if (ProfileRules.canArchive(p.slug)) MenuItem(stringResource(R.string.admin_profiles_archive), { menu = null; archiving.ask(p) }, icon = Lucide.Archive, danger = true)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        if (adding) AddProfileSheet(ops, list, onDone = { made -> adding = false; if (made) changed() })
        if (importing) ImportProfileSheet(ops, list.map { it.slug }, onDismiss = { importing = false }, onImported = { name -> importing = false; imported = name; changed() })
    }
    renaming?.let { p -> RenameProfileDialog(ops, p, onDone = { renamed -> renaming = null; if (renamed) changed() }) }
    exporting?.let { p -> ExportProfileSheet(ops, p, onDismiss = { exporting = null }) }
    // The hub's word is «archive»: the rows stay and only the memberships go (the body says exactly that).
    ConfirmDeleteDialog(
        archiving, { stringResource(R.string.admin_profiles_archive_title, it.name) },
        onDelete = { ops.archiveProfile(it.id) }, onDeleted = { changed() },
        body = archiving.pending?.let { stringResource(R.string.admin_profiles_archive_body, it.sessionCount) },
        confirm = stringResource(R.string.admin_profiles_archive),
    )
}

/** The hub's refusal, in Hermes's own words when Hermes said no. */
@Composable
internal fun ProfileRefusal(error: HubError?) {
    val hermes = ProfileRules.hermesRefusal(error)
    if (hermes != null) NoticeBox(stringResource(R.string.admin_profiles_refused, hermes), BadgeTone.Danger) else ErrorNotice(error)
}

@Composable
private fun AddProfileSheet(ops: AdminTwoOps, existing: List<Profile>, onDone: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var name by remember { mutableStateOf("") }
    var slug by remember { mutableStateOf("") }
    var copy by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val taken = existing.map { it.slug }
    val body = ProfileRules.create(name, slug, copy, from, taken)
    HubSheet(onDismiss = { onDone(false) }, title = stringResource(R.string.admin_profiles_add)) {
        HubTextField(name, { next -> slug = ProfileRules.followName(slug, name, next); name = next.take(ProfileRules.NAME_MAX) },
            label = stringResource(R.string.admin_profiles_name), size = ControlSize.Md, fieldTag = "profile.name")
        SlugField(slug, taken) { slug = it }
        Text(stringResource(R.string.admin_profiles_origin), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        Segmented(
            listOf(Segment(false, stringResource(R.string.admin_profiles_origin_blank)), Segment(true, stringResource(R.string.admin_profiles_origin_clone))),
            copy, { copy = it }, Modifier.fillMaxWidth().testTag("profile.origin"), size = ControlSize.Sm,
        )
        Text(stringResource(if (copy) R.string.admin_profiles_clone_what else R.string.admin_profiles_origin_blank_what), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        if (copy) GroupedList(title = stringResource(R.string.admin_profiles_clone)) {
            existing.forEach { p ->
                Item(p.name, subtitle = p.slug, tag = "profile.clone.${p.slug}", onClick = { from = p.slug }, trailing = { HubRadio(from == p.slug) })
            }
        }
        ProfileRefusal(error)
        HubButton(stringResource(R.string.admin_profiles_add), {
            val b = body ?: return@HubButton
            busy = true
            scope.launch {
                ops.createProfile(b).onSuccess { onDone(true) }.onFailure { error = it as? HubError }
                busy = false
            }
        }, icon = Lucide.Plus, fill = true, loading = busy, enabled = body != null, modifier = Modifier.fillMaxWidth().testTag("profile.create"))
    }
}

/** The slug: left to right, checked here so the field says so before the hub does. */
@Composable
internal fun SlugField(slug: String, taken: Collection<String>, onChange: (String) -> Unit) {
    HubTextField(
        slug, { onChange(it.lowercase().trim()) }, label = stringResource(R.string.admin_profiles_slug), placeholder = stringResource(R.string.admin_profiles_slug_hint),
        mono = true, size = ControlSize.Md, fieldTag = "profile.slug",
        error = when (ProfileRules.slugProblem(slug, taken)) {
            ProfileRules.SlugProblem.BAD -> stringResource(R.string.admin_profiles_slug_bad)
            ProfileRules.SlugProblem.TAKEN -> stringResource(R.string.admin_profiles_slug_taken)
            null -> null
        },
    )
}

/** A new name, never a new id: the slug stays, since chats, channels and schedules use it. */
@Composable
private fun RenameProfileDialog(ops: AdminTwoOps, p: Profile, onDone: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var name by remember(p.id) { mutableStateOf(p.name) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val patch = ProfileRules.rename(p.name, name)
    HubDialog({ onDone(false) }, stringResource(R.string.admin_profiles_rename_title, p.name)) {
        HubTextField(name, { name = it.take(ProfileRules.NAME_MAX) }, label = stringResource(R.string.admin_profiles_name), size = ControlSize.Md, fieldTag = "profile.rename")
        Text(stringResource(R.string.admin_profiles_rename_hint, p.slug), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        ProfileRefusal(error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            HubButton(stringResource(R.string.cancel), { onDone(false) }, kind = ButtonKind.Secondary, size = ControlSize.Md)
            HubButton(stringResource(R.string.save), {
                val body = patch ?: return@HubButton
                busy = true
                scope.launch {
                    ops.renameProfile(p.id, body).onSuccess { onDone(true) }.onFailure { error = it as? HubError }
                    busy = false
                }
            }, size = ControlSize.Md, loading = busy, enabled = patch != null, modifier = Modifier.testTag("profile.rename.save"))
        }
    }
}

/** Profiles (destination `workspaces`: the code's word; a person reads «profile»). */
internal val workspacesPage = SettingsPageEntry("workspaces") { ProfilesPage(it.session.profile, it.session.user.isAdmin, it.shell) }

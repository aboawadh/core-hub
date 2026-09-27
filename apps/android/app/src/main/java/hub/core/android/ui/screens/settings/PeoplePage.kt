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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import hub.core.android.ui.kit.HubCheckbox
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuDivider
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Profile
import hub.core.client.model.User
import hub.core.client.model.UserAdminPatch
import hub.core.client.model.UserStatus
import kotlinx.coroutines.launch

/**
 * People (admin): everyone on the hub, what each may enter, and what the row offers — only what
 * the hub accepts (the owner is not edited by an admin; nobody disables or deletes themselves).
 * Below them, everyone's linked messaging accounts and the addresses that locked themselves.
 */
@Composable
fun PeoplePage(profile: String, me: String, isAdmin: Boolean) {
    if (!isAdmin) return AdminOnlyNote()
    val ops = rememberAdminTwoOps(profile)
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val users = rememberLoad("people") { ops.users().getOrThrow() }
    var adding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf<User?>(null) }
    var placing by remember { mutableStateOf<Pair<User, Boolean>?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val deleting = rememberConfirmDelete<User>()
    fun act(r: Result<*>) { r.onFailure { error = it as? HubError }.onSuccess { error = null }; users.reload() }
    LoadView(users) { list ->
        LazyColumn(contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("people.list")) {
            item { ErrorNotice(error) }
            item {
                SectionTitle(stringResource(R.string.admin_people_title), trailing = {
                    HubButton(stringResource(R.string.people_add), { adding = true }, icon = Lucide.UserPlus, kind = ButtonKind.Subtle, size = ControlSize.Sm, modifier = Modifier.testTag("people.add"))
                })
            }
            item {
                GroupedList {
                    list.forEach { u ->
                        val row = PeopleRules.row(u, me)
                        Item(
                            u.displayName.ifBlank { u.username }, subtitle = "@${u.username} · ${reachText(u)}", icon = Lucide.CircleUserRound, tag = "person.${u.username}",
                            trailing = {
                                if (u.id == me) Badge(stringResource(R.string.admin_people_you), tone = BadgeTone.Info)
                                if (u.status == UserStatus.DISABLED) Badge(stringResource(R.string.admin_people_status_disabled), tone = BadgeTone.Warning)
                                Badge(stringResource(roleText(u.role.value)), tone = if (u.role.value == "member") BadgeTone.Neutral else BadgeTone.Accent)
                                if (row != PeopleRules.Row.OWNER_NOTE) Box {
                                    HubIconButton(Lucide.Ellipsis, stringResource(R.string.chat_more), { menu = u.id }, size = 32.dp, iconSize = 16.dp, modifier = Modifier.testTag("person.${u.username}.more"))
                                    HubMenu(menu == u.id, { menu = null }) {
                                        MenuItem(stringResource(R.string.people_set_password), { menu = null; password = u }, icon = Lucide.KeyRound)
                                        if (row == PeopleRules.Row.FULL) {
                                            val admin = u.role.value == "admin"
                                            MenuItem(stringResource(if (admin) R.string.people_make_member else R.string.people_make_admin), {
                                                menu = null
                                                // An admin holds no list: becoming a member means choosing, right then, what they may enter.
                                                if (admin) placing = u to true
                                                else scope.launch { act(ops.updateUser(u.id, UserAdminPatch(role = UserAdminPatch.Role.ADMIN))) }
                                            }, icon = Lucide.ShieldCheck)
                                            if (u.role.value == "member") MenuItem(stringResource(R.string.admin_people_profiles_edit), { menu = null; placing = u to false }, icon = Lucide.LayoutGrid)
                                            if (PeopleRules.canDisableOrDelete(u, me)) {
                                                MenuDivider()
                                                MenuItem(stringResource(if (u.status == UserStatus.DISABLED) R.string.people_enable else R.string.people_disable), {
                                                    menu = null
                                                    val next = if (u.status == UserStatus.DISABLED) UserStatus.ACTIVE else UserStatus.DISABLED
                                                    scope.launch { act(ops.updateUser(u.id, UserAdminPatch(status = next))) }
                                                }, icon = Lucide.Power)
                                                MenuItem(stringResource(R.string.presets_delete), { menu = null; deleting.ask(u) }, icon = Lucide.Trash, danger = true)
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            item { Text(stringResource(R.string.people_owner_note), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
            item { AllChannelAccountsSection(ops, list) }
            item { LockoutsSection(ops) }
        }
    }
    if (adding) AddPersonSheet(ops, onDone = { adding = false; users.reload() })
    password?.let { u -> SetPasswordDialog(u, own = u.id == me, onDismiss = { password = null }) { typed -> act(ops.updateUser(u.id, UserAdminPatch(password = typed))) } }
    placing?.let { (u, makeMember) -> PersonProfilesSheet(ops, u, makeMember, onDone = { placing = null; users.reload() }) }
    ConfirmDeleteDialog(
        deleting, { stringResource(R.string.people_delete_title, it.displayName.ifBlank { it.username }) },
        onDelete = { ops.deleteUser(it.id) }, onDeleted = { users.reload() },
        body = stringResource(R.string.people_delete_body), confirm = stringResource(R.string.presets_delete),
    )
}

@Composable
private fun reachText(u: User): String = when (PeopleRules.reach(u)) {
    PeopleRules.Reach.EVERY -> stringResource(R.string.admin_people_every_profile)
    PeopleRules.Reach.NONE -> stringResource(R.string.admin_people_no_profile)
    PeopleRules.Reach.LISTED -> u.profiles.joinToString(", ")
}

private fun roleText(role: String): Int = when (role) {
    "owner" -> R.string.people_role_owner
    "admin" -> R.string.people_role_admin
    else -> R.string.people_role_member
}

/** «Only an owner or an admin manages this page»: what a member sees if a link brings them here. */
@Composable
internal fun AdminOnlyNote() {
    LazyColumn(contentPadding = settingsPagePadding) {
        item { NoticeBox(stringResource(R.string.admin_only_note), BadgeTone.Info, Modifier.testTag("admin.only")) }
    }
}

/** A new password, typed once and emptied the moment it is sent; never shown back. */
@Composable
private fun SetPasswordDialog(u: User, own: Boolean, onDismiss: () -> Unit, onSave: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var typed by remember(u.id) { mutableStateOf("") }
    HubDialog(onDismiss, stringResource(R.string.people_password_for, u.displayName.ifBlank { u.username })) {
        Text(stringResource(if (own) R.string.admin_people_own_password_note else R.string.people_password_note), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        HubTextField(
            typed, { typed = it }, visualTransformation = PasswordVisualTransformation(), fieldTag = "person.password",
            error = if (typed.isNotEmpty() && !AdminRules.passwordOk(typed)) stringResource(R.string.people_password_short) else null,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            HubButton(stringResource(R.string.cancel), onDismiss, kind = ButtonKind.Secondary, size = ControlSize.Md)
            HubButton(stringResource(R.string.save), {
                val sent = typed
                typed = ""
                onDismiss()
                scope.launch { onSave(sent) }
            }, size = ControlSize.Md, enabled = AdminRules.passwordOk(typed), modifier = Modifier.testTag("person.password.save"))
        }
    }
}

/** The profiles a member may enter, one box each; or an admin's, as the member they are about to become. */
@Composable
private fun PersonProfilesSheet(ops: AdminTwoOps, u: User, makeMember: Boolean, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val profiles = rememberLoad("profiles") { ops.profiles().getOrThrow() }
    var chosen by remember(u.id, makeMember) { mutableStateOf(PeopleRules.startingChoice(u, makeMember)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val name = u.displayName.ifBlank { u.username }
    val patch = PeopleRules.profilesPatch(chosen, makeMember)
    HubSheet(onDismiss = onDone, title = stringResource(if (makeMember) R.string.admin_people_make_member_for else R.string.admin_people_profiles_for, name)) {
        Text(stringResource(R.string.admin_people_profiles_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        ErrorNotice(error)
        ProfileChoice(profiles, chosen) { chosen = it }
        if (chosen.isEmpty()) Text(stringResource(R.string.admin_people_profiles_required), fontSize = FontTokens.sizeXs.sp, color = t.danger, modifier = Modifier.testTag("person.profiles.required"))
        HubButton(stringResource(R.string.save), {
            val body = patch ?: return@HubButton
            busy = true
            scope.launch {
                ops.updateUser(u.id, body).onSuccess { onDone() }.onFailure { error = it as? HubError }
                busy = false
            }
        }, fill = true, loading = busy, enabled = patch != null, modifier = Modifier.fillMaxWidth().testTag("person.profiles.save"))
    }
}

@Composable
private fun ProfileChoice(profiles: hub.core.android.ui.components.Loader<List<Profile>>, chosen: List<String>, onChange: (List<String>) -> Unit) {
    LoadView(profiles) { list ->
        GroupedList(title = stringResource(R.string.people_profiles)) {
            list.forEach { p ->
                Item(
                    p.name, subtitle = p.slug, tag = "person.profile.${p.slug}",
                    onClick = { onChange(if (p.slug in chosen) chosen - p.slug else chosen + p.slug) },
                    trailing = { HubCheckbox(p.slug in chosen, null) },
                )
            }
        }
    }
}

@Composable
private fun AddPersonSheet(ops: AdminTwoOps, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val profiles = rememberLoad("profiles") { ops.profiles().getOrThrow() }
    var username by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<HubError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val body = AdminRules.create(username, name, password, admin, chosen)
    HubSheet(onDismiss = onDone, title = stringResource(R.string.people_add)) {
        ErrorNotice(error)
        HubTextField(username, { username = it.lowercase() }, label = stringResource(R.string.people_username), placeholder = stringResource(R.string.people_username_hint), mono = true, size = ControlSize.Md,
            error = if (username.isNotEmpty() && !AdminRules.usernameOk(username)) stringResource(R.string.people_username_bad) else null, fieldTag = "person.username")
        HubTextField(name, { name = it }, label = stringResource(R.string.people_display_name), size = ControlSize.Md, fieldTag = "person.name")
        HubTextField(password, { password = it }, label = stringResource(R.string.people_password), visualTransformation = PasswordVisualTransformation(), size = ControlSize.Md,
            error = if (password.isNotEmpty() && !AdminRules.passwordOk(password)) stringResource(R.string.people_password_short) else null, fieldTag = "person.new_password")
        Segmented(
            listOf(Segment(false, stringResource(R.string.people_role_member)), Segment(true, stringResource(R.string.people_role_admin))),
            admin, { admin = it }, Modifier.fillMaxWidth(), size = ControlSize.Sm,
        )
        Text(stringResource(if (admin) R.string.people_admin_can else R.string.people_member_can), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        if (admin) {
            Text(stringResource(R.string.admin_people_admin_everywhere), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        } else {
            ProfileChoice(profiles, chosen) { chosen = it }
            if (chosen.isEmpty()) Text(stringResource(R.string.admin_people_profiles_required), fontSize = FontTokens.sizeXs.sp, color = t.warningSoftText)
        }
        HubButton(stringResource(R.string.people_add), {
            val b = body ?: return@HubButton
            busy = true
            scope.launch {
                ops.addUser(b).onSuccess { password = ""; onDone() }.onFailure { error = it as? HubError }
                busy = false
            }
        }, icon = Lucide.UserPlus, fill = true, loading = busy, enabled = body != null, modifier = Modifier.fillMaxWidth().testTag("person.create"))
    }
}

internal val usersPage = SettingsPageEntry("users") { PeoplePage(it.session.profile, it.session.user.id, it.session.user.isAdmin) }

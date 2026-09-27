package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.ChannelIdentity
import hub.core.client.model.ChannelIdentityPlatform
import hub.core.client.model.Lockout
import hub.core.client.model.User
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Everyone's linked Telegram and WhatsApp accounts (contract decision §79), each removable. */
@Composable
internal fun AllChannelAccountsSection(ops: AdminTwoOps, users: List<User>) {
    val t = LocalTokens.current
    val links = rememberLoad("admin-links") { ops.links().getOrThrow() }
    val removing = rememberConfirmDelete<ChannelIdentity>()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("people.links")) {
        SectionTitle(stringResource(R.string.admin_links_title))
        Text(stringResource(R.string.admin_links_subtitle), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        LoadView(links) { list ->
            if (list.isEmpty()) {
                Text(stringResource(R.string.admin_links_none), fontSize = FontTokens.sizeSm.sp, color = t.textMuted, modifier = Modifier.testTag("people.links.none"))
            } else {
                GroupedList {
                    list.forEach { link ->
                        Item(
                            PeopleRules.nameOf(link.userId, users),
                            subtitle = "${platformText(link.platform)} · ${link.senderId} · " +
                                (link.lastUsedAt?.let { stringResource(R.string.admin_links_last_used, localTime(it)) } ?: stringResource(R.string.admin_links_never_used)),
                            icon = Lucide.Link, tag = "link.${link.id}",
                            trailing = {
                                HubIconButton(Lucide.Trash, stringResource(R.string.admin_links_remove), { removing.ask(link) }, size = 32.dp, iconSize = 16.dp, modifier = Modifier.testTag("link.${link.id}.remove"))
                            },
                        )
                    }
                }
            }
        }
    }
    ConfirmDeleteDialog(
        removing, { stringResource(R.string.admin_links_remove_title, PeopleRules.nameOf(it.userId, users)) },
        onDelete = { ops.removeLink(it.id) }, onDeleted = { links.reload() },
        body = stringResource(R.string.admin_links_remove_body), confirm = stringResource(R.string.admin_links_remove),
    )
}

@Composable
private fun platformText(platform: ChannelIdentityPlatform): String = stringResource(
    when (platform) {
        ChannelIdentityPlatform.TELEGRAM -> R.string.admin_platform_telegram
        ChannelIdentityPlatform.WHATSAPP -> R.string.admin_platform_whatsapp
    },
)

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT)

/**
 * The addresses that locked themselves after failed sign-ins. An empty list is what a healthy hub
 * answers, drawn as such rather than hidden: «nobody is locked out» is information.
 */
@Composable
internal fun LockoutsSection(ops: AdminTwoOps) {
    val scope = rememberCoroutineScope()
    val lockouts = rememberLoad("admin-lockouts") { ops.lockouts().getOrThrow() }
    var error by remember { mutableStateOf<HubError?>(null) }
    var note by remember { mutableStateOf<Int?>(null) }
    fun clear(ip: String?) = scope.launch {
        ops.clearLockouts(ip).onSuccess { note = it; error = null }.onFailure { error = it as? HubError }
        lockouts.reload()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("people.lockouts")) {
        LoadView(lockouts) { list ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle(stringResource(R.string.admin_lockouts_title), trailing = {
                    if (list.isNotEmpty()) HubButton(
                        stringResource(R.string.admin_lockouts_clear_all), { clear(null) }, kind = ButtonKind.Ghost, size = ControlSize.Sm,
                        modifier = Modifier.testTag("lockouts.clear"),
                    )
                })
                ErrorNotice(error)
                note?.let { NoticeBox(stringResource(R.string.admin_lockouts_cleared, it), BadgeTone.Success) }
                if (list.isEmpty()) {
                    EmptyState(stringResource(R.string.admin_lockouts_none), body = stringResource(R.string.admin_lockouts_none_body), icon = Lucide.Shield)
                } else {
                    GroupedList {
                        list.forEach { row ->
                            Item(
                                row.ip, subtitle = stringResource(
                                    R.string.admin_lockouts_row, lockKind(row.kind), row.failures,
                                    row.lockedUntil.atZoneSameInstant(ZoneId.systemDefault()).format(clock),
                                ),
                                icon = Lucide.Shield, tag = "lockout.${row.ip}",
                                trailing = { HubButton(stringResource(R.string.admin_lockouts_unlock), { clear(row.ip) }, kind = ButtonKind.Ghost, size = ControlSize.Sm) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun lockKind(kind: Lockout.Kind): String = stringResource(
    when (kind) {
        Lockout.Kind.PASSWORD -> R.string.admin_lockouts_kind_password
        Lockout.Kind.TOKEN -> R.string.admin_lockouts_kind_token
        Lockout.Kind.PAIRING -> R.string.admin_lockouts_kind_pairing
    },
)

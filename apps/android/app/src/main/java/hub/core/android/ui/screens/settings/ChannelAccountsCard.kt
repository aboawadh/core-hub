package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.Notice
import hub.core.android.ui.components.Tone
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.ChannelIdentity
import hub.core.client.model.ChannelIdentityPlatform
import hub.core.client.model.ChannelLinkCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → Account → Messaging accounts: the person's own Telegram / WhatsApp links. A link code
 * is sent from that account to the agent's bot; the list is read again every few seconds until the
 * new link shows (the web's MyChannelAccounts). iOS's ChannelAccountsSection is the twin.
 */
@Composable
internal fun ChannelAccountsCard(ops: OwnSettingsOps) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var items by remember { mutableStateOf<List<ChannelIdentity>?>(null) }
    var code by remember { mutableStateOf<ChannelLinkCode?>(null) }
    var before by remember { mutableStateOf<Int?>(null) }
    var linked by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val unlinking = rememberConfirmDelete<ChannelIdentity>()

    suspend fun load() {
        ops.channels()
            .onSuccess { list ->
                if (OwnSettingsRules.linked(code != null, before, list.size)) {
                    code = null
                    linked = true
                }
                items = list
                error = null
            }
            .onFailure { error = it as HubError; if (items == null) items = emptyList() }
    }

    LaunchedEffect(Unit) { load() }
    // While a code waits, the list is asked again until the link shows up.
    LaunchedEffect(code?.code) {
        while (code != null) {
            delay(3_000)
            load()
        }
    }

    Column(Modifier.testTag("channels.card"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.own_settings_channels_title))
        Text(stringResource(R.string.own_settings_channels_subtitle), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        ErrorNotice(error)
        val list = items
        when {
            list == null -> Spinner()
            list.isEmpty() -> Text(
                stringResource(R.string.own_settings_channels_none), fontSize = FontTokens.sizeSm.sp, color = t.textMuted,
                modifier = Modifier.testTag("channels.empty"),
            )
            else -> GroupedList {
                list.forEach { identity ->
                    Item(
                        "${platformLabel(identity.platform)} · ${identity.senderId}",
                        subtitle = listOfNotNull(
                            stringResource(R.string.own_settings_channels_linked_at, localTime(identity.linkedAt)),
                            identity.lastUsedAt?.let { stringResource(R.string.own_settings_channels_last_used, localTime(it)) },
                        ).joinToString(" · "),
                        icon = Lucide.MessagesSquare,
                        tag = "channels.${identity.id}",
                        trailing = {
                            HubIconButton(Lucide.Trash, stringResource(R.string.own_settings_channels_unlink), { unlinking.ask(identity) })
                        },
                    )
                }
            }
        }
        if (linked) Notice(stringResource(R.string.own_settings_channels_linked), Tone.SUCCESS, Modifier.testTag("channels.linked"))
        val waiting = code
        if (waiting != null) {
            HubCard {
                Text(stringResource(R.string.own_settings_channels_send_this), fontSize = FontTokens.sizeSm.sp, color = t.text)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        waiting.command, Modifier.weight(1f).testTag("channels.command"),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = FontTokens.sizeSm.sp, color = t.text, textDirection = TextDirection.Ltr),
                    )
                    HubButton(
                        stringResource(if (copied) R.string.own_settings_channels_copied else R.string.own_settings_channels_copy),
                        { clipboard.setText(AnnotatedString(waiting.command)); copied = true },
                        kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.Copy,
                    )
                }
                Text(stringResource(R.string.own_settings_channels_expires, localTime(waiting.expiresAt)), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                Text(stringResource(R.string.own_settings_channels_must_answer), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(14.dp)
                    Text(stringResource(R.string.own_settings_channels_waiting), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                }
                HubButton(stringResource(R.string.cancel), { code = null }, kind = ButtonKind.Ghost, size = ControlSize.Sm, modifier = Modifier.testTag("channels.cancel"))
            }
        } else {
            HubButton(
                stringResource(R.string.own_settings_channels_link), {
                    busy = true
                    scope.launch {
                        ops.linkCode()
                            .onSuccess { issued ->
                                before = items?.size ?: 0
                                linked = false
                                copied = false
                                error = null
                                code = issued
                            }
                            .onFailure { error = it as HubError }
                        busy = false
                    }
                },
                kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.Link, loading = busy,
                modifier = Modifier.testTag("channels.link"),
            )
        }
    }
    ConfirmDeleteDialog(
        unlinking,
        title = { stringResource(R.string.own_settings_channels_unlink_title) },
        onDelete = { identity -> ops.unlink(identity.id) },
        onDeleted = { identity -> items = items?.filterNot { it.id == identity.id } },
        body = stringResource(R.string.own_settings_channels_unlink_body),
        confirm = stringResource(R.string.own_settings_channels_unlink),
    )
}

@Composable
private fun platformLabel(platform: ChannelIdentityPlatform): String = stringResource(
    when (platform) {
        ChannelIdentityPlatform.TELEGRAM -> R.string.own_settings_platform_telegram
        ChannelIdentityPlatform.WHATSAPP -> R.string.own_settings_platform_whatsapp
    },
)

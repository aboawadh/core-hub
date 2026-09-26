package hub.core.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.nav.AppPaths
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.Channel
import hub.core.client.model.ChannelCredentialField
import hub.core.client.model.ChannelPlatform
import hub.core.client.model.ChannelTokenLink
import kotlinx.coroutines.launch

/** Which channels link from the phone, and what the link sends. */
object ChannelLinks {
    /** A bot token or credentials link here; a QR platform (WhatsApp) is scanned from another screen. */
    fun onPhone(platform: ChannelPlatform): Boolean = platform.login != ChannelPlatform.Login.QR

    /** The fields the phone asks for, in the platform's order; Telegram's one bot token. */
    fun fields(platform: ChannelPlatform): List<ChannelCredentialField> =
        if (platform.login == ChannelPlatform.Login.TOKEN && platform.credentials.isEmpty()) {
            listOf(ChannelCredentialField(key = "token", kind = ChannelCredentialField.Kind.SECRET, required = true))
        } else platform.credentials

    fun missing(platform: ChannelPlatform, typed: Map<String, String>): List<String> =
        fields(platform).filter { it.required && typed[it.key].isNullOrBlank() }.map { it.key }

    fun request(platform: ChannelPlatform, typed: Map<String, String>, allowed: String): ChannelTokenLink {
        val users = allowed.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() && platform.allowedUsersKey != null }
        return if (platform.login == ChannelPlatform.Login.TOKEN) {
            ChannelTokenLink(token = (typed["token"] ?: typed.values.firstOrNull { it.isNotBlank() }).orEmpty().trim(), allowedUsers = users)
        } else {
            ChannelTokenLink(credentials = typed.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }, allowedUsers = users)
        }
    }

    /** A linked channel's account in one line: its name, then its handle or number. */
    fun account(channel: Channel): String? = channel.link?.takeIf { it.linked }?.let { l ->
        listOfNotNull(l.accountName, l.accountUsername?.let { "@$it" }, l.accountPhone).joinToString(" · ").ifEmpty { null }
    }
}

/**
 * Channels: the linked platforms (with Unlink), senders waiting for approval and the approved
 * ones, and linking a platform that signs in with a bot token or credentials. A platform linked by
 * scanning a code (WhatsApp) cannot be scanned from the phone's own screen: the page says so and
 * opens the web on another screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelsPage(agent: Agent, profile: String) {
    val context = LocalContext.current
    val ops = rememberOps(agent, profile)
    val apis = agentApis()
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var error by remember { mutableStateOf<HubError?>(null) }
    var linking by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf<Channel?>(null) }
    val channels = rememberLoad(agent.id, profile) { apis().agents.agentsListChannels(profile, agent.id).items }
    val pairing = rememberLoad(agent.id, profile, "pairing") { apis().agents.agentsListPairing(profile, agent.id) }
    fun after(result: Result<*>) {
        result.onFailure { error = it as HubError }.onSuccess { error = null }
        channels.reload(); pairing.reload()
    }
    LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.channels")) {
        item { ErrorNotice(error) }
        item {
            LoadView(channels) { list ->
                val linked = list.filter { it.configured || it.link?.linked == true }
                GroupedList(title = stringResource(R.string.channels_linked)) {
                    if (linked.isEmpty()) Custom { Text(stringResource(R.string.channels_none), fontSize = FontTokens.sizeSm.sp, color = t.textMuted) }
                    linked.forEach { channel ->
                        Item(
                            channel.label, subtitle = listOfNotNull(ChannelLinks.account(channel), channel.error).joinToString(" · ").ifEmpty { null },
                            icon = Lucide.Radio, tag = "channel.${channel.platform}",
                            trailing = {
                                Badge(
                                    stringResource(
                                        when (channel.status) {
                                            Channel.Status.ONLINE -> R.string.channel_online
                                            Channel.Status.OFFLINE -> R.string.channel_offline
                                            Channel.Status.ERROR -> R.string.agent_error
                                            else -> if (channel.enabled) R.string.agent_on else R.string.agent_off
                                        },
                                    ),
                                    tone = when (channel.status) { Channel.Status.ONLINE -> BadgeTone.Success; Channel.Status.ERROR -> BadgeTone.Danger; else -> BadgeTone.Neutral },
                                    dot = true,
                                )
                                HubIconButton(Lucide.X, stringResource(R.string.channels_unlink), { unlinking = channel }, size = 32.dp, iconSize = 16.dp, modifier = Modifier.testTag("channel.${channel.platform}.unlink"))
                            },
                        )
                    }
                }
            }
        }
        item {
            HubButton(stringResource(R.string.channels_link), { linking = true }, kind = ButtonKind.Subtle, size = ControlSize.Md, icon = Lucide.Link, modifier = Modifier.testTag("channels.link"))
        }
        item {
            LoadView(pairing) { p ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupedList(title = stringResource(R.string.channels_waiting)) {
                        if (p.pending.isEmpty()) Custom { Text(stringResource(R.string.channels_nobody_waiting), fontSize = FontTokens.sizeSm.sp, color = t.textMuted) }
                        p.pending.forEach { r ->
                            Custom(Modifier.testTag("pairing.${r.requestId}")) {
                                Text(r.userName ?: r.userId, fontSize = FontTokens.sizeMd.sp)
                                Text("${r.platform} · ${localTime(r.requestedAt)}", fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    HubButton(stringResource(R.string.workflows_approve), { scope.launch { after(ops.approve(r.platform, r.requestId)) } }, size = ControlSize.Sm, icon = Lucide.Check, modifier = Modifier.testTag("pairing.${r.requestId}.approve"))
                                    HubButton(stringResource(R.string.workflows_deny), { scope.launch { after(ops.deny(r.platform, r.requestId)) } }, kind = ButtonKind.Danger, size = ControlSize.Sm, icon = Lucide.X)
                                }
                            }
                        }
                    }
                    if (p.approved.isNotEmpty()) GroupedList(title = stringResource(R.string.channels_approved)) {
                        p.approved.forEach { a ->
                            Item(
                                a.userName ?: a.userId, subtitle = a.platform, tag = "approved.${a.userId}",
                                trailing = { HubButton(stringResource(R.string.channels_revoke), { scope.launch { after(ops.revoke(a.platform, a.userId)) } }, kind = ButtonKind.Ghost, size = ControlSize.Sm) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (linking) LinkSheet(ops, onDone = { linking = false; channels.reload() }, onWeb = {
        context.graph.store.current?.hub?.let { hub -> AppPaths.webUrl(hub, "agent_channels")?.replace(":agentId", agent.id) }
            ?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
    })
    unlinking?.let { channel ->
        ConfirmDialog(
            stringResource(R.string.channels_unlink_confirm, channel.label), null, stringResource(R.string.channels_unlink),
            onConfirm = { scope.launch { after(ops.unlink(channel.platform)) }; unlinking = null }, onDismiss = { unlinking = null }, danger = true,
        )
    }
}

@Composable
private fun LinkSheet(ops: AgentOps, onDone: () -> Unit, onWeb: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val platforms = rememberLoad(ops.agentId, ops.profile, "platforms") {
        context.graph.apis(context.graph.store.current!!).agents.agentsListChannelPlatforms(ops.profile, ops.agentId).items
    }
    var chosen by remember { mutableStateOf<ChannelPlatform?>(null) }
    HubSheet(onDismiss = onDone, title = stringResource(R.string.channels_link)) {
        val p = chosen
        if (p == null) {
            LoadView(platforms) { list ->
                GroupedList {
                    list.forEach { platform ->
                        Item(
                            platform.label, icon = if (ChannelLinks.onPhone(platform)) Lucide.Link else Lucide.QrCode, chevron = true,
                            subtitle = if (ChannelLinks.onPhone(platform)) null else stringResource(R.string.channels_qr_short),
                            tag = "platform.${platform.platform}", onClick = { chosen = platform },
                        )
                    }
                }
            }
        } else if (!ChannelLinks.onPhone(p)) {
            // A code shown on this screen cannot be scanned by this phone's own camera.
            NoticeBox(stringResource(R.string.channels_qr_body, p.label), BadgeTone.Info)
            HubButton(stringResource(R.string.on_the_web_open), onWeb, icon = Lucide.ExternalLink, fill = true, modifier = Modifier.fillMaxWidth().testTag("platform.web"))
            HubButton(stringResource(R.string.back), { chosen = null }, kind = ButtonKind.Ghost, size = ControlSize.Md, icon = Lucide.ArrowLeft)
        } else {
            val typed = remember(p.platform) { mutableStateMapOf<String, String>() }
            var allowed by remember(p.platform) { mutableStateOf("") }
            var busy by remember(p.platform) { mutableStateOf(false) }
            var error by remember(p.platform) { mutableStateOf<HubError?>(null) }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.label, fontSize = FontTokens.sizeLg.sp, fontWeight = FontWeight.SemiBold)
                ErrorNotice(error)
                ChannelLinks.fields(p).forEach { field ->
                    HubTextField(
                        typed[field.key].orEmpty(), { typed[field.key] = it },
                        label = if (field.key == "token") stringResource(R.string.channels_bot_token) else field.key + if (field.required) " *" else "",
                        mono = true, size = ControlSize.Md,
                        visualTransformation = if (field.kind == ChannelCredentialField.Kind.SECRET) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        fieldTag = "link.${field.key}",
                    )
                }
                if (p.allowedUsersKey != null) {
                    HubTextField(allowed, { allowed = it }, label = stringResource(R.string.channels_allowed), placeholder = stringResource(R.string.channels_allowed_hint), size = ControlSize.Md, fieldTag = "link.allowed")
                }
                if (p.pairs) Text(stringResource(R.string.channels_pairs_note), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubButton(stringResource(R.string.channels_link_do), {
                        busy = true
                        scope.launch {
                            ops.link(p, typed.toMap(), allowed).onSuccess { onDone() }.onFailure { error = it as HubError }
                            busy = false
                        }
                    }, size = ControlSize.Md, icon = Lucide.Link, loading = busy, enabled = ChannelLinks.missing(p, typed).isEmpty(), modifier = Modifier.testTag("link.submit"))
                    HubButton(stringResource(R.string.back), { chosen = null }, kind = ButtonKind.Ghost, size = ControlSize.Md)
                }
                p.docsUrl?.let { url ->
                    HubButton(stringResource(R.string.channels_docs), { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.ExternalLink)
                }
            }
        }
    }
}

internal val agentChannelsPage = AgentPageEntry("agent_channels") { agent, profile -> ChannelsPage(agent, profile) }

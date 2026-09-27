package hub.core.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Alignment
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
import hub.core.android.ui.components.ConfirmDelete
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.RowAction
import hub.core.android.ui.components.RowActionsButton
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubSwitch
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.Channel
import hub.core.client.model.ChannelCredentialField
import hub.core.client.model.ChannelGateway
import hub.core.client.model.ChannelLink
import hub.core.client.model.ChannelPlatform
import hub.core.client.model.ChannelTokenLink
import hub.core.client.model.JobStatus
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
    fun account(channel: Channel): String? = ChannelRules.account(channel.link)
}

/**
 * Channels (apps batch 9, the web's page): each linked platform as a card — its switch, state and
 * account, and what it offers (its settings, WhatsApp's mode and reply header, its fields, Unlink or
 * Forget identity, Restart when the gateway does not serve it yet); linking a platform (a bot token or
 * credentials here; a QR platform by the code drawn on this screen, AgentChannelPair.kt);
 * senders waiting for approval and the approved ones; and the agent's incoming webhooks.
 */
@Composable
private fun ChannelsPage(agent: Agent, profile: String) {
    val ops = rememberOps(agent, profile)
    val two = rememberAgentsTwoOps(agent, profile)
    val tools = rememberToolOps(agent, profile)
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var error by remember { mutableStateOf<HubError?>(null) }
    var note by remember { mutableStateOf<ToolNote?>(null) }
    var linking by remember { mutableStateOf<ChannelPlatform?>(null) }
    var picking by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf<Channel?>(null) }
    var mode by remember { mutableStateOf<Channel?>(null) }
    var header by remember { mutableStateOf<Channel?>(null) }
    var fields by remember { mutableStateOf<Channel?>(null) }
    var restarting by remember { mutableStateOf(false) }
    val unlinking = rememberConfirmDelete<Channel>()
    val clearing = rememberConfirmDelete<Channel>()
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val channels = rememberLoad(agent.id, profile) { two.channels().getOrThrow() }
    val platforms = rememberLoad(agent.id, profile, "platforms") { two.platforms().getOrThrow() }
    val pairing = rememberLoad(agent.id, profile, "pairing") { two.pairing().getOrThrow() }
    val specs = (platforms.state as? hub.core.android.ui.components.Load.Ready)?.value.orEmpty()
    val pending = (pairing.state as? hub.core.android.ui.components.Load.Ready)?.value?.pending.orEmpty()
    fun specOf(platform: String) = specs.firstOrNull { it.platform == platform }
    fun after(result: Result<*>) {
        result.onFailure { error = it as HubError }.onSuccess { error = null }
        channels.reload(); pairing.reload()
    }
    val restartedText = stringResource(R.string.agents2_ch_restarted)
    /** Restarts the agent's runtime (Hermes's gateway with it), follows the job, then reads the channels again. */
    fun restart() {
        restarting = true
        note = null
        scope.launch {
            two.restart().onSuccess { id ->
                val job = tools.follow(id) {}
                note = if (job.status == JobStatus.SUCCEEDED) ToolNote(restartedText) else ToolNote(error = HubError(-1, null, job.error?.error))
            }.onFailure { note = ToolNote(error = it as HubError) }
            restarting = false
            channels.reload()
        }
    }
    LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.channels")) {
        item { ErrorNotice(error) }
        item { ToolNoteView(note) }
        item {
            LoadView(channels) { list ->
                val shown = ChannelRules.shown(list.items, pending)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.isNotEmpty()) GatewayNote(list.gateway)
                    if (shown.isEmpty()) EmptyState(stringResource(R.string.channels_none), icon = Lucide.Radio)
                    shown.forEach { channel ->
                        val spec = specOf(channel.platform)
                        ChannelCard(
                            channel, spec, ChannelRules.waitingOn(pending, channel.platform),
                            canRestart = AgentCardRules.canRestart(agent), restarting = restarting, busy = busy[channel.platform] == true,
                            onSwitch = { on ->
                                busy[channel.platform] = true
                                scope.launch { after(two.switchChannel(channel.platform, on)); busy[channel.platform] = false }
                            },
                            onRestart = ::restart,
                            onAction = { action ->
                                when (action) {
                                    ChannelRules.Action.PAIR, ChannelRules.Action.LINK -> linking = spec ?: return@ChannelCard
                                    ChannelRules.Action.SETTINGS -> settings = channel
                                    ChannelRules.Action.MODE -> mode = channel
                                    ChannelRules.Action.REPLY_HEADER -> header = channel
                                    ChannelRules.Action.FIELDS -> fields = channel
                                    ChannelRules.Action.UNLINK -> unlinking.ask(channel)
                                    ChannelRules.Action.CLEAR -> clearing.ask(channel)
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            HubButton(stringResource(R.string.channels_link), { picking = true }, kind = ButtonKind.Subtle, size = ControlSize.Md, icon = Lucide.Link, modifier = Modifier.testTag("channels.link"))
        }
        item {
            LoadView(pairing) { p ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupedList(title = stringResource(R.string.channels_waiting)) {
                        if (p.pending.isEmpty()) Custom { Text(stringResource(R.string.channels_nobody_waiting), fontSize = FontTokens.sizeSm.sp, color = t.textMuted) }
                        p.pending.forEach { r ->
                            Custom(Modifier.testTag("pairing.${r.requestId}")) {
                                Text(r.userName ?: r.userId, fontSize = FontTokens.sizeMd.sp)
                                Text("${r.platform} · ${r.userId} · ${localTime(r.requestedAt)}", fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    HubButton(stringResource(R.string.workflows_approve), { scope.launch { after(ops.approve(r.platform, r.requestId)) } }, size = ControlSize.Sm, icon = Lucide.Check, modifier = Modifier.testTag("pairing.${r.requestId}.approve"))
                                    HubButton(stringResource(R.string.workflows_deny), { scope.launch { after(ops.deny(r.platform, r.requestId)) } }, kind = ButtonKind.Danger, size = ControlSize.Sm, icon = Lucide.X, modifier = Modifier.testTag("pairing.${r.requestId}.deny"))
                                }
                            }
                        }
                    }
                    if (p.approved.isNotEmpty()) GroupedList(title = stringResource(R.string.channels_approved)) {
                        p.approved.forEach { a ->
                            Item(
                                a.userName ?: a.userId, subtitle = "${a.platform} · ${a.userId}", tag = "approved.${a.userId}",
                                trailing = { HubButton(stringResource(R.string.channels_revoke), { scope.launch { after(ops.revoke(a.platform, a.userId)) } }, kind = ButtonKind.Ghost, size = ControlSize.Sm) },
                            )
                        }
                    }
                }
            }
        }
        item {
            val items = (channels.state as? hub.core.android.ui.components.Load.Ready)?.value?.items.orEmpty()
            WebhooksSection(two, items)
        }
    }
    if (picking) LinkSheet(ops, null, onDone = { picking = false; channels.reload() }, onLinked = channels.reload)
    linking?.let { spec -> LinkSheet(ops, spec, onDone = { linking = null; channels.reload() }, onLinked = channels.reload) }
    settings?.let { channel ->
        ChannelSettingsSheet(two, channel.platform, specOf(channel.platform)?.label ?: channel.label, (channels.state as? hub.core.android.ui.components.Load.Ready)?.value?.gateway, onDismiss = { settings = null })
    }
    mode?.let { channel -> ChannelModeDialog(channel, onDismiss = { mode = null }) { chosen -> two.setMode(channel.platform, chosen).onSuccess { channels.reload() } } }
    header?.let { channel ->
        ReplyHeaderDialog(channel, agent.name, onDismiss = { header = null }) { custom, title -> two.setReplyHeader(channel.platform, custom, title).onSuccess { channels.reload() } }
    }
    fields?.let { channel -> ChannelFieldsSheet(channel, onDismiss = { fields = null }) { write -> two.saveChannel(channel.platform, write).onSuccess { channels.reload() } } }
    ConfirmDeleteDialog(
        unlinking, { stringResource(R.string.agents2_ch_unlink_title, it.label) }, { two.unlink(it.platform) }, onDeleted = { channels.reload(); pairing.reload() },
        body = unlinking.pending?.let { unlinkBody(it) }, confirm = stringResource(R.string.channels_unlink),
    )
    ConfirmDeleteDialog(
        clearing, { stringResource(R.string.agents2_ch_clear_title, it.label) }, { two.clearChannel(it.platform) }, onDeleted = { channels.reload() },
        body = stringResource(R.string.agents2_ch_clear_body), confirm = stringResource(R.string.agents2_ch_clear),
    )
}

@Composable
private fun unlinkBody(channel: Channel): String = when (ChannelRules.unlinkWords(channel)) {
    ChannelRules.UnlinkWords.TELEGRAM -> stringResource(R.string.agents2_ch_telegram_unlink_body)
    ChannelRules.UnlinkWords.CREDENTIALS -> stringResource(R.string.agents2_ch_platform_unlink_body, channel.label)
    ChannelRules.UnlinkWords.WHATSAPP -> stringResource(R.string.agents2_ch_unlink_body)
}

/** When a change on this page takes effect, and how the profile's messaging gateway is. */
@Composable
internal fun GatewayNote(gateway: ChannelGateway?) {
    val t = LocalTokens.current
    if (gateway == null || gateway.applies == ChannelGateway.Applies.ON_RESTART) {
        Text(stringResource(R.string.agents2_ch_restart_note), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.testTag("channels.gateway"))
        return
    }
    Column(Modifier.testTag("channels.gateway"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.agents2_ch_applies_now), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.weight(1f))
            Badge(
                stringResource(
                    when (gateway.state) {
                        ChannelGateway.State.RUNNING -> R.string.agents2_ch_gateway_running
                        ChannelGateway.State.STARTING -> R.string.agents2_ch_gateway_starting
                        ChannelGateway.State.STOPPED -> R.string.agents2_ch_gateway_stopped
                        ChannelGateway.State.ERROR -> R.string.agents2_ch_gateway_error
                    },
                ),
                tone = when (gateway.state) { ChannelGateway.State.RUNNING -> BadgeTone.Success; ChannelGateway.State.ERROR -> BadgeTone.Danger; else -> BadgeTone.Neutral },
                dot = true,
            )
        }
        gateway.error?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.danger) }
    }
}

/**
 * One linked platform: the switch, its name and marks (one identity, linked, its state, WhatsApp's
 * mode), whose account it is, what it offers (a button for the first, «⋯» for the rest), and what
 * needs attention: a restart the gateway waits for, Hermes's error, senders waiting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChannelCard(
    channel: Channel,
    spec: ChannelPlatform?,
    waiting: Int,
    canRestart: Boolean,
    restarting: Boolean,
    busy: Boolean,
    onSwitch: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onAction: (ChannelRules.Action) -> Unit,
) {
    val t = LocalTokens.current
    val link = channel.link
    val mode = ChannelRules.mode(channel)
    val actions = ChannelRules.actions(channel, spec)
    HubCard(Modifier.testTag("channel.${channel.platform}"), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(spec?.label ?: channel.label, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            HubSwitch(channel.enabled, onSwitch, Modifier.testTag("channel.${channel.platform}.switch"), enabled = !busy)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (channel.exclusive) Badge(stringResource(R.string.agents2_ch_exclusive), tone = BadgeTone.Warning)
            if (link != null) Badge(stringResource(if (link.linked) R.string.agents2_ch_linked else R.string.agents2_ch_not_linked), tone = if (link.linked) BadgeTone.Success else BadgeTone.Neutral)
            else if (!channel.configured) Badge(stringResource(R.string.agents2_ch_not_configured))
            if (channel.status != Channel.Status.UNKNOWN) Badge(
                stringResource(
                    when (channel.status) {
                        Channel.Status.ONLINE -> R.string.agents2_ch_status_online
                        Channel.Status.ERROR -> R.string.agents2_ch_status_error
                        else -> R.string.agents2_ch_status_offline
                    },
                ),
                tone = when (channel.status) { Channel.Status.ONLINE -> BadgeTone.Success; Channel.Status.ERROR -> BadgeTone.Danger; else -> BadgeTone.Neutral },
                dot = true, modifier = Modifier.testTag("channel.${channel.platform}.status"),
            )
            if (mode != null) Badge(stringResource(if (mode == ChannelLink.Mode.SELF_MINUS_CHAT) R.string.agents2_ch_mode_badge_self else R.string.agents2_ch_mode_badge_bot), tone = BadgeTone.Info)
        }
        val account = ChannelRules.account(link)
        val line = if (link?.linked == true && account != null) stringResource(R.string.agents2_ch_linked_as, account) else stringResource(R.string.agents2_ch_fields_n, channel.fields.size.toString())
        InContentDirection(line) { Text(line, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.testTag("channel.${channel.platform}.account")) }
        if (channel.restartNeeded) {
            NoticeBox(stringResource(R.string.agents2_ch_restart_needed, spec?.label ?: channel.label), BadgeTone.Warning, Modifier.testTag("channel.${channel.platform}.restart_needed"))
            if (canRestart) HubButton(
                stringResource(if (restarting) R.string.agents2_ch_restarting else R.string.agents2_ch_restart_now), onRestart,
                size = ControlSize.Sm, icon = Lucide.RotateCw, loading = restarting, modifier = Modifier.testTag("channel.${channel.platform}.restart"),
            )
        }
        if (channel.status == Channel.Status.ERROR) channel.error?.let { NoticeBox(it, BadgeTone.Danger) }
        if (waiting > 0) Text(stringResource(R.string.agents2_ch_waiting_review, waiting.toString()), fontSize = FontTokens.sizeXs.sp, color = t.warningSoftText, modifier = Modifier.testTag("channel.${channel.platform}.waiting"))
        if (actions.isNotEmpty()) {
            val first = actions.first()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubButton(
                    actionLabel(first), { onAction(first) }, kind = if (first == ChannelRules.Action.UNLINK || first == ChannelRules.Action.CLEAR) ButtonKind.Ghost else ButtonKind.Secondary,
                    size = ControlSize.Sm, icon = actionIcon(first), modifier = Modifier.testTag("channel.${channel.platform}.${first.name.lowercase()}"),
                )
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    val rest = actions.drop(1)
                    if (rest.isNotEmpty()) RowActionsButton(
                        rest.map { a -> RowAction(actionLabel(a), actionIcon(a), danger = a == ChannelRules.Action.UNLINK || a == ChannelRules.Action.CLEAR) { onAction(a) } },
                        Modifier.testTag("channel.${channel.platform}.more"),
                    )
                }
            }
        }
    }
}

@Composable
private fun actionLabel(action: ChannelRules.Action): String = stringResource(
    when (action) {
        ChannelRules.Action.PAIR -> R.string.agents2_ch_pair
        ChannelRules.Action.LINK -> R.string.agents2_ch_link
        ChannelRules.Action.SETTINGS -> R.string.agents2_chs_open
        ChannelRules.Action.MODE -> R.string.agents2_ch_mode_change
        ChannelRules.Action.REPLY_HEADER -> R.string.agents2_ch_reply_open
        ChannelRules.Action.FIELDS -> R.string.agents2_ch_edit
        ChannelRules.Action.UNLINK -> R.string.channels_unlink
        ChannelRules.Action.CLEAR -> R.string.agents2_ch_clear
    },
)

private fun actionIcon(action: ChannelRules.Action): Int = when (action) {
    ChannelRules.Action.PAIR -> Lucide.QrCode
    ChannelRules.Action.LINK -> Lucide.Link
    ChannelRules.Action.SETTINGS -> Lucide.SlidersHorizontal
    ChannelRules.Action.MODE -> Lucide.Smartphone
    ChannelRules.Action.REPLY_HEADER -> Lucide.Type
    ChannelRules.Action.FIELDS -> Lucide.Pencil
    ChannelRules.Action.UNLINK -> Lucide.X
    ChannelRules.Action.CLEAR -> Lucide.Trash
}

/**
 * Link a platform: every platform the hub knows, or straight to [start]'s form. A bot token or
 * credentials link here; a platform linked by scanning a code (WhatsApp) draws its code here, for
 * the phone that has that number to scan (AgentChannelPair.kt).
 */
@Composable
private fun LinkSheet(ops: AgentOps, start: ChannelPlatform?, onDone: () -> Unit, onLinked: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val platforms = rememberLoad(ops.agentId, ops.profile, "platforms") {
        context.graph.apis(context.graph.store.current!!).agents.agentsListChannelPlatforms(ops.profile, ops.agentId).items
    }
    var chosen by remember { mutableStateOf(start) }
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
            // A platform linked by scanning a code (WhatsApp): the code is drawn here (AgentChannelPair.kt).
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelPairPanel(ops.profile, ops.agentId, p.platform, p.label, onLinked = onLinked)
                if (start == null) HubButton(stringResource(R.string.back), { chosen = null }, kind = ButtonKind.Ghost, size = ControlSize.Md, icon = Lucide.ArrowLeft)
            }
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
                    if (start == null) HubButton(stringResource(R.string.back), { chosen = null }, kind = ButtonKind.Ghost, size = ControlSize.Md)
                }
                p.docsUrl?.let { url ->
                    HubButton(stringResource(R.string.channels_docs), { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }, kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.ExternalLink)
                }
            }
        }
    }
}

internal val agentChannelsPage = AgentPageEntry("agent_channels") { agent, profile -> ChannelsPage(agent, profile) }

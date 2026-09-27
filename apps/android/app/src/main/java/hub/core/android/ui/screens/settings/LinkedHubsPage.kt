package hub.core.android.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.data.apiBase
import hub.core.android.data.hubCall
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.FormRules
import hub.core.android.ui.components.Load
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.Notice
import hub.core.android.ui.components.Tone
import hub.core.android.ui.components.errorText
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.Chip
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubSwitch
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.api.DevicesApi
import hub.core.client.model.DevicesRequestPeerRequest
import hub.core.client.model.Peer
import hub.core.client.model.PeerAgent
import hub.core.client.model.PeerAsk
import hub.core.client.model.PeerEvent
import hub.core.client.model.PeerInvite
import hub.core.client.model.PeerPatch
import hub.core.client.model.PeerShare
import hub.core.client.model.PeerShareWrite
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/*
 * Settings → Linked hubs (ADR 0026; the web's LinkedHubsScreen): other Core Hubs linked to this one.
 * One owner makes an invite (single use, 10 minutes), the other pastes it under «Use an invite»,
 * and the first approves, comparing the keys each side shows. Per hub: approve, rename, switch
 * off, limit its questions, its shared agents with one question to one of them, its log, unlink.
 * Below: which of this hub's agents linked hubs may ask. Owners and admins; native on the phone
 * since 2026-09-27 (it was web-only).
 */

/** The calls the page makes (`devices.*Peer*`, `x-scope: global`). */
class LinkedHubsOps(private val api: () -> DevicesApi) {
    suspend fun peers(): Result<List<Peer>> = hubCall { api().devicesListPeers().items }
    suspend fun invite(): Result<PeerInvite> = hubCall { api().devicesCreatePeerInvite() }
    suspend fun request(body: DevicesRequestPeerRequest): Result<Peer> = hubCall { api().devicesRequestPeer(body) }
    suspend fun update(id: String, patch: PeerPatch): Result<Peer> = hubCall { api().devicesUpdatePeer(id, patch) }
    suspend fun delete(id: String): Result<Unit> = hubCall { api().devicesDeletePeer(id) }
    suspend fun shares(): Result<List<PeerShare>> = hubCall { api().devicesListPeerShares().items }
    suspend fun share(share: PeerShare, on: Boolean): Result<PeerShare> =
        hubCall { api().devicesSetPeerShare(PeerShareWrite(profile = share.profile, agentId = share.agentId, shared = on)) }
    suspend fun agents(peer: String): Result<List<PeerAgent>> = hubCall { api().devicesListPeerAgents(peer).items }
    suspend fun ask(peer: String, share: String, prompt: String): Result<String> =
        hubCall { api().devicesAskPeerAgent(peer, share, PeerAsk(prompt.trim())).answer }
    suspend fun events(peer: String): Result<List<PeerEvent>> = hubCall { api().devicesListPeerEvents(peer).items }

    companion object {
        fun of(hub: String, client: OkHttpClient) = LinkedHubsOps { DevicesApi(apiBase(hub), client) }
    }
}

/** The page's plain rules, checked by SelfSufficientTest. */
object LinkedHubsRules {
    const val MIN_ASKS = 1
    const val MAX_ASKS = 1000

    /** The request to link, or null while no link is pasted; an empty name is left out. */
    fun request(url: String, name: String): DevicesRequestPeerRequest? {
        val u = url.trim().takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
        return DevicesRequestPeerRequest(url = uri, name = name.trim().takeIf { it.isNotEmpty() })
    }

    /** The new hourly limit to save, or null when the typed value is not a change the hub takes. */
    fun limit(typed: String, current: Int): Int? {
        val v = typed.trim().toIntOrNull() ?: return null
        return v.takeIf { it in MIN_ASKS..MAX_ASKS && it != current }
    }

    /** A hub can be asked once it is linked and on. */
    fun usable(peer: Peer): Boolean = peer.enabled && peer.status != Peer.Status.PENDING

    /** Only a hub waiting for this owner is approved; the same button refuses it. */
    fun awaitingMe(peer: Peer): Boolean = peer.status == Peer.Status.PENDING

    fun tone(status: Peer.Status): BadgeTone = when (status) {
        Peer.Status.LINKED -> BadgeTone.Success
        Peer.Status.PENDING -> BadgeTone.Warning
        Peer.Status.WAITING -> BadgeTone.Info
    }

    /** The reason to name in the page's words: the peer's own first, then the hub's, then 429. */
    fun reason(error: HubError): String? = listOfNotNull(error.peerCode, error.reason).firstOrNull { it in REASONS }
        ?: if (error.status == 429) "rate_limited" else null

    val REASONS = setOf(
        "own_url_not_https", "https_required", "invite_url_invalid", "invite_refused", "fingerprint_mismatch", "peer_unreachable",
        "peer_exists", "peer_is_self", "peer_limit", "invite_limit", "peer_pending", "peer_not_linked", "peer_disabled",
        "agent_not_shared", "no_answer", "rate_limited", "peer_calls", "peer_asks", "peer_not_pending",
    )
}

/** A refusal in the page's own words when the hub names a reason it knows, the hub's otherwise. */
@Composable
internal fun peerErrorText(error: HubError): String = when (LinkedHubsRules.reason(error)) {
    "own_url_not_https" -> stringResource(R.string.peers_reason_own_url_not_https)
    "https_required" -> stringResource(R.string.peers_reason_https_required)
    "invite_url_invalid" -> stringResource(R.string.peers_reason_invite_url_invalid)
    "invite_refused" -> stringResource(R.string.peers_reason_invite_refused)
    "fingerprint_mismatch" -> stringResource(R.string.peers_reason_fingerprint_mismatch)
    "peer_unreachable" -> stringResource(R.string.peers_reason_peer_unreachable)
    "peer_exists" -> stringResource(R.string.peers_reason_peer_exists)
    "peer_is_self" -> stringResource(R.string.peers_reason_peer_is_self)
    "peer_limit" -> stringResource(R.string.peers_reason_peer_limit)
    "invite_limit" -> stringResource(R.string.peers_reason_invite_limit)
    "peer_pending" -> stringResource(R.string.peers_reason_peer_pending)
    "peer_not_linked" -> stringResource(R.string.peers_reason_peer_not_linked)
    "peer_disabled" -> stringResource(R.string.peers_reason_peer_disabled)
    "agent_not_shared" -> stringResource(R.string.peers_reason_agent_not_shared)
    "no_answer" -> stringResource(R.string.peers_reason_no_answer)
    "rate_limited" -> stringResource(R.string.peers_reason_rate_limited)
    "peer_calls" -> stringResource(R.string.peers_reason_peer_calls)
    "peer_asks" -> stringResource(R.string.peers_reason_peer_asks)
    "peer_not_pending" -> stringResource(R.string.peers_reason_peer_not_pending)
    else -> errorText(error)
}

@Composable
private fun PeerError(error: HubError?) {
    if (error != null) Notice(peerErrorText(error), Tone.DANGER)
}

@Composable
private fun rememberLinkedHubsOps(): LinkedHubsOps {
    val graph = LocalContext.current.graph
    val hub = graph.store.current?.hub.orEmpty()
    return remember(hub) { LinkedHubsOps.of(hub, graph.http.authed) }
}

private val ltr = TextStyle(textDirection = TextDirection.Ltr)

@Composable
private fun LinkedHubsPage() {
    val ops = rememberLinkedHubsOps()
    val peers = rememberLoad("peers") { ops.peers().getOrThrow() }
    val shares = rememberLoad("peer-shares") { ops.shares().getOrThrow() }
    val unlinking = rememberConfirmDelete<Peer>()
    LazyColumn(Modifier.testTag("linked_hubs.page"), contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.peers_intro), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted) }
        item { InviteCard(ops) }
        item { RedeemCard(ops, onSent = peers.reload) }
        item { SectionTitle(stringResource(R.string.peers_heading)) }
        item {
            LoadView(peers) { list ->
                if (list.isEmpty()) EmptyState(stringResource(R.string.peers_none), Modifier.testTag("peers.empty"), icon = Lucide.Network)
            }
        }
        val list = (peers.state as? Load.Ready)?.value.orEmpty()
        items(list, key = { it.id }) { peer -> PeerCard(peer, ops, onChanged = peers.reload, onUnlink = { unlinking.ask(peer) }) }
        item { SharesCard(shares, ops) }
    }
    ConfirmDeleteDialog(
        unlinking,
        title = { stringResource(R.string.peers_unlink_title, it.name) },
        onDelete = { ops.delete(it.id) },
        onDeleted = { peers.reload() },
        body = stringResource(R.string.peers_unlink_body),
        confirm = stringResource(R.string.peers_unlink),
    )
}

/** «Invite another hub»: a single-use link for 10 minutes, with Copy and Share, and this hub's key. */
@Composable
private fun InviteCard(ops: LinkedHubsOps) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val t = LocalTokens.current
    var invite by remember { mutableStateOf<PeerInvite?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    HubCard(Modifier.testTag("peers.invite"), padding = 12.dp) {
        Text(stringResource(R.string.peers_invite_title), fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.peers_invite_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        HubButton(
            stringResource(if (invite == null) R.string.peers_invite_create else R.string.peers_invite_again),
            {
                busy = true
                scope.launch {
                    ops.invite().onSuccess { invite = it; error = null }.onFailure { error = it as HubError }
                    busy = false
                }
            },
            size = ControlSize.Md, loading = busy, icon = Lucide.Link, modifier = Modifier.testTag("peers.invite.create"),
        )
        PeerError(error)
        invite?.let { made ->
            val url = made.url.toString()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    url, fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, style = ltr,
                    modifier = Modifier.weight(1f).testTag("peers.invite.url"),
                )
                HubIconButton(Lucide.Copy, stringResource(R.string.peers_copy), { clipboard.setText(AnnotatedString(url)) }, size = 32.dp, iconSize = 16.dp)
                HubIconButton(
                    Lucide.Share, stringResource(R.string.peers_share), {
                        runCatching {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
                        }
                    }, size = 32.dp, iconSize = 16.dp,
                )
            }
            Text(
                stringResource(R.string.peers_invite_expires, FormRules.dateText(made.expiresAt)),
                fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
            )
            Fingerprint(made.fingerprint, stringResource(R.string.peers_own_fingerprint))
        }
    }
}

/** «Use an invite»: paste the link another hub's owner sent, optionally name it, then ask to link. */
@Composable
private fun RedeemCard(ops: LinkedHubsOps, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    HubCard(Modifier.testTag("peers.redeem"), padding = 12.dp) {
        Text(stringResource(R.string.peers_redeem_title), fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.peers_redeem_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        HubTextField(
            url, { url = it; sent = false }, label = stringResource(R.string.peers_redeem_url), placeholder = "https://…/peer-invite/…", mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), size = ControlSize.Md, fieldTag = "peers.redeem.url",
        )
        HubTextField(name, { name = it }, label = stringResource(R.string.peers_redeem_name), size = ControlSize.Md, fieldTag = "peers.redeem.name")
        HubButton(
            stringResource(R.string.peers_redeem_submit),
            {
                val body = LinkedHubsRules.request(url, name) ?: return@HubButton
                busy = true
                scope.launch {
                    ops.request(body)
                        .onSuccess { url = ""; name = ""; sent = true; error = null; onSent() }
                        .onFailure { error = it as HubError; sent = false }
                    busy = false
                }
            },
            size = ControlSize.Md, loading = busy, enabled = LinkedHubsRules.request(url, name) != null, modifier = Modifier.testTag("peers.redeem.submit"),
        )
        PeerError(error)
        if (sent) Notice(stringResource(R.string.peers_redeem_sent), Tone.SUCCESS)
    }
}

@Composable
private fun Fingerprint(value: String, label: String) {
    Text(
        "$label: $value", fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted,
        fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("peers.fingerprint"),
    )
}

@Composable
private fun statusText(status: Peer.Status): String = stringResource(
    when (status) {
        Peer.Status.PENDING -> R.string.peers_status_pending
        Peer.Status.WAITING -> R.string.peers_status_waiting
        Peer.Status.LINKED -> R.string.peers_status_linked
    },
)

private enum class PeerPanel { AGENTS, LOG }

/** One linked hub, as on the web: its state and key, its switch, limit and actions, and two panels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PeerCard(peer: Peer, ops: LinkedHubsOps?, onChanged: () -> Unit, onUnlink: () -> Unit) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var busy by remember(peer.id) { mutableStateOf(false) }
    var error by remember(peer.id) { mutableStateOf<HubError?>(null) }
    var limit by remember(peer.id, peer.asksPerHour) { mutableStateOf(peer.asksPerHour.toString()) }
    var renaming by remember(peer.id) { mutableStateOf(false) }
    var panel by remember(peer.id) { mutableStateOf<PeerPanel?>(null) }
    fun change(patch: PeerPatch) {
        val o = ops ?: return
        busy = true
        scope.launch {
            o.update(peer.id, patch).onSuccess { error = null; onChanged() }.onFailure { error = it as HubError }
            busy = false
        }
    }
    HubCard(Modifier.testTag("peer.${peer.id}"), padding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    peer.name, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.Medium,
                    style = TextStyle(textDirection = TextDirection.Content),
                )
                Text(peer.url, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, fontFamily = FontFamily.Monospace, style = ltr)
            }
            HubSwitch(peer.enabled, { change(PeerPatch(enabled = it)) }, Modifier.testTag("peer.${peer.id}.enabled"), enabled = !busy)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Badge(statusText(peer.status), tone = LinkedHubsRules.tone(peer.status), dot = true)
            if (!peer.enabled) Badge(stringResource(R.string.peers_off))
        }
        Text(
            stringResource(if (peer.direction == Peer.Direction.INBOUND) R.string.peers_direction_inbound else R.string.peers_direction_outbound) +
                if (LinkedHubsRules.awaitingMe(peer)) " " + stringResource(R.string.peers_pending_hint) else "",
            fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
        )
        Fingerprint(peer.fingerprint, stringResource(R.string.peers_their_fingerprint))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubTextField(
                limit, { limit = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = stringResource(R.string.peers_asks_per_hour),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), size = ControlSize.Sm, fieldTag = "peer.${peer.id}.limit",
            )
            HubButton(
                stringResource(R.string.peers_save), { LinkedHubsRules.limit(limit, peer.asksPerHour)?.let { change(PeerPatch(asksPerHour = it)) } },
                kind = ButtonKind.Secondary, size = ControlSize.Sm, enabled = !busy && LinkedHubsRules.limit(limit, peer.asksPerHour) != null,
                modifier = Modifier.testTag("peer.${peer.id}.limit.save"),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (LinkedHubsRules.awaitingMe(peer)) {
                HubButton(
                    stringResource(R.string.peers_approve), { change(PeerPatch(approve = true)) }, size = ControlSize.Sm, icon = Lucide.Check,
                    loading = busy, modifier = Modifier.testTag("peer.${peer.id}.approve"),
                )
            }
            HubButton(stringResource(R.string.peers_rename), { renaming = true }, kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.Pencil)
            HubButton(
                stringResource(R.string.peers_their_agents), { panel = if (panel == PeerPanel.AGENTS) null else PeerPanel.AGENTS },
                kind = if (panel == PeerPanel.AGENTS) ButtonKind.Primary else ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.Bot,
                enabled = LinkedHubsRules.usable(peer), modifier = Modifier.testTag("peer.${peer.id}.agents"),
            )
            HubButton(
                stringResource(R.string.peers_log), { panel = if (panel == PeerPanel.LOG) null else PeerPanel.LOG },
                kind = if (panel == PeerPanel.LOG) ButtonKind.Primary else ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.ScrollText,
                modifier = Modifier.testTag("peer.${peer.id}.log"),
            )
            HubButton(
                stringResource(if (LinkedHubsRules.awaitingMe(peer)) R.string.peers_refuse else R.string.peers_unlink), onUnlink,
                kind = ButtonKind.Danger, size = ControlSize.Sm, icon = Lucide.Trash, modifier = Modifier.testTag("peer.${peer.id}.unlink"),
            )
        }
        PeerError(error)
        if (ops != null) {
            when (panel) {
                PeerPanel.AGENTS -> PeerAgentsPanel(peer, ops)
                PeerPanel.LOG -> PeerLog(peer, ops)
                null -> Unit
            }
        }
    }
    if (renaming) {
        PeerRenameDialog(peer.name, onDismiss = { renaming = false }) { name -> renaming = false; change(PeerPatch(name = name)) }
    }
}

@Composable
private fun PeerRenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var typed by remember(initial) { mutableStateOf(initial) }
    HubDialog(onDismiss, stringResource(R.string.peers_rename_title)) {
        HubTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = stringResource(R.string.peers_rename_label), size = ControlSize.Md, fieldTag = "peer.rename.field")
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            HubButton(stringResource(R.string.cancel), onDismiss, kind = ButtonKind.Secondary, size = ControlSize.Md)
            HubButton(
                stringResource(R.string.peers_save), { onSave(typed.trim()) }, size = ControlSize.Md,
                enabled = typed.isNotBlank() && typed.trim() != initial, modifier = Modifier.testTag("peer.rename.save"),
            )
        }
    }
}

/** Their shared agents, and one question to one of them, answered without tools, files or memory. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeerAgentsPanel(peer: Peer, ops: LinkedHubsOps) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val agents = rememberLoad("peer-agents", peer.id) { ops.agents(peer.id).getOrThrow() }
    var chosen by remember(peer.id) { mutableStateOf<String?>(null) }
    var question by remember(peer.id) { mutableStateOf("") }
    var busy by remember(peer.id) { mutableStateOf(false) }
    var answer by remember(peer.id) { mutableStateOf<String?>(null) }
    var error by remember(peer.id) { mutableStateOf<HubError?>(null) }
    Column(Modifier.testTag("peer.${peer.id}.agents.panel"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val s = agents.state) {
            Load.Loading -> Spinner(18.dp, t.textMuted)
            is Load.Failed -> PeerError(s.error)
            is Load.Ready -> {
                val list = s.value
                if (list.isEmpty()) {
                    Text(stringResource(R.string.peers_their_agents_none), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
                } else {
                    val share = chosen ?: list.first().id
                    list.forEach { a ->
                        Text(
                            a.name + (a.description?.let { " — $it" } ?: ""), fontSize = FontTokens.sizeSm.sp,
                            style = TextStyle(textDirection = TextDirection.Content),
                        )
                    }
                    Text(stringResource(R.string.peers_ask_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                    Text(stringResource(R.string.peers_ask_agent), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { a -> Chip(a.name, a.id == share, { chosen = a.id }, size = ControlSize.Sm) }
                    }
                    HubTextField(
                        question, { question = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.peers_ask_question),
                        singleLine = false, minLines = 2, size = ControlSize.Md, fieldTag = "peer.${peer.id}.question",
                    )
                    HubButton(
                        stringResource(R.string.peers_ask_send),
                        {
                            busy = true
                            answer = null
                            scope.launch {
                                ops.ask(peer.id, share, question).onSuccess { answer = it; error = null }.onFailure { error = it as HubError }
                                busy = false
                            }
                        },
                        size = ControlSize.Md, loading = busy, enabled = question.isNotBlank(), icon = Lucide.Send,
                        modifier = Modifier.testTag("peer.${peer.id}.ask"),
                    )
                    PeerError(error)
                    answer?.let {
                        HubCard(Modifier.testTag("peer.${peer.id}.answer"), color = t.surface2) {
                            Text(it, fontSize = FontTokens.sizeSm.sp, style = TextStyle(textDirection = TextDirection.Content))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun eventText(kind: PeerEvent.Kind): String = stringResource(
    when (kind) {
        PeerEvent.Kind.JOINED -> R.string.peers_event_joined
        PeerEvent.Kind.REQUESTED -> R.string.peers_event_requested
        PeerEvent.Kind.APPROVED -> R.string.peers_event_approved
        PeerEvent.Kind.APPROVED_BY_PEER -> R.string.peers_event_approved_by_peer
        PeerEvent.Kind.UPDATED -> R.string.peers_event_updated
        PeerEvent.Kind.UNLINKED -> R.string.peers_event_unlinked
        PeerEvent.Kind.UNLINKED_BY_PEER -> R.string.peers_event_unlinked_by_peer
        PeerEvent.Kind.LIST_IN -> R.string.peers_event_list_in
        PeerEvent.Kind.LIST_OUT -> R.string.peers_event_list_out
        PeerEvent.Kind.ASK_IN -> R.string.peers_event_ask_in
        PeerEvent.Kind.ASK_OUT -> R.string.peers_event_ask_out
        PeerEvent.Kind.REFUSED -> R.string.peers_event_refused
    },
)

/** The hub's log with this peer: when, what, and the detail when there is one; failures in red. */
@Composable
private fun PeerLog(peer: Peer, ops: LinkedHubsOps) {
    val t = LocalTokens.current
    val events = rememberLoad("peer-events", peer.id) { ops.events(peer.id).getOrThrow() }
    Column(Modifier.testTag("peer.${peer.id}.log.panel"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val s = events.state) {
            Load.Loading -> Spinner(18.dp, t.textMuted)
            is Load.Failed -> PeerError(s.error)
            is Load.Ready -> s.value.forEach { e ->
                Text(
                    "${FormRules.dateText(e.createdAt)}  ${eventText(e.kind)}${e.detail?.let { " ($it)" } ?: ""}",
                    fontSize = FontTokens.sizeXs.sp, color = if (e.ok) t.text else t.danger,
                )
            }
        }
    }
}

/** Which of this hub's agents linked hubs may ask: none until switched on. */
@Composable
private fun SharesCard(shares: hub.core.android.ui.components.Loader<List<PeerShare>>, ops: LinkedHubsOps) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    Column(Modifier.testTag("peers.shares"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(R.string.peers_shares_title))
        Text(stringResource(R.string.peers_shares_hint), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted)
        LoadView(shares) { list ->
            if (list.isNotEmpty()) {
                GroupedList {
                    list.forEach { share ->
                        Custom {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "${share.name} · ${share.profile}", Modifier.weight(1f), fontSize = FontTokens.sizeSm.sp,
                                    style = TextStyle(textDirection = TextDirection.Content),
                                )
                                HubSwitch(
                                    share.shared,
                                    { on ->
                                        busy = true
                                        scope.launch {
                                            ops.share(share, on).onSuccess { error = null; shares.reload() }.onFailure { error = it as HubError }
                                            busy = false
                                        }
                                    },
                                    Modifier.testTag("peers.share.${share.profile}.${share.agentId}"), enabled = !busy,
                                )
                            }
                        }
                    }
                }
            }
        }
        PeerError(error)
    }
}

internal val linkedHubsPage = SettingsPageEntry("linked_hubs") { LinkedHubsPage() }

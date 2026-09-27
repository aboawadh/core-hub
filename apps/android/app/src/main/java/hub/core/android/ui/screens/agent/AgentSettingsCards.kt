package hub.core.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.PendingWrite
import hub.core.client.model.ProviderSignIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The cards of an agent's Settings page the web shows beside the fields (apps batch 9): signing a
 * coding agent in to its own vendor account, context compression for the profile, and what Hermes
 * wrote and waits for review.
 */

@Composable
private fun CardHead(title: String, subtitle: String?) {
    val t = LocalTokens.current
    Text(title, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold)
    subtitle?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
}

/**
 * Sign in to the agent's own account (Kimi Code, Grok Build) by device code: the code and the link
 * the agent printed, a quiet wait while the hub polls, then the outcome. Nothing here holds a token.
 */
@Composable
internal fun SignInCard(agent: Agent, ops: AgentsTwoOps) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var current by remember { mutableStateOf<ProviderSignIn?>(null) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    var copied by remember { mutableStateOf(false) }
    // While the sign-in waits, the hub is asked again every two seconds (a failed read is tried again).
    LaunchedEffect(current?.id) {
        while (true) {
            val signIn = current ?: break
            if (signIn.status != ProviderSignIn.Status.PENDING) break
            delay(2000)
            ops.signIn(signIn.id).onSuccess { current = it; error = null }.onFailure { error = it as HubError }
        }
    }
    HubCard(Modifier.testTag("agent.signin"), padding = 14.dp) {
        CardHead(stringResource(R.string.agents2_signin_title, agent.name), stringResource(R.string.agents2_signin_hint, agent.name))
        ErrorNotice(error)
        current?.let { signIn ->
            Text(stringResource(R.string.agents2_signin_steps), fontSize = FontTokens.sizeSm.sp)
            signIn.userCode?.let { code ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.agents2_signin_code), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                    Text(
                        code, fontSize = FontTokens.sizeLg.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                        modifier = Modifier.background(t.surface2, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp).testTag("agent.signin.code"),
                    )
                    HubIconButton(
                        if (copied) Lucide.Check else Lucide.Copy, stringResource(if (copied) R.string.agents2_signin_copied else R.string.agents2_signin_copy),
                        { clipboard.setText(AnnotatedString(code)); copied = true }, size = 32.dp, iconSize = 16.dp,
                    )
                }
            }
            HubButton(
                stringResource(R.string.agents2_signin_open), { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(signIn.verificationUrl))) },
                kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.ExternalLink, modifier = Modifier.testTag("agent.signin.link"),
            )
            when (signIn.status) {
                ProviderSignIn.Status.PENDING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(14.dp)
                    Text(stringResource(R.string.agents2_signin_waiting), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                }
                ProviderSignIn.Status.APPROVED -> NoticeBox(stringResource(R.string.agents2_signin_approved, agent.name), BadgeTone.Success, Modifier.testTag("agent.signin.approved"))
                else -> NoticeBox(
                    stringResource(
                        when (signIn.status) {
                            ProviderSignIn.Status.DENIED -> R.string.agents2_signin_denied
                            ProviderSignIn.Status.EXPIRED -> R.string.agents2_signin_expired
                            else -> R.string.agents2_signin_failed
                        },
                    ) + (signIn.error?.let { " $it" } ?: ""),
                    BadgeTone.Danger,
                )
            }
        }
        if (current?.status != ProviderSignIn.Status.PENDING) {
            HubButton(
                stringResource(if (current == null) R.string.agents2_signin_action else R.string.agents2_signin_retry), {
                    starting = true
                    error = null
                    copied = false
                    scope.launch {
                        ops.startSignIn().onSuccess { current = it }.onFailure { error = it as HubError }
                        starting = false
                    }
                },
                kind = if (current == null) ButtonKind.Primary else ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.KeyRound, loading = starting,
                modifier = Modifier.testTag("agent.signin.start"),
            )
        }
    }
}

/** Context compression for the selected profile (decision §57): percentages here, ratios on the wire. */
@Composable
internal fun CompressionCard(ops: AgentsTwoOps) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var profileId by remember { mutableStateOf<String?>(null) }
    val load = rememberLoad(ops.profile, "compression") {
        val id = ops.profileId().getOrThrow() ?: throw HubError(404, "not_found", null)
        profileId = id
        CompressionRules.draftOf(ops.compression(id).getOrThrow())
    }
    var draft by remember { mutableStateOf<CompressionRules.Draft?>(null) }
    var invalid by remember { mutableStateOf<CompressionRules.Field?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    HubCard(Modifier.testTag("agent.compression"), padding = 14.dp) {
        CardHead(stringResource(R.string.agents2_compression_title), stringResource(R.string.agents2_compression_subtitle))
        LoadView(load) { stored ->
            val shown = draft ?: stored
            fun set(next: CompressionRules.Draft) { draft = next; invalid = null; saved = false }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ErrorNotice(error)
                if (saved) NoticeBox(stringResource(R.string.agents2_compression_saved), BadgeTone.Success)
                ToggleRow(
                    stringResource(R.string.agents2_compression_enabled), shown.enabled, { set(shown.copy(enabled = it)) },
                    subtitle = stringResource(R.string.agents2_compression_enabled_hint), modifier = Modifier.testTag("compression.enabled"),
                )
                @Composable
                fun number(field: CompressionRules.Field, label: Int, hint: Int?, value: String, change: (String) -> CompressionRules.Draft) {
                    HubTextField(
                        value, { set(change(it)) }, label = stringResource(label), mono = true, size = ControlSize.Md,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        error = if (invalid == field) stringResource(R.string.agents2_compression_invalid) else null,
                        fieldTag = "compression.${field.name.lowercase()}",
                    )
                    hint?.let { Text(stringResource(it), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
                }
                number(CompressionRules.Field.THRESHOLD, R.string.agents2_compression_threshold, R.string.agents2_compression_threshold_hint, shown.threshold) { shown.copy(threshold = it) }
                number(CompressionRules.Field.TARGET, R.string.agents2_compression_target, R.string.agents2_compression_target_hint, shown.target) { shown.copy(target = it) }
                number(CompressionRules.Field.PROTECT_FIRST, R.string.agents2_compression_protect_first, null, shown.protectFirst) { shown.copy(protectFirst = it) }
                number(CompressionRules.Field.PROTECT_LAST, R.string.agents2_compression_protect_last, null, shown.protectLast) { shown.copy(protectLast = it) }
                number(CompressionRules.Field.CONTEXT_LENGTH, R.string.agents2_compression_context_length, R.string.agents2_compression_context_length_hint, shown.contextLength) { shown.copy(contextLength = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubButton(
                        stringResource(R.string.save), {
                            val d = draft ?: return@HubButton
                            val patch = CompressionRules.patchOf(d)
                            invalid = CompressionRules.invalid(patch)
                            val id = profileId
                            if (invalid == null && id != null) {
                                saving = true
                                scope.launch {
                                    ops.saveCompression(id, patch.getOrThrow())
                                        .onSuccess { draft = CompressionRules.draftOf(it); saved = true; error = null; load.reload(); draft = null }
                                        .onFailure { error = it as HubError }
                                    saving = false
                                }
                            }
                        },
                        size = ControlSize.Sm, icon = Lucide.Check, enabled = draft != null, loading = saving, modifier = Modifier.testTag("compression.save"),
                    )
                    if (draft != null) HubButton(stringResource(R.string.cancel), { draft = null; invalid = null }, kind = ButtonKind.Ghost, size = ControlSize.Sm)
                }
            }
        }
    }
}

/**
 * «Waiting for review» (decision §58): what Hermes's agent wrote to its memory or skills while a
 * write approval is on. Approve applies it with Hermes's own code; Reject drops it. Read again every
 * fifteen seconds, as the web does.
 */
@Composable
internal fun PendingWritesCard(ops: AgentsTwoOps) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val load = rememberLoad(ops.agentId, ops.profile, "pending") { ops.pendingWrites().getOrThrow() }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    LaunchedEffect(ops.agentId, ops.profile) {
        while (true) { delay(15_000); load.reload() }
    }
    HubCard(Modifier.testTag("agent.pending"), padding = 14.dp) {
        CardHead(stringResource(R.string.agents2_pending_title), stringResource(R.string.agents2_pending_subtitle))
        ErrorNotice(error)
        LoadView(load) { writes ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (writes.isEmpty()) Text(stringResource(R.string.agents2_pending_empty), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
                writes.forEach { write ->
                    PendingWriteRow(write, busy == write.id) { approve ->
                        busy = write.id
                        scope.launch {
                            (if (approve) ops.approveWrite(write) else ops.rejectWrite(write)).onFailure { error = it as HubError }.onSuccess { error = null }
                            busy = null
                            load.reload()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PendingWriteRow(write: PendingWrite, busy: Boolean, onAnswer: (approve: Boolean) -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().background(t.surface2, RoundedCornerShape(8.dp)).padding(10.dp).testTag("pending.${write.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge(
                stringResource(
                    when {
                        write.kind == PendingWrite.Kind.SKILLS -> R.string.agents2_pending_kind_skill
                        write.target == "user" -> R.string.agents2_pending_kind_user
                        else -> R.string.agents2_pending_kind_memory
                    },
                ),
                tone = if (write.kind == PendingWrite.Kind.MEMORY) BadgeTone.Info else BadgeTone.Accent,
            )
            Badge(stringResource(if (write.origin == "background_review") R.string.agents2_pending_origin_review else R.string.agents2_pending_origin_chat))
            write.createdAt?.let { Text(localTime(it), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
        }
        val line = (write.name?.let { "$it — " } ?: "") + write.summary.ifEmpty { write.action }
        InContentDirection(line) { Text(line, fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium) }
        write.oldText?.let {
            Text(
                it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, textDecoration = TextDecoration.LineThrough, maxLines = 8,
                modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
            )
        }
        write.content?.let {
            Text(it, fontSize = FontTokens.sizeXs.sp, maxLines = 14, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("pending.${write.id}.content"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubButton(stringResource(R.string.agents2_pending_approve), { onAnswer(true) }, size = ControlSize.Sm, icon = Lucide.Check, enabled = !busy, modifier = Modifier.testTag("pending.${write.id}.approve"))
            HubButton(stringResource(R.string.agents2_pending_reject), { onAnswer(false) }, kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.X, enabled = !busy, modifier = Modifier.testTag("pending.${write.id}.reject"))
        }
    }
}

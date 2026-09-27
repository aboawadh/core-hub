package hub.core.android.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.PushProvider
import hub.core.client.model.PushSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun stateTone(state: PushSender.State): BadgeTone = when (state) {
    PushSender.State.READY -> BadgeTone.Success
    PushSender.State.DISABLED -> BadgeTone.Neutral
    PushSender.State.NOT_CONFIGURED -> BadgeTone.Warning
    PushSender.State.ERROR -> BadgeTone.Danger
}

@Composable
private fun providerName(provider: PushProvider): String = stringResource(
    when (provider) {
        PushProvider.WEBPUSH -> R.string.admin_push_provider_webpush
        PushProvider.FCM -> R.string.admin_push_provider_fcm
        PushProvider.APNS -> R.string.admin_push_provider_apns
    },
)

@Composable
private fun stateName(state: PushSender.State): String = stringResource(
    when (state) {
        PushSender.State.READY -> R.string.admin_push_state_ready
        PushSender.State.DISABLED -> R.string.admin_push_state_disabled
        PushSender.State.NOT_CONFIGURED -> R.string.admin_push_state_not_configured
        PushSender.State.ERROR -> R.string.admin_push_state_error
    },
)

/**
 * The admin's push setup, folded at the bottom of Device connections (the owner, 2026-09-25: the
 * senders under the devices distracted from them). Folded, one line says how each stands; open,
 * one row per sender with its state and Set up / Change. Nothing secret is ever shown.
 */
@Composable
internal fun PushSendersSection(profile: String) {
    val ops = rememberAdminTwoOps(profile)
    val t = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PushSender?>(null) }
    var saved by remember { mutableStateOf<PushSender?>(null) }
    val senders = rememberLoad("push-senders") { ops.senders().getOrThrow() }
    HubCard(Modifier.testTag("push.senders"), padding = 14.dp) {
        LoadView(senders) { list ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { open = !open }.testTag("push.senders.toggle"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LucideIcon(if (open) Lucide.ChevronUp else Lucide.ChevronDown, null, size = 16.dp, tint = t.textMuted)
                    Text(stringResource(R.string.admin_push_title), fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (!open) list.forEach { Badge(providerName(it.provider), tone = stateTone(it.state), dot = true) }
                }
                if (open) {
                    Text(stringResource(R.string.admin_push_intro), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                    list.forEach { sender -> SenderRow(sender, saved?.takeIf { it.provider == sender.provider }) { saved = null; editing = sender } }
                }
            }
        }
    }
    editing?.let { sender ->
        SenderSheet(ops, sender, onDismiss = { editing = null }, onSaved = { result -> editing = null; saved = result; senders.reload() }, onForgotten = { editing = null; saved = null; senders.reload() })
    }
}

@Composable
private fun SenderRow(sender: PushSender, saved: PushSender?, onEdit: () -> Unit) {
    val t = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("push.sender.${sender.provider.value}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(providerName(sender.provider), fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium)
            Badge(stateName(sender.state), tone = stateTone(sender.state))
            when (sender.source) {
                PushSender.Source.ENVIRONMENT -> Badge(stringResource(R.string.admin_push_source_environment))
                PushSender.Source.RELAY -> Badge(stringResource(R.string.admin_push_source_relay))
                else -> {}
            }
            Text(stringResource(R.string.admin_push_devices, sender.devices), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.weight(1f))
            if (PushSenderRules.editable(sender)) HubButton(
                stringResource(if (PushSenderRules.stored(sender)) R.string.admin_push_change else R.string.admin_push_set_up), onEdit,
                kind = if (PushSenderRules.stored(sender)) ButtonKind.Ghost else ButtonKind.Secondary, size = ControlSize.Sm,
                modifier = Modifier.testTag("push.sender.${sender.provider.value}.edit"),
            )
        }
        val parts = PushSenderRules.saved(sender).map { part ->
            when (part) {
                is PushSenderRules.Saved.Project -> stringResource(R.string.admin_push_saved_project, part.id)
                is PushSenderRules.Saved.Key -> stringResource(R.string.admin_push_saved_key, part.ending)
                is PushSenderRules.Saved.Team -> stringResource(R.string.admin_push_saved_team, part.id)
                PushSenderRules.Saved.Sandbox -> stringResource(R.string.admin_push_sandbox)
            }
        }
        if (parts.isNotEmpty()) Text(stringResource(R.string.admin_push_saved, parts.joinToString(" · ")), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        if (saved != null) {
            val outcome = PushSenderRules.outcome(saved)
            NoticeBox(
                when (outcome) {
                    PushSenderRules.Outcome.VALID -> stringResource(if (saved.provider == PushProvider.FCM) R.string.admin_push_valid_fcm else R.string.admin_push_valid_apns)
                    PushSenderRules.Outcome.INCOMPLETE -> stringResource(R.string.admin_push_saved_incomplete)
                    PushSenderRules.Outcome.REFUSED -> stringResource(R.string.admin_push_refused, saved.lastError.orEmpty())
                },
                if (outcome == PushSenderRules.Outcome.VALID) BadgeTone.Success else BadgeTone.Warning,
                Modifier.testTag("push.sender.saved"),
            )
        } else sender.lastError?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.danger) }
    }
}

/** A picked file's text, or null when the phone cannot read it (at most 256 KB: a key is small). */
private fun readText(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytesCompat(256 * 1024)
        String(bytes, Charsets.UTF_8)
    }
}.getOrNull()

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/**
 * One sender's setup, file first (the owner, 2026-09-26: Apple and Firebase hand out files): the
 * service-account JSON or the `.p8` key, read and checked here, with «paste instead» behind it. A
 * stored secret is kept when its field stays empty.
 */
@Composable
private fun SenderSheet(ops: AdminTwoOps, sender: PushSender, onDismiss: () -> Unit, onSaved: (PushSender) -> Unit, onForgotten: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val fcm = sender.provider == PushProvider.FCM
    val stored = PushSenderRules.stored(sender)
    var form by remember(sender.provider) { mutableStateOf(PushSenderRules.form(sender)) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var fileProblem by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var showEnv by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val unreadable = stringResource(R.string.admin_push_file_unreadable)
    val problems = mapOf(
        PushSenderRules.AccountProblem.NOT_JSON to stringResource(R.string.admin_push_fcm_not_json),
        PushSenderRules.AccountProblem.GOOGLE_SERVICES to stringResource(R.string.admin_push_fcm_google_services),
        PushSenderRules.AccountProblem.NOT_SERVICE_ACCOUNT to stringResource(R.string.admin_push_fcm_not_service_account),
        PushSenderRules.AccountProblem.MISSING to stringResource(R.string.admin_push_fcm_missing),
    )
    val keyProblems = mapOf(
        PushSenderRules.KeyProblem.NOT_A_KEY to stringResource(R.string.admin_push_apns_not_a_key),
        PushSenderRules.KeyProblem.NOT_P8 to stringResource(R.string.admin_push_apns_not_p8),
    )
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { hub.core.android.ui.components.PickedFiles.displayName(context, uri) } ?: uri.lastPathSegment
            val text = withContext(Dispatchers.IO) { readText(context, uri) }
            fileName = name
            if (text == null) { fileProblem = unreadable; return@launch }
            if (fcm) {
                val check = PushSenderRules.inspectServiceAccount(text)
                if (check.ok) { form = form.copy(serviceAccount = text); fileProblem = null }
                else { form = form.copy(serviceAccount = ""); fileProblem = problems[check.problem] }
            } else {
                val check = PushSenderRules.inspectP8(name, text)
                if (check.ok) { form = form.copy(privateKey = text, keyId = check.keyId ?: form.keyId); fileProblem = null }
                else { form = form.copy(privateKey = ""); fileProblem = keyProblems[check.problem] }
            }
        }
    }
    val account = form.serviceAccount.takeIf { it.isNotBlank() }?.let { PushSenderRules.inspectServiceAccount(it) }
    val key = form.privateKey.takeIf { it.isNotBlank() }?.let { PushSenderRules.inspectP8(null, it) }
    HubSheet(onDismiss = onDismiss, title = providerName(sender.provider)) {
        Text(stringResource(if (fcm) R.string.admin_push_fcm_intro else R.string.admin_push_apns_intro), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        ToggleRow(stringResource(R.string.admin_push_enabled), form.enabled, { form = form.copy(enabled = it) }, Modifier.testTag("push.form.enabled"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HubButton(
                stringResource(if (fcm) R.string.admin_push_fcm_upload else R.string.admin_push_apns_upload),
                { picker.launch(if (fcm) arrayOf("application/json", "text/plain", "*/*") else arrayOf("application/pkcs8", "application/octet-stream", "text/plain", "*/*")) },
                kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.File, modifier = Modifier.testTag("push.form.file"),
            )
            fileName?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 1, modifier = Modifier.weight(1f)) }
        }
        fileProblem?.let { NoticeBox(it, BadgeTone.Danger, Modifier.testTag("push.form.file_problem")) }
        if (fcm && account?.ok == true) NoticeBox(stringResource(R.string.admin_push_fcm_ok, account.projectId.orEmpty()), BadgeTone.Success, Modifier.testTag("push.form.file_ok"))
        if (!fcm && key?.ok == true && fileName != null) {
            NoticeBox(stringResource(if (PushSenderRules.keyIdFromFileName(fileName!!) != null) R.string.admin_push_apns_ok else R.string.admin_push_apns_ok_no_id), BadgeTone.Success, Modifier.testTag("push.form.file_ok"))
        }
        if (pasting && fcm && account != null && !account.ok) NoticeBox(problems[account.problem].orEmpty(), BadgeTone.Warning)
        if (pasting && !fcm && key != null && !key.ok) NoticeBox(keyProblems[key.problem].orEmpty(), BadgeTone.Warning)
        if (stored && (if (fcm) form.serviceAccount.isBlank() else form.privateKey.isBlank())) {
            Text(stringResource(R.string.admin_push_secret_kept), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        }
        HubButton(stringResource(if (pasting) R.string.admin_push_paste_hide else R.string.admin_push_paste_instead), { pasting = !pasting }, kind = ButtonKind.Ghost, size = ControlSize.Sm, modifier = Modifier.testTag("push.form.paste"))
        if (pasting) {
            HubTextField(
                if (fcm) form.serviceAccount else form.privateKey,
                { fileName = null; fileProblem = null; form = if (fcm) form.copy(serviceAccount = it) else form.copy(privateKey = it) },
                label = stringResource(if (fcm) R.string.admin_push_service_account else R.string.admin_push_private_key),
                placeholder = stringResource(R.string.admin_push_secret_hint), singleLine = false, minLines = 4, maxLines = 8, mono = true, size = ControlSize.Md,
                fieldTag = "push.form.secret",
            )
        }
        if (!fcm) {
            HubTextField(form.keyId, { form = form.copy(keyId = it) }, label = stringResource(R.string.admin_push_key_id), mono = true, size = ControlSize.Md, fieldTag = "push.form.key_id")
            HubTextField(form.teamId, { form = form.copy(teamId = it) }, label = stringResource(R.string.admin_push_team_id), mono = true, size = ControlSize.Md, fieldTag = "push.form.team_id")
            HubTextField(form.bundleId, { form = form.copy(bundleId = it) }, label = stringResource(R.string.admin_push_bundle_id), mono = true, size = ControlSize.Md, fieldTag = "push.form.bundle_id")
            Text(stringResource(R.string.admin_push_environment), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
            Segmented(
                listOf(Segment(false, stringResource(R.string.admin_push_production)), Segment(true, stringResource(R.string.admin_push_sandbox))),
                form.sandbox, { form = form.copy(sandbox = it) }, Modifier.fillMaxWidth(), size = ControlSize.Sm,
            )
        }
        Text(stringResource(R.string.admin_push_env_title), fontSize = FontTokens.sizeXs.sp, color = t.link, modifier = Modifier.clickable { showEnv = !showEnv }.testTag("push.form.env"))
        if (showEnv) {
            Text(stringResource(R.string.admin_push_env_body), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            PushSenderRules.environmentNames(sender.provider).forEach { Text(it, fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = t.textMuted) }
        }
        error?.let { e ->
            e.detailMessage?.takeIf { it.isNotBlank() }?.let { NoticeBox(stringResource(R.string.admin_push_refused, it), BadgeTone.Danger) } ?: ErrorNotice(e)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (stored) HubButton(stringResource(R.string.admin_push_forget), { forgetting = true }, kind = ButtonKind.Ghost, size = ControlSize.Md, modifier = Modifier.testTag("push.form.forget"))
            HubButton(stringResource(R.string.save), {
                busy = true
                scope.launch {
                    ops.saveSender(sender.provider, PushSenderRules.body(sender, form))
                        // Emptied once sent: nothing on the phone keeps the secret after that.
                        .onSuccess { form = form.copy(serviceAccount = "", privateKey = ""); onSaved(it) }
                        .onFailure { error = it as? HubError }
                    busy = false
                }
            }, size = ControlSize.Md, loading = busy, enabled = PushSenderRules.ready(sender, form), modifier = Modifier.testTag("push.form.save"))
        }
    }
    if (forgetting) ConfirmDialog(
        stringResource(R.string.admin_push_forget_title, providerName(sender.provider)), stringResource(R.string.admin_push_forget_body), stringResource(R.string.admin_push_forget),
        onConfirm = {
            forgetting = false
            scope.launch { ops.forgetSender(sender.provider).onSuccess { onForgotten() }.onFailure { error = it as? HubError } }
        },
        onDismiss = { forgetting = false }, danger = true,
    )
}

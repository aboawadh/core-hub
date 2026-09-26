package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.AppToken
import hub.core.client.model.SettingsField
import hub.core.client.model.SettingsSection
import kotlinx.coroutines.launch

/**
 * Settings → Privacy: who, besides you signing in, can act as you on this hub — the app tokens
 * (paired phones among them), each revocable after a confirm — and, for an admin, what Hermes tells
 * the model about people on messaging channels (the web's PrivacyTab). iOS's PrivacyPage.swift is the twin.
 */
@Composable
private fun PrivacyPage(profile: String, admin: Boolean) {
    val context = LocalContext.current
    val graph = context.graph
    val t = LocalTokens.current
    val ops = remember { OwnSettingsOps { graph.apis(graph.store.current!!) } }
    val tokens = rememberLoad { ops.tokens().getOrThrow() }
    val revoking = rememberConfirmDelete<AppToken>()
    LoadView(tokens) { list ->
        LazyColumn(Modifier.testTag("privacy.page"), contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionTitle(stringResource(R.string.own_settings_privacy_access)) }
            item { Text(stringResource(R.string.own_settings_privacy_access_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
            if (list.isEmpty()) {
                item {
                    EmptyState(
                        stringResource(R.string.own_settings_privacy_none), Modifier.testTag("privacy.empty"),
                        body = stringResource(R.string.own_settings_privacy_none_body), icon = Lucide.ShieldCheck,
                    )
                }
            } else item {
                GroupedList {
                    list.forEach { token -> TokenRow(token) { revoking.ask(token) } }
                }
            }
            item { HermesPrivacy(ops, profile, admin) }
            item { Text(stringResource(R.string.own_settings_privacy_browsers_note), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.testTag("privacy.browsers")) }
        }
    }
    val token = revoking.pending
    ConfirmDeleteDialog(
        revoking,
        title = { stringResource(R.string.own_settings_privacy_revoke_title, it.name) },
        onDelete = { ops.revoke(it.id) },
        onDeleted = { tokens.reload() },
        body = stringResource(if (token != null && OwnSettingsRules.isDevice(token)) R.string.own_settings_privacy_revoke_device_body else R.string.own_settings_privacy_revoke_body),
        confirm = stringResource(R.string.own_settings_privacy_revoke),
    )
}

@Composable
private fun hub.core.android.ui.kit.GroupScope.TokenRow(token: AppToken, onRevoke: () -> Unit) {
    val never = stringResource(R.string.own_settings_privacy_never)
    val device = OwnSettingsRules.isDevice(token)
    Item(
        token.name,
        subtitle = listOf(
            stringResource(if (device) R.string.own_settings_privacy_kind_device else R.string.own_settings_privacy_kind_app) +
                " · " + token.scopes.joinToString(", ") { it.value },
            stringResource(R.string.own_settings_privacy_used, token.lastUsedAt?.let(::localTime) ?: never) + " · " +
                stringResource(R.string.own_settings_privacy_expires, token.expiresAt?.let(::localTime) ?: never),
        ).joinToString("\n"),
        icon = if (device) Lucide.Smartphone else Lucide.KeyRound,
        tag = "privacy.token.${token.id}",
        trailing = {
            HubIconButton(Lucide.Trash, stringResource(R.string.own_settings_privacy_revoke), onRevoke, modifier = Modifier.testTag("privacy.revoke.${token.id}"))
        },
    )
}

/**
 * Hermes's `privacy.redact_pii` in the selected profile (contract decision §58). Shown only where the
 * profile has Hermes and the hub reads its settings; an admin changes it.
 */
@Composable
private fun HermesPrivacy(ops: OwnSettingsOps, profile: String, admin: Boolean) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var found by remember(profile) { mutableStateOf<Pair<String, Pair<SettingsSection, SettingsField>>?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(profile) { found = ops.redact(profile).getOrNull() }
    val (agentId, pair) = found ?: return
    val (section, field) = pair
    GroupedList(title = stringResource(R.string.own_settings_privacy_redact_title)) {
        Custom {
            ToggleRow(
                agentText(field.label), SettingValues.on(field), { on ->
                    busy = true
                    scope.launch {
                        ops.setRedact(profile, agentId, on).onSuccess { error = null }.onFailure { error = it as HubError }
                        found = ops.redact(profile).getOrNull() ?: found
                        busy = false
                    }
                },
                Modifier.testTag("privacy.redact"),
                subtitle = section.note?.let { agentText(it) },
                enabled = admin && !busy,
            )
            if (!admin) Text(stringResource(R.string.own_settings_privacy_redact_admin_only), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            ErrorNotice(error)
        }
    }
}

internal val privacyPage = SettingsPageEntry("privacy") { PrivacyPage(it.session.profile, it.session.user.isAdmin) }

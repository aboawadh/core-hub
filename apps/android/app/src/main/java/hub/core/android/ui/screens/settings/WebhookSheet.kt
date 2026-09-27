package hub.core.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.AppLanguage
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.Load
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCheckbox
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.kit.HubRadio
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.NotifyListWebhookEvents200ResponseItemsInner
import hub.core.client.model.Profile
import hub.core.client.model.Webhook
import kotlinx.coroutines.launch

/**
 * Add or edit a webhook (the web's WebhookDialog): name and address (the hub's refusal of an address
 * said beside it), private addresses, on/off, the signing secret (keep, new, stop), the events from
 * the hub's catalogue with a filter, the profiles (every one, or these), message text, retries. A new
 * secret is made here and handed to [onSaved] to be shown once; the hub never shows it again.
 */
@Composable
internal fun WebhookSheet(
    hook: Webhook?,
    profiles: List<Profile>,
    ops: HubDataOps,
    onDismiss: () -> Unit,
    onSaved: (secret: String?) -> Unit,
) {
    HubSheet(onDismiss, title = hook?.let { stringResource(R.string.knowledge_webhooks_edit_title, it.name) } ?: stringResource(R.string.knowledge_webhooks_add)) {
        val arabic = LocalContext.current.graph.prefs.effectiveLanguage == AppLanguage.AR
        val catalogue = rememberLoad("webhook-events") { ops.events().getOrThrow() }
        val events = (catalogue.state as? Load.Ready)?.value
        WebhookForm(hook, profiles, events, (catalogue.state as? Load.Failed)?.error, arabic, onDismiss) { draft ->
            val secret = if (draft.secret == WebhookRules.SecretChoice.NEW) WebhookRules.newSecret() else null
            val body = WebhookRules.write(draft, events?.map { it.name }, secret)
            (if (hook != null) ops.update(hook.id, body) else ops.create(body)).map { secret }.onSuccess(onSaved)
        }
    }
}

/** The form alone (no calls): [onSave] answers the hub's result; a success closes the sheet from [WebhookSheet]. */
@Composable
internal fun WebhookForm(
    hook: Webhook?,
    profiles: List<Profile>,
    events: List<NotifyListWebhookEvents200ResponseItemsInner>?,
    eventsError: HubError?,
    arabic: Boolean,
    onCancel: () -> Unit,
    onSave: suspend (WebhookRules.Draft) -> Result<*>,
) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var draft by remember(hook?.id) { mutableStateOf(WebhookRules.draft(hook)) }
    var filter by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val refusal = WebhookRules.urlRefusal(error?.reason)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HubTextField(draft.name, { draft = draft.copy(name = it.take(80)) }, label = stringResource(R.string.knowledge_webhooks_name), size = ControlSize.Md, fieldTag = "webhook.form.name")
        HubTextField(
            draft.url, { draft = draft.copy(url = it.trim()); if (refusal != null) error = null }, label = stringResource(R.string.knowledge_webhooks_url),
            placeholder = "https://", mono = true, size = ControlSize.Md, fieldTag = "webhook.form.url",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            error = when {
                WebhookRules.badUrl(draft.url) -> stringResource(R.string.knowledge_webhooks_url_scheme)
                refusal == WebhookRules.UrlRefusal.SCHEME -> stringResource(R.string.knowledge_webhooks_url_scheme)
                refusal == WebhookRules.UrlRefusal.UNRESOLVABLE -> stringResource(R.string.knowledge_webhooks_url_unresolvable)
                refusal == WebhookRules.UrlRefusal.PRIVATE -> stringResource(R.string.knowledge_webhooks_url_private)
                else -> null
            },
        )
        Hint(stringResource(R.string.knowledge_webhooks_url_hint))
        ToggleRow(
            stringResource(R.string.knowledge_webhooks_allow_private), draft.allowPrivate, { draft = draft.copy(allowPrivate = it) },
            Modifier.testTag("webhook.form.private"), subtitle = stringResource(R.string.knowledge_webhooks_allow_private_hint),
        )
        ToggleRow(stringResource(R.string.knowledge_webhooks_enabled), draft.enabled, { draft = draft.copy(enabled = it) }, Modifier.testTag("webhook.form.enabled"))

        Heading(stringResource(R.string.knowledge_webhooks_signing))
        Hint(stringResource(R.string.knowledge_webhooks_signing_hint))
        Text("${WebhookRules.SIGNATURE_HEADER}: sha256=<hex>", fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = t.textMuted)
        WebhookRules.secretChoices(hook).forEach { choice ->
            RadioRow(
                stringResource(
                    when (choice) {
                        WebhookRules.SecretChoice.KEEP -> R.string.knowledge_webhooks_secret_keep
                        WebhookRules.SecretChoice.NEW -> if (hook?.secret != null) R.string.knowledge_webhooks_secret_new else R.string.knowledge_webhooks_secret_make
                        WebhookRules.SecretChoice.NONE -> if (hook?.secret != null) R.string.knowledge_webhooks_secret_remove else R.string.knowledge_webhooks_secret_none
                    },
                ),
                draft.secret == choice, { draft = draft.copy(secret = choice) }, "webhook.form.secret.${choice.name.lowercase()}",
                hint = if (choice == WebhookRules.SecretChoice.NEW) stringResource(R.string.knowledge_webhooks_secret_new_hint) else null,
            )
        }

        Heading(stringResource(R.string.knowledge_webhooks_events) + if (draft.events.isNotEmpty()) " (${draft.events.size})" else "")
        Hint(stringResource(R.string.knowledge_webhooks_events_hint))
        ErrorNotice(eventsError)
        when {
            events == null && eventsError == null -> Spinner()
            events != null -> {
                HubTextField(
                    filter, { filter = it }, placeholder = stringResource(R.string.knowledge_webhooks_events_filter), leadingIcon = Lucide.Search,
                    mono = true, size = ControlSize.Sm, fieldTag = "webhook.form.events.filter",
                )
                val shown = WebhookRules.filterEvents(events, filter, arabic)
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    shown.forEach { e ->
                        CheckRow(
                            if (arabic) e.description.ar else e.description.en, e.name in draft.events,
                            { on -> draft = draft.copy(events = if (on) draft.events + e.name else draft.events - e.name) },
                            "webhook.form.event.${e.name}", code = e.name,
                        )
                    }
                    if (shown.isEmpty()) Hint(stringResource(R.string.knowledge_webhooks_events_no_match))
                }
            }
        }

        Heading(stringResource(R.string.knowledge_webhooks_profiles))
        Hint(stringResource(R.string.knowledge_webhooks_profiles_hint))
        RadioRow(stringResource(R.string.knowledge_webhooks_profiles_all), draft.allProfiles, { draft = draft.copy(allProfiles = true) }, "webhook.form.profiles.all")
        RadioRow(stringResource(R.string.knowledge_webhooks_profiles_some), !draft.allProfiles, { draft = draft.copy(allProfiles = false) }, "webhook.form.profiles.some")
        if (!draft.allProfiles) {
            Column(Modifier.padding(start = 28.dp)) {
                profiles.forEach { p ->
                    CheckRow(
                        p.name, p.slug in draft.profiles,
                        { on -> draft = draft.copy(profiles = if (on) draft.profiles + p.slug else draft.profiles - p.slug) }, "webhook.form.profile.${p.slug}",
                    )
                }
                if (WebhookRules.noProfile(draft)) Text(stringResource(R.string.knowledge_webhooks_profiles_pick_one), fontSize = FontTokens.sizeXs.sp, color = t.danger)
            }
        }

        ToggleRow(
            stringResource(R.string.knowledge_webhooks_include_content), draft.includeContent, { draft = draft.copy(includeContent = it) },
            Modifier.testTag("webhook.form.content"), subtitle = stringResource(R.string.knowledge_webhooks_include_content_hint),
        )
        HubTextField(
            draft.retries, { typed -> draft = draft.copy(retries = typed.filter(Char::isDigit).take(2)) },
            label = stringResource(R.string.knowledge_webhooks_max_retries), mono = true, size = ControlSize.Md, fieldTag = "webhook.form.retries",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Hint(stringResource(R.string.knowledge_webhooks_max_retries_hint))
        if (refusal == null) ErrorNotice(error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            HubButton(stringResource(R.string.cancel), onCancel, kind = ButtonKind.Secondary, size = ControlSize.Md)
            HubButton(
                stringResource(R.string.save), {
                    saving = true
                    scope.launch {
                        onSave(draft).onSuccess { error = null }.onFailure { error = it as? HubError ?: HubError(-1, null, it.message) }
                        saving = false
                    }
                },
                size = ControlSize.Md, icon = Lucide.Check, loading = saving, enabled = WebhookRules.ready(draft),
                modifier = Modifier.testTag("webhook.form.save"),
            )
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Hint(text: String) = Text(text, fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted)

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit, tag: String, hint: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        HubRadio(selected)
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = FontTokens.sizeSm.sp)
            hint?.let { Hint(it) }
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String, code: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        HubCheckbox(checked, null)
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = FontTokens.sizeSm.sp)
            code?.let { Text(it, fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, color = LocalTokens.current.textMuted) }
        }
    }
}

/** The new signing secret, once: the hub keeps it but never shows it again. */
@Composable
internal fun SecretOnceDialog(secret: String, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    HubDialog(onDone, stringResource(R.string.knowledge_webhooks_secret_title)) {
        Text(stringResource(R.string.knowledge_webhooks_secret_once), fontSize = FontTokens.sizeSm.sp, color = LocalTokens.current.textMuted)
        SelectionContainer {
            Text(secret, fontSize = FontTokens.sizeSm.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("webhook.secret.value"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            HubButton(
                stringResource(if (copied) R.string.knowledge_webhooks_copied else R.string.knowledge_webhooks_copy),
                { clipboard.setText(AnnotatedString(secret)); copied = true }, kind = ButtonKind.Secondary, size = ControlSize.Md,
                icon = if (copied) Lucide.Check else Lucide.Copy, modifier = Modifier.testTag("webhook.secret.copy"),
            )
            HubButton(stringResource(R.string.knowledge_webhooks_secret_done), onDone, size = ControlSize.Md, modifier = Modifier.testTag("webhook.secret.done"))
        }
    }
}

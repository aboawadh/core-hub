package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.Load
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubSwitch
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Webhook
import hub.core.client.model.WebhookDelivery
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → Webhooks (the web's WebhooksTab, contract decision §59): the addresses this hub calls on
 * its own, in the profile you are in. Each card switches on and off, sends a test and says how it went,
 * shows its recent deliveries (followed while one waits) and sends a failed one again; add and edit are
 * a sheet (WebhookSheet.kt), and a new signing secret is shown once with Copy. Hermes's incoming
 * webhooks are the agent's Channels page, not this one. iOS's WebhooksPage.swift is the twin.
 */
@Composable
private fun WebhooksPage(profile: String, shell: ShellViewModel) {
    val ops = rememberHubDataOps(profile)
    val profiles by shell.profiles.collectAsState()
    val hooks = rememberLoad("webhooks", profile) { ops.webhooks().getOrThrow() }
    var editing by remember { mutableStateOf<Webhook?>(null) }
    var creating by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf<String?>(null) }
    val deleting = rememberConfirmDelete<Webhook>()
    LazyColumn(Modifier.testTag("webhooks.page"), contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.knowledge_webhooks_intro), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted)
        }
        item {
            HubButton(
                stringResource(R.string.knowledge_webhooks_add), { creating = true }, size = ControlSize.Md, icon = Lucide.Plus,
                modifier = Modifier.testTag("webhooks.add"),
            )
        }
        item { NoticeBox(stringResource(R.string.knowledge_webhooks_forwarding_note), BadgeTone.Info) }
        item {
            LoadView(hooks) { list ->
                if (list.isEmpty()) {
                    EmptyState(
                        stringResource(R.string.knowledge_webhooks_none), Modifier.testTag("webhooks.empty"),
                        body = stringResource(R.string.knowledge_webhooks_none_body), icon = Lucide.Webhook,
                    )
                }
            }
        }
        val list = (hooks.state as? Load.Ready)?.value.orEmpty()
        items(list, key = { it.id }) { hook ->
            WebhookCard(hook, ops, onEdit = { editing = hook }, onDelete = { deleting.ask(hook) }, onChanged = hooks.reload)
        }
    }
    if (creating || editing != null) {
        WebhookSheet(
            hook = editing, profiles = profiles, ops = ops,
            onDismiss = { creating = false; editing = null },
            onSaved = { made -> creating = false; editing = null; secret = made; hooks.reload() },
        )
    }
    secret?.let { SecretOnceDialog(it) { secret = null } }
    ConfirmDeleteDialog(
        deleting,
        title = { stringResource(R.string.knowledge_webhooks_delete_title, it.name) },
        onDelete = { ops.delete(it.id) },
        onDeleted = { hooks.reload() },
        body = stringResource(R.string.knowledge_webhooks_delete_body),
    )
}

/** One webhook with its own test, switch and deliveries. */
@Composable
private fun WebhookCard(hook: Webhook, ops: HubDataOps, onEdit: () -> Unit, onDelete: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var switching by remember(hook.id) { mutableStateOf(false) }
    var testing by remember(hook.id) { mutableStateOf(false) }
    var outcome by remember(hook.id) { mutableStateOf<WebhookRules.TestOutcome?>(null) }
    var error by remember(hook.id) { mutableStateOf<HubError?>(null) }
    var open by remember(hook.id) { mutableStateOf(false) }
    var deliveries by remember(hook.id) { mutableStateOf<List<WebhookDelivery>?>(null) }
    var redelivered by remember(hook.id) { mutableStateOf(false) }
    var tick by remember(hook.id) { mutableStateOf(0) }
    // An open table follows the hub: quickly while something waits to be sent, slowly otherwise.
    LaunchedEffect(open, tick) {
        while (open) {
            ops.deliveries(hook.id).onSuccess { deliveries = it }.onFailure { error = it as HubError }
            delay(if (WebhookRules.waiting(deliveries.orEmpty())) 1_500 else 5_000)
        }
    }
    WebhookCardView(
        hook, switching = switching, testing = testing, outcome = outcome, error = error,
        deliveries = if (open) deliveries else null, open = open, redelivered = redelivered,
        onSwitch = { on ->
            switching = true
            scope.launch {
                ops.setEnabled(hook.id, on).onSuccess { error = null; onChanged() }.onFailure { error = it as HubError }
                switching = false
            }
        },
        onTest = {
            testing = true
            outcome = null
            scope.launch {
                ops.test(hook.id).onSuccess { outcome = it; error = null; onChanged() }.onFailure { error = it as HubError }
                testing = false
                tick++
            }
        },
        onToggleDeliveries = { open = !open; redelivered = false },
        onEdit = onEdit, onDelete = onDelete,
        onRedeliver = { delivery ->
            scope.launch {
                ops.redeliver(hook.id, delivery.id).onSuccess { redelivered = true; error = null; tick++ }.onFailure { error = it as HubError }
            }
        },
    )
}

/** The card as drawn (no calls of its own), so the shots can draw each state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WebhookCardView(
    hook: Webhook,
    switching: Boolean = false,
    testing: Boolean = false,
    outcome: WebhookRules.TestOutcome? = null,
    error: HubError? = null,
    deliveries: List<WebhookDelivery>? = null,
    open: Boolean = false,
    redelivered: Boolean = false,
    onSwitch: (Boolean) -> Unit = {},
    onTest: () -> Unit = {},
    onToggleDeliveries: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRedeliver: (WebhookDelivery) -> Unit = {},
) {
    val t = LocalTokens.current
    HubCard(Modifier.testTag("webhook.${hook.id}"), padding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(hook.name, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    hook.url.toString(), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, fontFamily = FontFamily.Monospace,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                )
            }
            HubSwitch(hook.enabled, onSwitch, Modifier.testTag("webhook.${hook.id}.enabled"), enabled = !switching)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Badge(stringResource(if (hook.enabled) R.string.knowledge_webhooks_on else R.string.knowledge_webhooks_off), tone = if (hook.enabled) BadgeTone.Success else BadgeTone.Neutral)
            Badge(
                stringResource(if (hook.secret != null) R.string.knowledge_webhooks_signed else R.string.knowledge_webhooks_unsigned),
                tone = if (hook.secret != null) BadgeTone.Info else BadgeTone.Warning,
            )
            Badge(if (hook.events.isEmpty()) stringResource(R.string.knowledge_webhooks_no_events) else stringResource(R.string.knowledge_webhooks_event_count, hook.events.size))
            Badge(if (hook.profiles.isEmpty()) stringResource(R.string.knowledge_webhooks_all_profiles) else stringResource(R.string.knowledge_webhooks_profile_count, hook.profiles.size))
            if (hook.includeContent) Badge(stringResource(R.string.knowledge_webhooks_with_content), tone = BadgeTone.Warning)
            if (hook.allowPrivateNetwork) Badge(stringResource(R.string.knowledge_webhooks_private_allowed), tone = BadgeTone.Warning)
        }
        Text(
            stringResource(R.string.knowledge_webhooks_stats, hook.stats.delivered, hook.stats.failed) +
                (hook.stats.lastDeliveryAt?.let { " · " + stringResource(R.string.knowledge_webhooks_last_delivery, localTime(it)) } ?: ""),
            fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.testTag("webhook.${hook.id}.stats"),
        )
        hook.stats.lastError?.let { Text(stringResource(R.string.knowledge_webhooks_last_error, it), fontSize = FontTokens.sizeXs.sp, color = t.danger) }
        if (testing) Text(stringResource(R.string.knowledge_webhooks_testing), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        outcome?.let { o ->
            NoticeBox(
                when {
                    o.delivered -> stringResource(R.string.knowledge_webhooks_test_ok, o.status)
                    o.status > 0 -> stringResource(R.string.knowledge_webhooks_test_status, o.status)
                    else -> stringResource(R.string.knowledge_webhooks_test_failed, o.error ?: "—")
                },
                if (o.delivered) BadgeTone.Success else BadgeTone.Danger, Modifier.testTag("webhook.${hook.id}.outcome"),
            )
        }
        ErrorNotice(error)
        if (open) Deliveries(deliveries, redelivered, onRedeliver)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HubButton(
                stringResource(R.string.knowledge_webhooks_test), onTest, kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.Play,
                loading = testing, modifier = Modifier.testTag("webhook.${hook.id}.test"),
            )
            HubButton(
                stringResource(if (open) R.string.knowledge_webhooks_hide_deliveries else R.string.knowledge_webhooks_show_deliveries), onToggleDeliveries,
                kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.Activity, modifier = Modifier.testTag("webhook.${hook.id}.deliveries"),
            )
            HubButton(stringResource(R.string.knowledge_webhooks_edit), onEdit, kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.Pencil, modifier = Modifier.testTag("webhook.${hook.id}.edit"))
            HubButton(stringResource(R.string.kit_delete), onDelete, kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.Trash, modifier = Modifier.testTag("webhook.${hook.id}.delete"))
        }
    }
}

/** The recent deliveries, newest first: event, state, attempts, answer, next try, error, and Redeliver where it may. */
@Composable
private fun Deliveries(deliveries: List<WebhookDelivery>?, redelivered: Boolean, onRedeliver: (WebhookDelivery) -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().testTag("webhook.deliveries"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            deliveries == null -> Spinner()
            deliveries.isEmpty() -> Text(stringResource(R.string.knowledge_webhooks_no_deliveries), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            else -> deliveries.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("delivery.${d.id}")) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            DeliveryBadge(d.status)
                            Text(d.event, fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            localTime(d.createdAt) + " · " +
                                stringResource(R.string.knowledge_webhooks_delivery_facts, d.attempts, d.responseStatus?.toString() ?: "—") +
                                (d.nextAttemptAt?.let { " · " + stringResource(R.string.knowledge_webhooks_delivery_next, localTime(it)) } ?: ""),
                            fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
                        )
                        d.error?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.danger, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    if (WebhookRules.canRedeliver(d)) {
                        HubButton(
                            stringResource(R.string.knowledge_webhooks_redeliver), { onRedeliver(d) }, kind = ButtonKind.Secondary, size = ControlSize.Sm,
                            icon = Lucide.RefreshCw, modifier = Modifier.testTag("delivery.${d.id}.redeliver"),
                        )
                    }
                }
            }
        }
        if (redelivered) Text(stringResource(R.string.knowledge_webhooks_redelivered), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
    }
}

@Composable
private fun DeliveryBadge(status: WebhookDelivery.Status) {
    val (text, tone) = when (status) {
        WebhookDelivery.Status.QUEUED -> R.string.knowledge_webhooks_delivery_queued to BadgeTone.Neutral
        WebhookDelivery.Status.DELIVERED -> R.string.knowledge_webhooks_delivery_delivered to BadgeTone.Success
        WebhookDelivery.Status.FAILED -> R.string.knowledge_webhooks_delivery_failed to BadgeTone.Danger
        WebhookDelivery.Status.DEAD -> R.string.knowledge_webhooks_delivery_dead to BadgeTone.Danger
    }
    Badge(stringResource(text), tone = tone)
}

internal val webhooksPage = SettingsPageEntry("webhooks") { WebhooksPage(it.session.profile, it.shell) }

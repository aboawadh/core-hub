package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
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
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Channel
import hub.core.client.model.HermesWebhook
import hub.core.client.model.HermesWebhookListener
import hub.core.client.model.HermesWebhookTestResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/*
 * «Webhooks» under the channels (decision §97, the web's `WebhooksSection`): Hermes's incoming
 * routes, each with its address on this hub and its secret (copy, show), a local test, and Delete for
 * a route made here; New webhook takes a name, a prompt, a description, events and where the answer
 * goes. An outside service reaches the address only if the hub is open to the internet: said plainly.
 */

@Composable
internal fun WebhooksSection(ops: AgentsTwoOps, channels: List<Channel>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val hub = context.graph.store.current?.hub.orEmpty()
    val load = rememberLoad(ops.agentId, ops.profile, "webhooks") { ops.webhooks().getOrThrow() }
    var creating by remember { mutableStateOf(false) }
    val deleting = rememberConfirmDelete<HermesWebhook>()
    val tests = remember { mutableStateMapOf<String, Result<HermesWebhookTestResult>>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    // A listener switched on a moment ago is starting with its gateway: read again until it says.
    val list = (load.state as? Load.Ready)?.value
    LaunchedEffect(list?.listener?.enabled, list?.listener?.status) {
        if (list?.listener?.enabled == true && list.listener.status == HermesWebhookListener.Status.UNKNOWN) { delay(3000); load.reload() }
    }
    Column(Modifier.testTag("webhooks"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.agents2_wh_title), fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold)
            list?.listener?.let { ListenerBadge(it) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                HubButton(stringResource(R.string.agents2_wh_new), { creating = true }, size = ControlSize.Sm, icon = Lucide.Plus, modifier = Modifier.testTag("webhook.new"))
            }
        }
        Text(stringResource(R.string.agents2_wh_intro), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        NoticeBox(stringResource(R.string.agents2_wh_public_note, hub), BadgeTone.Info)
        LoadView(load) { data ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (data.items.isEmpty()) Text(stringResource(R.string.agents2_wh_none), fontSize = FontTokens.sizeSm.sp, color = t.textMuted, modifier = Modifier.testTag("webhooks.empty"))
                data.items.forEach { route ->
                    WebhookRow(
                        route, WebhookRules.url(hub, route.path), tests[route.name], testing[route.name] == true,
                        onTest = {
                            testing[route.name] = true
                            scope.launch { tests[route.name] = ops.testWebhook(route.name); testing[route.name] = false }
                        },
                        onDelete = { deleting.ask(route) },
                    )
                }
            }
        }
    }
    if (creating) {
        val taken = (load.state as? Load.Ready)?.value?.items.orEmpty().map { it.name }.toSet()
        CreateWebhookSheet(taken, WebhookRules.targets(channels), onDismiss = { creating = false }) { name, prompt, description, events, deliver ->
            ops.createWebhook(name, prompt, description, events, deliver).onSuccess { load.reload() }
        }
    }
    ConfirmDeleteDialog(
        deleting, { stringResource(R.string.agents2_wh_delete_title, it.name) }, { ops.deleteWebhook(it.name) },
        onDeleted = { tests.remove(it.name); load.reload() }, body = stringResource(R.string.agents2_wh_delete_body), confirm = stringResource(R.string.agents2_wh_delete),
    )
}

@Composable
private fun ListenerBadge(listener: HermesWebhookListener) {
    val state = if (!listener.enabled) "off" else listener.status.value
    Badge(
        stringResource(
            when (state) {
                "online" -> R.string.agents2_wh_listener_online
                "offline" -> R.string.agents2_wh_listener_offline
                "error" -> R.string.agents2_wh_listener_error
                "unknown" -> R.string.agents2_wh_listener_unknown
                else -> R.string.agents2_wh_listener_off
            },
        ),
        tone = when (state) { "online" -> BadgeTone.Success; "error" -> BadgeTone.Danger; "off" -> BadgeTone.Neutral; else -> BadgeTone.Warning },
        dot = true, modifier = Modifier.testTag("webhooks.listener"),
    )
}

@Composable
internal fun WebhookRow(
    route: HermesWebhook,
    url: String,
    test: Result<HermesWebhookTestResult>?,
    testing: Boolean,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = LocalTokens.current
    val clipboard = LocalClipboardManager.current
    var showSecret by remember { mutableStateOf(false) }
    HubCard(Modifier.testTag("webhook.${route.name}"), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(route.name, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f, fill = false))
            if (route.static) Badge(stringResource(R.string.agents2_wh_static))
            if (route.events.isNotEmpty()) Badge(route.events.joinToString(", "))
        }
        route.description?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
        Text(
            stringResource(R.string.agents2_wh_prompt_label) + " " + route.prompt.ifEmpty { stringResource(R.string.agents2_wh_prompt_empty) },
            fontSize = FontTokens.sizeXs.sp, maxLines = 3,
        )
        Text(stringResource(R.string.agents2_wh_url), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(url, fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, maxLines = 2, modifier = Modifier.weight(1f).testTag("webhook.${route.name}.url"))
            HubIconButton(Lucide.Copy, stringResource(R.string.agents2_wh_copy_url), { clipboard.setText(AnnotatedString(url)) }, size = 32.dp, iconSize = 16.dp)
        }
        val secret = route.secret
        if (secret != null) {
            Text(stringResource(R.string.agents2_wh_secret), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (showSecret) secret else "••••••••••••", fontSize = FontTokens.sizeXs.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f).testTag("webhook.${route.name}.secret"))
                HubIconButton(
                    if (showSecret) Lucide.EyeOff else Lucide.Eye, stringResource(if (showSecret) R.string.agents2_wh_hide_secret else R.string.agents2_wh_show_secret),
                    { showSecret = !showSecret }, size = 32.dp, iconSize = 16.dp,
                )
                HubIconButton(Lucide.Copy, stringResource(R.string.agents2_wh_copy_secret), { clipboard.setText(AnnotatedString(secret)) }, size = 32.dp, iconSize = 16.dp)
            }
        } else {
            Text(stringResource(R.string.agents2_wh_secret_global), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        }
        test?.let { result ->
            result.onSuccess { r ->
                val said = (r.body?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                NoticeBox(
                    if (WebhookRules.accepted(r.status)) stringResource(R.string.agents2_wh_test_accepted) else stringResource(R.string.agents2_wh_test_refused, r.status.toString(), said),
                    if (WebhookRules.accepted(r.status)) BadgeTone.Success else BadgeTone.Warning, Modifier.testTag("webhook.${route.name}.result"),
                )
            }.onFailure { ToolErrorNotice(it as? HubError) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubButton(
                stringResource(if (testing) R.string.agents2_wh_testing else R.string.agents2_wh_test), onTest, kind = ButtonKind.Secondary, size = ControlSize.Sm,
                icon = Lucide.Activity, loading = testing, modifier = Modifier.testTag("webhook.${route.name}.test"),
            )
            if (!route.static) HubButton(
                stringResource(R.string.agents2_wh_delete), onDelete, kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.Trash,
                modifier = Modifier.testTag("webhook.${route.name}.delete"),
            )
        }
    }
}

@Composable
internal fun CreateWebhookSheet(
    taken: Set<String>,
    targets: List<Channel>,
    onDismiss: () -> Unit,
    onCreate: suspend (name: String, prompt: String, description: String, events: String, deliver: String) -> Result<*>,
) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var events by remember { mutableStateOf("") }
    var deliver by remember { mutableStateOf("log") }
    var choosing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val problem = WebhookRules.nameProblem(name, taken)
    val deliverLabel: @Composable (String) -> String = { value ->
        if (value == "log") stringResource(R.string.agents2_wh_deliver_log)
        else stringResource(R.string.agents2_wh_deliver_to, targets.firstOrNull { it.platform == value }?.label ?: value)
    }
    HubSheet(onDismiss = onDismiss, title = stringResource(R.string.agents2_wh_new_title)) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.agents2_wh_new_intro), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            ToolErrorNotice(error)
            HubTextField(
                name, { name = it }, label = stringResource(R.string.agents2_wh_name), placeholder = "github-issues", mono = true, size = ControlSize.Md, fieldTag = "webhook.create.name",
                error = when (problem) {
                    WebhookRules.NameProblem.INVALID -> stringResource(R.string.agents2_wh_name_invalid)
                    WebhookRules.NameProblem.TAKEN -> stringResource(R.string.agents2_wh_name_taken)
                    null -> null
                },
            )
            Text(stringResource(R.string.agents2_wh_name_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            HubTextField(
                prompt, { prompt = it }, label = stringResource(R.string.agents2_wh_prompt), placeholder = stringResource(R.string.agents2_wh_prompt_placeholder),
                singleLine = false, minLines = 3, maxLines = 8, size = ControlSize.Md, fieldTag = "webhook.create.prompt",
            )
            Text(stringResource(R.string.agents2_wh_prompt_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            HubTextField(description, { description = it }, label = stringResource(R.string.agents2_wh_description), size = ControlSize.Md, fieldTag = "webhook.create.description")
            HubTextField(events, { events = it }, label = stringResource(R.string.agents2_wh_events), placeholder = "issues, push", mono = true, size = ControlSize.Md, fieldTag = "webhook.create.events")
            Text(stringResource(R.string.agents2_wh_events_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            Text(stringResource(R.string.agents2_wh_deliver), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
            Box {
                HubButton(deliverLabel(deliver), { choosing = true }, kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.ChevronsUpDown, modifier = Modifier.testTag("webhook.create.deliver"))
                HubMenu(choosing, { choosing = false }) {
                    (listOf("log") + targets.map { it.platform }).forEach { value ->
                        MenuItem(deliverLabel(value), { choosing = false; deliver = value }, checked = value == deliver)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                HubButton(stringResource(R.string.cancel), onDismiss, kind = ButtonKind.Secondary, size = ControlSize.Md)
                HubButton(
                    stringResource(R.string.agents2_wh_create), {
                        saving = true
                        scope.launch {
                            onCreate(name, prompt, description, events, deliver).onSuccess { onDismiss() }.onFailure { error = it as? HubError ?: HubError(-1, null, it.message) }
                            saving = false
                        }
                    },
                    size = ControlSize.Md, icon = Lucide.Check, loading = saving, enabled = WebhookRules.ready(name, prompt, taken),
                    modifier = Modifier.testTag("webhook.create.save"),
                )
            }
        }
    }
}

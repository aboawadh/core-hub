package hub.core.android.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubRadio
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Channel
import hub.core.client.model.ChannelField
import hub.core.client.model.ChannelGateway
import hub.core.client.model.ChannelLink
import hub.core.client.model.ChannelModeWrite
import hub.core.client.model.ChannelSetting
import hub.core.client.model.ChannelWrite
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * The sheets and dialogs of a channel's card (apps batch 9): its settings (web `ChannelSettingsPanel`),
 * WhatsApp's mode and reply header, and the fields Hermes reads for a platform the hub does not know.
 */

/** The Save / Cancel pair at the end of a sheet or dialog. */
@Composable
private fun SaveRow(onCancel: () -> Unit, onSave: () -> Unit, enabled: Boolean, saving: Boolean, tag: String, saveLabel: String = stringResource(R.string.save)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        HubButton(stringResource(R.string.cancel), onCancel, kind = ButtonKind.Secondary, size = ControlSize.Md)
        HubButton(saveLabel, onSave, size = ControlSize.Md, icon = Lucide.Check, enabled = enabled, loading = saving, modifier = Modifier.testTag(tag))
    }
}

/** One choice with its line under it, as the web's `Radio`. */
@Composable
private fun RadioRow(title: String, hint: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().border(1.dp, if (selected) t.accent else t.border, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(10.dp).testTag(tag),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HubRadio(selected, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.Medium)
            hint?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
        }
    }
}

/** «How WhatsApp is used»: the phone stays linked; the hub rewrites the mode and restarts the gateway. */
@Composable
internal fun ChannelModeDialog(channel: Channel, onDismiss: () -> Unit, onSave: suspend (ChannelModeWrite.Mode) -> Result<*>) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val current = channel.link?.mode
    var chosen by remember { mutableStateOf(current) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    HubDialog(onDismiss, stringResource(R.string.agents2_ch_mode_change_title)) {
        Text(stringResource(R.string.agents2_ch_mode_change_note), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        ToolErrorNotice(error)
        Text(stringResource(R.string.agents2_ch_mode_title), fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium)
        RadioRow(stringResource(R.string.agents2_ch_mode_bot), stringResource(R.string.agents2_ch_mode_bot_hint), chosen == ChannelLink.Mode.BOT, "channel.mode.bot") { chosen = ChannelLink.Mode.BOT }
        RadioRow(stringResource(R.string.agents2_ch_mode_self), stringResource(R.string.agents2_ch_mode_self_hint), chosen == ChannelLink.Mode.SELF_MINUS_CHAT, "channel.mode.self") { chosen = ChannelLink.Mode.SELF_MINUS_CHAT }
        SaveRow(
            onDismiss, {
                val mode = chosen ?: return@SaveRow
                saving = true
                scope.launch {
                    onSave(ChannelModeWrite.Mode.valueOf(mode.name)).onSuccess { onDismiss() }.onFailure { error = it as? HubError ?: HubError(-1, null, it.message) }
                    saving = false
                }
            },
            enabled = chosen != null && chosen != current, saving = saving, tag = "channel.mode.save", saveLabel = stringResource(R.string.agents2_ch_mode_save),
        )
    }
}

/**
 * «Reply header» (WhatsApp in «Message yourself»): the agent's name, or a typed title, shown as a
 * reply will start. There is no "no header": Hermes's bridge puts its own back.
 */
@Composable
internal fun ReplyHeaderDialog(channel: Channel, agentName: String, onDismiss: () -> Unit, onSave: suspend (custom: Boolean, title: String) -> Result<*>) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val current = channel.link?.replyTitle
    var custom by remember { mutableStateOf(ChannelRules.replyCustom(current, agentName)) }
    var typed by remember { mutableStateOf(if (ChannelRules.replyCustom(current, agentName)) current.orEmpty() else "") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val title = ChannelRules.replyTitle(custom, agentName, typed)
    val usable = ChannelRules.replyUsable(title)
    HubDialog(onDismiss, stringResource(R.string.agents2_ch_reply_title)) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.agents2_ch_reply_note), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
            if (current == null) Text(stringResource(R.string.agents2_ch_reply_hermes_now), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            ToolErrorNotice(error)
            RadioRow(
                if (agentName.isNotEmpty()) stringResource(R.string.agents2_ch_reply_agent_name, agentName) else stringResource(R.string.agents2_ch_reply_agent_name_plain),
                null, !custom, "channel.reply.agent",
            ) { custom = false }
            RadioRow(stringResource(R.string.agents2_ch_reply_custom), null, custom, "channel.reply.custom") { custom = true }
            if (custom) {
                HubTextField(
                    typed, { if (it.codePointCount(0, it.length) <= ChannelRules.REPLY_TITLE_MAX) typed = it }, label = stringResource(R.string.agents2_ch_reply_custom_label),
                    size = ControlSize.Md, fieldTag = "channel.reply.text",
                )
                Text(stringResource(R.string.agents2_ch_reply_custom_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            }
            if (usable) {
                Text(stringResource(R.string.agents2_ch_reply_preview), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                Column(Modifier.fillMaxWidth().border(1.dp, t.border, RoundedCornerShape(8.dp)).padding(8.dp).testTag("channel.reply.preview")) {
                    Text(title, fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Bold)
                    Text(ChannelRules.REPLY_RULE, fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
                    Text(stringResource(R.string.agents2_ch_reply_preview_body), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
                }
            }
            Text(stringResource(R.string.agents2_ch_reply_no_none), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            SaveRow(
                onDismiss, {
                    saving = true
                    scope.launch {
                        onSave(custom, title).onSuccess { onDismiss() }.onFailure { error = it as? HubError ?: HubError(-1, null, it.message) }
                        saving = false
                    }
                },
                enabled = usable && !(current != null && title == current), saving = saving, tag = "channel.reply.save", saveLabel = stringResource(R.string.agents2_ch_reply_save),
            )
        }
    }
}

/**
 * A platform's fields as Hermes reads them (the web's channel editor): what each field declared itself
 * to be decides where it goes; a secret reads as [stored] and leaving it keeps the one on file.
 */
@Composable
internal fun ChannelFieldsSheet(channel: Channel, onDismiss: () -> Unit, onSave: suspend (ChannelWrite) -> Result<*>) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val draft = remember { mutableStateMapOf<String, JsonElement?>() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    HubSheet(onDismiss = onDismiss, title = channel.label) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.agents2_ch_editor_note), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            ErrorNotice(error)
            if (channel.fields.isEmpty()) Text(stringResource(R.string.agents2_ch_no_fields), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
            channel.fields.forEach { field ->
                val value = if (draft.containsKey(field.key)) draft[field.key] else field.value
                val label = agentText(field.label).ifEmpty { field.key }
                if (field.kind == ChannelField.Kind.TOGGLE) {
                    ToggleRow(label, ChannelRules.fieldOn(value), { draft[field.key] = JsonPrimitive(it) }, modifier = Modifier.testTag("channel.field.${field.key}"))
                } else {
                    HubTextField(
                        ChannelRules.fieldText(value), { draft[field.key] = JsonPrimitive(it) }, label = label, placeholder = field.hint, mono = true, size = ControlSize.Md,
                        visualTransformation = if (field.kind == ChannelField.Kind.SECRET) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        fieldTag = "channel.field.${field.key}",
                    )
                    if (field.kind == ChannelField.Kind.SECRET) Text(stringResource(R.string.agents2_ch_secret_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                }
            }
            SaveRow(
                onDismiss, {
                    saving = true
                    scope.launch {
                        onSave(ChannelRules.write(channel.fields, draft.toMap())).onSuccess { onDismiss() }.onFailure { error = it as? HubError ?: HubError(-1, null, it.message) }
                        saving = false
                    }
                },
                enabled = draft.isNotEmpty(), saving = saving, tag = "channel.fields.save",
            )
        }
    }
}

// ---------------------------------------------------------------- a channel's settings

@Composable
private fun platformWords(platform: String, key: String, fallback: Map<String, Int>, fallbackKey: String): String? =
    Agents2Words.platform["$platform.$key"]?.let { stringResource(it) } ?: fallback[fallbackKey]?.let { stringResource(it) }

@Composable
private fun optionLabel(platform: String, key: String): String = platformWords(platform, "option.$key.label", Agents2Words.optionLabel, key) ?: key

@Composable
private fun optionHelp(platform: String, key: String): String? = platformWords(platform, "option.$key.help", Agents2Words.optionHelp, key)

@Composable
private fun choiceWords(key: String, value: String): String = Agents2Words.choice["$key.$value"]?.let { stringResource(it) } ?: value

@Composable
private fun defaultText(option: ChannelSetting): String = when (val d = ChannelSettingRules.default(option)) {
    ChannelSettingRules.Default.None -> stringResource(R.string.agents2_chs_default_none)
    is ChannelSettingRules.Default.On -> stringResource(R.string.agents2_chs_default_is, stringResource(if (d.on) R.string.agents2_chs_on else R.string.agents2_chs_off))
    is ChannelSettingRules.Default.Choice -> stringResource(R.string.agents2_chs_default_is, choiceWords(option.key, d.value))
    is ChannelSettingRules.Default.Text -> stringResource(R.string.agents2_chs_default_is, d.text)
}

/**
 * «<Platform> settings»: every option Hermes has for the channel in this profile, in sections, each
 * saying what it does and what applies while it is unset. Changes are gathered and saved together:
 * every save restarts the profile's gateway, so one save is one restart.
 */
@Composable
internal fun ChannelSettingsSheet(ops: AgentsTwoOps, platform: String, name: String, gateway: ChannelGateway?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val load = rememberLoad(ops.agentId, ops.profile, platform) { ops.channelSettings(platform).getOrThrow().options }
    val draft = remember { mutableStateMapOf<String, JsonElement>() }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    HubSheet(onDismiss = onDismiss, title = if (platform == "telegram") stringResource(R.string.agents2_chs_title) else stringResource(R.string.agents2_chs_title_of, name)) {
        LoadView(load) { options ->
            ChannelSettingsBody(
                platform, options, draft, gateway, saved, error, saving,
                onChange = { key, value -> draft[key] = value; saved = false },
                onDiscard = { draft.clear() },
                onSave = {
                    saving = true
                    scope.launch {
                        ops.saveChannelSettings(platform, ChannelSettingRules.values(options, draft.toMap()))
                            .onSuccess { draft.clear(); saved = true; error = null; load.reload() }
                            .onFailure { error = it as HubError }
                        saving = false
                    }
                },
            )
        }
    }
}

@Composable
internal fun ChannelSettingsBody(
    platform: String,
    options: List<ChannelSetting>,
    draft: Map<String, JsonElement>,
    gateway: ChannelGateway?,
    saved: Boolean,
    error: HubError?,
    saving: Boolean,
    onChange: (String, JsonElement) -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val t = LocalTokens.current
    val problems = ChannelSettingRules.problems(options, draft)
    Column(Modifier.verticalScroll(rememberScrollState()).testTag("channel.settings.$platform"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(if (gateway?.applies == ChannelGateway.Applies.NOW) R.string.agents2_chs_applies_now else R.string.agents2_chs_applies_restart),
            fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
        )
        ChannelSettingRules.SECTIONS.forEach { section ->
            val inSection = options.filter { it.section == section }
            if (inSection.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("channel.settings.${section.value}")) {
                    Text(
                        Agents2Words.platform["$platform.section.${section.value}"]?.let { stringResource(it) } ?: wordsOf(Agents2Words.section, section.value),
                        fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.SemiBold,
                    )
                    if (inSection.any { it.shared }) Text(stringResource(R.string.agents2_chs_media_note), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                    inSection.forEach { option -> OptionControl(platform, option, draft, option.key in problems, onChange) }
                }
            }
        }
        ErrorNotice(error)
        if (saved && draft.isEmpty()) NoticeBox(
            stringResource(if (gateway?.applies == ChannelGateway.Applies.NOW) R.string.agents2_chs_saved_now else R.string.agents2_chs_saved_restart),
            BadgeTone.Success, Modifier.testTag("channel.settings.saved"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            HubButton(stringResource(R.string.agents2_chs_discard), onDiscard, kind = ButtonKind.Ghost, size = ControlSize.Md, enabled = draft.isNotEmpty())
            HubButton(
                stringResource(R.string.save), onSave, size = ControlSize.Md, icon = Lucide.Check, loading = saving,
                enabled = draft.isNotEmpty() && problems.isEmpty(), modifier = Modifier.testTag("channel.settings.save"),
            )
        }
    }
}

@Composable
private fun OptionControl(platform: String, option: ChannelSetting, draft: Map<String, JsonElement>, invalid: Boolean, onChange: (String, JsonElement) -> Unit) {
    val t = LocalTokens.current
    val tag = "channel.setting.${option.key}"
    val label = optionLabel(platform, option.key)
    val help = listOfNotNull(optionHelp(platform, option.key), defaultText(option)).joinToString("\n")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (option.kind) {
            ChannelSetting.Kind.TOGGLE -> ToggleRow(
                label, (ChannelSettingRules.effective(option, draft) as? JsonPrimitive)?.booleanOrNull == true, { onChange(option.key, JsonPrimitive(it)) },
                subtitle = help, modifier = Modifier.testTag(tag),
            )
            ChannelSetting.Kind.SELECT -> {
                var open by remember { mutableStateOf(false) }
                val value = (ChannelSettingRules.effective(option, draft) as? JsonPrimitive)?.contentOrNull
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, fontSize = FontTokens.sizeMd.sp, modifier = Modifier.weight(1f))
                    Box {
                        HubButton(value?.let { choiceWords(option.key, it) } ?: "—", { open = true }, kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.ChevronsUpDown, modifier = Modifier.testTag(tag))
                        HubMenu(open, { open = false }) {
                            option.choices.orEmpty().forEach { choice ->
                                MenuItem(choiceWords(option.key, choice), { open = false; onChange(option.key, JsonPrimitive(choice)) }, checked = choice == value)
                            }
                        }
                    }
                }
                Text(help, fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            }
            else -> {
                val placeholder = when {
                    option.kind == ChannelSetting.Kind.LIST -> Agents2Words.platform["$platform.list_placeholder"]?.let { stringResource(it) } ?: stringResource(R.string.agents2_chs_list_placeholder)
                    else -> (option.default as? JsonPrimitive)?.contentOrNull
                }
                HubTextField(
                    ChannelSettingRules.shown(option, draft), { onChange(option.key, ChannelSettingRules.typed(it)) }, label = label, placeholder = placeholder,
                    mono = true, size = ControlSize.Md, fieldTag = tag,
                    keyboardOptions = KeyboardOptions(keyboardType = if (option.kind == ChannelSetting.Kind.NUMBER) KeyboardType.Number else KeyboardType.Text),
                    error = if (invalid) stringResource(R.string.agents2_chs_invalid) else null,
                )
                Text(help, fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (option.shared) Badge(stringResource(R.string.agents2_chs_shared), tone = BadgeTone.Warning)
            val current = ChannelSettingRules.current(option, draft)
            if (current != null && current !is JsonNull) {
                HubButton(stringResource(R.string.agents2_chs_reset), { onChange(option.key, JsonNull) }, kind = ButtonKind.Ghost, size = ControlSize.Sm, modifier = Modifier.testTag("$tag.reset"))
            }
        }
    }
}

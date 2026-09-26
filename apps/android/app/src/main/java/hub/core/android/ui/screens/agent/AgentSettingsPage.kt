package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
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
import hub.core.android.ui.components.InContentDirection
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.SettingsField
import java.math.BigDecimal
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A settings field's value, shown and typed (the kinds the phone edits in place). */
object SettingValues {
    /** The kinds edited on the phone; `list` and `json` are read here and edited on the web. */
    fun editable(field: SettingsField): Boolean = field.kind !in setOf(SettingsField.Kind.LIST, SettingsField.Kind.JSON)

    fun text(value: JsonElement?): String? = when (value) {
        null, is JsonNull -> null
        is JsonPrimitive -> value.contentOrNull
        else -> value.toString()
    }

    /**
     * What a person typed as the field's value, or null when it is not one: a whole number for
     * `integer`, a number for `number` (a comma taken as the decimal point), within `min` and
     * `max`; text as typed. An empty entry puts the field back to its default (`JsonNull`).
     */
    fun parse(field: SettingsField, typed: String): JsonElement? {
        val t = typed.trim()
        if (t.isEmpty()) return JsonNull
        fun inRange(n: BigDecimal) = (field.min == null || n >= field.min) && (field.max == null || n <= field.max)
        return when (field.kind) {
            SettingsField.Kind.INTEGER -> t.toLongOrNull()?.takeIf { inRange(BigDecimal(it)) }?.let(::JsonPrimitive)
            SettingsField.Kind.NUMBER -> t.replace(',', '.').toBigDecimalOrNull()?.takeIf(::inRange)?.let { JsonPrimitive(it.toDouble()) }
            SettingsField.Kind.TOGGLE -> t.toBooleanStrictOrNull()?.let(::JsonPrimitive)
            SettingsField.Kind.CHOICE -> t.takeIf { v -> field.options.any { it.value == v } }?.let(::JsonPrimitive)
            else -> JsonPrimitive(typed)
        }
    }

    fun on(field: SettingsField): Boolean = ((field.value ?: field.default) as? JsonPrimitive)?.booleanOrNull ?: false
}

/**
 * The adapter's settings (ADR 0002), editable in place: switches, a choice from a menu, and text or
 * numbers in a small dialog; lists and JSON are edited on the web. Presets (§100) head the page.
 */
@Composable
private fun SettingsPage(agent: Agent, profile: String) {
    val ops = rememberOps(agent, profile)
    val apis = agentApis()
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var error by remember { mutableStateOf<HubError?>(null) }
    var editing by remember { mutableStateOf<Pair<String, SettingsField>?>(null) }
    var choosing by remember { mutableStateOf<String?>(null) }
    val load = rememberLoad(agent.id, profile) { apis().agents.agentsGetSettings(profile, agent.id).sections }
    fun write(section: String, key: String, value: JsonElement) {
        scope.launch { ops.setSetting(section, key, value).onFailure { error = it as HubError }.onSuccess { error = null }; load.reload() }
    }
    LoadView(load) { sections ->
        LazyColumn(contentPadding = agentPagePad, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("agent.settings")) {
            item { PresetsCard(ops, onApplied = { load.reload() }) }
            item { ErrorNotice(error) }
            sections.forEach { section ->
                item(key = section.key) {
                    GroupedList(title = agentText(section.title)) {
                        section.fields.forEach { field ->
                            val id = section.key + "." + field.key
                            when {
                                field.kind == SettingsField.Kind.TOGGLE -> Custom {
                                    ToggleRow(agentText(field.label), SettingValues.on(field), { on -> write(section.key, field.key, JsonPrimitive(on)) },
                                        subtitle = field.help?.let { agentText(it) }, modifier = Modifier.testTag("setting.$id"))
                                }
                                field.kind == SettingsField.Kind.CHOICE -> Custom {
                                    Box {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            Text(agentText(field.label), fontSize = FontTokens.sizeMd.sp, modifier = Modifier.weight(1f))
                                            val current = SettingValues.text(field.value ?: field.default)
                                            HubButton(
                                                field.options.firstOrNull { it.value == current }?.let { it.labels?.let { l -> agentText(l) } ?: it.label } ?: current ?: "—",
                                                { choosing = id }, kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.ChevronsUpDown,
                                                modifier = Modifier.testTag("setting.$id"),
                                            )
                                        }
                                        HubMenu(choosing == id, { choosing = null }) {
                                            field.options.forEach { option ->
                                                MenuItem(option.labels?.let { agentText(it) } ?: option.label, { choosing = null; write(section.key, field.key, JsonPrimitive(option.value)) }, checked = option.value == SettingValues.text(field.value))
                                            }
                                        }
                                    }
                                }
                                SettingValues.editable(field) -> Item(
                                    agentText(field.label),
                                    value = if (field.kind == SettingsField.Kind.SECRET) (if (field.value != null && field.value !is JsonNull) "••••" else "—") else SettingValues.text(field.value) ?: SettingValues.text(field.default)?.let { "($it)" } ?: "—",
                                    subtitle = field.help?.let { agentText(it) }, chevron = true, tag = "setting.$id",
                                    onClick = { editing = section.key to field },
                                )
                                else -> Item(agentText(field.label), value = SettingValues.text(field.value) ?: "—", subtitle = stringResource(R.string.settings_edit_on_web))
                            }
                        }
                    }
                }
                section.note?.let { note -> item { Text(agentText(note), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) } }
            }
        }
    }
    editing?.let { (section, field) ->
        var typed by remember(field.key) { mutableStateOf(if (field.kind == SettingsField.Kind.SECRET) "" else SettingValues.text(field.value).orEmpty()) }
        val parsed = SettingValues.parse(field, typed)
        HubDialog({ editing = null }, agentText(field.label)) {
            field.help?.let { Text(agentText(it), fontSize = FontTokens.sizeSm.sp, color = t.textMuted) }
            HubTextField(
                typed, { typed = it }, placeholder = field.hint ?: SettingValues.text(field.default),
                keyboardOptions = KeyboardOptions(keyboardType = if (field.kind == SettingsField.Kind.INTEGER) KeyboardType.Number else if (field.kind == SettingsField.Kind.NUMBER) KeyboardType.Decimal else KeyboardType.Text),
                visualTransformation = if (field.kind == SettingsField.Kind.SECRET) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                error = if (parsed == null) stringResource(R.string.settings_value_bad) else null, fieldTag = "setting.editor",
            )
            Text(stringResource(R.string.settings_empty_is_default), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                HubButton(stringResource(R.string.cancel), { editing = null }, kind = ButtonKind.Secondary, size = ControlSize.Md)
                HubButton(stringResource(R.string.save), { parsed?.let { write(section, field.key, it) }; editing = null }, size = ControlSize.Md, enabled = parsed != null, modifier = Modifier.testTag("setting.save"))
            }
        }
    }
}

/** Presets (§100): saved bundles of this agent's settings in the profile; activate, save the current, delete. */
@Composable
private fun PresetsCard(ops: AgentOps, onApplied: () -> Unit) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val load = rememberLoad(ops.agentId, ops.profile) { ops.presets().getOrThrow() }
    var naming by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<AgentNote?>(null) }
    var deleting by remember { mutableStateOf<hub.core.client.model.AgentPreset?>(null) }
    val appliedText = stringResource(R.string.presets_applied)
    val skippedText = stringResource(R.string.presets_skipped)
    GroupedList(title = stringResource(R.string.presets_title)) {
        Custom {
            AgentNoteView(notice)
            LoadView(load) { presets ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (presets.isEmpty()) Text(stringResource(R.string.presets_none), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
                    presets.forEach { preset ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("preset.${preset.id}")) {
                            Column(Modifier.weight(1f)) {
                                InContentDirection(preset.name) { Text(preset.name, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.Medium) }
                                preset.lastActivatedAt?.let { Text(stringResource(R.string.presets_last, localTime(it)), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
                            }
                            HubButton(stringResource(R.string.presets_activate), {
                                scope.launch {
                                    ops.activatePreset(preset.id)
                                        .onSuccess { a -> notice = AgentNote(if (a.skipped.isEmpty()) appliedText else skippedText.format(a.skipped.size), if (a.skipped.isEmpty()) BadgeTone.Success else BadgeTone.Warning); onApplied(); load.reload() }
                                        .onFailure { notice = AgentNote(error = it as HubError) }
                                }
                            }, kind = ButtonKind.Subtle, size = ControlSize.Sm, modifier = Modifier.testTag("preset.${preset.id}.activate"))
                            HubIconButton(Lucide.Trash, stringResource(R.string.presets_delete), { deleting = preset }, size = 32.dp, iconSize = 16.dp)
                        }
                    }
                }
            }
            HubButton(stringResource(R.string.presets_save_current), { naming = true }, kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.Plus, modifier = Modifier.testTag("preset.new"))
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        HubDialog({ naming = false }, stringResource(R.string.presets_save_current)) {
            HubTextField(name, { name = it }, placeholder = stringResource(R.string.presets_name), fieldTag = "preset.name")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                HubButton(stringResource(R.string.cancel), { naming = false }, kind = ButtonKind.Secondary, size = ControlSize.Md)
                HubButton(stringResource(R.string.save), {
                    scope.launch {
                        ops.savePreset(name).onSuccess { load.reload() }.onFailure { notice = AgentNote(error = it as HubError) }
                    }
                    naming = false
                }, size = ControlSize.Md, enabled = name.isNotBlank(), modifier = Modifier.testTag("preset.save"))
            }
        }
    }
    deleting?.let { preset ->
        ConfirmDialog(
            stringResource(R.string.presets_delete_confirm, preset.name), null, stringResource(R.string.presets_delete),
            onConfirm = { scope.launch { ops.deletePreset(preset.id); load.reload() }; deleting = null }, onDismiss = { deleting = null }, danger = true,
        )
    }
}

/** Settings: every installed agent has it, and an unknown page id falls back to it. */
internal val agentSettingsPage = AgentPageEntry("agent_settings") { agent, profile -> SettingsPage(agent, profile) }

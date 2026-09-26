package hub.core.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import java.math.BigDecimal
import kotlinx.coroutines.launch

/*
 * The form sheet every create/edit on the phone uses (docs/clients/phone-pages.md): fields of a
 * few kinds, typed as text, checked before saving, and the hub's own answer when it refuses. The
 * rules are plain functions ([FormRules]) so a page's test can check them without drawing.
 */

/** What a field holds. Values travel as text: a toggle is "true"/"false", a choice its option's value. */
enum class FormKind { Text, Multiline, Number, Toggle, Choice, Secret }

data class FormOption(val value: String, val label: String)

/** One field of a [FormSheet]. [min]/[max] and [integer] apply to [FormKind.Number]; [options] to [FormKind.Choice]. */
data class FormField(
    val key: String,
    val label: String,
    val kind: FormKind = FormKind.Text,
    val required: Boolean = false,
    val help: String? = null,
    val placeholder: String? = null,
    val options: List<FormOption> = emptyList(),
    val min: BigDecimal? = null,
    val max: BigDecimal? = null,
    val integer: Boolean = false,
    /** Left to right in monospace (ids, cron, commands); otherwise the text keeps its own direction. */
    val mono: Boolean = false,
)

/** Why a value is refused; [limit] is the bound for [TooSmall]/[TooLarge]. */
sealed interface FormProblem {
    data object Required : FormProblem
    data object NotANumber : FormProblem
    data object NotWhole : FormProblem
    data class TooSmall(val limit: BigDecimal) : FormProblem
    data class TooLarge(val limit: BigDecimal) : FormProblem
    data object NotAnOption : FormProblem
}

object FormRules {
    /** A number as a person types it: a comma is the decimal point too. */
    fun number(text: String): BigDecimal? = text.trim().replace(',', '.').toBigDecimalOrNull()

    fun problem(field: FormField, value: String): FormProblem? {
        val t = value.trim()
        if (t.isEmpty() || (field.kind == FormKind.Toggle && t != "true" && t != "false")) {
            return if (field.required && field.kind != FormKind.Toggle) FormProblem.Required else null
        }
        return when (field.kind) {
            FormKind.Number -> {
                val n = number(t) ?: return FormProblem.NotANumber
                when {
                    field.integer && n.stripTrailingZeros().scale() > 0 -> FormProblem.NotWhole
                    field.min != null && n < field.min -> FormProblem.TooSmall(field.min)
                    field.max != null && n > field.max -> FormProblem.TooLarge(field.max)
                    else -> null
                }
            }
            FormKind.Choice -> if (field.options.none { it.value == t }) FormProblem.NotAnOption else null
            else -> null
        }
    }

    /** Every refused field, by key; empty when the form can be saved. */
    fun problems(fields: List<FormField>, values: Map<String, String>): Map<String, FormProblem> =
        fields.mapNotNull { f -> problem(f, values[f.key].orEmpty())?.let { f.key to it } }.toMap()
}

@Composable
fun formProblemText(problem: FormProblem): String = when (problem) {
    FormProblem.Required -> stringResource(R.string.kit_required)
    FormProblem.NotANumber -> stringResource(R.string.kit_not_number)
    FormProblem.NotWhole -> stringResource(R.string.kit_not_whole)
    is FormProblem.TooSmall -> stringResource(R.string.kit_too_small, problem.limit.toPlainString())
    is FormProblem.TooLarge -> stringResource(R.string.kit_too_large, problem.limit.toPlainString())
    FormProblem.NotAnOption -> stringResource(R.string.kit_not_option)
}

/**
 * A sheet with [fields] filled from [initial], Save and Cancel. Save checks every field first (a
 * refused one says why under it), then calls [onSave] with every value; a failure stays in the
 * sheet as the hub's words, a success closes it. Tags: `<tag>.<key>` per field, `<tag>.save`.
 */
@Composable
fun FormSheet(
    title: String,
    fields: List<FormField>,
    initial: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: suspend (Map<String, String>) -> Result<*>,
    saveLabel: String = stringResource(R.string.save),
    intro: String? = null,
    tag: String = "form",
) {
    HubSheet(onDismiss = onDismiss, title = title) {
        FormBody(fields, initial, onDismiss, onSave, saveLabel, intro, tag)
    }
}

/** The form itself, for a page or dialog that is not a sheet. */
@Composable
fun FormBody(
    fields: List<FormField>,
    initial: Map<String, String>,
    onDone: () -> Unit,
    onSave: suspend (Map<String, String>) -> Result<*>,
    saveLabel: String = stringResource(R.string.save),
    intro: String? = null,
    tag: String = "form",
) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, initial[it.key].orEmpty()) } } }
    var tried by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    val problems = FormRules.problems(fields, values)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        intro?.let { Text(it, fontSize = FontTokens.sizeSm.sp, color = t.textMuted) }
        failure?.let { e -> if (e is HubError) ErrorNotice(e) else Notice(e.message ?: "—") }
        fields.forEach { field ->
            FormFieldView(field, values[field.key].orEmpty(), { values[field.key] = it }, problems[field.key]?.takeIf { tried }, "$tag.${field.key}")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            HubButton(stringResource(R.string.cancel), onDone, kind = ButtonKind.Secondary, size = ControlSize.Md)
            HubButton(
                saveLabel, {
                    tried = true
                    if (problems.isEmpty()) {
                        saving = true
                        scope.launch {
                            onSave(values.toMap()).onSuccess { failure = null; onDone() }.onFailure { failure = it }
                            saving = false
                        }
                    }
                },
                size = ControlSize.Md, icon = Lucide.Check, loading = saving, modifier = Modifier.testTag("$tag.save"),
            )
        }
    }
}

/** One field drawn by its kind; [problem] is shown under it once the person tried to save. */
@Composable
fun FormFieldView(field: FormField, value: String, onValue: (String) -> Unit, problem: FormProblem?, tag: String) {
    val t = LocalTokens.current
    val error = problem?.let { formProblemText(it) }
    val label = field.label + if (field.required) " *" else ""
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (field.kind) {
            FormKind.Toggle -> ToggleRow(field.label, value == "true", { onValue(it.toString()) }, subtitle = field.help, modifier = Modifier.testTag(tag))
            FormKind.Choice -> ChoiceField(field, label, value, onValue, error, tag)
            FormKind.Secret -> {
                var shown by remember { mutableStateOf(false) }
                HubTextField(
                    value, onValue, label = label, placeholder = field.placeholder, mono = true, size = ControlSize.Md, error = error, fieldTag = tag,
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailing = {
                        HubIconButton(
                            if (shown) Lucide.EyeOff else Lucide.Eye, stringResource(if (shown) R.string.kit_hide_secret else R.string.kit_show_secret),
                            { shown = !shown }, size = 32.dp, iconSize = 16.dp, modifier = Modifier.testTag("$tag.reveal"),
                        )
                    },
                )
            }
            else -> HubTextField(
                value, onValue, label = label, placeholder = field.placeholder, error = error, fieldTag = tag, size = ControlSize.Md,
                singleLine = field.kind != FormKind.Multiline, minLines = if (field.kind == FormKind.Multiline) 4 else 1,
                maxLines = if (field.kind == FormKind.Multiline) 12 else 1, mono = field.mono,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        field.kind != FormKind.Number -> KeyboardType.Text
                        field.integer -> KeyboardType.Number
                        else -> KeyboardType.Decimal
                    },
                ),
            )
        }
        if (field.help != null && field.kind != FormKind.Toggle) Text(field.help, fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
    }
}

/** A choice: a segmented row for up to three options, else a button that opens the menu of them. */
@Composable
private fun ChoiceField(field: FormField, label: String, value: String, onValue: (String) -> Unit, error: String?, tag: String) {
    val t = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        if (field.options.size in 1..3) {
            Segmented(field.options.map { Segment(it.value, it.label, tag = "$tag.${it.value}") }, value, onValue, Modifier.fillMaxWidth(), size = ControlSize.Md)
        } else {
            var open by remember { mutableStateOf(false) }
            Box {
                HubButton(
                    field.options.firstOrNull { it.value == value }?.label ?: stringResource(R.string.kit_choose), { open = true },
                    kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.ChevronsUpDown, modifier = Modifier.testTag(tag),
                )
                HubMenu(open, { open = false }) {
                    field.options.forEach { option ->
                        MenuItem(option.label, { open = false; onValue(option.value) }, checked = option.value == value, modifier = Modifier.testTag("$tag.${option.value}"))
                    }
                }
            }
        }
        if (error != null) Text(error, fontSize = FontTokens.sizeXs.sp, color = t.danger)
    }
}

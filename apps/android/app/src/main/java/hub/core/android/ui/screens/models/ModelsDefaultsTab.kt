package hub.core.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.AppLanguage
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.generated.RadiusTokens
import hub.core.android.graph
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.ModelRef
import hub.core.client.model.Provider
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Defaults: the chat model, the fallbacks in the order they are tried (dragged by their handle), and
 * the auxiliary roles the hub names. A role this profile left alone shows the default profile's
 * choice and says so (§37).
 */
@Composable
internal fun DefaultsTab(ops: ModelOps, providers: List<Provider>, isAdmin: Boolean) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val density = LocalDensity.current
    val arabic = LocalContext.current.graph.prefs.effectiveLanguage == AppLanguage.AR
    val load = rememberLoad(ops.profile, "defaults") { ops.defaults().getOrThrow() }
    var picking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val chat = remember(providers) { ModelRules.chatModels(providers) }
    val inheritedText = stringResource(R.string.models_inherited_short)
    LoadView(load) { d ->
        val inherited = d.inherited.orEmpty().toSet()
        var order by remember(d) { mutableStateOf(d.fallbacks) }
        var dragging by remember { mutableIntStateOf(-1) }
        var dy by remember { mutableFloatStateOf(0f) }
        var rowHeight by remember { mutableFloatStateOf(0f) }
        fun save(list: List<ModelRef>) {
            order = list
            scope.launch { ops.setFallbacks(list, d).onFailure { error = it as HubError }; load.reload() }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag("models.defaults"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorNotice(error)
            GroupedList(title = stringResource(R.string.models_default)) {
                Item(
                    modelLabel(d.default, providers) ?: stringResource(R.string.models_none_chosen), icon = Lucide.Sparkles,
                    subtitle = inheritedText.takeIf { "default" in inherited }, chevron = isAdmin, tag = "defaults.default",
                    onClick = if (isAdmin) ({ picking = "default" }) else null,
                )
            }
            Text(stringResource(R.string.models_defaults_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            SectionTitle(stringResource(R.string.models_fallbacks))
            Text(stringResource(R.string.models_fallbacks_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                order.forEachIndexed { i, ref ->
                    val lifted = dragging == i
                    Row(
                        Modifier.fillMaxWidth()
                            .onGloballyPositioned { if (rowHeight == 0f) rowHeight = it.size.height + with(density) { 6.dp.toPx() } }
                            .then(if (lifted) Modifier.offset { IntOffset(0, dy.roundToInt()) }.shadow(8.dp, RoundedCornerShape(RadiusTokens.md.dp)) else Modifier)
                            .background(t.surface, RoundedCornerShape(RadiusTokens.md.dp))
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .testTag("fallback.$i"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (isAdmin) {
                            LucideIcon(
                                Lucide.GripVertical, stringResource(R.string.models_drag), size = 18.dp, tint = t.textFaint,
                                modifier = Modifier.pointerInput(order) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragging = i; dy = 0f },
                                        onDrag = { change, amount -> change.consume(); dy += amount.y },
                                        onDragEnd = {
                                            val to = ModelRules.dropIndex(i, dy, rowHeight, order.size)
                                            dragging = -1; dy = 0f
                                            if (to != i) save(ModelRules.move(order, i, to))
                                        },
                                        onDragCancel = { dragging = -1; dy = 0f },
                                    )
                                }.testTag("fallback.$i.handle"),
                            )
                        }
                        Text("${i + 1}", fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
                        Text(modelLabel(ref, providers) ?: ref.model, fontSize = FontTokens.sizeSm.sp, modifier = Modifier.weight(1f))
                        if (isAdmin) HubIconButton(Lucide.X, stringResource(R.string.models_remove), { save(order.filterIndexed { j, _ -> j != i }) }, size = 28.dp, iconSize = 14.dp)
                    }
                }
                if (order.isEmpty()) {
                    Text(stringResource(if (d.default == null) R.string.models_fallbacks_need_default else R.string.models_no_fallbacks), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
                }
            }
            if (isAdmin && d.default != null) {
                HubButton(stringResource(R.string.models_add_fallback), { picking = "fallback" }, kind = ButtonKind.Subtle, size = ControlSize.Sm, icon = Lucide.Plus, modifier = Modifier.testTag("defaults.add_fallback"))
            }
            if (d.auxiliary.tasks.isNotEmpty()) {
                GroupedList(title = stringResource(R.string.models_auxiliary)) {
                    d.auxiliary.tasks.forEach { task ->
                        val name = if (arabic) task.label.ar else task.label.en
                        Item(
                            name, icon = Lucide.Wrench,
                            subtitle = listOfNotNull(
                                modelLabel(d.auxiliary.assignments[task.key], providers) ?: stringResource(R.string.models_none_chosen),
                                inheritedText.takeIf { task.key in inherited },
                            ).joinToString(" · "),
                            chevron = isAdmin, tag = "defaults.aux.${task.key}", onClick = if (isAdmin) ({ picking = "aux:${task.key}" }) else null,
                        )
                    }
                }
                Text(stringResource(R.string.models_auxiliary_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            }
        }
        picking?.let { what ->
            val auxKey = what.removePrefix("aux:").takeIf { what.startsWith("aux:") }
            val auxTask = auxKey?.let { k -> d.auxiliary.tasks.firstOrNull { it.key == k } }
            val clearable = what == "default" && d.default != null && "default" !in inherited
            ModelPickerSheet(
                when {
                    what == "default" -> stringResource(R.string.models_default)
                    auxTask != null -> if (arabic) auxTask.label.ar else auxTask.label.en
                    else -> stringResource(R.string.models_add_fallback)
                },
                chat, providers,
                onPick = { ref ->
                    picking = null
                    when {
                        what == "default" -> scope.launch { ops.setDefault(ref).onFailure { error = it as HubError }; load.reload() }
                        auxKey != null && ref != null -> scope.launch { ops.setAssignment(auxKey, ref).onFailure { error = it as HubError }; load.reload() }
                        ref != null -> save(ModelRules.add(order, ref, d.default))
                    }
                },
                onDismiss = { picking = null },
                clear = if (clearable) stringResource(if (ops.profile == "default") R.string.models_clear_choice else R.string.models_use_inherited) else null,
            )
        }
    }
}

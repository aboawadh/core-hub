package hub.core.android.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.AppLanguage
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import hub.core.android.ui.theme.ThemeChoice
import hub.core.client.model.Preferences
import kotlinx.coroutines.launch

@Composable
private fun DisplayPage(showLanguage: Boolean) {
    val context = LocalContext.current
    val prefs = context.graph.prefs
    val theme by prefs.theme.collectAsState()
    LazyColumn(Modifier.testTag("display.page"), contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLanguage) {
            item { SectionTitle(stringResource(R.string.display_language)) }
            item {
                Segmented(
                    listOf(
                        Segment<AppLanguage?>(null, stringResource(R.string.display_language_system)),
                        Segment<AppLanguage?>(AppLanguage.AR, stringResource(R.string.display_language_ar)),
                        Segment<AppLanguage?>(AppLanguage.EN, stringResource(R.string.display_language_en)),
                    ),
                    prefs.language,
                    { lang -> prefs.language = lang; (context as? Activity)?.recreate() },
                    Modifier.fillMaxWidth(), size = ControlSize.Lg,
                )
            }
        }
        item { SectionTitle(term("theme")) }
        item {
            Segmented(
                listOf(
                    Segment(ThemeChoice.SYSTEM, stringResource(R.string.theme_system), Lucide.Contrast),
                    Segment(ThemeChoice.LIGHT, stringResource(R.string.theme_light), Lucide.Sun),
                    Segment(ThemeChoice.DARK, stringResource(R.string.theme_dark), Lucide.Moon),
                ),
                theme, { prefs.setTheme(it) }, Modifier.fillMaxWidth(), size = ControlSize.Lg,
            )
        }
        if (showLanguage) hubPreferences()
    }
}

/**
 * The display preferences the hub keeps for the person (`auth.getPreferences` / `auth.setPreferences`),
 * as iOS's Display page shows them: where links open, what Send does while the agent works, reasoning,
 * tool calls, compact, text size. Each change is saved at once (the web's switches); a refusal puts the
 * old value back and says why.
 */
private fun LazyListScope.hubPreferences() {
    item { HubPreferences() }
}

@Composable
private fun HubPreferences() {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val ops = remember { OwnSettingsOps { graph.apis(graph.store.current!!) } }
    var saved by remember { mutableStateOf<Preferences?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    LaunchedEffect(Unit) { ops.preferences().onSuccess { saved = it }.onFailure { error = it as HubError } }

    fun save(change: (Preferences) -> Preferences) {
        val before = saved ?: return
        val next = change(before)
        if (next == before) return
        saved = next
        scope.launch {
            ops.savePreferences(next)
                .onSuccess { saved = it; error = null }
                .onFailure { saved = before; error = it as HubError }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.own_settings_display_hub))
        ErrorNotice(error)
        val p = saved
        if (p == null) {
            if (error == null) Spinner()
        } else {
            HubPreferenceRows(p, ::save)
        }
        Text(stringResource(R.string.own_settings_display_hub_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun HubPreferenceRows(p: Preferences, save: ((Preferences) -> Preferences) -> Unit) {
    val t = LocalTokens.current
    GroupedList(Modifier.testTag("display.hub")) {
        Custom {
            Text(stringResource(R.string.own_settings_display_link_target), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
            Segmented(
                listOf(
                    Segment(Preferences.LinkTarget.IN_APP, stringResource(R.string.own_settings_display_link_in_app), tag = "display.link.in_app"),
                    Segment(Preferences.LinkTarget.BROWSER, stringResource(R.string.own_settings_display_link_browser), tag = "display.link.browser"),
                ),
                p.linkTarget, { v -> save { it.copy(linkTarget = v) } }, Modifier.fillMaxWidth(), size = ControlSize.Sm,
            )
        }
        Custom {
            Text(stringResource(R.string.own_settings_display_busy_input), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
            Segmented(
                listOf(
                    Segment(Preferences.BusyInputMode.QUEUE, stringResource(R.string.own_settings_display_busy_queue), tag = "display.busy.queue"),
                    Segment(Preferences.BusyInputMode.NEXT, stringResource(R.string.own_settings_display_busy_next), tag = "display.busy.next"),
                    Segment(Preferences.BusyInputMode.INTERRUPT, stringResource(R.string.own_settings_display_busy_interrupt), tag = "display.busy.interrupt"),
                ),
                p.busyInputMode, { v -> save { it.copy(busyInputMode = v) } }, Modifier.fillMaxWidth(), size = ControlSize.Sm,
            )
        }
        Custom {
            ToggleRow(stringResource(R.string.own_settings_display_show_reasoning), p.showReasoning, { v -> save { it.copy(showReasoning = v) } }, Modifier.testTag("display.reasoning"))
            ToggleRow(stringResource(R.string.own_settings_display_show_tool_calls), p.showToolCalls, { v -> save { it.copy(showToolCalls = v) } }, Modifier.testTag("display.tool_calls"))
            ToggleRow(stringResource(R.string.own_settings_display_compact), p.compact, { v -> save { it.copy(compact = v) } }, Modifier.testTag("display.compact"))
        }
        Custom {
            val scale = OwnSettingsRules.textScale(p.textScale)
            val index = OwnSettingsRules.textScales.indexOf(scale)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.own_settings_display_text_scale, scale.movePointRight(2).toInt().toString()),
                    fontSize = FontTokens.sizeMd.sp, color = t.text, modifier = Modifier.weight(1f).testTag("display.text_scale"),
                )
                HubButton(
                    "A−", { save { it.copy(textScale = OwnSettingsRules.textScales[index - 1]) } },
                    kind = ButtonKind.Secondary, size = ControlSize.Sm, enabled = index > 0, modifier = Modifier.testTag("display.text_smaller"),
                )
                HubButton(
                    "A+", { save { it.copy(textScale = OwnSettingsRules.textScales[index + 1]) } },
                    kind = ButtonKind.Secondary, size = ControlSize.Sm, enabled = index < OwnSettingsRules.textScales.lastIndex,
                    modifier = Modifier.testTag("display.text_larger"),
                )
            }
        }
    }
}

internal val displayPage = SettingsPageEntry("display") { DisplayPage(showLanguage = true) }

/** Theme is Display without the language (the same page, iOS's twin). */
internal val themePage = SettingsPageEntry("theme") { DisplayPage(showLanguage = false) }

package hub.core.android.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hub.core.android.AppLanguage
import hub.core.android.R
import hub.core.android.graph
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.theme.ThemeChoice

@Composable
private fun DisplayPage(showLanguage: Boolean) {
    val context = LocalContext.current
    val prefs = context.graph.prefs
    val theme by prefs.theme.collectAsState()
    LazyColumn(contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    }
}

internal val displayPage = SettingsPageEntry("display") { DisplayPage(showLanguage = true) }

/** Theme is Display without the language (the same page, iOS's twin). */
internal val themePage = SettingsPageEntry("theme") { DisplayPage(showLanguage = false) }

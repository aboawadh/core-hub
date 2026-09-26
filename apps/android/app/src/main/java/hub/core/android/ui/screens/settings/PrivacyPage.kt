package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hub.core.android.R
import hub.core.android.graph
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox

/** App tokens and paired devices that can act as this person, as the web's Privacy lists them. */
@Composable
private fun PrivacyPage() {
    val context = LocalContext.current
    val tokens = rememberLoad { context.graph.apis(context.graph.store.current!!).auth.authListAppTokens().items }
    LoadView(tokens) { list ->
        LazyColumn(contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { NoticeBox(stringResource(R.string.privacy_tokens), BadgeTone.Info) }
            if (list.isEmpty()) item { EmptyState(stringResource(R.string.privacy_none), icon = Lucide.Shield) }
            else item {
                GroupedList {
                    list.forEach { token -> Item(token.name, subtitle = token.lastUsedAt?.let(::localTime), icon = Lucide.KeyRound) }
                }
            }
        }
    }
}

internal val privacyPage = SettingsPageEntry("privacy") { PrivacyPage() }

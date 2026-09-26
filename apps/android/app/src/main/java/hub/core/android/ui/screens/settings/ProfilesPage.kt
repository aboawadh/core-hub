package hub.core.android.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import hub.core.android.R
import hub.core.android.graph
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide

@Composable
private fun ProfilesPage() {
    val context = LocalContext.current
    val profiles = rememberLoad { context.graph.apis(context.graph.store.current!!).auth.authListProfiles().items }
    LoadView(profiles) { list ->
        LazyColumn(contentPadding = settingsPagePadding) {
            item {
                GroupedList {
                    list.forEach { p -> Item(p.name, subtitle = stringResource(R.string.profiles_counts, p.slug, p.agentCount, p.sessionCount), icon = Lucide.LayoutGrid) }
                }
            }
        }
    }
}

/** Profiles (destination `workspaces`: the code's word; a person reads «profile»). */
internal val workspacesPage = SettingsPageEntry("workspaces") { ProfilesPage() }

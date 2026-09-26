package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hub.core.android.R
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide

@Composable
private fun AccountPage(shell: ShellViewModel) {
    val session by shell.session.collectAsState()
    val s = session ?: return
    LazyColumn(contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            GroupedList {
                Item(s.user.displayName, subtitle = "@${s.user.username}", icon = Lucide.CircleUserRound, trailing = { Badge(s.user.role) })
                Item(stringResource(R.string.account_profiles), subtitle = s.user.profiles.joinToString(" · ") { shell.profileName(it) }, icon = Lucide.LayoutGrid)
            }
        }
        item {
            HubButton(term("sign_out"), shell::signOut, kind = ButtonKind.Danger, icon = Lucide.LogOut, fill = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

internal val accountPage = SettingsPageEntry("account") { AccountPage(it.shell) }

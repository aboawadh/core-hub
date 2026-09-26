package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.BuildConfig
import hub.core.android.R
import hub.core.android.generated.FontTokens
import hub.core.android.generated.Product
import hub.core.android.graph
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val meta = rememberLoad { context.graph.apis(context.graph.store.current!!).meta.metaGet() }
    LazyColumn(contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hub.core.android.ui.components.BrandMark(48)
                Text(stringResource(R.string.app_name), fontSize = FontTokens.sizeXl.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.about_app, BuildConfig.VERSION_NAME), fontSize = FontTokens.sizeSm.sp, color = LocalTokens.current.textMuted)
            }
        }
        item {
            LoadView(meta) { m ->
                GroupedList {
                    Item(m.name, subtitle = stringResource(R.string.about_hub, m.serverVersion, m.contractVersion), icon = Lucide.Server)
                    Item(stringResource(R.string.about_address), subtitle = context.graph.store.current?.hub, icon = Lucide.Link)
                }
            }
        }
        item { Text(stringResource(R.string.about_license, Product.NAME), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted) }
    }
}

internal val aboutPage = SettingsPageEntry("about") { AboutPage() }

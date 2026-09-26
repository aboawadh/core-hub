package hub.core.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hub.core.android.R
import hub.core.android.graph
import hub.core.android.nav.AppPaths
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.Lucide

/*
 * The one fallback for a page the phone does not draw yet (docs/clients/phone-pages.md): its title,
 * a sentence that says so, and the same page on the web one tap away. A page that is not native
 * has its own file whose entry says `native = false`; the registry draws this for it.
 */

/** The padding every Settings page's list uses. */
internal val settingsPagePadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)

/** A page the phone does not draw: said plainly, with the same page on the web one tap away. */
@Composable
fun OnTheWebPage(destination: String, agentId: String? = null) {
    val context = LocalContext.current
    val hub = context.graph.store.current?.hub
    val url = hub?.let { AppPaths.webUrl(it, destination) }?.let { u -> agentId?.let { u.replace(":agentId", it) } ?: u }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(term(destination), body = stringResource(R.string.on_the_web_body), icon = settingsIcon(destination))
        if (url != null) {
            HubButton(
                stringResource(R.string.on_the_web_open), { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                icon = Lucide.ExternalLink, fill = true, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

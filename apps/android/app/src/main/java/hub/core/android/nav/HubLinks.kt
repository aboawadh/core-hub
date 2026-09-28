package hub.core.android.nav

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * A link to this hub's own pages in a reply (a chat, a task, a room's invite…) opens in the app,
 * not in the browser: the web's address and the app's path are the same (surfaceRoutes), so the
 * path is resolved as a `corehub://open/<path>` link is. A room's invite (`<hub>/join/<code>`)
 * opens the app's join dialog. Anything else — another site, a hub page the app has no screen
 * for — still goes to the browser. Since 2026-09-27 (every section of the app works on its own).
 */

/** What a link in the app opens. */
sealed interface InAppLink {
    /** A page, and the item to open on it once it is on screen ([Focus]), when the link names one. */
    data class Page(val route: Route, val focus: FocusItem? = null) : InAppLink
    data class Join(val code: String) : InAppLink
}

object HubLinks {
    /** A room invite waiting for its dialog (set by a tapped link, cleared by the dialog). */
    val joinCode = MutableStateFlow<String?>(null)

    /** The path and query of [uri] when it is on [hub] (same scheme, host and port), or a bare `/path`; else null. */
    fun pathOf(uri: String, hub: String): String? {
        if (uri.startsWith("/") && !uri.startsWith("//")) return uri
        val link = runCatching { java.net.URI(uri) }.getOrNull() ?: return null
        val home = runCatching { java.net.URI(hub) }.getOrNull() ?: return null
        if (link.scheme == null || link.host == null) return null
        fun port(u: java.net.URI) = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80
        if (!link.scheme.equals(home.scheme, true) || !link.host.equals(home.host, true) || port(link) != port(home)) return null
        val base = home.rawPath.orEmpty().trimEnd('/')
        val path = link.rawPath.orEmpty().let { if (base.isNotEmpty() && it.startsWith(base)) it.removePrefix(base) else it }.ifEmpty { "/" }
        return path + (link.rawQuery?.let { "?$it" } ?: "")
    }

    /** What a link opens in the app, or null when it belongs in the browser. */
    fun target(uri: String, hub: String, profile: String): InAppLink? {
        val path = pathOf(uri, hub) ?: return null
        if (path.startsWith("/join/")) {
            val code = path.removePrefix("/join/").substringBefore('?').substringBefore('/').uppercase()
            return code.takeIf { it.length in 6..32 && it.all(Char::isLetterOrDigit) }?.let { InAppLink.Join(it) }
        }
        val target = AppPaths.resolve(path) ?: return null
        val route = AppPaths.route(target, profile) ?: return null
        return InAppLink.Page(route, AppPaths.focus(target, profile))
    }
}

/** Opens a link inside the app when it can (true), provided by the signed-in shell; null elsewhere. */
val LocalOpenInApp = staticCompositionLocalOf<((String) -> Boolean)?> { null }

package hub.core.android.nav

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * The drawer's «Tools» group (DECISIONS §126, phones §128): Agents (owners and admins), Tasks,
 * Workflows and Schedules under one heading that opens and closes so the chats list gets the room.
 * Open by default; the choice is this device's (SharedPreferences `sidebar_groups_closed`, a string
 * set), read defensively — anything unreadable counts as every group open. While a group is closed
 * and the page shown is one of its items, the heading is drawn as the current place.
 */

object SidebarGroups {
    const val KEY = "sidebar_groups_closed"

    /** A group's items this person sees, in the manifest's order; a group with none is not drawn. */
    fun visibleItems(items: List<String>, isAdmin: Boolean): List<String> = items.filter { Screens.visible(it, isAdmin) }

    /** The heading stands for the current page: the group is closed and the page is one of its items. */
    fun headingMarked(closed: Boolean, items: List<String>, current: String): Boolean = closed && current in items
}

/** The groups closed on this device. */
class SidebarGroupsStore(private val prefs: SharedPreferences) {
    private val _closed = MutableStateFlow(read())
    val closed: StateFlow<Set<String>> = _closed.asStateFlow()

    private fun read(): Set<String> = runCatching { prefs.getStringSet(SidebarGroups.KEY, null)?.filterNotNull()?.toSet() }
        .getOrNull().orEmpty()

    fun isClosed(group: String): Boolean = group in _closed.value

    /** Opens a closed group, closes an open one, and remembers it. */
    fun toggle(group: String) {
        val next = if (group in _closed.value) _closed.value - group else _closed.value + group
        _closed.value = next
        runCatching { prefs.edit().putStringSet(SidebarGroups.KEY, next.toMutableSet()).apply() }
    }
}

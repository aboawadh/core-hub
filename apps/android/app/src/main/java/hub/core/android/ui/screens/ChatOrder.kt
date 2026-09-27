package hub.core.android.ui.screens

import android.content.SharedPreferences
import hub.core.client.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * The order a person drags the chats into (the web's `sessions/order.ts`, iOS's SessionOrder). The
 * contract has no sort field (only `pinned`), so the order is kept on this phone, per view — every
 * profile together, or one profile — and laid over the hub's newest-first order; a chat it does not
 * know keeps the hub's place. A chat is dragged within its group (a long press, then move); dropped
 * on a category it is filed there, dropped on the chats in no group it leaves its category. Move up /
 * Move down in the chat's menu do the same without dragging. Since 2026-09-27.
 */
object ChatOrder {
    /** The view the order belongs to: one profile, or every profile together. */
    fun scope(profileFilter: String?): String = profileFilter ?: "all"

    /** Pinned first; within each, the remembered order, then the hub's. */
    fun arrange(sessions: List<Session>, manual: List<String>): List<Session> {
        val rank = manual.withIndex().associate { it.value to it.index }
        return sessions.withIndex().sortedWith(
            compareBy<IndexedValue<Session>> { if (it.value.pinned) 0 else 1 }
                .thenBy { rank[it.value.id] ?: Int.MAX_VALUE }
                .thenBy { it.index },
        ).map { it.value }
    }

    /** [moved] takes [target]'s place among [shown] (the web's `move(ids, from, to)`); null when nothing changes. */
    fun drop(moved: String, target: String, shown: List<String>): List<String>? {
        val from = shown.indexOf(moved)
        val to = shown.indexOf(target)
        if (from < 0 || to < 0 || from == to) return null
        val next = shown.toMutableList()
        next.removeAt(from)
        next.add(to, moved)
        return next
    }

    /** One place up (-1) or down (+1) in [shown]; null at either end. */
    fun step(moved: String, offset: Int, shown: List<String>): List<String>? {
        val from = shown.indexOf(moved)
        val to = from + offset
        if (from < 0 || to !in shown.indices) return null
        return shown.toMutableList().also { it[from] = shown[to]; it[to] = moved }
    }

    /** The remembered order after one group's new order: its ids in their new places, the rest after them. */
    fun merge(group: List<String>, manual: List<String>): List<String> = group + manual.filter { it !in group }

    /** What dropping a chat on a place means (the web's `dropOutcome`). */
    sealed interface Outcome {
        data class Move(val categoryId: String?) : Outcome
        data object Reorder : Outcome
        data object None : Outcome
    }

    /**
     * [source] and [target] are group keys (`pinned`, `category:<id>`, `channel:<p>`, `rest`).
     * Within its own group: the order. Into a category of the chat's own profile: a move. Onto the
     * chats in no group, from a category: out of it. Nothing is moved into a channel or another
     * profile's category.
     */
    fun outcome(session: Session, source: String, target: String?, categories: Map<String, String>): Outcome = when {
        target == null -> Outcome.None
        target == source -> Outcome.Reorder
        target.startsWith("category:") -> {
            val id = target.removePrefix("category:")
            if (categories[id] == session.profile) Outcome.Move(id) else Outcome.None
        }
        target == "rest" && source.startsWith("category:") -> Outcome.Move(null)
        else -> Outcome.None
    }

    /** One item of the drawn list: its key, where it sits (top and height), and what it is. */
    data class Placed(val key: Any, val top: Int, val size: Int)

    /** The item under the dragged one's middle once it has moved by [dy]; null over nothing (or itself). */
    fun landing(items: List<Placed>, dragged: Any, dy: Float): Any? {
        val me = items.firstOrNull { it.key == dragged } ?: return null
        val middle = me.top + me.size / 2f + dy
        return items.firstOrNull { it.key != dragged && middle >= it.top && middle < it.top + it.size }?.key
    }

    /** A move smaller than this is a long press, not a drag: the chat's menu opens. */
    const val DRAG_SLOP_PX = 24f
}

/** The remembered orders, one per view, kept in this phone's preferences. */
class ChatOrderStore(private val prefs: SharedPreferences) {
    private val _order = MutableStateFlow<List<String>>(emptyList())
    val order: StateFlow<List<String>> = _order.asStateFlow()
    private var scope: String? = null

    private fun key(scope: String) = "order.$scope"

    fun use(scope: String) {
        if (scope == this.scope) return
        this.scope = scope
        _order.value = prefs.getString(key(scope), null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    }

    /** A group's new order, merged into what this view remembers. */
    fun save(group: List<String>) {
        val s = scope ?: return
        val next = ChatOrder.merge(group, _order.value)
        _order.value = next
        prefs.edit().putString(key(s), next.joinToString("\n")).apply()
    }
}

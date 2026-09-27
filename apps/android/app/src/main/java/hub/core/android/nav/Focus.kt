package hub.core.android.nav

import hub.core.client.model.ResourceRef
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * «Open» on a background item, a waiting approval or a notice leads to the thing itself — the
 * task's sheet, the workflow run, the schedule — not only to its page (before 2026-09-27 a task run
 * opened the board and a workflow run the Schedules list). The page is still the route; which item
 * to open there is set here and taken by the page once it is on screen.
 */

/** One item to open, in its own profile. */
data class FocusItem(val kind: Kind, val id: String, val profile: String) {
    enum class Kind { TASK, SCHEDULE, WORKFLOW_RUN }
}

object Focus {
    val item = MutableStateFlow<FocusItem?>(null)

    /** The item a resource names, when its page can open it on its own. */
    fun of(resource: ResourceRef?, profile: String?): FocusItem? {
        if (resource == null || profile == null) return null
        return when (resource.kind) {
            ResourceRef.Kind.TASK -> FocusItem(FocusItem.Kind.TASK, resource.id, profile)
            ResourceRef.Kind.SCHEDULE -> FocusItem(FocusItem.Kind.SCHEDULE, resource.id, profile)
            ResourceRef.Kind.WORKFLOW_RUN -> FocusItem(FocusItem.Kind.WORKFLOW_RUN, resource.id, profile)
            else -> null
        }
    }

    /** Takes the pending item of [kind] (it opens once). */
    fun take(kind: FocusItem.Kind): FocusItem? {
        val f = item.value ?: return null
        if (f.kind != kind) return null
        item.value = null
        return f
    }
}

package hub.core.android.nav

import hub.core.android.MemoryPrefs
import hub.core.client.model.ResourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer's «Tools» group and the ways to Workflows (DECISIONS §128): the group's open/closed
 * choice is this device's, open by default and read defensively; its heading stands for the page
 * while it is closed on one of its items; and everything that named a workflow run through
 * Schedules opens Workflows (and the run), while plain schedule things still open Schedules.
 */
class ToolsDrawerTest {
    private val tools = Screens.groups.first { it.first == "tools" }.second

    @Test fun `the group is open by default and a toggle is remembered on this device`() {
        val prefs = MemoryPrefs()
        val store = SidebarGroupsStore(prefs)
        assertFalse(store.isClosed("tools"))
        assertTrue(store.closed.value.isEmpty())

        store.toggle("tools")
        assertTrue(store.isClosed("tools"))
        assertEquals(setOf("tools"), prefs.values[SidebarGroups.KEY])
        // A new start reads the same choice back.
        assertTrue(SidebarGroupsStore(prefs).isClosed("tools"))

        store.toggle("tools")
        assertFalse(store.isClosed("tools"))
        assertFalse(SidebarGroupsStore(prefs).isClosed("tools"))
    }

    @Test fun `anything unreadable under the key counts as every group open`() {
        val prefs = MemoryPrefs()
        prefs.values[SidebarGroups.KEY] = "tools" // not a string set
        val store = SidebarGroupsStore(prefs)
        assertFalse(store.isClosed("tools"))
        // …and the next toggle writes a proper set over it.
        store.toggle("tools")
        assertEquals(setOf("tools"), prefs.values[SidebarGroups.KEY])
    }

    @Test fun `the heading is marked only while closed on one of its pages`() {
        assertTrue(SidebarGroups.headingMarked(closed = true, items = tools, current = "workflows"))
        assertTrue(SidebarGroups.headingMarked(closed = true, items = tools, current = "schedules"))
        assertFalse("open: the row itself is highlighted", SidebarGroups.headingMarked(closed = false, items = tools, current = "workflows"))
        assertFalse("elsewhere", SidebarGroups.headingMarked(closed = true, items = tools, current = "new_chat"))
        // A member does not see Agents, so being on it (refused) does not mark the heading.
        val member = SidebarGroups.visibleItems(tools, isAdmin = false)
        assertFalse(SidebarGroups.headingMarked(closed = true, items = member, current = "agent_manager"))
    }

    @Test fun `Schedules' old workflows address and a workflow run open Workflows, with the run`() {
        val old = AppPaths.resolve("/schedules?section=workflows&workflow=W1&profile=home")!!
        assertEquals(Route.Workflows, AppPaths.route(old, "default"))
        assertNull("no run named", AppPaths.focus(old, "default"))

        val run = AppPaths.resolve("/schedules?workflow_run=R1&profile=home")!!
        assertEquals(Route.Workflows, AppPaths.route(run, "default"))
        assertEquals(FocusItem(FocusItem.Kind.WORKFLOW_RUN, "R1", "home"), AppPaths.focus(run, "default"))

        val direct = AppPaths.resolve("/workflows?workflow_run=R2")!!
        assertEquals(Route.Workflows, AppPaths.route(direct, "work"))
        assertEquals(FocusItem(FocusItem.Kind.WORKFLOW_RUN, "R2", "work"), AppPaths.focus(direct, "work"))

        // Plain schedules still open Schedules.
        val plain = AppPaths.resolve("/schedules")!!
        assertEquals(Route.Schedules, AppPaths.route(plain, "work"))
        assertNull(AppPaths.focus(plain, "work"))
    }

    @Test fun `a link in a reply carries the run to open`() {
        val link = HubLinks.target("https://hub.example/schedules?section=workflows&workflow_run=R9&profile=home", "https://hub.example", "work")
        assertEquals(InAppLink.Page(Route.Workflows, FocusItem(FocusItem.Kind.WORKFLOW_RUN, "R9", "home")), link)
        assertEquals(InAppLink.Page(Route.Schedules), HubLinks.target("https://hub.example/schedules", "https://hub.example", "work"))
    }

    @Test fun `a notice about a workflow run opens it on Workflows, a schedule's on Schedules`() {
        val path = hub.core.android.phone.NoticeTracker.path(ResourceRef.Kind.WORKFLOW_RUN.value, "R1", "home")
        assertEquals("/workflows?workflow_run=R1&profile=home", path)
        val target = AppPaths.resolve(path)!!
        assertEquals(Route.Workflows, AppPaths.route(target, "work"))
        assertEquals(FocusItem(FocusItem.Kind.WORKFLOW_RUN, "R1", "home"), AppPaths.focus(target, "work"))
        assertEquals("/schedules", hub.core.android.phone.NoticeTracker.path(ResourceRef.Kind.SCHEDULE_RUN.value, "S1", "home"))
        assertEquals(Route.Workflows, hub.core.android.ui.screens.NoticeLinks.route(ResourceRef(ResourceRef.Kind.WORKFLOW_RUN, "R1"), "home"))
        assertEquals(Route.Schedules, hub.core.android.ui.screens.NoticeLinks.route(ResourceRef(ResourceRef.Kind.SCHEDULE, "S1"), "home"))
    }
}

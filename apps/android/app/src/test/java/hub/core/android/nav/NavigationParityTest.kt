package hub.core.android.nav

import hub.core.android.generated.SurfaceRoutes
import hub.core.android.generated.Terms
import hub.core.android.repoRoot
import hub.core.android.ui.screens.PageRegistry
import hub.core.android.ui.screens.SearchHits
import hub.core.android.ui.screens.SettingsList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android client against docs/clients/navigation.json (docs/clients/README.md, rules 1–7).
 * The manifest is read from the repository (the test resources point at docs/clients), never
 * from a copy.
 */
class NavigationParityTest {
    private val manifest: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("navigation.json")!!.readText(),
    ).jsonObject

    private fun list(key: String) = manifest.getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private val destinations = manifest.getValue("destinations").jsonArray.map { it.jsonObject }
        .filter { d -> d["surfaces"]?.jsonArray?.any { it.jsonPrimitive.content == "android" } ?: true }
    private val ids = destinations.map { it.getValue("id").jsonPrimitive.content }
    private fun destination(id: String) = destinations.first { it.getValue("id").jsonPrimitive.content == id }

    @Test fun `1 and 2 - every destination has a screen and every screen a destination`() {
        assertEquals(ids.toSet(), Screens.all)
        assertTrue("this_device is a phone destination", "this_device" in Screens.all)
    }

    @Test fun `pre-auth screens are the manifest's and are not destinations`() {
        val preAuth = manifest.getValue("preAuth").jsonObject.filterKeys { !it.startsWith("$") }
        assertEquals(preAuth.keys.toList(), Screens.preAuth)
        Screens.preAuth.forEach { assertFalse(it in ids) }
        assertEquals(
            preAuth.mapValues { it.value.jsonObject.getValue("routes").jsonObject.getValue("android").jsonPrimitive.content },
            SurfaceRoutes.preAuthAndroid,
        )
    }

    @Test fun `3 - one primary entry per destination, in the manifest's order`() {
        assertEquals(list("rail"), Screens.rail)
        assertEquals(list("segments"), Screens.segments)
        assertEquals(list("footer"), Screens.footer)
        assertEquals(list("settingsTabs").filter { it in ids }, Screens.settingsTabs)
        assertEquals(list("settingsManagement").filter { it in ids }, Screens.settingsManagement)
        assertEquals(list("settingsTools").filter { it in ids }, Screens.settingsTools)
        assertEquals(list("agentLevel").filter { it in ids }, Screens.agentLevel)
        assertEquals(listOf(Screens.settingsTabs, Screens.settingsManagement, Screens.settingsTools), SettingsList.groups.map { it.second })
        val primaries = Screens.rail + Screens.railExtra + Screens.segments + Screens.footer + Screens.settingsTabs + Screens.settingsManagement +
            Screens.settingsTools + Screens.agentLevel
        assertEquals("no destination has two primary entries", primaries.size, primaries.toSet().size)
    }

    private fun onAndroid(o: JsonObject) = o["surfaces"]?.jsonArray?.any { it.jsonPrimitive.content == "android" } ?: true

    @Test fun `railExtra, filtered to Android, is the app's list`() {
        assertEquals(list("railExtra").filter { it in ids }, Screens.railExtra)
        assertTrue("workflows is an Android rail entry", "workflows" in Screens.railExtra)
    }

    /** The drawer draws its groups from `sidebarGroups`, each item filtered by surface, in order (DECISIONS §128). */
    @Test fun `the drawer's groups are the manifest's sidebarGroups for Android`() {
        val groups = manifest.getValue("sidebarGroups").jsonObject.filterKeys { !it.startsWith("$") }
            .filterValues { onAndroid(it.jsonObject) }
            .map { (_, g) ->
                val o = g.jsonObject
                o.getValue("title").jsonPrimitive.content to o.getValue("items").jsonArray.map { it.jsonPrimitive.content }.filter { it in ids }
            }
        assertEquals(groups, Screens.groups)
        for ((title, items) in Screens.groups) {
            assertNotNull("$title is a term", Terms.ids[title])
            items.forEach { assertNotNull("$it opens a route", Screens.routeOf(it)) }
        }
        // Members see the items their role allows: Agents is an owner's and admin's.
        assertEquals(listOf("tasks", "workflows", "schedules"), SidebarGroups.visibleItems(Screens.groups.single().second, isAdmin = false))
        assertEquals(listOf("agent_manager", "tasks", "workflows", "schedules"), SidebarGroups.visibleItems(Screens.groups.single().second, isAdmin = true))
    }

    @Test fun `the drawer's header draws brandRow, and the rows are what is left of the rail`() {
        val brand = manifest.getValue("brandRow").jsonObject
        assertTrue("brandRow is on Android", onAndroid(brand))
        assertEquals(brand.getValue("items").jsonArray.map { it.jsonPrimitive.content }, Screens.brandRow)
        Screens.brandRow.forEach { assertNotNull("$it opens a route", Screens.routeOf(it)) }
        // Every rail entry is drawn once: as a row, in the header, or in a group.
        val drawn = Screens.railRows + Screens.brandRow + Screens.groups.flatMap { it.second }
        assertEquals((Screens.rail + Screens.railExtra).sorted(), drawn.sorted())
        assertEquals(listOf("new_chat"), Screens.railRows)
    }

    @Test fun `workflows has a screen and its route resolves`() {
        assertTrue("workflows" in Screens.all)
        assertEquals("workflows", Route.Workflows.destination)
        val path = manifest.getValue("surfaceRoutes").jsonObject.getValue("android").jsonObject.getValue("workflows").jsonPrimitive.content
        assertEquals(Route.Workflows, AppPaths.route(AppPaths.resolve(path)!!, "default"))
        assertEquals(Route.Workflows, Screens.routeOf("workflows"))
        assertFalse("members see Workflows", "workflows" in Screens.adminOnly)
    }

    private fun termsXml(dir: String): Map<String, String> {
        val file = File(System.getProperty("user.dir"), "build/generated/shared/res/$dir/terms.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            nodes.item(i).attributes.getNamedItem("name").nodeValue.removePrefix("term_") to nodes.item(i).textContent
        }
    }

    @Test fun `4 - every label and title comes from terms, in Arabic and English`() {
        val terms = manifest.getValue("terms").jsonObject
        assertEquals(terms.keys, Terms.ids.keys)
        val en = termsXml("values")
        val ar = termsXml("values-ar")
        for ((key, value) in terms) {
            assertEquals(key, value.jsonObject.getValue("en").jsonPrimitive.content, en[key])
            assertEquals(key, value.jsonObject.getValue("ar").jsonPrimitive.content, ar[key])
        }
        for (d in destinations) assertNotNull(Terms.ids[d.getValue("title").jsonPrimitive.content])
    }

    @Test fun `5 - search is the secondary way to a conversation and to the global agent`() {
        val secondary = manifest.getValue("secondaryEntries").jsonObject
        val chat = SearchHits.routeOf(fakeSession("chat")).destination
        val global = SearchHits.routeOf(fakeSession("global_agent")).destination
        for (target in listOf(chat, global)) {
            assertTrue("$target reached from search", secondary.getValue(target).jsonArray.any { it.jsonPrimitive.content == "search" })
        }
        assertEquals("global_agent", global)
    }

    @Test fun `6 - admin destinations are hidden from members, and surfaces are respected`() {
        val admin = destinations.filter { d -> d.getValue("roles").jsonArray.any { it.jsonPrimitive.content == "admin" } }
            .map { it.getValue("id").jsonPrimitive.content }.toSet()
        assertEquals(admin, Screens.adminOnly)
        admin.forEach { assertFalse(Screens.visible(it, isAdmin = false)) }
        assertTrue(SettingsList.visible(isAdmin = false).flatMap { it.second }.none { it in admin })
        val web = manifest.getValue("surfaceRoutes").jsonObject.getValue("web").jsonObject.keys
        assertFalse("this_device is not on the web", "this_device" in web)
    }

    @Test fun `7 - agent pages follow the adapter's capabilities`() {
        for (id in Screens.agentLevel) {
            assertEquals(destination(id).getValue("capability").jsonPrimitive.content, Screens.capabilityOf[id])
        }
        assertEquals(listOf("agent_skills", "agent_mcp", "agent_settings"), Screens.agentPages(listOf("streaming", "skills", "mcp"), configurable = true))
        assertEquals(listOf("agent_skills", "agent_mcp"), Screens.agentPages(listOf("skills", "mcp"), configurable = false))
        assertEquals(
            listOf("agent_skills", "agent_mcp", "agent_memory", "agent_jobs", "agent_channels", "agent_plugins", "agent_settings"),
            Screens.agentPages(listOf("plugins", "channels", "jobs", "memory", "mcp", "skills"), configurable = true),
        )
    }

    @Test fun `the page registry names every Settings and agent page once, in the manifest's order`() {
        assertEquals(Screens.settingsTabs + Screens.settingsManagement + Screens.settingsTools, PageRegistry.settings.map { it.id })
        assertEquals(list("agentLevel").filter { it in ids }, PageRegistry.agent.map { it.id })
        assertEquals(PageRegistry.settings.filter { it.native }.map { it.id }.toSet(), SettingsList.native)
    }

    /**
     * docs/clients/phone-pages.md: a page's entry lives in its page's file; `native = false` and the
     * web fallback go together, so a batch that draws a page cannot forget to flip it (or the other way).
     */
    @Test fun `a page is native exactly when its entry does not draw the web fallback`() {
        val screens = File(repoRoot, "apps/android/app/src/main/java/hub/core/android/ui/screens")
        val entry = Regex("""(SettingsPageEntry|AgentPageEntry)\("([a-z_]+)"""")
        val found = mutableMapOf<String, Boolean>()
        screens.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            entry.findAll(text).filter { !text.substring(0, it.range.first).trimEnd().endsWith("class") }.forEach { m ->
                val end = text.indexOf("\n\n", m.range.last).let { if (it < 0) text.length else it }
                val declaration = text.substring(m.range.first, end)
                val id = m.groupValues[2]
                val native = !declaration.contains("native = false")
                assertFalse("$id is declared twice", id in found)
                found[id] = native
                assertEquals("${file.name}: $id native=$native but ${if (native) "draws" else "does not draw"} OnTheWebPage", native, !declaration.contains("OnTheWebPage("))
            }
        }
        val registry = (PageRegistry.settings.map { it.id to it.native } + PageRegistry.agent.map { it.id to it.native }).toMap()
        assertEquals(registry, found)
    }

    @Test fun `every Android path resolves to its destination`() {
        val android = manifest.getValue("surfaceRoutes").jsonObject.getValue("android").jsonObject
        assertEquals(ids.toSet(), android.keys)
        for ((id, route) in android) {
            val path = route.jsonPrimitive.content.replace(":agentId", "01J8QK3ZR2W7M5N4P6T8V9X0AG")
                .replace("/:sessionId?", "/01J8QK3ZR2W7M5N4P6T8V9X0YA").replace("/:roomId?", "/01J8QK3ZR2W7M5N4P6T8V9X0RM")
            val target = AppPaths.resolve(path)
            assertEquals(path, id, target?.destination)
            val opened = AppPaths.route(target!!, "default")
            assertEquals(path, id, opened?.destination)
        }
        assertEquals(SurfaceRoutes.android.keys, android.keys)
    }

    private fun fakeSession(source: String) = hub.core.client.infrastructure.Serializer.kotlinxSerializationJson.decodeFromString(
        hub.core.client.model.Session.serializer(),
        """{"id":"01J8QK3ZR2W7M5N4P6T8V9X0S1","profile":"work","owner_id":"01J8QK3ZR2W7M5N4P6T8V9X0HM","created_at":"2026-09-20T10:00:00Z",
           "updated_at":"2026-09-20T10:00:00Z","agent_id":"01J8QK3ZR2W7M5N4P6T8V9X0AG","source":"$source","pinned":false,
           "archived":false,"message_count":0,"status":"idle","notify":false}""",
    )
}

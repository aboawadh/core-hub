package hub.core.android.rooms

import hub.core.android.data.HubApis
import hub.core.android.realtime.Envelope
import hub.core.android.ui.components.FormProblem
import hub.core.android.ui.components.FormRules
import hub.core.client.api.MetaApi
import hub.core.client.api.RoomsApi
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Agent
import hub.core.client.model.HandoffChain
import hub.core.client.model.HandoffPolicy
import hub.core.client.model.RoomDetail
import hub.core.client.model.Seat
import hub.core.client.model.SeatConfig
import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Managing a room on the phone (apps batch 7): the web's room menu, seat form, settings and handoff
 * strip as rules, the room's state keeping who manages it, and the calls behind them against a
 * mock hub. iOS RoomManageTests checks the same rules.
 */
class RoomManageTest {
    private val json = Serializer.kotlinxSerializationJson
    private val server = MockWebServer()
    private val room = "01J8QK3ZR2W7M5N4P6T8V9X0RM"
    private val me = "01J8QK3ZR2W7M5N4P6T8V9X0HM"
    private val planner = "01J8QK3ZR2W7M5N4P6T8V9X0ST"
    private val coder = "01J8QK3ZR2W7M5N4P6T8V9X0SU"

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    private fun actions() = RoomActions(HubApis(server.url("/").toString().trimEnd('/'), OkHttpClient()))

    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun agent(id: String, name: String, status: String = "available", enabled: Boolean = true) = json.decodeFromString(
        Agent.serializer(),
        """{"id":"$id","profile":"work","owner_id":"$me","created_at":"2026-09-20T10:00:00Z",
           "updated_at":"2026-09-20T10:00:00Z","slug":"a$id","name":"$name","kind":"hermes","vendor":null,
           "avatar":{"kind":"generated","url":null,"seed":"a"},"status":"$status","enabled":$enabled,
           "install":{"source":"managed","path":null,"package":null,"command":null,"version":null,"latest_version":null,
             "update_available":false,"pinned_version":null,"newer_than_tested":false,"auto_update":false,
             "auto_update_supported":false,"checked_at":null,"error":null},
           "runtime":{"state":"running","url":null,"error":null},"capabilities":["streaming"],"sections":[],"limited":false,"subagents":"none","default_model":null}""",
    )

    private fun seatJson(id: String, name: String, description: String? = null, model: String? = null) = """
        {"id":"$id","room_id":"$room","agent_id":"01J8QK3ZR2W7M5N4P6T8V9X0AG","name":"$name","description":${description?.let { "\"$it\"" }},
         "avatar":{"kind":"generated","url":null,"seed":"s"},"model":${model?.let { "\"$it\"" }},"provider":null,"reasoning_effort":null,
         "instructions":null,"preset_id":null,"status":"idle","executor":{"kind":"server","device_id":null},
         "created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z"}
    """.trimIndent()

    private fun seat(id: String, name: String, description: String? = null, model: String? = null) =
        json.decodeFromString(Seat.serializer(), seatJson(id, name, description, model))

    private fun chainJson(id: String, status: String, reason: String? = null, used: Boolean = false, at: String = "2026-09-21T10:00:00Z") = """
        {"id":"$id","room_id":"$room","from_seat_id":"$planner","to_seat_id":"$coder","status":"$status",
         "stop_reason":${reason?.let { "\"$it\"" }},"depth":2,"max_depth":3,"continue_used":$used,"error":null,"updated_at":"$at"}
    """.trimIndent()

    private fun chain(id: String, status: String, reason: String? = null, used: Boolean = false, at: String = "2026-09-21T10:00:00Z") =
        json.decodeFromString(HandoffChain.serializer(), chainJson(id, status, reason, used, at))

    private fun roomJson(name: String, canManage: Boolean, invite: String?, mentionAll: Boolean, depth: Int?) =
        """{"id":"$room","profile":"work","owner_id":"$me","created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z",
            "name":"$name","working_dir":null,"invite_code":${invite?.let { "\"$it\"" }},"can_manage":$canManage,"can_mention_all":$mentionAll,
            "member_count":1,"total_tokens":0,"summary_policy":{"every_turns":20,"model":null,"provider":null},
            "handoff":{"enabled":true,"max_depth":$depth},"seats":[${seatJson(planner, "Planner")}],"last_active_at":null,
            "lead_seat_id":"$planner","archived_at":null"""

    private fun opened(): RoomState = RoomReducer.loaded(
        RoomState(),
        json.decodeFromString(
            RoomDetail.serializer(),
            roomJson("فريق الإطلاق", true, "AB12CD34", true, 3) +
                ""","members":[],"runs":[],"pending_approvals":[],"handoff_chains":[${chainJson("c1", "active")}],
                   "memory":{"summary":null,"status":"idle","summarized_turn_count":0,"error":null,"updated_at":null},"typing":[]}""",
        ),
        emptyList(),
    )

    private var seq = 1L
    private fun env(event: String, payload: String) =
        Envelope.parse("""{"event":"$event","namespace":"/rt/rooms","profile":"work","ts":"2026-09-21T10:15:04Z","seq":${++seq},"payload":$payload}""")!!

    private val labels = RoomManage.SeatLabels("agent", "name", "hint", "add hint", "role", "instructions", "i hint", "model", "m hint")

    // Actions

    @Test fun `a manager renames, sets, clears, archives and deletes, a member only leaves`() {
        val a = RoomManage.Action.entries
        assertEquals(listOf(a[0], a[1], a[2], RoomManage.Action.ARCHIVE, RoomManage.Action.DELETE), RoomManage.actions(true, false))
        assertEquals(RoomManage.Action.UNARCHIVE, RoomManage.actions(true, true)[3])
        assertEquals(listOf(RoomManage.Action.LEAVE), RoomManage.actions(false, false))
        assertEquals(emptyList<RoomManage.Action>(), RoomManage.actions(false, false, owner = true))
        assertEquals(listOf(RoomManage.Action.RENAME, RoomManage.Action.ARCHIVE, RoomManage.Action.DELETE), RoomManage.rowActions(true, false))
        assertEquals(listOf(RoomManage.Action.LEAVE), RoomManage.rowActions(false, true))
    }

    @Test fun `a rename is trimmed and nothing is sent for an empty or unchanged name`() {
        assertEquals("Launch", RoomManage.renamed("  Launch ", "Old")?.name)
        assertNull(RoomManage.renamed("  ", "Old"))
        assertNull(RoomManage.renamed("Old ", "Old"))
        assertNull(RoomManage.renamed("x".repeat(121), "Old"))
    }

    // Seats

    @Test fun `the seat form chooses an agent only when adding, among agents that can sit`() {
        val agents = listOf(agent("1", "Hermes"), agent("2", "Off", enabled = false), agent("3", "Broken", status = "error"))
        val adding = RoomManage.seatFields(true, agents, labels)
        assertEquals(listOf("agent", "name", "role", "instructions", "model"), adding.map { it.key })
        assertEquals(listOf("1"), adding.first().options.map { it.value })
        assertFalse(adding[1].required)
        val editing = RoomManage.seatFields(false, agents, labels)
        assertEquals(listOf("name", "role", "instructions", "model"), editing.map { it.key })
        assertTrue(editing.first().required)
        assertEquals("1", RoomManage.seatValues(null, agents)["agent"])
        assertEquals("m1", RoomManage.seatValues(seat(planner, "Planner", model = "m1"), agents)["model"])
    }

    @Test fun `a new seat is named after its agent unless named, and leaves empty fields out`() {
        val agents = listOf(agent("1", "Hermes"))
        val plain = RoomManage.seatConfig(mapOf("agent" to "1", "name" to " ", "role" to "", "model" to " "), agents)
        assertEquals(SeatConfig(agentId = "1", name = "Hermes"), plain)
        val named = RoomManage.seatConfig(mapOf("agent" to "1", "name" to "Critic", "role" to "Finds holes", "instructions" to "Be brief", "model" to "gpt-5"), agents)
        assertEquals(SeatConfig(agentId = "1", name = "Critic", description = "Finds holes", model = "gpt-5", instructions = "Be brief"), named)
        assertNull(RoomManage.seatConfig(mapOf("agent" to "9"), agents))
    }

    @Test fun `a seat's line is its role and its model or the agent's own`() {
        assertEquals("Plans · m1", RoomManage.seatLine(seat(planner, "P", "Plans", "m1"), "own"))
        assertEquals("own", RoomManage.seatLine(seat(planner, "P"), "own"))
    }

    // Settings

    @Test fun `settings send only what changed, and the policy whole`() {
        val policy = HandoffPolicy(enabled = true, maxDepth = 3)
        val values = RoomManage.settingsValues(true, policy)
        assertEquals(mapOf("mention_all" to "true", "handoff" to "true", "max_depth" to "3"), values)
        assertNull(RoomManage.settingsPatch(values, true, policy))
        val onlyAll = RoomManage.settingsPatch(values + ("mention_all" to "false"), true, policy)!!
        assertEquals(false, onlyAll.canMentionAll)
        assertNull(onlyAll.handoff)
        val deeper = RoomManage.settingsPatch(values + ("max_depth" to " 5 "), true, policy)!!
        assertEquals(HandoffPolicy(enabled = true, maxDepth = 5), deeper.handoff)
        assertNull(deeper.canMentionAll)
        assertEquals(
            HandoffPolicy(enabled = false, maxDepth = null),
            RoomManage.settingsPatch(values + ("handoff" to "false") + ("max_depth" to ""), true, policy)!!.handoff,
        )
    }

    @Test fun `the depth is a whole number from 1 to 20, or empty`() {
        val depth = RoomManage.settingsFields("a", "h", "hh", "d", "dh").first { it.key == "max_depth" }
        assertNull(FormRules.problem(depth, ""))
        assertNull(FormRules.problem(depth, "20"))
        assertEquals(FormProblem.TooSmall(BigDecimal.ONE), FormRules.problem(depth, "0"))
        assertEquals(FormProblem.TooLarge(BigDecimal(20)), FormRules.problem(depth, "21"))
        assertEquals(FormProblem.NotWhole, FormRules.problem(depth, "2.5"))
    }

    // Passing the turn

    @Test fun `the strip says the chain going on now first`() {
        val seats = listOf(seat(planner, "Planner"), seat(coder, "Coder"))
        assertEquals(
            RoomManage.HandoffLine.Active("Planner", "Coder", 2),
            RoomManage.handoffLine(listOf(chain("c2", "active")), listOf(chain("c1", "stopped", "max_depth")), seats),
        )
    }

    @Test fun `only the newest stopped chain offers one more round, once`() {
        val seats = listOf(seat(planner, "Planner"), seat(coder, "Coder"))
        assertEquals(
            RoomManage.HandoffLine.Stopped("c1", HandoffChain.StopReason.MAX_DEPTH, "Planner", "Coder", more = true),
            RoomManage.handoffLine(emptyList(), listOf(chain("c1", "stopped", "max_depth")), seats),
        )
        assertEquals(false, (RoomManage.handoffLine(emptyList(), listOf(chain("c1", "stopped", "interrupted")), seats) as RoomManage.HandoffLine.Stopped).more)
        assertNull(RoomManage.handoffLine(emptyList(), listOf(chain("c1", "stopped", "loop_detected", used = true)), seats))
        assertNull(
            RoomManage.handoffLine(
                emptyList(),
                listOf(chain("c1", "stopped", "max_depth", at = "2026-09-21T10:00:00Z"), chain("c2", "completed", at = "2026-09-21T10:05:00Z")),
                seats,
            ),
        )
    }

    // The room's state

    @Test fun `a room update keeps who manages it and its invite code`() {
        val state = opened()
        assertEquals(HandoffPolicy(enabled = true, maxDepth = 3), state.room?.handoff)
        assertEquals(listOf("c1"), state.activeChains.map { it.id })
        // The hub sends every member the same room: nobody manages it, no invite code.
        val next = RoomReducer.apply(state, env("room.updated", """{"room":${roomJson("Launch", false, null, false, 5)}}}"""))
        assertEquals("Launch", next.room?.name)
        assertTrue(next.room!!.canManage)
        assertEquals("AB12CD34", next.room!!.inviteCode)
        assertFalse(next.room!!.canMentionAll)
        assertEquals(HandoffPolicy(enabled = true, maxDepth = 5), next.room!!.handoff)
    }

    @Test fun `the summary and the handoff chains follow their events`() {
        var state = opened()
        state = RoomReducer.apply(
            state, env("memory.updated", """{"room_id":"$room","memory":{"summary":"We ship Friday.","status":"idle","summarized_turn_count":12,"error":null,"updated_at":null}}"""),
        )
        assertEquals("We ship Friday.", state.memory?.summary)
        state = RoomReducer.apply(
            state, env("memory.updated", """{"room_id":"01J8QK3ZR2W7M5N4P6T8V9X0RO","memory":{"summary":null,"status":"error","summarized_turn_count":0,"error":null,"updated_at":null}}"""),
        )
        assertEquals("We ship Friday.", state.memory?.summary)
        state = RoomReducer.apply(state, env("handoff.updated", """{"room_id":"$room","chain":${chainJson("c1", "stopped", "max_depth")}}"""))
        assertTrue(state.activeChains.isEmpty())
        assertEquals(listOf("c1"), state.handoffs.map { it.id })
        state = RoomReducer.apply(state, env("handoff.updated", """{"room_id":"$room","chain":${chainJson("c2", "active")}}"""))
        assertEquals(listOf("c2"), state.activeChains.map { it.id })
        assertEquals(listOf("c2", "c1"), state.handoffs.map { it.id })
    }

    // The calls

    private fun pathOf(operation: String) = MetaApi.defaultBasePath + operation

    private fun body(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun `leaving from the list removes the member that is you`() = runTest {
        server.enqueue(
            ok(
                """{"items":[{"id":"m1","room_id":"$room","user_id":"someone","name":"Sara","avatar":{"kind":"generated","url":null,"seed":"m"},
                   "role":"owner","online":true,"joined_at":"2026-09-21T10:00:00Z"},
                   {"id":"m2","room_id":"$room","user_id":"$me","name":"Tariq","avatar":{"kind":"generated","url":null,"seed":"m"},
                   "role":"member","online":true,"joined_at":"2026-09-21T10:00:00Z"}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        actions().leave("work", room, me)
        val list = server.takeRequest()
        assertEquals("GET", list.method)
        assertEquals("work", list.getHeader("X-Hub-Profile"))
        val remove = server.takeRequest()
        assertEquals("DELETE", remove.method)
        assertEquals(pathOf(RoomsApi("").roomsRemoveMemberRequestConfig("work", room, "m2").path), remove.requestUrl!!.encodedPath)
    }

    @Test fun `an edited seat sends every field, an emptied role as empty text`() = runTest {
        server.enqueue(ok(seatJson(planner, "Critic")))
        actions().updateSeat("work", room, planner, RoomManage.seatPatch(mapOf("name" to " Critic ", "role" to "", "instructions" to "Be brief", "model" to "")))
        val sent = server.takeRequest()
        assertEquals("PATCH", sent.method)
        val payload = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("Critic", payload["name"]!!.jsonPrimitive.content)
        assertEquals("", payload["description"]!!.jsonPrimitive.content)
        assertEquals("Be brief", payload["instructions"]!!.jsonPrimitive.content)
    }

    @Test fun `the room's settings go as one patch, the policy whole`() = runTest {
        server.enqueue(ok(roomJson("R", true, "AB12CD34", false, 5) + "}"))
        val patch = RoomManage.settingsPatch(mapOf("mention_all" to "false", "handoff" to "true", "max_depth" to "5"), true, HandoffPolicy(true, 3))!!
        actions().update("work", room, patch)
        val payload = body()
        assertFalse(payload["can_mention_all"]!!.jsonPrimitive.boolean)
        val handoff = payload["handoff"]!!.jsonObject
        assertTrue(handoff["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("5", handoff["max_depth"]!!.jsonPrimitive.content)
    }

    @Test fun `clearing the context and deleting are their own calls`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        actions().clearContext("work", room)
        actions().delete("work", room)
        val clear = server.takeRequest()
        assertEquals("DELETE", clear.method)
        assertEquals(pathOf(RoomsApi("").roomsClearContextRequestConfig("work", room).path), clear.requestUrl!!.encodedPath)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals(pathOf(RoomsApi("").roomsDeleteRequestConfig("work", room).path), delete.requestUrl!!.encodedPath)
    }
}

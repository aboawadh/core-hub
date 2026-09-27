package hub.core.android.parity

import hub.core.android.MemoryPrefs
import hub.core.android.chat.ChatControls
import hub.core.android.chat.Outgoing
import hub.core.android.chat.SlashCommands
import hub.core.android.realtime.Envelope
import hub.core.android.realtime.JOBS_NAMESPACE
import hub.core.android.realtime.JobsFeed
import hub.core.android.ui.screens.ChatOrder
import hub.core.android.ui.screens.ChatOrderStore
import hub.core.android.ui.screens.HubDisplay
import hub.core.android.ui.screens.MessageQueueRules
import hub.core.android.ui.screens.TrajectoryLog
import hub.core.android.ui.screens.TrajectoryRules
import hub.core.android.ui.screens.WorkflowDraft
import hub.core.android.ui.screens.WorkflowDraftRules
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.AgentCapability
import hub.core.client.model.ContentBlock
import hub.core.client.model.Preferences
import hub.core.client.model.Session
import hub.core.client.model.SkillCategory
import hub.core.client.model.WorkflowNode
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six follow-ups iOS built in #202, on Android (2026-09-27): chats in the order dragged, the
 * message queue, `/rt/jobs` for the session, the trajectory's timeline rules and its log, the skill
 * picker after `/skill ` (with the `/` commands it lives in), and a workflow agent step's model.
 */
class FollowUpTest {
    private val json = Serializer.kotlinxSerializationJson
    private val at = "2026-09-27T10:00:00Z"

    private fun session(id: String, pinned: Boolean = false, profile: String = "work") = json.decodeFromString(
        Session.serializer(),
        """{"id":"$id","profile":"$profile","owner_id":"01J8QK3ZR2W7M5N4P6T8V9X0HM","created_at":"$at","updated_at":"$at",
           "agent_id":"01J8QK3ZR2W7M5N4P6T8V9X0AG","title":null,"source":"chat","origin":null,"channel":null,"model":null,"provider":null,
           "reasoning_effort":null,"working_dir":null,"pinned":$pinned,"archived":false,"category_id":null,"preview":null,"message_count":0,
           "usage":null,"context":null,"status":"idle","active_run_id":null,"parent_session_id":null,"notify":false,"last_message_at":"$at","match":null}""",
    )

    // ------------------------------------------------------------------ 1. order

    @Test fun `the dragged order lies over the hub's - pinned first, remembered next, unknown in the hub's place`() {
        val hub = listOf(session("a"), session("b"), session("c"), session("p", pinned = true), session("d"))
        assertEquals(listOf("p", "c", "a", "b", "d"), ChatOrder.arrange(hub, listOf("c", "a")).map { it.id })
        assertEquals(listOf("p", "a", "b", "c", "d"), ChatOrder.arrange(hub, emptyList()).map { it.id })
    }

    @Test fun `a drop takes the target's place, a step moves one place, and the group is merged into what is remembered`() {
        val shown = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), ChatOrder.drop("a", "c", shown))
        assertEquals(listOf("a", "d", "b", "c"), ChatOrder.drop("d", "b", shown))
        assertNull(ChatOrder.drop("a", "a", shown))
        assertNull(ChatOrder.drop("a", "zz", shown))
        assertEquals(listOf("b", "a", "c", "d"), ChatOrder.step("b", -1, shown))
        assertEquals(listOf("a", "b", "d", "c"), ChatOrder.step("c", 1, shown))
        assertNull(ChatOrder.step("a", -1, shown))
        assertNull(ChatOrder.step("d", 1, shown))
        assertEquals(listOf("b", "a", "x", "y"), ChatOrder.merge(listOf("b", "a"), listOf("x", "a", "y", "b")))
    }

    @Test fun `where a drop lands - its own group reorders, a category of its profile files it, the loose chats take it out`() {
        val s = session("a")
        val cats = mapOf("C1" to "work", "C2" to "home")
        assertEquals(ChatOrder.Outcome.Reorder, ChatOrder.outcome(s, "rest", "rest", cats))
        assertEquals(ChatOrder.Outcome.Move("C1"), ChatOrder.outcome(s, "rest", "category:C1", cats))
        assertEquals("another profile's category never takes it", ChatOrder.Outcome.None, ChatOrder.outcome(s, "rest", "category:C2", cats))
        assertEquals(ChatOrder.Outcome.Move(null), ChatOrder.outcome(s, "category:C1", "rest", cats))
        assertEquals(ChatOrder.Outcome.None, ChatOrder.outcome(s, "rest", "channel:telegram", cats))
        assertEquals(ChatOrder.Outcome.None, ChatOrder.outcome(s, "pinned", "rest", cats))
        assertEquals(ChatOrder.Outcome.None, ChatOrder.outcome(s, "rest", null, cats))
        val placed = listOf(ChatOrder.Placed("h", 0, 40), ChatOrder.Placed("a", 40, 50), ChatOrder.Placed("b", 90, 50), ChatOrder.Placed("c", 140, 50))
        assertEquals("b", ChatOrder.landing(placed, "a", 50f))
        assertEquals("h", ChatOrder.landing(placed, "a", -50f))
        assertNull("still over itself", ChatOrder.landing(placed, "a", 10f))
        assertNull(ChatOrder.landing(placed, "a", 500f))
    }

    @Test fun `the order is kept per view on the phone`() {
        val prefs = MemoryPrefs()
        val store = ChatOrderStore(prefs)
        store.use(ChatOrder.scope(null))
        store.save(listOf("b", "a"))
        store.use(ChatOrder.scope("work"))
        assertTrue("another view has its own order", store.order.value.isEmpty())
        store.save(listOf("z"))
        val again = ChatOrderStore(prefs)
        again.use("all")
        assertEquals(listOf("b", "a"), again.order.value)
        again.use("work")
        assertEquals(listOf("z"), again.order.value)
    }

    // ------------------------------------------------------------------ 2. the queue

    private fun prefs(busy: String) = json.decodeFromString(
        Preferences.serializer(),
        """{"theme":"system","locale":"ar","text_scale":1,"link_target":"in_app","busy_input_mode":"$busy","streaming":true,"compact":false,
            "show_reasoning":true,"show_tool_calls":true,"show_cost":false,"inline_diffs":false,"sound_on_complete":false,
            "notify_on_complete":false,"notify_on_approval":true,
            "voice":{"input_mode":"device","dictation_language":"auto","output_mode":"device","auto_speak":false},"reasoning_effort":null}""",
    )

    @Test fun `only wait-in-line holds a message back, and only while something is going`() {
        assertTrue(MessageQueueRules.holdsBack(prefs("queue"), busy = true))
        assertTrue("unknown preferences wait in line, as the web", MessageQueueRules.holdsBack(null, busy = true))
        assertFalse(MessageQueueRules.holdsBack(prefs("queue"), busy = false))
        assertFalse(MessageQueueRules.holdsBack(prefs("next"), busy = true))
        assertFalse(MessageQueueRules.holdsBack(prefs("interrupt"), busy = true))
        assertEquals(hub.core.client.model.RunCreate.When.NEXT, HubDisplay.busyWhen(prefs("next")))
    }

    @Test fun `the queue drains one at a time once nothing runs and the last one was heard`() {
        val one = MessageQueueRules.queued(Outgoing("first"), null)
        val two = MessageQueueRules.queued(Outgoing("second"), "M1")
        assertTrue(one.key != two.key)
        assertEquals("M1", two.replyTo)
        val q = listOf(one, two)
        assertTrue(MessageQueueRules.shouldDrain(running = false, sending = false, holding = false, queue = q))
        assertFalse(MessageQueueRules.shouldDrain(running = true, sending = false, holding = false, queue = q))
        assertFalse(MessageQueueRules.shouldDrain(running = false, sending = true, holding = false, queue = q))
        assertFalse(MessageQueueRules.shouldDrain(running = false, sending = false, holding = true, queue = q))
        assertFalse(MessageQueueRules.shouldDrain(running = false, sending = false, holding = false, queue = emptyList()))
        assertEquals("x".repeat(80) + "…", MessageQueueRules.preview(listOf(ContentBlock(type = ContentBlock.Type.TEXT, text = "x".repeat(100)))))
        assertEquals("a b", MessageQueueRules.preview(listOf(ContentBlock(type = ContentBlock.Type.TEXT, text = " a \n b "))))
        assertEquals("photo.jpg", MessageQueueRules.preview(listOf(ContentBlock(type = ContentBlock.Type.IMAGE, name = "photo.jpg"))))
    }

    // ------------------------------------------------------------------ 3. /rt/jobs

    private fun envelope(event: String, payload: String, namespace: String = JOBS_NAMESPACE) =
        Envelope(event, namespace, "work", 1, at, json.parseToJsonElement(payload).jsonObject)

    @Test fun `a job's event wakes whoever follows it, and agent updated bumps the agents page`() = runTest {
        val before = JobsFeed.agentsRevision.value
        val waiting = async { JobsFeed.wait("J1", 60_000) }
        yield()
        JobsFeed.receive(envelope("job.progress", """{"job":{"id":"J2","profile":"work","owner_id":"u1","created_at":"$at","updated_at":"$at",
            "kind":"install","status":"running","progress":{"percent":10}}}"""))
        assertFalse("another job does not wake it", waiting.isCompleted)
        JobsFeed.receive(envelope("job.completed", """{"job":{"id":"J1","profile":"work","owner_id":"u1","created_at":"$at","updated_at":"$at",
            "kind":"install","status":"succeeded","progress":{"percent":100}}}"""))
        yield()
        assertTrue("its own event woke it, before any timeout", waiting.isCompleted)
        assertEquals(hub.core.client.model.JobStatus.SUCCEEDED, JobsFeed.jobs.value["J1"]!!.status)
        JobsFeed.receive(envelope("agent.updated", """{"agent":{}}"""))
        assertEquals(before + 1, JobsFeed.agentsRevision.value)
        JobsFeed.receive(envelope("agent.updated", "{}", namespace = "/rt/sessions"))
        assertEquals("only /rt/jobs counts", before + 1, JobsFeed.agentsRevision.value)
    }

    @Test fun `with no event the wait ends at its timeout (the poll is the fallback)`() = runTest {
        JobsFeed.wait("never", 10)
    }

    // ------------------------------------------------------------------ 4. timeline

    @Test fun `idle stretches longer than 3 s are folded to 0,6 s, and the bars keep their proportions`() {
        val axis = TrajectoryRules.axis(listOf(0L to 1_000L, 1_500L to 2_000L, 60_000L to 61_000L))
        // Work: 0–2 s, then 60–61 s; drawn as 2 s + 0.6 s + 1 s = 3.6 s.
        assertEquals(0f, axis.at(0), 0.001f)
        assertEquals(2_000f / 3_600, axis.at(2_000), 0.001f)
        assertEquals(2_600f / 3_600, axis.at(60_000), 0.001f)
        assertEquals(1f, axis.at(61_000), 0.001f)
        assertEquals("an instant in a folded gap sits at the next block", 2_600f / 3_600, axis.at(30_000), 0.001f)
        assertEquals(1, axis.folds.size)
        assertEquals(2_300f / 3_600, axis.folds.single(), 0.001f)
        assertEquals(0f, TrajectoryRules.axis(emptyList()).at(5))
    }

    @Test fun `calls that ran at the same time get their own rows`() {
        assertEquals(listOf(0, 1, 0, 2), TrajectoryRules.packRows(listOf(0L to 10L, 5L to 15L, 10L to 20L, 12L to 13L)))
        assertEquals(emptyList<Int>(), TrajectoryRules.packRows(emptyList()))
    }

    @Test fun `the session log keeps the name the hub gives it`() {
        assertEquals("trajectory-S1.json", TrajectoryLog.fileName("S1", "attachment; filename=\"trajectory-S1.json\""))
        assertEquals("session-S1-log.json", TrajectoryLog.fileName("S1", null))
        assertEquals("a_b.json", TrajectoryLog.fileName("S1", "attachment; filename=a/b.json"))
    }

    // ------------------------------------------------------------------ 5. / commands and skills

    @Test fun `the commands offered follow the agent's capabilities`() {
        val bare = SlashCommands.available(emptyList()).map { it.name }
        assertEquals(listOf("new", "fork", "archive", "model", "clear-screen"), bare)
        val all = SlashCommands.available(listOf(AgentCapability.COMPRESS, AgentCapability.STEER, AgentCapability.SKILL_COMMANDS, AgentCapability.GOALS)).map { it.name }
        assertEquals(listOf("compress", "steer", "skill", "goal", "new", "fork", "archive", "model", "clear-screen"), all)
    }

    @Test fun `what is typed after slash is read as a menu query, a skill query, or a finished command`() {
        assertEquals("", SlashCommands.query("/"))
        assertEquals("com", SlashCommands.query("/com"))
        assertNull(SlashCommands.query("/compress now"))
        assertNull(SlashCommands.query("hello /x"))
        assertEquals("", SlashCommands.skillQuery("/skill "))
        assertEquals("rev", SlashCommands.skillQuery("/skill rev"))
        assertNull(SlashCommands.skillQuery("/skill review the pr"))
        assertNull(SlashCommands.skillQuery("/skills"))
        val offered = SlashCommands.available(listOf(AgentCapability.COMPRESS, AgentCapability.SKILL_COMMANDS))
        assertEquals("compress" to "the API", SlashCommands.parse(" /compress the API ", offered)!!.let { it.first.name to it.second })
        assertEquals("", SlashCommands.parse("/new", offered)!!.second)
        assertNull("not offered", SlashCommands.parse("/steer go", offered))
        assertNull("an unknown command is a message", SlashCommands.parse("/etc/hosts", offered))
        assertEquals(listOf("compress", "clear-screen"), SlashCommands.filter(SlashCommands.all.filter { it.name.startsWith("c") }, "c", { it.name }).map { it.name })
        assertEquals("/compress ", SlashCommands.picked(offered.first { it.name == "compress" }))
        assertNull("a command without words runs at once", SlashCommands.picked(offered.first { it.name == "fork" }))
    }

    @Test fun `after slash skill the agent's enabled skills are offered, key matches first, a tap writes the key`() {
        val categories = listOf(
            json.decodeFromString(
                SkillCategory.serializer(),
                """{"key":"dev","name":"Dev","skills":[
                    {"key":"code-review","name":"Code review","enabled":true,"pinned":false,"source":"builtin","use_count":0,"description":"Reviews a diff"},
                    {"key":"off","name":"Off","enabled":false,"pinned":false,"source":"builtin","use_count":0},
                    {"key":"docs","name":"Docs","enabled":true,"pinned":false,"source":"builtin","use_count":0,"description":"Writes a review of docs"}]}""",
            ),
        )
        val skills = SlashCommands.skills(categories)
        assertEquals(listOf("code-review", "docs"), skills.map { it.key })
        assertEquals(listOf("docs", "code-review"), SlashCommands.filterSkills(skills, "d").map { it.key })
        assertEquals(listOf("code-review", "docs"), SlashCommands.filterSkills(skills, "review").map { it.key })
        assertEquals("/skill code-review ", SlashCommands.pickSkill(skills.first()))
    }

    @Test fun `slash model finds a model by id, label or the id's last part, and clear-screen hides what was there`() {
        val options = listOf(ChatControls.ModelOption("openrouter/anthropic/claude-x", "Claude X", "openrouter"), ChatControls.ModelOption("gpt-y", "GPT Y", "openai"))
        assertEquals("gpt-y", SlashCommands.model("GPT Y", options))
        assertEquals("openrouter/anthropic/claude-x", SlashCommands.model("claude-x", options))
        assertNull(SlashCommands.model("nothing", options))
        assertNull(SlashCommands.model("  ", options))
        assertEquals(listOf(4, 5), SlashCommands.afterClear(listOf(1, 2, 3, 4, 5), { it }, 3))
        assertEquals(listOf(1, 2), SlashCommands.afterClear(listOf(1, 2), { it }, null))
    }

    // ------------------------------------------------------------------ 6. a step's model

    @Test fun `an agent step's model is a catalogue key or the agent's own, and the button names it`() {
        var d = WorkflowDraftRules.add(WorkflowDraft(name = "W"), WorkflowNode.Kind.AGENT, "Research", "A1")
        val node = d.nodes.single()
        val chosen = WorkflowDraftRules.withModel(node.copy(provider = "old"), "openai/gpt-y")
        assertEquals("openai/gpt-y", chosen.model)
        assertNull("the provider goes with the key", chosen.provider)
        assertNull(WorkflowDraftRules.withModel(chosen, null).model)
        assertNull(WorkflowDraftRules.withModel(chosen, " ").model)
        val options = listOf(ChatControls.ModelOption("openai/gpt-y", "GPT Y", "openai"))
        assertEquals("GPT Y", WorkflowDraftRules.modelLabel("openai/gpt-y", options))
        assertEquals("unknown-x", WorkflowDraftRules.modelLabel("vendor/unknown-x", options))
        assertNull(WorkflowDraftRules.modelLabel(null, options))
        d = WorkflowDraftRules.update(d, node.id) { WorkflowDraftRules.withModel(it, "openai/gpt-y") }
        assertEquals("openai/gpt-y", WorkflowDraftRules.toWrite(d).nodes!!.single().model)
    }
}

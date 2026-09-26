package hub.core.android.parity

import hub.core.android.data.HubApis
import hub.core.android.data.HubError
import hub.core.android.ui.screens.TaskFacts
import hub.core.android.ui.screens.TaskOps
import hub.core.android.ui.screens.TaskRules
import hub.core.client.infrastructure.ExplicitNulls
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Assignee
import hub.core.client.model.RunStatus
import hub.core.client.model.TaskAllOfExternal
import hub.core.client.model.TaskDependencyState
import hub.core.client.model.TaskPatch
import hub.core.client.model.TaskPriority
import hub.core.client.model.TaskStatus
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A task on its own (batch 2, Tasks I): the moves its detail offers, who may assign, stop or take
 * it back, what the create, edit and assign forms send — and the calls, against a scripted hub.
 * iOS's TaskDetailTests is the twin.
 */
class TaskDetailTest {
    private val json = Serializer.kotlinxSerializationJson
    private val id = "01J8QK3ZR2W7M5N4P6T8V9X0TK"
    private val agent = "01J8QK3ZR2W7M5N4P6T8V9X0AC"

    private fun taskJson(status: String = "todo", extra: String = "") = """{"id":"$id","profile":"work","owner_id":"01J8QK3ZR2W7M5N4P6T8V9X0HM",
        "created_at":"2026-09-20T09:00:00Z","updated_at":"2026-09-21T10:00:00Z","project_id":"01J8QK3ZR2W7M5N4P6T8V9X0PJ",
        "title":"Settings page","description":"**Phone** first","status":"$status","priority":"high","tags":[],
        "assignee":{"kind":"agent","id":"$agent","name":"Claude Code"},"auto_start":false,"position":"a0",
        "blocked_reason":null,"status_reason":null,"subtask_counts":{"total":0,"done":0},"depends_on":["01J8QK3ZR2W7M5N4P6T8V9X0D1","01J8QK3ZR2W7M5N4P6T8V9X0D2"],
        "waiting_on":[{"id":"01J8QK3ZR2W7M5N4P6T8V9X0D1","title":"Design","status":"todo"}],"stuck_since":null,"worktree":null,
        "session_id":"01J8QK3ZR2W7M5N4P6T8V9X0YE","last_run":{"id":null,"status":null,"finished_at":null},"attempt_count":0,
        "latest_summary":null,"due_at":null,"started_at":null,"completed_at":null,"archived_at":null,"attachment_ids":[]$extra}"""

    private val run = """{"id":"01J8QK3ZR2W7M5N4P6T8V9X0RS","profile":"work","owner_id":"01J8QK3ZR2W7M5N4P6T8V9X0HM",
        "created_at":"2026-09-21T09:30:00Z","updated_at":"2026-09-21T10:00:00Z","session_id":"01J8QK3ZR2W7M5N4P6T8V9X0YE",
        "room_id":null,"seat_id":null,"job_id":"01J8QK3ZR2W7M5N4P6T8V9X0JT","status":"running","queue_position":null,
        "trigger":{"kind":"task","id":"$id"},"input_message_id":null,"output_message_id":null,"model":null,"provider":null,
        "reasoning_effort":null,"interrupted":false,"error":null,"usage":null,"started_at":"2026-09-21T09:30:01Z","finished_at":null}"""

    // ------------------------------------------------------------------ the rules

    @Test fun `the detail offers only the moves the hub accepts`() {
        assertEquals(listOf(TaskStatus.READY, TaskStatus.SCHEDULED, TaskStatus.BLOCKED), TaskRules.moves(TaskStatus.TODO).map { it.to })
        assertTrue(TaskRules.moves(TaskStatus.TODO).last().transition.requiresReason)
        assertEquals(listOf(TaskStatus.SCHEDULED, TaskStatus.BLOCKED, TaskStatus.REVIEW, TaskStatus.DONE), TaskRules.moves(TaskStatus.RUNNING).map { it.to })
        // Reopening a review is one move, not two.
        assertEquals(listOf(TaskStatus.TODO, TaskStatus.DONE), TaskRules.moves(TaskStatus.REVIEW).map { it.to })
        // Done goes only to the archive, and asks first.
        val done = TaskRules.moves(TaskStatus.DONE)
        assertEquals(listOf(TaskStatus.ARCHIVED), done.map { it.to })
        assertTrue(done.single().transition.confirm)
        assertEquals(emptyList<Any>(), TaskRules.moves(TaskStatus.ARCHIVED))
    }

    @Test fun `stop, assign and unassign follow the status and the card's owner`() {
        val claude = Assignee(Assignee.Kind.AGENT, agent, "Claude Code")
        assertTrue(TaskRules.canStop(TaskStatus.RUNNING))
        assertFalse(TaskRules.canStop(TaskStatus.READY))
        assertTrue(TaskRules.canAssign(TaskStatus.TODO, hermes = false))
        assertFalse(TaskRules.canAssign(TaskStatus.DONE, hermes = false))
        assertFalse("Hermes dispatches its own cards", TaskRules.canAssign(TaskStatus.TODO, hermes = true))
        assertTrue(TaskRules.canUnassign(claude, hermes = false))
        assertFalse(TaskRules.canUnassign(Assignee(Assignee.Kind.USER, "u", "Sara"), hermes = false))
        assertFalse(TaskRules.canUnassign(null, hermes = false))
        assertTrue(TaskRules.fromHermes(TaskAllOfExternal(TaskAllOfExternal.Source.HERMES, "t1")))
        assertFalse(TaskRules.fromHermes(null))
    }

    @Test fun `what it waits for is said only before it runs`() {
        val waiting = listOf(TaskDependencyState("d1", "Design", TaskStatus.TODO))
        assertEquals(listOf("d1"), TaskRules.waiting(TaskStatus.READY, waiting).map { it.id })
        assertEquals(emptyList<Any>(), TaskRules.waiting(TaskStatus.RUNNING, waiting))
        assertEquals(2, TaskRules.doneDependencies(listOf("d1", "d2", "d3"), waiting))
        assertEquals(0, TaskRules.doneDependencies(emptyList(), null))
    }

    @Test fun `an edit sends only what changed`() {
        val original = TaskRules.editValues("Plan", "Old", TaskPriority.NORMAL, "P1")
        assertNull(TaskRules.patch(original, original))
        val patch = TaskRules.patch(original, original + mapOf("title" to "  Plan the launch ", "priority" to "urgent"))!!
        assertEquals("Plan the launch", patch.title)
        assertEquals(TaskPriority.URGENT, patch.priority)
        assertNull(patch.description)
        assertNull(patch.projectId)
        assertTrue("an untouched description stays out", patch.sendNull.isEmpty())
        // A cleared description is sent as `null` (§114); another project by its id.
        val moved = TaskRules.patch(original, original + mapOf("description" to "", "project" to "P2"))!!
        assertNull(moved.description)
        assertEquals(setOf(TaskPatch.Clearable.DESCRIPTION), moved.sendNull)
        assertEquals(
            """{"project_id":"P2","description":null}""",
            ExplicitNulls.encodeToString(Serializer.kotlinxSerializationJson, moved),
        )
        assertEquals("P2", moved.projectId)
        // A title emptied is not sent (the form refuses it first).
        assertNull(TaskRules.patch(original, original + mapOf("title" to "   ")))
    }

    @Test fun `a new task lands in the profile's list and starts only when asked`() {
        val (plain, noStart) = TaskRules.create(
            mapOf("title" to " Write the notes ", "description" to "", "project" to TaskRules.NONE, "priority" to "high", "agent" to TaskRules.NONE, "start" to "true"),
        )
        assertEquals("Write the notes", plain.title)
        assertNull(plain.projectId)
        assertNull(plain.description)
        assertEquals(TaskPriority.HIGH, plain.priority)
        assertNull(plain.assigneeAgentId)
        assertNull("nobody to start it", noStart)

        val (started, start) = TaskRules.create(mapOf("title" to "Ship", "project" to "P1", "agent" to "A1", "start" to "true", "description" to "**Now**"))
        assertEquals("P1", started.projectId)
        assertEquals("A1", started.assigneeAgentId)
        assertEquals("**Now**", started.description)
        assertEquals("A1", start)

        val (assigned, later) = TaskRules.create(mapOf("title" to "Later", "agent" to "A1", "start" to "false"))
        assertEquals("A1", assigned.assigneeAgentId)
        assertNull(later)
    }

    @Test fun `an assign carries the agent, its words and whether to start`() {
        assertNull(TaskRules.assign(mapOf("agent" to "")))
        val now = TaskRules.assign(mapOf("agent" to "A1", "instructions" to "  Begin with the account tab. ", "start" to "true"))!!
        assertEquals("A1", now.agentId)
        assertEquals("Begin with the account tab.", now.instructions)
        assertEquals(true, now.start)
        val later = TaskRules.assign(mapOf("agent" to "A1", "instructions" to " ", "start" to "false"))!!
        assertNull(later.instructions)
        assertEquals(false, later.start)
    }

    @Test fun `a client ULID looks like the contract's`() {
        val made = TaskRules.ulid()
        assertTrue(made, Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}$").matches(made))
        assertTrue(TaskRules.ulid(1_000) < TaskRules.ulid(2_000_000_000_000))
    }

    // ------------------------------------------------------------------ the calls, against a scripted hub

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()

    private fun ok(body: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/tasks") && request.method == "POST" -> ok(taskJson("triage"), 201)
                    path.endsWith("/tasks/$id/assign") && request.method == "POST" ->
                        ok("""{"job_id":"01J8QK3ZR2W7M5N4P6T8V9X0JT","run_id":"01J8QK3ZR2W7M5N4P6T8V9X0RS","session_id":"01J8QK3ZR2W7M5N4P6T8V9X0YE","task_id":"$id"}""", 202)
                    path.endsWith("/tasks/$id/assign") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path.endsWith("/tasks/$id/stop") -> MockResponse().setResponseCode(204)
                    path.endsWith("/tasks/$id/move") ->
                        if (request.body.peek().readUtf8().contains("\"done\"")) ok("""{"error":"not a move from todo","code":"state_invalid"}""", 409)
                        else ok(taskJson("blocked"))
                    path.endsWith("/tasks/$id") && request.method == "GET" ->
                        ok(taskJson("running", ""","subtasks":[],"comments":[],"runs":[$run]"""))
                    path.endsWith("/tasks/$id") && request.method == "PATCH" -> ok(taskJson())
                    path.endsWith("/tasks/$id") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun ops() = TaskOps { HubApis(server.url("/").toString().trimEnd('/'), OkHttpClient()) }

    @Test fun `a new task is made once, in the selector's profile, then started`() = runTest {
        val key = TaskRules.ulid()
        val made = ops().create("work", mapOf("title" to "Settings page", "project" to TaskRules.NONE, "agent" to agent, "start" to "true"), key)
        assertEquals(id, made.getOrThrow().id)
        val create = requests[0]
        assertEquals("POST", create.method)
        assertEquals("work", create.getHeader("X-Hub-Profile"))
        assertEquals(key, create.getHeader("Idempotency-Key"))
        val body = create.body.readUtf8()
        assertTrue(body, body.contains("\"title\":\"Settings page\"") && body.contains("\"assignee_agent_id\":\"$agent\""))
        assertFalse(body, body.contains("project_id"))
        val assign = requests[1]
        assertTrue(assign.requestUrl!!.encodedPath.endsWith("/tasks/$id/assign"))
        assertEquals("work", assign.getHeader("X-Hub-Profile"))
        assertEquals("""{"agent_id":"$agent","start":true}""", assign.body.readUtf8())
    }

    @Test fun `a new task nobody takes is only made`() = runTest {
        ops().create("home", mapOf("title" to "Notes", "agent" to TaskRules.NONE, "start" to "true"), TaskRules.ulid()).getOrThrow()
        assertEquals(1, requests.size)
        assertEquals("home", requests.single().getHeader("X-Hub-Profile"))
    }

    @Test fun `the detail reads the task with its run, and each action goes to the task's profile`() = runTest {
        val detail = ops().detail("work", id).getOrThrow()
        assertEquals(RunStatus.RUNNING, detail.runs.single().status)
        val facts = TaskFacts.of(detail)
        assertEquals("**Phone** first", facts.description)
        assertEquals(1, TaskRules.doneDependencies(facts.dependsOn, facts.waitingOn))

        assertTrue(ops().move("work", id, TaskStatus.BLOCKED, "  waiting for a key ").isSuccess)
        assertEquals("""{"status":"blocked","reason":"waiting for a key"}""", requests.last().body.readUtf8())
        assertTrue(ops().stop("work", id).isSuccess)
        assertEquals("POST", requests.last().method)
        assertTrue(ops().unassign("work", id).isSuccess)
        assertEquals("DELETE", requests.last().method)
        assertTrue(ops().update("work", id, TaskRules.patch(mapOf("title" to "a"), mapOf("title" to "b"))!!).isSuccess)
        assertEquals("""{"title":"b"}""", requests.last().body.readUtf8())
        assertTrue(ops().delete("work", id).isSuccess)
        assertTrue(requests.last().requestUrl!!.encodedPath.endsWith("/tasks/$id"))
        assertTrue(requests.all { it.getHeader("X-Hub-Profile") == "work" })
    }

    @Test fun `a move the hub refuses comes back in its words`() = runTest {
        val refused = ops().move("work", id, TaskStatus.DONE).exceptionOrNull() as HubError
        assertEquals(409, refused.status)
        assertEquals("state_invalid", refused.code)
    }
}

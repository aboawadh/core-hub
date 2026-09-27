package hub.core.android.parity

import hub.core.android.data.HubApis
import hub.core.android.ui.components.FormField
import hub.core.android.ui.components.FormKind
import hub.core.android.ui.components.FormProblem
import hub.core.android.ui.components.FormRules
import hub.core.android.ui.screens.BulkRules
import hub.core.android.ui.screens.CheckLines
import hub.core.android.ui.screens.ProjectRules
import hub.core.android.ui.screens.SubtaskRules
import hub.core.android.ui.screens.TaskOps
import hub.core.android.ui.screens.TaskRules
import hub.core.client.infrastructure.ExplicitNulls
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.BulkResult
import hub.core.client.model.BulkResultResultsInner
import hub.core.client.model.ProjectStatus
import hub.core.client.model.ProjectWrite
import hub.core.client.model.Subtask
import hub.core.client.model.SubtaskWrite
import hub.core.client.model.Task
import hub.core.client.model.TaskBulkUpdatePatch
import hub.core.client.model.TaskCheckItem
import hub.core.client.model.TaskPatch
import hub.core.client.model.TaskPriority
import hub.core.client.model.TaskStatus
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
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
 * Tasks II (batch 5): the due date and the shared date field, the checklist's ticks and drag, the
 * definition of done and constraints, the board's bulk edits by profile, a project's create and
 * edit — and the calls, against a scripted hub. iOS's TasksTwoTests is the twin.
 */
class TasksTwoTest {
    private val json = Serializer.kotlinxSerializationJson
    private val utc = ZoneId.of("UTC")
    private val at = OffsetDateTime.of(2026, 10, 1, 9, 30, 0, 0, ZoneOffset.UTC)
    private val id = "01J8QK3ZR2W7M5N4P6T8V9X0TK"

    private fun line(id: String, index: Int, status: Subtask.Status = Subtask.Status.TODO) =
        Subtask(id = id, taskId = "T", index = index, title = "line $id", status = status, updatedAt = at)

    private fun task(id: String, status: String, profile: String): Task = json.decodeFromString(
        Task.serializer(),
        """{"id":"$id","profile":"$profile","owner_id":"me","created_at":"2026-09-20T09:00:00Z","updated_at":"2026-09-20T09:00:00Z",
        "project_id":"P","title":"t$id","description":null,"status":"$status","priority":"normal","tags":[],"assignee":null,"auto_start":false,
        "position":"a0","blocked_reason":null,"status_reason":null,"subtask_counts":{"total":0,"done":0},"depends_on":[],"worktree":null,
        "session_id":null,"last_run":{"id":null,"status":null,"finished_at":null},"attempt_count":0,"latest_summary":null,"due_at":null,
        "started_at":null,"completed_at":null,"archived_at":null,"attachment_ids":[]}""",
    )

    private fun body(value: TaskPatch): String = ExplicitNulls.encodeToString<TaskPatch>(json, value)

    private fun body(value: ProjectWrite): String = ExplicitNulls.encodeToString<ProjectWrite>(json, value)

    // ------------------------------------------------------------------ the date field and the due date

    @Test fun `a date field reads and writes to the minute and refuses what is not a date`() {
        val field = FormField("due", "Due", FormKind.Date)
        assertNull("no date is a value when the field is not required", FormRules.problem(field, ""))
        assertEquals(FormProblem.Required, FormRules.problem(field.copy(required = true), ""))
        assertNull(FormRules.problem(field, "2026-10-01T09:30"))
        assertEquals(FormProblem.NotADate, FormRules.problem(field, "tomorrow"))
        assertEquals(at, FormRules.date("2026-10-01T09:30", utc)?.withOffsetSameInstant(ZoneOffset.UTC))
        assertEquals("2026-10-01T09:30", FormRules.dateText(at, utc))
        assertEquals("", FormRules.dateText(null, utc))
        assertTrue(FormRules.tomorrowAtNine().endsWith("T09:00"))
    }

    @Test fun `the due date is set, changed, or cleared as null`() {
        val original = TaskRules.editValues("Plan", null, TaskPriority.NORMAL, "P1", null)
        assertEquals("", original["due"])
        val set = TaskRules.patch(original, original + ("due" to FormRules.dateText(at)))!!
        assertEquals(at.toInstant(), set.dueAt?.toInstant())
        assertTrue(set.sendNull.isEmpty())

        val dated = TaskRules.editValues("Plan", null, TaskPriority.NORMAL, "P1", at)
        assertNull("an untouched date is not sent", TaskRules.patch(dated, dated))
        val cleared = TaskRules.patch(dated, dated + ("due" to ""))!!
        assertEquals(setOf(TaskPatch.Clearable.DUE_AT), cleared.sendNull)
        assertEquals("""{"due_at":null}""", body(cleared))
    }

    // ------------------------------------------------------------------ the checklist

    @Test fun `a tick flips a line and a new line needs words`() {
        assertEquals(SubtaskWrite.Status.DONE, SubtaskRules.toggled(Subtask.Status.TODO))
        assertEquals(SubtaskWrite.Status.DONE, SubtaskRules.toggled(Subtask.Status.IN_PROGRESS))
        assertEquals(SubtaskWrite.Status.TODO, SubtaskRules.toggled(Subtask.Status.DONE))
        assertNull(SubtaskRules.title("   "))
        assertEquals("Privacy tab", SubtaskRules.title("  Privacy tab "))
        assertEquals(SubtaskRules.TITLE_MAX, SubtaskRules.title("a".repeat(400))!!.length)
        assertEquals(1, SubtaskRules.doneCount(listOf(line("a", 0, Subtask.Status.DONE), line("b", 1))))
    }

    @Test fun `a drag tells the hub only the lines whose place changed`() {
        val lines = listOf(line("a", 0), line("b", 1), line("c", 2), line("d", 3))
        val order = SubtaskRules.order(lines.map { it.id }, 3, 0)
        assertEquals(listOf("d", "a", "b", "c"), order)
        assertEquals(listOf("d" to 0, "a" to 1, "b" to 2, "c" to 3), SubtaskRules.reindex(lines, order))
        val swap = SubtaskRules.order(lines.map { it.id }, 0, 1)
        assertEquals(listOf("b", "a", "c", "d"), swap)
        assertEquals(listOf("b" to 0, "a" to 1), SubtaskRules.reindex(lines, swap))
        assertTrue(SubtaskRules.reindex(lines, lines.map { it.id }).isEmpty())
        // Where the finger lets go: a row and a half down is two rows down; never past the ends.
        assertEquals(2, SubtaskRules.landing(0, 150f, 100f, 4))
        assertEquals(0, SubtaskRules.landing(1, -400f, 100f, 4))
        assertEquals(3, SubtaskRules.landing(2, 900f, 100f, 4))
        assertEquals(1, SubtaskRules.landing(1, 30f, 100f, 4))
    }

    // ------------------------------------------------------------------ definition of done and constraints (§104)

    @Test fun `the lists are ticked only in review and sent whole`() {
        assertTrue(CheckLines.canTick(TaskStatus.REVIEW))
        assertFalse(CheckLines.canTick(TaskStatus.READY))
        assertNull(CheckLines.adding(emptyList(), "  "))
        val one = CheckLines.adding(emptyList(), " Tests pass ")!!
        assertEquals(listOf(TaskCheckItem("Tests pass", false)), one)
        assertNull("the hub keeps at most thirty lines", CheckLines.adding(List(CheckLines.LINES_MAX) { TaskCheckItem("x", false) }, "one more"))
        val two = CheckLines.ticking(one + TaskCheckItem("No new deps", false), 1, true)
        assertEquals(listOf(false, true), two.map { it.checked })
        assertEquals(listOf("No new deps"), CheckLines.removing(two, 0).map { it.text })
        assertEquals(
            """{"definition_of_done":[{"text":"Tests pass","checked":false},{"text":"No new deps","checked":true}]}""",
            body(CheckLines.patch(CheckLines.Kind.DONE, two)),
        )
        assertTrue(body(CheckLines.patch(CheckLines.Kind.CONSTRAINTS, two)).startsWith("""{"constraints":"""))
    }

    // ------------------------------------------------------------------ bulk (§103)

    @Test fun `a bulk edit goes once per profile and counts the refusals`() {
        val tasks = listOf(task("1", "done", "work"), task("2", "todo", "home"), task("3", "done", "work"))
        assertEquals(listOf("work" to listOf("1", "3"), "home" to listOf("2")), BulkRules.calls(tasks))
        assertEquals(listOf(100, 50), BulkRules.calls(List(150) { task("$it", "todo", "work") }).map { it.second.size })
        assertEquals("1,3", BulkRules.idsParam(listOf("1", "3")))
        val answers = listOf(
            BulkResult(listOf(BulkResultResultsInner("1", true), BulkResultResultsInner("3", false))),
            BulkResult(listOf(BulkResultResultsInner("2", true))),
        )
        assertEquals(2 to 1, BulkRules.tally(answers))
        assertEquals(setOf("3"), BulkRules.refused(answers))
        assertFalse("only finished work is archived", BulkRules.archivable(tasks))
        assertTrue(BulkRules.archivable(listOf(tasks[0], tasks[2])))
        assertFalse(BulkRules.archivable(emptyList()))
    }

    // ------------------------------------------------------------------ projects

    @Test fun `a project is made with what was given and edited with only what changed`() {
        val made = ProjectRules.create(mapOf("name" to "  Launch ", "repository" to "", "branch" to ""))
        assertEquals("""{"name":"Launch"}""", body(made))
        val original = mapOf("name" to "Launch", "status" to "active", "repository" to "/data/workspaces/work/site", "branch" to "main")
        assertNull(ProjectRules.patch(original, original))
        val renamed = ProjectRules.patch(original, original + mapOf("name" to "Launch v2", "status" to "archived"))!!
        assertEquals("Launch v2", renamed.name)
        assertEquals(ProjectStatus.ARCHIVED, renamed.status)
        // An emptied repository is sent as null (§114); an emptied branch is left as it was.
        assertEquals("""{"working_dir":null}""", body(ProjectRules.patch(original, original + mapOf("repository" to " ", "branch" to ""))!!))
        assertEquals(ProjectStatus.ARCHIVED, ProjectRules.toggledArchive(ProjectStatus.ACTIVE))
        assertEquals(ProjectStatus.ACTIVE, ProjectRules.toggledArchive(ProjectStatus.ARCHIVED))
        assertEquals("active", ProjectRules.values(null)["status"])
    }

    // ------------------------------------------------------------------ the calls, against a scripted hub

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()

    private fun ok(body: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private val subtask = """{"id":"S1","task_id":"$id","index":0,"title":"Privacy tab","status":"todo","note":null,"blocked_reason":null,"completed_at":null,"updated_at":"2026-09-21T12:35:00Z"}"""
    private val project = """{"id":"P1","profile":"work","owner_id":"me","created_at":"2026-09-20T09:00:00Z","updated_at":"2026-09-20T09:00:00Z",
        "name":"Launch","description":null,"status":"active","color":null,"repo_url":null,"working_dir":null,"default_branch":"main",
        "default_agent_id":null,"report_room_id":null,"auto_dispatch":false,"counts":{"total":3,"by_status":{"todo":3}}}"""

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/subtasks") -> ok(subtask, 201)
                    path.contains("/subtasks/") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path.contains("/subtasks/") -> ok(subtask)
                    path.endsWith("/comments") ->
                        ok("""{"id":"C1","task_id":"$id","author":{"kind":"user","id":"me","name":"Sara"},"content":"On it","created_at":"2026-09-21T12:35:00Z"}""", 201)
                    path.endsWith("/projects") && request.method == "GET" ->
                        ok(if (request.requestUrl!!.queryParameter("status") == "archived") """{"items":[],"next_cursor":null}""" else """{"items":[$project],"next_cursor":null}""")
                    path.endsWith("/projects") -> ok(project, 201)
                    path.endsWith("/projects/P1") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path.endsWith("/projects/P1") -> ok(project)
                    path.endsWith("/tasks") && request.method == "PATCH" -> {
                        val ids = Regex("\"(\\d)\"").findAll(request.body.peek().readUtf8()).map { it.groupValues[1] }.toList()
                        ok("""{"results":[${ids.joinToString(",") { """{"id":"$it","ok":${it != "3"},"error":null}""" }}]}""")
                    }
                    path.endsWith("/tasks") && request.method == "DELETE" -> {
                        val ids = request.requestUrl!!.queryParameter("ids").orEmpty().split(",")
                        ok("""{"results":[${ids.joinToString(",") { """{"id":"$it","ok":true,"error":null}""" }}]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun ops() = TaskOps { HubApis(server.url("/").toString().trimEnd('/'), OkHttpClient()) }

    @Test fun `checklist lines are added, ticked, moved and deleted in the task's profile`() = runTest {
        assertEquals("Privacy tab", ops().addLine("work", id, "Privacy tab").getOrThrow().title)
        assertTrue(requests.last().requestUrl!!.encodedPath.endsWith("/tasks/$id/subtasks"))
        assertEquals("""{"title":"Privacy tab"}""", requests.last().body.readUtf8())
        ops().tickLine("work", id, line("S1", 0)).getOrThrow()
        assertEquals("PATCH", requests.last().method)
        assertEquals("""{"status":"done"}""", requests.last().body.readUtf8())
        ops().reorderLines("work", id, listOf("S2" to 0, "S1" to 1)).getOrThrow()
        assertEquals(listOf("""{"index":0}""", """{"index":1}"""), requests.takeLast(2).map { it.body.readUtf8() })
        assertTrue(requests.takeLast(2)[0].requestUrl!!.encodedPath.endsWith("/subtasks/S2"))
        ops().deleteLine("work", id, "S1").getOrThrow()
        assertEquals("DELETE", requests.last().method)
        assertTrue(requests.all { it.getHeader("X-Hub-Profile") == "work" })
    }

    @Test fun `a comment is said on the task in its profile`() = runTest {
        assertEquals("On it", ops().comment("home", id, "On it").getOrThrow().content)
        assertEquals("""{"content":"On it"}""", requests.single().body.readUtf8())
        assertEquals("home", requests.single().getHeader("X-Hub-Profile"))
    }

    @Test fun `projects are listed with the archive, made, edited and deleted`() = runTest {
        val (active, archived) = ops().allProjects("work").getOrThrow()
        assertEquals(listOf("Launch"), active.map { it.name })
        assertTrue(archived.isEmpty())
        assertEquals("archived", requests[1].requestUrl!!.queryParameter("status"))
        ops().createProject("work", ProjectRules.create(mapOf("name" to "Launch"))).getOrThrow()
        assertEquals("POST", requests.last().method)
        ops().updateProject("work", "P1", ProjectWrite(status = ProjectStatus.ARCHIVED)).getOrThrow()
        assertEquals("""{"status":"archived"}""", requests.last().body.readUtf8())
        ops().deleteProject("work", "P1").getOrThrow()
        assertEquals("DELETE", requests.last().method)
    }

    @Test fun `a bulk edit and a bulk delete go once per profile`() = runTest {
        val tasks = listOf(task("1", "done", "work"), task("2", "todo", "home"), task("3", "done", "work"))
        val answers = ops().bulkUpdate(tasks, TaskBulkUpdatePatch(priority = TaskPriority.HIGH)).getOrThrow()
        assertEquals(2 to 1, BulkRules.tally(answers))
        assertEquals(listOf("work", "home"), requests.map { it.getHeader("X-Hub-Profile") })
        assertEquals("""{"task_ids":["1","3"],"patch":{"priority":"high"}}""", requests[0].body.readUtf8())
        requests.clear()
        ops().bulkDelete(tasks).getOrThrow()
        assertEquals(listOf("1,3", "2"), requests.map { it.requestUrl!!.queryParameter("ids") })
        assertTrue(requests.all { it.method == "DELETE" })
    }
}

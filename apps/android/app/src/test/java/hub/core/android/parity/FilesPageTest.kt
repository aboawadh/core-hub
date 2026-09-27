package hub.core.android.parity

import hub.core.android.chat.HubFile
import hub.core.android.chat.HubFileFetcher
import hub.core.android.chat.Progress
import hub.core.android.data.HubError
import hub.core.android.ui.components.changedElsewhere
import hub.core.android.ui.screens.FilesApis
import hub.core.android.ui.screens.FilesOps
import hub.core.android.ui.screens.FilesRules
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.WorkspaceFileEntry
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Apps batches 11 and 13: Settings → Files — the rules the page follows and the calls it makes,
 * against a scripted hub. iOS's FilesRulesTests is the twin.
 */
class FilesPageTest {
    private val json = Serializer.kotlinxSerializationJson

    private fun entry(name: String, kind: String = "file", size: Long? = 10, modified: String? = "2026-09-25T08:00:00Z", path: String = name) =
        json.decodeFromString(
            WorkspaceFileEntry.serializer(),
            """{"name":"$name","path":"$path","kind":"$kind","link":false,"size_bytes":${size ?: "null"},
                "modified_at":${modified?.let { "\"$it\"" } ?: "null"},"mime":null,"editable":true}""",
        )

    // ------------------------------------------------------------------ the rules

    @Test fun `paths join, split and trail the way the hub writes them`() {
        assertEquals("a", FilesRules.join("", "a"))
        assertEquals("a/b/c", FilesRules.join("a/b", "c"))
        assertEquals("", FilesRules.parent("plan.md"))
        assertEquals("notes/2026", FilesRules.parent("notes/2026/plan.md"))
        assertEquals("plan.md", FilesRules.baseName("notes/plan.md"))
        assertEquals(listOf("notes" to "notes", "2026" to "notes/2026"), FilesRules.crumbs("notes/2026"))
        assertTrue(FilesRules.crumbs("").isEmpty())
        assertEquals("reports/2026/plan.md", FilesRules.normalise("  /./reports//2026\\plan.md/ "))
        assertEquals("notes copy.md", FilesRules.copyName("notes.md"))
        assertEquals("Makefile copy", FilesRules.copyName("Makefile"))
        assertEquals(".env copy", FilesRules.copyName(".env"))
    }

    @Test fun `a new name is one segment`() {
        assertTrue(FilesRules.validName(" plan.md "))
        assertFalse(FilesRules.validName(""))
        assertFalse(FilesRules.validName(".."))
        assertFalse(FilesRules.validName("a/b"))
        assertFalse(FilesRules.validName("a\\b"))
    }

    @Test fun `folders come first, then the chosen order, and a search narrows by name`() {
        val list = listOf(
            entry("b.txt", size = 5, modified = "2026-09-20T08:00:00Z"),
            entry("Zeta", kind = "directory", size = null, modified = "2026-09-01T08:00:00Z"),
            entry("a.md", size = 900, modified = "2026-09-26T08:00:00Z"),
            entry("alpha", kind = "directory", size = null, modified = "2026-09-27T08:00:00Z"),
            entry("c.png", size = 50, modified = null),
        )
        assertEquals(listOf("alpha", "Zeta", "a.md", "b.txt", "c.png"), FilesRules.arrange(list, "", FilesRules.Sort.NAME).map { it.name })
        assertEquals(listOf("alpha", "Zeta", "a.md", "b.txt", "c.png"), FilesRules.arrange(list, "", FilesRules.Sort.NEWEST).map { it.name })
        assertEquals(listOf("alpha", "Zeta", "a.md", "c.png", "b.txt"), FilesRules.arrange(list, "", FilesRules.Sort.LARGEST).map { it.name })
        assertEquals(listOf("alpha", "a.md"), FilesRules.arrange(list, " A", FilesRules.Sort.NAME).map { it.name }.filter { it.startsWith("a") })
        assertEquals(listOf("c.png"), FilesRules.arrange(list, "PNG", FilesRules.Sort.NAME).map { it.name })
    }

    @Test fun `sizes and times read in Latin digits`() {
        assertEquals("⁦40 B⁩", FilesRules.size(40))
        assertEquals("⁦1.5 KB⁩", FilesRules.size(1536))
        assertEquals("⁦25 MB⁩", FilesRules.size(25L * 1024 * 1024))
        val time = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("ar-u-nu-latn")).withZone(ZoneOffset.UTC)
        val detail = FilesRules.detail(entry("a.md", size = 2048, modified = "2026-09-25T08:05:00Z"), time)!!
        assertTrue(detail, detail.startsWith("⁦2 KB⁩ · 25 "))
        assertTrue(detail, detail.endsWith("2026, 08:05"))
        assertNull(FilesRules.detail(entry("x", kind = "directory", size = null, modified = null), time))
    }

    @Test fun `a prompt's words become the one step the page takes`() {
        assertEquals(FilesRules.Step.MakeFolder("notes/2026/q3"), FilesRules.promptStep(FilesRules.PromptKind.NEW_FOLDER, " 2026/q3/ ", "notes", ""))
        assertEquals(FilesRules.Step.BadName, FilesRules.promptStep(FilesRules.PromptKind.NEW_FOLDER, "  ", "notes", ""))
        assertEquals(FilesRules.Step.BadName, FilesRules.promptStep(FilesRules.PromptKind.NEW_FOLDER, "../up", "notes", ""))
        assertEquals(FilesRules.Step.WriteNew("plan.md"), FilesRules.promptStep(FilesRules.PromptKind.NEW_FILE, "plan.md", "", ""))
        assertEquals(FilesRules.Step.Move("notes/a.md", "notes/b.md"), FilesRules.promptStep(FilesRules.PromptKind.RENAME, " b.md ", "notes", "notes/a.md"))
        assertEquals(FilesRules.Step.Same, FilesRules.promptStep(FilesRules.PromptKind.RENAME, "a.md", "notes", "notes/a.md"))
        assertEquals(FilesRules.Step.BadName, FilesRules.promptStep(FilesRules.PromptKind.RENAME, "x/b.md", "notes", "notes/a.md"))
        assertEquals(FilesRules.Step.Move("notes/a.md", "archive/a.md"), FilesRules.promptStep(FilesRules.PromptKind.MOVE, "/archive/a.md", "notes", "notes/a.md"))
        assertEquals(FilesRules.Step.Same, FilesRules.promptStep(FilesRules.PromptKind.MOVE, "notes/a.md", "notes", "notes/a.md"))
        assertEquals(FilesRules.Step.Copy("notes/a.md", "notes/a copy.md"), FilesRules.promptStep(FilesRules.PromptKind.COPY, "notes/a copy.md", "notes", "notes/a.md"))
        assertEquals(FilesRules.Step.BadName, FilesRules.promptStep(FilesRules.PromptKind.COPY, "notes/a.md", "notes", "notes/a.md"))
    }

    @Test fun `a refusal is named in one line, and a name already there asks to replace`() {
        assertEquals(FilesRules.Refusal.NOT_ALLOWED, FilesRules.refusal(HubError(403, "forbidden", "No")))
        assertEquals(FilesRules.Refusal.CHANGED, FilesRules.refusal(HubError(409, "conflict", null, reason = "changed")))
        assertEquals(FilesRules.Refusal.EXISTS, FilesRules.refusal(HubError(409, "conflict", null, reason = "exists")))
        assertEquals(FilesRules.Refusal.TOO_LARGE, FilesRules.refusal(HubError(413, "payload_too_large", null)))
        assertEquals(FilesRules.Refusal.NOT_TEXT, FilesRules.refusal(HubError(415, "unsupported_media_type", null)))
        assertEquals(FilesRules.Refusal.ROOT, FilesRules.refusal(HubError(400, "validation_failed", null, reason = "root")))
        assertEquals(FilesRules.Refusal.INTO_ITSELF, FilesRules.refusal(HubError(400, "validation_failed", null, reason = "into_itself")))
        assertEquals(FilesRules.Refusal.OUTSIDE, FilesRules.refusal(HubError(400, "validation_failed", null, reason = "outside_root")))
        assertEquals(FilesRules.Refusal.GONE, FilesRules.refusal(HubError(404, "not_found", null)))
        assertEquals(FilesRules.Refusal.OTHER, FilesRules.refusal(HubError(500, "internal", null)))
        assertTrue(FilesRules.nameTaken(HubError(409, "conflict", null, reason = "exists")))
        assertFalse(FilesRules.nameTaken(HubError(409, "conflict", null, reason = "changed")))
        assertFalse(FilesRules.nameTaken(HubError(413, "payload_too_large", null)))
        assertFalse(FilesRules.nameTaken(null))
        assertTrue(FilesRules.tooLarge(26, 25))
        assertFalse(FilesRules.tooLarge(25, 25))
        assertFalse(FilesRules.tooLarge(26, null))
        assertTrue(FilesRules.markdown("Plan.MD"))
        assertFalse(FilesRules.markdown("plan.py"))
    }

    @Test fun `a file opens through the chat's opener, and a changed file is fetched again`() {
        val file = FilesRules.hubFile(entry("a.png", size = 10, path = "img/a.png"), "work")!!
        assertEquals(HubFile.Profile("work", "img/a.png", "a.png", null, 10, "2026-09-25T08:00Z"), file)
        assertNotEquals(file.cacheKey, FilesRules.hubFile(entry("a.png", size = 11, path = "img/a.png"), "work")!!.cacheKey)
        assertNotEquals(file.cacheKey, FilesRules.hubFile(entry("a.png", size = 10, path = "img/a.png"), "home")!!.cacheKey)
        assertNull(FilesRules.hubFile(entry("img", kind = "directory", size = null), "work"))
        assertNull(FilesRules.hubFile(entry("out", kind = "link", size = null), "work"))
    }

    // ------------------------------------------------------------------ the calls

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private var textEtag = "\"e1\""
    private var uploadsSeen = 0
    private fun ok(body: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private val entryJson = """{"name":"plan.md","path":"notes/plan.md","kind":"file","link":false,"size_bytes":7,
        "modified_at":"2026-09-25T08:12:00Z","mime":"text/markdown","editable":true}"""
    private val textJson get() = """{"path":"notes/plan.md","content":"# Plan\n","etag":${json.encodeToString(kotlinx.serialization.serializer<String>(), textEtag)},
        "size_bytes":7,"modified_at":"2026-09-25T08:12:00Z"}"""

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/workspace-files") && request.method == "GET" -> ok(
                        """{"profile":"work","path":"notes","entries":[$entryJson],"truncated":false,
                            "limits":{"max_upload_bytes":26214400,"max_edit_bytes":1048576,"max_archive_bytes":209715200,"max_archive_entries":20000}}""",
                    )
                    path.endsWith("/workspace-files") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path.endsWith("/workspace-files/folders") -> ok(entryJson.replace("\"file\"", "\"directory\""), 201)
                    path.endsWith("/workspace-files/move") -> ok(entryJson)
                    path.endsWith("/workspace-files/copy") -> ok(entryJson, 201)
                    path.endsWith("/workspace-files/text") && request.method == "GET" -> ok(textJson)
                    path.endsWith("/workspace-files/text") && request.method == "PUT" -> {
                        val sent = json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["etag"]
                        if (sent is JsonNull || sent?.jsonPrimitive?.content == textEtag) {
                            textEtag = "\"e2\""
                            ok(textJson)
                        } else {
                            ok("""{"error":"The file changed on disk.","code":"conflict","details":{"reason":"changed","etag":"\"e2\""}}""", 409)
                        }
                    }
                    path.endsWith("/workspace-files/upload") -> {
                        uploadsSeen++
                        if (request.requestUrl!!.queryParameter("overwrite") == "true") ok(entryJson, 201)
                        else ok("""{"error":"A file of that name is there.","code":"conflict","details":{"reason":"exists"}}""", 409)
                    }
                    path.endsWith("/workspace-files/archive") -> MockResponse().setHeader("Content-Type", "application/zip").setBody("PK-zip-bytes")
                    path.endsWith("/workspace-files/content") -> MockResponse().setHeader("Content-Type", "text/markdown").setBody("# Plan\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private val hub get() = server.url("/").toString().trimEnd('/')
    private fun ops() = FilesOps("work") { FilesApis(hub, OkHttpClient()) }
    private fun body(request: RecordedRequest) = json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test fun `a folder is read in the profile, with its entries and limits`() = runTest {
        val folder = ops().folder("notes").getOrThrow()
        assertEquals(listOf("plan.md"), folder.propertyEntries.map { it.name })
        assertEquals(26214400L, folder.limits.maxUploadBytes)
        assertEquals("notes", requests[0].requestUrl!!.queryParameter("path"))
        assertEquals("work", requests[0].getHeader("X-Hub-Profile"))
    }

    @Test fun `new folder, rename or move, copy and delete send what the contract names`() = runTest {
        ops().mkdir("notes/2026").getOrThrow()
        assertEquals("notes/2026", body(requests[0])["path"]!!.jsonPrimitive.content)
        ops().move("notes/a.md", "notes/b.md").getOrThrow()
        val moved = body(requests[1])
        assertEquals("notes/a.md", moved["from"]!!.jsonPrimitive.content)
        assertEquals("notes/b.md", moved["to"]!!.jsonPrimitive.content)
        ops().copy("notes/a.md", "archive/a.md").getOrThrow()
        assertTrue(requests[2].requestUrl!!.encodedPath.endsWith("/workspace-files/copy"))
        ops().delete("notes/old").getOrThrow()
        assertEquals("DELETE", requests[3].method)
        assertEquals("notes/old", requests[3].requestUrl!!.queryParameter("path"))
        assertTrue(requests.all { it.getHeader("X-Hub-Profile") == "work" })
    }

    @Test fun `a save sends the etag it read, a change on disk since is refused and offers Reload`() = runTest {
        val read = ops().readText("notes/plan.md").getOrThrow()
        assertEquals("\"e1\"", read.etag)
        val saved = ops().writeText("notes/plan.md", "# Plan\nmore\n", read.etag).getOrThrow()
        assertEquals("\"e2\"", saved.etag)
        assertEquals("\"e1\"", body(requests[1])["etag"]!!.jsonPrimitive.content)
        // Saved again against the first etag: the file changed since, nothing is written.
        val stale = ops().writeText("notes/plan.md", "lost", read.etag).exceptionOrNull() as HubError
        assertEquals(409, stale.status)
        assertEquals("changed", stale.reason)
        assertEquals(FilesRules.Refusal.CHANGED, FilesRules.refusal(stale))
        assertTrue("the shared editor offers Reload", changedElsewhere(stale))
        assertFalse("not a name already there", FilesRules.nameTaken(stale))
        // A new file is written with `etag: null`, sent as null.
        ops().writeText("notes/new.md", "x", null).getOrThrow()
        assertEquals(JsonNull, body(requests[3])["etag"])
    }

    @Test fun `an upload reports its progress, and a name already there is replaced only when asked`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("files-upload").toFile()
        val file = File(dir, "report.pdf").apply { writeBytes(ByteArray(300_000) { 7 }) }
        val seen = mutableListOf<Progress>()
        val refused = ops().upload("notes", file) { seen += it }.exceptionOrNull() as HubError
        assertTrue(FilesRules.nameTaken(refused))
        val first = requests.last()
        assertEquals("notes", first.requestUrl!!.queryParameter("path"))
        assertNull("no overwrite unless asked", first.requestUrl!!.queryParameter("overwrite"))
        assertTrue(first.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue(first.body.readUtf8().contains("filename=\"report.pdf\""))
        assertTrue("progress reaches the whole body", seen.last().read == seen.last().total && seen.last().read > 300_000)
        ops().upload("notes", file, overwrite = true).getOrThrow()
        assertEquals("true", requests.last().requestUrl!!.queryParameter("overwrite"))
        assertEquals(2, uploadsSeen)
        dir.deleteRecursively()
    }

    @Test fun `a folder comes as one zip named after it, the top folder after the profile`() = runTest {
        val dir = Files.createTempDirectory("files-zip").toFile()
        val zip = ops().zip("notes/2026", dir).getOrThrow()
        assertEquals("2026.zip", zip.name)
        assertEquals("PK-zip-bytes", zip.readText())
        assertEquals("notes/2026", requests[0].requestUrl!!.queryParameter("path"))
        assertEquals("work.zip", ops().zip("", dir).getOrThrow().name)
        dir.deleteRecursively()
    }

    @Test fun `a profile file is fetched through the chat's fetcher from the working folder`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("files-cache").toFile()
        val fetcher = HubFileFetcher(OkHttpClient(), root)
        val file = HubFile.Profile("work", "notes/plan.md", "plan.md", "text/markdown", 7, "2026-09-25T08:12Z")
        val local = fetcher.fetch(hub, "work", file)
        assertEquals("# Plan\n", local.readText())
        assertEquals("plan.md", local.name)
        val request = requests.single()
        assertTrue(request.requestUrl!!.encodedPath.endsWith("/workspace-files/content"))
        assertEquals("notes/plan.md", request.requestUrl!!.queryParameter("path"))
        assertEquals("work", request.getHeader("X-Hub-Profile"))
        root.deleteRecursively()
    }
}

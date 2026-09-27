package hub.core.android.parity

import hub.core.android.data.HubError
import hub.core.android.ui.screens.AdminApis
import hub.core.android.ui.screens.AdminTwoOps
import hub.core.android.ui.screens.PairingRules
import hub.core.android.ui.screens.PeopleRules
import hub.core.android.ui.screens.ProfileRules
import hub.core.android.ui.screens.PushSenderRules
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Agent
import hub.core.client.model.Job
import hub.core.client.model.ProfileImport
import hub.core.client.model.PushSender
import hub.core.client.model.User
import hub.core.client.model.UserAdminPatch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The admin pages on the phone (apps batches 12 and 14): their rules, and what is sent. */
class AdminPagesTest {
    private val json = Serializer.kotlinxSerializationJson

    private fun user(id: String, role: String, profiles: List<String> = emptyList(), name: String = "") = json.decodeFromString(
        User.serializer(),
        """{"id":"$id","username":"u$id","display_name":"$name","role":"$role","status":"active","locale":"ar",
            "avatar":{"kind":"generated","url":null,"seed":"a"},"profiles":[${profiles.joinToString(",") { "\"$it\"" }}],
            "default_profile":"default","created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z","last_login_at":null}""",
    )

    // ------------------------------------------------------------------ people

    @Test fun `a row offers only what the hub accepts`() {
        val owner = user("o", "owner")
        val admin = user("a", "admin")
        assertEquals(PeopleRules.Row.OWNER_NOTE, PeopleRules.row(owner, me = "a"))
        assertEquals(PeopleRules.Row.OWN_PASSWORD, PeopleRules.row(owner, me = "o"))
        assertEquals(PeopleRules.Row.FULL, PeopleRules.row(admin, me = "o"))
        assertFalse("nobody disables or deletes themselves", PeopleRules.canDisableOrDelete(admin, me = "a"))
        assertTrue(PeopleRules.canDisableOrDelete(admin, me = "o"))
        assertFalse(PeopleRules.canDisableOrDelete(owner, me = "a"))
    }

    @Test fun `a member reaches exactly the listed profiles, an admin every one`() {
        assertEquals(PeopleRules.Reach.EVERY, PeopleRules.reach(user("a", "admin", listOf("default"))))
        assertEquals(PeopleRules.Reach.NONE, PeopleRules.reach(user("m", "member")))
        assertEquals(PeopleRules.Reach.LISTED, PeopleRules.reach(user("m", "member", listOf("work"))))
    }

    @Test fun `making an admin a member sends the chosen profiles with the role, never a bare role`() {
        val admin = user("a", "admin", listOf("default", "work"))
        assertTrue("a member-to-be starts from nothing chosen", PeopleRules.startingChoice(admin, makeMember = true).isEmpty())
        assertEquals(listOf("default", "work"), PeopleRules.startingChoice(admin, makeMember = false))
        assertNull(PeopleRules.profilesPatch(emptyList(), makeMember = true))
        assertEquals(UserAdminPatch(role = UserAdminPatch.Role.MEMBER, profiles = listOf("work")), PeopleRules.profilesPatch(listOf("work"), makeMember = true))
        assertEquals(UserAdminPatch(profiles = listOf("work")), PeopleRules.profilesPatch(listOf("work"), makeMember = false))
        assertEquals("سارة", PeopleRules.nameOf("s", listOf(user("s", "member", name = "سارة"))))
        assertEquals("us", PeopleRules.nameOf("s", listOf(user("s", "member"))))
        assertEquals("gone", PeopleRules.nameOf("gone", emptyList()))
    }

    // ------------------------------------------------------------------ profiles

    @Test fun `a new profile needs a free, well-formed slug, a name, and a source when copied`() {
        assertEquals("my-team", ProfileRules.suggest(" My Team! "))
        assertEquals("", ProfileRules.suggest("فريقي"))
        assertEquals("my-team", ProfileRules.followName("", "", "My Team"))
        assertEquals("custom", ProfileRules.followName("custom", "My", "My Team"))
        assertEquals(ProfileRules.SlugProblem.BAD, ProfileRules.slugProblem("-x", emptyList()))
        assertEquals(ProfileRules.SlugProblem.TAKEN, ProfileRules.slugProblem("work", listOf("work")))
        assertNull(ProfileRules.create("Team", "work", false, null, listOf("work")))
        assertNull("a copy asks which one", ProfileRules.create("Team", "team", true, null, emptyList()))
        val copy = ProfileRules.create(" Team ", "team", true, "default", emptyList())!!
        assertEquals("Team", copy.name)
        assertEquals("default", copy.cloneFrom)
        assertNull(ProfileRules.create("Team", "team", false, "default", emptyList())!!.cloneFrom)
        assertNull("an unchanged name is not sent", ProfileRules.rename("Work", " Work "))
        assertEquals("العمل", ProfileRules.rename("Work", " العمل ")!!.name)
        assertFalse(ProfileRules.canArchive("default"))
        assertTrue(ProfileRules.canArchive("work"))
    }

    @Test fun `an archive suggests its slug and its name, made free`() {
        assertEquals("design", ProfileRules.slugFromArchive("design-20260924-101500.tar.gz"))
        assertEquals("الرئيسي", ProfileRules.nameFromArchive("الرئيسي-20260925-101500.tar.gz"))
        assertEquals("design-2", ProfileRules.freeSlug("design", listOf("design")))
        assertEquals("design-2" to "design-2", ProfileRules.fromArchive("design-20260924-101500.tar.gz", listOf("design")))
        assertEquals("" to "العمل", ProfileRules.fromArchive("العمل-20260925-101500.tgz", emptyList()))
        assertEquals(ProfileImport("att1", "team", null), ProfileRules.import("att1", "team", "  "))
        assertEquals("120 KB", ProfileRules.size(120 * 1024))
        assertEquals("3.4 MB", ProfileRules.size((3.4 * 1_048_576).toLong()))
    }

    private fun job(status: String, result: String = "null", error: String = "null", kind: String = "export") = json.decodeFromString(
        Job.serializer(),
        """{"id":"j1","profile":"work","owner_id":"u1","created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z",
            "kind":"$kind","status":"$status","progress":{"percent":null,"message":null},"resource":null,"result":$result,"error":$error,
            "started_at":null,"finished_at":null}""",
    )

    @Test fun `an export's result says what is in the file, and a failure says why`() {
        val done = job("succeeded", """{"attachment_id":"att9","profile":"work","name":"Work-20260927-010203.tar.gz","size_bytes":2048,
            "expires_at":"2026-09-28T01:02:03Z","removed":["work/.env"],"masked":["work/config.yaml"],"providers":2}""")
        val result = ProfileRules.exported(done)!!
        assertEquals("att9", result.attachmentId)
        assertEquals(listOf("work/.env"), result.removed)
        assertEquals(listOf("work/config.yaml"), result.masked)
        assertEquals(2, result.providers)
        assertNull(ProfileRules.exported(job("running")))
        assertFalse(ProfileRules.finished(job("running")))
        assertEquals("Hermes said no", ProfileRules.failure(job("failed", error = """{"error":"Hermes said no","code":"state_invalid"}""")))
        assertEquals("Team", ProfileRules.importedName(job("succeeded", """{"profile_id":"p","slug":"team","name":"Team","providers":0}""", kind = "import")))
        assertEquals("Hermes: bad", ProfileRules.hermesRefusal(HubError(409, "conflict", "x", reason = "hermes_refused", detailMessage = "Hermes: bad")))
        assertNull(ProfileRules.hermesRefusal(HubError(409, "conflict", "x")))
    }

    @Test fun `an import into the default sends the option and reads the backup back (decision 116)`() {
        assertEquals(ProfileImport("att1", "team", "Old", replaceDefault = true), ProfileRules.import("att1", "team", " Old ", replaceDefault = true))
        // The hub does not use the slug then, but it is still sent, and valid.
        assertEquals("imported", ProfileRules.import("att1", "", "", replaceDefault = true).slug)
        assertNull(ProfileRules.import("att1", "team", "Team").replaceDefault)
        val replaced = job("succeeded", """{"profile_id":"p","slug":"default","name":"Old","providers":0,"replaced_default":true,
            "backup":{"profile_id":"b","slug":"default-backup-2","name":"default-backup-2"},"skipped":[]}""", kind = "import")
        assertEquals("default-backup-2", ProfileRules.replacedBackup(replaced))
        // An ordinary import, and a hub older than the option (no `replaced_default`), have none.
        assertNull(ProfileRules.replacedBackup(job("succeeded", """{"profile_id":"p","slug":"team","name":"Team","providers":0}""", kind = "import")))
        assertNull(ProfileRules.replacedBackup(job("failed", error = """{"error":"The import did not happen","code":"internal"}""")))
    }

    // ------------------------------------------------------------------ push senders

    private val account = """{"type":"service_account","project_id":"corehub-1","client_email":"a@b.iam.gserviceaccount.com",
        "private_key":"-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n"}"""
    private val p8 = "-----BEGIN PRIVATE KEY-----\nMIGT\n-----END PRIVATE KEY-----"

    @Test fun `the files Firebase and Apple hand out are recognised, and the wrong ones said so`() {
        assertEquals("corehub-1", PushSenderRules.inspectServiceAccount(account).projectId)
        assertEquals(PushSenderRules.AccountProblem.NOT_JSON, PushSenderRules.inspectServiceAccount("nope").problem)
        assertEquals(PushSenderRules.AccountProblem.GOOGLE_SERVICES, PushSenderRules.inspectServiceAccount("""{"project_info":{},"client":[]}""").problem)
        assertEquals(PushSenderRules.AccountProblem.NOT_SERVICE_ACCOUNT, PushSenderRules.inspectServiceAccount("""{"type":"authorized_user"}""").problem)
        assertEquals(PushSenderRules.AccountProblem.MISSING, PushSenderRules.inspectServiceAccount("""{"type":"service_account","project_id":"x"}""").problem)
        assertEquals("ABCDE12345", PushSenderRules.inspectP8("AuthKey_ABCDE12345.p8", p8).keyId)
        assertEquals("ABCDE12345", PushSenderRules.keyIdFromFileName("AuthKey_abcde12345 (1).p8"))
        assertNull(PushSenderRules.keyIdFromFileName("key.p8"))
        assertEquals(PushSenderRules.KeyProblem.NOT_P8, PushSenderRules.inspectP8("cert.json", "{}").problem)
        assertEquals(PushSenderRules.KeyProblem.NOT_A_KEY, PushSenderRules.inspectP8("AuthKey_ABCDE12345.p8", "hello").problem)
        assertEquals("2345", PushSenderRules.ending("ABCDE12345"))
    }

    private fun sender(provider: String, source: String, state: String = "ready", details: String = "{}") = json.decodeFromString(
        PushSender.serializer(),
        """{"provider":"$provider","state":"$state","source":"$source","missing":[],"details":$details,"devices":2,"last_error":null}""",
    )

    @Test fun `a stored secret is kept when its field stays empty, and never filled back in`() {
        val fcm = sender("fcm", "settings", details = """{"project_id":"corehub-1","service_account":"[stored]"}""")
        val form = PushSenderRules.form(fcm)
        assertEquals("", form.serviceAccount)
        assertTrue("stored and untouched: save keeps it", PushSenderRules.ready(fcm, form))
        assertEquals("[stored]", PushSenderRules.body(fcm, form).serviceAccount)
        assertFalse(PushSenderRules.ready(sender("fcm", "none", "not_configured"), form))

        val apns = sender("apns", "settings", details = """{"key_id":"ABCDE12345","team_id":"TEAM1","bundle_id":"hub.core.app","environment":"sandbox","private_key":"[stored]"}""")
        val apnsForm = PushSenderRules.form(apns)
        assertTrue(apnsForm.sandbox)
        assertEquals("", apnsForm.privateKey)
        val body = PushSenderRules.body(apns, apnsForm)
        assertNull("an empty key is left out", body.privateKey)
        assertEquals("TEAM1", body.teamId)
        assertFalse(PushSenderRules.ready(apns, apnsForm.copy(teamId = " ")))
        assertFalse(PushSenderRules.ready(apns, apnsForm.copy(privateKey = "junk")))
        assertEquals(
            listOf(PushSenderRules.Saved.Key("2345"), PushSenderRules.Saved.Team("TEAM1"), PushSenderRules.Saved.Sandbox),
            PushSenderRules.saved(apns),
        )
        assertFalse("Web Push is the hub's own", PushSenderRules.editable(sender("webpush", "generated")))
        assertFalse("the environment wins", PushSenderRules.editable(sender("fcm", "environment")))
        assertEquals(PushSenderRules.Outcome.INCOMPLETE, PushSenderRules.outcome(sender("apns", "settings", "not_configured")))
        assertEquals(PushSenderRules.Outcome.REFUSED, PushSenderRules.outcome(sender("apns", "settings", "error")))
    }

    // ------------------------------------------------------------------ pairing

    private fun agent(id: String, status: String, capabilities: String) = json.decodeFromString(
        Agent.serializer(),
        """{"id":"$id","profile":"work","owner_id":"u1","created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z",
            "slug":"$id","name":"$id","kind":"hermes","avatar":{"kind":"generated","url":null,"seed":"c"},"status":"$status","enabled":true,
            "install":{"source":"managed","update_available":false,"newer_than_tested":false,"auto_update":false,"auto_update_supported":false},
            "runtime":{"state":"running"},"capabilities":$capabilities,"sections":[],"limited":false,"subagents":"none","vendor":null}""",
    )

    @Test fun `pairing is asked of the installed agent that has channels`() {
        assertNull(PairingRules.channelAgent(listOf(agent("a", "available", "[]"))))
        assertNull(PairingRules.channelAgent(listOf(agent("a", "not_installed", """["channels"]"""))))
        assertEquals("b", PairingRules.channelAgent(listOf(agent("a", "available", "[]"), agent("b", "limited", """["channels"]""")))?.id)
    }

    // ------------------------------------------------------------------ what is sent

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/auth/lockouts") -> ok("""{"cleared":1}""")
                    path.endsWith("/export") -> MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json").setBody("""{"job_id":"j1"}""")
                    path.endsWith("/profile-imports") -> MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json").setBody("""{"job_id":"j2"}""")
                    path.endsWith("/push/senders/fcm") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path.endsWith("/push/senders/fcm") -> ok("""{"provider":"fcm","state":"ready","source":"settings","missing":[],"details":{"project_id":"corehub-1"},"devices":0}""")
                    path.contains("/users/") -> ok(
                        """{"id":"a","username":"ua","display_name":"","role":"member","status":"active","locale":"ar","avatar":{"kind":"generated","url":null,"seed":"a"},
                            "profiles":["work"],"default_profile":"work","created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z"}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun ops() = AdminTwoOps("work") { AdminApis(server.url("/").toString().trimEnd('/'), OkHttpClient(), "work") }

    @Test fun `one lockout is cleared by its address, and all without one`() = runTest {
        assertEquals(1, ops().clearLockouts("203.0.113.7").getOrThrow())
        assertEquals("203.0.113.7", requests.last().requestUrl!!.queryParameter("ip"))
        ops().clearLockouts()
        assertNull(requests.last().requestUrl!!.queryParameter("ip"))
        assertEquals("DELETE", requests.last().method)
    }

    @Test fun `an admin made a member carries the list with the role`() = runTest {
        ops().updateUser("a", PeopleRules.profilesPatch(listOf("work"), makeMember = true)!!).getOrThrow()
        assertEquals("""{"role":"member","profiles":["work"]}""", requests.last().body.readUtf8())
    }

    @Test fun `export and import name the profile the person is in, and export asks with or without providers`() = runTest {
        assertEquals("j1", ops().export("p1", providers = true).getOrThrow())
        val export = requests.last()
        assertEquals("work", export.getHeader("X-Hub-Profile"))
        assertEquals("""{"providers":true}""", export.body.readUtf8())
        assertEquals("j2", ops().import(ProfileRules.import("att1", "team", "Team")).getOrThrow())
        val import = requests.last()
        assertEquals("work", import.getHeader("X-Hub-Profile"))
        assertEquals("""{"attachment_id":"att1","slug":"team","name":"Team"}""", import.body.readUtf8())
    }

    @Test fun `a sender saved with its stored secret sends the marker, and forgetting deletes it`() = runTest {
        val fcm = sender("fcm", "settings", details = """{"project_id":"corehub-1"}""")
        val saved = ops().saveSender(fcm.provider, PushSenderRules.body(fcm, PushSenderRules.form(fcm))).getOrThrow()
        assertEquals(PushSenderRules.Outcome.VALID, PushSenderRules.outcome(saved))
        assertEquals("""{"enabled":true,"service_account":"[stored]"}""", requests.last().body.readUtf8())
        assertNotNull(ops().forgetSender(fcm.provider).getOrThrow())
        assertEquals("DELETE", requests.last().method)
    }
}

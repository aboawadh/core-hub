package hub.core.android.parity

import hub.core.android.data.HubApis
import hub.core.android.nav.Route
import hub.core.android.ui.screens.NoticeLinks
import hub.core.android.ui.screens.OwnSettingsOps
import hub.core.android.ui.screens.OwnSettingsRules
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.AppToken
import hub.core.client.model.Notice
import hub.core.client.model.NoticeKind
import hub.core.client.model.ResourceRef
import hub.core.client.model.TokenScope
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The person's own settings (batch 4): the inbox marks and opens, the account's name, password and
 * picture rules, messaging-account linking, the display text sizes, what a revoke says — and the
 * calls, against a scripted hub. iOS's OwnSettingsTests is the twin.
 */
class OwnSettingsTest {
    private val json = Serializer.kotlinxSerializationJson
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-09-27T10:00:00Z")

    private fun notice(id: String, read: Boolean) = Notice(
        id = id, userId = "u1", kind = NoticeKind.RUN_COMPLETED, title = "Done", createdAt = now, profile = "work",
        readAt = if (read) now else null,
    )

    // ------------------------------------------------------------------ the rules

    @Test fun `marking one notice moves the unread count only when it changes`() {
        val inbox = OwnSettingsRules.Inbox(listOf(notice("a", read = false), notice("b", read = true)), 1)
        val read = OwnSettingsRules.marking(inbox, "a", read = true, now = now)
        assertNotNull(read.notices[0].readAt)
        assertEquals(0, read.unread)
        assertEquals(read, OwnSettingsRules.marking(read, "a", read = true))
        val unread = OwnSettingsRules.marking(inbox, "b", read = false)
        assertNull(unread.notices[1].readAt)
        assertEquals(2, unread.unread)
        assertEquals(inbox, OwnSettingsRules.marking(inbox, "zz", read = true))
        assertEquals(0, OwnSettingsRules.marking(inbox.copy(unread = 0), "a", read = true).unread)
    }

    @Test fun `mark all read empties the unread view and reads every row of all`() {
        val list = listOf(notice("a", read = false), notice("b", read = true))
        assertEquals(emptyList<Notice>(), OwnSettingsRules.allRead(list, unreadOnly = true))
        val all = OwnSettingsRules.allRead(list, unreadOnly = false, now = now)
        assertTrue(all.all { it.readAt != null })
        assertEquals(listOf("a", "b"), all.map { it.id })
    }

    @Test fun `a tapped notice opens what it is about`() {
        assertEquals(Route.Chat("S", "work"), NoticeLinks.route(ResourceRef(ResourceRef.Kind.SESSION, "S"), "work"))
        assertEquals(Route.Workflows, NoticeLinks.route(ResourceRef(ResourceRef.Kind.WORKFLOW_RUN, "R"), "work"))
        assertNull(NoticeLinks.route(null, "work"))
    }

    @Test fun `a name is saved trimmed and only when it changed`() {
        assertEquals("Sara", OwnSettingsRules.nameToSave("  Sara  ", "Tariq"))
        assertNull(OwnSettingsRules.nameToSave("Tariq ", "Tariq"))
        assertNull(OwnSettingsRules.nameToSave("   ", "Tariq"))
        assertNull(OwnSettingsRules.nameToSave("a".repeat(81), "Tariq"))
        assertEquals(80, OwnSettingsRules.nameToSave("a".repeat(80), "Tariq")?.length)
    }

    @Test fun `a new password must be long enough and typed twice`() {
        assertNull(OwnSettingsRules.passwordProblem("", ""))
        assertEquals(OwnSettingsRules.PasswordProblem.SHORT, OwnSettingsRules.passwordProblem("short", ""))
        assertEquals(OwnSettingsRules.PasswordProblem.MISMATCH, OwnSettingsRules.passwordProblem("long enough", "long"))
        assertNull(OwnSettingsRules.passwordProblem("long enough", "long enough"))
        assertTrue(OwnSettingsRules.canChangePassword("old", "long enough", "long enough"))
        assertFalse(OwnSettingsRules.canChangePassword("", "long enough", "long enough"))
        assertFalse(OwnSettingsRules.canChangePassword("old", "long enough", "long enougH"))
        assertFalse(OwnSettingsRules.canChangePassword("old", "1234567", "1234567"))
    }

    @Test fun `a picture is made small and sent as a JPEG the hub takes`() {
        assertEquals(512 to 384, OwnSettingsRules.avatarSize(4032, 3024))
        assertEquals("never made larger", 200 to 300, OwnSettingsRules.avatarSize(200, 300))
        assertEquals(0 to 0, OwnSettingsRules.avatarSize(0, 10))
        assertEquals("data:image/jpeg;base64,/9j/", OwnSettingsRules.avatarDataUrl(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertNull(OwnSettingsRules.avatarDataUrl(ByteArray(0)))
        assertNull(OwnSettingsRules.avatarDataUrl(ByteArray(512 * 1024 + 1)))
        assertEquals("TA", OwnSettingsRules.initials("Tariq Alowairdhi"))
        assertEquals("ط", OwnSettingsRules.initials("طارق"))
        assertEquals("?", OwnSettingsRules.initials(" "))
    }

    @Test fun `a link shows once the list grows while a code waits`() {
        assertTrue(OwnSettingsRules.linked(true, 1, 2))
        assertFalse(OwnSettingsRules.linked(true, 1, 1))
        assertFalse(OwnSettingsRules.linked(false, 1, 2))
        assertFalse(OwnSettingsRules.linked(true, null, 2))
    }

    @Test fun `the text size keeps to the range in five percent steps`() {
        assertEquals(13, OwnSettingsRules.textScales.size)
        assertEquals(BigDecimal("0.85"), OwnSettingsRules.textScales.first())
        assertEquals(BigDecimal("1.45"), OwnSettingsRules.textScales.last())
        assertEquals(BigDecimal("1.00"), OwnSettingsRules.textScale(BigDecimal.ONE))
        assertEquals(BigDecimal("1.05"), OwnSettingsRules.textScale(BigDecimal("1.04")))
        assertEquals(BigDecimal("0.85"), OwnSettingsRules.textScale(BigDecimal("0.5")))
        assertEquals(BigDecimal("1.45"), OwnSettingsRules.textScale(BigDecimal("2")))
        assertTrue(OwnSettingsRules.textScale(BigDecimal("1.25")) in OwnSettingsRules.textScales)
    }

    @Test fun `a device token is told apart from an app token`() {
        assertTrue(OwnSettingsRules.isDevice(AppToken("t1", "Phone", listOf(TokenScope.READ), now, deviceId = "d1")))
        assertFalse(OwnSettingsRules.isDevice(AppToken("t2", "Script", listOf(TokenScope.READ), now)))
    }

    // ------------------------------------------------------------------ the calls

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private fun ok(body: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private val user = """{"id":"01J8QK3ZR2W7M5N4P6T8V9X0HM","username":"tariq","display_name":"Sara","role":"owner","status":"active",
        "locale":"ar","avatar":{"kind":"image","url":null,"seed":null},"profiles":["work"],"default_profile":"work",
        "last_login_at":null,"created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z"}"""
    private val noticeJson = """{"id":"01J8QK3ZR2W7M5N4P6T8V9X0NT","user_id":"u1","profile":"work","kind":"run_completed","title":"Done",
        "body":null,"resource":{"kind":"session","id":"S"},"read_at":"2026-09-27T10:00:00Z","created_at":"2026-09-27T09:00:00Z"}"""

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/auth/me") && request.method == "PATCH" -> ok(user)
                    path.endsWith("/auth/me/password") -> MockResponse().setResponseCode(204)
                    path.endsWith("/link-codes") -> ok("""{"code":"K7M2Q9","command":"/link K7M2Q9","expires_at":"2026-09-27T10:10:00Z"}""", 201)
                    path.contains("/auth/me/channel-identities/") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path.endsWith("/auth/me/channel-identities") -> ok("""{"items":[{"id":"CI1","user_id":"u1","platform":"telegram","sender_id":"123456","linked_at":"2026-09-26T10:00:00Z","last_used_at":null}]}""")
                    path.endsWith("/notify/notices") && request.method == "GET" -> ok("""{"items":[$noticeJson],"next_cursor":"c2","unread_count":3}""")
                    path.endsWith("/notify/notices") && request.method == "PATCH" -> ok("""{"updated":3}""")
                    path.contains("/notify/notices/") -> ok(noticeJson)
                    path.contains("/auth/app-tokens/") && request.method == "DELETE" -> MockResponse().setResponseCode(204)
                    path.endsWith("/agents") -> ok("""{"items":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun ops() = OwnSettingsOps { HubApis(server.url("/").toString().trimEnd('/'), OkHttpClient()) }
    private fun body(request: RecordedRequest) = json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test fun `the name, the picture and the password go to the hub as the contract has them`() = runTest {
        assertEquals("Sara", ops().rename("Sara").getOrThrow().displayName)
        assertEquals("PATCH", requests[0].method)
        assertEquals("Sara", body(requests[0])["display_name"]!!.jsonPrimitive.content)

        ops().setAvatar("data:image/jpeg;base64,/9j/").getOrThrow()
        val image = body(requests[1])["avatar"]!!.jsonObject
        assertEquals("image", image["kind"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,/9j/", image["data_url"]!!.jsonPrimitive.content)
        ops().setAvatar(null).getOrThrow()
        assertEquals("generated", body(requests[2])["avatar"]!!.jsonObject["kind"]!!.jsonPrimitive.content)

        ops().changePassword("old password", "new password").getOrThrow()
        val change = body(requests[3])
        assertEquals("old password", change["current_password"]!!.jsonPrimitive.content)
        assertEquals("new password", change["new_password"]!!.jsonPrimitive.content)
    }

    @Test fun `messaging accounts are listed, a code is asked for, and one is unlinked`() = runTest {
        assertEquals("123456", ops().channels().getOrThrow().single().senderId)
        assertEquals("/link K7M2Q9", ops().linkCode().getOrThrow().command)
        assertEquals("POST", requests[1].method)
        ops().unlink("CI1").getOrThrow()
        assertEquals("DELETE", requests[2].method)
        assertTrue(requests[2].requestUrl!!.encodedPath.endsWith("/CI1"))
    }

    @Test fun `the inbox asks for unread only, marks one, and marks all`() = runTest {
        val page = ops().notices(unreadOnly = true).getOrThrow()
        assertEquals(3, page.unreadCount)
        assertEquals("c2", page.nextCursor)
        assertEquals("true", requests[0].requestUrl!!.queryParameter("unread"))
        ops().notices(unreadOnly = false, cursor = "c2").getOrThrow()
        assertNull(requests[1].requestUrl!!.queryParameter("unread"))
        assertEquals("c2", requests[1].requestUrl!!.queryParameter("cursor"))

        ops().mark("01J8QK3ZR2W7M5N4P6T8V9X0NT", read = false).getOrThrow()
        assertEquals("PATCH", requests[2].method)
        assertEquals("false", body(requests[2])["read"]!!.jsonPrimitive.content)
        assertEquals(3, ops().markAll().getOrThrow())
        assertEquals("PATCH", requests[3].method)
    }

    @Test fun `a token is revoked by its id, and a profile without Hermes shows no redact switch`() = runTest {
        ops().revoke("T1").getOrThrow()
        assertEquals("DELETE", requests[0].method)
        assertTrue(requests[0].requestUrl!!.encodedPath.endsWith("/T1"))
        assertNull(ops().redact("work").getOrThrow())
        assertEquals("work", requests[1].getHeader("X-Hub-Profile"))
    }
}

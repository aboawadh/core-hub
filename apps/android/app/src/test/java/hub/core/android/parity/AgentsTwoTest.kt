package hub.core.android.parity

import hub.core.android.data.HubApis
import hub.core.android.ui.screens.AgentsTwoOps
import hub.core.android.ui.screens.ChannelRules
import hub.core.android.ui.screens.ChannelSettingRules
import hub.core.android.ui.screens.CompressionRules
import hub.core.android.ui.screens.McpRules
import hub.core.android.ui.screens.SettingsCardRules
import hub.core.android.ui.screens.WebhookRules
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Channel
import hub.core.client.model.ChannelModeWrite
import hub.core.client.model.ChannelPlatform
import hub.core.client.model.ChannelSetting
import hub.core.client.model.HubToolGroupId
import hub.core.client.model.McpServer
import hub.core.client.model.McpServerPatch
import hub.core.client.model.McpServerWrite
import hub.core.client.model.PairingRequest
import hub.core.client.model.PendingWrite
import hub.core.client.model.ProfileSettingsCompression
import hub.core.client.model.SettingsSection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Agents II on Android (apps batch 9): the rules of MCP servers, the Settings cards and Channels, and their calls against a scripted hub. */
class AgentsTwoTest {
    private val json = Serializer.kotlinxSerializationJson
    private val agentId = "01J8QK3ZR2W7M5N4P6T8V9X0AG"

    private fun server(name: String, transport: String, config: String) = json.decodeFromString(
        McpServer.serializer(),
        """{"name":"$name","transport":"$transport","enabled":true,"connected":false,"tools":[],"error":null,"config":$config,"updated_at":"2026-09-21T11:45:00Z"}""",
    )

    private fun channel(platform: String, login: String? = null, linked: Boolean? = null, configured: Boolean = false, mode: String? = null, fields: String = "[]") = json.decodeFromString(
        Channel.serializer(),
        """{"platform":"$platform","label":"${platform.replaceFirstChar { it.uppercase() }}","enabled":true,"configured":$configured,"exclusive":false,"status":"online",
            "restart_needed":false,"fields":$fields,"error":null,"login":${login?.let { "\"$it\"" } ?: "null"},
            "link":${linked?.let { """{"linked":$it,"mode":${mode?.let { m -> "\"$m\"" } ?: "null"},"reply_title":null}""" } ?: "null"}}""",
    )

    private fun spec(platform: String, login: String, settings: Boolean) = json.decodeFromString(
        ChannelPlatform.serializer(),
        """{"platform":"$platform","label":"$platform","support":"full","login":"$login","credentials":[],"allowed_users_key":null,"validates":true,"pairs":true,
            "allowlist":false,"settings":$settings,"exclusive":true,"packages":"none","inbound":false,"program":null,"docs_url":null}""",
    )

    private fun option(key: String, kind: String, value: String = "null", default: String = "null", min: Int? = null, max: Int? = null) = json.decodeFromString(
        ChannelSetting.serializer(),
        """{"key":"$key","section":"access","kind":"$kind","value":$value,"default":$default,"shared":false,"choices":null,"min":${min ?: "null"},"max":${max ?: "null"}}""",
    )

    // ------------------------------------------------------------------ MCP servers

    @Test fun `a command server reads as a form and writes back with its other keys and stored secrets kept`() {
        val stored = server(
            "github", "stdio",
            """{"command":"npx","args":["-y","@mcp/github"],"env":{"GITHUB_TOKEN":"[stored]","MODE":"read"},"enabled":true,"timeout":30}""",
        )
        val draft = McpRules.draftOf(stored.config)
        assertEquals(McpRules.Kind.COMMAND, draft.kind)
        assertEquals("-y\n@mcp/github", draft.args)
        assertEquals(McpRules.Row("GITHUB_TOKEN", "", stored = true), draft.env[0])
        assertEquals(setOf("timeout"), draft.rest.keys)
        val back = McpRules.configOf(draft.copy(args = "-y\n\n @mcp/github \n"))
        assertEquals(JsonPrimitive("[stored]"), back["env"]!!.jsonObject["GITHUB_TOKEN"])
        assertEquals(JsonArray(listOf(JsonPrimitive("-y"), JsonPrimitive("@mcp/github"))), back["args"])
        assertEquals(JsonPrimitive(30), back["timeout"])
        assertFalse(back.containsKey("enabled"))
        // A new value replaces the stored one; a removed row is gone.
        val typed = McpRules.configOf(draft.copy(env = listOf(McpRules.Row("GITHUB_TOKEN", "ghp_new", stored = true))))
        assertEquals(JsonObject(mapOf("GITHUB_TOKEN" to JsonPrimitive("ghp_new"))), typed["env"])
        assertEquals("npx -y @mcp/github", McpRules.summary(stored))
    }

    @Test fun `an address server, its problems, and the transport an edit sends`() {
        val http = server("docs", "http", """{"url":"https://mcp.example/docs","headers":{"Authorization":"[stored]"}}""")
        val draft = McpRules.draftOf(http.config)
        assertEquals(McpRules.Kind.URL, draft.kind)
        assertEquals("https://mcp.example/docs", McpRules.summary(http))
        assertEquals(McpServerWrite.Transport.HTTP, McpRules.transportOf(McpRules.configOf(draft)))
        assertNull(McpRules.transportChange(http, McpRules.configOf(draft)))
        val toCommand = McpRules.configOf(draft.copy(kind = McpRules.Kind.COMMAND, command = "uvx"))
        assertFalse(toCommand.containsKey("url"))
        assertEquals(McpServerPatch.Transport.STDIO, McpRules.transportChange(http, toCommand))

        assertEquals(McpRules.Problem.URL_BAD, McpRules.problem(draft.copy(url = "mcp.example")))
        assertEquals(McpRules.Problem.COMMAND_REQUIRED, McpRules.problem(McpRules.Draft(command = " ")))
        assertEquals(McpRules.Problem.ROW_BAD, McpRules.problem(McpRules.Draft(command = "npx", env = listOf(McpRules.Row("A B", "x")))))
        assertEquals(McpRules.Problem.ROW_BAD, McpRules.problem(McpRules.Draft(command = "npx", env = listOf(McpRules.Row("", "orphan")))))
        assertNull(McpRules.problem(McpRules.TEMPLATE))
        assertTrue(McpRules.validName("fs.local_1-2"))
        assertFalse(McpRules.validName("my server"))
        assertNull(McpRules.parse("[1]"))
        assertEquals(JsonPrimitive("x"), McpRules.parse("""{"command":"x"}""")!!["command"])
        assertEquals("0.8", McpRules.seconds(820))
        // The hub's own block belongs to its card, not to the list.
        val listed = McpRules.listed(listOf(http, server("corehub", "http", """{"url":"http://hub/hub-mcp"}""")), null)
        assertEquals(listOf("docs"), listed.map { it.name })
    }

    // ------------------------------------------------------------------ Settings cards

    @Test fun `compression is whole percentages here and ratios on the wire`() {
        val stored = ProfileSettingsCompression(
            enabled = true, threshold = java.math.BigDecimal("0.85"), targetRatio = java.math.BigDecimal("0.2"), protectFirst = 3, protectLast = 20, contextLength = null,
        )
        val draft = CompressionRules.draftOf(stored)
        assertEquals("85", draft.threshold)
        assertEquals("20", draft.target)
        assertEquals("", draft.contextLength)
        val patch = CompressionRules.patchOf(draft.copy(threshold = "90", contextLength = "200000")).getOrThrow()
        assertEquals(JsonPrimitive(0.9), patch["threshold"])
        assertEquals(JsonPrimitive(200000), patch["context_length"])
        assertEquals(JsonNull, CompressionRules.patchOf(draft).getOrThrow()["context_length"])
        assertEquals(CompressionRules.Field.THRESHOLD, CompressionRules.invalid(CompressionRules.patchOf(draft.copy(threshold = "101"))))
        assertEquals(CompressionRules.Field.CONTEXT_LENGTH, CompressionRules.invalid(CompressionRules.patchOf(draft.copy(contextLength = "512"))))
        assertEquals(CompressionRules.Field.PROTECT_LAST, CompressionRules.invalid(CompressionRules.patchOf(draft.copy(protectLast = "a"))))
        assertEquals(SettingsCardRules.Saved.RESTARTING, SettingsCardRules.saved("01J8QK3ZR2W7M5N4P6T8V9X0JB", SettingsSection.Applies.RESTART))
        assertEquals(SettingsCardRules.Saved.RESTART_NEEDED, SettingsCardRules.saved(null, SettingsSection.Applies.RESTART))
        assertEquals(SettingsCardRules.Saved.NEXT_MESSAGE, SettingsCardRules.saved(null, SettingsSection.Applies.NEXT_MESSAGE))
    }

    // ------------------------------------------------------------------ Channels

    @Test fun `only what is linked or waited on is listed, each card offering what the web's does`() {
        val whatsapp = channel("whatsapp", "qr", linked = true, configured = true, mode = "self-chat")
        val telegram = channel("telegram", "token", linked = true, configured = true)
        val discord = channel("discord", "credentials", linked = false)
        val matrix = channel("matrix", "credentials", linked = false)
        val legacy = channel("irc", configured = true, fields = """[{"key":"IRC_SERVER","label":{"ar":"خ","en":"Server"},"kind":"text","target":"configuration","value":"irc.x"}]""")
        val webhook = channel("webhook", configured = true)
        val waiting = listOf(json.decodeFromString(PairingRequest.serializer(), """{"platform":"discord","request_id":"r1","user_id":"u1","user_name":null,"requested_at":"2026-09-27T10:00:00Z"}"""))
        assertEquals(listOf("whatsapp", "telegram", "discord", "irc"), ChannelRules.shown(listOf(whatsapp, telegram, discord, matrix, legacy, webhook), waiting).map { it.platform })
        assertEquals(1, ChannelRules.waitingOn(waiting, "discord"))

        assertEquals(listOf(ChannelRules.Action.MODE, ChannelRules.Action.REPLY_HEADER, ChannelRules.Action.UNLINK), ChannelRules.actions(whatsapp, spec("whatsapp", "qr", false)))
        assertEquals(listOf(ChannelRules.Action.SETTINGS, ChannelRules.Action.UNLINK), ChannelRules.actions(telegram, null))
        assertEquals(listOf(ChannelRules.Action.LINK), ChannelRules.actions(discord, spec("discord", "credentials", true)))
        assertEquals(emptyList<ChannelRules.Action>(), ChannelRules.actions(discord, null))
        assertEquals(listOf(ChannelRules.Action.PAIR), ChannelRules.actions(channel("whatsapp", "qr", linked = false), null))
        assertEquals(listOf(ChannelRules.Action.FIELDS, ChannelRules.Action.CLEAR), ChannelRules.actions(legacy, null))
        assertEquals(ChannelRules.UnlinkWords.TELEGRAM, ChannelRules.unlinkWords(telegram))
        assertEquals(ChannelRules.UnlinkWords.CREDENTIALS, ChannelRules.unlinkWords(discord))
    }

    @Test fun `the reply header is the agent's name or one typed line of at most 64 characters`() {
        assertFalse(ChannelRules.replyCustom(null, "Office"))
        assertFalse(ChannelRules.replyCustom("Office", "Office"))
        assertTrue(ChannelRules.replyCustom("Desk", "Office"))
        assertEquals("مكتب الإدارة", ChannelRules.replyTitle(true, "Office", "  مكتب \n  الإدارة "))
        assertEquals("Office", ChannelRules.replyTitle(false, "Office", "ignored"))
        assertTrue(ChannelRules.replyUsable("x".repeat(64)))
        assertFalse(ChannelRules.replyUsable("x".repeat(65)))
        assertFalse(ChannelRules.replyUsable(""))
    }

    @Test fun `fields split by what they declared, and a channel's settings become the values the hub takes`() {
        val fields = channel(
            "irc", configured = true,
            fields = """[{"key":"TOKEN","label":{"ar":"ر","en":"Token"},"kind":"secret","target":"credentials","value":"[stored]"},
                {"key":"SERVER","label":{"ar":"خ","en":"Server"},"kind":"text","target":"configuration","value":"irc.x"},
                {"key":"TLS","label":{"ar":"ت","en":"TLS"},"kind":"toggle","target":"configuration","value":true}]""",
        ).fields
        val write = ChannelRules.write(fields, mapOf("SERVER" to JsonPrimitive("irc.y")))
        assertEquals(mapOf("TOKEN" to "[stored]"), write.credentials)
        assertEquals(JsonPrimitive("irc.y"), write.configuration!!["SERVER"])
        assertEquals(JsonPrimitive(true), write.configuration!!["TLS"])

        val users = option("allowed_users", "list", value = """["1"]""")
        val every = option("command_menu_max", "number", default = "30", min = 1, max = 100)
        val proxy = option("proxy_url", "text")
        val draft = mapOf("allowed_users" to ChannelSettingRules.typed("12, 34،56 78"), "command_menu_max" to ChannelSettingRules.typed(""), "proxy_url" to JsonNull)
        val values = ChannelSettingRules.values(listOf(users, every, proxy), draft)
        assertEquals(JsonArray(listOf("12", "34", "56", "78").map(::JsonPrimitive)), values["allowed_users"])
        assertEquals(JsonNull, values["command_menu_max"])
        assertEquals(JsonNull, values["proxy_url"])
        assertEquals(JsonPrimitive(40), ChannelSettingRules.values(listOf(every), mapOf("command_menu_max" to JsonPrimitive("40")))["command_menu_max"])
        assertEquals(setOf("command_menu_max"), ChannelSettingRules.problems(listOf(every), mapOf("command_menu_max" to JsonPrimitive("500"))))
        assertEquals("1", ChannelSettingRules.shown(users, emptyMap()))
        assertEquals(ChannelSettingRules.Default.Text("30"), ChannelSettingRules.default(every))
        assertEquals(ChannelSettingRules.Default.None, ChannelSettingRules.default(option("x", "list", default = "[]")))
        assertEquals(ChannelSettingRules.Default.On(false), ChannelSettingRules.default(option("x", "toggle", default = "false")))
    }

    @Test fun `a webhook's name, events, address and where its answer may go`() {
        assertNull(WebhookRules.nameProblem("GitHub-Issues", emptySet()))
        assertEquals(WebhookRules.NameProblem.INVALID, WebhookRules.nameProblem("-x", emptySet()))
        assertEquals(WebhookRules.NameProblem.TAKEN, WebhookRules.nameProblem("ci", setOf("ci")))
        assertFalse(WebhookRules.ready("ci", " ", emptySet()))
        assertTrue(WebhookRules.ready("ci", "Build {ref}", emptySet()))
        assertEquals(listOf("issues", "push"), WebhookRules.eventsOf("issues, push،issues  "))
        assertEquals("https://hub.example/webhooks/w/ci", WebhookRules.url("https://hub.example/", "/webhooks/w/ci"))
        val targets = WebhookRules.targets(listOf(channel("telegram", "token", linked = true, configured = true), channel("webhook", configured = true), channel("discord", linked = false)))
        assertEquals(listOf("telegram"), targets.map { it.platform })
        assertTrue(WebhookRules.accepted(202))
        assertFalse(WebhookRules.accepted(401))
    }

    // ------------------------------------------------------------------ the calls, against a scripted hub

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private val bodies = mutableListOf<String>()

    private fun ok(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private val channelJson = """{"platform":"whatsapp","label":"WhatsApp","enabled":true,"configured":true,"exclusive":false,"status":"online","restart_needed":false,"fields":[],"error":null,"login":"qr","link":{"linked":true,"mode":"bot"}}"""
    private val hubToolsJson = """{"enabled":true,"available":true,"server_name":"corehub","groups":[],"recent_calls":[]}"""

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                bodies += request.body.readUtf8()
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/mcp-servers") && request.method == "POST" -> ok("""{"name":"docs","transport":"http","enabled":true,"connected":false,"tools":[],"error":null,"config":{"url":"https://x"},"updated_at":"2026-09-21T11:45:00Z"}""", 201)
                    path.contains("/mcp-servers/") && request.method == "PATCH" -> ok("""{"name":"docs","transport":"stdio","enabled":true,"connected":false,"tools":[],"error":null,"config":{"command":"uvx"},"updated_at":"2026-09-21T11:45:00Z"}""")
                    path.endsWith("/hub-tools") -> ok(hubToolsJson)
                    path.endsWith("/mode") || path.endsWith("/reply-header") -> ok(channelJson)
                    path.endsWith("/settings") && path.contains("/channels/") -> ok("""{"platform":"telegram","options":[]}""")
                    (path.endsWith("/channels/irc") && request.method == "DELETE") || path.endsWith("/restart") -> ok("""{"job_id":"01J8QK3ZR2W7M5N4P6T8V9X0JB"}""", 202)
                    path.contains("/pending-writes/") -> ok("""{"id":"w1","kind":"skills","applied":true}""")
                    path.endsWith("/webhooks") && request.method == "POST" ->
                        ok("""{"name":"ci","prompt":"p","events":["push"],"deliver":"log","path":"/webhooks/x/ci","static":false,"description":null,"secret":"s"}""", 201)
                    path.endsWith("/profiles") -> ok("""{"items":[{"id":"01J8QK3ZR2W7M5N4P6T8V9X0PF","slug":"work","name":"Work","avatar":{"kind":"generated","url":null,"seed":"w"},"default_model":null,
                        "agent_count":1,"session_count":0,"owner_id":"u1","created_at":"2026-09-21T10:00:00Z","updated_at":"2026-09-21T10:00:00Z"}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun ops() = AgentsTwoOps({ HubApis(server.url("/").toString().trimEnd('/'), OkHttpClient()) }, "work", agentId)

    private fun last(method: String, pathEnd: String): Pair<RecordedRequest, JsonObject> {
        val i = requests.indexOfLast { it.method == method && it.requestUrl!!.encodedPath.endsWith(pathEnd) }
        assertTrue("no $method …$pathEnd in ${requests.map { it.method + " " + it.requestUrl!!.encodedPath }}", i >= 0)
        assertEquals("work", requests[i].getHeader("X-Hub-Profile"))
        return requests[i] to (json.parseToJsonElement(bodies[i].ifEmpty { "{}" }) as JsonObject)
    }

    @Test fun `a new server is http by its address, and an edit that changed kind sends the transport`() = runTest {
        ops().createServer(" docs ", mapOf("url" to JsonPrimitive("https://x"))).getOrThrow()
        val (_, created) = last("POST", "/mcp-servers")
        assertEquals("docs", created["name"]!!.jsonPrimitive.content)
        assertEquals("http", created["transport"]!!.jsonPrimitive.content)
        assertEquals(true, created["enabled"]!!.jsonPrimitive.content.toBoolean())

        ops().saveServer("docs", mapOf("command" to JsonPrimitive("uvx")), McpServerPatch.Transport.STDIO).getOrThrow()
        val (patch, body) = last("PATCH", "/mcp-servers/docs")
        assertTrue(patch.requestUrl!!.encodedPath.contains("/agents/$agentId/"))
        assertEquals("stdio", body["transport"]!!.jsonPrimitive.content)
        assertEquals("uvx", body["config"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("enabled"))
    }

    @Test fun `a hub tools group changes alone, and WhatsApp's mode and header are what was chosen`() = runTest {
        ops().setGroup(HubToolGroupId.TASKS, allowWrites = true).getOrThrow()
        val (_, tools) = last("PATCH", "/hub-tools")
        val group = (tools["groups"] as JsonArray).single().jsonObject
        assertEquals("tasks", group["id"]!!.jsonPrimitive.content)
        assertEquals("true", group["allow_writes"]!!.jsonPrimitive.content)
        assertFalse(tools.containsKey("enabled"))

        ops().setMode("whatsapp", ChannelModeWrite.Mode.SELF_MINUS_CHAT).getOrThrow()
        assertEquals("self-chat", last("PUT", "/mode").second["mode"]!!.jsonPrimitive.content)
        ops().setReplyHeader("whatsapp", custom = true, title = "Desk").getOrThrow()
        val (_, header) = last("PUT", "/reply-header")
        assertEquals("custom", header["use"]!!.jsonPrimitive.content)
        assertEquals("Desk", header["title"]!!.jsonPrimitive.content)
        ops().setReplyHeader("whatsapp", custom = false, title = "ignored").getOrThrow()
        assertEquals("agent_name", last("PUT", "/reply-header").second["use"]!!.jsonPrimitive.content)
    }

    @Test fun `a channel's settings, clearing it, a restart, a pending write and a webhook reach the hub as the web sends them`() = runTest {
        ops().saveChannelSettings("telegram", mapOf("allowed_users" to JsonArray(listOf(JsonPrimitive("12"))), "proxy_url" to JsonNull)).getOrThrow()
        val (_, settings) = last("PATCH", "/channels/telegram/settings")
        assertEquals(JsonNull, settings["values"]!!.jsonObject["proxy_url"])
        assertEquals("12", (settings["values"]!!.jsonObject["allowed_users"] as JsonArray).single().jsonPrimitive.content)

        assertEquals("01J8QK3ZR2W7M5N4P6T8V9X0JB", ops().clearChannel("irc").getOrThrow().jobId)
        last("DELETE", "/channels/irc")
        assertEquals("01J8QK3ZR2W7M5N4P6T8V9X0JB", ops().restart().getOrThrow())

        val write = json.decodeFromString(PendingWrite.serializer(), """{"id":"w1","kind":"skills","action":"create","summary":"s","origin":"foreground"}""")
        ops().approveWrite(write).getOrThrow()
        last("POST", "/pending-writes/skills/w1/approve")

        ops().createWebhook(" CI ", "Build {ref}", "  ", "push, push", "log").getOrThrow()
        val (_, hook) = last("POST", "/webhooks")
        assertEquals("ci", hook["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("push"), (hook["events"] as JsonArray).map { it.jsonPrimitive.content })
        assertFalse(hook.containsKey("description"))

        assertEquals("01J8QK3ZR2W7M5N4P6T8V9X0PF", ops().profileId().getOrThrow())
    }
}

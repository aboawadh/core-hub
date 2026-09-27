package hub.core.android.parity

import hub.core.android.data.HubApis
import hub.core.android.ui.screens.ModelOps
import hub.core.android.ui.screens.ModelRules
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.ModelDefaults
import hub.core.client.model.ModelRef
import hub.core.client.model.Provider
import hub.core.client.model.ProviderHost
import hub.core.client.model.ProviderKind
import hub.core.client.model.ProviderPreset
import hub.core.client.model.ProviderScope
import kotlinx.coroutines.test.runTest
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

/** Models on the phone as on the web (batch 15): adding, editing and naming, where a list came from, the image models, what is sent. */
class ModelsExtrasTest {
    private val json = Serializer.kotlinxSerializationJson

    private fun model(id: String, provider: String = "p1", draws: Boolean = false, imageOnly: Boolean? = null, visible: Boolean = true, alias: String? = null) =
        """{"key":"x/$id","provider_id":"$provider","provider":"x","model":"$id","alias":${alias?.let { "\"$it\"" } ?: "null"},"kind":"chat","visible":$visible,
            "custom":false,"preview":false,"disabled":false,"context_window":null,"capabilities":${if (draws) "[\"image_output\"]" else "[\"tools\"]"},
            "image_only":${imageOnly ?: "null"},"pricing":null}"""

    private fun provider(
        id: String, slug: String = "openai", scope: String = "all", auth: String = "api_key", key: Boolean = true, draws: Boolean? = null,
        source: String? = null, reason: String? = null, status: String = "ready", baseUrl: String? = null, models: List<String> = emptyList(),
    ): Provider = json.decodeFromString(Provider.serializer(), providerJson(id, slug, scope, auth, key, draws, source, reason, status, baseUrl, models))

    private fun providerJson(
        id: String, slug: String, scope: String, auth: String, key: Boolean, draws: Boolean?,
        source: String?, reason: String?, status: String, baseUrl: String?, models: List<String>,
    ): String =
        """{"id":"$id","profile":"default","owner_id":"o","created_at":"2026-09-27T00:00:00Z","updated_at":"2026-09-27T00:00:00Z","slug":"$slug",
            "label":"${slug.replaceFirstChar { it.uppercase() }}","kind":"llm","scope":"$scope","builtin":false,"enabled":true,
            "api_key":${if (key) "\"[stored]\"" else "null"},"base_url":${baseUrl?.let { "\"$it\"" } ?: "null"},"api_mode":"chat_completions",
            "auth":{"kind":"$auth","signed_in":true},
            "catalogue":{"status":"$status","refreshed_at":null,"error":null,"refreshable":true,"source":${source?.let { "\"$it\"" } ?: "null"},
                "fallback_reason":${reason?.let { "\"$it\"" } ?: "null"}},
            ${draws?.let { "\"draws_images\":$it," } ?: ""}"visibility":{"mode":"all","models":[]},"models":[${models.joinToString(",")}]}"""

    private fun preset(id: String, key: String = "required", repeatable: Boolean = false, keyOnFile: String? = null) = json.decodeFromString(
        ProviderPreset.serializer(),
        """{"id":"$id","label":"$id","kind":"llm","api_mode":"chat_completions","base_url_required":false,"key":"$key","local":false,
            "repeatable":$repeatable,"sign_in":false,"base_url":null,"keys_url":null,"base_url_example":null${keyOnFile?.let { ",\"key_on_file\":[\"$it\"]" } ?: ""}}""",
    )

    @Test fun `a preset is offered once per scope, and a key on file makes the key optional`() {
        val added = listOf(provider("a", slug = "openai", scope = "all"))
        val presets = listOf(preset("openai"), preset("groq"), preset("custom-proxy", repeatable = true))
        assertEquals(listOf("groq", "custom-proxy"), ModelRules.offered(presets, added, ProviderScope.ALL).map { it.id })
        assertEquals(listOf("openai", "groq", "custom-proxy"), ModelRules.offered(presets, added, ProviderScope.PROFILE).map { it.id })
        val groqSpeech = preset("groq-stt", keyOnFile = "all")
        assertTrue("the account's key lends itself", ModelRules.ready(groqSpeech, "", "", ProviderScope.ALL))
        assertFalse("not in a scope it has no key in", ModelRules.ready(groqSpeech, "", "", ProviderScope.PROFILE))
    }

    @Test fun `a custom endpoint needs a name and an address, and sends a key only when typed`() {
        assertNull(ModelRules.custom(" ", ProviderKind.LLM, "http://x/v1", "", ProviderScope.ALL))
        assertNull(ModelRules.custom("Proxy", ProviderKind.LLM, "", "", ProviderScope.ALL))
        val body = ModelRules.custom(" Proxy ", ProviderKind.TTS, " http://x/v1 ", "", ProviderScope.PROFILE)!!
        assertEquals("Proxy", body.label)
        assertEquals(ProviderKind.TTS, body.kind)
        assertEquals("http://x/v1", body.baseUrl)
        assertEquals(ProviderScope.PROFILE, body.scope)
        assertNull(body.apiKey)
        assertNull(body.preset)
    }

    @Test fun `editing sends what changed, and an empty key field keeps the key`() {
        val p = provider("a", slug = "proxy", baseUrl = "http://old/v1")
        val unchanged = ModelRules.edit(p, "Proxy", "http://old/v1", " ", true)
        assertEquals(listOf(null, null, null, null), listOf(unchanged.label, unchanged.enabled, unchanged.apiKey, unchanged.baseUrl))
        assertTrue(unchanged.sendNull.isEmpty())
        val changed = ModelRules.edit(p, "My proxy", "", " sk-new ", false)
        assertEquals("My proxy", changed.label)
        assertEquals("sk-new", changed.apiKey)
        assertEquals(false, changed.enabled)
        assertTrue("an emptied address is cleared", hub.core.client.model.ProviderPatch.Clearable.BASE_URL in changed.sendNull)
    }

    @Test fun `a display name is set or cleared, and its model stays one path segment`() {
        assertEquals("Sonnet", ModelRules.alias(" Sonnet ").alias)
        assertTrue(hub.core.client.model.ModelPatch.Clearable.ALIAS in ModelRules.alias("  ").sendNull)
        assertEquals("anthropic%2Fclaude-sonnet-4.5", ModelRules.pathModel("anthropic/claude-sonnet-4.5"))
        assertEquals("gpt-5", ModelRules.pathModel("gpt-5"))
    }

    @Test fun `where a list came from is said`() {
        assertEquals(listOf(ModelRules.CatalogueNote.Fallback("sign-in lapsed")), ModelRules.catalogueNotes(provider("a", source = "fallback", reason = "sign-in lapsed")))
        assertEquals(listOf(ModelRules.CatalogueNote.FromAccount), ModelRules.catalogueNotes(provider("a", auth = "oauth", source = "provider")))
        assertTrue("a key provider's own list needs no words", ModelRules.catalogueNotes(provider("a", source = "provider")).isEmpty())
        assertEquals(listOf(ModelRules.CatalogueNote.Refreshing), ModelRules.catalogueNotes(provider("a", status = "loading")))
    }

    @Test fun `the images tab offers only models that draw, on providers that draw, image-only first`() {
        val key = provider(
            "p1", draws = true,
            models = listOf(model("gpt-5"), model("gemini-image", draws = true), model("gpt-image-2", draws = true, imageOnly = true), model("hidden-image", draws = true, visible = false)),
        )
        val subscription = provider("p2", slug = "codex", auth = "oauth", key = false, draws = false, models = listOf(model("gpt-image-2", provider = "p2", draws = true, imageOnly = true)))
        val older = provider("p3", slug = "old", models = listOf(model("dall-e-3", provider = "p3", draws = true, imageOnly = true)))
        assertEquals(listOf("x/gpt-image-2", "x/dall-e-3", "x/gemini-image"), ModelRules.imageModels(listOf(key, subscription, older)).map { it.key })
        val signedIn = provider("p4", slug = "codex", auth = "oauth", key = false, draws = true, models = listOf(model("gpt-image-2", provider = "p4", draws = true, imageOnly = true)))
        assertEquals("Codex", ModelRules.viaSubscription(signedIn.models[0], listOf(signedIn)))
        assertNull(ModelRules.viaSubscription(key.models[1], listOf(key)))
        assertEquals("never an image-only or hidden model for chat", listOf("gpt-5", "gemini-image"), ModelRules.chatModels(listOf(key)).map { it.model })
    }

    @Test fun `a loopback address on a containerised hub is called out with the host alias`() {
        val docker = ProviderHost(containerized = true, loopbackAlias = "host.docker.internal")
        assertEquals("http://host.docker.internal:1234/v1", ModelRules.loopbackSuggestion("http://127.0.0.1:1234/v1", docker))
        assertEquals("http://host.docker.internal:11434", ModelRules.loopbackSuggestion("http://localhost:11434/", docker))
        assertNull(ModelRules.loopbackSuggestion("http://192.168.1.5:1234/v1", docker))
        assertNull(ModelRules.loopbackSuggestion("http://127.0.0.1:1234/v1", ProviderHost(containerized = false, loopbackAlias = "x")))
        assertEquals(JsonNull, ModelRules.speechSetting(" "))
        assertEquals(JsonPrimitive("ar-EG"), ModelRules.speechSetting(" ar-EG "))
        assertTrue(ModelRules.languageCode("ar-EG"))
        assertFalse(ModelRules.languageCode("arabic!"))
    }

    // ------------------------------------------------------------------ what is sent

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private val defaults = """{"default":{"provider_id":"p1","model":"a"},"image":null,"fallbacks":[],"auxiliary":{"tasks":[],"assignments":{}},"inherited":["default"]}"""
    private val speech = """{"stt":{"ready":false,"providers":[],"active_provider_id":null,"reason":null},"tts":{"ready":true,"providers":[],"active_provider_id":null,"reason":null}}"""

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                val body = when {
                    path.endsWith("/models/defaults") -> defaults
                    path.endsWith("/models/speech") -> speech
                    path.contains("/models/providers/p1/models/") -> model("anthropic/claude", alias = "Claude")
                    path.endsWith("/models/providers/p1") -> providerJson("p1", "openai", "all", "api_key", false, null, null, null, "ready", null, emptyList())
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun ops() = ModelOps({ HubApis(server.url("/").toString().trimEnd('/'), OkHttpClient()) }, "work")

    private fun sent(): JsonObject = json.parseToJsonElement(requests.last().body.readUtf8()).jsonObject

    @Test fun `a display name goes to the model as one segment, and a cleared key is sent empty`() = runTest {
        val p = provider("p1")
        assertTrue(ops().setAlias(p, "anthropic/claude", "Claude").isSuccess)
        assertTrue(requests.last().requestUrl!!.encodedPath.endsWith("/models/providers/p1/models/anthropic%252Fclaude"))
        assertEquals("PUT", requests.last().method)
        assertEquals("Claude", sent()["alias"]!!.jsonPrimitive.content)

        assertTrue(ops().setAlias(p, "gpt-5", " ").isSuccess)
        assertEquals(JsonNull, sent()["alias"])

        assertTrue(ops().clearKey(p).isSuccess)
        assertEquals("", sent()["api_key"]!!.jsonPrimitive.content)
    }

    @Test fun `the image model goes back to the default profile's, a role is set, and a chain keeps an inherited chat model`() = runTest {
        assertTrue(ops().setImage(null).isSuccess)
        assertEquals(JsonNull, sent()["image"])

        assertTrue(ops().setAssignment("title", ModelRef("p1", "mini")).isSuccess)
        assertEquals("""{"assignments":{"title":{"provider_id":"p1","model":"mini"}}}""", requests.last().body.readUtf8())

        val inherited = json.decodeFromString(ModelDefaults.serializer(), defaults)
        assertTrue(ops().setFallbacks(listOf(ModelRef("p1", "b")), inherited).isSuccess)
        assertEquals("a", sent()["default"]!!.jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test fun `a speech setting is kept in the provider's settings, and empty is the provider's default`() = runTest {
        assertTrue(ops().setSpeech("stt1", "language", "ar-EG").isSuccess)
        assertEquals("""{"providers":[{"id":"stt1","settings":{"language":"ar-EG"}}]}""", requests.last().body.readUtf8())
        assertTrue(ops().setSpeech("stt1", "model", "").isSuccess)
        assertEquals("""{"providers":[{"id":"stt1","settings":{"model":null}}]}""", requests.last().body.readUtf8())
    }
}

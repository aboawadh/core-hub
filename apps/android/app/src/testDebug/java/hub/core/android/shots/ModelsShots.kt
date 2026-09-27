package hub.core.android.shots

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hub.core.android.AppLanguage
import hub.core.android.MainActivity
import hub.core.android.data.StoredSession
import hub.core.android.data.StoredUser
import hub.core.android.data.TokenKind
import hub.core.android.graph
import hub.core.android.repoRoot
import hub.core.android.ui.theme.ThemeChoice
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Apps batch 15: Models as on the web — the providers with where each list came from, one provider
 * opened (its notes checked; a sheet is not in the picture), the defaults with the auxiliary roles, speech and the image model — against the demo hub,
 * light English and dark Arabic, photographed to `apps/android/app/build/shots/models/android-<page>-<theme>-<lang>.png`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w440dp-h956dp-xxhdpi", application = ShotsApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelsShots {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var hub: DemoHub
    private val out = File(repoRoot, "apps/android/app/build/shots/models").apply { mkdirs() }

    private val stamp = """"owner_id":"u1","created_at":"2026-09-26T08:00:00Z","updated_at":"2026-09-26T08:00:00Z""""
    private fun model(provider: String, slug: String, id: String, alias: String? = null, caps: String = "\"tools\"", imageOnly: Boolean = false, visible: Boolean = true) =
        """{"key":"$slug/$id","provider_id":"$provider","provider":"$slug","model":"$id","alias":${alias?.let { "\"$it\"" } ?: "null"},"kind":"chat","visible":$visible,
            "custom":false,"preview":false,"disabled":false,"context_window":null,"capabilities":[$caps],"image_only":$imageOnly,"pricing":null}"""

    private val providers = """{"items":[
        {"id":"01K5DM00000000000000000PV1","profile":"default",$stamp,"slug":"openai-codex","label":"ChatGPT","kind":"llm","scope":"all","builtin":true,"enabled":true,
         "api_key":null,"base_url":null,"api_mode":"responses","auth":{"kind":"oauth","signed_in":true},
         "catalogue":{"status":"ready","refreshed_at":"2026-09-27T05:00:00Z","error":null,"refreshable":true,"source":"provider","fallback_reason":null},
         "draws_images":true,"visibility":{"mode":"all","models":[]},
         "models":[${model("01K5DM00000000000000000PV1", "openai-codex", "gpt-6-sol", "GPT-6 Sol")},${model("01K5DM00000000000000000PV1", "openai-codex", "gpt-5.5")},
                   ${model("01K5DM00000000000000000PV1", "openai-codex", "gpt-image-2", caps = "\"image_output\"", imageOnly = true)}]},
        {"id":"01K5DM00000000000000000PV2","profile":"default",$stamp,"slug":"nous","label":"Nous Portal","kind":"llm","scope":"all","builtin":true,"enabled":true,
         "api_key":null,"base_url":null,"api_mode":"chat_completions","auth":{"kind":"oauth","signed_in":true},
         "catalogue":{"status":"ready","refreshed_at":"2026-09-27T05:00:00Z","error":null,"refreshable":true,"source":"fallback","fallback_reason":"the sign-in lapsed"},
         "draws_images":false,"visibility":{"mode":"all","models":[]},"models":[${model("01K5DM00000000000000000PV2", "nous", "hermes-4-405b")}]},
        {"id":"01K5DM00000000000000000PV3","profile":"work",$stamp,"slug":"lmstudio","label":"LM Studio","kind":"llm","scope":"profile","builtin":false,"enabled":true,
         "api_key":"[stored]","base_url":"http://host.docker.internal:1234/v1","api_mode":"chat_completions","auth":{"kind":"none","signed_in":true},
         "catalogue":{"status":"ready","refreshed_at":"2026-09-27T05:00:00Z","error":null,"refreshable":true,"source":"provider","fallback_reason":null},
         "draws_images":false,"visibility":{"mode":"all","models":[]},"models":[${model("01K5DM00000000000000000PV3", "lmstudio", "qwen2.5-coder-7b", "Qwen coder")}]},
        {"id":"01K5DM00000000000000000PV4","profile":"default",$stamp,"slug":"elevenlabs","label":"ElevenLabs","kind":"tts","scope":"all","builtin":true,"enabled":true,
         "api_key":"[stored]","base_url":null,"api_mode":"native","auth":{"kind":"api_key","signed_in":true},
         "catalogue":{"status":"unsupported","refreshed_at":null,"error":null,"refreshable":false,"source":null,"fallback_reason":null},
         "visibility":{"mode":"all","models":[]},"models":[]}]}"""

    private val answers = mapOf(
        "models.listProviders" to providers,
        "models.getDefaults" to """{"inherited":["title"],"default":{"provider_id":"01K5DM00000000000000000PV1","model":"gpt-6-sol"},
            "fallbacks":[{"provider_id":"01K5DM00000000000000000PV1","model":"gpt-5.5"},{"provider_id":"01K5DM00000000000000000PV3","model":"qwen2.5-coder-7b"}],
            "image":{"provider_id":"01K5DM00000000000000000PV1","model":"gpt-image-2"},
            "auxiliary":{"tasks":[{"key":"title","label":{"ar":"تسمية المحادثات","en":"Naming chats"}},{"key":"summary","label":{"ar":"التلخيص","en":"Summaries"}}],
                "assignments":{"title":{"provider_id":"01K5DM00000000000000000PV3","model":"qwen2.5-coder-7b"}}}}""",
        "models.getSpeech" to """{"stt":{"active_provider_id":null,"ready":false,"reason":"No dictation provider is chosen.","providers":[]},
            "tts":{"active_provider_id":"01K5DM00000000000000000PV4","ready":true,"reason":null,"providers":[
              {"id":"01K5DM00000000000000000PV4","slug":"elevenlabs","label":"ElevenLabs","kind":"tts","configured":true,"api_key":"[stored]",
               "settings":{"model":"eleven_v3","language":"ar","base_url":null,"voice":"Rachel"}}]}}""",
    )

    @Before fun start() {
        hub = DemoHub(answers).start()
    }

    @After fun stop() {
        hub.missed.sorted().forEach { println("demo hub: no answer for $it") }
        hub.stop()
    }

    @Test fun lightEnglish() = shoot(ThemeChoice.LIGHT, AppLanguage.EN)
    @Test fun darkArabic() = shoot(ThemeChoice.DARK, AppLanguage.AR)

    private fun shoot(theme: ThemeChoice, language: AppLanguage) {
        val graph = ApplicationProvider.getApplicationContext<android.content.Context>().graph
        graph.prefs.language = language
        graph.prefs.setTheme(theme)
        graph.store.save(
            StoredSession(
                hub = hub.base, kind = TokenKind.APP, accessToken = "demo",
                user = StoredUser("01K5DM00000000000000000001", "sara", "Sara", "owner", listOf("work", "personal"), "work"),
                profile = "work",
            ),
        )
        val name = "${theme.name.lowercase()}-${if (language == AppLanguage.AR) "ar" else "en"}"
        open("/settings/models") { activity ->
            waitFor("provider.nous")
            save(activity, "providers-$name")
            compose.onNodeWithTag("provider.nous").performClick()
            // The provider opens in a sheet (a window of its own, not in the picture): its fallback note is there.
            waitFor("provider.catalogue_fallback")
        }
        open("/settings/models") { _ ->
            waitFor("provider.openai-codex")
            compose.onNodeWithTag("provider.openai-codex").performClick()
            waitFor("provider.catalogue_account")
        }
        open("/settings/models") { activity ->
            waitFor("models.tab.defaults")
            compose.onNodeWithTag("models.tab.defaults", useUnmergedTree = true).performClick()
            waitFor("defaults.aux.title")
            save(activity, "defaults-$name")
            compose.onNodeWithTag("models.tab.speech", useUnmergedTree = true).performClick()
            waitFor("speech.tts.language")
            save(activity, "speech-$name")
            compose.onNodeWithTag("models.tab.images", useUnmergedTree = true).performClick()
            waitFor("images.model")
            save(activity, "images-$name")
        }
    }

    private fun open(path: String, block: (Activity) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).setData(Uri.parse("corehub://open$path"))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var activity: Activity? = null
            scenario.onActivity { activity = it }
            block(activity!!)
        }
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun save(activity: Activity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, "android-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.ModelKind
import hub.core.client.model.SpeechProvider
import hub.core.client.model.VoiceListSource
import kotlinx.coroutines.launch
import java.util.Locale

private enum class SpeechPick { MODEL, LANGUAGE, VOICE }

/**
 * Speech: which provider listens and which speaks, and for each its model (the provider's own list,
 * or typed), its language (detected, or chosen) and — for speaking — its voice, searched in every
 * language and heard before it is kept.
 */
@Composable
internal fun SpeechTab(ops: ModelOps, isAdmin: Boolean) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val appLocale = LocalConfiguration.current.locales[0]
    val load = rememberLoad(ops.profile, "speech") { ops.speech().getOrThrow() }
    var error by remember { mutableStateOf<HubError?>(null) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Triple<SpeechProvider, ModelKind, SpeechPick>?>(null) }
    val auto = stringResource(R.string.models_language_auto)
    val providerDefault = stringResource(R.string.models_provider_default)
    LoadView(load) { speech ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag("models.speech"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorNotice(error)
            listOf(ModelKind.STT to speech.stt, ModelKind.TTS to speech.tts).forEach { (kind, s) ->
                val side = kind.value
                val active = s.providers.firstOrNull { it.id == s.activeProviderId }
                GroupedList(title = stringResource(if (kind == ModelKind.STT) R.string.models_stt else R.string.models_tts)) {
                    Custom {
                        Box {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(active?.label ?: stringResource(R.string.models_none_chosen), fontSize = FontTokens.sizeMd.sp)
                                    if (!s.ready) Text(s.reason ?: stringResource(R.string.models_not_ready), fontSize = FontTokens.sizeXs.sp, color = t.warningSoftText)
                                }
                                Badge(stringResource(if (s.ready) R.string.models_ready else R.string.models_not_ready), tone = if (s.ready) BadgeTone.Success else BadgeTone.Warning, dot = true)
                                if (isAdmin && s.providers.size > 1) HubIconButton(Lucide.ChevronsUpDown, stringResource(R.string.models_change), { choosing = side }, size = 32.dp, iconSize = 16.dp, modifier = Modifier.testTag("speech.$side.change"))
                            }
                            HubMenu(choosing == side, { choosing = null }) {
                                s.providers.forEach { p ->
                                    MenuItem(p.label, {
                                        choosing = null
                                        scope.launch {
                                            (if (kind == ModelKind.STT) ops.useSpeech(stt = p.id) else ops.useSpeech(tts = p.id)).onFailure { error = it as HubError }
                                            load.reload()
                                        }
                                    }, checked = p.id == s.activeProviderId)
                                }
                            }
                        }
                    }
                    if (active != null) {
                        Item(
                            stringResource(R.string.models_speech_model), value = active.settings.model ?: providerDefault, chevron = isAdmin, icon = Lucide.Cpu, tag = "speech.$side.model",
                            onClick = if (isAdmin) ({ picking = Triple(active, kind, SpeechPick.MODEL) }) else null,
                        )
                        Item(
                            stringResource(if (kind == ModelKind.STT) R.string.models_language_stt else R.string.models_language_tts),
                            value = active.settings.language?.takeIf { it.isNotBlank() }?.let { languageName(it, appLocale) } ?: auto,
                            chevron = isAdmin, icon = Lucide.Globe, tag = "speech.$side.language",
                            onClick = if (isAdmin) ({ picking = Triple(active, kind, SpeechPick.LANGUAGE) }) else null,
                        )
                        if (kind == ModelKind.TTS) {
                            Item(
                                stringResource(R.string.models_voice), value = active.settings.voice ?: "—", chevron = isAdmin, icon = Lucide.Volume2, tag = "speech.voice",
                                onClick = if (isAdmin) ({ picking = Triple(active, kind, SpeechPick.VOICE) }) else null,
                            )
                        }
                    }
                }
                Text(stringResource(if (kind == ModelKind.STT) R.string.models_hint_stt else R.string.models_hint_tts), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            }
            if (speech.stt.providers.isEmpty() && speech.tts.providers.isEmpty()) {
                NoticeBox(stringResource(R.string.models_speech_none), BadgeTone.Info)
            }
        }
        picking?.let { (provider, kind, what) ->
            val done = { picking = null; load.reload() }
            when (what) {
                SpeechPick.MODEL -> SpeechModelPicker(ops, provider, kind, onPicked = done, onDismiss = { picking = null })
                SpeechPick.LANGUAGE -> SpeechLanguagePicker(ops, provider, kind, onPicked = done, onDismiss = { picking = null })
                SpeechPick.VOICE -> VoicePicker(ops, provider, onPicked = done, onDismiss = { picking = null })
            }
        }
    }
}

private fun languageName(code: String, locale: Locale): String =
    Locale.forLanguageTag(code).getDisplayName(locale).takeIf { it.isNotBlank() && it != code } ?: code

/** A row that keeps what was typed when the list does not have it. */
@Composable
private fun TypedRow(value: String, tag: String, onPick: () -> Unit) {
    GroupedList { Item(stringResource(R.string.models_use_typed, value), icon = Lucide.Pencil, tag = tag, onClick = onPick) }
}

@Composable
private fun SpeechModelPicker(ops: ModelOps, provider: SpeechProvider, kind: ModelKind, onPicked: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val models = rememberLoad(provider.id, kind, "speech-models") { ops.speechModels(provider.id, kind).getOrThrow() }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<HubError?>(null) }
    fun choose(value: String) = scope.launch { ops.setSpeech(provider.id, "model", value).onSuccess { onPicked() }.onFailure { error = it as HubError } }
    HubSheet(onDismiss = onDismiss, title = stringResource(R.string.models_speech_model_of, provider.label)) {
        ErrorNotice(error)
        HubTextField(query, { query = it }, placeholder = stringResource(R.string.models_model_search), leadingIcon = Lucide.Search, size = ControlSize.Md, mono = true, fieldTag = "speech.model.search")
        LoadView(models) { all ->
            val q = query.trim()
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupedList {
                    Item(stringResource(R.string.models_provider_default), icon = Lucide.RotateCcw, value = if (provider.settings.model == null) "✓" else null, tag = "speech.model.default", onClick = { choose("") })
                }
                if (q.isNotEmpty() && all.none { it.model == q }) TypedRow(q, "speech.model.typed") { choose(q) }
                if (all.isEmpty()) Text(stringResource(R.string.models_speech_models_none), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
                val shown = all.filter { q.isEmpty() || it.model.contains(q, true) || it.alias?.contains(q, true) == true }
                if (shown.isNotEmpty()) GroupedList {
                    shown.forEach { m ->
                        Item(
                            m.alias ?: m.model, subtitle = m.model.takeIf { m.alias != null }, value = if (m.model == provider.settings.model) "✓" else null,
                            tag = "speech.model.${m.model}", onClick = { choose(m.model) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeechLanguagePicker(ops: ModelOps, provider: SpeechProvider, kind: ModelKind, onPicked: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<HubError?>(null) }
    fun choose(value: String) = scope.launch { ops.setSpeech(provider.id, "language", value).onSuccess { onPicked() }.onFailure { error = it as HubError } }
    val current = provider.settings.language.orEmpty()
    HubSheet(onDismiss = onDismiss, title = stringResource(if (kind == ModelKind.STT) R.string.models_language_stt else R.string.models_language_tts)) {
        ErrorNotice(error)
        HubTextField(query, { query = it }, placeholder = stringResource(R.string.models_language_search), leadingIcon = Lucide.Search, size = ControlSize.Md, fieldTag = "speech.language.search")
        val q = query.trim()
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupedList {
                Item(stringResource(R.string.models_language_auto), icon = Lucide.RotateCcw, value = if (current.isEmpty()) "✓" else null, tag = "speech.language.auto", onClick = { choose("") })
            }
            if (q.isNotEmpty() && q !in ModelRules.popularLanguages && ModelRules.languageCode(q)) TypedRow(q, "speech.language.typed") { choose(q) }
            val codes = ModelRules.popularLanguages.filter { q.isEmpty() || it.contains(q, true) || languageName(it, locale).contains(q, true) }
            if (codes.isNotEmpty()) GroupedList(title = stringResource(R.string.models_lang_popular)) {
                codes.forEach { code ->
                    Item(languageName(code, locale), subtitle = code, value = if (current == code) "✓" else null, tag = "speech.language.$code", onClick = { choose(code) })
                }
            }
        }
    }
}

/** Every voice the provider offers, the person's languages first, searchable, heard before it is kept; a voice id can be typed. */
@Composable
private fun VoicePicker(ops: ModelOps, provider: SpeechProvider, onPicked: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val voices = rememberLoad(provider.id, "voices") { ops.voices(provider.id, provider.settings.model).getOrThrow() }
    var query by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val app = context.graph.prefs.effectiveLanguage
    val phone = LocalConfiguration.current.locales[0].language
    val preferred = listOf(app.tag) + listOf(phone).filter { it != app.tag } + listOf("en")
    val fallbackSample = stringResource(R.string.models_voice_sample)
    val player = remember { android.media.MediaPlayer() }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    fun choose(value: String) = scope.launch { ops.setVoice(provider.id, value).onSuccess { onPicked() }.onFailure { error = it as HubError } }
    HubSheet(onDismiss = onDismiss, title = stringResource(R.string.models_voice_of, provider.label)) {
        ErrorNotice(error)
        HubTextField(query, { query = it }, placeholder = stringResource(R.string.models_voice_search), leadingIcon = Lucide.Search, size = ControlSize.Md, fieldTag = "voice.search")
        LoadView(voices) { answer ->
            val all = answer.items
            val q = query.trim()
            val list = ModelRules.voices(all, preferred, query)
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (answer.source == VoiceListSource.DOCUMENTED) NoticeBox(stringResource(R.string.models_voices_documented), BadgeTone.Info)
                if (answer.source == VoiceListSource.NONE) NoticeBox(stringResource(R.string.models_voices_none), BadgeTone.Info)
                if (q.isNotEmpty() && all.none { it.id == q }) TypedRow(q, "voice.typed") { choose(q) }
                if (list.isNotEmpty()) GroupedList {
                    list.forEach { v ->
                        Item(
                            v.name, subtitle = listOfNotNull(v.language, v.description).joinToString(" · ").ifEmpty { null }, tag = "voice.${v.id}",
                            trailing = {
                                HubIconButton(if (playing == v.id) Lucide.CircleStop else Lucide.Play, stringResource(R.string.models_voice_preview), {
                                    playing = v.id
                                    scope.launch {
                                        ops.preview(provider.id, v.id, ModelRules.sample(v.language, fallbackSample), v.language)
                                            .onSuccess { file ->
                                                runCatching {
                                                    player.reset(); player.setDataSource(file.absolutePath); player.prepare(); player.start()
                                                    player.setOnCompletionListener { playing = null }
                                                }.onFailure { playing = null }
                                            }
                                            .onFailure { error = it as HubError; playing = null }
                                    }
                                }, size = 32.dp, iconSize = 16.dp, modifier = Modifier.testTag("voice.${v.id}.play"))
                            },
                            value = if (v.id == provider.settings.voice) "✓" else null,
                            onClick = { choose(v.id) },
                        )
                    }
                }
                if (list.isEmpty() && answer.source != VoiceListSource.NONE) Text(stringResource(R.string.models_voice_none), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
            }
        }
    }
}

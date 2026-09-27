package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.errorText
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.Chip
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.ModelRef
import hub.core.client.model.Provider
import hub.core.client.model.ProviderKind
import hub.core.client.model.ProviderProbe
import hub.core.client.model.ProviderProbeResult
import kotlinx.coroutines.launch

/*
 * «Fetch» in Add provider (the web's AddProviderDialog, `models.probeProvider`): the endpoint is
 * asked for its models before anything is saved, and what came back is shown — the endpoint's own
 * words when it refused, never an empty list drawn as success. A chat model picked from the answer
 * becomes the default once the provider is added (registered first, as the web does). Since
 * 2026-09-27.
 */

object ProbeRules {
    /** What is sent: the preset when one was chosen, the address, and a key only when typed. */
    fun request(preset: String?, baseUrl: String, key: String, kind: ProviderKind?): ProviderProbe? {
        val url = baseUrl.trim()
        if (url.isEmpty() && preset == null) return null
        return ProviderProbe(
            preset = preset, baseUrl = url.takeIf { it.isNotEmpty() }, apiKey = key.trim().takeIf { it.isNotEmpty() },
            kind = kind,
        )
    }

    /** The chat models of an answer (a model that only draws is never a chat default, §87). */
    fun chatModels(result: ProviderProbeResult): List<String> = if (!result.ok) emptyList() else result.models.filter { it.imageOnly != true }.map { it.id }
}

/** The probe's state in the form: the models found, the one picked, and the answer's words. */
class ProbeState {
    var models by mutableStateOf<List<String>>(emptyList())
    var picked by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
    var busy by mutableStateOf(false)
}

@Composable
fun rememberProbe(vararg keys: Any?): ProbeState = remember(*keys) { ProbeState() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProbePart(ops: ModelOps, state: ProbeState, request: ProviderProbe?, chat: Boolean) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val failedWords = stringResource(R.string.models_fetch_failed)
    val emptyWords = stringResource(R.string.models_fetch_empty)
    val errorWords: @Composable (HubError) -> String = { errorText(it) }
    var hubError by remember { mutableStateOf<HubError?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HubButton(
            if (state.busy) stringResource(R.string.models_fetching) else stringResource(R.string.models_fetch),
            {
                val body = request ?: return@HubButton
                state.busy = true
                state.message = null
                hubError = null
                scope.launch {
                    ops.probe(body).onSuccess { result ->
                        val found = ProbeRules.chatModels(result)
                        state.models = found
                        state.failed = !result.ok || found.isEmpty()
                        state.message = when {
                            !result.ok -> result.message ?: failedWords
                            found.isEmpty() -> emptyWords
                            else -> null
                        }
                        state.picked = if (chat) found.firstOrNull() else null
                    }.onFailure { hubError = it as HubError; state.failed = true }
                    state.busy = false
                }
            },
            kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.RefreshCw, loading = state.busy, enabled = request != null,
            modifier = Modifier.testTag("provider.fetch"),
        )
        hubError?.let { Text(errorWords(it), fontSize = FontTokens.sizeXs.sp, color = t.danger) }
        state.message?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = if (state.failed) t.danger else t.textMuted, modifier = Modifier.testTag("provider.fetch.message")) }
        if (state.models.isNotEmpty()) {
            Text(stringResource(R.string.models_fetched, state.models.size.toString()), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            if (chat) {
                Text(stringResource(R.string.models_default_model), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.models.take(40).forEach { m -> Chip(m, state.picked == m, { state.picked = if (state.picked == m) null else m }, size = ControlSize.Sm) }
                }
            }
        }
    }
}

/** After the provider is added: the picked chat model is registered and made the default. */
suspend fun ModelOps.applyPicked(provider: Provider, state: ProbeState): Result<*>? {
    val model = state.picked ?: return null
    if (provider.kind != ProviderKind.LLM) return null
    return registerModel(provider, model).mapCatching { setDefault(ModelRef(providerId = provider.id, model = model)).getOrThrow() }
}


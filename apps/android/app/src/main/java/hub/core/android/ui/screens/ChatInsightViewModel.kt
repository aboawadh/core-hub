package hub.core.android.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hub.core.android.AppGraph
import hub.core.android.chat.ChatInsight
import hub.core.android.chat.ChatInsightApi
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.android.graph
import hub.core.android.realtime.SESSIONS_NAMESPACE
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Run
import hub.core.client.model.RunStatus
import hub.core.client.model.Subagent
import hub.core.client.model.SubagentStatus
import hub.core.client.model.SubagentSupport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which of the insight's sheets is open. */
enum class InsightSheet { CONTEXT, RUNS, SUBAGENTS, CHANGES, FILES }

data class InsightUi(
    val sheet: InsightSheet? = null,
    val subagents: List<Subagent> = emptyList(),
    val support: SubagentSupport = SubagentSupport.NONE,
    val subagentsLoaded: Boolean = false,
    val subagentsError: HubError? = null,
    /** The runs of the chat that ended while it was open, for the ring's estimate and the changes' revision. */
    val ended: List<Run> = emptyList(),
) {
    val running: Int get() = subagents.count { it.status == SubagentStatus.RUNNING }
}

/**
 * The chat's insight (apps batch 6): its subagents, kept live while the chat is open (the top bar
 * says when some are running), the runs that ended here, and which sheet is open. Shared by the top
 * bar and the chat's «⋮» (the same key, the same instance).
 */
class ChatInsightViewModel(
    private val graph: AppGraph,
    val sessionId: String,
    val profile: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(InsightUi())
    val ui: StateFlow<InsightUi> = _ui.asStateFlow()
    val api: ChatInsightApi? get() = graph.store.current?.let(graph::apis)?.let(::ChatInsightApi)
    private var poll: Job? = null
    private val json = Serializer.kotlinxSerializationJson

    init {
        viewModelScope.launch {
            graph.realtime.events.collect { envelope ->
                if (envelope.namespace != SESSIONS_NAMESPACE) return@collect
                when (envelope.event) {
                    in SUBAGENT_EVENTS -> {
                        val subagent = envelope.payload["subagent"]
                            ?.let { runCatching { json.decodeFromJsonElement(Subagent.serializer(), it) }.getOrNull() }
                            ?: return@collect
                        if (subagent.sessionId != sessionId) return@collect
                        _ui.update {
                            it.copy(
                                subagents = ChatInsight.upsert(it.subagents, subagent),
                                support = if (it.support == SubagentSupport.NONE) SubagentSupport.OBSERVE else it.support,
                            )
                        }
                        keepPolling()
                    }
                    "run.completed", "run.failed", "run.cancelled" -> {
                        val run = envelope.payload["run"]
                            ?.let { runCatching { json.decodeFromJsonElement(Run.serializer(), it) }.getOrNull() }
                            ?: return@collect
                        if (run.sessionId == sessionId) _ui.update { ui -> ui.copy(ended = ui.ended.filter { it.id != run.id } + run) }
                    }
                }
            }
        }
        refreshSubagents()
    }

    fun show(sheet: InsightSheet?) = _ui.update { it.copy(sheet = sheet) }

    fun refreshSubagents() {
        val api = api ?: return
        viewModelScope.launch {
            hubCall { api.subagents(sessionId, profile) }
                .onSuccess { list -> _ui.update { it.copy(subagents = list.items, support = list.support, subagentsLoaded = true, subagentsError = null) } }
                .onFailure { e -> _ui.update { it.copy(subagentsLoaded = true, subagentsError = e as HubError) } }
            keepPolling()
        }
    }

    /** A missed event is caught up with every 15 seconds while one runs (as the web). */
    private fun keepPolling() {
        poll?.cancel()
        if (_ui.value.running == 0) return
        poll = viewModelScope.launch {
            delay(RUNNING_POLL_MS)
            refreshSubagents()
        }
    }

    /** Stops one subagent; the list takes the hub's answer. */
    fun interrupt(id: String, onFailed: (HubError) -> Unit) {
        val api = api ?: return
        viewModelScope.launch {
            hubCall { api.interrupt(sessionId, profile, id) }
                .onSuccess { subagent -> _ui.update { it.copy(subagents = ChatInsight.upsert(it.subagents, subagent)) } }
                .onFailure { e -> onFailed(e as HubError); refreshSubagents() }
        }
    }

    /** Hands a subagent a note: queued, rejected, or why it failed. */
    fun steer(id: String, text: String, onResult: (Boolean?, HubError?) -> Unit) {
        val api = api ?: return
        viewModelScope.launch {
            hubCall { api.steer(sessionId, profile, id, text) }
                .onSuccess { onResult(it.status == hub.core.client.model.SubagentSteerResult.Status.QUEUED, null) }
                .onFailure { onResult(null, it as HubError) }
        }
    }

    companion object {
        val SUBAGENT_EVENTS = setOf("subagent.started", "subagent.updated", "subagent.completed")
        const val RUNNING_POLL_MS = 15_000L

        /** The ended runs among [runs] (and the live one's status), for [ChatInsight.changesRevision]. */
        fun endedIds(runs: List<Run>): List<String> =
            runs.filter { it.status == RunStatus.SUCCEEDED || it.status == RunStatus.FAILED || it.status == RunStatus.CANCELLED }.map { it.id }
    }
}

/** The chat's insight view model, shared by its top bar and its menu. */
@Composable
fun rememberChatInsightViewModel(sessionId: String, profile: String): ChatInsightViewModel {
    val context = LocalContext.current
    return viewModel(key = "insight:$sessionId:$profile") { ChatInsightViewModel(context.graph, sessionId, profile) }
}

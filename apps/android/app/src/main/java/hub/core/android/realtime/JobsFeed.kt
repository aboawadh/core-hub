package hub.core.android.realtime

import hub.core.client.infrastructure.Serializer
import hub.core.client.model.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/*
 * `/rt/jobs` (events/README.md), heard for the whole signed-in session since 2026-09-27, as the
 * web's `useJobs` and iOS's JobsFeed: every job of the profiles joined — an agent installing, a
 * skill imported, a channel pairing, a restart, an export — as it moves, and `agent.updated` when an
 * agent's registry entry changed. A page following a job wakes on its event instead of waiting for
 * the next poll; the poll stays as the fallback for a dropped connection. The Agents page reads
 * again on `agent.updated`.
 */
object JobsFeed {
    private val heard = MutableSharedFlow<String>(extraBufferCapacity = 256)
    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    /** The latest state of each job heard, by id. */
    val jobs: StateFlow<Map<String, Job>> = _jobs.asStateFlow()
    private val _agentsRevision = MutableStateFlow(0)
    /** Bumped on every `agent.updated`: the Agents page reads again. */
    val agentsRevision: StateFlow<Int> = _agentsRevision.asStateFlow()

    /** One envelope of `/rt/jobs`; anything else is ignored. */
    fun receive(envelope: Envelope) {
        if (envelope.namespace != JOBS_NAMESPACE) return
        if (envelope.event == "agent.updated") {
            _agentsRevision.value += 1
            return
        }
        if (!envelope.event.startsWith("job.")) return
        val job = envelope.payload["job"]?.let { runCatching { Serializer.kotlinxSerializationJson.decodeFromJsonElement(Job.serializer(), it) }.getOrNull() }
            ?: return
        _jobs.value = _jobs.value + (job.id to job)
        heard.tryEmit(job.id)
    }

    /** Waits until an event about the job arrives or [timeoutMs] passes, whichever is first. */
    suspend fun wait(jobId: String, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { heard.first { it == jobId } }
    }

    /** Forgets what was heard (sign-out). */
    fun reset() {
        _jobs.value = emptyMap()
    }
}

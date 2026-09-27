package hub.core.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hub.core.android.AppGraph
import hub.core.android.chat.AttachmentTray
import hub.core.android.chat.AttachmentUploader
import hub.core.android.chat.HubAttachmentBackend
import hub.core.android.chat.mimeOf
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.android.rooms.RoomActions
import hub.core.android.rooms.RoomManage
import hub.core.android.rooms.RoomMentions
import hub.core.android.rooms.RoomReducer
import hub.core.android.rooms.RoomState
import hub.core.client.model.Approval
import hub.core.client.model.ApprovalDecision
import hub.core.client.model.ApprovalResponse
import hub.core.client.model.Member
import hub.core.client.model.RoomMessageCreate
import hub.core.client.model.RoomPatch
import hub.core.client.model.Seat
import hub.core.client.model.SeatConfig
import hub.core.client.model.SeatPatch
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

data class RoomUi(
    val state: RoomState = RoomState(),
    val loading: Boolean = true,
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val sending: Boolean = false,
    val error: HubError? = null,
    /** The invite link the manager just made (`rotateInviteCode`). */
    val inviteLink: String? = null,
    /** You left the room, or were removed: the screen goes back to the draft. */
    val gone: Boolean = false,
)

/**
 * One open room (DECISIONS §69): HTTP for the room and its messages, `/rt/rooms` for everything
 * after (joined while the screen shows it), and the reply of each seat streaming into its
 * message. What is sent carries its mentions as structured ids and its files as blocks
 * (contract decision §99).
 */
class RoomViewModel(
    private val graph: AppGraph,
    val roomId: String,
    val profile: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(RoomUi())
    val ui: StateFlow<RoomUi> = _ui.asStateFlow()
    private val apis get() = graph.store.current?.let(graph::apis)

    /** The signed-in person: their own messages are on the right. */
    val me: String? get() = graph.store.current?.user?.id

    /** Files for the next message, uploaded into the room's profile as soon as they are picked. */
    val tray = AttachmentTray(
        viewModelScope,
        upload = { file ->
            val api = apis ?: throw HubError(401, "unauthorized", null)
            AttachmentUploader(HubAttachmentBackend(api, profile)).upload(file, mimeOf(file))
        },
        discard = { attachment -> hubCall { apis?.sessions?.sessionsDeleteAttachment(attachment.profile, attachment.id) } },
    )

    private var typingSentAt = 0L

    init {
        viewModelScope.launch {
            graph.realtime.events.collect { envelope ->
                _ui.update { it.copy(state = RoomReducer.apply(it.state, envelope)) }
                if (_ui.value.state.deleted) _ui.update { it.copy(gone = true) }
                // Clearing the context resets the summary and the token count: read the room again.
                else if (envelope.event == "room.cleared" && (envelope.payload["room_id"] as? JsonPrimitive)?.content == roomId) load()
            }
        }
        // Rooms have no replay: after a reconnect the room is read again.
        viewModelScope.launch { graph.realtime.roomReconnects.drop(1).collect { load() } }
        load()
    }

    /** The screen is showing: become present in the room and hear it. */
    fun enter() = graph.realtime.joinRoom(roomId)

    fun exit() {
        typing(false)
        graph.realtime.leaveRoom(roomId)
    }

    fun load() {
        val api = apis ?: return
        viewModelScope.launch {
            val detail = hubCall { api.rooms.roomsGet(profile, roomId) }
            val page = hubCall { api.rooms.roomsListMessages(profile, roomId, limit = 50) }
            val d = detail.getOrNull()
            val p = page.getOrNull()
            if (d == null || p == null) {
                val error = (detail.exceptionOrNull() ?: page.exceptionOrNull()) as? HubError
                _ui.update { it.copy(loading = false, error = error, gone = error?.status == 404) }
                return@launch
            }
            _ui.update { it.copy(loading = false, hasOlder = p.hasMore, state = RoomReducer.loaded(it.state, d, p.items)) }
            loadHandoffs()
        }
    }

    // Managing the room (the web's room menu, members panel and settings)

    /** The room's handoff chains, newest first: the strip offers one more round on a stopped one. */
    fun loadHandoffs() {
        val api = apis ?: return
        viewModelScope.launch {
            hubCall { RoomActions(api).handoffs(profile, roomId) }
                .onSuccess { chains -> _ui.update { it.copy(state = it.state.copy(handoffs = chains)) } }
        }
    }

    /** The profile's projects, read when the room's settings open (one of them may report here). */
    var projects: List<hub.core.client.model.Project>? = null
        private set

    suspend fun loadProjects() {
        val api = apis ?: return
        projects = hubCall { RoomActions(api).projects(profile) }.getOrNull()
    }

    val linkedProject: String? get() = projects?.let { RoomManage.linkedProject(it, roomId) }

    /** The room's settings form: the room's own patch, then the project that reports here. */
    suspend fun saveSettings(values: Map<String, String>): Result<*> {
        val api = apis ?: return Result.failure<Unit>(HubError(401, "unauthorized", null))
        val room = _ui.value.state.room
        RoomManage.settingsPatch(values, room?.canMentionAll == true, room?.handoff)?.let { patch ->
            update(patch).onFailure { return Result.failure<Unit>(it) }
        }
        val links = RoomManage.projectLinks(values, linkedProject, roomId)
        if (links.isEmpty()) return Result.success(Unit)
        return hubCall { RoomActions(api).link(profile, links) }.also { loadProjects() }
    }

    /** Whether you made the room (the maker cannot leave it). */
    val isOwner: Boolean get() = _ui.value.state.members.any { it.userId == me && it.role == Member.Role.OWNER }

    /** A change to the room itself (name, settings, lead, archived); the result is for a form to show. */
    suspend fun update(patch: RoomPatch): Result<*> {
        val api = apis ?: return Result.failure<Unit>(HubError(401, "unauthorized", null))
        return hubCall { RoomActions(api).update(profile, roomId, patch) }.onSuccess { room ->
            _ui.update { ui ->
                val info = ui.state.room
                ui.copy(
                    state = ui.state.copy(
                        room = info?.copy(
                            name = room.name, canMentionAll = room.canMentionAll, leadSeatId = room.leadSeatId,
                            archived = room.archivedAt != null, handoff = room.handoff,
                        ),
                        seats = room.seats,
                    ),
                )
            }
        }
    }

    /** The same, from a menu: a refusal shows over the composer. */
    fun change(patch: RoomPatch) {
        viewModelScope.launch { update(patch).onFailure { e -> _ui.update { it.copy(error = e as? HubError) } } }
    }

    fun makeLead(seat: Seat) = change(RoomPatch(leadSeatId = seat.id))

    suspend fun delete(): Result<*> {
        val api = apis ?: return Result.failure<Unit>(HubError(401, "unauthorized", null))
        return hubCall { RoomActions(api).delete(profile, roomId) }.onSuccess { _ui.update { it.copy(gone = true) } }
    }

    /** The agents forget the room (its messages stay); the summary and token count start over. */
    fun clearContext() = act { api ->
        RoomActions(api).clearContext(profile, roomId)
        load()
    }

    suspend fun addSeat(seat: SeatConfig): Result<*> {
        val api = apis ?: return Result.failure<Unit>(HubError(401, "unauthorized", null))
        return hubCall { RoomActions(api).addSeat(profile, roomId, seat) }.onSuccess { added ->
            _ui.update { ui -> ui.copy(state = ui.state.copy(seats = ui.state.seats.filter { it.id != added.id } + added)) }
            if (_ui.value.state.room?.leadSeatId == null) load()
        }
    }

    suspend fun updateSeat(seat: Seat, patch: SeatPatch): Result<*> {
        val api = apis ?: return Result.failure<Unit>(HubError(401, "unauthorized", null))
        return hubCall { RoomActions(api).updateSeat(profile, roomId, seat.id, patch) }.onSuccess { updated ->
            _ui.update { ui -> ui.copy(state = ui.state.copy(seats = ui.state.seats.map { if (it.id == updated.id) updated else it })) }
        }
    }

    /** The seat leaves the room; what it said stays. When it led, the hub names the next lead. */
    suspend fun removeSeat(seat: Seat): Result<*> {
        val api = apis ?: return Result.failure<Unit>(HubError(401, "unauthorized", null))
        return hubCall { RoomActions(api).removeSeat(profile, roomId, seat.id) }.onSuccess {
            _ui.update { ui -> ui.copy(state = ui.state.copy(seats = ui.state.seats.filter { it.id != seat.id })) }
            load()
        }
    }

    /** Rewrite the summary now (a job; `memory.updated` brings the result). */
    fun refreshMemory() = act { api ->
        RoomActions(api).refreshMemory(profile, roomId)
        _ui.update { ui -> ui.copy(state = ui.state.copy(memory = ui.state.memory?.copy(status = hub.core.client.model.RoomMemory.Status.SUMMARIZING))) }
    }

    suspend fun putMemory(summary: String): Result<*> {
        val api = apis ?: return Result.failure<Unit>(HubError(401, "unauthorized", null))
        return hubCall { RoomActions(api).putMemory(profile, roomId, summary) }.onSuccess { memory ->
            _ui.update { ui -> ui.copy(state = ui.state.copy(memory = memory)) }
        }
    }

    /** One more round for a chain the guard stopped. */
    fun continueHandoff(chainId: String) = act { api ->
        RoomActions(api).continueHandoff(profile, roomId, chainId)
        loadHandoffs()
    }

    fun loadOlder() {
        val api = apis ?: return
        val first = _ui.value.state.messages.firstOrNull()?.id ?: return
        if (_ui.value.loadingOlder || !_ui.value.hasOlder) return
        _ui.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            hubCall { api.rooms.roomsListMessages(profile, roomId, before = first, limit = 50) }
                .onSuccess { page -> _ui.update { it.copy(loadingOlder = false, hasOlder = page.hasMore, state = RoomReducer.olderLoaded(it.state, page.items)) } }
                .onFailure { e -> _ui.update { it.copy(loadingOlder = false, error = e as HubError) } }
        }
    }

    /** Words and files as one message; the seats it names are its mentions (or the lead answers). */
    fun send(text: String, onFailed: (String) -> Unit = {}) {
        if (tray.uploading) return
        val picked = tray.message(text)
        if (picked.isEmpty) return
        // A room takes words, pictures and files (§99): a recording goes as a file.
        val outgoing = picked.copy(
            asFiles = picked.asFiles + picked.attachments.filter { it.kind == hub.core.client.model.Attachment.Kind.AUDIO }.map { it.id },
        )
        val api = apis ?: return
        val state = _ui.value.state
        val mentions = RoomMentions.mentionsIn(text, state.mentionSeats, state.room?.canMentionAll == true)
        tray.clear()
        typing(false)
        _ui.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            hubCall {
                api.rooms.roomsPostMessage(
                    profile, roomId,
                    RoomMessageCreate(content = outgoing.blocks(), mentions = mentions.ifEmpty { null }),
                    UUID.randomUUID().toString(),
                )
            }.onSuccess { _ui.update { it.copy(sending = false) } }
                .onFailure { e ->
                    _ui.update { it.copy(sending = false, error = e as HubError) }
                    onFailed(text)
                }
        }
    }

    /** Tells the others you are writing; at most once every two seconds while you type. */
    fun typing(on: Boolean) {
        val now = System.currentTimeMillis()
        if (on && now - typingSentAt < 2_000) return
        typingSentAt = if (on) now else 0L
        graph.realtime.typing(roomId, on)
    }

    fun respond(approval: Approval, decision: ApprovalDecision?, answer: String?) {
        val api = apis ?: return
        viewModelScope.launch {
            hubCall { api.sessions.sessionsRespondApproval(approval.profile, approval.id, ApprovalResponse(decision, answer)) }
                .onSuccess { drop(approval) }
                .onFailure { e -> if ((e as HubError).status == 409) drop(approval) else _ui.update { it.copy(error = e) } }
        }
    }

    private fun drop(approval: Approval) =
        _ui.update { it.copy(state = it.state.copy(approvals = it.state.approvals - approval.id)) }

    fun stopSeat(seat: Seat) = act { api -> api.rooms.roomsStopSeat(profile, roomId, seat.id) }

    fun removeMember(member: Member) = act { api -> api.rooms.roomsRemoveMember(profile, roomId, member.id); load() }

    /** Leaving is removing yourself (the maker stays, DECISIONS §69). */
    fun leave() {
        val mine = _ui.value.state.members.firstOrNull { it.userId == me } ?: return
        act { api ->
            api.rooms.roomsRemoveMember(profile, roomId, mine.id)
            _ui.update { it.copy(gone = true) }
        }
    }

    /** A new invite code (the old one stops working) and its link, for the manager to share. */
    fun rotateInvite() = act { api ->
        val invite = api.rooms.roomsRotateInviteCode(profile, roomId)
        _ui.update { ui ->
            ui.copy(
                inviteLink = invite.joinUrl.toString(),
                state = ui.state.copy(room = ui.state.room?.copy(inviteCode = invite.inviteCode)),
            )
        }
    }

    private fun act(block: suspend (hub.core.android.data.HubApis) -> Unit) {
        val api = apis ?: return
        viewModelScope.launch { hubCall { block(api) }.onFailure { e -> _ui.update { it.copy(error = e as HubError) } } }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    override fun onCleared() {
        exit()
    }
}

package hub.core.android.rooms

import hub.core.android.data.HubApis
import hub.core.android.data.HubError
import hub.core.client.model.HandoffChain
import hub.core.client.model.Project
import hub.core.client.model.Room
import hub.core.client.model.RoomMemory
import hub.core.client.model.RoomPatch
import hub.core.client.model.RoomsPutMemoryRequest
import hub.core.client.model.Seat
import hub.core.client.model.SeatConfig
import hub.core.client.model.SeatPatch

/**
 * The calls behind managing a room (contract tag `rooms`), apart from any screen so their
 * requests are tested against a mock hub (RoomManageTest). The room's screen and the rooms list
 * both use them.
 */
class RoomActions(private val api: HubApis) {
    suspend fun update(profile: String, roomId: String, patch: RoomPatch): Room = api.rooms.roomsUpdate(profile, roomId, patch)

    suspend fun delete(profile: String, roomId: String) = api.rooms.roomsDelete(profile, roomId)

    /** The agents forget the room (its messages stay); the summary and token count start over. */
    suspend fun clearContext(profile: String, roomId: String) = api.rooms.roomsClearContext(profile, roomId)

    /** Leaving is removing yourself: the room's list of people says which member you are. */
    suspend fun leave(profile: String, roomId: String, userId: String) {
        val mine = api.rooms.roomsListMembers(profile, roomId).items.firstOrNull { it.userId == userId }
            ?: throw HubError(404, "not_found", null)
        api.rooms.roomsRemoveMember(profile, roomId, mine.id)
    }

    suspend fun addSeat(profile: String, roomId: String, seat: SeatConfig): Seat = api.rooms.roomsAddSeat(profile, roomId, seat)

    suspend fun updateSeat(profile: String, roomId: String, seatId: String, patch: SeatPatch): Seat =
        api.rooms.roomsUpdateSeat(profile, roomId, seatId, patch)

    suspend fun removeSeat(profile: String, roomId: String, seatId: String) = api.rooms.roomsRemoveSeat(profile, roomId, seatId)

    /** Rewrite the summary now: a job, whose result comes as `memory.updated`. */
    suspend fun refreshMemory(profile: String, roomId: String) = api.rooms.roomsRefreshMemory(profile, roomId)

    suspend fun putMemory(profile: String, roomId: String, summary: String): RoomMemory =
        api.rooms.roomsPutMemory(profile, roomId, RoomsPutMemoryRequest(summary))

    /** The room's handoff chains, newest first. */
    suspend fun handoffs(profile: String, roomId: String): List<HandoffChain> = api.rooms.roomsListHandoffs(profile, roomId).items

    /** The profile's projects (one of them may report into the room). */
    suspend fun projects(profile: String): List<Project> = api.tasks.tasksListProjects(profile, limit = 100).items

    /** The project that reports here moves: the old one unlinked first, then the new one linked. */
    suspend fun link(profile: String, links: List<RoomManage.ProjectLink>) {
        for (link in links) api.tasks.tasksUpdateProject(profile, link.projectId, link.write)
    }

    /** One more round for a chain the guard stopped. */
    suspend fun continueHandoff(profile: String, roomId: String, chainId: String) = api.rooms.roomsContinueHandoff(profile, roomId, chainId)
}

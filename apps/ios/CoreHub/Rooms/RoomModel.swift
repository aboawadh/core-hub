// Opens one room (DECISIONS §69): HTTP for the room and its messages (`rooms.get`,
// `rooms.listMessages`), `/rt/rooms` for everything after — joined while the screen shows it,
// and read again after a reconnect, because rooms have no replay. What is sent carries its
// mentions as structured ids and its files as blocks (contract decision §99).
import CoreHubClient
import Foundation
import Observation

@MainActor
@Observable
final class RoomModel {
    enum Load: Equatable {
        case loading
        case ready
        case failed(String)
    }

    private(set) var state: RoomState
    private(set) var load: Load = .loading
    private(set) var sending = false
    private(set) var loadingOlder = false
    /// The last action that failed, in one sentence.
    var actionError: String?
    /// The invite link the manager just made (`rotateInviteCode`).
    private(set) var inviteLink: String?
    /// You left the room, it was deleted, or it is not yours to open.
    private(set) var gone = false

    let roomID: String
    let profile: String

    @ObservationIgnored private weak var app: AppModel?
    @ObservationIgnored private var listeners: [UUID] = []
    @ObservationIgnored private var started = false
    @ObservationIgnored private var connectedOnce = false
    @ObservationIgnored private var typingSentAt: Date?

    init(app: AppModel, roomID: String, profile: String) {
        self.app = app
        self.roomID = roomID
        self.profile = profile
        self.state = RoomState(roomID: roomID, profile: profile)
    }

    var l10n: L10n { app?.l10n ?? L10n(.en) }

    /// The signed-in person: their own messages are on the right.
    var me: String? { app?.credentials?.userID }

    func start() {
        guard !started, let app else { return }
        started = true
        if let namespace = app.rooms {
            listeners.append(namespace.onEvent { [weak self] name, argument in
                guard RoomEvents.names.contains(name), let envelope = Envelope.parse(argument) else { return }
                self?.state.apply(envelope)
                if self?.state.deleted == true { self?.gone = true }
                // Clearing the context resets the summary and the token count: read the room again.
                if name == "room.cleared", self?.state.deleted == false { Task { await self?.fetch() } }
            })
            listeners.append(namespace.onConnect { [weak self] in
                guard let self else { return }
                self.join()
                if self.connectedOnce { Task { await self.fetch() } }
                self.connectedOnce = true
            })
            if namespace.isConnected {
                connectedOnce = true
                join()
            }
        }
        Task { await fetch() }
    }

    func stop() {
        guard started else { return }
        started = false
        typing(false)
        if let namespace = app?.rooms {
            for id in listeners { namespace.remove(id) }
            if namespace.isConnected {
                let roomID = roomID
                Task { _ = try? await namespace.emit("leave", ["room_id": roomID]) }
            }
        }
        listeners.removeAll()
    }

    private func join() {
        guard let namespace = app?.rooms, namespace.isConnected else { return }
        let roomID = roomID
        Task { _ = try? await namespace.emit("join", ["room_id": roomID]) }
    }

    func fetch() async {
        guard let app else { return }
        let profile = profile
        let roomID = roomID
        do {
            async let detail = app.api.call { try await RoomsAPI.roomsGet(xHubProfile: profile, roomId: roomID, apiConfiguration: $0) }
            async let page = app.api.call { try await RoomsAPI.roomsListMessages(xHubProfile: profile, roomId: roomID, limit: 50, apiConfiguration: $0) }
            let (d, p) = try await (detail, page)
            state.hydrate(d, messages: p)
            load = .ready
            await fetchHandoffs()
        } catch {
            let failure = HubFailure(error)
            if failure.status == 404 { gone = true }
            if load != .ready { load = .failed(failure.describe(l10n)) } else { actionError = failure.describe(l10n) }
        }
    }

    func loadOlder() async {
        guard let app, state.hasOlder, !loadingOlder, let oldest = state.messages.first else { return }
        loadingOlder = true
        defer { loadingOlder = false }
        let profile = profile
        let roomID = roomID
        do {
            let page = try await app.api.call {
                try await RoomsAPI.roomsListMessages(xHubProfile: profile, roomId: roomID, before: oldest.id, limit: 50, apiConfiguration: $0)
            }
            state.prependOlder(page)
        } catch {
            actionError = HubFailure(error).describe(l10n)
        }
    }

    /// Words and files as one message; the seats it names are its mentions (else the lead answers).
    /// False when the hub refused it, so the words can go back in the composer.
    @discardableResult
    func send(_ message: OutgoingMessage) async -> Bool {
        guard !message.isEmpty, let app else { return false }
        let mentions = RoomMentions.mentions(in: message.text, seats: state.mentionSeats, allowAll: state.canMentionAll)
        // A room takes words, pictures and files (§99): a recording goes as a file.
        var outgoing = message
        outgoing.asFiles.formUnion(message.attachments.filter { $0.kind == .audio }.map(\.id))
        let body = RoomMessageCreate(content: outgoing.blocks, mentions: mentions.isEmpty ? nil : mentions)
        let profile = profile
        let roomID = roomID
        let key = ULID.make()
        sending = true
        actionError = nil
        typing(false)
        defer { sending = false }
        do {
            _ = try await app.api.call {
                try await RoomsAPI.roomsPostMessage(xHubProfile: profile, roomId: roomID, roomMessageCreate: body, idempotencyKey: key, apiConfiguration: $0)
            }
            return true
        } catch {
            actionError = HubFailure(error).describe(l10n)
            return false
        }
    }

    /// Tells the others you are writing; at most once every two seconds while you type.
    func typing(_ on: Bool) {
        guard let namespace = app?.rooms, namespace.isConnected else { return }
        if on, let last = typingSentAt, Date().timeIntervalSince(last) < 2 { return }
        if !on, typingSentAt == nil { return }
        typingSentAt = on ? Date() : nil
        let roomID = roomID
        Task { _ = try? await namespace.emit("typing", ["room_id": roomID, "typing": on]) }
    }

    func respond(_ approval: Approval, decision: ApprovalDecision?, answer: String?) async {
        guard let app else { return }
        let profile = approval.profile
        do {
            _ = try await app.api.call {
                try await SessionsAPI.sessionsRespondApproval(
                    xHubProfile: profile, approvalId: approval.id,
                    approvalResponse: ApprovalResponse(decision: decision, answer: answer), apiConfiguration: $0
                )
            }
            state.approvals[approval.id] = nil
        } catch {
            let failure = HubFailure(error)
            if failure.status == 409 { state.approvals[approval.id] = nil } else { actionError = failure.describe(l10n) }
        }
    }

    func stopSeat(_ seat: Seat) async {
        await act { profile, roomID, config in
            _ = try await RoomsAPI.roomsStopSeat(xHubProfile: profile, roomId: roomID, seatId: seat.id, apiConfiguration: config)
        }
    }

    func remove(_ member: Member) async {
        await act { profile, roomID, config in
            try await RoomsAPI.roomsRemoveMember(xHubProfile: profile, roomId: roomID, memberId: member.id, apiConfiguration: config)
        }
        await fetch()
    }

    /// Leaving is removing yourself (the maker stays, DECISIONS §69).
    func leave() async {
        guard let mine = state.members.first(where: { $0.userId == me }) else { return }
        await act { profile, roomID, config in
            try await RoomsAPI.roomsRemoveMember(xHubProfile: profile, roomId: roomID, memberId: mine.id, apiConfiguration: config)
        }
        if actionError == nil { gone = true }
    }

    /// A new invite code (the old one stops working) and its link, for the manager to share.
    func rotateInvite() async {
        guard let app else { return }
        let profile = profile
        let roomID = roomID
        do {
            let invite = try await app.api.call {
                try await RoomsAPI.roomsRotateInviteCode(xHubProfile: profile, roomId: roomID, apiConfiguration: $0)
            }
            state.inviteCode = invite.inviteCode
            inviteLink = invite.joinUrl
        } catch {
            actionError = HubFailure(error).describe(l10n)
        }
    }

    /// The link to share: the one just made, else the room's code on this hub.
    var shareLink: String? {
        if let inviteLink { return inviteLink }
        guard let code = state.inviteCode, let hub = app?.credentials?.hubURL else { return nil }
        return RoomLinks.join(hub: hub, code: code)
    }

    // MARK: - Managing the room (the web's room menu, members panel and settings)

    /// The room's handoff chains, newest first: the strip offers one more round on a stopped one.
    func fetchHandoffs() async {
        guard let app else { return }
        let profile = profile
        let roomID = roomID
        if let page = try? await app.api.call({
            try await RoomsAPI.roomsListHandoffs(xHubProfile: profile, roomId: roomID, apiConfiguration: $0)
        }) {
            state.handoffs = page.items
        }
    }

    /// A change to the room itself (name, settings, lead, archived); throws the hub's refusal so a
    /// form can show it.
    func update(_ patch: RoomPatch) async throws {
        guard let app else { return }
        let profile = profile
        let roomID = roomID
        let room = try await app.api.call {
            try await RoomsAPI.roomsUpdate(xHubProfile: profile, roomId: roomID, roomPatch: patch, apiConfiguration: $0)
        }
        state.name = room.name
        state.canMentionAll = room.canMentionAll
        state.leadSeatID = room.leadSeatId
        state.archived = room.archivedAt != nil
        state.handoff = room.handoff
        state.seats = room.seats
    }

    /// The same, from a menu: a refusal shows over the composer.
    func change(_ patch: RoomPatch) async {
        actionError = nil
        do { try await update(patch) } catch { actionError = HubFailure(error).describe(l10n) }
    }

    func makeLead(_ seat: Seat) async { await change(RoomPatch(leadSeatId: seat.id)) }

    func delete() async throws {
        guard let app else { return }
        let profile = profile
        let roomID = roomID
        try await app.api.call { try await RoomsAPI.roomsDelete(xHubProfile: profile, roomId: roomID, apiConfiguration: $0) }
        gone = true
    }

    /// The agents forget the room (its messages stay); the summary and token count start over.
    func clearContext() async {
        await act { profile, roomID, config in
            try await RoomsAPI.roomsClearContext(xHubProfile: profile, roomId: roomID, apiConfiguration: config)
        }
        if actionError == nil { await fetch() }
    }

    func addSeat(_ seat: SeatConfig) async throws {
        guard let app else { return }
        let profile = profile
        let roomID = roomID
        let added = try await app.api.call {
            try await RoomsAPI.roomsAddSeat(xHubProfile: profile, roomId: roomID, body: seat, apiConfiguration: $0)
        }
        if !state.seats.contains(where: { $0.id == added.id }) { state.seats.append(added) }
        if state.leadSeatID == nil { await fetch() }
    }

    func updateSeat(_ seat: Seat, _ patch: SeatPatch) async throws {
        guard let app else { return }
        let profile = profile
        let roomID = roomID
        let updated = try await app.api.call {
            try await RoomsAPI.roomsUpdateSeat(xHubProfile: profile, roomId: roomID, seatId: seat.id, seatPatch: patch, apiConfiguration: $0)
        }
        if let index = state.seats.firstIndex(where: { $0.id == updated.id }) { state.seats[index] = updated }
    }

    /// The seat leaves the room; what it said stays. When it led, the hub names the next lead.
    func removeSeat(_ seat: Seat) async throws {
        guard let app else { return }
        let profile = profile
        let roomID = roomID
        try await app.api.call {
            try await RoomsAPI.roomsRemoveSeat(xHubProfile: profile, roomId: roomID, seatId: seat.id, apiConfiguration: $0)
        }
        state.seats.removeAll { $0.id == seat.id }
        await fetch()
    }

    /// Rewrite the summary now (a job; `memory.updated` brings the result).
    func refreshMemory() async {
        await act { profile, roomID, config in
            _ = try await RoomsAPI.roomsRefreshMemory(xHubProfile: profile, roomId: roomID, apiConfiguration: config)
        }
        if actionError == nil, let memory = state.memory {
            state.memory = RoomMemory(summary: memory.summary, status: .summarizing, summarizedTurnCount: memory.summarizedTurnCount, updatedAt: memory.updatedAt)
        }
    }

    func putMemory(_ summary: String) async throws {
        guard let app else { return }
        let profile = profile
        let roomID = roomID
        state.memory = try await app.api.call {
            try await RoomsAPI.roomsPutMemory(xHubProfile: profile, roomId: roomID, roomsPutMemoryRequest: RoomsPutMemoryRequest(summary: summary), apiConfiguration: $0)
        }
    }

    /// One more round for a chain the guard stopped.
    func continueHandoff(_ chainID: String) async {
        await act { profile, roomID, config in
            _ = try await RoomsAPI.roomsContinueHandoff(xHubProfile: profile, roomId: roomID, chainId: chainID, apiConfiguration: config)
        }
        if actionError == nil { await fetchHandoffs() }
    }

    /// The profile's projects, read when the room's settings open (one of them may report here).
    private(set) var projects: [Project]?

    func loadProjects() async {
        guard let app else { return }
        let profile = profile
        projects = try? await app.api.call {
            try await TasksAPI.tasksListProjects(xHubProfile: profile, limit: 100, apiConfiguration: $0)
        }.items
    }

    var linkedProject: String? { projects.flatMap { RoomManage.linkedProject($0, roomID: roomID) } }

    /// The room's settings form: the room's own patch, then the project that reports here.
    func saveSettings(_ values: [String: String]) async throws {
        if let patch = RoomManage.settingsPatch(values, canMentionAll: state.canMentionAll, handoff: state.handoff) {
            try await update(patch)
        }
        let links = RoomManage.projectLinks(values, linked: linkedProject, roomID: roomID)
        guard let app, !links.isEmpty else { return }
        let profile = profile
        for link in links {
            _ = try await app.api.call {
                try await TasksAPI.tasksUpdateProject(xHubProfile: profile, projectId: link.projectID, projectWrite: link.write, apiConfiguration: $0)
            }
        }
        await loadProjects()
    }

    /// Whether you made the room (the maker cannot leave it).
    var isOwner: Bool { state.members.contains { $0.userId == me && $0.role == .owner } }

    private func act(_ call: @escaping (String, String, CoreHubClientAPIConfiguration) async throws -> Void) async {
        guard let app else { return }
        let profile = profile
        let roomID = roomID
        actionError = nil
        do {
            _ = try await app.api.call { try await call(profile, roomID, $0) }
        } catch {
            actionError = HubFailure(error).describe(l10n)
        }
    }
}

@testable import CoreHub
import CoreHubClient
import XCTest

/// Managing a room on the phone (the web's room menu, seat form, settings, handoff strip), and the
/// room's state keeping who manages it when the room changes.
final class RoomManageTests: XCTestCase {
    private let room = "01J8QK3ZR2W7M5N4P6T8V9X0RM"
    private let planner = "01J8QK3ZR2W7M5N4P6T8V9X0ST"
    private let coder = "01J8QK3ZR2W7M5N4P6T8V9X0SU"

    private func agent(_ id: String, _ name: String, status: AgentStatus = .available, enabled: Bool = true) -> Agent {
        Agent(id: id, profile: "work", ownerId: "u1", createdAt: Fixture.date, updatedAt: Fixture.date, slug: "a\(id)",
              name: name, kind: .hermes, avatar: Avatar(kind: .generated, seed: "a"), status: status, enabled: enabled,
              install: AgentInstall(source: .managed, updateAvailable: false, newerThanTested: false, autoUpdate: false, autoUpdateSupported: false),
              runtime: AgentRuntime(state: .running), capabilities: [], sections: [], limited: false, subagents: ._none)
    }

    private func seat(_ id: String, _ name: String, description: String? = nil, model: String? = nil) -> Seat {
        Seat(id: id, roomId: room, agentId: "ag1", name: name, description: description, avatar: Avatar(kind: .generated, seed: "s"),
             model: model, status: .idle, executor: SeatExecutor(kind: .server), createdAt: Fixture.date, updatedAt: Fixture.date)
    }

    private func chain(_ id: String, _ status: HandoffChain.Status, reason: HandoffChain.StopReason? = nil, used: Bool = false,
                       depth: Int = 2, at seconds: TimeInterval = 0) -> HandoffChain {
        HandoffChain(id: id, roomId: room, fromSeatId: planner, toSeatId: coder, status: status, stopReason: reason, depth: depth,
                     maxDepth: 3, continueUsed: used, updatedAt: Fixture.date.addingTimeInterval(seconds))
    }

    // MARK: - Actions

    func testAManagerRenamesSetsClearsArchivesAndDeletesAMemberOnlyLeaves() {
        XCTAssertEqual(RoomManage.actions(canManage: true, archived: false), [.rename, .settings, .clearContext, .archive, .delete])
        XCTAssertEqual(RoomManage.actions(canManage: true, archived: true), [.rename, .settings, .clearContext, .unarchive, .delete])
        XCTAssertEqual(RoomManage.actions(canManage: false, archived: false), [.leave])
        XCTAssertEqual(RoomManage.actions(canManage: false, archived: false, owner: true), [])
        XCTAssertEqual(RoomManage.rowActions(canManage: true, archived: false), [.rename, .archive, .delete])
        XCTAssertEqual(RoomManage.rowActions(canManage: false, archived: true), [.leave])
    }

    func testARenameIsTrimmedAndNothingIsSentForAnEmptyOrSameName() {
        XCTAssertEqual(RoomManage.renamed("  Launch  ", from: "Old")?.name, "Launch")
        XCTAssertNil(RoomManage.renamed("   ", from: "Old"))
        XCTAssertNil(RoomManage.renamed("Old ", from: "Old"))
        XCTAssertNil(RoomManage.renamed(String(repeating: "x", count: 121), from: "Old"))
    }

    // MARK: - Seats

    func testTheSeatFormChoosesAnAgentOnlyWhenAddingAndOffersOnlySeatableAgents() {
        let agents = [agent("1", "Hermes"), agent("2", "Off", enabled: false), agent("3", "Broken", status: .error)]
        let adding = RoomManage.seatFields(adding: true, agents: agents, l10n: L10n(.en))
        XCTAssertEqual(adding.map(\.key), ["agent", "name", "role", "instructions", "model"])
        XCTAssertEqual(adding.first?.options.map(\.value), ["1"])
        XCTAssertFalse(adding[1].required)
        let editing = RoomManage.seatFields(adding: false, agents: agents, l10n: L10n(.en))
        XCTAssertEqual(editing.map(\.key), ["name", "role", "instructions", "model"])
        XCTAssertTrue(editing[0].required)
        XCTAssertEqual(RoomManage.seatValues(nil, agents: agents)["agent"], "1")
    }

    func testANewSeatIsNamedAfterItsAgentUnlessNamedAndLeavesEmptyFieldsOut() {
        let agents = [agent("1", "Hermes")]
        let plain = RoomManage.seatConfig(["agent": "1", "name": " ", "role": "", "model": " "], agents: agents)
        XCTAssertEqual(plain?.name, "Hermes")
        XCTAssertNil(plain?.description)
        XCTAssertNil(plain?.model)
        XCTAssertNil(plain?.instructions)
        let named = RoomManage.seatConfig(["agent": "1", "name": "Critic", "role": "Finds holes", "instructions": "Be brief", "model": "gpt-5"], agents: agents)
        XCTAssertEqual(named, SeatConfig(agentId: "1", name: "Critic", description: "Finds holes", model: "gpt-5", instructions: "Be brief"))
        XCTAssertNil(RoomManage.seatConfig(["agent": "9"], agents: agents))
    }

    func testAnEditedSeatSendsEveryFieldAndAnEmptiedOneAsNull() throws {
        let patch = RoomManage.seatPatch(["name": " Critic ", "role": "", "instructions": " Be brief ", "model": ""])
        XCTAssertEqual(patch.name, "Critic")
        XCTAssertNil(patch.description)
        XCTAssertEqual(patch.instructions, "Be brief")
        XCTAssertNil(patch.model)
        XCTAssertEqual(patch.sendNull, [.description, .model])
        let sent = try JSONSerialization.jsonObject(with: CodableHelper().jsonEncoder.encode(patch)) as! [String: Any]
        XCTAssertTrue(sent["model"] is NSNull)
        XCTAssertTrue(sent["description"] is NSNull)
        XCTAssertEqual(sent["instructions"] as? String, "Be brief")
        XCTAssertEqual(RoomManage.seatValues(seat(planner, "Planner", description: "Plans", model: "m1"), agents: [])["model"], "m1")
    }

    func testASeatLineIsItsRoleAndModelOrTheAgentsOwn() {
        XCTAssertEqual(RoomManage.seatLine(seat(planner, "P", description: "Plans", model: "m1"), defaultModel: "own"), "Plans · m1")
        XCTAssertEqual(RoomManage.seatLine(seat(planner, "P"), defaultModel: "own"), "own")
    }

    // MARK: - Settings

    func testSettingsSendOnlyWhatChangedAndThePolicyWhole() {
        let policy = HandoffPolicy(enabled: true, maxDepth: 3)
        let values = RoomManage.settingsValues(canMentionAll: true, handoff: policy)
        XCTAssertEqual(values, ["mention_all": "true", "handoff": "true", "max_depth": "3", "project": "none"])
        XCTAssertNil(RoomManage.settingsPatch(values, canMentionAll: true, handoff: policy))

        var all = values
        all["mention_all"] = "false"
        let onlyAll = RoomManage.settingsPatch(all, canMentionAll: true, handoff: policy)
        XCTAssertEqual(onlyAll?.canMentionAll, false)
        XCTAssertNil(onlyAll?.handoff)

        var deeper = values
        deeper["max_depth"] = " 5 "
        XCTAssertEqual(RoomManage.settingsPatch(deeper, canMentionAll: true, handoff: policy)?.handoff, HandoffPolicy(enabled: true, maxDepth: 5))
        XCTAssertNil(RoomManage.settingsPatch(deeper, canMentionAll: true, handoff: policy)?.canMentionAll)

        var unlimited = values
        unlimited["max_depth"] = ""
        unlimited["handoff"] = "false"
        let off = RoomManage.settingsPatch(unlimited, canMentionAll: true, handoff: policy)
        XCTAssertEqual(off?.handoff, HandoffPolicy(enabled: false, maxDepth: nil))
        let sent = try! JSONSerialization.jsonObject(with: CodableHelper().jsonEncoder.encode(off!)) as! [String: Any]
        XCTAssertTrue((sent["handoff"] as? [String: Any])?["max_depth"] is NSNull)
    }

    func testTheProjectReportingHereMovesByUnlinkingTheOldOneFirst() {
        let values = RoomManage.settingsValues(canMentionAll: true, handoff: nil, linkedProject: "p1")
        XCTAssertEqual(values["project"], "p1")
        XCTAssertEqual(RoomManage.projectLinks(values, linked: "p1", roomID: room), [])
        var moved = values
        moved["project"] = "p2"
        XCTAssertEqual(RoomManage.projectLinks(moved, linked: "p1", roomID: room), [
            .init(projectID: "p1", write: ProjectWrite(sendNull: [.reportRoomId])),
            .init(projectID: "p2", write: ProjectWrite(reportRoomId: room)),
        ])
        moved["project"] = RoomManage.noProject
        XCTAssertEqual(RoomManage.projectLinks(moved, linked: "p1", roomID: room), [.init(projectID: "p1", write: ProjectWrite(sendNull: [.reportRoomId]))])
        XCTAssertEqual(RoomManage.projectLinks(moved, linked: nil, roomID: room), [])
        XCTAssertNil(RoomManage.settingsFields(L10n(.en)).first { $0.key == "project" })
        XCTAssertEqual(RoomManage.settingsFields(L10n(.en), projects: []).first { $0.key == "project" }?.options.map(\.value), ["none"])
    }

    func testTheDepthIsAWholeNumberFromOneToTwentyOrEmpty() {
        let depth = RoomManage.settingsFields(L10n(.en)).first { $0.key == "max_depth" }!
        XCTAssertNil(FormRules.problem(depth, ""))
        XCTAssertNil(FormRules.problem(depth, "20"))
        XCTAssertEqual(FormRules.problem(depth, "0"), .tooSmall(1))
        XCTAssertEqual(FormRules.problem(depth, "21"), .tooLarge(20))
        XCTAssertEqual(FormRules.problem(depth, "2.5"), .notWhole)
    }

    // MARK: - Passing the turn

    func testTheStripSaysTheChainGoingOnNowFirst() {
        let seats = [seat(planner, "Planner"), seat(coder, "Coder")]
        XCTAssertEqual(
            RoomManage.handoffLine(active: [chain("c2", .active, depth: 2)], listed: [chain("c1", .stopped, reason: .maxDepth)], seats: seats),
            .active(from: "Planner", to: "Coder", depth: 2)
        )
    }

    func testOnlyTheNewestStoppedChainOffersOneMoreRoundOnce() {
        let seats = [seat(planner, "Planner"), seat(coder, "Coder")]
        XCTAssertEqual(
            RoomManage.handoffLine(active: [], listed: [chain("c1", .stopped, reason: .maxDepth, at: 10)], seats: seats),
            .stopped(id: "c1", reason: .maxDepth, from: "Planner", to: "Coder", more: true)
        )
        XCTAssertEqual(
            RoomManage.handoffLine(active: [], listed: [chain("c1", .stopped, reason: .interrupted)], seats: seats),
            .stopped(id: "c1", reason: .interrupted, from: "Planner", to: "Coder", more: false)
        )
        XCTAssertNil(RoomManage.handoffLine(active: [], listed: [chain("c1", .stopped, reason: .loopDetected, used: true)], seats: seats))
        XCTAssertNil(RoomManage.handoffLine(
            active: [], listed: [chain("c1", .stopped, reason: .maxDepth, at: 0), chain("c2", .completed, at: 60)], seats: seats
        ))
        XCTAssertEqual(
            RoomManage.handoffText(.stopped(id: "c1", reason: .loopDetected, from: "A", to: "B", more: true), L10n(.en)),
            "Stopped passing the turn: @A → @B would repeat a pass already made."
        )
    }

    // MARK: - The room's state

    private func envelope(_ event: String, _ payload: [String: Any]) -> Envelope {
        let object: [String: Any] = [
            "event": event, "namespace": "/rt/rooms", "profile": "work", "ts": "2026-09-27T10:00:00Z", "seq": 1, "payload": payload,
        ]
        return Envelope.parse(try! JSONSerialization.data(withJSONObject: object))!
    }

    private func managed() -> RoomState {
        var state = RoomState(roomID: room, profile: "work")
        let detail = RoomDetail(
            id: room, profile: "work", ownerId: "u1", createdAt: Fixture.date, updatedAt: Fixture.date, name: "Launch team",
            inviteCode: "AB12CD34", canManage: true, canMentionAll: true, leadSeatId: planner, memberCount: 1, totalTokens: 0,
            summaryPolicy: SummaryPolicy(everyTurns: 20), handoff: HandoffPolicy(enabled: true, maxDepth: 3),
            seats: [seat(planner, "Planner")], members: [], runs: [], pendingApprovals: [], handoffChains: [chain("c1", .active)],
            memory: RoomMemory(status: .idle, summarizedTurnCount: 0), typing: []
        )
        state.hydrate(detail, messages: MessagePage(items: [], hasMore: false))
        return state
    }

    func testARoomUpdateKeepsWhoManagesItAndItsInviteCode() {
        var state = managed()
        XCTAssertEqual(state.handoff, HandoffPolicy(enabled: true, maxDepth: 3))
        XCTAssertEqual(state.activeChains.map(\.id), ["c1"])
        // The hub sends every member the same room: nobody manages it, no invite code.
        let updated = Room(
            id: room, profile: "work", ownerId: "u1", createdAt: Fixture.date, updatedAt: Fixture.date, name: "Launch",
            inviteCode: nil, canManage: false, canMentionAll: false, leadSeatId: planner, memberCount: 1, totalTokens: 0,
            summaryPolicy: SummaryPolicy(everyTurns: 20), handoff: HandoffPolicy(enabled: false, maxDepth: 5), seats: [seat(planner, "Planner")]
        )
        state.apply(envelope("room.updated", ["room": Fixture.json(updated)]))
        XCTAssertEqual(state.name, "Launch")
        XCTAssertTrue(state.canManage)
        XCTAssertEqual(state.inviteCode, "AB12CD34")
        XCTAssertFalse(state.canMentionAll)
        XCTAssertEqual(state.handoff, HandoffPolicy(enabled: false, maxDepth: 5))
    }

    func testTheSummaryAndTheHandoffChainsFollowTheirEvents() {
        var state = managed()
        let memory = RoomMemory(summary: "We ship Friday.", status: .idle, summarizedTurnCount: 12)
        state.apply(envelope("memory.updated", ["room_id": room, "memory": Fixture.json(memory)]))
        XCTAssertEqual(state.memory?.summary, "We ship Friday.")
        state.apply(envelope("memory.updated", ["room_id": "01J8QK3ZR2W7M5N4P6T8V9X0RO", "memory": Fixture.json(RoomMemory(status: .error, summarizedTurnCount: 0))]))
        XCTAssertEqual(state.memory?.summary, "We ship Friday.")

        state.apply(envelope("handoff.updated", ["room_id": room, "chain": Fixture.json(chain("c1", .stopped, reason: .maxDepth))]))
        XCTAssertTrue(state.activeChains.isEmpty)
        XCTAssertEqual(state.handoffs.map(\.id), ["c1"])
        state.apply(envelope("handoff.updated", ["room_id": room, "chain": Fixture.json(chain("c2", .active))]))
        XCTAssertEqual(state.activeChains.map(\.id), ["c2"])
        XCTAssertEqual(state.handoffs.map(\.id), ["c2", "c1"])
    }
}

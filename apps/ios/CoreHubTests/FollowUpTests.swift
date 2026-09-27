@testable import CoreHub
import CoreHubClient
import XCTest

/// The follow-ups to "every section works on its own" (docs/changes/2026-09-27-twuijri-ios-self-sufficient-2.md):
/// the chats' own order, the message queue, jobs heard live, the trajectory's timeline and log,
/// the skill picker, and a workflow step's model.
final class FollowUpTests: XCTestCase {
    private let date = Date(timeIntervalSince1970: 1_790_000_000)

    private func session(_ id: String, pinned: Bool = false) -> Session {
        Session(id: id, profile: "work", ownerId: "me", createdAt: date, updatedAt: date, agentId: "A1", source: .chat,
                pinned: pinned, archived: false, messageCount: 1, status: .idle, notify: true)
    }

    // MARK: - The chats' own order

    func testTheDraggedOrderIsLaidOverTheHubsAndPinnedStayFirst() {
        let sessions = [session("a"), session("b"), session("c", pinned: true), session("d")]
        XCTAssertEqual(SessionOrder.arrange(sessions, manual: []).map(\.id), ["c", "a", "b", "d"])
        XCTAssertEqual(SessionOrder.arrange(sessions, manual: ["d", "b"]).map(\.id), ["c", "d", "b", "a"])
        XCTAssertEqual(SessionOrder.drop("d", before: "a", shown: ["a", "b", "d"]), ["d", "a", "b"])
        XCTAssertEqual(SessionOrder.drop("a", before: "d", shown: ["a", "b", "d"]), ["b", "a", "d"])
        XCTAssertNil(SessionOrder.drop("a", before: "a", shown: ["a", "b"]))
        XCTAssertNil(SessionOrder.drop("a", before: "b", shown: ["a", "b"]), "already just before it")
        XCTAssertNil(SessionOrder.drop("x", before: "a", shown: ["a", "b"]))
        XCTAssertEqual(SessionOrder.step("b", by: -1, shown: ["a", "b"]), ["b", "a"])
        XCTAssertNil(SessionOrder.step("a", by: -1, shown: ["a", "b"]))
        XCTAssertEqual(SessionOrder.merge(["b", "a"], into: ["x", "a", "y"]), ["b", "a", "x", "y"])
        let groups = SessionGroups.group([session("a"), session("b")], categories: [], conversations: [], keepEmpty: true, manual: ["b"])
        XCTAssertEqual(groups.last?.sessions.map(\.id), ["b", "a"])
    }

    func testTheOrderIsKeptPerView() {
        let defaults = UserDefaults(suiteName: "FollowUpTests.order")!
        defaults.removePersistentDomain(forName: "FollowUpTests.order")
        SessionOrder.write(["b", "a"], scope: "all", defaults: defaults)
        XCTAssertEqual(SessionOrder.read("all", defaults: defaults), ["b", "a"])
        XCTAssertEqual(SessionOrder.read("work", defaults: defaults), [])
    }

    // MARK: - The message queue

    func testOnlyWaitInLineHoldsAMessageBackAndOnlyWhileATurnRuns() {
        XCTAssertTrue(MessageQueueRules.holdsBack(.queue, busy: true))
        XCTAssertFalse(MessageQueueRules.holdsBack(.queue, busy: false))
        XCTAssertFalse(MessageQueueRules.holdsBack(.next, busy: true))
        XCTAssertFalse(MessageQueueRules.holdsBack(.interrupt, busy: true))
        XCTAssertEqual(MessageQueueRules.preview(OutgoingMessage(text: "  fix   the\nbuild ")), "fix the build")
        let long = String(repeating: "a", count: 100)
        XCTAssertEqual(MessageQueueRules.preview(OutgoingMessage(text: long)), String(repeating: "a", count: 80) + "…")
    }

    // MARK: - Jobs heard live

    @MainActor
    func testAJobEventWakesItsWaiterAndIsKept() async throws {
        let feed = JobsFeed()
        let job = Job(id: "J1", profile: "work", ownerId: "me", createdAt: date, updatedAt: date, kind: .install,
                      status: .succeeded, progress: JobProgress())
        let payload = try JSONSerialization.jsonObject(with: CodableHelper().jsonEncoder.encode(job))
        let event = try JSONSerialization.data(withJSONObject: [
            "event": "job.completed", "namespace": "/rt/jobs", "profile": "work", "ts": "2026-09-27T10:00:00Z", "seq": 1,
            "payload": ["job": payload],
        ])
        let started = Date()
        async let waited: Void = feed.wait(for: "J1", timeout: .seconds(10))
        try await Task.sleep(for: .milliseconds(50))
        feed.receive("job.completed", event)
        await waited
        XCTAssertLessThan(Date().timeIntervalSince(started), 5, "woken by the event, not the timeout")
        XCTAssertEqual(feed.jobs["J1"]?.status, .succeeded)
        feed.receive("agent.updated", nil)
        XCTAssertEqual(feed.agentsRevision, 1)
        // Nothing heard: the wait ends at its timeout.
        await feed.wait(for: "J2", timeout: .milliseconds(20))
    }

    // MARK: - The trajectory's timeline and log

    func testIdleStretchesFoldAndParallelCallsGetTheirOwnRows() {
        // Two stretches of work 60 s apart: the gap is folded to 600 ms.
        let axis = TrajectoryRules.axis([(0, 1000), (500, 2000), (62_000, 63_000)])
        XCTAssertEqual(axis.blocks.count, 2)
        XCTAssertEqual(axis.length, 2000 + 600 + 1000)
        XCTAssertEqual(axis.at(0), 0)
        XCTAssertEqual(axis.at(63_000), 1)
        XCTAssertEqual(axis.at(30_000), axis.at(62_000), accuracy: 0.0001, "an instant in a fold sits at the next stretch")
        XCTAssertEqual(axis.folds.count, 1)
        XCTAssertEqual(TrajectoryRules.rows([(0, 10), (5, 15), (12, 20)]), [0, 1, 0])
        XCTAssertEqual(TrajectoryRules.logName("S1"), "session-S1-log.json")
        let running = TrajectoryStep(id: "1", kind: .tool, lane: .tools, exchange: 1, status: .running, startedAt: date, toolCallOnly: true)
        let span = TrajectoryRules.span(running, now: date.addingTimeInterval(2))
        XCTAssertEqual((span?.1 ?? 0) - (span?.0 ?? 0), 2000, accuracy: 1)
        let untimed = TrajectoryStep(id: "2", kind: .turn, lane: .model, exchange: 1, status: .succeeded, toolCallOnly: false)
        XCTAssertNil(TrajectoryRules.span(untimed, now: date))
    }

    // MARK: - The skill picker

    func testSkillsAreOfferedAfterSkillAndFilteredByWhatIsTyped() {
        XCTAssertEqual(SlashCommands.skillQuery("/skill "), "")
        XCTAssertEqual(SlashCommands.skillQuery("/skill rev"), "rev")
        XCTAssertNil(SlashCommands.skillQuery("/skill review now"))
        XCTAssertNil(SlashCommands.skillQuery("/skills"))
        XCTAssertNil(SlashCommands.skillQuery("/skill"))
        let category = SkillCategory(key: "code", name: "Code", skills: [
            Skill(key: "code-review", name: "Code review", description: "Reviews a diff", enabled: true, pinned: false, source: .user, useCount: 0),
            Skill(key: "release", name: "Release notes", description: "Writes the review of a release", enabled: true, pinned: false, source: .user, useCount: 0),
            Skill(key: "off", name: "Off", enabled: false, pinned: false, source: .user, useCount: 0),
        ])
        let skills = SlashCommands.skills([category])
        XCTAssertEqual(skills.map(\.key), ["code-review", "release"], "a switched-off skill is not offered")
        XCTAssertEqual(SlashCommands.filterSkills(skills, "rel").map(\.key), ["release"])
        XCTAssertEqual(SlashCommands.filterSkills(skills, "review").map(\.key), ["code-review", "release"])
    }

    // MARK: - A workflow step's model

    func testAStepsModelIsNamedFromTheCatalogue() {
        let options = [ChatControls.ModelOption(value: "openai/gpt-5", label: "GPT-5", group: "OpenAI")]
        XCTAssertEqual(WorkflowEditRules.modelLabel("openai/gpt-5", options: options), "GPT-5")
        XCTAssertEqual(WorkflowEditRules.modelLabel("local/other", options: options), "local/other")
        XCTAssertNil(WorkflowEditRules.modelLabel(nil, options: options))
        XCTAssertNil(WorkflowEditRules.modelLabel("", options: options))
    }
}

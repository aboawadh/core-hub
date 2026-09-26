@testable import CoreHub
import CoreHubClient
import XCTest

/// A task on its own (batch 2, Tasks I): the moves its detail offers, who may assign, stop or take
/// it back, and what the create, edit and assign forms send. Android's TaskDetailTest is the twin.
final class TaskDetailTests: XCTestCase {
    func testTheDetailOffersOnlyTheMovesTheHubAccepts() {
        XCTAssertEqual(TaskRules.moves(from: .todo).map(\.to), [.ready, .scheduled, .blocked])
        XCTAssertTrue(TaskRules.moves(from: .todo).last!.transition.requiresReason)
        XCTAssertEqual(TaskRules.moves(from: .running).map(\.to), [.scheduled, .blocked, .review, .done])
        // Reopening a review is one move, not two.
        XCTAssertEqual(TaskRules.moves(from: .review).map(\.to), [.todo, .done])
        // Done goes only to the archive, and asks first.
        let done = TaskRules.moves(from: .done)
        XCTAssertEqual(done.map(\.to), [.archived])
        XCTAssertTrue(done[0].transition.confirm)
        XCTAssertEqual(TaskRules.moves(from: .archived), [])
    }

    func testStopAssignAndUnassignFollowTheStatusAndTheCardsOwner() {
        let agent = Assignee(kind: .agent, id: "a", name: "Claude Code")
        XCTAssertTrue(TaskRules.canStop(.running))
        XCTAssertFalse(TaskRules.canStop(.ready))
        XCTAssertTrue(TaskRules.canAssign(.todo, hermes: false))
        XCTAssertFalse(TaskRules.canAssign(.done, hermes: false))
        XCTAssertFalse(TaskRules.canAssign(.todo, hermes: true), "Hermes dispatches its own cards")
        XCTAssertTrue(TaskRules.canUnassign(agent, hermes: false))
        XCTAssertFalse(TaskRules.canUnassign(Assignee(kind: .user, id: "u", name: "Sara"), hermes: false))
        XCTAssertFalse(TaskRules.canUnassign(nil, hermes: false))
        XCTAssertTrue(TaskRules.fromHermes(HubTaskAllOfExternal(source: .hermes, id: "t1")))
        XCTAssertFalse(TaskRules.fromHermes(nil))
    }

    func testWhatItWaitsForIsSaidOnlyBeforeItRuns() {
        let waiting = [TaskDependencyState(id: "d1", title: "Design", status: .todo)]
        XCTAssertEqual(TaskRules.waiting(.ready, waiting).map(\.id), ["d1"])
        XCTAssertEqual(TaskRules.waiting(.running, waiting), [])
        XCTAssertEqual(TaskRules.doneDependencies(dependsOn: ["d1", "d2", "d3"], waitingOn: waiting), 2)
        XCTAssertEqual(TaskRules.doneDependencies(dependsOn: [], waitingOn: nil), 0)
    }

    func testAnEditSendsOnlyWhatChanged() {
        let original = TaskRules.editValues(title: "Plan", description: "Old", priority: .normal, projectID: "P1")
        XCTAssertNil(TaskRules.patch(from: original, to: original))
        var values = original
        values["title"] = "  Plan the launch "
        values["priority"] = "urgent"
        let patch = TaskRules.patch(from: original, to: values)
        XCTAssertEqual(patch?.title, "Plan the launch")
        XCTAssertEqual(patch?.priority, .urgent)
        XCTAssertNil(patch?.description)
        XCTAssertNil(patch?.projectId)
        XCTAssertEqual(patch?.sendNull, [], "an untouched description stays out")
        // A cleared description is sent as `null` (§114); another project by its id.
        values = original
        values["description"] = ""
        values["project"] = "P2"
        let cleared = TaskRules.patch(from: original, to: values)
        XCTAssertNil(cleared?.description)
        XCTAssertEqual(cleared?.sendNull, [.description])
        XCTAssertEqual(cleared?.projectId, "P2")
        let body = try? JSONSerialization.jsonObject(with: CodableHelper().jsonEncoder.encode(XCTUnwrap(cleared))) as? [String: Any]
        XCTAssertTrue(body?["description"] is NSNull)
        XCTAssertEqual(body?["project_id"] as? String, "P2")
        XCTAssertNil(body?["title"])
        // A title emptied is not sent (the form refuses it first).
        values = original
        values["title"] = "   "
        XCTAssertNil(TaskRules.patch(from: original, to: values))
    }

    func testANewTaskLandsInTheProfilesListAndStartsOnlyWhenAsked() {
        let plain = TaskRules.create(["title": " Write the notes ", "description": "", "project": TaskRules.none, "priority": "high", "agent": TaskRules.none, "start": "true"])
        XCTAssertEqual(plain.task.title, "Write the notes")
        XCTAssertNil(plain.task.projectId)
        XCTAssertNil(plain.task.description)
        XCTAssertEqual(plain.task.priority, .high)
        XCTAssertNil(plain.task.assigneeAgentId)
        XCTAssertNil(plain.start, "nobody to start it")

        let started = TaskRules.create(["title": "Ship", "project": "P1", "agent": "A1", "start": "true", "description": "**Now**"])
        XCTAssertEqual(started.task.projectId, "P1")
        XCTAssertEqual(started.task.assigneeAgentId, "A1")
        XCTAssertEqual(started.task.description, "**Now**")
        XCTAssertEqual(started.start, "A1")

        let assigned = TaskRules.create(["title": "Later", "agent": "A1", "start": "false"])
        XCTAssertEqual(assigned.task.assigneeAgentId, "A1")
        XCTAssertNil(assigned.start)
    }

    func testAnAssignCarriesTheAgentItsWordsAndWhetherToStart() {
        XCTAssertNil(TaskRules.assign(["agent": ""]))
        let now = TaskRules.assign(["agent": "A1", "instructions": "  Begin with the account tab. ", "start": "true"])
        XCTAssertEqual(now?.agentId, "A1")
        XCTAssertEqual(now?.instructions, "Begin with the account tab.")
        XCTAssertEqual(now?.start, true)
        let later = TaskRules.assign(["agent": "A1", "instructions": " ", "start": "false"])
        XCTAssertNil(later?.instructions)
        XCTAssertEqual(later?.start, false)
    }
}

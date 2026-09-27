@testable import CoreHub
import CoreHubClient
import XCTest

/// Tasks II (batch 5): the due date in the edit form and the shared date field, the checklist's
/// ticks and drag, the definition of done and constraints, the board's bulk edits by profile, and
/// a project's create and edit. Android's TasksTwoTest is the twin.
final class TasksTwoTests: XCTestCase {
    private let date = Date(timeIntervalSince1970: 1_790_000_000)
    private let utc = TimeZone(identifier: "UTC")!

    private func encoded<T: Encodable>(_ value: T) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: CodableHelper().jsonEncoder.encode(value)) as? [String: Any])
    }

    private func task(_ id: String, _ status: TaskStatus, profile: String) -> HubTask {
        HubTask(
            id: id, profile: profile, ownerId: "me", createdAt: date, updatedAt: date, projectId: "p", title: "t\(id)",
            status: status, priority: .normal, tags: [], autoStart: false, position: "a0",
            subtaskCounts: HubTaskAllOfSubtaskCounts(total: 0, done: 0), dependsOn: [], lastRun: HubTaskAllOfLastRun(),
            attemptCount: 0, attachmentIds: []
        )
    }

    private func line(_ id: String, _ index: Int, _ status: Subtask.Status = .todo) -> Subtask {
        Subtask(id: id, taskId: "T", index: index, title: "line \(id)", status: status, updatedAt: date)
    }

    // MARK: - The date field and the due date

    func testADateFieldReadsAndWritesToTheMinuteAndRefusesWhatIsNotADate() {
        let field = FormField(key: "due", label: "Due", kind: .date)
        XCTAssertNil(FormRules.problem(field, ""), "no date is a value when the field is not required")
        XCTAssertEqual(FormRules.problem(FormField(key: "due", label: "Due", kind: .date, required: true), ""), .required)
        XCTAssertNil(FormRules.problem(field, "2026-10-01T09:30"))
        XCTAssertEqual(FormRules.problem(field, "tomorrow"), .notADate)
        let when = FormRules.date("2026-10-01T09:30", zone: utc)
        XCTAssertEqual(when.map { FormRules.dateText($0, zone: utc) }, "2026-10-01T09:30")
        XCTAssertEqual(FormRules.dateText(nil), "")
        // Latin digits whatever the phone's language.
        XCTAssertEqual(FormRules.dateText(Date(timeIntervalSince1970: 0), zone: utc), "1970-01-01T00:00")
    }

    func testTheDueDateIsSetChangedOrClearedAsNull() throws {
        let original = TaskRules.editValues(title: "Plan", description: nil, priority: .normal, projectID: "P1", dueAt: nil)
        XCTAssertEqual(original["due"], "")
        var values = original
        values["due"] = FormRules.dateText(date)
        let set = try XCTUnwrap(TaskRules.patch(from: original, to: values))
        XCTAssertEqual(set.dueAt.map { Int($0.timeIntervalSince1970) / 60 }, Int(date.timeIntervalSince1970) / 60)
        XCTAssertEqual(set.sendNull, [])

        let dated = TaskRules.editValues(title: "Plan", description: nil, priority: .normal, projectID: "P1", dueAt: date)
        XCTAssertNil(TaskRules.patch(from: dated, to: dated), "an untouched date is not sent")
        values = dated
        values["due"] = ""
        let cleared = try XCTUnwrap(TaskRules.patch(from: dated, to: values))
        XCTAssertEqual(cleared.sendNull, [.dueAt])
        XCTAssertTrue(try encoded(cleared)["due_at"] is NSNull)
    }

    // MARK: - The checklist

    func testATickFlipsALineAndANewLineNeedsWords() {
        XCTAssertEqual(SubtaskRules.toggled(.todo), .done)
        XCTAssertEqual(SubtaskRules.toggled(.inProgress), .done)
        XCTAssertEqual(SubtaskRules.toggled(.done), .todo)
        XCTAssertNil(SubtaskRules.title("   "))
        XCTAssertEqual(SubtaskRules.title("  Privacy tab "), "Privacy tab")
        XCTAssertEqual(SubtaskRules.title(String(repeating: "a", count: 400))?.count, SubtaskRules.titleMax)
        XCTAssertEqual(SubtaskRules.doneCount([line("a", 0, .done), line("b", 1)]), 1)
    }

    func testADragTellsTheHubOnlyTheLinesWhosePlaceChanged() {
        let lines = [line("a", 0), line("b", 1), line("c", 2), line("d", 3)]
        // "d" dragged to the top.
        let order = SubtaskRules.order(lines.map(\.id), from: IndexSet(integer: 3), to: 0)
        XCTAssertEqual(order, ["d", "a", "b", "c"])
        let moves = SubtaskRules.reindex(lines, order: order)
        XCTAssertEqual(moves.map { $0.id }, ["d", "a", "b", "c"])
        XCTAssertEqual(moves.map { $0.index }, [0, 1, 2, 3])
        // "a" dragged below "b": only those two move.
        let swap = SubtaskRules.order(lines.map(\.id), from: IndexSet(integer: 0), to: 2)
        XCTAssertEqual(swap, ["b", "a", "c", "d"])
        XCTAssertEqual(SubtaskRules.reindex(lines, order: swap).map { $0.id }, ["b", "a"])
        // Dropped where it was: nothing to say.
        XCTAssertTrue(SubtaskRules.reindex(lines, order: lines.map(\.id)).isEmpty)
    }

    // MARK: - Definition of done and constraints (§104)

    func testTheListsAreTickedOnlyInReviewAndSentWhole() throws {
        XCTAssertTrue(CheckLines.canTick(.review))
        XCTAssertFalse(CheckLines.canTick(.ready))
        XCTAssertNil(CheckLines.adding([], "  "))
        let one = try XCTUnwrap(CheckLines.adding([], " Tests pass "))
        XCTAssertEqual(one, [TaskCheckItem(text: "Tests pass", checked: false)])
        let full = Array(repeating: TaskCheckItem(text: "x", checked: false), count: CheckLines.linesMax)
        XCTAssertNil(CheckLines.adding(full, "one more"), "the hub keeps at most thirty lines")
        let two = CheckLines.ticking(one + [TaskCheckItem(text: "No new deps", checked: false)], at: 1, true)
        XCTAssertEqual(two.map(\.checked), [false, true])
        XCTAssertEqual(CheckLines.removing(two, at: IndexSet(integer: 0)).map(\.text), ["No new deps"])
        let body = try encoded(CheckLines.patch(.done, two))
        let lines = try XCTUnwrap(body["definition_of_done"] as? [[String: Any]])
        XCTAssertEqual(lines.map { $0["text"] as? String }, ["Tests pass", "No new deps"])
        XCTAssertEqual(lines.map { $0["checked"] as? Bool }, [false, true])
        XCTAssertNil(body["constraints"])
        XCTAssertNotNil(try encoded(CheckLines.patch(.constraints, two))["constraints"])
    }

    // MARK: - Bulk (§103)

    func testABulkEditGoesOncePerProfileAndCountsTheRefusals() {
        let tasks = [task("1", .done, profile: "work"), task("2", .todo, profile: "home"), task("3", .done, profile: "work")]
        let calls = BulkRules.calls(tasks)
        XCTAssertEqual(calls.map { $0.profile }, ["work", "home"])
        XCTAssertEqual(calls.map { $0.ids }, [["1", "3"], ["2"]])
        let many = (0 ..< 150).map { task("\($0)", .todo, profile: "work") }
        XCTAssertEqual(BulkRules.calls(many).map { $0.ids.count }, [100, 50], "at most a hundred ids a call")
        XCTAssertEqual(BulkRules.idsParam(["1", "3"]), "1,3")
        let tally = BulkRules.tally([
            BulkResult(results: [BulkResultResultsInner(id: "1", ok: true), BulkResultResultsInner(id: "3", ok: false)]),
            BulkResult(results: [BulkResultResultsInner(id: "2", ok: true)]),
        ])
        XCTAssertEqual(tally.changed, 2)
        XCTAssertEqual(tally.refused, 1)
        XCTAssertFalse(BulkRules.archivable(tasks), "only finished work is archived")
        XCTAssertTrue(BulkRules.archivable([tasks[0], tasks[2]]))
        XCTAssertFalse(BulkRules.archivable([]))
    }

    // MARK: - Projects

    func testAProjectIsMadeWithWhatWasGivenAndEditedWithOnlyWhatChanged() throws {
        let made = try encoded(ProjectRules.create(["name": "  Launch ", "repository": "", "branch": ""]))
        XCTAssertEqual(made["name"] as? String, "Launch")
        XCTAssertNil(made["working_dir"], "no repository: not sent")
        let original = ["name": "Launch", "status": "active", "repository": "/data/workspaces/work/site", "branch": "main"]
        XCTAssertNil(ProjectRules.patch(from: original, to: original))
        var values = original
        values["name"] = "Launch v2"
        values["status"] = "archived"
        let renamed = try XCTUnwrap(ProjectRules.patch(from: original, to: values))
        XCTAssertEqual(renamed.name, "Launch v2")
        XCTAssertEqual(renamed.status, .archived)
        XCTAssertEqual(renamed.sendNull, [])
        values = original
        values["repository"] = " "
        values["branch"] = ""
        let unlinked = try XCTUnwrap(ProjectRules.patch(from: original, to: values))
        let body = try encoded(unlinked)
        XCTAssertTrue(body["working_dir"] is NSNull, "an emptied repository is sent as null (§114)")
        XCTAssertNil(body["default_branch"], "an emptied branch is left as it was")
        XCTAssertEqual(ProjectRules.toggledArchive(.active), .archived)
        XCTAssertEqual(ProjectRules.toggledArchive(.archived), .active)
        XCTAssertEqual(ProjectRules.values(nil)["status"], "active")
    }
}

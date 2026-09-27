@testable import CoreHub
import CoreHubClient
import XCTest

/// Drawing a workflow on the phone (WorkflowEditRules): what is saved is the contract's own
/// `WorkflowWrite`, so the web's canvas opens it unchanged.
final class WorkflowEditorTests: XCTestCase {
    private func empty() -> WorkflowEditRules.Draft { WorkflowEditRules.draft(nil) }

    func testAddingStepsNamesThemPlacesThemAndLinksEachAfterTheOneBefore() {
        var draft = empty()
        let first = WorkflowEditRules.add(.agent, title: "Draft", to: &draft, agentID: "A1")
        let second = WorkflowEditRules.add(.approval, title: "Check", to: &draft)
        let third = WorkflowEditRules.add(.agent, title: "Publish", to: &draft)
        XCTAssertEqual([first, second, third], ["agent_1", "approval_1", "agent_2"])
        XCTAssertEqual(draft.nodes[0].agentId, "A1")
        XCTAssertNil(draft.nodes[1].agentId)
        XCTAssertEqual(draft.edges.map { "\($0.from)>\($0.to):\($0.route.rawValue)" }, ["agent_1>approval_1:success", "approval_1>agent_2:success"])
        XCTAssertEqual(draft.nodes[0].position, WorkflowNodePosition(x: 40, y: 40))
        XCTAssertEqual(draft.nodes[1].position.x, 40 + WorkflowEditRules.nodeWidth + 72)
        XCTAssertEqual(WorkflowEditRules.add(.delay, title: "Wait", to: &draft, after: "agent_1"), "delay_1")
        XCTAssertEqual(draft.nodes.last?.input, "60")
        // Its spot after agent_1 is approval_1's: it goes below.
        XCTAssertEqual(draft.nodes.last?.position.x, draft.nodes[1].position.x)
        XCTAssertGreaterThan(draft.nodes.last?.position.y ?? 0, draft.nodes[1].position.y)
    }

    func testRemovingAStepRemovesItsLinksAndConnectingTwiceOrToItselfDoesNothing() {
        var draft = empty()
        WorkflowEditRules.add(.agent, title: "A", to: &draft)
        WorkflowEditRules.add(.notify, title: "B", to: &draft)
        XCTAssertNil(WorkflowEditRules.connect("agent_1", to: "agent_1", route: .always, in: &draft))
        XCTAssertNil(WorkflowEditRules.connect("agent_1", to: "nope", route: .always, in: &draft))
        XCTAssertEqual(WorkflowEditRules.connect("agent_1", to: "notify_1", route: .success, in: &draft), "e1")
        XCTAssertEqual(WorkflowEditRules.connect("agent_1", to: "notify_1", route: .failure, in: &draft), "e2")
        XCTAssertEqual(draft.edges.count, 2)
        WorkflowEditRules.remove("notify_1", from: &draft)
        XCTAssertEqual(draft.nodes.map(\.id), ["agent_1"])
        XCTAssertTrue(draft.edges.isEmpty)
    }

    func testAStepReadsTheOutputOfEveryStepWithAPathToIt() {
        var draft = empty()
        WorkflowEditRules.add(.agent, title: "A", to: &draft)
        WorkflowEditRules.add(.condition, title: "B", to: &draft)
        WorkflowEditRules.add(.notify, title: "C", to: &draft)
        XCTAssertEqual(WorkflowEditRules.upstream(draft, of: "notify_1").map(\.id), ["agent_1", "condition_1"])
        XCTAssertTrue(WorkflowEditRules.upstream(draft, of: "agent_1").isEmpty)
        WorkflowEditRules.move("notify_1", by: -1, in: &draft)
        XCTAssertEqual(draft.nodes.map(\.id), ["agent_1", "notify_1", "condition_1"])
    }

    func testConditionsSplitAndJoinAsTheEngineReadsThem() {
        XCTAssertEqual(WorkflowEditRules.split("input exists"), WorkflowEditRules.Condition(path: "input", op: "exists", value: ""))
        XCTAssertEqual(WorkflowEditRules.split(#"steps.a.output contains "done""#), WorkflowEditRules.Condition(path: "steps.a.output", op: "contains", value: "done"))
        XCTAssertEqual(WorkflowEditRules.split("trigger.count >= 3"), WorkflowEditRules.Condition(path: "trigger.count", op: ">=", value: "3"))
        XCTAssertNil(WorkflowEditRules.split("a and b"))
        XCTAssertEqual(WorkflowEditRules.join(.init(path: "input", op: "==", value: "yes")), #"input == "yes""#)
        XCTAssertEqual(WorkflowEditRules.join(.init(path: "trigger.count", op: ">", value: " 2 ")), "trigger.count > 2")
        XCTAssertEqual(WorkflowEditRules.join(.init(path: "input", op: "empty", value: "x")), "input empty")
    }

    func testWhatIsSavedIsTheContractsWrite() {
        var draft = empty()
        draft.name = "  Morning  "
        WorkflowEditRules.add(.agent, title: "A", to: &draft, agentID: "A1")
        WorkflowEditRules.add(.approval, title: "Ok?", to: &draft)
        draft.nodes[1].agentId = "stray"
        draft.nodes[1].approvalRequired = true
        let write = WorkflowEditRules.write(draft, clearing: true)
        XCTAssertEqual(write.name, "Morning")
        XCTAssertNil(write.description)
        XCTAssertEqual(write.sendNull, [.description], "an emptied description is cleared on the hub")
        XCTAssertEqual(write.nodes?[0].agentId, "A1")
        XCTAssertNil(write.nodes?[1].agentId, "only an agent step names an agent")
        XCTAssertEqual(write.nodes?[1].approvalRequired, false)
        XCTAssertEqual(WorkflowEditRules.write(draft, clearing: false).sendNull, [])
        XCTAssertTrue(WorkflowEditRules.canSave(draft, validation: nil))
        XCTAssertFalse(WorkflowEditRules.canSave(draft, validation: WorkflowValidation(valid: false, problems: [WorkflowIssue(code: "agent_missing", message: "x")], warnings: [])))
        draft.name = " "
        XCTAssertFalse(WorkflowEditRules.canSave(draft, validation: nil))
    }

    func testFindingsAreSaidPerStepInThePersonsLanguage() {
        let l10n = L10n(.en, bundle: Bundle(for: AppModel.self))
        let mine = WorkflowIssue(code: "agent_missing", nodeId: "agent_1", message: "hub words")
        let unknown = WorkflowIssue(code: "something_new", message: "The hub's own words")
        let general = WorkflowIssue(code: "workflow_empty", message: "x")
        let validation = WorkflowValidation(valid: false, problems: [mine, general], warnings: [unknown])
        XCTAssertEqual(WorkflowEditRules.issues(validation, node: "agent_1"), [mine])
        XCTAssertEqual(WorkflowEditRules.general(validation), [general, unknown])
        XCTAssertTrue(WorkflowEditRules.isProblem(validation, mine))
        XCTAssertFalse(WorkflowEditRules.isProblem(validation, unknown))
        XCTAssertEqual(WorkflowEditRules.describe(mine, l10n), l10n("workflow_editor.issues.agent_missing"))
        XCTAssertEqual(WorkflowEditRules.describe(unknown, l10n), "The hub's own words")
    }

    func testSavedLimitsAreReadFromTheFields() {
        let (limits, problem) = WorkflowEditRules.limits(minutes: "30", cost: "2.5", stepMinutes: "")
        XCTAssertNil(problem)
        XCTAssertEqual(limits?.maxDurationSeconds, 1800)
        XCTAssertEqual(limits?.maxCost?.amount, "2.50")
        XCTAssertNil(limits?.stepTimeoutSeconds)
        XCTAssertEqual(WorkflowEditRules.limits(minutes: "0", cost: "", stepMinutes: "").1, .duration)
        let (none, _) = WorkflowEditRules.limits(minutes: "", cost: "", stepMinutes: "")
        XCTAssertEqual(none, WorkflowLimits(), "every field empty: no limit at all")
    }
}

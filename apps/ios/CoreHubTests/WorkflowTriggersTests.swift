@testable import CoreHub
import CoreHubClient
import XCTest

/// A workflow's triggers on the phone (WorkflowTriggerRules, §123): what each change sends, so the
/// hub reads the same thing the web sends.
final class WorkflowTriggersTests: XCTestCase {
    private func json(_ value: some Encodable) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(value)) as? [String: Any])
    }

    private func trigger(_ preset: WorkflowTriggerPreset, enabled: Bool = true, secret: Bool = true, events: [String] = []) -> WorkflowTrigger {
        WorkflowTrigger(
            id: "01J8QK3ZR2W7M5N4P6T8V9X0TR", profile: "work", ownerId: "u1", createdAt: Fixture.date, updatedAt: Fixture.date,
            workflowId: "01J8QK3ZR2W7M5N4P6T8V9X0WF", name: "ClickUp", preset: preset, enabled: enabled, events: events,
            secretStored: secret, path: "/hooks/workflows/abc"
        )
    }

    func testANewTriggerIsNamedAfterItsSenderAndClickUpTakesNewTasksAndStatusChanges() throws {
        let clickup = WorkflowTriggerRules.newTrigger(.clickup, name: "ClickUp")
        XCTAssertEqual(clickup.name, "ClickUp")
        XCTAssertEqual(clickup.events, ["taskCreated", "taskStatusUpdated"])
        let github = WorkflowTriggerRules.newTrigger(.github, name: "GitHub")
        XCTAssertNil(github.events)
        XCTAssertEqual(try json(github)["preset"] as? String, "github")
        XCTAssertEqual(WorkflowTriggerRules.presets, [.clickup, .github, .genericHmac, .token])
        XCTAssertEqual(WorkflowTriggerRules.clickUpEvents.count, 11)
        XCTAssertEqual(WorkflowTriggerRules.clickUpEvents.first, "taskCreated")
    }

    func testEventsAreTypedCommaSeparatedOrTickedOneByOne() {
        XCTAssertEqual(WorkflowTriggerRules.events(fromText: " issues, pull_request ,, push "), ["issues", "pull_request", "push"])
        XCTAssertEqual(WorkflowTriggerRules.events(fromText: "  "), [])
        XCTAssertEqual(WorkflowTriggerRules.toggle("taskMoved", on: true, in: ["taskCreated"]), ["taskCreated", "taskMoved"])
        XCTAssertEqual(WorkflowTriggerRules.toggle("taskCreated", on: true, in: ["taskCreated"]), ["taskCreated"])
        XCTAssertEqual(WorkflowTriggerRules.toggle("taskCreated", on: false, in: ["taskCreated", "taskMoved"]), ["taskMoved"])
    }

    func testAnEmptiedHeaderOrPrefixIsAnExplicitNullAndTheSecretIsNeverSentEmpty() throws {
        XCTAssertTrue(try json(WorkflowTriggerRules.headerPatch("  "))["signature_header"] is NSNull)
        XCTAssertEqual(try json(WorkflowTriggerRules.headerPatch(" X-Sig "))["signature_header"] as? String, "X-Sig")
        XCTAssertTrue(try json(WorkflowTriggerRules.prefixPatch(""))["signature_prefix"] is NSNull)
        XCTAssertEqual(try json(WorkflowTriggerRules.prefixPatch("sha256="))["signature_prefix"] as? String, "sha256=")
        XCTAssertEqual(try json(WorkflowTriggerRules.encodingPatch(.base64))["signature_encoding"] as? String, "base64")
        XCTAssertNil(WorkflowTriggerRules.secretPatch("   "))
        let secret = try json(XCTUnwrap(WorkflowTriggerRules.secretPatch(" s3cret ")))
        XCTAssertEqual(secret["secret"] as? String, "s3cret")
        XCTAssertEqual(Set(secret.keys), ["secret"], "a patch says only what changed")
        XCTAssertEqual(WorkflowTriggerRules.headerPlaceholder(.token), "X-Webhook-Token")
        XCTAssertEqual(WorkflowTriggerRules.headerPlaceholder(.genericHmac), "X-Signature")
    }

    func testATestEventNeedsAStoredSecretAndTheTriggerOn() throws {
        XCTAssertTrue(WorkflowTriggerRules.canTest(trigger(.clickup)))
        XCTAssertFalse(WorkflowTriggerRules.canTest(trigger(.clickup, secret: false)))
        XCTAssertFalse(WorkflowTriggerRules.canTest(trigger(.clickup, enabled: false)))
        XCTAssertEqual(WorkflowTriggerRules.test(event: " taskMoved ").event, "taskMoved")
        XCTAssertTrue(try json(WorkflowTriggerRules.test(event: ""))["event"] is NSNull, "none typed: the trigger's first event")
    }

    func testADeliveryLineNamesItsEventTaskAndEventIdLeftToRight() {
        func delivery(event: String?, task: String?, id: String?, status: WorkflowTriggerDeliveryStatus = .runStarted) -> WorkflowTriggerDelivery {
            WorkflowTriggerDelivery(
                id: "d1", triggerId: "t1", workflowId: "w1", receivedAt: Fixture.date, status: status,
                event: event, eventId: id, taskId: task, filtered: false, test: false
            )
        }
        XCTAssertEqual(WorkflowTriggerRules.line(delivery(event: "taskCreated", task: "86abc", id: "h1")), "taskCreated · task 86abc · #h1")
        XCTAssertEqual(WorkflowTriggerRules.line(delivery(event: "push", task: nil, id: nil)), "push")
        XCTAssertNil(WorkflowTriggerRules.line(delivery(event: nil, task: nil, id: "h1")), "the web shows the line only for an event or a task")
        XCTAssertEqual(WorkflowTriggerRules.kind(.runSucceeded), .good)
        XCTAssertEqual(WorkflowTriggerRules.kind(.signatureRejected), .bad)
        XCTAssertEqual(WorkflowTriggerRules.kind(.runFailed), .bad)
        XCTAssertEqual(WorkflowTriggerRules.kind(.received), .info)
        XCTAssertEqual(WorkflowTriggerRules.kind(.duplicate), .neutral)
    }

    func testAnOlderHubsNotFoundHidesTheSectionButOtherFailuresShow() {
        let missing = HubFailure(kind: .http, status: 404, code: "not_found", message: nil, operationID: nil, requestID: nil, detail: "")
        let refused = HubFailure(kind: .http, status: 403, code: "forbidden", message: nil, operationID: nil, requestID: nil, detail: "")
        let offline = HubFailure(kind: .connection, status: 0, code: nil, message: nil, operationID: nil, requestID: nil, detail: "")
        XCTAssertTrue(WorkflowTriggerRules.unsupported(missing))
        XCTAssertFalse(WorkflowTriggerRules.unsupported(refused))
        XCTAssertFalse(WorkflowTriggerRules.unsupported(offline))
    }
}

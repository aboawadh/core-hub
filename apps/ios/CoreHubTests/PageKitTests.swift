// The shared page pieces' rules (docs/clients/phone-pages.md), without drawing them; Android's
// PageKitTest.kt checks the same rules.
@testable import CoreHub
import CoreHubClient
import XCTest

final class PageKitTests: XCTestCase {
    func testAFormRefusesWhatItCannotSaveAndSaysWhy() {
        let name = FormField(key: "name", label: "Name", required: true)
        let port = FormField(key: "port", label: "Port", kind: .number, min: 1, max: 65535, integer: true)
        let ratio = FormField(key: "ratio", label: "Ratio", kind: .number)
        let mode = FormField(key: "mode", label: "Mode", kind: .choice, options: [FormOption(value: "a", label: "A"), FormOption(value: "b", label: "B")])
        let on = FormField(key: "on", label: "On", kind: .toggle, required: true)
        XCTAssertEqual(FormRules.problem(name, "  "), .required)
        XCTAssertNil(FormRules.problem(name, "x"))
        XCTAssertNil(FormRules.problem(port, ""), "an empty optional field is fine")
        XCTAssertEqual(FormRules.problem(port, "eighty"), .notANumber)
        XCTAssertEqual(FormRules.problem(port, "80.5"), .notWhole)
        XCTAssertNil(FormRules.problem(port, "80.0"), "80.0 is whole")
        XCTAssertEqual(FormRules.problem(port, "0"), .tooSmall(1))
        XCTAssertEqual(FormRules.problem(port, "70000"), .tooLarge(65535))
        XCTAssertNil(FormRules.problem(ratio, "0,7"), "a comma is a decimal point")
        XCTAssertEqual(FormRules.number("0,7"), Decimal(string: "0.7"))
        XCTAssertEqual(FormRules.problem(mode, "c"), .notAnOption)
        XCTAssertNil(FormRules.problem(on, ""), "a toggle always has a value")
        XCTAssertEqual(Set(FormRules.problems([name, port, ratio], ["port": "x"]).keys), ["name", "port"])
        XCTAssertTrue(FormRules.problems([name, port], ["name": "hub", "port": "8080"]).isEmpty)
    }

    func testATriggerIsBuiltFromWhatWasTypedAndRefusedWithAReason() {
        let zone = "Asia/Riyadh"
        let cron = TriggerDraft(cron: " 0  9 * * 1-5 ", timezone: zone)
        XCTAssertEqual(TriggerRules.build(cron), ScheduleTrigger(kind: .cron, expression: "0 9 * * 1-5", timezone: zone))
        var bad = cron
        bad.cron = "0 9 * *"
        XCTAssertEqual(TriggerRules.problem(bad), .cron)
        let every = TriggerDraft(kind: .interval, every: "2", unit: .hours, timezone: zone)
        XCTAssertEqual(TriggerRules.build(every)?.everyMinutes, 120)
        var zero = every
        zero.every = "0"
        XCTAssertEqual(TriggerRules.problem(zero), .every)
        XCTAssertEqual(TriggerRules.problem(TriggerDraft(kind: .once, timezone: zone)), .when)
        var mars = cron
        mars.timezone = "Mars/Olympus"
        XCTAssertEqual(TriggerRules.problem(mars), .zone)
        XCTAssertNil(TriggerRules.build(mars))
    }

    func testASavedTriggerComesBackAsTheDraftThatMakesIt() {
        XCTAssertTrue(TriggerRules.split(120) == (2, .hours))
        XCTAssertTrue(TriggerRules.split(1440) == (1, .days))
        XCTAssertTrue(TriggerRules.split(90) == (90, .minutes))
        let every = ScheduleTrigger(kind: .interval, everyMinutes: 1440, timezone: "UTC")
        XCTAssertEqual(TriggerRules.build(TriggerRules.draft(every)), every)
        let once = ScheduleTrigger(kind: .once, runAt: Fixture.date, timezone: "Asia/Riyadh")
        XCTAssertEqual(TriggerRules.build(TriggerRules.draft(once)), once)
        // 2026-09-27 23:10 UTC: tomorrow at 09:00 in Riyadh (UTC+3) is 2026-09-29 06:00 UTC.
        let now = Date(timeIntervalSince1970: 1_790_550_600)
        let nine = TriggerRules.tomorrowAtNine(TriggerDraft(timezone: "Asia/Riyadh"), now: now).runAt
        XCTAssertEqual(nine.map { ISO8601DateFormatter().string(from: $0) }, "2026-09-29T06:00:00Z")
    }

    private struct Row: Identifiable, Equatable { let id: String }

    @MainActor
    func testAPagedListAddsTheNextPageKeepsRowsOnceAndKeepsThemOnAFailure() async {
        var fail = false
        let pages: [String?: ListPage<Row>] = [nil: ListPage(items: [Row(id: "a"), Row(id: "b")], next: "p2"),
                                               "p2": ListPage(items: [Row(id: "b"), Row(id: "c")], next: nil)]
        let list = PagedList<Row> { cursor in
            if fail { throw HubFailure(kind: .http, status: 500, code: "boom", message: "down", operationID: nil, requestID: nil, detail: "") }
            return pages[cursor]!
        }
        XCTAssertTrue(list.loading)
        await list.refresh()
        XCTAssertEqual(list.items.map(\.id), ["a", "b"])
        XCTAssertTrue(list.hasMore)
        await list.loadMore()
        XCTAssertEqual(list.items.map(\.id), ["a", "b", "c"])
        XCTAssertFalse(list.hasMore)
        fail = true
        await list.refresh()
        XCTAssertEqual(list.items.map(\.id), ["a", "b", "c"], "a failure keeps what was loaded")
        XCTAssertEqual(list.failure?.status, 500)
        list.remove("b")
        XCTAssertEqual(list.items.map(\.id), ["a", "c"])
    }

    func testASaveRefusedAsChangedElsewhereIsToldApart() {
        XCTAssertTrue(DocumentRules.changedElsewhere(HubFailure(kind: .http, status: 409, code: "changed", message: nil, operationID: nil, requestID: nil, detail: "")))
        XCTAssertFalse(DocumentRules.changedElsewhere(HubFailure(kind: .http, status: 409, code: "conflict", message: nil, operationID: nil, requestID: nil, detail: "")))
    }
}

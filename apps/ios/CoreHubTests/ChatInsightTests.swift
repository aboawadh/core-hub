// The chat insight's rules (apps batch 6): the context ring and its sheet, compress with a focus,
// the chat's runs, the subagents tree, and the changed files' diffs. Android's ChatInsightTest.kt
// checks the same rules.
@testable import CoreHub
import CoreHubClient
import XCTest

final class ChatInsightTests: XCTestCase {
    private func run(_ id: String, started: TimeInterval, input: Int, output: Int, cost: Money? = nil, status: RunStatus = .succeeded) -> Run {
        var run = Fixture.run(id: id, status: status)
        run.startedAt = Fixture.date.addingTimeInterval(started)
        run.finishedAt = Fixture.date.addingTimeInterval(started + 65)
        run.usage = Usage(inputTokens: input, outputTokens: output, cost: cost)
        return run
    }

    func testTheRingSaysHowFullTheWindowIsAndWhereTheFigureCameFrom() throws {
        let reported = try XCTUnwrap(ChatInsight.use(reported: ContextUsage(usedTokens: 48_210, windowTokens: 200_000), window: nil, runs: []))
        XCTAssertEqual(reported.source, .reported)
        XCTAssertEqual(ChatInsight.percent(reported), 24)
        XCTAssertEqual(ChatInsight.band(reported), .normal)

        let rough = try XCTUnwrap(ChatInsight.use(reported: ContextUsage(usedTokens: 180_000, windowTokens: nil, estimated: true), window: 200_000, runs: []))
        XCTAssertEqual(rough.source, .agentEstimate, "the agent's rough count says so")
        XCTAssertEqual(rough.window, 200_000, "the catalogue's window fills in a window the agent did not say")
        XCTAssertEqual(ChatInsight.band(rough), .danger)

        let runs = [run("a", started: 0, input: 1_000, output: 100), run("b", started: 100, input: 140_000, output: 2_000)]
        let estimate = try XCTUnwrap(ChatInsight.use(reported: nil, window: 200_000, runs: runs))
        XCTAssertEqual(estimate.source, .estimate)
        XCTAssertEqual(estimate.used, 142_000, "the last counted turn, not the first")
        XCTAssertEqual(ChatInsight.band(estimate), .warning)

        XCTAssertNil(ChatInsight.use(reported: nil, window: nil, runs: runs), "no window known is no ring")
        XCTAssertNil(ChatInsight.use(reported: nil, window: 200_000, runs: []), "nothing counted is no ring")
        let full = try XCTUnwrap(ChatInsight.use(reported: ContextUsage(usedTokens: 199_300, windowTokens: 200_000), window: nil, runs: []))
        XCTAssertEqual(ChatInsight.percent(full), 99, "99.6 % is not 100 %")
    }

    func testTheBreakdownSharesTheWholeWindowOrTheirSum() {
        let categories = [SessionContextCategory(id: "system_prompt", label: "System", tokens: 50), SessionContextCategory(id: "conversation", label: "Chat", tokens: 150)]
        XCTAssertEqual(ChatInsight.shares(categories, window: 1_000), [0.05, 0.15])
        XCTAssertEqual(ChatInsight.shares(categories, window: 100), [0.25, 0.75], "rough counts over the window fill the bar, no more")
        XCTAssertTrue(ChatInsight.knownCategories.contains("tool_definitions"))
        let arabic = ChatInsight.number(48_210, .ar)
        XCTAssertTrue(arabic.hasPrefix("48") && arabic.hasSuffix("210"), "Latin digits in Arabic too: \(arabic)")
    }

    func testCompressSendsTheFocusOrAnEmptyBody() throws {
        let focused = Fixture.json(ChatInsight.compressRequest(focus: "  keep the test plan  "))
        XCTAssertEqual(focused["focus"] as? String, "keep the test plan")
        XCTAssertNil(Fixture.json(ChatInsight.compressRequest(focus: " \n "))["focus"], "nothing typed compresses the whole chat")
        let long = ChatInsight.compressRequest(focus: String(repeating: "x", count: 2_500))
        XCTAssertEqual(long.focus?.count, 2_000)
    }

    func testTheCatalogueCarriesTheModelsWindowForTheRing() {
        let model = Model(
            key: "anthropic/claude", providerId: "anthropic", provider: "Anthropic", model: "claude", kind: .chat,
            visible: true, custom: false, preview: false, disabled: false, contextWindow: 200_000, capabilities: []
        )
        XCTAssertEqual(ChatControls.models([model]).first?.window, 200_000)
    }

    func testRunsReadNewestFirstWithTheirTimeTokensAndCost() {
        let older = run("a", started: 0, input: 10, output: 5)
        let newer = run("b", started: 300, input: 10, output: 5)
        XCTAssertEqual(ChatInsight.history([older, newer]).map(\.id), ["b", "a"])
        XCTAssertEqual(ChatInsight.elapsedMs(started: older.startedAt, finished: older.finishedAt, now: Date()), 65_000)
        XCTAssertNil(ChatInsight.elapsedMs(started: nil, finished: nil, now: Date()))
        XCTAssertEqual(ChatInsight.clock(65_000), "1:05")
        XCTAssertEqual(ChatInsight.clock(3_723_000), "1:02:03")
        XCTAssertEqual(ChatInsight.cost(Money(amount: "0.1310", currency: "USD")), "0.13 USD")
        XCTAssertEqual(ChatInsight.cost(Money(amount: "0.000420", currency: "USD")), "0.0004 USD")
        XCTAssertEqual(ChatInsight.cost(Money(amount: "0", currency: "USD")), "0 USD")
        XCTAssertNil(ChatInsight.cost(nil), "no price is no line")
        XCTAssertEqual(ChatInsight.tone(.waiting), .running)
        XCTAssertEqual(ChatInsight.tone(.failed), .bad)
    }

    private func subagent(_ id: String, parent: String? = nil, depth: Int = 0, status: SubagentStatus = .running, started: TimeInterval, finished: TimeInterval? = nil) -> Subagent {
        Subagent(
            id: id, sessionId: "s1", parentId: parent, depth: depth, goal: "goal \(id)", status: status,
            startedAt: Fixture.date.addingTimeInterval(started), finishedAt: finished.map { Fixture.date.addingTimeInterval($0) },
            acceptingSteer: true, tools: []
        )
    }

    func testSubagentsRunAsATreeAndTheFinishedNewestFirst() {
        let items = [
            subagent("child", parent: "root", depth: 1, started: 5),
            subagent("root", started: 1),
            subagent("orphan", parent: "gone", depth: 2, started: 3),
            subagent("old", status: .completed, started: 0, finished: 10),
            subagent("new", status: .failed, started: 0, finished: 20),
        ]
        let split = ChatInsight.split(items)
        XCTAssertEqual(split.running.map(\.subagent.id), ["root", "child", "orphan"])
        XCTAssertEqual(split.running.map(\.indent), [0, 1, 2], "a subagent whose parent is not running keeps its depth")
        XCTAssertEqual(split.finished.map(\.id), ["new", "old"])

        var changed = items[1]
        changed.status = .interrupted
        let list = ChatInsight.upsert(items, changed)
        XCTAssertEqual(list.count, items.count)
        XCTAssertEqual(list.first { $0.id == "root" }?.status, .interrupted)
        XCTAssertEqual(ChatInsight.upsert([], changed).count, 1)
        XCTAssertEqual(ChatInsight.steerText("  only the server tests "), "only the server tests")
        XCTAssertNil(ChatInsight.steerText("   "))
    }

    func testAUnifiedDiffReadsIntoHunksWithLineNumbers() {
        let text = "@@ -1,2 +1,3 @@\n # notes\n-old\n+new\n+another\n\\ No newline at end of file\n"
        let hunks = ChatInsight.parseDiff(text)
        XCTAssertEqual(hunks.count, 1)
        XCTAssertEqual(hunks[0].lines.map(\.kind), [.context, .del, .add, .add, .note])
        XCTAssertEqual(hunks[0].lines.map(\.old), [1, 2, nil, nil, nil])
        XCTAssertEqual(hunks[0].lines.map(\.new), [1, nil, 2, 3, nil])
        XCTAssertEqual(hunks[0].lines[3].text, "another")
        XCTAssertEqual(ChatInsight.parseDiff("@@ -10 +12 @@ func\n x\n").first?.lines.first?.new, 12)
        XCTAssertTrue(ChatInsight.parseDiff("no hunk here").isEmpty)
    }

    func testChangedFilesSayWhatTheyCanShowAndWhenToReadAgain() {
        XCTAssertEqual(ChatInsight.counts(additions: 14, deletions: 2), "+14 −2")
        XCTAssertNil(ChatInsight.counts(additions: nil, deletions: nil))
        XCTAssertNil(ChatInsight.noDiffKey(.available, live: false))
        XCTAssertEqual(ChatInsight.noDiffKey(.available, live: true), "chat_insight.diff_live", "a live run's diff is not recorded yet")
        XCTAssertEqual(ChatInsight.noDiffKey(.binary, live: false), "chat_insight.diff_binary")
        XCTAssertEqual(ChatInsight.noDiffKey(.tooLarge, live: false), "chat_insight.diff_too_large")
        XCTAssertFalse(ChatInsight.canOpen(.deleted))
        XCTAssertTrue(ChatInsight.canOpen(.renamed))
        XCTAssertEqual(ChatInsight.fileName("src/app.ts"), "app.ts")
        let revision = ChatInsight.changesRevision([Fixture.run(id: "b", status: .succeeded), Fixture.run(id: "a", status: .failed), Fixture.run(id: "c", status: .running)])
        XCTAssertEqual(revision, "a,b", "only the runs that ended")
    }

    func testEveryChatInsightStringExistsInBothLanguages() {
        let en = L10n(.en, bundle: Bundle(for: AppModel.self))
        let ar = L10n(.ar, bundle: Bundle(for: AppModel.self))
        let keys = en.keys.filter { $0.hasPrefix("chat_insight.") }
        XCTAssertGreaterThan(keys.count, 60)
        XCTAssertEqual(Set(keys), Set(ar.keys.filter { $0.hasPrefix("chat_insight.") }))
        for category in ChatInsight.knownCategories { XCTAssertTrue(en.has("chat_insight.category.\(category)"), category) }
        for status in RunStatus.allCases { XCTAssertTrue(en.has("chat_insight.run_status.\(status.rawValue)"), status.rawValue) }
        for status in SubagentStatus.allCases { XCTAssertTrue(en.has("chat_insight.subagent_status.\(status.rawValue)"), status.rawValue) }
        for change in RunFileChangeKind.allCases { XCTAssertTrue(ar.has("chat_insight.change.\(change.rawValue)"), change.rawValue) }
    }
}

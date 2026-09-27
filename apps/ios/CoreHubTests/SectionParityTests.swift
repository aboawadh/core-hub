@testable import CoreHub
import CoreHubClient
import XCTest

/// The sections the web had and the phone did not (docs/changes/2026-09-27-twuijri-ios-self-sufficient.md):
/// the `/` commands, the trajectory, the runtime checks, the updates shelf, a task's worktree and
/// hand-over, and the starters.
final class SectionParityTests: XCTestCase {
    private let date = Date(timeIntervalSince1970: 1_790_000_000)

    func testSlashCommandsAreOfferedByCapabilityAndReadFromTheComposer() {
        let bare = SlashCommands.available([])
        XCTAssertEqual(bare.map(\.name), ["new", "fork", "archive", "model", "clear-screen"])
        let hermes = SlashCommands.available([.compress, .steer, .goals])
        XCTAssertEqual(hermes.prefix(3).map(\.name), ["compress", "steer", "goal"])
        XCTAssertEqual(SlashCommands.query("/co"), "co")
        XCTAssertEqual(SlashCommands.query("/"), "")
        XCTAssertNil(SlashCommands.query("/compress now"))
        XCTAssertNil(SlashCommands.query("hello"))
        XCTAssertEqual(SlashCommands.filter(hermes, "ar").map(\.name), ["archive", "clear-screen"])
        let parsed = SlashCommands.parse("/compress the API part", offered: hermes)
        XCTAssertEqual(parsed?.command.name, "compress")
        XCTAssertEqual(parsed?.argument, "the API part")
        XCTAssertNil(SlashCommands.parse("/compressor", offered: hermes))
        XCTAssertNil(SlashCommands.parse("/plan tomorrow", offered: hermes), "an agent without plans is sent the text")
        XCTAssertNil(SlashCommands.parse("hello /new", offered: hermes))
        let options = [ChatControls.ModelOption(value: "gpt-5", label: "GPT-5", group: "OpenAI")]
        XCTAssertEqual(SlashCommands.model(named: "gpt-5", in: options), "gpt-5")
        XCTAssertNil(SlashCommands.model(named: "nope", in: options))
    }

    func testTheTrajectoryIsFilteredAndTimed() {
        let l10n = L10n(.en, bundle: Bundle(for: AppModel.self))
        let steps = [
            TrajectoryStep(id: "1", kind: .input, lane: .input, exchange: 1, status: .succeeded, text: "Fix the build", toolCallOnly: false),
            TrajectoryStep(id: "2", kind: .turn, lane: .model, exchange: 1, status: .succeeded, durationMs: 1500, text: "Looking", toolCallOnly: false, model: "gpt-5"),
            TrajectoryStep(id: "3", kind: .tool, lane: .tools, exchange: 1, status: .failed, durationMs: 90_000, toolCallOnly: true),
        ]
        XCTAssertEqual(TrajectoryRules.shown(steps, lane: .tools, query: "").map(\.id), ["3"])
        XCTAssertEqual(TrajectoryRules.shown(steps, lane: nil, query: "build").map(\.id), ["1"])
        XCTAssertEqual(TrajectoryRules.shown(steps, lane: nil, query: "GPT").map(\.id), ["2"])
        XCTAssertEqual(TrajectoryRules.duration(250, l10n), "250 " + l10n("trajectory_sheet.unit.ms"))
        XCTAssertEqual(TrajectoryRules.duration(1500, l10n), "1.5 " + l10n("trajectory_sheet.unit.s"))
        XCTAssertEqual(TrajectoryRules.duration(90_000, l10n), "1.5 " + l10n("trajectory_sheet.unit.min"))
        XCTAssertNil(TrajectoryRules.duration(nil, l10n))
    }

    func testRuntimeChecksPutWhatFailedFirstAndARestartIsAThingToDo() {
        let checks = [
            RuntimeCheck(id: .gatewayReloaded, ok: false),
            RuntimeCheck(id: .runtimeWritable, ok: true),
            RuntimeCheck(id: .modelSelected, ok: true),
        ]
        XCTAssertEqual(RuntimeRules.failingFirst(checks).map(\.id), [.gatewayReloaded, .runtimeWritable, .modelSelected])
        XCTAssertTrue(RuntimeRules.onlyRestartPending(checks))
        XCTAssertFalse(RuntimeRules.onlyRestartPending(checks + [RuntimeCheck(id: .providerKeys, ok: false)]))
        let probe = ProviderProbeResult(ok: true, durationMs: 10, models: [
            ProviderProbeResultModelsInner(id: "chat", label: "Chat"), ProviderProbeResultModelsInner(id: "draw", label: "Draw", imageOnly: true),
        ])
        XCTAssertEqual(RuntimeRules.chatModels(probe).map(\.id), ["chat"])
    }

    func testTheUpdatesShelfAndItsSource() {
        XCTAssertTrue(UpdatesAdminRules.repoValid("twuijri/core-hub"))
        XCTAssertFalse(UpdatesAdminRules.repoValid("core-hub"))
        XCTAssertFalse(UpdatesAdminRules.repoValid("a/b/c"))
        XCTAssertFalse(UpdatesAdminRules.repoValid("a b/c"))
        let old = Release(id: "r1", platform: .ios, channel: .stable, version: "1.1.3", build: 3, notes: LocalizedText(ar: "", en: ""),
                          sizeBytes: 10, sha256: "x", mandatory: false, downloadUrl: "/x", publishedAt: date)
        var new = old
        new.id = "r2"
        new.publishedAt = date.addingTimeInterval(60)
        XCTAssertEqual(UpdatesAdminRules.shelf([old, new]).map(\.id), ["r2", "r1"])
    }

    func testATaskIsHandedToAnotherProfilesHermes() {
        let hermes = Assignee(kind: .agent, id: "H1", name: "Hermes")
        XCTAssertEqual(TaskExtrasRules.hermesID(hermes, agents: []), "H1")
        XCTAssertNil(TaskExtrasRules.hermesID(nil, agents: []))
        XCTAssertEqual(TaskExtrasRules.targets(["work", "home", "lab"], current: "work"), ["home", "lab"])
        XCTAssertEqual(TaskExtrasRules.statusKind(.dirty), .warn)
        XCTAssertEqual(TaskExtrasRules.statusKind(.error), .bad)
    }

    func testAnEmptyChatOffersThreeStartersInItsLanguage() {
        XCTAssertEqual(Starters.suggestions(.en).count, 3)
        XCTAssertEqual(Starters.suggestions(.ar).count, 3)
        XCTAssertNotEqual(Starters.suggestions(.ar), Starters.suggestions(.en))
    }
}

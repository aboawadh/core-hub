@testable import CoreHub
import CoreHubClient
import XCTest

/// Agents I on the phone (apps batch 8): the rules of the Skills, Memory and Plugins pages and of the
/// agent cards, as the web's pages have them. Android's AgentToolsTest checks the same rules.
final class AgentToolRulesTests: XCTestCase {
    private func skill(_ key: String, _ source: SkillSource, name: String? = nil, description: String? = nil,
                       library: Skill.Library? = nil, pinned: Bool = false) -> Skill {
        Skill(key: key, name: name ?? key, description: description, enabled: true, pinned: pinned, source: source, useCount: 0, library: library)
    }

    private func agent(kind: AgentKind = .acp, status: AgentStatus = .available, source: AgentInstall.Source = .managed,
                       runtime: AgentRuntime.State = .notApplicable, update: Bool = false, latest: String? = nil, pinned: String? = nil,
                       auto: Bool = false, autoSupported: Bool = true) -> Agent {
        Agent(id: "01J8QK3ZR2W7M5N4P6T8V9X0AG", profile: "work", ownerId: "u1", createdAt: Fixture.date, updatedAt: Fixture.date, slug: "codex",
              name: "Codex", kind: kind, avatar: Avatar(kind: .generated, seed: "c"), status: status, enabled: true,
              install: AgentInstall(source: source, version: "1.0.0", latestVersion: latest, updateAvailable: update, pinnedVersion: pinned,
                                    newerThanTested: false, autoUpdate: auto, autoUpdateSupported: autoSupported),
              runtime: AgentRuntime(state: runtime), capabilities: [], sections: [], limited: false, subagents: ._none)
    }

    // MARK: - Skills

    func testTheSearchAndTheChipNarrowTheSkillsAndEmptyCategoriesDropOut() {
        let categories = [
            SkillCategory(key: "user", name: "user", skills: [skill("web", .user, name: "Web research", description: "Search and summarise"), skill("mine", .external)]),
            SkillCategory(key: "core-hub", name: "core-hub", skills: [skill("image-generate", .library, library: .edited)]),
            SkillCategory(key: "devops", name: "devops", skills: [skill("docker", .builtin, description: "Containers")]),
        ]
        XCTAssertEqual(SkillRules.total(categories), 4)
        XCTAssertEqual(SkillRules.narrow(categories, query: "SUMMAR", filter: .all).flatMap { $0.skills.map(\.key) }, ["web"])
        XCTAssertEqual(SkillRules.narrow(categories, query: "", filter: .yours).map(\.key), ["user"])
        XCTAssertEqual(SkillRules.narrow(categories, query: "", filter: .library).map(\.key), ["core-hub"])
        XCTAssertEqual(SkillRules.narrow(categories, query: "contain", filter: .builtin).map(\.key), ["devops"])
        XCTAssertTrue(SkillRules.narrow(categories, query: "docker", filter: .yours).isEmpty)
    }

    func testHermesSkillsAreReadOnlyAnEditedLibrarySkillRestoresAndANewKeyIsAFolderName() {
        XCTAssertEqual(SkillRules.actions(skill("docker", .builtin)), [.open, .pin])
        XCTAssertEqual(SkillRules.actions(skill("image", .library, library: .edited, pinned: true)), [.open, .unpin, .restore, .delete])
        XCTAssertTrue(SkillRules.broken(skill("bad", .user, description: "[unreadable: no front matter]")))
        XCTAssertTrue(SkillRules.validKey("web-research.v2"))
        XCTAssertFalse(SkillRules.validKey("Web"))
        XCTAssertFalse(SkillRules.validKey("-web"))
        XCTAssertFalse(SkillRules.validKey(""))
        XCTAssertTrue(SkillRules.importable("pack.ZIP") && SkillRules.importable("SKILL.md") && SkillRules.importable("x.skill"))
        XCTAssertFalse(SkillRules.importable("notes.txt"))
    }

    func testTheLibraryCardSaysOnNotYetOrOffAndOffersInstallWhenSomeAreMissing() {
        XCTAssertEqual(SkillRules.libraryLine(SkillLibrary(enabled: false, available: 12, installed: 0, edited: 0)), .off)
        XCTAssertEqual(SkillRules.libraryLine(SkillLibrary(enabled: true, available: 12, installed: 0, edited: 0)), .none)
        XCTAssertEqual(SkillRules.libraryLine(SkillLibrary(enabled: true, available: 12, installed: 12, edited: 1)), .on)
        XCTAssertTrue(SkillRules.libraryMissing(SkillLibrary(enabled: true, available: 12, installed: 11, edited: 0)))
        XCTAssertFalse(SkillRules.libraryMissing(SkillLibrary(enabled: false, available: 12, installed: 0, edited: 0)))
    }

    // MARK: - Memory

    func testMemoryEntriesSplitOnASectionLineCountCodePointsAndAreReplacedAddedOrRemovedAlone() {
        let item = MemoryItem(id: "memory", kind: .document, title: "MEMORY.md", content: "short answers\n§\nEnglish commits", tags: [], revision: 7, charLimit: 30)
        let entries = MemoryRules.list(of: item)
        XCTAssertEqual(entries, ["short answers", "English commits"])
        XCTAssertEqual(MemoryRules.length(entries), "short answers\n§\nEnglish commits".unicodeScalars.count)
        XCTAssertEqual(MemoryRules.length(["😀"]), 1)
        XCTAssertEqual(MemoryRules.with(entries, at: 1, typed: "a\n  §  \nb"), ["short answers", "a", "b"])
        XCTAssertEqual(MemoryRules.with(entries, at: nil, typed: " new "), ["short answers", "English commits", "new"])
        XCTAssertEqual(MemoryRules.without(entries, at: 0), ["English commits"])
        XCTAssertTrue(MemoryRules.isList(item))
        var soul = item
        soul.id = "soul"
        XCTAssertFalse(MemoryRules.isList(soul))
        var fromHub = item
        fromHub.entries = ["from the hub"]
        XCTAssertEqual(MemoryRules.list(of: fromHub), ["from the hub"])
    }

    func testTheBudgetWarnsFrom80PercentAndRefusesGrowingPastItNeverShrinking() {
        XCTAssertEqual(MemoryRules.tone(10, limit: 100), .normal)
        XCTAssertEqual(MemoryRules.tone(80, limit: 100), .warning)
        XCTAssertEqual(MemoryRules.tone(101, limit: 100), .danger)
        XCTAssertEqual(MemoryRules.tone(500, limit: nil), .normal)
        XCTAssertTrue(MemoryRules.fits(100, current: 90, limit: 100))
        XCTAssertFalse(MemoryRules.fits(101, current: 90, limit: 100))
        XCTAssertTrue(MemoryRules.fits(110, current: 120, limit: 100), "an over-full list may shrink")
        XCTAssertFalse(MemoryRules.fits(121, current: 120, limit: 100))
    }

    // MARK: - Plugins and cards

    func testAPluginToInstallIsOneWordThatIsNotAnOption() {
        XCTAssertTrue(PluginRules.validIdentifier(" owner/repo "))
        XCTAssertTrue(PluginRules.validIdentifier("https://github.com/a/b.git"))
        XCTAssertFalse(PluginRules.validIdentifier("--force"))
        XCTAssertFalse(PluginRules.validIdentifier("two words"))
        XCTAssertFalse(PluginRules.validIdentifier("   "))
    }

    func testACardInstallsWhatIsMissingTakesAnUpdateRestartsHermesAndKeepsTheRestInItsMenu() {
        XCTAssertEqual(AgentCardRules.primary(agent(status: .notInstalled, source: ._none)), .install)
        XCTAssertTrue(AgentCardRules.menu(agent(status: .notInstalled, source: ._none)).isEmpty)

        let upToDate = agent()
        XCTAssertNil(AgentCardRules.primary(upToDate))
        XCTAssertEqual(AgentCardRules.menu(upToDate), [.checkUpdate, .autoUpdateOn, .uninstall])

        let behind = agent(update: true, latest: "1.2.0", pinned: "1.1.0", auto: true)
        XCTAssertEqual(AgentCardRules.primary(behind), .upgrade)
        XCTAssertEqual(AgentCardRules.update(behind), "1.2.0")
        XCTAssertTrue(AgentCardRules.updateUntested(behind))
        XCTAssertEqual(AgentCardRules.menu(behind), [.checkUpdate, .autoUpdateOff, .uninstall])

        let hermes = agent(kind: .hermes, source: .builtin, runtime: .running)
        XCTAssertEqual(AgentCardRules.primary(hermes), .restart)
        XCTAssertTrue(AgentCardRules.menu(hermes).isEmpty, "an image's own agent is neither updated nor removed here")
        XCTAssertNil(AgentCardRules.primary(agent(kind: .hermes, source: .builtin)), "a Hermes the hub only found is not restarted")
        XCTAssertEqual(AgentCardRules.menu(agent(source: .userCli, autoSupported: false)), [.checkUpdate])
    }

    func testAJobIsFollowedByItsPercentAndItsResultIsRead() {
        let running = Job(id: "j", profile: "work", ownerId: "u1", createdAt: Fixture.date, updatedAt: Fixture.date, kind: .install, status: .running,
                          progress: JobProgress(percent: 40))
        XCTAssertEqual(AgentCardRules.progress(running), 0.4, accuracy: 0.001)
        XCTAssertFalse(AgentCardRules.terminal(running.status))
        var done = running
        done.status = .succeeded
        done.progress = JobProgress()
        done.result = ["version": .string("1.2.0"), "update_available": .bool(true)]
        XCTAssertEqual(AgentCardRules.progress(done), 1)
        XCTAssertEqual(AgentCardRules.result(done, "version"), "1.2.0")
        XCTAssertTrue(AgentCardRules.resultFlag(done, "update_available"))
        XCTAssertTrue(AgentCardRules.terminal(.cancelled))
    }

    func testRefusalsTheHubNamesReadInOurWordsAndAnImportsOwnSentenceFollows() {
        XCTAssertEqual(AgentToolErrors.lead(reason: "skill_exists")?.key, "agents.import.skill_exists")
        XCTAssertEqual(AgentToolErrors.lead(reason: "skill_exists")?.withHubText, true)
        XCTAssertEqual(AgentToolErrors.lead(reason: "skill_bundled")?.key, "agents.skill.bundled_refused")
        XCTAssertNil(AgentToolErrors.lead(reason: "boom"))
        let l10n = L10n(.en)
        for key in AgentToolErrors.importReasons.map({ "agents.import.\($0)" }) + Array(AgentToolErrors.others.values) {
            XCTAssertTrue(l10n.has(key), key)
        }
    }
}

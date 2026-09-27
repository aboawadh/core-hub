@testable import CoreHub
import CoreHubClient
import Foundation
import XCTest

/// Apps batches 11 and 13: Settings → Files — the rules the page follows. Android's FilesPageTest is
/// the twin (it also checks the calls against a scripted hub).
final class FilesRulesTests: XCTestCase {
    private func iso(_ text: String) -> Date { ISO8601DateFormatter().date(from: text)! }

    private func entry(_ name: String, kind: WorkspaceFileEntry.Kind = .file, size: Int? = 10, modified: String? = "2026-09-25T08:00:00Z", path: String? = nil) -> WorkspaceFileEntry {
        WorkspaceFileEntry(name: name, path: path ?? name, kind: kind, link: false, sizeBytes: size, modifiedAt: modified.map(iso), mime: nil, editable: true)
    }

    private func failure(_ status: Int, _ code: String, reason: String? = nil) -> HubFailure {
        var failure = HubFailure(kind: .http, status: status, code: code, message: "hub words", operationID: nil, requestID: nil, detail: "")
        failure.reason = reason
        return failure
    }

    func testPathsJoinSplitAndTrailTheWayTheHubWritesThem() {
        XCTAssertEqual(FilesRules.join("", "a"), "a")
        XCTAssertEqual(FilesRules.join("a/b", "c"), "a/b/c")
        XCTAssertEqual(FilesRules.parent("plan.md"), "")
        XCTAssertEqual(FilesRules.parent("notes/2026/plan.md"), "notes/2026")
        XCTAssertEqual(FilesRules.baseName("notes/plan.md"), "plan.md")
        XCTAssertEqual(FilesRules.crumbs("notes/2026").map(\.name), ["notes", "2026"])
        XCTAssertEqual(FilesRules.crumbs("notes/2026").map(\.path), ["notes", "notes/2026"])
        XCTAssertTrue(FilesRules.crumbs("").isEmpty)
        XCTAssertEqual(FilesRules.normalise("  /./reports//2026\\plan.md/ "), "reports/2026/plan.md")
        XCTAssertEqual(FilesRules.copyName("notes.md"), "notes copy.md")
        XCTAssertEqual(FilesRules.copyName("Makefile"), "Makefile copy")
        XCTAssertEqual(FilesRules.copyName(".env"), ".env copy")
        XCTAssertTrue(FilesRules.validName(" plan.md "))
        XCTAssertFalse(FilesRules.validName(".."))
        XCTAssertFalse(FilesRules.validName("a/b"))
    }

    func testFoldersComeFirstThenTheChosenOrderAndASearchNarrowsByName() {
        let list = [
            entry("b.txt", size: 5, modified: "2026-09-20T08:00:00Z"),
            entry("Zeta", kind: .directory, size: nil, modified: "2026-09-01T08:00:00Z"),
            entry("a.md", size: 900, modified: "2026-09-26T08:00:00Z"),
            entry("alpha", kind: .directory, size: nil, modified: "2026-09-27T08:00:00Z"),
            entry("c.png", size: 50, modified: nil),
        ]
        XCTAssertEqual(FilesRules.arrange(list, query: "", sort: .name).map(\.name), ["alpha", "Zeta", "a.md", "b.txt", "c.png"])
        XCTAssertEqual(FilesRules.arrange(list, query: "", sort: .newest).map(\.name), ["alpha", "Zeta", "a.md", "b.txt", "c.png"])
        XCTAssertEqual(FilesRules.arrange(list, query: "", sort: .largest).map(\.name), ["alpha", "Zeta", "a.md", "c.png", "b.txt"])
        XCTAssertEqual(FilesRules.arrange(list, query: "PNG", sort: .name).map(\.name), ["c.png"])
    }

    func testSizesAndTimesReadInLatinDigits() {
        XCTAssertEqual(FilesRules.size(40), "\u{2066}40 B\u{2069}")
        XCTAssertEqual(FilesRules.size(1536), "\u{2066}1.5 KB\u{2069}")
        XCTAssertEqual(FilesRules.size(25 * 1024 * 1024), "\u{2066}25 MB\u{2069}")
        let utc = TimeZone(identifier: "UTC")!
        for language in [AppLanguage.en, .ar] {
            let text = FilesRules.detail(entry("a.md", size: 2048, modified: "2026-09-25T08:05:00Z"), language: language, zone: utc)!
            XCTAssertTrue(text.hasPrefix("\u{2066}2 KB\u{2069} · 25 "), text)
            XCTAssertTrue(text.hasSuffix("2026, 08:05"), text)
            XCTAssertNil(text.rangeOfCharacter(from: CharacterSet(charactersIn: "٠١٢٣٤٥٦٧٨٩")), text)
        }
        XCTAssertNil(FilesRules.detail(entry("x", kind: .directory, size: nil, modified: nil), language: .en))
    }

    func testAPromptsWordsBecomeTheOneStepThePageTakes() {
        XCTAssertEqual(FilesRules.promptStep(.newFolder, typed: " 2026/q3/ ", folder: "notes", path: ""), .makeFolder("notes/2026/q3"))
        XCTAssertEqual(FilesRules.promptStep(.newFolder, typed: "  ", folder: "notes", path: ""), .badName)
        XCTAssertEqual(FilesRules.promptStep(.newFolder, typed: "../up", folder: "notes", path: ""), .badName)
        XCTAssertEqual(FilesRules.promptStep(.newFile, typed: "plan.md", folder: "", path: ""), .writeNew("plan.md"))
        XCTAssertEqual(FilesRules.promptStep(.rename, typed: " b.md ", folder: "notes", path: "notes/a.md"), .move(from: "notes/a.md", to: "notes/b.md"))
        XCTAssertEqual(FilesRules.promptStep(.rename, typed: "a.md", folder: "notes", path: "notes/a.md"), .same)
        XCTAssertEqual(FilesRules.promptStep(.rename, typed: "x/b.md", folder: "notes", path: "notes/a.md"), .badName)
        XCTAssertEqual(FilesRules.promptStep(.move, typed: "/archive/a.md", folder: "notes", path: "notes/a.md"), .move(from: "notes/a.md", to: "archive/a.md"))
        XCTAssertEqual(FilesRules.promptStep(.move, typed: "notes/a.md", folder: "notes", path: "notes/a.md"), .same)
        XCTAssertEqual(FilesRules.promptStep(.copy, typed: "notes/a copy.md", folder: "notes", path: "notes/a.md"), .copy(from: "notes/a.md", to: "notes/a copy.md"))
        XCTAssertEqual(FilesRules.promptStep(.copy, typed: "notes/a.md", folder: "notes", path: "notes/a.md"), .badName)
    }

    func testARefusalIsNamedInOneLineAndANameAlreadyThereAsksToReplace() {
        XCTAssertEqual(FilesRules.refusal(failure(403, "forbidden")), .notAllowed)
        XCTAssertEqual(FilesRules.refusal(failure(409, "conflict", reason: "changed")), .changed)
        XCTAssertEqual(FilesRules.refusal(failure(409, "conflict", reason: "exists")), .exists)
        XCTAssertEqual(FilesRules.refusal(failure(413, "payload_too_large")), .tooLarge)
        XCTAssertEqual(FilesRules.refusal(failure(415, "unsupported_media_type")), .notText)
        XCTAssertEqual(FilesRules.refusal(failure(400, "validation_failed", reason: "root")), .root)
        XCTAssertEqual(FilesRules.refusal(failure(400, "validation_failed", reason: "into_itself")), .intoItself)
        XCTAssertEqual(FilesRules.refusal(failure(400, "validation_failed", reason: "outside_root")), .outside)
        XCTAssertEqual(FilesRules.refusal(failure(404, "not_found")), .gone)
        XCTAssertEqual(FilesRules.refusal(failure(500, "internal")), .other)
        XCTAssertTrue(FilesRules.nameTaken(failure(409, "conflict", reason: "exists")))
        XCTAssertFalse(FilesRules.nameTaken(failure(409, "conflict", reason: "changed")))
        XCTAssertFalse(FilesRules.nameTaken(nil))
        // A save refused as changed on disk is what the shared editor offers Reload for.
        XCTAssertTrue(DocumentRules.changedElsewhere(failure(409, "conflict", reason: "changed")))
        XCTAssertTrue(FilesRules.tooLarge(26, max: 25))
        XCTAssertFalse(FilesRules.tooLarge(25, max: 25))
        XCTAssertFalse(FilesRules.tooLarge(26, max: nil))
        XCTAssertTrue(FilesRules.markdown("Plan.MD"))
        XCTAssertFalse(FilesRules.markdown("plan.py"))
    }

    func testEveryRefusalHasItsLineInBothLanguagesAndASaidLineStaysAsItIs() {
        for language in [AppLanguage.en, .ar] {
            let l10n = L10n(language, bundle: Bundle(for: AppModel.self))
            XCTAssertEqual(FilesRules.say(failure(403, "forbidden"), l10n), l10n("files.not_allowed"))
            XCTAssertNotEqual(l10n("files.not_allowed"), "files.not_allowed", "the line is in the \(language) strings")
            XCTAssertEqual(FilesRules.say(failure(409, "conflict", reason: "exists"), l10n), l10n("files.exists"))
            XCTAssertEqual(FilesRules.say(FilesRules.said("Type one name."), l10n), "Type one name.")
            XCTAssertTrue(FilesRules.say(failure(500, "internal"), l10n).contains("hub words"))
        }
    }

    func testAFileOpensThroughTheChatsOpenerAndAChangedFileIsFetchedAgain() {
        let file = FilesRules.hubFile(entry("a.png", size: 10, path: "img/a.png"), profile: "work")!
        XCTAssertEqual(file.name, "a.png")
        XCTAssertEqual(file.size, 10)
        XCTAssertTrue(file.cacheKey.hasPrefix("ws-"))
        XCTAssertNotEqual(file.cacheKey, FilesRules.hubFile(entry("a.png", size: 11, path: "img/a.png"), profile: "work")!.cacheKey)
        XCTAssertNotEqual(file.cacheKey, FilesRules.hubFile(entry("a.png", size: 10, path: "img/a.png"), profile: "home")!.cacheKey)
        XCTAssertEqual(FileKinds.openAs(file), .picture)
        XCTAssertNil(FilesRules.hubFile(entry("img", kind: .directory, size: nil), profile: "work"))
        XCTAssertNil(FilesRules.hubFile(entry("out", kind: .link, size: nil), profile: "work"))
    }
}

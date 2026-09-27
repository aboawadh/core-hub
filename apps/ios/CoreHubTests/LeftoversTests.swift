@testable import CoreHub
import CoreHubClient
import XCTest

/// The phone leftovers of the apps night (iOS): a profile file handed to a chat's composer ready (no
/// second upload), the chats «Attach to chat» offers, and the Background sheet's rules. Android's
/// LeftoversTest is the twin.
@MainActor
final class LeftoversTests: XCTestCase {
    private func attachment(_ id: String, kind: Attachment.Kind = .file) -> Attachment {
        Attachment(
            id: id, profile: "work", ownerId: "01J8QK3ZR2W7M5N4P6T8V9X0HM", createdAt: Fixture.date,
            updatedAt: Fixture.date, name: "plan.md", mime: "text/markdown", sizeBytes: 7, kind: kind,
            url: "https://hub.example/\(id)", purpose: .message, sha256: String(repeating: "0", count: 64)
        )
    }

    // MARK: - the hand-off

    func testAHandedOffFileIsTakenOnceByTheComposerOfItsOwnProfile() {
        var now = Fixture.date
        let slot = AttachmentHandOff(now: { now })
        slot.put("work", [attachment("01J8QK3ZR2W7M5N4P6T8V9X0A1")])
        XCTAssertTrue(slot.take("home").isEmpty)
        XCTAssertEqual(slot.take("work").map(\.id), ["01J8QK3ZR2W7M5N4P6T8V9X0A1"])
        XCTAssertTrue(slot.take("work").isEmpty)
        slot.put("work", [attachment("01J8QK3ZR2W7M5N4P6T8V9X0A2")])
        now = now.addingTimeInterval(AttachmentHandOff.ttl + 1)
        XCTAssertTrue(slot.take("work").isEmpty, "an old hand-off does not surprise a chat opened much later")
    }

    func testAReadyAttachmentGoesWithTheNextMessageWithoutAnUpload() {
        var uploads = 0
        let tray = AttachmentTray(
            upload: { _, _ in
                uploads += 1
                throw CocoaError(.fileReadUnknown)
            },
            discard: { _ in },
            describe: { _ in "failed" },
            tooLarge: { _ in "too large" }
        )
        let file = attachment("01J8QK3ZR2W7M5N4P6T8V9X0A1")
        tray.addReady(file)
        tray.addReady(file)
        XCTAssertEqual(tray.items.count, 1)
        XCTAssertFalse(tray.uploading)
        let blocks = tray.message("Read this").blocks
        XCTAssertEqual(blocks.count, 2)
        guard case .typeFileBlock(let block) = blocks[1] else { return XCTFail("the file as a file block") }
        XCTAssertEqual(block.attachmentId, file.id)
        tray.addReady(attachment("01J8QK3ZR2W7M5N4P6T8V9X0A3", kind: .image))
        XCTAssertTrue(tray.items.last?.isImage == true)
        XCTAssertEqual(uploads, 0)
    }

    func testAttachOffersThisProfilesRecentChatsAtMostEightWithoutTheGlobalAgents() {
        let sessions = (1...10).map { index in
            Session(
                id: "01J8QK3ZR2W7M5N4P6T8V9X0S\(index)", profile: "work", ownerId: "u1", createdAt: Fixture.date,
                updatedAt: Fixture.date, agentId: "ag1", title: "Chat \(index)", source: index == 2 ? .globalAgent : .chat,
                pinned: false, archived: false, messageCount: 0, status: .idle, notify: false
            )
        }
        let offered = FilesRules.recentChats(sessions)
        XCTAssertEqual(offered.count, FilesRules.recentChatLimit)
        XCTAssertFalse(offered.contains { $0.source == .globalAgent })
        XCTAssertEqual(offered.first?.id, "01J8QK3ZR2W7M5N4P6T8V9X0S1")
    }

    // MARK: - Background

    private func item(
        _ kind: BackgroundKind, session: String? = nil, job: JobKind? = nil, resource: ResourceRef? = nil,
        started: Date? = Fixture.date, finished: Date? = nil, title: String = "Plan", profile: String = "work"
    ) -> BackgroundItem {
        BackgroundItem(
            id: "x:\(kind.rawValue)", kind: kind, jobKind: job, title: title, profile: profile,
            status: finished == nil ? .running : .succeeded, startedAt: started, finishedAt: finished, stoppable: true,
            sessionId: session, resource: resource
        )
    }

    func testEachItemOpensWhereItLives() {
        XCTAssertEqual(BackgroundRules.destination(of: item(.taskRun, session: "s1")), .destination(.tasks))
        XCTAssertEqual(BackgroundRules.destination(of: item(.workflowRun)), .destination(.schedules))
        XCTAssertEqual(BackgroundRules.destination(of: item(.subagent, session: "s1", profile: "home")), .chat(sessionID: "s1", profile: "home"))
        XCTAssertEqual(BackgroundRules.destination(of: item(.chatRun, session: "s1")), .chat(sessionID: "s1", profile: "work"))
        XCTAssertEqual(BackgroundRules.destination(of: item(.job, job: .export)), .settings)
        XCTAssertEqual(
            BackgroundRules.destination(of: item(.job, job: .pluginInstall, resource: ResourceRef(kind: .agent, id: "ag1"))),
            .destination(.agentManager)
        )
        XCTAssertNil(BackgroundRules.destination(of: item(.job, job: .run)))
    }

    func testTimeIsCountedInLatinDigitsAndAQueuedItemHasNoneYet() {
        let now = Fixture.date.addingTimeInterval(12 * 60 + 5)
        XCTAssertEqual(BackgroundRules.elapsed(item(.chatRun), now: now).map(BackgroundRules.clock), "12:05")
        XCTAssertEqual(BackgroundRules.elapsed(item(.job, finished: Fixture.date.addingTimeInterval(60)), now: now).map(BackgroundRules.clock), "1:00")
        XCTAssertNil(BackgroundRules.elapsed(item(.chatRun, started: nil), now: now))
        XCTAssertEqual(BackgroundRules.clock(3729), "1:02:09")
    }

    func testARowWithoutWordsSaysWhatItIs() {
        XCTAssertEqual(BackgroundRules.title(item(.job, job: .export, title: " "), untitled: "Untitled"), "export")
        XCTAssertEqual(BackgroundRules.title(item(.chatRun, title: ""), untitled: "Untitled"), "Untitled")
        XCTAssertEqual(BackgroundRules.title(item(.chatRun), untitled: "Untitled"), "Plan")
    }

    func testEveryKindAndStatusHasItsWordsInBothLanguages() {
        for language in [AppLanguage.en, .ar] {
            let l10n = L10n(language, bundle: Bundle(for: AppModel.self))
            for kind in BackgroundKind.allCases { XCTAssertTrue(l10n.has("background.kind.\(kind.rawValue)"), "\(language) \(kind)") }
            for status in BackgroundStatus.allCases { XCTAssertTrue(l10n.has("background.status.\(status.rawValue)"), "\(language) \(status)") }
            for key in ["files.attach_title", "files.attach_new", "files.attach_recent", "background.title", "background.none"] {
                XCTAssertTrue(l10n.has(key), "\(language) \(key)")
            }
        }
    }
}

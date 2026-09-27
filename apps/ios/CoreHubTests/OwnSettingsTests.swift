@testable import CoreHub
import CoreHubClient
import Foundation
import XCTest

/// The person's own settings (batch 4): the inbox marks and opens, the account's name, password and
/// picture rules, messaging-account linking, and what a revoke says. Android's OwnSettingsTest is the twin.
final class OwnSettingsTests: XCTestCase {
    private let date = Date(timeIntervalSince1970: 1_790_000_000)

    private func notice(_ id: String, read: Bool, resource: ResourceRef? = nil, profile: String? = "work") -> Notice {
        Notice(id: id, userId: "u1", profile: profile, kind: .runCompleted, title: "Done", resource: resource,
               readAt: read ? date : nil, createdAt: date)
    }

    // MARK: - Inbox

    func testMarkingOneNoticeMovesTheUnreadCountOnlyWhenItChanges() {
        let list = [notice("a", read: false), notice("b", read: true)]
        let read = OwnSettingsRules.marking(list, unread: 1, id: "a", read: true, at: date)
        XCTAssertNotNil(read.notices[0].readAt)
        XCTAssertEqual(read.unread, 0)
        // Already read: nothing changes, the count stays.
        let again = OwnSettingsRules.marking(read.notices, unread: 0, id: "a", read: true)
        XCTAssertEqual(again.unread, 0)
        XCTAssertEqual(again.notices, read.notices)
        // Unread again: back up by one.
        let unread = OwnSettingsRules.marking(list, unread: 1, id: "b", read: false)
        XCTAssertNil(unread.notices[1].readAt)
        XCTAssertEqual(unread.unread, 2)
        // A notice not in the list, and a count that would go below zero.
        XCTAssertEqual(OwnSettingsRules.marking(list, unread: 1, id: "zz", read: true).unread, 1)
        XCTAssertEqual(OwnSettingsRules.marking(list, unread: 0, id: "a", read: true).unread, 0)
    }

    func testMarkAllReadEmptiesTheUnreadViewAndReadsEveryRowOfAll() {
        let list = [notice("a", read: false), notice("b", read: true)]
        XCTAssertEqual(OwnSettingsRules.allRead(list, unreadOnly: true), [])
        let all = OwnSettingsRules.allRead(list, unreadOnly: false, at: date)
        XCTAssertTrue(all.allSatisfy { $0.readAt != nil })
        XCTAssertEqual(all.map(\.id), ["a", "b"])
    }

    func testATappedNoticeOpensWhatItIsAbout() {
        XCTAssertEqual(
            OwnSettingsRules.route(for: notice("a", read: false, resource: ResourceRef(kind: .session, id: "S1")), selector: "default"),
            .chat(sessionID: "S1", profile: "work")
        )
        // A conversation with no profile opens in the selector's.
        XCTAssertEqual(
            OwnSettingsRules.route(for: notice("a", read: false, resource: ResourceRef(kind: .session, id: "S1"), profile: nil), selector: "default"),
            .chat(sessionID: "S1", profile: "default")
        )
        XCTAssertEqual(OwnSettingsRules.route(for: notice("a", read: false, resource: ResourceRef(kind: .task, id: "T")), selector: "default"), .destination(.tasks))
        XCTAssertEqual(OwnSettingsRules.route(for: notice("a", read: false, resource: ResourceRef(kind: .workflowRun, id: "R")), selector: "default"), .destination(.schedules))
        // A record with nothing behind it only gets marked read.
        XCTAssertNil(OwnSettingsRules.route(for: notice("a", read: false), selector: "default"))
    }

    @MainActor
    func testTheInboxMarksAtOnceAndReadsAgainWhenTheHubRefuses() async {
        var marked: [(String, Bool)] = []
        var refuse = false
        var markedAll = 0
        let pages = [notice("a", read: false), notice("b", read: false)]
        let model = InboxModel(
            fetch: { unreadOnly, _ in
                NotifyListNotices200Response(items: unreadOnly ? pages.filter { $0.readAt == nil } : pages, nextCursor: nil, unreadCount: 2)
            },
            mark: { id, read in
                if refuse { throw URLError(.notConnectedToInternet) }
                marked.append((id, read))
            },
            markAll: { markedAll += 1 }
        )
        await model.refresh()
        XCTAssertEqual(model.unread, 2)
        await model.set(model.notices[0], read: true)
        XCTAssertEqual(marked.map(\.0), ["a"])
        XCTAssertEqual(model.unread, 1)
        // Marking it read twice asks the hub once.
        await model.set(model.notices[0], read: true)
        XCTAssertEqual(marked.count, 1)
        // A refusal says why and puts back what the hub has.
        refuse = true
        await model.set(model.notices[1], read: true)
        XCTAssertNotNil(model.failure)
        XCTAssertEqual(model.unread, 2)
        XCTAssertNil(model.notices[1].readAt)
        refuse = false
        await model.readAll()
        XCTAssertEqual(markedAll, 1)
        XCTAssertEqual(model.unread, 0)
        XCTAssertTrue(model.notices.allSatisfy { $0.readAt != nil })
    }

    // MARK: - Account

    func testANameIsSavedTrimmedAndOnlyWhenItChanged() {
        XCTAssertEqual(OwnSettingsRules.nameToSave("  Sara  ", current: "Tariq"), "Sara")
        XCTAssertNil(OwnSettingsRules.nameToSave("Tariq ", current: "Tariq"))
        XCTAssertNil(OwnSettingsRules.nameToSave("   ", current: "Tariq"))
        XCTAssertNil(OwnSettingsRules.nameToSave(String(repeating: "a", count: 81), current: "Tariq"))
        XCTAssertEqual(OwnSettingsRules.nameToSave(String(repeating: "a", count: 80), current: "Tariq")?.count, 80)
    }

    func testANewPasswordMustBeLongEnoughAndTypedTwice() {
        XCTAssertNil(OwnSettingsRules.passwordProblem(new: "", again: ""))
        XCTAssertEqual(OwnSettingsRules.passwordProblem(new: "short", again: ""), .short)
        XCTAssertEqual(OwnSettingsRules.passwordProblem(new: "long enough", again: "long"), .mismatch)
        XCTAssertNil(OwnSettingsRules.passwordProblem(new: "long enough", again: "long enough"))
        XCTAssertTrue(OwnSettingsRules.canChangePassword(current: "old", new: "long enough", again: "long enough"))
        XCTAssertFalse(OwnSettingsRules.canChangePassword(current: "", new: "long enough", again: "long enough"))
        XCTAssertFalse(OwnSettingsRules.canChangePassword(current: "old", new: "long enough", again: "long enougH"))
        XCTAssertFalse(OwnSettingsRules.canChangePassword(current: "old", new: "1234567", again: "1234567"))
    }

    func testAPictureIsMadeSmallAndSentAsAJPEGTheHubTakes() {
        let wide = OwnSettingsRules.avatarSize(width: 4032, height: 3024)
        XCTAssertEqual(wide.width, 512)
        XCTAssertEqual(wide.height, 384)
        let small = OwnSettingsRules.avatarSize(width: 200, height: 300)
        XCTAssertEqual(small.width, 200, "never made larger")
        XCTAssertEqual(small.height, 300)
        XCTAssertEqual(OwnSettingsRules.avatarSize(width: 0, height: 10).width, 0)
        let url = OwnSettingsRules.avatarDataURL(jpeg: Data([0xFF, 0xD8, 0xFF]))
        XCTAssertEqual(url, "data:image/jpeg;base64,/9j/")
        XCTAssertNil(OwnSettingsRules.avatarDataURL(jpeg: Data()))
        XCTAssertNil(OwnSettingsRules.avatarDataURL(jpeg: Data(count: 512 * 1024 + 1)))
        XCTAssertEqual(OwnSettingsRules.initials("Tariq Alowairdhi"), "TA")
        XCTAssertEqual(OwnSettingsRules.initials("طارق"), "ط")
        XCTAssertEqual(OwnSettingsRules.initials(" "), "?")
    }

    func testALinkShowsOnceTheListGrowsWhileACodeWaits() {
        XCTAssertTrue(OwnSettingsRules.linked(waiting: true, before: 1, now: 2))
        XCTAssertFalse(OwnSettingsRules.linked(waiting: true, before: 1, now: 1))
        XCTAssertFalse(OwnSettingsRules.linked(waiting: false, before: 1, now: 2))
        XCTAssertFalse(OwnSettingsRules.linked(waiting: true, before: nil, now: 2))
    }

    // MARK: - Privacy

    func testRevokingADeviceSaysItIsUnlinked() {
        let device = AppToken(id: "t1", name: "Phone", scopes: [.read], deviceId: "d1", createdAt: date)
        let app = AppToken(id: "t2", name: "Script", scopes: [.read], createdAt: date)
        XCTAssertTrue(OwnSettingsRules.isDevice(device))
        XCTAssertFalse(OwnSettingsRules.isDevice(app))
        XCTAssertEqual(OwnSettingsRules.revokeBodyKey(device), "own_settings.privacy_revoke_device_body")
        XCTAssertEqual(OwnSettingsRules.revokeBodyKey(app), "own_settings.privacy_revoke_body")
        // Both sentences exist in both languages.
        for language in [AppLanguage.en, .ar] {
            let l10n = L10n(language)
            for key in ["own_settings.privacy_revoke_device_body", "own_settings.privacy_revoke_body", "own_settings.inbox_mark_all"] {
                XCTAssertNotEqual(l10n(key), key, "\(key) in \(language)")
            }
        }
    }
}

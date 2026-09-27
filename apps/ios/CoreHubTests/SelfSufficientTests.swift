@testable import CoreHub
import CoreHubClient
import XCTest

/// Every section works in the app on its own (docs/changes/2026-09-27-twuijri-ios-self-sufficient.md):
/// the terminal's screen, linked hubs' rules, pairing a channel by its code, and hub links opening in
/// the app instead of a browser.
final class SelfSufficientTests: XCTestCase {
    private let date = Date(timeIntervalSince1970: 1_790_000_000)

    private func rows(_ buffer: TerminalScreenBuffer) -> [String] {
        buffer.lines.map(TerminalScreenBuffer.text(of:))
    }

    // MARK: - The terminal's screen

    func testTextWrapsAtTheEdgeAndNewlinesReturnThroughTheShellsCarriageReturn() {
        let screen = TerminalScreenBuffer(cols: 5, rows: 3)
        screen.feed("abcdefg\r\nxy")
        XCTAssertEqual(rows(screen), ["abcde", "fg", "xy"])
        XCTAssertEqual(screen.cursorX, 2)
        XCTAssertEqual(screen.cursorY, 2)
    }

    func testLinesThatScrollOffTheTopAreKeptInTheHistory() {
        let screen = TerminalScreenBuffer(cols: 10, rows: 2)
        screen.feed("one\r\ntwo\r\nthree")
        XCTAssertEqual(rows(screen), ["two", "three"])
        XCTAssertEqual(screen.scrollback.map(TerminalScreenBuffer.text(of:)), ["one"])
        XCTAssertEqual(screen.plainText, "one\ntwo\nthree")
    }

    func testCursorMovesAndErasesAsAShellPromptAsksForThem() {
        let screen = TerminalScreenBuffer(cols: 10, rows: 3)
        screen.feed("hello\u{1B}[2D\u{1B}[K!")
        XCTAssertEqual(rows(screen)[0], "hel!")
        screen.feed("\u{1B}[3;4Hx")
        XCTAssertEqual(rows(screen)[2], "   x")
        screen.feed("\u{1B}[2J\u{1B}[H")
        XCTAssertEqual(rows(screen), ["", "", ""])
        XCTAssertEqual(screen.cursorX, 0)
        XCTAssertEqual(screen.cursorY, 0)
        // Backspace and delete-character, as a line editor sends them.
        screen.feed("abc\u{8}\u{1B}[P")
        XCTAssertEqual(rows(screen)[0], "ab")
    }

    func testColoursAndStylesAreKeptPerCell() {
        let screen = TerminalScreenBuffer(cols: 10, rows: 2)
        screen.feed("\u{1B}[1;31mA\u{1B}[0mB\u{1B}[38;5;208mC\u{1B}[38;2;1;2;3mD")
        let cells = screen.lines[0]
        XCTAssertEqual(cells[0].style.foreground, .indexed(1))
        XCTAssertTrue(cells[0].style.bold)
        XCTAssertEqual(cells[1].style, .plain)
        XCTAssertEqual(cells[2].style.foreground, .indexed(208))
        XCTAssertEqual(cells[3].style.foreground, .rgb(1, 2, 3))
        XCTAssertEqual(TerminalPalette.rgb(.indexed(16)).0, 0)
        XCTAssertEqual(TerminalPalette.rgb(.indexed(231)).0, 255)
        XCTAssertEqual(TerminalPalette.rgb(.indexed(232)).0, 8)
    }

    func testAFullScreenProgramUsesTheAlternateScreenAndLeavesTheShellAsItWas() {
        let screen = TerminalScreenBuffer(cols: 10, rows: 3)
        screen.feed("$ top")
        screen.feed("\u{1B}[?1049h\u{1B}[?1h\u{1B}[H\u{1B}[2Jload 1.0")
        XCTAssertTrue(screen.usingAlternateScreen)
        XCTAssertTrue(screen.applicationCursorKeys)
        XCTAssertEqual(rows(screen)[0], "load 1.0")
        screen.feed("\u{1B}[?1049l\u{1B}[?1l")
        XCTAssertFalse(screen.usingAlternateScreen)
        XCTAssertEqual(rows(screen)[0], "$ top")
        XCTAssertEqual(TerminalKeys.sequence(.up, applicationCursor: true), "\u{1B}OA")
        XCTAssertEqual(TerminalKeys.sequence(.up, applicationCursor: false), "\u{1B}[A")
    }

    func testTheScreenAnswersWhereItsCursorIsAndIgnoresTitles() {
        let screen = TerminalScreenBuffer(cols: 10, rows: 3)
        var replies: [String] = []
        screen.respond = { replies.append($0) }
        screen.feed("\u{1B}]0;user@host: ~\u{7}ab\u{1B}[6n")
        XCTAssertEqual(replies, ["\u{1B}[1;3R"])
        XCTAssertEqual(screen.title, "user@host: ~")
        XCTAssertEqual(rows(screen)[0], "ab")
    }

    func testAScrollRegionScrollsOnlyItsRows() {
        let screen = TerminalScreenBuffer(cols: 5, rows: 4)
        screen.feed("top\r\na\r\nb\r\nbot")
        screen.feed("\u{1B}[2;3r\u{1B}[3;1H\n")
        XCTAssertEqual(rows(screen), ["top", "b", "", "bot"])
    }

    func testResizingKeepsTheCursorOnTheScreen() {
        let screen = TerminalScreenBuffer(cols: 10, rows: 4)
        screen.feed("1\r\n2\r\n3\r\n4")
        screen.resize(cols: 4, rows: 2)
        XCTAssertEqual(rows(screen), ["3", "4"])
        XCTAssertEqual(screen.cursorY, 1)
        XCTAssertEqual(screen.scrollback.map(TerminalScreenBuffer.text(of:)), ["1", "2"])
    }

    func testKeysThePhoneKeyboardLacks() {
        XCTAssertEqual(TerminalKeys.control("c"), "\u{3}")
        XCTAssertEqual(TerminalKeys.control("D"), "\u{4}")
        XCTAssertEqual(TerminalKeys.control("["), "\u{1B}")
        XCTAssertEqual(TerminalKeys.typed("ls\n"), "ls\r")
        XCTAssertEqual(TerminalKeys.paste("a\nb", bracketed: true), "\u{1B}[200~a\rb\u{1B}[201~")
        XCTAssertEqual(TerminalKeys.paste("a\r\nb", bracketed: false), "a\rb")
    }

    func testTheTerminalsAcksAndEventsAreRead() throws {
        let ack = TerminalAck.parse(try JSONSerialization.data(withJSONObject: [
            "ok": true, "session": ["id": "01J8QK3ZR2W7M5N4P6T8V9X0TM", "cwd": "/data/workspaces/default"], "backlog": "$ ",
        ]))
        XCTAssertEqual(ack, TerminalAck(ok: true, sessionID: "01J8QK3ZR2W7M5N4P6T8V9X0TM", cwd: "/data/workspaces/default", backlog: "$ ", error: nil, reason: nil))
        let refused = TerminalAck.parse(try JSONSerialization.data(withJSONObject: [
            "ok": false, "error": "too many", "code": "conflict", "details": ["reason": "terminal_limit"],
        ]))
        XCTAssertFalse(refused.ok)
        XCTAssertEqual(refused.reason, "terminal_limit")
        XCTAssertFalse(TerminalAck.parse(nil).ok)

        let output = try JSONSerialization.data(withJSONObject: [
            "event": "terminal.output", "namespace": "/rt/terminal", "profile": "default", "ts": "2026-09-25T10:15:04Z", "seq": 3,
            "payload": ["terminal_id": "T1", "data": "hi"],
        ])
        XCTAssertEqual(TerminalEvent.parse("terminal.output", output), .output(id: "T1", data: "hi"))
        let exited = try JSONSerialization.data(withJSONObject: [
            "event": "terminal.exited", "namespace": "/rt/terminal", "profile": "default", "ts": "2026-09-25T10:15:04Z", "seq": 4,
            "payload": ["terminal_id": "T1", "reason": "idle", "exit_code": NSNull()],
        ])
        XCTAssertEqual(TerminalEvent.parse("terminal.exited", exited), .exited(id: "T1", reason: "idle"))
    }

    @MainActor
    func testTheTerminalModelFeedsOutputToItsTabAndMarksAnEndedOne() {
        let session = TerminalSession(id: "T1", profile: "default", cwd: "/data/workspaces/default", cols: 80, rows: 24,
                                      attached: false, startedAt: date, lastActiveAt: date)
        let status = TerminalStatus(enabled: true, pty: true, shell: "/bin/bash", idleTimeoutSeconds: 900, maxSessions: 1, sessions: [session])
        let model = TerminalModel(status: status, app: nil)
        XCTAssertEqual(model.tabs.map(\.number), [1])
        XCTAssertEqual(model.active, "T1")
        model.apply(.output(id: "T1", data: "$ ls"))
        XCTAssertEqual(model.buffer("T1").map { TerminalScreenBuffer.text(of: $0.lines[0]) }, "$ ls")
        XCTAssertFalse(model.canOpen, "not connected, and one of one already runs")
        model.apply(.exited(id: "T1", reason: "idle"))
        XCTAssertEqual(model.tabs.first?.ended, "idle")
        XCTAssertEqual(model.liveCount, 0)
        let l10n = L10n(.en, bundle: Bundle(for: AppModel.self))
        XCTAssertEqual(TerminalWords.ended("gone", l10n), l10n("terminal.gone"))
        XCTAssertTrue(TerminalWords.ended("idle", l10n).contains(l10n("terminal.reason_idle")))
    }

    // MARK: - Linked hubs

    private func peer(_ status: Peer.Status, enabled: Bool = true) -> Peer {
        Peer(id: "p1", name: "Office", hubName: "Office hub", url: "https://office.example", direction: .inbound, status: status,
             enabled: enabled, fingerprint: "SHA256:abcd", asksPerHour: 30, createdAt: date)
    }

    func testLinkedHubsRules() {
        XCTAssertEqual(LinkedHubsRules.limit(" 30 "), 30)
        XCTAssertNil(LinkedHubsRules.limit("0"))
        XCTAssertNil(LinkedHubsRules.limit("1001"))
        XCTAssertNil(LinkedHubsRules.limit("ten"))
        XCTAssertFalse(LinkedHubsRules.usable(peer(.pending)))
        XCTAssertFalse(LinkedHubsRules.usable(peer(.linked, enabled: false)))
        XCTAssertTrue(LinkedHubsRules.usable(peer(.waiting)))
        XCTAssertEqual(LinkedHubsRules.removeKey(peer(.pending)), "linked_hubs.refuse")
        XCTAssertEqual(LinkedHubsRules.removeKey(peer(.linked)), "linked_hubs.unlink")
        XCTAssertEqual(LinkedHubsRules.statusKind(.linked), .good)
    }

    func testALinkedHubsRefusalIsSaidInItsOwnWords() {
        let l10n = L10n(.en, bundle: Bundle(for: AppModel.self))
        var failure = HubFailure(kind: .http, status: 409, code: "conflict", message: "raw", operationID: nil, requestID: nil, detail: "")
        failure.peerCode = "invite_refused"
        failure.reason = "peer_refused"
        XCTAssertEqual(LinkedHubsRules.describe(failure, l10n), l10n("linked_hubs.reason.invite_refused"))
        failure.peerCode = nil
        failure.reason = "https_required"
        XCTAssertEqual(LinkedHubsRules.describe(failure, l10n), l10n("linked_hubs.reason.https_required"))
        failure.reason = nil
        failure.status = 429
        XCTAssertEqual(LinkedHubsRules.describe(failure, l10n), l10n("linked_hubs.reason.rate_limited"))
        failure.status = 409
        XCTAssertEqual(LinkedHubsRules.describe(failure, l10n), "raw")
    }

    // MARK: - Pairing a channel by its code

    func testThePairingJobsStateIsReadFromItsResult() {
        let state = ChannelPairState([
            "qr": .string("2@abc"), "expires_at": .string("2026-09-27T10:00:00.000Z"),
            "account_name": .string("Ahmad"), "account_phone": .string("+966500000000"), "applies": .string("now"),
        ])
        XCTAssertEqual(state.qr, "2@abc")
        XCTAssertNotNil(state.expiresAt)
        XCTAssertEqual(state.account, "Ahmad · +966500000000")
        XCTAssertEqual(state.doneKey(chosen: .selfChat), "channel_pair.done_as_self_now")
        XCTAssertEqual(state.doneKey(chosen: .bot), "channel_pair.done_as_now")
        let empty = ChannelPairState(nil)
        XCTAssertNil(empty.qr)
        XCTAssertNil(empty.account)
        XCTAssertEqual(empty.doneKey(chosen: .bot), "channel_pair.done")
        XCTAssertNotNil(QRImage.make("2@abc"))
    }

    func testNoWayOutToTheWebIsLeft() {
        let l10n = L10n(.en, bundle: Bundle(for: AppModel.self))
        for key in ["channels.open_web", "agents2.ch.qr_note", "settings.on_the_web", "models.edit_on_web"] {
            XCTAssertFalse(l10n.has(key), key)
        }
    }

    // MARK: - Hub links open in the app

    func testALinkToAHubPageOpensThatPageInTheApp() {
        let hub = URL(string: "https://hub.example:8443")!
        XCTAssertEqual(FileLinks.appLink("https://hub.example:8443/tasks", hub: hub)?.absoluteString, "corehub://open/tasks")
        XCTAssertEqual(FileLinks.appLink("/chat/01J8QK3ZR2W7M5N4P6T8V9X0YA?profile=work", hub: hub)?.absoluteString,
                       "corehub://open/chat/01J8QK3ZR2W7M5N4P6T8V9X0YA?profile=work")
        XCTAssertEqual(FileLinks.appLink("https://hub.example:8443/settings/linked-hubs", hub: hub)?.absoluteString, "corehub://open/settings/linked-hubs")
        XCTAssertNil(FileLinks.appLink("https://example.com/tasks", hub: hub), "another site stays a web link")
        XCTAssertNil(FileLinks.appLink("https://hub.example:8443/nowhere", hub: hub))
        XCTAssertNil(FileLinks.appLink("https://hub.example:8443/", hub: hub))
        XCTAssertNil(FileLinks.appLink("notes.md", hub: hub))
        let route = AppModel.route(for: FileLinks.appLink("/tasks", hub: hub)!, selector: "default")
        XCTAssertEqual(route, .destination(.tasks))
    }
}

// Settings → Terminal on the phone: the owner's shells on the hub's host (DECISIONS §70), the same
// sessions the web shows. `GET /terminal` says whether the terminal is on and which sessions live;
// typing, resizing, opening and closing go over `/rt/terminal` (events/README.md, "The owner's
// terminal"). A session belongs to the owner, not to this phone: after a dropped connection each
// tab attaches again and is repainted from what the hub kept.
import CoreHubClient
import Foundation
import Observation

/// What an ack from `/rt/terminal` says, read loosely (`{ ok, session?, backlog?, error?, details? }`).
struct TerminalAck: Equatable {
    var ok: Bool
    var sessionID: String?
    var cwd: String?
    var backlog: String?
    var error: String?
    var reason: String?

    static func parse(_ data: Data?) -> TerminalAck {
        guard let data, let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return TerminalAck(ok: false, error: nil)
        }
        let session = object["session"] as? [String: Any]
        let details = object["details"] as? [String: Any]
        return TerminalAck(
            ok: object["ok"] as? Bool ?? false,
            sessionID: session?["id"] as? String,
            cwd: session?["cwd"] as? String,
            backlog: object["backlog"] as? String,
            error: object["error"] as? String,
            reason: details?["reason"] as? String
        )
    }
}

/// `terminal.output` / `terminal.exited`, the two events of the namespace.
enum TerminalEvent: Equatable {
    case output(id: String, data: String)
    case exited(id: String, reason: String)

    static func parse(_ name: String, _ argument: Data?) -> TerminalEvent? {
        guard let envelope = Envelope.parse(argument),
              let payload = try? JSONSerialization.jsonObject(with: envelope.payload) as? [String: Any],
              let id = payload["terminal_id"] as? String else { return nil }
        switch name {
        case "terminal.output":
            guard let data = payload["data"] as? String else { return nil }
            return .output(id: id, data: data)
        case "terminal.exited":
            return .exited(id: id, reason: payload["reason"] as? String ?? "exited")
        default:
            return nil
        }
    }
}

@MainActor
@Observable
final class TerminalModel {
    struct Tab: Identifiable, Equatable {
        let id: String
        let number: Int
        var cwd: String
        /// Why it ended (`exited`, `closed`, `idle`, `shutdown`), or `gone` when the hub no longer
        /// knows it; nil while it runs.
        var ended: String?
    }

    let status: TerminalStatus
    private(set) var tabs: [Tab]
    var active: String?
    private(set) var problem: String?
    private(set) var opening = false
    private(set) var connected = false
    /// Bumped on every change a screen shows, so the visible screen redraws.
    private(set) var revision = 0

    @ObservationIgnored private var buffers: [String: TerminalScreenBuffer] = [:]
    @ObservationIgnored private weak var app: AppModel?
    @ObservationIgnored private var namespace: RealtimeNamespace?
    @ObservationIgnored private var listeners: [UUID] = []
    @ObservationIgnored private var counter: Int
    /// The screen size the phone last measured, sent with `open` and `resize`.
    @ObservationIgnored private(set) var size = (cols: 80, rows: 24)

    init(status: TerminalStatus, app: AppModel?) {
        self.status = status
        self.app = app
        self.tabs = status.sessions.enumerated().map { Tab(id: $0.element.id, number: $0.offset + 1, cwd: $0.element.cwd, ended: nil) }
        self.counter = status.sessions.count
        self.active = status.sessions.first?.id
        for session in status.sessions {
            buffers[session.id] = TerminalScreenBuffer(cols: session.cols, rows: session.rows)
        }
    }

    var liveCount: Int { tabs.filter { $0.ended == nil }.count }

    var canOpen: Bool { connected && !opening && liveCount < status.maxSessions }

    var front: Tab? { tabs.first { $0.id == active } }

    func buffer(_ id: String) -> TerminalScreenBuffer? { buffers[id] }

    // MARK: - The connection

    func start() {
        guard namespace == nil, let app else { return }
        let namespace = app.realtime.namespace("/rt/terminal") { [weak app] in
            await app?.handshake(all: false) ?? [:]
        }
        self.namespace = namespace
        listeners.append(namespace.onEvent { [weak self] name, argument in
            guard let self, let event = TerminalEvent.parse(name, argument) else { return }
            self.apply(event)
        })
        listeners.append(namespace.onConnect { [weak self] in self?.attachAll() })
        if namespace.isConnected { attachAll() }
    }

    func stop() {
        for id in listeners { namespace?.remove(id) }
        listeners = []
        namespace = nil
        connected = false
    }

    func apply(_ event: TerminalEvent) {
        switch event {
        case .output(let id, let data):
            guard let buffer = buffers[id] else { return }
            buffer.feed(data)
            revision += 1
        case .exited(let id, let reason):
            markEnded(id, reason)
        }
    }

    private func markEnded(_ id: String, _ reason: String) {
        guard let index = tabs.firstIndex(where: { $0.id == id }) else { return }
        tabs[index].ended = reason
        revision += 1
    }

    private func attachAll() {
        connected = true
        for tab in tabs where tab.ended == nil {
            let id = tab.id
            Task {
                guard let namespace = self.namespace else { return }
                let ack = TerminalAck.parse(try? await namespace.emit("attach", ["terminal_id": id]))
                guard ack.ok else {
                    self.markEnded(id, "gone")
                    return
                }
                let buffer = TerminalScreenBuffer(cols: self.size.cols, rows: self.size.rows)
                self.wire(buffer, id)
                buffer.feed(ack.backlog ?? "")
                self.buffers[id] = buffer
                self.revision += 1
                _ = try? await namespace.emit("resize", ["terminal_id": id, "cols": self.size.cols, "rows": self.size.rows])
            }
        }
    }

    /// What the screen must answer (the cursor's place, the terminal's kind) goes back as input.
    private func wire(_ buffer: TerminalScreenBuffer, _ id: String) {
        buffer.respond = { [weak self] reply in self?.send(reply, to: id) }
    }

    // MARK: - What the owner does

    func open(profile: String) {
        guard let namespace, canOpen else { return }
        problem = nil
        opening = true
        let size = size
        Task {
            let ack = TerminalAck.parse(try? await namespace.emit("open", ["profile": profile, "cols": size.cols, "rows": size.rows]))
            self.opening = false
            guard ack.ok, let id = ack.sessionID else {
                self.problem = ack.reason == "terminal_limit"
                    ? self.app?.l10n("terminal.limit", ["max": String(self.status.maxSessions)])
                    : (ack.error ?? self.app?.l10n("terminal.open_failed"))
                return
            }
            self.counter += 1
            let buffer = TerminalScreenBuffer(cols: size.cols, rows: size.rows)
            self.wire(buffer, id)
            self.buffers[id] = buffer
            self.tabs.append(Tab(id: id, number: self.counter, cwd: ack.cwd ?? "", ended: nil))
            self.active = id
        }
    }

    func close(_ tab: Tab) {
        let remove = {
            self.tabs.removeAll { $0.id == tab.id }
            self.buffers[tab.id] = nil
            if self.active == tab.id { self.active = self.tabs.last?.id }
        }
        guard tab.ended == nil, let namespace else {
            remove()
            return
        }
        Task {
            _ = try? await namespace.emit("close", ["terminal_id": tab.id])
            remove()
        }
    }

    func send(_ data: String, to id: String? = nil) {
        guard let id = id ?? active, !data.isEmpty, let namespace,
              tabs.first(where: { $0.id == id })?.ended == nil else { return }
        Task { _ = try? await namespace.emit("input", ["terminal_id": id, "data": data]) }
    }

    /// The phone measured a new screen size: every live screen takes it, and the hub hears it.
    func resize(cols: Int, rows: Int) {
        let cols = max(20, min(500, cols)), rows = max(5, min(200, rows))
        guard cols != size.cols || rows != size.rows else { return }
        size = (cols, rows)
        for (id, buffer) in buffers {
            buffer.resize(cols: cols, rows: rows)
            guard tabs.first(where: { $0.id == id })?.ended == nil, let namespace else { continue }
            Task { _ = try? await namespace.emit("resize", ["terminal_id": id, "cols": cols, "rows": rows]) }
        }
        revision += 1
    }
}

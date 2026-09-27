// A list that reads itself again when the hub says something in it changed (the web's
// invalidation on `/rt/tasks` and `/rt/schedules`, DECISIONS §32): the board and the Schedules
// page follow what an agent or another device does, instead of waiting for a pull to refresh.
// The handshake hears every profile the person may enter (ADR 0016); bursts are read once.
import SwiftUI

extension View {
    func liveReload(_ path: String, events prefixes: [String], reload: @escaping () async -> Void) -> some View {
        modifier(LiveReloadModifier(path: path, prefixes: prefixes, reload: reload))
    }
}

enum LiveReloadRules {
    /// Whether an event name is one the list shows.
    static func matters(_ name: String, prefixes: [String]) -> Bool {
        prefixes.contains { name.hasPrefix($0) }
    }
}

private struct LiveReloadModifier: ViewModifier {
    let path: String
    let prefixes: [String]
    let reload: () async -> Void
    @Environment(AppModel.self) private var app
    @State private var listener: UUID?
    @State private var pending: Task<Void, Never>?

    func body(content: Content) -> some View {
        content
            .onAppear {
                #if DEBUG
                if DemoHub.isOn { return }
                #endif
                guard listener == nil else { return }
                let namespace = app.realtime.namespace(path) { [weak app] in await app?.handshake(all: true) ?? [:] }
                listener = namespace.onEvent { name, _ in
                    guard LiveReloadRules.matters(name, prefixes: prefixes) else { return }
                    pending?.cancel()
                    pending = Task {
                        try? await Task.sleep(nanoseconds: 500_000_000)
                        guard !Task.isCancelled else { return }
                        await reload()
                    }
                }
            }
            .onDisappear {
                if let listener { app.realtime.namespace(path) { [weak app] in await app?.handshake(all: true) ?? [:] }.remove(listener) }
                listener = nil
                pending?.cancel()
            }
    }
}

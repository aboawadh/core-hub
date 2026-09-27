// What a chat can tell about itself (apps batch 6), from the same contract operations as the web's
// chat: how full the model's window is and what fills it (`sessions.getContextBreakdown`, §57/§102),
// compressing with a focus (`sessions.compress`), the chat's runs (`sessions.listRuns`), the
// subagents its agent delegated to (`sessions.listSubagents`, `.interruptSubagent`, `.steerSubagent`,
// `.tailSubagent`, §56), the files each run changed with their diffs (`sessions.listChanges`,
// `.getRunChanges`, `.getRunChangeDiff`, §49/§102), and the conversation's files
// (`sessions.listFiles`, §48). The rules are plain functions, tested in ChatInsightTests; Android's
// `chat/ChatInsight.kt` is their twin.
import CoreHubClient
import Foundation
import Observation

enum ChatInsight {
    // MARK: - The context window

    /// Where the figure came from; it is always said, never implied (web `ContextRing.tsx`).
    enum Source: String, Equatable {
        /// The agent's own count of the window and of what the last request took.
        case reported
        /// The same, when the agent says it counted roughly.
        case agentEstimate = "agent_estimate"
        /// Nothing reported: the provider's tokens for the last finished turn against the catalogue's window.
        case estimate
    }

    struct Use: Equatable {
        let used: Int
        let window: Int
        /// 0…1
        let ratio: Double
        let source: Source
    }

    /// How full the window is: the agent's report, else the last turn the provider counted against
    /// the catalogue's `context_window`. Nothing known is no ring — never an invented number.
    static func use(reported: ContextUsage?, window catalogue: Int?, runs: [Run]) -> Use? {
        if let reported, let window = reported.windowTokens ?? catalogue, window > 0 {
            return Use(
                used: reported.usedTokens, window: window,
                ratio: min(1, Double(reported.usedTokens) / Double(window)),
                source: reported.estimated == true ? .agentEstimate : .reported
            )
        }
        guard let window = catalogue, window > 0 else { return nil }
        let counted = runs.filter { ($0.usage?.inputTokens ?? 0) > 0 }
        guard let last = counted.max(by: { ($0.startedAt ?? $0.createdAt) < ($1.startedAt ?? $1.createdAt) }),
              let usage = last.usage else { return nil }
        let used = usage.inputTokens + usage.outputTokens
        return Use(used: used, window: window, ratio: min(1, Double(used) / Double(window)), source: .estimate)
    }

    /// A whole percentage, floored: 99.6 % of a window is not «100 %».
    static func percent(_ use: Use) -> Int { Int((use.ratio * 100).rounded(.down)) }

    enum Band: Equatable { case normal, warning, danger }

    /// Three bands, because a number alone does not say whether to act.
    static func band(_ use: Use) -> Band {
        use.ratio >= 0.9 ? .danger : (use.ratio >= 0.7 ? .warning : .normal)
    }

    /// The categories named in the person's language (Hermes's today, §102); any other keeps the agent's label.
    static let knownCategories = [
        "system_prompt", "tool_definitions", "rules", "skills", "mcp", "subagent_definitions", "memory", "conversation",
    ]

    /// Each category's share of the bar: over the whole window, or over their sum when the agent's
    /// rough counts add up to more.
    static func shares(_ categories: [SessionContextCategory], window: Int) -> [Double] {
        let whole = max(window, categories.reduce(0) { $0 + $1.tokens })
        guard whole > 0 else { return categories.map { _ in 0 } }
        return categories.map { Double($0.tokens) / Double(whole) }
    }

    /// What «Compress» sends: the focus the person typed (trimmed, at most 2000 characters), or
    /// nothing — an empty body compresses the whole conversation.
    static func compressRequest(focus typed: String) -> SessionCompressRequest {
        let focus = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        return focus.isEmpty ? SessionCompressRequest() : SessionCompressRequest(focus: String(focus.prefix(2000)))
    }

    /// A count with Latin digits and the language's grouping.
    static func number(_ value: Int, _ language: AppLanguage) -> String {
        value.formatted(.number.locale(language.locale))
    }

    // MARK: - Runs

    /// Newest first; a run not started yet by when it was asked for.
    static func history(_ runs: [Run]) -> [Run] {
        runs.sorted { ($0.startedAt ?? $0.createdAt) > ($1.startedAt ?? $1.createdAt) }
    }

    /// How long it ran, or has been running up to `now`; nil before it started.
    static func elapsedMs(started: Date?, finished: Date?, now: Date) -> Int? {
        guard let started else { return nil }
        return max(0, Int(((finished ?? now).timeIntervalSince(started) * 1000).rounded()))
    }

    /// `0:05`, `12:40`, `1:02:03` — a clock, the same digits in both languages (web `subagents.ts`).
    static func clock(_ ms: Int) -> String {
        let total = ms / 1000
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        let seconds = total % 60
        let two = { (n: Int) in n < 10 ? "0\(n)" : "\(n)" }
        return hours > 0 ? "\(hours):\(two(minutes)):\(two(seconds))" : "\(minutes):\(two(seconds))"
    }

    /// What a run cost, as the web's turn says it: none without a price, `0 USD` when free, else
    /// rounded to where the digits stop being noise.
    static func cost(_ money: Money?) -> String? {
        guard let money, let value = Double(money.amount), value.isFinite else { return nil }
        if value == 0 { return "0 \(money.currency)" }
        let digits = value < 0.01 ? 4 : 2
        return String(format: "%.\(digits)f", locale: Locale(identifier: "en_US_POSIX"), value) + " \(money.currency)"
    }

    enum RunTone: Equatable { case running, good, bad, quiet }

    static func tone(_ status: RunStatus) -> RunTone {
        switch status {
        case .queued, .running, .waiting: return .running
        case .succeeded: return .good
        case .failed: return .bad
        case .cancelled: return .quiet
        }
    }

    // MARK: - Subagents

    struct SubagentRow: Equatable {
        let subagent: Subagent
        /// How far in it is drawn: under the one that started it.
        let indent: Int
    }

    /// The running ones as a tree — each after the one that started it, siblings in the order they
    /// started — and the finished ones, newest first. One whose parent is not running is drawn at its
    /// own depth, never lost (web `splitSubagents`).
    static func split(_ items: [Subagent]) -> (running: [SubagentRow], finished: [Subagent]) {
        let running = items.filter { $0.status == .running }.sorted { $0.startedAt < $1.startedAt }
        let ids = Set(running.map(\.id))
        var children: [String: [Subagent]] = [:]
        var roots: [Subagent] = []
        for item in running {
            if let parent = item.parentId, parent != item.id, ids.contains(parent) {
                children[parent, default: []].append(item)
            } else {
                roots.append(item)
            }
        }
        var rows: [SubagentRow] = []
        var seen = Set<String>()
        func walk(_ item: Subagent, _ indent: Int) {
            guard seen.insert(item.id).inserted else { return }
            rows.append(SubagentRow(subagent: item, indent: indent))
            for child in children[item.id] ?? [] { walk(child, indent + 1) }
        }
        for root in roots { walk(root, root.parentId != nil ? max(0, root.depth) : 0) }
        let finished = items.filter { $0.status != .running }
            .sorted { ($0.finishedAt ?? $0.startedAt) > ($1.finishedAt ?? $1.startedAt) }
        return (rows, finished)
    }

    /// One subagent's latest state into the list, in place, or added.
    static func upsert(_ list: [Subagent], _ next: Subagent) -> [Subagent] {
        guard let at = list.firstIndex(where: { $0.id == next.id }) else { return list + [next] }
        var copy = list
        copy[at] = next
        return copy
    }

    /// A note for a subagent: trimmed, at most 4000 characters; nothing typed is no note.
    static func steerText(_ typed: String) -> String? {
        let text = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : String(text.prefix(4000))
    }

    // MARK: - Changed files and their diffs

    enum DiffKind: Equatable { case context, add, del, note }

    struct DiffLine: Equatable {
        let kind: DiffKind
        /// Its number before the run (context and removed lines).
        let old: Int?
        /// Its number after the run (context and added lines).
        let new: Int?
        let text: String
    }

    struct DiffHunk: Equatable {
        let header: String
        var lines: [DiffLine]
    }

    /// The hunks of a unified diff, each line with its old and new number (web `parseUnifiedDiff`).
    static func parseDiff(_ text: String) -> [DiffHunk] {
        var hunks: [DiffHunk] = []
        var oldLine = 0
        var newLine = 0
        var lines = text.components(separatedBy: "\n")
        if lines.last == "" { lines.removeLast() }
        let header = try? NSRegularExpression(pattern: #"^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@"#)
        for line in lines {
            let range = NSRange(line.startIndex..., in: line)
            if let match = header?.firstMatch(in: line, range: range),
               let old = Range(match.range(at: 1), in: line), let new = Range(match.range(at: 2), in: line) {
                hunks.append(DiffHunk(header: line, lines: []))
                oldLine = Int(line[old]) ?? 0
                newLine = Int(line[new]) ?? 0
                continue
            }
            guard !hunks.isEmpty else { continue }
            let body = String(line.dropFirst())
            switch line.first {
            case "+":
                hunks[hunks.count - 1].lines.append(DiffLine(kind: .add, old: nil, new: newLine, text: body))
                newLine += 1
            case "-":
                hunks[hunks.count - 1].lines.append(DiffLine(kind: .del, old: oldLine, new: nil, text: body))
                oldLine += 1
            case "\\":
                hunks[hunks.count - 1].lines.append(DiffLine(kind: .note, old: nil, new: nil, text: String(line.dropFirst(2))))
            default:
                hunks[hunks.count - 1].lines.append(DiffLine(kind: .context, old: oldLine, new: newLine, text: body))
                oldLine += 1
                newLine += 1
            }
        }
        return hunks
    }

    /// `+14 −2`, or nothing when the hub could not count the lines (binary, no copy of before).
    static func counts(additions: Int?, deletions: Int?) -> String? {
        guard additions != nil || deletions != nil else { return nil }
        return "+\(additions ?? 0) −\(deletions ?? 0)"
    }

    /// A file the run left behind opens in the viewer; a deleted one has nothing to open.
    static func canOpen(_ change: RunFileChangeKind) -> Bool { change != .deleted }

    /// What the diff page says instead of a diff, as the file's entry tells it; nil when there is one to read.
    static func noDiffKey(_ state: RunFileDiffState, live: Bool) -> String? {
        if live { return "chat_insight.diff_live" }
        switch state {
        case .available: return nil
        case .binary: return "chat_insight.diff_binary"
        case .tooLarge: return "chat_insight.diff_too_large"
        case .unavailable: return "chat_insight.diff_unavailable"
        }
    }

    /// Which recorded changes are still current: the runs that ended. A new one means read again —
    /// the hub records a run's changes before it says the run ended.
    static func changesRevision(_ runs: [Run]) -> String {
        runs.filter { [.succeeded, .failed, .cancelled].contains($0.status) }.map(\.id).sorted().joined(separator: ",")
    }

    /// The last part of a path, for a file's row and the viewer.
    static func fileName(_ path: String) -> String {
        path.split(separator: "/").last.map(String.init) ?? path
    }
}

/// The calls behind the insight, each in the chat's own profile.
@MainActor
enum ChatInsightCalls {
    static func breakdown(_ app: AppModel, id: String, profile: String) async throws -> SessionContextBreakdown {
        try await app.api.call {
            try await SessionsAPI.sessionsGetContextBreakdown(xHubProfile: profile, sessionId: id, apiConfiguration: $0)
        }
    }

    static func runs(_ app: AppModel, id: String, profile: String, cursor: String?) async throws -> SessionsListRuns200Response {
        try await app.api.call {
            try await SessionsAPI.sessionsListRuns(xHubProfile: profile, sessionId: id, cursor: cursor, limit: 30, apiConfiguration: $0)
        }
    }

    static func subagents(_ app: AppModel, id: String, profile: String) async throws -> SubagentList {
        try await app.api.call {
            try await SessionsAPI.sessionsListSubagents(xHubProfile: profile, sessionId: id, apiConfiguration: $0)
        }
    }

    static func interrupt(_ app: AppModel, id: String, profile: String, subagent: String) async throws -> Subagent {
        try await app.api.call {
            try await SessionsAPI.sessionsInterruptSubagent(xHubProfile: profile, sessionId: id, subagentId: subagent, apiConfiguration: $0)
        }
    }

    static func steer(_ app: AppModel, id: String, profile: String, subagent: String, text: String) async throws -> SubagentSteerResult {
        try await app.api.call {
            try await SessionsAPI.sessionsSteerSubagent(
                xHubProfile: profile, sessionId: id, subagentId: subagent, subagentSteer: SubagentSteer(text: text), apiConfiguration: $0
            )
        }
    }

    static func tail(_ app: AppModel, id: String, profile: String, subagent: String) async throws -> SubagentTail {
        try await app.api.call {
            try await SessionsAPI.sessionsTailSubagent(xHubProfile: profile, sessionId: id, subagentId: subagent, apiConfiguration: $0)
        }
    }

    static func changes(_ app: AppModel, id: String, profile: String, cursor: String?) async throws -> RunChangesList {
        try await app.api.call {
            try await SessionsAPI.sessionsListChanges(xHubProfile: profile, sessionId: id, cursor: cursor, limit: 20, apiConfiguration: $0)
        }
    }

    /// What a run still going has changed so far (`live: true`); nil when it has nothing to compare
    /// (no folder) or has just ended — its recorded entry takes over then.
    static func liveChanges(_ app: AppModel, id: String, profile: String, run: String) async throws -> RunChanges? {
        do {
            return try await app.api.call {
                try await SessionsAPI.sessionsGetRunChanges(xHubProfile: profile, sessionId: id, runId: run, apiConfiguration: $0)
            }
        } catch {
            if HubFailure(error).status == 404 { return nil }
            throw error
        }
    }

    static func diff(_ app: AppModel, id: String, profile: String, run: String, path: String) async throws -> RunFileDiff {
        try await app.api.call {
            try await SessionsAPI.sessionsGetRunChangeDiff(xHubProfile: profile, sessionId: id, runId: run, path: path, apiConfiguration: $0)
        }
    }

    static func files(_ app: AppModel, id: String, profile: String) async throws -> SessionFileList {
        try await app.api.call {
            try await SessionsAPI.sessionsListFiles(xHubProfile: profile, sessionId: id, apiConfiguration: $0)
        }
    }
}

/// The chat's subagents, kept live while the chat is open (the top bar says when some are running),
/// and which of the insight's sheets is open.
@MainActor
@Observable
final class ChatInsightModel {
    enum Sheet: String, Identifiable {
        case context, runs, subagents, changes, files, trajectory
        var id: String { rawValue }
    }

    var sheet: Sheet?
    private(set) var subagents: [Subagent] = []
    private(set) var support: SubagentSupport = ._none
    private(set) var subagentsLoaded = false
    private(set) var subagentsError: String?

    let sessionID: String
    let profile: String
    @ObservationIgnored private weak var app: AppModel?
    @ObservationIgnored private var listener: UUID?
    @ObservationIgnored private var poll: Task<Void, Never>?

    /// A missed event is caught up with at this pace while one runs (as the web).
    static let runningPoll: UInt64 = 15_000_000_000
    static let subagentEvents: Set<String> = ["subagent.started", "subagent.updated", "subagent.completed"]

    init(app: AppModel, sessionID: String, profile: String) {
        self.app = app
        self.sessionID = sessionID
        self.profile = profile
    }

    var running: Int { subagents.filter { $0.status == .running }.count }

    func start() {
        guard listener == nil, let app else { return }
        listener = app.sessions?.onEvent { [weak self] name, argument in
            self?.receive(name, argument)
        }
        Task { await refreshSubagents() }
    }

    func stop() {
        if let listener { app?.sessions?.remove(listener) }
        listener = nil
        poll?.cancel()
        poll = nil
    }

    func refreshSubagents() async {
        guard let app else { return }
        do {
            let list = try await ChatInsightCalls.subagents(app, id: sessionID, profile: profile)
            subagents = list.items
            support = list.support
            subagentsError = nil
        } catch {
            subagentsError = HubFailure(error).describe(app.l10n)
        }
        subagentsLoaded = true
        keepPolling()
    }

    private func keepPolling() {
        poll?.cancel()
        guard running > 0, listener != nil else { return }
        poll = Task { [weak self] in
            try? await Task.sleep(nanoseconds: Self.runningPoll)
            guard !Task.isCancelled else { return }
            await self?.refreshSubagents()
        }
    }

    /// Each `subagent.*` carries the whole subagent; the events are profile-wide.
    private func receive(_ name: String, _ argument: Data?) {
        guard Self.subagentEvents.contains(name), let envelope = Envelope.parse(argument),
              let box = try? HubJSON.decoder.decode(SubagentBox.self, from: envelope.payload),
              box.subagent.sessionId == sessionID else { return }
        subagents = ChatInsight.upsert(subagents, box.subagent)
        if support == ._none { support = .observe }
        keepPolling()
    }

    private struct SubagentBox: Decodable { let subagent: Subagent }

    /// Stops one subagent; the list takes the hub's answer. Returns why it failed, if it did.
    func interrupt(_ id: String) async -> String? {
        guard let app else { return nil }
        do {
            let subagent = try await ChatInsightCalls.interrupt(app, id: sessionID, profile: profile, subagent: id)
            subagents = ChatInsight.upsert(subagents, subagent)
            return nil
        } catch {
            await refreshSubagents()
            return HubFailure(error).describe(app.l10n)
        }
    }

    /// Hands a subagent a note; the line to show (queued, rejected, or why it failed).
    func steer(_ id: String, text: String) async -> (text: String, failed: Bool) {
        guard let app else { return ("", true) }
        do {
            let result = try await ChatInsightCalls.steer(app, id: sessionID, profile: profile, subagent: id, text: text)
            return (app.l10n(result.status == .queued ? "chat_insight.steer_queued" : "chat_insight.steer_rejected"), result.status != .queued)
        } catch {
            return (HubFailure(error).describe(app.l10n), true)
        }
    }
}

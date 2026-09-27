// The conversation's trajectory on the phone (the web's Trajectory tab, `sessions.getTrajectory`):
// its figures (turns, steps, model and tool time, first word, tokens), and every step the agent
// took — inputs, turns, reasoning, tool calls, subagents — in order, each with how long it took,
// filtered by lane or found by a word. A phone has no room for the web's three-lane timeline, so
// each step carries its time instead. Read again while a run streams.
import CoreHubClient
import SwiftUI

enum TrajectoryRules {
    /// A duration as the web writes it: ms under a second, seconds under a minute, else minutes.
    static func duration(_ ms: Int?, _ l10n: L10n) -> String? {
        guard let ms else { return nil }
        if ms < 1000 { return "\(ms) \(l10n("trajectory_sheet.unit.ms"))" }
        if ms < 60_000 { return String(format: "%.1f %@", Double(ms) / 1000, l10n("trajectory_sheet.unit.s")) }
        return String(format: "%.1f %@", Double(ms) / 60_000, l10n("trajectory_sheet.unit.min"))
    }

    /// The steps a filter and a search leave.
    static func shown(_ steps: [TrajectoryStep], lane: TrajectoryLane?, query: String) -> [TrajectoryStep] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return steps.filter { step in
            (lane == nil || step.lane == lane)
                && (needle.isEmpty || [step.text, step.toolCall?.name, step.model].contains { ($0 ?? "").lowercased().contains(needle) })
        }
    }

    /// Longer than this with nothing happening is folded out of the timeline; a fold is drawn as
    /// `foldMS` of axis time (the web's `axisOf`).
    static let idleMS: Double = 3000
    static let foldMS: Double = 600

    /// The time a step occupies, in ms since 1970; a running one up to `now`; nil without times.
    static func span(_ step: TrajectoryStep, now: Date) -> (Double, Double)? {
        guard let started = step.startedAt else { return nil }
        let start = started.timeIntervalSince1970 * 1000
        if let ended = step.endedAt { return (start, max(start, ended.timeIntervalSince1970 * 1000)) }
        return step.status == .running ? (start, max(start, now.timeIntervalSince1970 * 1000)) : (start, start)
    }

    struct Axis {
        let blocks: [(Double, Double)]
        let offsets: [Double]
        let length: Double

        /// Where an instant sits, from 0 (start) to 1 (end).
        func at(_ time: Double) -> Double {
            guard !blocks.isEmpty else { return 0 }
            let index = blocks.firstIndex { time <= $0.1 } ?? blocks.count - 1
            let (start, end) = blocks[index]
            let within = min(max(time, start), end) - start
            return min(1, max(0, (offsets[index] + within) / length))
        }

        /// Where the folded gaps are drawn.
        var folds: [Double] { offsets.dropFirst().map { ($0 - TrajectoryRules.foldMS / 2) / length } }
    }

    static func axis(_ spans: [(Double, Double)]) -> Axis {
        var blocks: [(Double, Double)] = []
        for (start, end) in spans.sorted(by: { $0.0 < $1.0 }) {
            if let last = blocks.last, start - last.1 <= idleMS {
                blocks[blocks.count - 1].1 = max(last.1, end)
            } else {
                blocks.append((start, end))
            }
        }
        var offsets: [Double] = []
        var total: Double = 0
        for (index, block) in blocks.enumerated() {
            if index > 0 { total += foldMS }
            offsets.append(total)
            total += block.1 - block.0
        }
        return Axis(blocks: blocks, offsets: offsets, length: max(total, 1))
    }

    /// Sub-rows for a lane, so calls that ran at the same time do not hide each other.
    static func rows(_ spans: [(Double, Double)]) -> [Int] {
        var ends: [Double] = []
        var rows = Array(repeating: 0, count: spans.count)
        for (index, span) in spans.enumerated().sorted(by: { $0.element.0 < $1.element.0 }) {
            if let row = ends.firstIndex(where: { $0 <= span.0 }) {
                ends[row] = span.1
                rows[index] = row
            } else {
                rows[index] = ends.count
                ends.append(span.1)
            }
        }
        return rows
    }

    /// The file the hub names the log (`session-<id>-log.json`).
    static func logName(_ sessionID: String) -> String { "session-\(sessionID)-log.json" }

    static func icon(_ kind: TrajectoryStepKind) -> Lucide {
        switch kind {
        case .input: return .userRound
        case .turn: return .sparkles
        case .reasoning: return .brain
        case .tool: return .wrench
        case .subagent: return .bot
        }
    }
}

struct TrajectorySheet: View {
    let sessionID: String
    let profile: String
    let live: Bool
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var trajectory: Trajectory?
    @State private var error: String?
    @State private var lane: TrajectoryLane?
    @State private var query = ""
    /// A bar tapped on the timeline: its step is brought into view in the list.
    @State private var focused: String?
    @State private var saving = false
    @State private var downloadError: String?
    @State private var saved: SharedFile?

    var body: some View {
        NavigationStack {
          ScrollViewReader { reader in
            List {
                if let error { NoticeView(text: error, tone: .danger) }
                if let trajectory {
                    metrics(trajectory)
                    if trajectory.timing == ._none {
                        Text(l10n("trajectory_sheet.untimed")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    } else if trajectory.timing == .partial {
                        Text(l10n("trajectory_sheet.partial")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                    if trajectory.timing != ._none && trajectory.steps.contains(where: { TrajectoryRules.span($0, now: Date()) != nil }) {
                        Section(l10n("trajectory_sheet.timeline")) {
                            TrajectoryTimeline(steps: trajectory.steps) { step in
                                lane = nil
                                query = ""
                                focused = step.id
                            }
                            .listRowInsets(EdgeInsets(top: Space.s2, leading: Space.s2, bottom: Space.s2, trailing: Space.s2))
                        }
                    }
                    Section {
                        Button {
                            Task { await download() }
                        } label: {
                            LucideLabel(l10n("trajectory_sheet.download"), icon: .download, size: 16)
                        }
                        .disabled(saving)
                        .accessibilityIdentifier("trajectory.download")
                        if let downloadError { NoticeView(text: downloadError, tone: .danger) }
                    }
                    Section {
                        Picker(l10n("trajectory_sheet.filters"), selection: $lane) {
                            Text(l10n("trajectory_sheet.all")).tag(TrajectoryLane?.none)
                            ForEach([TrajectoryLane.input, .model, .tools, .subagents], id: \.self) { value in
                                Text(l10n("trajectory_sheet.lane.\(value.rawValue)")).tag(TrajectoryLane?.some(value))
                            }
                        }
                        .accessibilityIdentifier("trajectory.lane")
                        TextField(l10n("trajectory_sheet.search"), text: $query)
                            .accessibilityIdentifier("trajectory.search")
                    }
                    let steps = TrajectoryRules.shown(trajectory.steps, lane: lane, query: query)
                    Section {
                        if trajectory.steps.isEmpty {
                            EmptyStateView(icon: .activity, title: l10n("trajectory_sheet.empty_title"), message: l10n("trajectory_sheet.empty_body"))
                        } else if steps.isEmpty {
                            Text(l10n("trajectory_sheet.no_match")).foregroundStyle(Tone.textMuted)
                        }
                        ForEach(steps, id: \.id) { step in
                            TrajectoryStepRow(step: step, highlighted: focused == step.id).id(step.id)
                        }
                    }
                } else if error == nil {
                    SkeletonList(rows: 5).listRowBackground(Color.clear)
                }
            }
            .onChange(of: focused) { _, id in
                if let id { withAnimation { reader.scrollTo(id, anchor: .center) } }
            }
            .navigationTitle(l10n("trajectory_sheet.tab"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { dismiss() } }
            }
            .refreshable { await load() }
            .sheet(item: $saved) { file in ActivitySheet(items: [file.url]) }
            .task {
                await load()
                // While a run streams, the trajectory grows: read again now and then.
                while live && !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    if Task.isCancelled { return }
                    await load()
                }
            }
          }
        }
        .accessibilityIdentifier("chat.trajectory")
    }

    private func metrics(_ trajectory: Trajectory) -> some View {
        let m = trajectory.metrics
        return Section {
            FactRow(label: l10n("trajectory_sheet.metric.turns"), value: String(m.turns))
            FactRow(label: l10n("trajectory_sheet.metric.steps"), value: String(m.steps))
            if m.toolCalls > 0 {
                FactRow(label: l10n("trajectory_sheet.kind.tool"), value: m.failedToolCalls > 0 ? "\(m.toolCalls) · ✕ \(m.failedToolCalls)" : String(m.toolCalls))
            }
            if let text = TrajectoryRules.duration(m.modelMs, l10n) { FactRow(label: l10n("trajectory_sheet.metric.model_time"), value: text) }
            if let text = TrajectoryRules.duration(m.toolMs, l10n) { FactRow(label: l10n("trajectory_sheet.metric.tool_time"), value: text) }
            if let text = TrajectoryRules.duration(m.avgFirstTokenMs, l10n) { FactRow(label: l10n("trajectory_sheet.metric.first_token"), value: text) }
            if let rate = m.outputTokensPerSecond { FactRow(label: l10n("trajectory_sheet.metric.tokens_per_second"), value: String(format: "%.1f", rate)) }
            if let hit = m.cacheHitPct { FactRow(label: l10n("trajectory_sheet.metric.cache_hit"), value: String(format: "%.0f%%", hit)) }
            if let tokens = m.inputTokens { FactRow(label: l10n("trajectory_sheet.metric.input_tokens"), value: String(tokens)) }
            if let tokens = m.outputTokens { FactRow(label: l10n("trajectory_sheet.metric.output_tokens"), value: String(tokens)) }
        }
        .accessibilityIdentifier("trajectory.metrics")
    }

    /// The session log: the same document, as the hub sends it for download (`download=true`).
    private func download() async {
        saving = true
        defer { saving = false }
        let profile = profile, id = sessionID
        do {
            let data = try await app.api.call {
                try await SessionsAPI.sessionsGetTrajectoryWithRequestBuilder(xHubProfile: profile, sessionId: id, download: true, apiConfiguration: $0).execute().bodyData
            }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(TrajectoryRules.logName(id))
            try (data ?? Data()).write(to: url, options: .atomic)
            downloadError = nil
            saved = SharedFile(url: url)
        } catch {
            downloadError = HubFailure(error).describe(l10n)
        }
    }

    private func load() async {
        let profile = profile, id = sessionID
        do {
            trajectory = try await app.api.call { try await SessionsAPI.sessionsGetTrajectory(xHubProfile: profile, sessionId: id, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

private struct TrajectoryStepRow: View {
    let step: TrajectoryStep
    var highlighted = false
    @Environment(\.l10n) private var l10n
    @State private var open = false

    var body: some View {
        Button {
            open.toggle()
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: Space.s2) {
                    LucideIcon(TrajectoryRules.icon(step.kind), size: 14).foregroundStyle(Tone.textMuted)
                    Text(title).font(.system(size: FontSize.sizeSm, weight: .medium)).lineLimit(1).foregroundStyle(Tone.text)
                    Spacer()
                    if let time = TrajectoryRules.duration(step.durationMs, l10n) {
                        Text(time).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                    StatusDot(kind: step.status == .failed ? .bad : step.status == .running ? .good : .neutral,
                              label: l10n("trajectory_sheet.status.\(step.status.rawValue)"))
                }
                if open {
                    if let model = step.model { Text(l10n("trajectory_sheet.model", ["model": model])).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
                    if let first = TrajectoryRules.duration(step.firstTokenMs, l10n) {
                        Text(l10n("trajectory_sheet.first_token_here", ["time": first])).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                    if let text = step.text, !text.isEmpty {
                        Text(text).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.text).textSelection(.enabled).contentDirection(of: text)
                    } else {
                        Text(l10n(step.toolCallOnly ? "trajectory_sheet.tool_call_only" : "trajectory_sheet.no_details")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .listRowBackground(highlighted ? Tone.accentSoft : nil)
        .onChange(of: highlighted) { _, on in if on { open = true } }
        .accessibilityIdentifier("trajectory.step.\(step.id)")
    }

    private var title: String {
        if step.kind == .tool, let name = step.toolCall?.name { return name }
        if let text = step.text, !text.isEmpty, step.kind == .input { return String(text.prefix(60)) }
        return l10n("trajectory_sheet.kind.\(step.kind.rawValue)")
    }
}

/// The three lanes (four with subagents) on one time axis, idle stretches folded, time running in
/// the reading direction; parallel calls on their own sub-rows. A tap on a bar finds its step.
struct TrajectoryTimeline: View {
    let steps: [TrajectoryStep]
    let pick: (TrajectoryStep) -> Void
    @Environment(\.l10n) private var l10n
    private static let rowHeight: CGFloat = 12
    private static let rowGap: CGFloat = 3

    private struct Bar: Identifiable {
        let step: TrajectoryStep
        let from: Double
        let to: Double
        let row: Int
        var id: String { step.id }
    }

    var body: some View {
        let now = Date()
        let spans = steps.compactMap { TrajectoryRules.span($0, now: now) }
        let axis = TrajectoryRules.axis(spans)
        let lanes = [TrajectoryLane.input, .model, .tools, .subagents].filter { lane in steps.contains { $0.lane == lane && TrajectoryRules.span($0, now: now) != nil } }
        VStack(alignment: .leading, spacing: Space.s2) {
            ForEach(lanes, id: \.self) { lane in
                let timed = steps.filter { $0.lane == lane }.compactMap { step in TrajectoryRules.span(step, now: now).map { (step, $0) } }
                let rows = TrajectoryRules.rows(timed.map(\.1))
                let bars = timed.enumerated().map { index, item in
                    Bar(step: item.0, from: axis.at(item.1.0), to: axis.at(item.1.1), row: rows[index])
                }
                let height = CGFloat((rows.max() ?? 0) + 1) * (Self.rowHeight + Self.rowGap)
                VStack(alignment: .leading, spacing: 2) {
                    Text(l10n("trajectory_sheet.lane.\(lane.rawValue)")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    GeometryReader { geometry in
                        ZStack(alignment: .topLeading) {
                            ForEach(axis.folds, id: \.self) { fold in
                                Rectangle().fill(Tone.border).frame(width: 1, height: height)
                                    .padding(.leading, geometry.size.width * fold)
                            }
                            ForEach(bars) { bar in
                                Button { pick(bar.step) } label: {
                                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                                        .fill(colour(bar.step))
                                        .frame(width: max(3, geometry.size.width * (bar.to - bar.from)), height: Self.rowHeight)
                                }
                                .buttonStyle(.plain)
                                // Leading padding follows the reading direction: time runs as the text does.
                                .padding(.leading, geometry.size.width * bar.from)
                                .padding(.top, CGFloat(bar.row) * (Self.rowHeight + Self.rowGap))
                                .accessibilityLabel(l10n("trajectory_sheet.kind.\(bar.step.kind.rawValue)"))
                            }
                        }
                    }
                    .frame(height: height)
                }
                .accessibilityIdentifier("trajectory.lane.\(lane.rawValue)")
            }
        }
        .accessibilityIdentifier("trajectory.timeline")
    }

    private func colour(_ step: TrajectoryStep) -> Color {
        if step.status == .failed { return Tone.danger }
        switch step.lane {
        case .input: return Tone.textMuted
        case .model: return Tone.accent
        case .tools: return Tone.statusRunning
        case .subagents: return Tone.link
        }
    }
}

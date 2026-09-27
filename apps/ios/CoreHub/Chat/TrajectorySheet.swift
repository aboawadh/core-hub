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

    var body: some View {
        NavigationStack {
            List {
                if let error { NoticeView(text: error, tone: .danger) }
                if let trajectory {
                    metrics(trajectory)
                    if trajectory.timing == ._none {
                        Text(l10n("trajectory_sheet.untimed")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    } else if trajectory.timing == .partial {
                        Text(l10n("trajectory_sheet.partial")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
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
                            TrajectoryStepRow(step: step)
                        }
                    }
                } else if error == nil {
                    SkeletonList(rows: 5).listRowBackground(Color.clear)
                }
            }
            .navigationTitle(l10n("trajectory_sheet.tab"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { dismiss() } }
            }
            .refreshable { await load() }
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
        .accessibilityIdentifier("trajectory.step.\(step.id)")
    }

    private var title: String {
        if step.kind == .tool, let name = step.toolCall?.name { return name }
        if let text = step.text, !text.isEmpty, step.kind == .input { return String(text.prefix(60)) }
        return l10n("trajectory_sheet.kind.\(step.kind.rawValue)")
    }
}

// The chat's insight on the phone (apps batch 6): a small context ring in the top bar (and a count
// when subagents are running), the same five places in the chat's «…» menu, and their sheets — the
// context window with compress, and the chat's runs. Subagents, changed files and the chat's files
// are in ChatInsightWork.swift; the rules and calls in ChatInsight.swift. Nothing here is on screen
// until the person asks, except the ring (web: beside the mic) and the running subagents' count.
import CoreHubClient
import SwiftUI

/// The top bar's compact part: the running subagents (only while some run) and the context ring
/// (only when the window is known). Each opens its sheet.
struct ChatInsightBar: View {
    let insight: ChatInsightModel
    let use: ChatInsight.Use?
    @Environment(\.l10n) private var l10n

    var body: some View {
        HStack(spacing: Space.s2) {
            if insight.running > 0 {
                Button { insight.sheet = .subagents } label: {
                    HStack(spacing: 3) {
                        LucideIcon(.bot, size: 15)
                        Text(ChatInsight.number(insight.running, l10n.language))
                            .font(.system(size: FontSize.sizeXs, weight: .semibold))
                            .monospacedDigit()
                    }
                    .foregroundStyle(Tone.accentSoftText)
                    .padding(.horizontal, Space.s2)
                    .frame(height: 26)
                    .background(Tone.accentSoft, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(l10n("chat_insight.subagents_running", ["count": String(insight.running)]))
                .accessibilityIdentifier("chat.insight.subagents")
            }
            if let use {
                Button { insight.sheet = .context } label: {
                    ContextRing(use: use, size: 20)
                        .frame(width: 30, height: 30)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(ContextSheet.ringLabel(use, l10n))
                .accessibilityIdentifier("chat.insight.ring")
            }
        }
    }
}

/// The ring itself: the track, and the used part from the top, in its band's colour.
struct ContextRing: View {
    let use: ChatInsight.Use
    var size: CGFloat = 18

    var body: some View {
        ZStack {
            Circle().stroke(Tone.border, lineWidth: 2.5)
            Circle()
                .trim(from: 0, to: max(0.02, use.ratio))
                .stroke(ContextSheet.color(ChatInsight.band(use)), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: size, height: size)
        .environment(\.layoutDirection, .leftToRight)
    }
}

/// The insight's five places in the chat's «…» menu, in their own section.
struct ChatInsightMenu: View {
    let insight: ChatInsightModel
    @Environment(\.l10n) private var l10n

    var body: some View {
        Section {
            item(.context, "chat_insight.context", .gauge)
            item(.runs, "chat_insight.runs", .rotateCcwClock)
            item(.subagents, "chat_insight.subagents", .bot)
            item(.changes, "chat_insight.changes", .fileDiff)
            item(.files, "chat_insight.files", .folder)
        }
    }

    private func item(_ sheet: ChatInsightModel.Sheet, _ key: String, _ icon: Lucide) -> some View {
        Button { insight.sheet = sheet } label: {
            Label { Text(l10n(key)) } icon: { Image(lucide: icon) }
        }
        .accessibilityIdentifier("chat.insight.\(sheet.rawValue)")
    }
}

/// Which sheet is open, drawn.
struct ChatInsightSheet: View {
    let which: ChatInsightModel.Sheet
    let chat: ChatModel
    let insight: ChatInsightModel
    let use: ChatInsight.Use?
    let canCompress: Bool

    var body: some View {
        switch which {
        case .context:
            ContextSheet(chat: chat, use: use, canCompress: canCompress)
                .presentationDetents([.medium, .large])
        case .runs:
            RunsSheet(sessionID: chat.sessionID, profile: chat.profile, revision: ChatInsight.changesRevision(Array(chat.state.runs.values)))
        case .subagents:
            SubagentsSheet(insight: insight)
        case .changes:
            ChangesSheet(chat: chat)
        case .files:
            ChatFilesSheet(sessionID: chat.sessionID, profile: chat.profile, revision: ChatInsight.changesRevision(Array(chat.state.runs.values)))
        }
    }
}

// MARK: - Context

/// How full the window is, what fills it (read only while the sheet is open, §102), and Compress
/// with an optional focus — where the agent can compress, and only between replies.
struct ContextSheet: View {
    let chat: ChatModel
    let use: ChatInsight.Use?
    let canCompress: Bool
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var breakdown: SessionContextBreakdown?
    @State private var focus = ""

    static func color(_ band: ChatInsight.Band) -> Color {
        switch band {
        case .normal: return Tone.accent
        case .warning: return Tone.warningSoftText
        case .danger: return Tone.danger
        }
    }

    /// Distinct colours for the categories, in order.
    static let palette: [Color] = [
        Tone.accent, Tone.infoSoftText, Tone.successSoftText, Tone.warningSoftText,
        Tone.dangerSoftText, Tone.statusRunning, Tone.link, Tone.textMuted,
    ]

    static func ringLabel(_ use: ChatInsight.Use, _ l10n: L10n) -> String {
        l10n("chat_insight.ring", [
            "percent": String(ChatInsight.percent(use)),
            "used": ChatInsight.number(use.used, l10n.language),
            "window": ChatInsight.number(use.window, l10n.language),
        ])
    }

    var body: some View {
        NavigationStack {
            List {
                Section { figure }
                if let breakdown, breakdown.available, !breakdown.categories.isEmpty {
                    Section {
                        categories(breakdown)
                    } header: {
                        Text(l10n("chat_insight.breakdown_title"))
                    } footer: {
                        Text(l10n("chat_insight.breakdown_note"))
                    }
                }
                if canCompress { compressSection }
            }
            .navigationTitle(l10n("chat_insight.context_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { dismiss() } }
            }
        }
        .task(id: use?.used ?? -1) {
            breakdown = try? await ChatInsightCalls.breakdown(app, id: chat.sessionID, profile: chat.profile)
        }
        .accessibilityIdentifier("sheet.context")
    }

    @ViewBuilder
    private var figure: some View {
        if let use {
            VStack(alignment: .leading, spacing: Space.s2) {
                Text(l10n("chat_insight.percent", ["percent": String(ChatInsight.percent(use))]))
                    .font(.system(size: FontSize.size2xl, weight: .semibold))
                    .monospacedDigit()
                    .accessibilityIdentifier("context.percent")
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Tone.surface2)
                        Capsule().fill(Self.color(ChatInsight.band(use)))
                            .frame(width: max(4, geo.size.width * use.ratio))
                    }
                }
                .frame(height: 8)
                .environment(\.layoutDirection, .leftToRight)
                Text(l10n("chat_insight.used", [
                    "used": ChatInsight.number(use.used, l10n.language),
                    "window": ChatInsight.number(use.window, l10n.language),
                ]))
                .font(.system(size: FontSize.sizeSm))
                Text(l10n("chat_insight.source.\(use.source.rawValue)"))
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
                    .accessibilityIdentifier("context.source")
            }
            .padding(.vertical, Space.s1)
        } else {
            Text(l10n("chat_insight.context_unknown"))
                .font(.system(size: FontSize.sizeSm))
                .foregroundStyle(Tone.textMuted)
        }
    }

    @ViewBuilder
    private func categories(_ breakdown: SessionContextBreakdown) -> some View {
        let window = breakdown.windowTokens ?? use?.window ?? 0
        let shares = ChatInsight.shares(breakdown.categories, window: window)
        GeometryReader { geo in
            HStack(spacing: 1) {
                ForEach(Array(breakdown.categories.enumerated()), id: \.offset) { index, _ in
                    Rectangle()
                        .fill(Self.palette[index % Self.palette.count])
                        .frame(width: max(0, geo.size.width * shares[index] - 1))
                }
                Spacer(minLength: 0)
            }
            .background(Tone.surface2)
            .clipShape(Capsule())
        }
        .frame(height: 8)
        .environment(\.layoutDirection, .leftToRight)
        ForEach(Array(breakdown.categories.enumerated()), id: \.offset) { index, category in
            HStack(spacing: Space.s2) {
                Circle().fill(Self.palette[index % Self.palette.count]).frame(width: 8, height: 8)
                Text(ChatInsight.knownCategories.contains(category.id) ? l10n("chat_insight.category.\(category.id)") : category.label)
                    .font(.system(size: FontSize.sizeSm))
                Spacer(minLength: Space.s2)
                Text(ChatInsight.number(category.tokens, l10n.language))
                    .font(.system(size: FontSize.sizeSm))
                    .foregroundStyle(Tone.textMuted)
                    .monospacedDigit()
            }
            .accessibilityElement(children: .combine)
        }
    }

    private var compressSection: some View {
        Section {
            TextField(l10n("chat_insight.focus_placeholder"), text: $focus, axis: .vertical)
                .lineLimit(2...5)
                .accessibilityIdentifier("context.focus")
            Button {
                Task { await chat.compress(focus: focus) }
            } label: {
                HStack(spacing: Space.s2) {
                    if chat.compressing { ProgressView() } else { LucideIcon(.shrink, size: 16) }
                    Text(l10n(chat.compressing ? "chat_insight.compressing" : "chat_insight.compress"))
                }
            }
            .disabled(chat.state.isBusy || chat.compressing)
            .accessibilityIdentifier("context.compress")
            if chat.state.isBusy {
                Text(l10n("chat_insight.compress_wait"))
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
            }
            if let notice = chat.notice, !chat.compressing {
                Text(notice).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
            }
            if let error = chat.actionError {
                Text(error).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.danger)
            }
        } header: {
            Text(l10n("chat_insight.compress_title"))
        } footer: {
            Text(l10n("chat_insight.focus_hint"))
        }
    }
}

// MARK: - Runs

/// The chat's runs, newest first: status, model, when, how long, tokens and cost.
struct RunsSheet: View {
    let sessionID: String
    let profile: String
    /// Changes when a run of the chat ends: the list is read again.
    let revision: String
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var list: PagedList<RunItem>?

    struct RunItem: Identifiable {
        let run: Run
        var id: String { run.id }
    }

    var body: some View {
        NavigationStack {
            Group {
                if let list {
                    ListScaffold(list, key: revision, emptyIcon: .rotateCcwClock, emptyTitle: l10n("chat_insight.runs_empty"), tag: "runs") { item in
                        RunRow(run: item.run)
                    }
                }
            }
            .navigationTitle(l10n("chat_insight.runs"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { dismiss() } }
            }
        }
        .onAppear {
            if list == nil {
                let app = app, id = sessionID, profile = profile
                list = PagedList { cursor in
                    let page = try await ChatInsightCalls.runs(app, id: id, profile: profile, cursor: cursor)
                    return ListPage(items: ChatInsight.history(page.items).map(RunItem.init), next: page.nextCursor)
                }
            }
        }
        .accessibilityIdentifier("sheet.runs")
    }
}

struct RunRow: View {
    let run: Run
    @Environment(\.l10n) private var l10n

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            VStack(alignment: .leading, spacing: Space.s1) {
                HStack(spacing: Space.s2) {
                    StatusPill(text: l10n("chat_insight.run_status.\(run.status.rawValue)"), kind: kind)
                    if let model = run.model {
                        Text(model.split(separator: "/").last.map(String.init) ?? model)
                            .font(.system(size: FontSize.sizeSm, weight: .medium))
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer(minLength: Space.s1)
                    Text((run.startedAt ?? run.createdAt).shortText(l10n.language))
                        .font(.system(size: FontSize.sizeXs))
                        .foregroundStyle(Tone.textMuted)
                }
                Text(details(now: context.date))
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
                    .monospacedDigit()
                if let error = run.error?.error, run.status == .failed {
                    Text(error)
                        .font(.system(size: FontSize.sizeXs))
                        .foregroundStyle(Tone.danger)
                        .lineLimit(3)
                        .contentDirection(of: error)
                }
            }
            .padding(.vertical, 2)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("runs.row")
    }

    private var kind: StatusPill.Kind {
        switch ChatInsight.tone(run.status) {
        case .running: return .warn
        case .good: return .good
        case .bad: return .bad
        case .quiet: return .neutral
        }
    }

    private func details(now: Date) -> String {
        var parts: [String] = []
        let live = ChatInsight.tone(run.status) == .running
        if let ms = ChatInsight.elapsedMs(started: run.startedAt, finished: live ? nil : (run.finishedAt ?? run.updatedAt), now: now) {
            parts.append(ChatInsight.clock(ms))
        }
        if let usage = run.usage {
            parts.append(l10n("chat_insight.tokens", [
                "input": ChatInsight.number(usage.inputTokens, l10n.language),
                "output": ChatInsight.number(usage.outputTokens, l10n.language),
            ]))
            if let cost = ChatInsight.cost(usage.cost) { parts.append(cost) }
        }
        if run.interrupted { parts.append(l10n("chat_insight.run_interrupted")) }
        return parts.isEmpty ? "—" : parts.joined(separator: " · ")
    }
}

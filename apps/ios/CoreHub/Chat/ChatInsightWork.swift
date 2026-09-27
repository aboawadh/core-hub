// The chat's work on the phone (apps batch 6): the subagents its agent delegated to (stop, steer,
// read the end of their transcript, §56), the files each run changed with a unified diff (live while
// a run goes, §49/§102), and the conversation's own files (§48). Files open through the same opener
// as a message's files (Attachments.swift, #179). Rules and calls: ChatInsight.swift.
import CoreHubClient
import SwiftUI

// MARK: - Subagents

struct SubagentsSheet: View {
    let insight: ChatInsightModel
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var steering: Subagent?
    @State private var note = ""
    @State private var line: (text: String, failed: Bool)?
    @State private var showFinished = false

    var body: some View {
        let split = ChatInsight.split(insight.subagents)
        let full = insight.support == .full
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                List {
                    if let line {
                        Text(line.text)
                            .font(.system(size: FontSize.sizeSm))
                            .foregroundStyle(line.failed ? Tone.danger : Tone.textMuted)
                    }
                    if !insight.subagentsLoaded {
                        ProgressView().frame(maxWidth: .infinity)
                    } else if insight.subagents.isEmpty {
                        EmptyStateView(
                            icon: .bot, title: l10n("chat_insight.subagents_empty"),
                            message: l10n(insight.support == ._none ? "chat_insight.subagents_none" : "chat_insight.subagents_empty_hint")
                        )
                        .listRowBackground(Color.clear)
                        .accessibilityIdentifier("subagents.empty")
                    }
                    if let error = insight.subagentsError {
                        NoticeView(text: error, tone: .danger)
                    }
                    if !split.running.isEmpty {
                        Section(l10n("chat_insight.subagents_running_title")) {
                            ForEach(split.running, id: \.subagent.id) { row in
                                SubagentRowView(
                                    subagent: row.subagent, indent: row.indent, now: context.date, full: full,
                                    stop: {
                                        Task { if let failed = await insight.interrupt(row.subagent.id) { line = (failed, true) } }
                                    },
                                    steer: {
                                        note = ""
                                        steering = row.subagent
                                    }
                                )
                            }
                        }
                    }
                    if !split.finished.isEmpty {
                        Section {
                            DisclosureGroup(isExpanded: $showFinished) {
                                ForEach(split.finished, id: \.id) { subagent in
                                    SubagentRowView(subagent: subagent, indent: 0, now: context.date, full: full, stop: nil, steer: nil)
                                }
                            } label: {
                                Text(l10n("chat_insight.subagents_finished", ["count": String(split.finished.count)]))
                                    .accessibilityIdentifier("subagents.finished")
                            }
                        }
                    }
                }
            }
            .refreshable { await insight.refreshSubagents() }
            .navigationTitle(l10n("chat_insight.subagents"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { dismiss() } }
            }
            .navigationDestination(for: Subagent.self) { subagent in
                SubagentOutputView(insight: insight, subagentID: subagent.id)
            }
        }
        .task { await insight.refreshSubagents() }
        .alert(l10n("chat_insight.steer_title"), isPresented: Binding(get: { steering != nil }, set: { if !$0 { steering = nil } })) {
            TextField(l10n("chat_insight.steer_placeholder"), text: $note)
            Button(l10n("common.cancel"), role: .cancel) { steering = nil }
            Button(l10n("chat_insight.steer_send")) {
                guard let target = steering, let text = ChatInsight.steerText(note) else { return }
                steering = nil
                Task { line = await insight.steer(target.id, text: text) }
            }
        } message: {
            Text(steering?.goal ?? "")
        }
        .accessibilityIdentifier("sheet.subagents")
    }
}

/// One subagent: its goal, model, clock, tools and status; Stop, Steer and its output where the
/// agent allows them; a finished one's last words.
struct SubagentRowView: View {
    let subagent: Subagent
    let indent: Int
    let now: Date
    let full: Bool
    let stop: (() -> Void)?
    let steer: (() -> Void)?
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            HStack(alignment: .firstTextBaseline, spacing: Space.s2) {
                StatusPill(text: l10n("chat_insight.subagent_status.\(subagent.status.rawValue)"), kind: kind)
                Text(subagent.goal)
                    .font(.system(size: FontSize.sizeSm, weight: .medium))
                    .lineLimit(3)
                    .contentDirection(of: subagent.goal)
            }
            Text(details)
                .font(.system(size: FontSize.sizeXs))
                .foregroundStyle(Tone.textMuted)
                .monospacedDigit()
                .lineLimit(1)
            if let summary = subagent.summary, subagent.status != .running {
                Text(summary)
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(subagent.status == .failed ? Tone.danger : Tone.textMuted)
                    .lineLimit(4)
                    .contentDirection(of: summary)
            }
            if full {
                HStack(spacing: Space.s3) {
                    if let stop, subagent.status == .running {
                        Button(action: stop) { LucideLabel(l10n("chat_insight.subagent_stop"), icon: .circleStop) }
                            .accessibilityIdentifier("subagents.stop")
                    }
                    if let steer, subagent.status == .running, subagent.acceptingSteer {
                        Button(action: steer) { LucideLabel(l10n("chat_insight.subagent_steer"), icon: .cornerDownRight) }
                            .accessibilityIdentifier("subagents.steer")
                    }
                    NavigationLink(value: subagent) { LucideLabel(l10n("chat_insight.subagent_output"), icon: .eye) }
                        .accessibilityIdentifier("subagents.output")
                }
                .buttonStyle(.borderless)
                .font(.system(size: FontSize.sizeSm))
            }
        }
        .padding(.leading, CGFloat(indent) * Space.s4)
        .padding(.vertical, 2)
        .accessibilityIdentifier("subagents.row")
    }

    private var kind: StatusPill.Kind {
        switch subagent.status {
        case .running: return .warn
        case .completed: return .good
        case .failed: return .bad
        case .interrupted: return .neutral
        }
    }

    private var details: String {
        var parts: [String] = []
        if let ms = ChatInsight.elapsedMs(started: subagent.startedAt, finished: subagent.finishedAt, now: now) {
            parts.append(ChatInsight.clock(ms))
        }
        if let model = subagent.model { parts.append(model) }
        if let count = subagent.toolCount { parts.append(l10n("chat_insight.subagent_tools", ["count": String(count)])) }
        if let tool = subagent.lastTool, subagent.status == .running { parts.append(tool) }
        return parts.joined(separator: " · ")
    }
}

/// The end of a subagent's own transcript (up to 16 KB), read again every 2 seconds while it runs.
struct SubagentOutputView: View {
    let insight: ChatInsightModel
    let subagentID: String
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var tail: SubagentTail?
    @State private var error: String?

    private var subagent: Subagent? { insight.subagents.first { $0.id == subagentID } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.s3) {
                if let subagent {
                    Text(subagent.goal)
                        .font(.system(size: FontSize.sizeSm, weight: .medium))
                        .contentDirection(of: subagent.goal)
                }
                if let error { NoticeView(text: error, tone: .danger) }
                if let tail {
                    if tail.available {
                        if tail.truncated {
                            Text(l10n("chat_insight.output_truncated")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                        Text(tail.text)
                            .font(.system(size: FontSize.sizeXs, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(Space.s3)
                            .background(Tone.codeBg, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                            .foregroundStyle(Tone.codeText)
                            .accessibilityIdentifier("subagent.output")
                    } else {
                        Text(l10n("chat_insight.output_none")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                    }
                } else if error == nil {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding(Space.s4)
        }
        .background(Tone.bg)
        .navigationTitle(l10n("chat_insight.subagent_output"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            while !Task.isCancelled {
                do {
                    tail = try await ChatInsightCalls.tail(app, id: insight.sessionID, profile: insight.profile, subagent: subagentID)
                    error = nil
                } catch {
                    self.error = HubFailure(error).describe(l10n)
                }
                guard subagent?.status == .running else { return }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }
}

// MARK: - Changed files

/// The files each run changed, newest run first; the running one's so far, read again every few
/// seconds. A file opens its diff; the diff opens the file.
struct ChangesSheet: View {
    let chat: ChatModel
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var list: PagedList<RunChangesItem>?
    @State private var live: RunChanges?

    struct RunChangesItem: Identifiable {
        let changes: RunChanges
        var id: String { changes.runId }
    }

    /// While the sheet is open and a run goes, its folder is looked at again this often (§102).
    static let livePoll: UInt64 = 4_000_000_000

    private var revision: String { ChatInsight.changesRevision(Array(chat.state.runs.values)) }

    var body: some View {
        NavigationStack {
            List {
                if let run = chat.state.activeRun {
                    Section {
                        if let live, !live.files.isEmpty {
                            ForEach(live.files, id: \.path) { file in
                                NavigationLink(value: DiffTarget(runID: run.id, file: file, live: true)) { ChangeRow(file: file) }
                            }
                        } else {
                            Text(l10n("chat_insight.changes_live_none")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                        }
                    } header: {
                        header(l10n("chat_insight.changes_live"), live.flatMap { ChatInsight.counts(additions: $0.additions, deletions: $0.deletions) })
                    }
                }
                if let list {
                    if list.loading {
                        ProgressView().frame(maxWidth: .infinity)
                    } else if list.items.isEmpty, chat.state.activeRun == nil {
                        if let failure = list.failure {
                            NoticeView(text: failure.describe(l10n), tone: .danger)
                        } else {
                            EmptyStateView(icon: .fileDiff, title: l10n("chat_insight.changes_empty"), message: l10n("chat_insight.changes_empty_hint"))
                                .listRowBackground(Color.clear)
                                .accessibilityIdentifier("changes.empty")
                        }
                    }
                    ForEach(list.items) { item in
                        Section {
                            ForEach(item.changes.files, id: \.path) { file in
                                NavigationLink(value: DiffTarget(runID: item.changes.runId, file: file, live: false)) { ChangeRow(file: file) }
                            }
                            if item.changes.truncated || !item.changes.complete {
                                Text(l10n(item.changes.truncated ? "chat_insight.changes_truncated" : "chat_insight.changes_incomplete"))
                                    .font(.system(size: FontSize.sizeXs))
                                    .foregroundStyle(Tone.textMuted)
                            }
                        } header: {
                            header(
                                item.changes.recordedAt.shortText(l10n.language),
                                l10n("chat_insight.changes_summary", [
                                    "files": String(item.changes.filesChanged),
                                    "counts": ChatInsight.counts(additions: item.changes.additions, deletions: item.changes.deletions) ?? "",
                                ])
                            )
                        }
                    }
                    if list.hasMore {
                        Button(l10n("kit.load_more")) { Task { await list.loadMore() } }
                            .frame(maxWidth: .infinity)
                            .onAppear { Task { await list.loadMore() } }
                    }
                }
            }
            .refreshable { await list?.refresh() }
            .navigationTitle(l10n("chat_insight.changes"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { dismiss() } }
            }
            .navigationDestination(for: DiffTarget.self) { target in
                DiffPage(sessionID: chat.sessionID, profile: chat.profile, target: target)
            }
        }
        // Read when the sheet opens, and again when a run of the chat ends.
        .task(id: revision) {
            if list == nil {
                let app = app, id = chat.sessionID, profile = chat.profile
                list = PagedList { cursor in
                    let page = try await ChatInsightCalls.changes(app, id: id, profile: profile, cursor: cursor)
                    return ListPage(items: page.items.map(RunChangesItem.init), next: page.nextCursor)
                }
            }
            await list?.refresh()
        }
        .task(id: chat.state.activeRun?.id) {
            live = nil
            guard let run = chat.state.activeRun?.id else { return }
            while !Task.isCancelled {
                live = try? await ChatInsightCalls.liveChanges(app, id: chat.sessionID, profile: chat.profile, run: run)
                try? await Task.sleep(nanoseconds: Self.livePoll)
            }
        }
        .accessibilityIdentifier("sheet.changes")
    }

    private func header(_ title: String, _ detail: String?) -> some View {
        HStack {
            Text(title)
            Spacer(minLength: Space.s2)
            if let detail { Text(detail).monospacedDigit() }
        }
    }
}

/// A file one run changed, and whether it is the live (not yet recorded) picture.
struct DiffTarget: Hashable {
    let runID: String
    let file: RunFileChange
    let live: Bool
}

/// One changed file: what happened to it, its path (and where a renamed one was), its line counts.
struct ChangeRow: View {
    let file: RunFileChange
    @Environment(\.l10n) private var l10n

    var body: some View {
        HStack(spacing: Space.s2) {
            LucideIcon(icon, size: 16).foregroundStyle(color)
            VStack(alignment: .leading, spacing: 2) {
                Text(file.path)
                    .font(.system(size: FontSize.sizeSm, design: .monospaced))
                    .lineLimit(2)
                    .truncationMode(.head)
                    .environment(\.layoutDirection, .leftToRight)
                Text(detail)
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
                    .lineLimit(1)
            }
            Spacer(minLength: Space.s1)
            if let additions = file.additions, let deletions = file.deletions {
                HStack(spacing: 4) {
                    Text("+\(additions)").foregroundStyle(Tone.successSoftText)
                    Text("−\(deletions)").foregroundStyle(Tone.dangerSoftText)
                }
                .font(.system(size: FontSize.sizeXs, design: .monospaced))
                .environment(\.layoutDirection, .leftToRight)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("changes.file")
    }

    private var detail: String {
        let kind = l10n("chat_insight.change.\(file.change.rawValue)")
        if let old = file.oldPath { return "\(kind) · \(l10n("chat_insight.renamed_from", ["path": old]))" }
        return file.binary ? "\(kind) · \(l10n("chat_insight.binary"))" : kind
    }

    private var icon: Lucide {
        switch file.change {
        case .added: return .plus
        case .modified: return .pencil
        case .deleted: return .trash
        case .renamed: return .fileText
        }
    }

    private var color: Color {
        switch file.change {
        case .added: return Tone.successSoftText
        case .deleted: return Tone.danger
        default: return Tone.textMuted
        }
    }
}

/// One file's diff as the hub recorded it: unified, monospaced, with its line numbers, scrolled
/// sideways for long lines. «Open» shows the file as it is now, in the viewer.
struct DiffPage: View {
    let sessionID: String
    let profile: String
    let target: DiffTarget
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var diff: RunFileDiff?
    @State private var error: String?
    @State private var opener = FileOpener()
    @State private var opening = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.s3) {
                ChangeRow(file: target.file)
                if let key = ChatInsight.noDiffKey(target.file.diff, live: target.live) {
                    NoticeView(text: l10n(key), tone: .info)
                } else if let error {
                    NoticeView(text: error, tone: .danger)
                } else if let diff {
                    if diff.truncated { NoticeView(text: l10n("chat_insight.diff_truncated"), tone: .warning) }
                    if let key = ChatInsight.noDiffKey(diff.diff, live: false) {
                        NoticeView(text: l10n(key), tone: .info)
                    } else {
                        DiffBody(hunks: ChatInsight.parseDiff(diff.text ?? ""))
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
                if let notice = opener.notice { NoticeView(text: notice, tone: .danger) }
            }
            .padding(Space.s4)
        }
        .background(Tone.bg)
        .navigationTitle(ChatInsight.fileName(target.file.path))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if ChatInsight.canOpen(target.file.change) {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { open() } label: {
                        if opening { ProgressView() } else { LucideLabel(l10n("chat_insight.open_file"), icon: .externalLink) }
                    }
                    .disabled(opening)
                    .accessibilityIdentifier("diff.open")
                }
            }
        }
        .fileOpener(opener)
        .task {
            guard ChatInsight.noDiffKey(target.file.diff, live: target.live) == nil else { return }
            do {
                diff = try await ChatInsightCalls.diff(app, id: sessionID, profile: profile, run: target.runID, path: target.file.path)
            } catch {
                self.error = HubFailure(error).describe(l10n)
            }
        }
        .accessibilityIdentifier("screen.diff")
    }

    /// The file as the folder has it now: its entry in the chat's files (size and time, so a changed
    /// file is fetched again), else by its path alone.
    private func open() {
        opening = true
        Task {
            let list = try? await ChatInsightCalls.files(app, id: sessionID, profile: profile)
            let entry = list?.items.first { $0.path == target.file.path }
            let file = entry.map { HubFile.of($0, sessionID: sessionID) }
                ?? .working(sessionID: sessionID, path: target.file.path, name: ChatInsight.fileName(target.file.path), mime: nil, size: nil, modified: nil)
            opener.open(file, profile: profile, app: app)
            opening = false
        }
    }
}

/// The hunks, each line with its old and new number, in one column that scrolls sideways.
struct DiffBody: View {
    let hunks: [ChatInsight.DiffHunk]

    var body: some View {
        ScrollView(.horizontal) {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(hunks.enumerated()), id: \.offset) { _, hunk in
                    Text(hunk.header)
                        .foregroundStyle(Tone.infoSoftText)
                        .padding(.horizontal, Space.s2)
                        .padding(.vertical, 2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Tone.infoSoft)
                    ForEach(Array(hunk.lines.enumerated()), id: \.offset) { _, line in
                        HStack(spacing: 0) {
                            Text(line.old.map(String.init) ?? "").frame(width: 38, alignment: .trailing).foregroundStyle(Tone.textFaint)
                            Text(line.new.map(String.init) ?? "").frame(width: 38, alignment: .trailing).foregroundStyle(Tone.textFaint)
                            Text(sign(line.kind)).frame(width: 18).foregroundStyle(Tone.textMuted)
                            Text(line.text.isEmpty ? " " : line.text)
                                .foregroundStyle(line.kind == .note ? Tone.textMuted : Tone.codeText)
                                .fixedSize(horizontal: true, vertical: false)
                        }
                        .padding(.trailing, Space.s3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(background(line.kind))
                    }
                }
            }
            .font(.system(size: FontSize.sizeXs, design: .monospaced))
            .textSelection(.enabled)
        }
        .background(Tone.codeBg, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        // A diff reads left to right in every language, like code.
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityIdentifier("diff.body")
    }

    private func sign(_ kind: ChatInsight.DiffKind) -> String {
        switch kind {
        case .add: return "+"
        case .del: return "−"
        case .context, .note: return ""
        }
    }

    private func background(_ kind: ChatInsight.DiffKind) -> Color {
        switch kind {
        case .add: return Tone.successSoft
        case .del: return Tone.dangerSoft
        case .context, .note: return .clear
        }
    }
}

// MARK: - The chat's files

/// What the agent wrote or edited, what is in the chat's folder, and what was attached (§48); each
/// opens as a message's file does.
struct ChatFilesSheet: View {
    let sessionID: String
    let profile: String
    /// Changes when a run ends: the list is read again.
    let revision: String
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var opener = FileOpener()

    var body: some View {
        NavigationStack {
            AsyncContent(key: revision) {
                try await ChatInsightCalls.files(app, id: sessionID, profile: profile)
            } content: { list, reload in
                List {
                    if list.items.isEmpty {
                        EmptyStateView(icon: .folder, title: l10n("chat_insight.files_empty"), message: l10n("chat_insight.files_empty_hint"))
                            .listRowBackground(Color.clear)
                            .accessibilityIdentifier("files.empty")
                    }
                    ForEach(list.items, id: \.key) { item in
                        OpenableFileRow(file: HubFile.of(item, sessionID: sessionID), profile: profile, opener: opener)
                            .listRowSeparator(.hidden)
                    }
                    if list.truncated {
                        Text(l10n("chat_insight.files_truncated")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                    if let dir = list.workingDir {
                        Text(l10n("chat_insight.files_folder", ["path": dir]))
                            .font(.system(size: FontSize.sizeXs))
                            .foregroundStyle(Tone.textMuted)
                            .textSelection(.enabled)
                    }
                    if let notice = opener.notice { NoticeView(text: notice, tone: .danger) }
                }
                .refreshable { reload() }
            }
            .navigationTitle(l10n("chat_insight.files"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { dismiss() } }
            }
        }
        .fileOpener(opener)
        .accessibilityIdentifier("sheet.files")
    }
}

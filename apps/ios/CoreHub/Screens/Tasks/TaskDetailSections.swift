// The parts Tasks II (batch 5) adds to a task's sheet (TaskDetailView.swift): its checklist
// (subtasks: tick, add, drag into another order, swipe to delete), its definition of done and
// constraints (§104, ticked by the reviewer while it is in review), what was said on it (comments,
// and saying something), and — for a card on Hermes's board — Hermes's own history of it (§103).
// Each is a List section; the calls are the sheet's. The rules are TaskListsRules.swift; Android's
// twin is TaskDetailParts.kt.
import CoreHubClient
import SwiftUI

/// The task's checklist. A long press lifts a line to drag it; a swipe deletes it.
struct SubtasksSection: View {
    let lines: [Subtask]
    let editable: Bool
    let tick: (Subtask) -> Void
    let add: (String) async -> Bool
    let delete: (Subtask) -> Void
    let reorder: (_ order: [String]) -> Void
    @Environment(\.l10n) private var l10n
    @State private var draft = ""
    @State private var adding = false

    var body: some View {
        Section {
            ForEach(lines, id: \.id) { line in
                HStack(spacing: Space.s3) {
                    Button { tick(line) } label: {
                        LucideIcon(line.status == .done ? .circleCheck : .circle, size: 20)
                            .foregroundStyle(line.status == .done ? Tone.accent : Tone.textMuted)
                    }
                    .buttonStyle(.plain)
                    .disabled(!editable)
                    .accessibilityLabel(l10n(line.status == .done ? "tasks.list.untick" : "tasks.list.tick", ["title": line.title]))
                    .accessibilityIdentifier("task.subtask.tick.\(line.index)")
                    Text(line.title)
                        .font(.system(size: FontSize.sizeMd))
                        .strikethrough(line.status == .done, color: Tone.textFaint)
                        .foregroundStyle(line.status == .done ? Tone.textMuted : Tone.text)
                        .contentDirection(of: line.title)
                    Spacer(minLength: 0)
                }
                .accessibilityIdentifier("task.subtask.\(line.index)")
            }
            .onMove(perform: moveAction)
            .onDelete(perform: deleteAction)
            if editable {
                HStack {
                    TextField(l10n("tasks.list.add_placeholder"), text: $draft)
                        .submitLabel(.done)
                        .onSubmit { Task { await send() } }
                        .accessibilityIdentifier("task.subtask.input")
                    Button { Task { await send() } } label: { LucideIcon(.plus, size: 18) }
                        .disabled(SubtaskRules.title(draft) == nil || adding)
                        .accessibilityLabel(l10n("tasks.list.add"))
                        .accessibilityIdentifier("task.subtask.add")
                }
            }
        } header: {
            Text(lines.isEmpty ? l10n("tasks.list.title") : l10n("tasks.list.title_count", ["done": String(SubtaskRules.doneCount(lines)), "total": String(lines.count)]))
        } footer: {
            if editable && lines.count > 1 { Text(l10n("tasks.list.hint")) }
        }
        .accessibilityIdentifier("task.subtasks")
    }

    private var moveAction: ((IndexSet, Int) -> Void)? {
        guard editable else { return nil }
        return { from, to in reorder(SubtaskRules.order(lines.map(\.id), from: from, to: to)) }
    }

    private var deleteAction: ((IndexSet) -> Void)? {
        guard editable else { return nil }
        return { offsets in offsets.map { lines[$0] }.forEach(delete) }
    }

    private func send() async {
        guard let title = SubtaskRules.title(draft), !adding else { return }
        adding = true
        defer { adding = false }
        if await add(title) { draft = "" }
    }
}

/// A definition of done or the constraints: lines written here, ticked here by the reviewer.
struct CheckLinesSection: View {
    let kind: CheckLines.Kind
    let items: [TaskCheckItem]
    let status: TaskStatus
    let save: ([TaskCheckItem]) -> Void
    @Environment(\.l10n) private var l10n
    @State private var draft = ""

    var body: some View {
        let reviewing = CheckLines.canTick(status)
        Section {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                HStack(spacing: Space.s3) {
                    Button { save(CheckLines.ticking(items, at: index, !item.checked)) } label: {
                        LucideIcon(item.checked ? .circleCheck : .circle, size: 20)
                            .foregroundStyle(item.checked ? Tone.accent : Tone.textFaint)
                    }
                    .buttonStyle(.plain)
                    .disabled(!reviewing)
                    .accessibilityLabel(l10n("tasks.list.tick", ["title": item.text]))
                    .accessibilityIdentifier("task.\(kind.rawValue).tick.\(index)")
                    Text(item.text).font(.system(size: FontSize.sizeSm)).contentDirection(of: item.text)
                }
            }
            .onDelete { offsets in save(CheckLines.removing(items, at: offsets)) }
            if items.count < CheckLines.linesMax {
                HStack {
                    TextField(l10n("tasks.\(kind.rawValue).placeholder"), text: $draft)
                        .submitLabel(.done)
                        .onSubmit(add)
                        .accessibilityIdentifier("task.\(kind.rawValue).input")
                    Button(action: add) { LucideIcon(.plus, size: 18) }
                        .disabled(CheckLines.adding(items, draft) == nil)
                        .accessibilityLabel(l10n("tasks.\(kind.rawValue).add"))
                        .accessibilityIdentifier("task.\(kind.rawValue).add")
                }
            }
        } header: {
            Text(items.isEmpty ? l10n("tasks.\(kind.rawValue).title")
                : "\(l10n("tasks.\(kind.rawValue).title")) · \(l10n("tasks.dod.ticked", ["done": String(items.filter(\.checked).count), "total": String(items.count)]))")
        } footer: {
            Text(l10n(reviewing ? "tasks.dod.review_hint" : "tasks.\(kind.rawValue).hint"))
        }
        .accessibilityIdentifier("task.\(kind.rawValue)")
    }

    private func add() {
        guard let next = CheckLines.adding(items, draft) else { return }
        draft = ""
        save(next)
    }
}

/// What was said on the task, oldest first, and a field to say something.
struct CommentsSection: View {
    let comments: [Comment]
    let send: (String) async -> Bool
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var draft = ""
    @State private var sending = false

    var body: some View {
        Section(l10n("tasks.comments.title")) {
            if comments.isEmpty {
                Text(l10n("tasks.comments.none")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textFaint)
            }
            ForEach(comments, id: \.id) { comment in
                VStack(alignment: .leading, spacing: Space.s1) {
                    HStack {
                        Text(comment.author.name).font(.system(size: FontSize.sizeXs, weight: .semibold)).foregroundStyle(Tone.textMuted)
                        Spacer()
                        Text(comment.createdAt.shortText(app.language)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint)
                    }
                    MarkdownView(text: comment.content, foreground: Tone.text)
                }
                .accessibilityIdentifier("task.comment")
            }
            VStack(alignment: .trailing, spacing: Space.s2) {
                TextField(l10n("tasks.comments.placeholder"), text: $draft, axis: .vertical)
                    .lineLimit(1 ... 6)
                    .accessibilityIdentifier("task.comment.input")
                Button {
                    Task {
                        sending = true
                        if await send(draft.trimmingCharacters(in: .whitespacesAndNewlines)) { draft = "" }
                        sending = false
                    }
                } label: {
                    if sending { ProgressView() } else { LucideLabel(l10n("tasks.comments.send"), icon: .arrowUp, size: 16) }
                }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || sending)
                .accessibilityIdentifier("task.comment.send")
            }
        }
        .accessibilityIdentifier("task.comments")
    }
}

/// A Hermes card's own history, read from Hermes as the card opened (§103): its runs, then what
/// happened on it, newest first.
struct HermesHistorySection: View {
    let history: HermesCardHistory
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        if !history.runs.isEmpty {
            Section(l10n("tasks.hermes.runs")) {
                ForEach(history.runs, id: \.id) { run in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            StatusPill(text: run.outcome ?? run.status, kind: run.error != nil ? .bad : .neutral)
                            if let profile = run.profile { Text(profile).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
                            Spacer()
                            Text(run.startedAt.shortText(app.language)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint)
                        }
                        if let words = run.error ?? run.summary, !words.isEmpty {
                            Text(words).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted).contentDirection(of: words).lineLimit(4)
                        }
                    }
                }
            }
            .accessibilityIdentifier("task.hermes.runs")
        }
        Section(l10n("tasks.hermes.events")) {
            if history.events.isEmpty {
                Text(l10n("tasks.hermes.no_events")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textFaint)
            }
            ForEach(history.events, id: \.id) { event in
                HStack {
                    Text(event.kind).font(.system(size: FontSize.sizeSm, design: .monospaced))
                    Spacer()
                    Text(event.createdAt.shortText(app.language)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint)
                }
            }
        }
        .accessibilityIdentifier("task.hermes.events")
    }
}

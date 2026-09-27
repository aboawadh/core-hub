// The Tasks board on the phone (B15), the web's shape (packages/web/src/tasks/board.ts): the hub's
// nine statuses regrouped as an intake strip plus four columns — Queue, Waiting, Review, Done —
// side by side, swiped between one column at a time. A card is lifted with a long press and
// dropped into another column (a move a person may make, asked when it can mean two things, a
// reason asked for a block) or elsewhere in its own (a reorder). A card says what it waits on and
// when its run went quiet (contract decision §93).
import CoreHubClient
import SwiftUI

enum BoardLogic {
    enum ColumnID: String, CaseIterable, Identifiable {
        case intake, queue, waiting, review, done
        var id: String { rawValue }
    }

    struct Column: Identifiable, Equatable {
        let id: ColumnID
        let statuses: [TaskStatus]
        let collapsible: Bool
    }

    static let intake = Column(id: .intake, statuses: [.triage], collapsible: true)
    static let columns: [Column] = [
        Column(id: .queue, statuses: [.todo, .ready, .running], collapsible: false),
        Column(id: .waiting, statuses: [.scheduled, .blocked], collapsible: true),
        Column(id: .review, statuses: [.review], collapsible: false),
        Column(id: .done, statuses: [.done], collapsible: false),
    ]

    enum Action: String { case queue, promote, schedule, block, unblock, requestReview, reopenReview, complete, archive }

    struct Transition: Equatable {
        let action: Action
        var requiresReason = false
        var confirm = false
    }

    struct Drop: Equatable, Identifiable {
        let to: TaskStatus
        let transition: Transition
        var id: String { to.rawValue }
    }

    /// Keyed by destination, then origin.
    private static let rules: [TaskStatus: [TaskStatus: Transition]] = [
        .todo: [.triage: Transition(action: .queue), .blocked: Transition(action: .unblock), .scheduled: Transition(action: .unblock), .review: Transition(action: .reopenReview)],
        .ready: [.todo: Transition(action: .promote), .blocked: Transition(action: .unblock), .scheduled: Transition(action: .unblock), .review: Transition(action: .reopenReview)],
        .scheduled: [.todo: Transition(action: .schedule), .ready: Transition(action: .schedule), .running: Transition(action: .schedule), .blocked: Transition(action: .schedule)],
        .blocked: [.todo: Transition(action: .block, requiresReason: true), .ready: Transition(action: .block, requiresReason: true), .running: Transition(action: .block, requiresReason: true)],
        .review: [.ready: Transition(action: .requestReview), .running: Transition(action: .requestReview)],
        .done: [.ready: Transition(action: .complete), .running: Transition(action: .complete), .review: Transition(action: .complete), .blocked: Transition(action: .complete)],
        .archived: [.done: Transition(action: .archive, confirm: true)],
    ]

    static func transition(from: TaskStatus, to: TaskStatus) -> Transition? {
        from == to ? nil : rules[to]?[from]
    }

    static func dropOptions(from: TaskStatus, column: Column) -> [Drop] {
        if column.statuses.contains(from) { return [] }
        var seen = Set<String>()
        return column.statuses.compactMap { to in
            guard let found = transition(from: from, to: to), seen.insert(found.action.rawValue).inserted else { return nil }
            return Drop(to: to, transition: found)
        }
    }

    static func isDropTarget(from: TaskStatus, column: Column) -> Bool {
        column.statuses.contains(from) || !dropOptions(from: from, column: column).isEmpty
    }

    static func column(of status: TaskStatus) -> Column {
        if status == .triage { return intake }
        if status == .archived { return columns[3] }
        return columns.first { $0.statuses.contains(status) } ?? columns[0]
    }

    static func collapsed(_ column: Column, count: Int, openedByHand: Bool, dragging: TaskStatus?) -> Bool {
        if !column.collapsible || count > 0 || openedByHand { return false }
        if let dragging, isDropTarget(from: dragging, column: column) { return false }
        return true
    }

    static func showsStatusWord(_ status: TaskStatus) -> Bool { status != .todo && status != .triage }

    /// Each column's tasks, in the hub's order within it.
    static func group(_ board: CoreHubClient.TaskColumns) -> [ColumnID: [HubTask]] {
        var byStatus: [TaskStatus: [HubTask]] = [:]
        for column in board.columns { byStatus[column.status] = column.tasks }
        var out: [ColumnID: [HubTask]] = [:]
        for column in [intake] + columns {
            out[column.id] = column.statuses.flatMap { byStatus[$0] ?? [] }
        }
        return out
    }

    static func archived(_ board: CoreHubClient.TaskColumns) -> Int {
        board.columns.first { $0.status == .archived }?.count ?? board.counts.byStatus["archived"] ?? 0
    }

    enum Outcome: Equatable {
        case reordered(afterTaskID: String?)
        case move(Drop)
        case choose([Drop])
        case refused
        case unchanged
    }

    /// `index`: how many of the column's other cards lie above the drop point.
    static func outcome(task: HubTask, column: Column, columnTasks: [HubTask], index: Int) -> Outcome {
        let options = dropOptions(from: task.status, column: column)
        if options.isEmpty && column.statuses.contains(task.status) {
            let others = columnTasks.filter { $0.id != task.id }
            let at = min(max(index, 0), others.count)
            let above = others.prefix(at).last { $0.profile == task.profile }
            let before = columnTasks.prefix { $0.id != task.id }.last { $0.profile == task.profile }
            if above?.id == before?.id { return .unchanged }
            return .reordered(afterTaskID: above?.id)
        }
        if options.isEmpty { return .refused }
        if options.count == 1 { return .move(options[0]) }
        return .choose(options)
    }

    static func waitingOn(_ task: HubTask) -> [String] { (task.waitingOn ?? []).map(\.title) }

    static func stuck(_ task: HubTask) -> Bool { task.stuckSince != nil && task.status == .running }
}

/// Where each card sits inside its column, for a drop's reorder.
private struct CardFrames: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

struct TaskBoardView: View {
    let openChat: (_ sessionID: String, _ profile: String) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var board: CoreHubClient.TaskColumns?
    @State private var error: String?
    @State private var frames: [String: CGRect] = [:]
    @State private var targeted: BoardLogic.ColumnID?
    @State private var openedWaiting = false
    @State private var choosing: (task: HubTask, options: [BoardLogic.Drop])?
    @State private var blocking: (task: HubTask, drop: BoardLogic.Drop)?
    @State private var reason = ""
    @State private var focused: BoardLogic.ColumnID?
    @State private var archiving: (task: HubTask, drop: BoardLogic.Drop)?
    /// The task open on its own (TaskDetailView), and whether a new one is being written.
    @State private var opened: OpenedTask?
    @State private var creating = false
    /// Tasks II (batch 5): the profile's projects and the one the board is narrowed to, the
    /// projects sheet, and "Select" — the ticked cards and what is done to them all at once (§103).
    @State private var projects: [Project] = []
    @State private var projectFilter: String?
    @State private var managing = false
    @State private var selecting = false
    @State private var ticked: Set<String> = []
    @State private var bulkBusy = false
    @State private var bulkNotice: String?
    @State private var commenting = false
    @State private var bulkText = ""
    @State private var bulkDeleting = false

    var body: some View {
        Group {
            if let board {
                content(board)
            } else if let error {
                EmptyStateView(icon: .triangleAlert, title: l10n("common.error_title"), message: error, actionTitle: l10n("common.retry")) {
                    Task { await load() }
                }
            } else {
                SkeletonList()
            }
        }
        .task(id: projectFilter) { await load() }
        .task { await loadProjects() }
        .refreshable { await load() }
        // What an agent or another device does to a card shows here as it happens (`/rt/tasks`).
        .liveReload("/rt/tasks", events: ["task.", "project.", "subtask.", "comment.", "worktree."]) { await load() }
        .sheet(isPresented: $managing) {
            ProjectsSheet(changed: { Task { await loadProjects(); await load() } })
        }
        .alert(l10n("tasks.bulk.comment_title", ["count": String(ticked.count)]), isPresented: $commenting) {
            TextField(l10n("tasks.comments.placeholder"), text: $bulkText)
            Button(l10n("tasks.comments.send")) {
                let words = bulkText.trimmingCharacters(in: .whitespacesAndNewlines)
                bulkText = ""
                if !words.isEmpty { Task { await bulk(TaskBulkUpdatePatch(comment: words)) } }
            }
            .accessibilityIdentifier("dialog.confirm")
            Button(l10n("common.cancel"), role: .cancel) { bulkText = "" }
        }
        .alert(l10n("tasks.bulk.delete_confirm", ["count": String(ticked.count)]), isPresented: $bulkDeleting) {
            Button(l10n("common.cancel"), role: .cancel) {}
            Button(l10n("kit.delete"), role: .destructive) { Task { await bulkDelete() } }
                .accessibilityIdentifier("dialog.confirm")
        } message: {
            Text(l10n("kit.delete_body"))
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { creating = true } label: { LucideIcon(.plus, size: 20) }
                    .accessibilityLabel(l10n("tasks.new_task"))
                    .accessibilityIdentifier("tasks.new")
            }
        }
        .sheet(item: $opened) { which in
            TaskDetailView(task: which.task, openChat: openChat, changed: { Task { await load() } })
        }
        .sheet(isPresented: $creating) {
            NewTaskSheet(created: { _ in Task { await load() } })
        }
        .alert(archiving.map { l10n("tasks.archive_confirm", ["title": $0.task.title]) } ?? "", isPresented: Binding(get: { archiving != nil }, set: { if !$0 { archiving = nil } })) {
            Button(l10n("board.action_archive")) {
                if let archiving {
                    self.archiving = nil
                    Task { await move(archiving.task, to: archiving.drop.to, reason: nil) }
                }
            }
            .accessibilityIdentifier("dialog.confirm")
            Button(l10n("common.cancel"), role: .cancel) { archiving = nil }
        }
        .confirmationDialog(l10n("board.which"), isPresented: Binding(get: { choosing != nil }, set: { if !$0 { choosing = nil } }), titleVisibility: .visible) {
            if let choosing {
                ForEach(choosing.options) { option in
                    Button(l10n("board.action_\(option.transition.action.rawValue)")) {
                        let task = choosing.task
                        self.choosing = nil
                        start(option, task: task)
                    }
                }
            }
            Button(l10n("common.cancel"), role: .cancel) { choosing = nil }
        }
        .alert(l10n("board.block_reason"), isPresented: Binding(get: { blocking != nil }, set: { if !$0 { blocking = nil } })) {
            TextField(l10n("board.block_reason_hint"), text: $reason)
            Button(l10n("board.action_block")) {
                if let blocking {
                    let why = reason
                    reason = ""
                    self.blocking = nil
                    Task { await move(blocking.task, to: blocking.drop.to, reason: why) }
                }
            }
            .disabled(reason.trimmingCharacters(in: .whitespaces).isEmpty)
            Button(l10n("common.cancel"), role: .cancel) { blocking = nil; reason = "" }
        }
    }

    private func visibleColumns(_ grouped: [BoardLogic.ColumnID: [HubTask]]) -> [BoardLogic.Column] {
        ((grouped[.intake] ?? []).isEmpty ? [] : [BoardLogic.intake]) + BoardLogic.columns
    }

    @ViewBuilder
    private func content(_ board: CoreHubClient.TaskColumns) -> some View {
        let grouped = BoardLogic.group(board)
        let columns = visibleColumns(grouped)
        let badges = Set(grouped.values.flatMap { $0 }.map(\.profile)).count > 1
        VStack(spacing: 0) {
            if let error { NoticeView(text: error, tone: .danger).padding(.horizontal, Space.s4) }
            if let bulkNotice { NoticeView(text: bulkNotice, tone: .warning).padding(.horizontal, Space.s4) }
            boardTools
            ScrollViewReader { proxy in
                // The columns by name and count: a tap brings one into view, and a card dropped on
                // one goes to that column.
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Space.s2) {
                        ForEach(columns) { column in
                            Button {
                                withAnimation { proxy.scrollTo(column.id, anchor: .leading) }
                            } label: {
                                Text("\(l10n("board.column_\(column.id.rawValue)")) · \((grouped[column.id] ?? []).count)")
                            }
                            .buttonStyle(ChipButtonStyle(quiet: targeted != column.id))
                            .dropDestination(for: String.self) { ids, _ in
                                drop(ids, on: column, grouped: grouped, y: .infinity)
                            } isTargeted: { on in targeted = on ? column.id : (targeted == column.id ? nil : targeted) }
                            .accessibilityIdentifier("board.jump.\(column.id.rawValue)")
                        }
                    }
                    .padding(.horizontal, Space.s4)
                    .padding(.vertical, Space.s2)
                }
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: Space.s3) {
                        ForEach(columns) { column in
                            columnView(column, tasks: grouped[column.id] ?? [], archived: column.id == .done ? BoardLogic.archived(board) : 0, grouped: grouped, badges: badges)
                                .id(column.id)
                        }
                    }
                    .scrollTargetLayout()
                    .padding(.horizontal, Space.s4)
                }
                .scrollTargetBehavior(.viewAligned)
                .scrollIndicators(.hidden)
            }
        }
        .safeAreaInset(edge: .bottom) {
            if selecting { bulkBar }
        }
    }

    // MARK: - Projects and selection (Tasks II)

    /// The project filter, Select, and the projects sheet.
    private var boardTools: some View {
        HStack(spacing: Space.s2) {
            if projects.count > 1 || projectFilter != nil {
                Menu {
                    Button { projectFilter = nil } label: { checkedLabel(l10n("tasks.filter.all"), projectFilter == nil) }
                    ForEach(projects, id: \.id) { project in
                        Button { projectFilter = project.id } label: { checkedLabel(project.name, projectFilter == project.id) }
                    }
                } label: {
                    HStack(spacing: Space.s1) {
                        LucideIcon(.listFilter, size: 14)
                        Text(projects.first { $0.id == projectFilter }?.name ?? l10n("tasks.filter.all")).lineLimit(1)
                    }
                }
                .buttonStyle(ChipButtonStyle(quiet: projectFilter == nil))
                .accessibilityIdentifier("tasks.project_filter")
            }
            Spacer(minLength: 0)
            Button {
                selecting.toggle()
                ticked = []
                bulkNotice = nil
            } label: {
                Text(l10n(selecting ? "tasks.bulk.done" : "tasks.bulk.select"))
            }
            .buttonStyle(ChipButtonStyle(quiet: !selecting))
            .accessibilityIdentifier("tasks.select")
            Button { managing = true } label: { LucideIcon(.folder, size: 16) }
                .buttonStyle(ChipButtonStyle(quiet: true))
                .accessibilityLabel(l10n("tasks.projects.title"))
                .accessibilityIdentifier("tasks.projects")
        }
        .padding(.horizontal, Space.s4)
        .padding(.top, Space.s2)
    }

    @ViewBuilder
    private func checkedLabel(_ title: String, _ on: Bool) -> some View {
        if on { Label { Text(title) } icon: { Image(lucide: .check) } } else { Text(title) }
    }

    private var tickedTasks: [HubTask] {
        (board?.columns ?? []).flatMap(\.tasks).filter { ticked.contains($0.id) }
    }

    /// What is done to every ticked card: a priority, the same comment, the archive, delete.
    private var bulkBar: some View {
        let chosen = tickedTasks
        return HStack(spacing: Space.s3) {
            Text(l10n("tasks.bulk.count", ["count": String(chosen.count)]))
                .font(.system(size: FontSize.sizeSm, weight: .semibold))
                .accessibilityIdentifier("tasks.bulk.count")
            Spacer(minLength: 0)
            if bulkBusy { ProgressView() }
            Menu {
                ForEach(TaskRules.priorities, id: \.self) { priority in
                    Button(l10n("tasks.priority_\(priority.rawValue)")) { Task { await bulk(TaskBulkUpdatePatch(priority: priority)) } }
                }
            } label: {
                LucideIcon(.gauge, size: 20)
            }
            .accessibilityLabel(l10n("tasks.bulk.priority"))
            .accessibilityIdentifier("tasks.bulk.priority")
            Button { commenting = true } label: { LucideIcon(.messagesSquare, size: 20) }
                .accessibilityLabel(l10n("tasks.bulk.comment"))
                .accessibilityIdentifier("tasks.bulk.comment")
            if BulkRules.archivable(chosen) {
                Button { Task { await bulk(TaskBulkUpdatePatch(archived: true)) } } label: { LucideIcon(.archive, size: 20) }
                    .accessibilityLabel(l10n("tasks.bulk.archive"))
                    .accessibilityIdentifier("tasks.bulk.archive")
            }
            Button(role: .destructive) { bulkDeleting = true } label: { LucideIcon(.trash, size: 20).foregroundStyle(Tone.danger) }
                .accessibilityLabel(l10n("kit.delete"))
                .accessibilityIdentifier("tasks.bulk.delete")
        }
        .disabled(chosen.isEmpty || bulkBusy)
        .padding(.horizontal, Space.s4)
        .padding(.vertical, Space.s3)
        .background(.bar)
        .accessibilityIdentifier("tasks.bulk.bar")
    }

    /// The same change on every ticked card, one call per profile they are in.
    private func bulk(_ patch: TaskBulkUpdatePatch) async {
        let calls = BulkRules.calls(tickedTasks)
        bulkBusy = true
        defer { bulkBusy = false }
        var answers: [BulkResult] = []
        do {
            for call in calls {
                let request = TaskBulkUpdate(taskIds: call.ids, patch: patch)
                answers.append(try await app.api.call { try await TasksAPI.tasksBulkUpdateTasks(xHubProfile: call.profile, taskBulkUpdate: request, apiConfiguration: $0) })
            }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        settle(answers)
        await load()
    }

    private func bulkDelete() async {
        let calls = BulkRules.calls(tickedTasks)
        bulkBusy = true
        defer { bulkBusy = false }
        var answers: [BulkResult] = []
        do {
            for call in calls {
                let ids = BulkRules.idsParam(call.ids)
                answers.append(try await app.api.call { try await TasksAPI.tasksBulkDeleteTasks(xHubProfile: call.profile, ids: ids, apiConfiguration: $0) })
            }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        settle(answers)
        await load()
    }

    /// All went: the selection ends. Some were refused: they stay ticked, and the board says how many.
    private func settle(_ answers: [BulkResult]) {
        let (changed, refused) = BulkRules.tally(answers)
        if refused > 0 {
            let failed = Set(answers.flatMap(\.results).filter { !$0.ok }.map(\.id))
            ticked = ticked.intersection(failed)
            bulkNotice = l10n("tasks.bulk.refused", ["done": String(changed), "count": String(refused)])
        } else if !answers.isEmpty {
            ticked = []
            bulkNotice = nil
            selecting = false
        }
    }

    private func loadProjects() async {
        let profile = app.currentProfile
        projects = (try? await app.api.call { try await TasksAPI.tasksListProjects(xHubProfile: profile, apiConfiguration: $0) }.items) ?? []
        if let filter = projectFilter, !projects.contains(where: { $0.id == filter }) { projectFilter = nil }
    }

    @ViewBuilder
    private func columnView(_ column: BoardLogic.Column, tasks: [HubTask], archived: Int, grouped: [BoardLogic.ColumnID: [HubTask]], badges: Bool) -> some View {
        let folded = BoardLogic.collapsed(column, count: tasks.count, openedByHand: openedWaiting, dragging: nil)
        let space = "board.\(column.id.rawValue)"
        VStack(alignment: .leading, spacing: Space.s2) {
            HStack(spacing: Space.s2) {
                Circle().fill(color(column.statuses[0])).frame(width: 8, height: 8)
                Text("\(l10n("board.column_\(column.id.rawValue)")) · \(tasks.count)")
                    .font(.system(size: FontSize.sizeSm, weight: .semibold))
                    .foregroundStyle(Tone.textMuted)
                Spacer()
                if folded {
                    Button(l10n("board.open_column")) { openedWaiting = true }.font(.system(size: FontSize.sizeXs))
                }
            }
            if !folded {
                ScrollView {
                    LazyVStack(spacing: Space.s2) {
                        if tasks.isEmpty {
                            Text(l10n("board.empty_column")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(Space.s2)
                        }
                        ForEach(tasks, id: \.id) { task in
                            let card = BoardCard(
                                task: task, showProfile: badges, openChat: openChat,
                                open: {
                                    if selecting {
                                        if ticked.contains(task.id) { ticked.remove(task.id) } else { ticked.insert(task.id) }
                                    } else {
                                        opened = OpenedTask(task: task)
                                    }
                                },
                                choose: { drop in start(drop, task: task) },
                                selected: selecting ? ticked.contains(task.id) : nil
                            )
                            .background(GeometryReader { g in
                                Color.clear.preference(key: CardFrames.self, value: [task.id: g.frame(in: .named(space))])
                            })
                            // While selecting, a card is ticked, not lifted.
                            if selecting {
                                card
                            } else {
                                card.draggable(task.id) {
                                    Text(task.title).font(.system(size: FontSize.sizeSm, weight: .semibold))
                                        .padding(Space.s3).background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.lg))
                                }
                            }
                        }
                        if archived > 0 {
                            Text(l10n("board.archived", ["count": String(archived)]))
                                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(Space.s2)
                        }
                    }
                    .padding(.bottom, Space.s8)
                }
            }
        }
        .padding(Space.s2)
        .frame(maxHeight: .infinity, alignment: .top)
        .containerRelativeFrame(.horizontal) { width, _ in folded ? 120 : width * 0.86 }
        .background(targeted == column.id ? Tone.accentSoft : Tone.surface2.opacity(0.5), in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous).strokeBorder(targeted == column.id ? Tone.accent : Color.clear, lineWidth: 1.5))
        .coordinateSpace(.named(space))
        .onPreferenceChange(CardFrames.self) { value in frames.merge(value, uniquingKeysWith: { _, new in new }) }
        .dropDestination(for: String.self) { ids, location in
            drop(ids, on: column, grouped: grouped, y: location.y)
        } isTargeted: { on in targeted = on ? column.id : (targeted == column.id ? nil : targeted) }
        .accessibilityIdentifier("board.column.\(column.id.rawValue)")
    }

    private func color(_ status: TaskStatus) -> Color {
        switch status {
        case .running: return Tone.statusRunning
        case .blocked, .scheduled: return Tone.statusBlocked
        case .review: return Color.token(\.statusReview)
        case .done: return Tone.successSoftText
        default: return Tone.textFaint
        }
    }

    /// Letting go of a card over a column (or its chip, `y` infinite: at the end).
    private func drop(_ ids: [String], on column: BoardLogic.Column, grouped: [BoardLogic.ColumnID: [HubTask]], y: CGFloat) -> Bool {
        targeted = nil
        guard let id = ids.first, let task = grouped.values.flatMap({ $0 }).first(where: { $0.id == id }) else { return false }
        let list = grouped[column.id] ?? []
        let index = list.filter { $0.id != task.id }.filter { (frames[$0.id]?.midY ?? .infinity) < y }.count
        switch BoardLogic.outcome(task: task, column: column, columnTasks: list, index: index) {
        case .reordered(let after):
            Task { await reorder(task, after: after) }
        case .move(let option):
            start(option, task: task)
        case .choose(let options):
            choosing = (task, options)
        case .refused:
            error = l10n("board.cannot_drop")
            return false
        case .unchanged:
            break
        }
        return true
    }

    private func start(_ option: BoardLogic.Drop, task: HubTask) {
        if option.transition.requiresReason {
            blocking = (task, option)
        } else if option.transition.confirm {
            archiving = (task, option)
        } else {
            Task { await move(task, to: option.to, reason: nil) }
        }
    }

    private func load() async {
        do {
            let project = projectFilter
            board = try await app.api.call { try await TasksAPI.tasksGetColumns(profiles: .all, projectId: project, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func move(_ task: HubTask, to status: TaskStatus, reason: String?) async {
        await send(task, TaskMove(status: status, reason: reason?.isEmpty == true ? nil : reason))
    }

    private func reorder(_ task: HubTask, after: String?) async {
        await send(task, TaskMove(status: task.status, afterTaskId: after))
    }

    /// An existing task is acted on in its own profile.
    private func send(_ task: HubTask, _ change: TaskMove) async {
        let profile = task.profile, id = task.id
        do {
            _ = try await app.api.call { try await TasksAPI.tasksMoveTask(xHubProfile: profile, taskId: id, taskMove: change, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        await load()
    }
}

/// A task's card: title, profile, stage, what it waits on or that it seems stuck, who has it, the latest line.
struct BoardCard: View {
    let task: HubTask
    let showProfile: Bool
    let openChat: (_ sessionID: String, _ profile: String) -> Void
    /// Opens the task on its own (TaskDetailView).
    let open: () -> Void
    /// A move from the card's menu means what the same drop would: a reason for a block, a yes to archive.
    let choose: (BoardLogic.Drop) -> Void
    /// While the board is selecting: whether this card is ticked (a tap ticks it); nil otherwise.
    var selected: Bool? = nil
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            HStack(alignment: .firstTextBaseline) {
                if let selected {
                    LucideIcon(selected ? .circleCheck : .circle, size: 18)
                        .foregroundStyle(selected ? Tone.accent : Tone.textFaint)
                        .accessibilityIdentifier("task.tick.\(task.id)")
                }
                Text(task.title)
                    .font(.system(size: FontSize.sizeMd, weight: .medium))
                    .foregroundStyle(Tone.text)
                    .contentDirection(of: task.title)
                    .lineLimit(3)
                Spacer(minLength: 0)
                if showProfile { ProfileBadge(name: app.profileName(task.profile)) }
            }
            FlowLayout(spacing: Space.s1) {
                if BoardLogic.showsStatusWord(task.status) {
                    StatusPill(text: l10n("tasks.status_\(task.status.rawValue)"), kind: task.status == .blocked ? .bad : task.status == .running ? .good : .neutral)
                }
                if BoardLogic.stuck(task) {
                    StatusPill(text: l10n("board.stuck"), kind: .bad).accessibilityIdentifier("task.stuck.\(task.id)")
                }
                let waiting = BoardLogic.waitingOn(task)
                if !waiting.isEmpty {
                    StatusPill(text: l10n("board.waiting_on", ["count": String(waiting.count)]), kind: .warn)
                        .accessibilityIdentifier("task.waiting.\(task.id)")
                        .accessibilityHint(waiting.joined(separator: ", "))
                }
                if task.priority == .urgent || task.priority == .high {
                    StatusPill(text: l10n("tasks.priority_\(task.priority.rawValue)"), kind: .warn)
                }
                if let assignee = task.assignee {
                    Text(assignee.name).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
                // The checklist, as the web's card counts it.
                if task.subtaskCounts.total > 0 {
                    HStack(spacing: 2) {
                        LucideIcon(.listChecks, size: 12)
                        Text("\(task.subtaskCounts.done)/\(task.subtaskCounts.total)")
                    }
                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    .accessibilityIdentifier("task.subtasks.\(task.id)")
                }
            }
            if let reason = task.blockedReason ?? task.statusReason {
                Text(reason).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2)
            }
            if let summary = task.latestSummary {
                Text(summary).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2)
                    .contentDirection(of: summary)
            }
        }
        .padding(Space.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous).strokeBorder(selected == true ? Tone.accent : Tone.border, lineWidth: selected == true ? 1.5 : 0.5))
        .contentShape(RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
        .accessibilityAddTraits(selected == true ? .isSelected : [])
        .onTapGesture(perform: open)
        .contextMenu {
            Button(l10n("tasks.detail.open"), action: open)
            if let sessionID = task.sessionId {
                Button(l10n("tasks.open_chat")) { openChat(sessionID, task.profile) }
            }
            let moves = TaskRules.moves(from: task.status)
            if !moves.isEmpty {
                Menu(l10n("tasks.move")) {
                    ForEach(moves) { drop in
                        Button(l10n("board.action_\(drop.transition.action.rawValue)")) { choose(drop) }
                    }
                }
            }
        }
        .accessibilityIdentifier("task.card.\(task.id)")
    }
}

/// A task the board opened on its own (a sheet needs an identity; the generated task has none).
struct OpenedTask: Identifiable {
    let task: HubTask
    var id: String { task.id }
}

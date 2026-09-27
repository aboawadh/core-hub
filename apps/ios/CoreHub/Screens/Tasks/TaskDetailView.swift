// A task opened on its own (batch 2, Tasks I), the web's task dialog and card menu in one sheet:
// its words (the description in Markdown), where it stands, who has it, when it is due, what it
// waits for, the run it started with a way into that conversation — and what a person does to it:
// move it, give it to an agent (and start), take it back, stop its run, edit, delete. Everything is
// done in the task's own profile (ADR 0016). The rules are TaskRules.swift; Android's twin is
// TaskDetail.kt.
import CoreHubClient
import SwiftUI

/// The fields the sheet shows, from the board's card until the full task arrives.
struct TaskFacts: Equatable {
    let id: String
    let profile: String
    let title: String
    let description: String?
    let status: TaskStatus
    let priority: TaskPriority
    let projectID: String
    let assignee: Assignee?
    let dueAt: Date?
    let dependsOn: [String]
    let waitingOn: [TaskDependencyState]?
    let sessionID: String?
    let latestSummary: String?
    let reason: String?
    let stuckSince: Date?
    let hermes: Bool

    init(_ task: HubTask) {
        id = task.id; profile = task.profile; title = task.title; description = task.description
        status = task.status; priority = task.priority; projectID = task.projectId; assignee = task.assignee
        dueAt = task.dueAt; dependsOn = task.dependsOn; waitingOn = task.waitingOn; sessionID = task.sessionId
        latestSummary = task.latestSummary; reason = task.blockedReason ?? task.statusReason
        stuckSince = task.stuckSince; hermes = TaskRules.fromHermes(task.external)
    }

    init(_ task: TaskDetail) {
        id = task.id; profile = task.profile; title = task.title; description = task.description
        status = task.status; priority = task.priority; projectID = task.projectId; assignee = task.assignee
        dueAt = task.dueAt; dependsOn = task.dependsOn; waitingOn = task.waitingOn; sessionID = task.sessionId
        latestSummary = task.latestSummary; reason = task.blockedReason ?? task.statusReason
        stuckSince = task.stuckSince; hermes = TaskRules.fromHermes(task.external)
    }
}

struct TaskDetailView: View {
    let task: HubTask
    let openChat: (_ sessionID: String, _ profile: String) -> Void
    /// The board reloads after anything changed here.
    let changed: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var detail: TaskDetail?
    @State private var projects: [Project] = []
    @State private var failure: String?
    @State private var notice: String?
    @State private var busy = false
    @State private var sheet: Sheet?
    @State private var moving = false
    @State private var blocking: BoardLogic.Drop?
    @State private var reason = ""
    @State private var archiving: BoardLogic.Drop?
    @State private var deleting: TaskFacts?
    @State private var handing = false

    private enum Sheet: Identifiable {
        case edit
        case assign([Agent])
        var id: String {
            switch self {
            case .edit: return "edit"
            case .assign: return "assign"
            }
        }
    }

    private var shown: TaskFacts { detail.map(TaskFacts.init) ?? TaskFacts(task) }

    var body: some View {
        NavigationStack {
            List {
                header
                if let failure { Section { NoticeView(text: failure, tone: .danger) } }
                if let notice { Section { NoticeView(text: notice, tone: .info) } }
                facts
                descriptionSection
                lists
                dependencies
                runSection
                if let worktree = detail?.worktree ?? task.worktree {
                    TaskWorktreeSection(worktree: worktree, running: shown.status == .running) {
                        let facts = shown
                        Task {
                            await act { _ = try await TasksAPI.tasksDeleteWorktree(xHubProfile: facts.profile, taskId: facts.id, apiConfiguration: $0) }
                        }
                    }
                }
                CommentsSection(comments: detail?.comments ?? [], send: say)
                actions
            }
            .listStyle(.insetGrouped)
            .navigationTitle(l10n("tasks.detail.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(l10n("common.close")) { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button { sheet = .edit } label: { LucideLabel(l10n("tasks.edit"), icon: .pencil) }
                            .accessibilityIdentifier("task.edit")
                        if TaskExtrasRules.handable(shown) {
                            Button { handing = true } label: { LucideLabel(l10n("task_extras.handover.open"), icon: .share2) }
                                .accessibilityIdentifier("task.handover")
                        }
                        Button(role: .destructive) { deleting = shown } label: { LucideLabel(l10n("kit.delete"), icon: .trash) }
                            .accessibilityIdentifier("task.delete")
                    } label: {
                        LucideIcon(.ellipsis, size: 18)
                    }
                    .accessibilityLabel(l10n("kit.more_actions"))
                    .accessibilityIdentifier("task.more")
                }
            }
            .refreshable { await load() }
            .task { await load() }
            .sheet(isPresented: $handing) {
                NavigationStack {
                    HandOverSheet(facts: shown) {
                        changed()
                        dismiss()
                    }
                }
            }
            .sheet(item: $sheet) { which in
                switch which {
                case .edit: editSheet
                case .assign(let agents): assignSheet(agents)
                }
            }
            .confirmationDialog(l10n("tasks.move"), isPresented: $moving, titleVisibility: .visible) {
                ForEach(TaskRules.moves(from: shown.status)) { drop in
                    Button(l10n("board.action_\(drop.transition.action.rawValue)")) { start(drop) }
                }
                Button(l10n("common.cancel"), role: .cancel) {}
            }
            .alert(l10n("board.block_reason"), isPresented: Binding(get: { blocking != nil }, set: { if !$0 { blocking = nil } })) {
                TextField(l10n("board.block_reason_hint"), text: $reason)
                Button(l10n("board.action_block")) {
                    if let drop = blocking {
                        let why = reason.trimmingCharacters(in: .whitespaces)
                        reason = ""
                        blocking = nil
                        Task { await move(drop, reason: why) }
                    }
                }
                .disabled(reason.trimmingCharacters(in: .whitespaces).isEmpty)
                Button(l10n("common.cancel"), role: .cancel) { blocking = nil; reason = "" }
            }
            .alert(l10n("tasks.archive_confirm", ["title": shown.title]), isPresented: Binding(get: { archiving != nil }, set: { if !$0 { archiving = nil } })) {
                Button(l10n("board.action_archive")) {
                    if let drop = archiving {
                        archiving = nil
                        Task { await move(drop, reason: nil) }
                    }
                }
                .accessibilityIdentifier("dialog.confirm")
                Button(l10n("common.cancel"), role: .cancel) { archiving = nil }
            }
            .confirmDelete($deleting, name: \.title, delete: { facts in
                _ = try await app.api.call { try await TasksAPI.tasksDeleteTask(xHubProfile: facts.profile, taskId: facts.id, apiConfiguration: $0) }
            }, deleted: { _ in
                changed()
                dismiss()
            })
        }
        .accessibilityIdentifier("task.detail")
    }

    // MARK: - Sections

    private var header: some View {
        Section {
            VStack(alignment: .leading, spacing: Space.s2) {
                HStack(alignment: .firstTextBaseline) {
                    Text(shown.title)
                        .font(.system(size: FontSize.sizeLg, weight: .semibold))
                        .foregroundStyle(Tone.text)
                        .contentDirection(of: shown.title)
                        .accessibilityIdentifier("task.detail.title")
                    Spacer(minLength: 0)
                    if app.enterableProfiles.count > 1 { ProfileBadge(name: app.profileName(shown.profile)) }
                }
                FlowLayout(spacing: Space.s1) {
                    StatusPill(text: l10n("tasks.status_\(shown.status.rawValue)"), kind: statusKind(shown.status))
                        .accessibilityIdentifier("task.detail.status")
                    StatusPill(text: l10n("tasks.priority_\(shown.priority.rawValue)"), kind: shown.priority == .urgent || shown.priority == .high ? .warn : .neutral)
                    if shown.status == .running && shown.stuckSince != nil {
                        StatusPill(text: l10n("board.stuck"), kind: .bad)
                    }
                }
                if shown.hermes {
                    Text(l10n("tasks.detail.hermes")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
                if let reason = shown.reason {
                    NoticeView(text: reason, tone: shown.status == .blocked ? .danger : .warning)
                }
                if shown.status == .running, let since = shown.stuckSince {
                    NoticeView(text: l10n("tasks.detail.stuck_since", ["time": since.shortText(app.language)]), tone: .danger)
                }
            }
            .padding(.vertical, Space.s1)
        }
    }

    private var facts: some View {
        Section {
            FactRow(label: l10n("tasks.form.project"), value: projectName)
            FactRow(label: l10n("tasks.detail.assignee"), value: assigneeText)
                .accessibilityIdentifier("task.detail.assignee")
            FactRow(label: l10n("tasks.detail.due"), value: shown.dueAt?.shortText(app.language) ?? l10n("tasks.detail.no_due"))
            // Start on its own once it is ready and given to an agent; a Hermes card has none.
            if !shown.hermes {
                Toggle(isOn: Binding(get: { detail?.autoStart ?? task.autoStart }, set: { on in
                    let facts = shown
                    Task { await act { try await TasksAPI.tasksUpdateTask(xHubProfile: facts.profile, taskId: facts.id, taskPatch: TaskPatch(autoStart: on), apiConfiguration: $0) } }
                })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(l10n("tasks.auto_start.label"))
                        Text(l10n("tasks.auto_start.hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                }
                .disabled(busy)
                .accessibilityIdentifier("task.auto_start")
            }
        }
    }

    /// The checklist, the definition of done and the constraints — or, for a Hermes card, Hermes's
    /// own history of it (Hermes briefs its own worker, §103/§104).
    @ViewBuilder
    private var lists: some View {
        if shown.hermes {
            if let history = detail?.hermes { HermesHistorySection(history: history) }
        } else {
            SubtasksSection(
                lines: detail?.subtasks ?? [],
                editable: detail != nil && !busy,
                tick: { line in subtaskCall { try await TasksAPI.tasksUpdateSubtask(xHubProfile: $0.profile, taskId: $0.id, subtaskId: line.id, subtaskWrite: SubtaskWrite(status: SubtaskRules.toggled(line.status)), apiConfiguration: $1) } },
                add: addLine,
                delete: { line in subtaskCall { try await TasksAPI.tasksDeleteSubtask(xHubProfile: $0.profile, taskId: $0.id, subtaskId: line.id, apiConfiguration: $1) } },
                reorder: reorder
            )
            let status = shown.status
            CheckLinesSection(kind: .done, items: detail?.definitionOfDone ?? task.definitionOfDone ?? [], status: status) { items in
                subtaskCall { try await TasksAPI.tasksUpdateTask(xHubProfile: $0.profile, taskId: $0.id, taskPatch: CheckLines.patch(.done, items), apiConfiguration: $1) }
            }
            CheckLinesSection(kind: .constraints, items: detail?.constraints ?? task.constraints ?? [], status: status) { items in
                subtaskCall { try await TasksAPI.tasksUpdateTask(xHubProfile: $0.profile, taskId: $0.id, taskPatch: CheckLines.patch(.constraints, items), apiConfiguration: $1) }
            }
        }
    }

    private var descriptionSection: some View {
        Section(l10n("tasks.form.description")) {
            if let text = shown.description, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                MarkdownView(text: text, foreground: Tone.text)
                    .accessibilityIdentifier("task.detail.description")
            } else {
                Text(l10n("tasks.detail.no_description")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textFaint)
            }
        }
    }

    @ViewBuilder
    private var dependencies: some View {
        let waiting = TaskRules.waiting(shown.status, shown.waitingOn)
        let done = TaskRules.doneDependencies(dependsOn: shown.dependsOn, waitingOn: shown.waitingOn)
        if !shown.dependsOn.isEmpty {
            Section(l10n("tasks.detail.depends_on")) {
                ForEach(waiting, id: \.id) { one in
                    HStack {
                        Text(one.title).contentDirection(of: one.title).lineLimit(2)
                        Spacer(minLength: Space.s2)
                        StatusPill(text: l10n("tasks.status_\(one.status.rawValue)"))
                    }
                    .font(.system(size: FontSize.sizeSm))
                }
                if done > 0 {
                    Text(l10n("tasks.detail.dependencies_done", ["count": String(done)]))
                        .font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                }
            }
            .accessibilityIdentifier("task.detail.dependencies")
        }
    }

    private var runSection: some View {
        Section(l10n("tasks.detail.run")) {
            if let run = detail?.runs.first {
                FactRow(label: l10n("tasks.detail.run_status"), value: l10n("tasks.run_\(run.status.rawValue)"))
                    .accessibilityIdentifier("task.detail.run")
                FactRow(label: l10n("tasks.detail.run_started"), value: (run.startedAt ?? run.createdAt).shortText(app.language))
            } else {
                Text(l10n("tasks.detail.no_run")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textFaint)
            }
            if let summary = shown.latestSummary, !summary.isEmpty {
                Text(summary).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted).contentDirection(of: summary)
            }
            if let sessionID = shown.sessionID {
                Button {
                    dismiss()
                    openChat(sessionID, shown.profile)
                } label: {
                    LucideLabel(l10n("tasks.open_chat"), icon: .messagesSquare)
                }
                .accessibilityIdentifier("task.open_chat")
            }
        }
    }

    private var actions: some View {
        Section {
            let facts = shown
            if !TaskRules.moves(from: facts.status).isEmpty {
                Button { moving = true } label: { LucideLabel(l10n("tasks.move"), icon: .chevronsUpDown) }
                    .accessibilityIdentifier("task.move")
            }
            if TaskRules.canStop(facts.status) {
                Button(role: .destructive) { Task { await act { try await TasksAPI.tasksStopTask(xHubProfile: facts.profile, taskId: facts.id, apiConfiguration: $0) } } } label: {
                    LucideLabel(l10n("tasks.stop"), icon: .circleStop)
                }
                .accessibilityIdentifier("task.stop")
            }
            if TaskRules.canAssign(facts.status, hermes: facts.hermes) {
                Button { Task { await openAssign() } } label: {
                    LucideLabel(l10n(facts.assignee?.kind == .agent ? "tasks.reassign" : "tasks.assign"), icon: .userPlus)
                }
                .accessibilityIdentifier("task.assign")
            }
            if TaskRules.canUnassign(facts.assignee, hermes: facts.hermes) {
                Button { Task { await act { try await TasksAPI.tasksUnassignTask(xHubProfile: facts.profile, taskId: facts.id, apiConfiguration: $0) } } } label: {
                    LucideLabel(l10n("tasks.unassign"), icon: .x)
                }
                .accessibilityIdentifier("task.unassign")
            }
        }
        .disabled(busy)
    }

    // MARK: - Sheets

    private var editSheet: some View {
        let facts = shown
        let initial = TaskRules.editValues(title: facts.title, description: facts.description, priority: facts.priority, projectID: facts.projectID, dueAt: facts.dueAt)
        var fields = [
            FormField(key: "title", label: l10n("tasks.form.title"), required: true),
            FormField(key: "description", label: l10n("tasks.form.description"), kind: .multiline, help: l10n("tasks.form.description_help")),
            FormField(key: "priority", label: l10n("tasks.form.priority"), kind: .choice, required: true, options: priorityOptions),
            FormField(key: "due", label: l10n("tasks.detail.due"), kind: .date),
        ]
        // Another project only when the list came and holds this one.
        if projects.contains(where: { $0.id == facts.projectID }) {
            fields.append(FormField(key: "project", label: l10n("tasks.form.project"), kind: .choice, required: true, options: projects.map { FormOption(value: $0.id, label: $0.name) }))
        }
        return FormSheet(title: l10n("tasks.edit_title"), fields: fields, initial: initial, tag: "task.form") { values in
            guard let patch = TaskRules.patch(from: initial, to: values) else { return }
            _ = try await app.api.call { try await TasksAPI.tasksUpdateTask(xHubProfile: facts.profile, taskId: facts.id, taskPatch: patch, apiConfiguration: $0) }
            await load()
            changed()
        }
    }

    private func assignSheet(_ agents: [Agent]) -> some View {
        let facts = shown
        let current = facts.assignee?.kind == .agent ? facts.assignee?.id : nil
        let waiting = TaskRules.waiting(facts.status, facts.waitingOn).map(\.title)
        let fields = [
            FormField(key: "agent", label: l10n("tasks.assign_agent"), kind: .choice, required: true, options: agents.map { FormOption(value: $0.id, label: $0.name) }),
            FormField(key: "instructions", label: l10n("tasks.assign_instructions"), kind: .multiline, help: l10n("tasks.assign_instructions_help")),
            FormField(key: "start", label: l10n("tasks.form.start"), kind: .toggle, help: l10n("tasks.form.start_help")),
        ]
        let initial = [
            "agent": agents.contains { $0.id == current } ? (current ?? "") : (agents.first?.id ?? ""),
            "start": "true",
        ]
        return FormSheet(
            title: l10n("tasks.assign_title"),
            fields: fields,
            initial: initial,
            intro: waiting.isEmpty ? nil : l10n("tasks.assign_waiting", ["titles": waiting.joined(separator: app.language == .ar ? "، " : ", ")]),
            saveTitle: l10n("tasks.assign"),
            tag: "task.assign"
        ) { values in
            guard let request = TaskRules.assign(values) else { return }
            let answer = try await app.api.call { try await TasksAPI.tasksAssignTask(xHubProfile: facts.profile, taskId: facts.id, taskAssign: request, apiConfiguration: $0) }
            // Asked to start and no run came back: the agent keeps its own board (Hermes).
            notice = request.start == true && answer.runId == nil ? l10n("tasks.assign_not_started") : nil
            await load()
            changed()
        }
    }

    // MARK: - Doing

    private var priorityOptions: [FormOption] {
        TaskRules.priorities.map { FormOption(value: $0.rawValue, label: l10n("tasks.priority_\($0.rawValue)")) }
    }

    private var projectName: String {
        projects.first { $0.id == shown.projectID }?.name ?? "—"
    }

    private var assigneeText: String {
        guard let assignee = shown.assignee else { return l10n("tasks.detail.nobody") }
        return "\(assignee.name) · \(l10n(assignee.kind == .agent ? "tasks.detail.agent" : "tasks.detail.person"))"
    }

    private func statusKind(_ status: TaskStatus) -> StatusPill.Kind {
        switch status {
        case .blocked: return .bad
        case .running, .done: return .good
        case .scheduled, .review: return .warn
        default: return .neutral
        }
    }

    private func load() async {
        let profile = task.profile, id = task.id
        do {
            detail = try await app.api.call { try await TasksAPI.tasksGetTask(xHubProfile: profile, taskId: id, apiConfiguration: $0) }
            failure = nil
        } catch {
            failure = HubFailure(error).describe(l10n)
        }
        if projects.isEmpty, let list = try? await app.api.call({ try await TasksAPI.tasksListProjects(xHubProfile: profile, apiConfiguration: $0) }) {
            projects = list.items
        }
    }

    private func start(_ drop: BoardLogic.Drop) {
        if drop.transition.requiresReason {
            blocking = drop
        } else if drop.transition.confirm {
            archiving = drop
        } else {
            Task { await move(drop, reason: nil) }
        }
    }

    private func move(_ drop: BoardLogic.Drop, reason: String?) async {
        let facts = shown
        let change = TaskMove(status: drop.to, reason: reason?.isEmpty == true ? nil : reason)
        await act { try await TasksAPI.tasksMoveTask(xHubProfile: facts.profile, taskId: facts.id, taskMove: change, apiConfiguration: $0) }
    }

    private func openAssign() async {
        let profile = shown.profile
        do {
            let list = try await app.api.call { try await AgentsAPI.agentsList(xHubProfile: profile, apiConfiguration: $0) }
            let agents = TaskRules.startable(list.items)
            if agents.isEmpty {
                failure = l10n("tasks.assign_no_agents")
            } else {
                failure = nil
                sheet = .assign(agents)
            }
        } catch {
            failure = HubFailure(error).describe(l10n)
        }
    }

    /// A checklist or list write in the task's profile (given the task's facts and the configuration),
    /// then the task read again.
    private func subtaskCall<T>(_ call: @escaping (TaskFacts, CoreHubClientAPIConfiguration) async throws -> T) {
        let facts = shown
        Task { await act { try await call(facts, $0) } }
    }

    private func addLine(_ title: String) async -> Bool {
        let facts = shown
        do {
            _ = try await app.api.call { try await TasksAPI.tasksCreateSubtask(xHubProfile: facts.profile, taskId: facts.id, subtaskWrite: SubtaskWrite(title: title), apiConfiguration: $0) }
            failure = nil
            await load()
            changed()
            return true
        } catch {
            failure = HubFailure(error).describe(l10n)
            return false
        }
    }

    /// The new order shows at once; each line whose place changed is told its new index.
    private func reorder(_ order: [String]) {
        guard let lines = detail?.subtasks else { return }
        let moves = SubtaskRules.reindex(lines, order: order)
        let byID = Dictionary(uniqueKeysWithValues: lines.map { ($0.id, $0) })
        detail?.subtasks = order.enumerated().compactMap { place, id in
            guard var line = byID[id] else { return nil }
            line.index = place
            return line
        }
        guard !moves.isEmpty else { return }
        subtaskCall { facts, configuration in
            for move in moves {
                _ = try await TasksAPI.tasksUpdateSubtask(xHubProfile: facts.profile, taskId: facts.id, subtaskId: move.id, subtaskWrite: SubtaskWrite(index: move.index), apiConfiguration: configuration)
            }
        }
    }

    /// Say something on the task; on a Hermes card it is said on Hermes's card, in your name.
    private func say(_ text: String) async -> Bool {
        guard !text.isEmpty else { return false }
        let facts = shown
        do {
            _ = try await app.api.call { try await TasksAPI.tasksCreateComment(xHubProfile: facts.profile, taskId: facts.id, tasksCreateCommentRequest: TasksCreateCommentRequest(content: text), apiConfiguration: $0) }
            failure = nil
            await load()
            return true
        } catch {
            failure = HubFailure(error).describe(l10n)
            return false
        }
    }

    /// One call in the task's profile, then the task and the board read again.
    private func act<T>(_ call: @escaping (CoreHubClientAPIConfiguration) async throws -> T) async {
        busy = true
        defer { busy = false }
        do {
            _ = try await app.api.call(call)
            failure = nil
            notice = nil
        } catch {
            failure = HubFailure(error).describe(l10n)
        }
        await load()
        changed()
    }
}

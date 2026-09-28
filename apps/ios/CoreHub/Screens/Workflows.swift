// Workflows on the phone (B13): every workflow of every profile the person may enter (their own
// page, WorkflowsScreen.swift, as on the web), run with the limits the hub supports, and a
// read-only view of a run whose steps follow it live — a waiting step is approved or denied right
// there, and a finished run is run again from any step. A workflow is drawn, edited, copied and
// deleted here too (WorkflowEditor.swift), and its saved limits changed. Every call about a
// workflow or a run goes to its own profile (ADR 0016).
import CoreHubClient
import Observation
import SwiftUI

/// The run view's rules, apart from the views so they are unit-tested.
enum WorkflowLogic {
    static func finished(_ run: WorkflowRun) -> Bool {
        run.status == .succeeded || run.status == .failed || run.status == .cancelled
    }

    struct StepRow: Identifiable, Equatable {
        let nodeID: String
        let title: String
        let kind: WorkflowNode.Kind?
        let status: WorkflowStepStatus
        let step: WorkflowStep?
        var id: String { nodeID }

        static func == (a: StepRow, b: StepRow) -> Bool {
            a.nodeID == b.nodeID && a.status == b.status && a.title == b.title
        }
    }

    /// The run's steps in the order they ran, then the nodes it has not reached yet as `pending`
    /// (only while it can still reach them: a finished run shows what ran).
    static func rows(workflow: Workflow?, run: WorkflowRun) -> [StepRow] {
        let nodes = Dictionary((workflow?.nodes ?? []).map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let ran = run.steps.map { step in
            StepRow(nodeID: step.nodeId, title: nodes[step.nodeId]?.title ?? step.nodeId, kind: nodes[step.nodeId]?.kind, status: step.status, step: step)
        }
        if finished(run) { return ran }
        let seen = Set(run.steps.map(\.nodeId))
        let rest = (workflow?.nodes ?? []).filter { !seen.contains($0.id) }.map {
            StepRow(nodeID: $0.id, title: $0.title, kind: $0.kind, status: .pending, step: nil)
        }
        return ran + rest
    }

    /// The step waiting for a person's yes or no, with the approval to answer.
    static func waiting(_ run: WorkflowRun) -> WorkflowStep? {
        run.steps.first { $0.status == .waitingApproval && $0.approvalId != nil }
    }

    enum LimitProblem: Equatable { case duration, cost, step }

    /// Typed limits as the run's own: an empty field keeps the workflow's; minutes become seconds
    /// within the hub's bounds (a week, a day); a cost is a positive USD amount, two decimals at most.
    static func limits(minutes: String, cost: String, stepMinutes: String) -> (WorkflowLimitsOverride?, LimitProblem?) {
        func seconds(_ text: String, max: Int) -> Int?? {
            let trimmed = text.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { return .some(nil) }
            guard let n = Int(trimmed), n >= 1, n * 60 <= max else { return nil }
            return .some(n * 60)
        }
        guard let duration = seconds(minutes, max: 604_800) else { return (nil, .duration) }
        guard let step = seconds(stepMinutes, max: 86_400) else { return (nil, .step) }
        var money: Money?
        let typed = cost.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        if !typed.isEmpty {
            guard let amount = Decimal(string: typed, locale: Locale(identifier: "en_US_POSIX")), amount > 0,
                  typed.split(separator: ".").dropFirst().first.map({ $0.count <= 2 }) ?? true,
                  typed.allSatisfy({ $0.isNumber || $0 == "." }) else { return (nil, .cost) }
            let formatter = NumberFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.minimumFractionDigits = 2
            formatter.maximumFractionDigits = 2
            formatter.minimumIntegerDigits = 1
            money = Money(amount: formatter.string(from: amount as NSDecimalNumber) ?? typed, currency: "USD")
        }
        if duration == nil && step == nil && money == nil { return (nil, nil) }
        return (WorkflowLimitsOverride(maxDurationSeconds: duration, maxCost: money, stepTimeoutSeconds: step), nil)
    }

    /// Seconds as whole minutes, rounded up.
    static func minutes(_ seconds: Int) -> Int { (seconds + 59) / 60 }

    /// A workflow's limits as short facts.
    static func facts(_ limits: WorkflowLimits, _ l10n: L10n) -> [String] {
        var out: [String] = []
        if let s = limits.maxDurationSeconds { out.append(l10n("workflows.limit_duration", ["n": String(minutes(s))])) }
        if let c = limits.maxCost { out.append(l10n("workflows.limit_cost", ["amount": c.amount])) }
        if let s = limits.stepTimeoutSeconds { out.append(l10n("workflows.limit_step", ["n": String(minutes(s))])) }
        return out
    }

    static func runKind(_ status: WorkflowRun.Status) -> StatusPill.Kind {
        switch status {
        case .succeeded: return .good
        case .failed: return .bad
        case .waiting: return .warn
        default: return .neutral
        }
    }

    static func stepKind(_ status: WorkflowStepStatus) -> StatusDot.Kind {
        switch status {
        case .running, .queued, .succeeded: return .good
        case .waitingApproval: return .warn
        case .failed, .rejected: return .bad
        default: return .neutral
        }
    }

    /// "Find a run" (§123): the loaded runs whose task or event id contains the text, in any case.
    static func find(_ runs: [WorkflowRun], _ text: String) -> [WorkflowRun] {
        let needle = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !needle.isEmpty else { return runs }
        return runs.filter { run in
            [run.taskId, run.eventId].contains { $0?.lowercased().contains(needle) == true }
        }
    }

    /// How many runs a workflow's page reads, so "Find a run" has something to look through.
    static let runsPage = 50

    static func icon(_ kind: WorkflowNode.Kind?) -> Lucide {
        switch kind {
        case .approval: return .hand
        case .condition: return .listFilter
        case .delay: return .clock
        case .notify: return .bell
        default: return .bot
        }
    }
}

/// One run, read again every two seconds while it is going and whenever an approval changes.
@MainActor
@Observable
final class WorkflowRunModel {
    private(set) var run: WorkflowRun?
    var error: String?
    private(set) var acting = false
    let profile: String
    let runID: String
    @ObservationIgnored private weak var app: AppModel?
    @ObservationIgnored private var poller: Task<Void, Never>?
    @ObservationIgnored private var listener: UUID?

    init(app: AppModel, profile: String, runID: String) {
        self.app = app
        self.profile = profile
        self.runID = runID
    }

    func start() {
        if listener == nil, let namespace = app?.sessions {
            listener = namespace.onEvent { [weak self] name, _ in
                guard name.hasPrefix("approval.") else { return }
                Task { await self?.refresh() }
            }
        }
        guard poller == nil else { return }
        poller = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.refresh()
                if let run = self.run, WorkflowLogic.finished(run) { return }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    func stop() {
        poller?.cancel()
        poller = nil
        if let listener { app?.sessions?.remove(listener) }
        listener = nil
    }

    func refresh() async {
        guard let app else { return }
        let profile = self.profile, runID = self.runID
        do {
            run = try await app.api.call { try await SchedulesAPI.schedulesGetWorkflowRun(xHubProfile: profile, workflowRunId: runID, apiConfiguration: $0) }
        } catch {
            self.error = HubFailure(error).describe(app.l10n)
        }
    }

    /// Yes continues the run from the step; no fails it with the reason.
    func respond(approve: Bool, reason: String?) async {
        guard let app, let run, let step = WorkflowLogic.waiting(run), let approvalID = step.approvalId else { return }
        acting = true
        defer { acting = false }
        let profile = self.profile
        let answer = reason?.trimmingCharacters(in: .whitespacesAndNewlines)
        let response = ApprovalResponse(decision: approve ? .approveOnce : .deny, answer: (answer?.isEmpty ?? true) ? nil : answer)
        do {
            _ = try await app.api.call {
                try await SessionsAPI.sessionsRespondApproval(xHubProfile: profile, approvalId: approvalID, approvalResponse: response, apiConfiguration: $0)
            }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(app.l10n)
        }
        await refresh()
    }

    /// What a waiting approval asks, in its own words (`sessions.getApproval`), by approval id.
    private(set) var questions: [String: String] = [:]

    func loadQuestion(_ approvalID: String) async {
        guard let app, questions[approvalID] == nil else { return }
        let profile = self.profile
        if let approval = try? await app.api.call({ try await SessionsAPI.sessionsGetApproval(xHubProfile: profile, approvalId: approvalID, apiConfiguration: $0) }) {
            questions[approvalID] = [approval.title, approval.description].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "\n")
        }
    }

    /// A new run that starts at this step, reusing what the earlier steps said in this one.
    func rerun(from nodeID: String) async -> String? {
        guard let app else { return nil }
        acting = true
        defer { acting = false }
        let profile = self.profile, runID = self.runID
        do {
            let accepted = try await app.api.call {
                try await SchedulesAPI.schedulesRerunWorkflowFromNode(
                    xHubProfile: profile, workflowRunId: runID,
                    schedulesRerunWorkflowFromNodeRequest: SchedulesRerunWorkflowFromNodeRequest(fromNodeId: nodeID), apiConfiguration: $0
                )
            }
            error = nil
            return accepted.workflowRunId
        } catch {
            self.error = HubFailure(error).describe(app.l10n)
            return nil
        }
    }

    func cancel() async {
        guard let app else { return }
        acting = true
        defer { acting = false }
        let profile = self.profile, runID = self.runID
        do {
            run = try await app.api.call { try await SchedulesAPI.schedulesCancelWorkflowRun(xHubProfile: profile, workflowRunId: runID, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(app.l10n)
        }
    }
}

/// The Workflows page's list: a row per workflow, its profile's badge when there are several.
struct WorkflowsList: View {
    /// Bumped by the page when a workflow was made or changed elsewhere (the New button).
    var refresh = 0
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: "workflows/\(refresh)") {
            let header = app.currentProfile
            return try await app.api.call {
                try await SchedulesAPI.schedulesListWorkflows(xHubProfile: header, profiles: .all, limit: 200, apiConfiguration: $0)
            }.items
        } content: { workflows, reload in
            List {
                if workflows.isEmpty {
                    EmptyStateView(icon: .workflow, title: l10n("workflows.empty"), message: l10n("workflows.empty_body"))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
                ForEach(workflows, id: \.id) { workflow in
                    NavigationLink {
                        WorkflowDetail(workflow: workflow, changed: reload)
                    } label: {
                        WorkflowRow(workflow: workflow, showProfile: Set(workflows.map(\.profile)).count > 1)
                    }
                    .accessibilityIdentifier("workflow.\(workflow.id)")
                }
            }
            .refreshable { reload() }
        }
    }
}

struct WorkflowRow: View {
    let workflow: Workflow
    let showProfile: Bool
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            HStack(alignment: .firstTextBaseline, spacing: Space.s2) {
                StatusDot(kind: statusKind, label: l10n("workflows.state_\(workflow.status.rawValue)"))
                Text(workflow.name)
                    .font(.system(size: FontSize.sizeMd, weight: .medium))
                    .contentDirection(of: workflow.name)
                    .lineLimit(2)
                if showProfile { ProfileBadge(name: app.profileName(workflow.profile)) }
            }
            Text(([l10n("workflows.counts", ["steps": String(workflow.nodes.count), "runs": String(workflow.runCount)])] + WorkflowLogic.facts(workflow.limits, l10n)).joined(separator: " · "))
                .font(.system(size: FontSize.sizeXs))
                .foregroundStyle(Tone.textMuted)
                .lineLimit(2)
        }
    }

    private var statusKind: StatusDot.Kind {
        switch workflow.status {
        case .running: return .good
        case .waiting: return .warn
        case .error: return .bad
        case .idle: return .neutral
        }
    }
}

/// A workflow: run it (with an input and this run's limits), and its latest runs.
struct WorkflowDetail: View {
    @State var workflow: Workflow
    /// The list reads again after a change here (saved, copied, deleted).
    var changed: () -> Void = {}
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var editing = false
    @State private var copying: WorkflowEditRules.Draft?
    @State private var deleting = false
    @State private var limitsOpen = false
    @Environment(\.l10n) private var l10n
    @State private var input = ""
    @State private var minutes = ""
    @State private var cost = ""
    @State private var stepMinutes = ""
    @State private var busy = false
    @State private var error: String?
    @State private var opened: String?
    @State private var runsKey = 0
    @State private var runs: [WorkflowRun]?
    @State private var find = ""

    var body: some View {
        let (limits, problem) = WorkflowLogic.limits(minutes: minutes, cost: cost, stepMinutes: stepMinutes)
        List {
            Section {
                if let description = workflow.description, !description.isEmpty {
                    Text(description).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                        .contentDirection(of: description)
                }
                HStack(spacing: Space.s2) {
                    ProfileBadge(name: app.profileName(workflow.profile))
                    ForEach(WorkflowLogic.facts(workflow.limits, l10n), id: \.self) { StatusPill(text: $0) }
                }
            }
            Section(l10n("workflows.run_section")) {
                TextField(l10n("workflows.input_hint"), text: $input, axis: .vertical)
                    .lineLimit(1...4)
                    .contentDirection(of: input)
                    .accessibilityIdentifier("workflow.input")
                DisclosureGroup(l10n("workflows.run_limits")) {
                    limitField("workflows.limit_minutes", text: $minutes, placeholder: workflow.limits.maxDurationSeconds.map { String(WorkflowLogic.minutes($0)) }, bad: problem == .duration ? "workflows.limit_minutes_bad" : nil)
                    limitField("workflows.limit_cost_label", text: $cost, placeholder: workflow.limits.maxCost?.amount, bad: problem == .cost ? "workflows.limit_cost_bad" : nil, decimal: true)
                    limitField("workflows.limit_step_label", text: $stepMinutes, placeholder: workflow.limits.stepTimeoutSeconds.map { String(WorkflowLogic.minutes($0)) }, bad: problem == .step ? "workflows.limit_step_bad" : nil)
                }
                if let error { NoticeView(text: error, tone: .danger) }
                Button {
                    Task { await run(limits) }
                } label: {
                    LucideLabel(l10n("workflows.run"), icon: .play, size: 16)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Tone.accent)
                .disabled(busy || problem != nil || workflow.nodes.isEmpty)
                .accessibilityIdentifier("workflow.run")
            }
            Section(l10n("workflows.runs")) {
                if let runs {
                    if runs.isEmpty {
                        Text(l10n("workflows.no_runs")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                    } else {
                        // The runs about one task or one event (§123), matched on the ids a trigger gave them.
                        VStack(alignment: .leading, spacing: 2) {
                            Text(l10n("workflow_editor.run_find")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            TextField(l10n("workflow_editor.run_find"), text: $find, prompt: Text(l10n("workflow_editor.run_find_hint")))
                                .monoField()
                                .accessibilityIdentifier("workflow.runs.find")
                        }
                    }
                    ForEach(WorkflowLogic.find(runs, find), id: \.id) { run in
                        Button { opened = run.id } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text((run.startedAt ?? run.createdAt).shortText(app.language))
                                        .font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.text)
                                    if let task = run.taskId {
                                        Text(l10n("workflow_editor.run_task", ["id": task]))
                                            .font(.system(size: FontSize.sizeXs, design: .monospaced))
                                            .foregroundStyle(Tone.textMuted)
                                            .lineLimit(1)
                                            .environment(\.layoutDirection, .leftToRight)
                                    }
                                    if let line = run.error ?? run.input {
                                        Text(line).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(1)
                                    }
                                }
                                Spacer()
                                StatusPill(text: l10n("workflows.run_\(run.status.rawValue)"), kind: WorkflowLogic.runKind(run.status))
                            }
                        }
                        .accessibilityIdentifier("workflow.run.\(run.id)")
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            Section {
                Button {
                    editing = true
                } label: {
                    LucideLabel(l10n("workflow_editor.edit"), icon: .pencil, size: 16)
                }
                .accessibilityIdentifier("workflow.edit")
                Button {
                    limitsOpen = true
                } label: {
                    LucideLabel(l10n("workflow_editor.limits.title"), icon: .gauge, size: 16)
                }
                .accessibilityIdentifier("workflow.limits")
                Button {
                    copying = WorkflowEditRules.copy(workflow, name: l10n("workflow_editor.copy_name", ["name": workflow.name]))
                } label: {
                    LucideLabel(l10n("workflow_editor.duplicate"), icon: .copy, size: 16)
                }
                .accessibilityIdentifier("workflow.duplicate")
                Button(role: .destructive) {
                    deleting = true
                } label: {
                    LucideLabel(l10n("kit.delete"), icon: .trash, size: 16)
                }
                .accessibilityIdentifier("workflow.delete")
            }
        }
        .navigationTitle(workflow.name)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $opened) { runID in
            WorkflowRunView(workflow: workflow, model: WorkflowRunModel(app: app, profile: workflow.profile, runID: runID))
        }
        .navigationDestination(isPresented: $editing) {
            WorkflowEditorPage(original: workflow, profile: workflow.profile) { saved in
                workflow = saved
                changed()
            }
        }
        .navigationDestination(item: $copying) { start in
            WorkflowEditorPage(original: nil, profile: workflow.profile, start: start) { _ in changed() }
        }
        .sheet(isPresented: $limitsOpen) {
            NavigationStack {
                WorkflowLimitsSheet(workflow: workflow) { saved in
                    workflow = saved
                    changed()
                }
            }
        }
        .confirmDelete(Binding(get: { deleting ? workflow : nil }, set: { if $0 == nil { deleting = false } }), name: { $0.name }, delete: { target in
            try await app.api.call { try await SchedulesAPI.schedulesDeleteWorkflow(xHubProfile: target.profile, workflowId: target.id, apiConfiguration: $0) }
        }, deleted: { _ in
            changed()
            dismiss()
        })
        .onChange(of: opened) { _, value in if value == nil { runsKey += 1 } }
        .task(id: runsKey) { await loadRuns() }
    }

    @ViewBuilder
    private func limitField(_ label: String, text: Binding<String>, placeholder: String?, bad: String?, decimal: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(l10n(label)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            TextField(placeholder ?? "—", text: text)
                .keyboardType(decimal ? .decimalPad : .numberPad)
            if let bad { Text(l10n(bad)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger) }
        }
    }

    private func loadRuns() async {
        let profile = workflow.profile, id = workflow.id
        do {
            runs = try await app.api.call {
                try await SchedulesAPI.schedulesListWorkflowRuns(xHubProfile: profile, workflowId: id, limit: WorkflowLogic.runsPage, apiConfiguration: $0)
            }.items
        } catch {
            runs = []
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func run(_ limits: WorkflowLimitsOverride?) async {
        busy = true
        defer { busy = false }
        let profile = workflow.profile, id = workflow.id
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let request = WorkflowRunRequest(input: text.isEmpty ? nil : text, limits: limits)
        do {
            let accepted = try await app.api.call {
                try await SchedulesAPI.schedulesRunWorkflow(xHubProfile: profile, workflowId: id, idempotencyKey: UUID().uuidString, workflowRunRequest: request, apiConfiguration: $0)
            }
            error = nil
            opened = accepted.workflowRunId
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// A run, read-only and live: its state, a waiting step's question, then each step.
struct WorkflowRunView: View {
    let workflow: Workflow
    @State var model: WorkflowRunModel
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var denying = false
    @State private var reason = ""
    /// A new run started from one of this run's steps.
    @State private var next: String?

    var body: some View {
        List {
            if let run = model.run {
                Section {
                    HStack(spacing: Space.s2) {
                        StatusPill(text: l10n("workflows.run_\(run.status.rawValue)"), kind: WorkflowLogic.runKind(run.status))
                            .accessibilityIdentifier("workflow.run.status")
                        // Where the run is (§127); a value this app does not know is shown as it is.
                        if let phase = run.phase {
                            StatusPill(text: l10n.has("workflow_editor.phase.\(phase)") ? l10n("workflow_editor.phase.\(phase)") : phase)
                                .accessibilityIdentifier("workflow.run.phase")
                        }
                        if let started = run.startedAt {
                            Text(started.shortText(app.language)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                        Spacer()
                        if let cost = run.cost {
                            Text(l10n("workflows.limit_cost", ["amount": cost.amount])).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                    }
                    // What a trigger's event said about the run (§123): not one to act on, its task, its event.
                    if run.filtered == true || run.taskId != nil || run.eventId != nil {
                        HStack(spacing: Space.s2) {
                            if run.filtered == true {
                                StatusPill(text: l10n("workflow_editor.run_filtered"))
                                    .accessibilityIdentifier("workflow.run.filtered")
                            }
                            if let task = run.taskId {
                                StatusPill(text: l10n("workflow_editor.run_task", ["id": task]), kind: .info)
                                    .environment(\.layoutDirection, .leftToRight)
                                    .accessibilityIdentifier("workflow.run.task")
                            }
                            if let event = run.eventId {
                                StatusPill(text: l10n("workflow_editor.run_event", ["id": event]))
                                    .environment(\.layoutDirection, .leftToRight)
                                    .accessibilityIdentifier("workflow.run.event")
                            }
                            Spacer(minLength: 0)
                        }
                    }
                    if let input = run.input, !input.isEmpty {
                        Text(input).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted).contentDirection(of: input)
                    }
                    if let failure = run.error { NoticeView(text: failure, tone: .danger) }
                    if let stopped = run.stoppedBy { NoticeView(text: l10n("workflows.stopped_\(stopped.rawValue)"), tone: .warning) }
                    if let error = model.error { NoticeView(text: error, tone: .danger) }
                }
                if let step = WorkflowLogic.waiting(run) {
                    Section {
                        let title = workflow.nodes.first { $0.id == step.nodeId }?.title ?? step.nodeId
                        NoticeView(text: l10n("workflows.waiting_step", ["step": title]), tone: .warning)
                            .task(id: step.approvalId ?? "") {
                                if let approvalID = step.approvalId { await model.loadQuestion(approvalID) }
                            }
                        if let approvalID = step.approvalId, let question = model.questions[approvalID], !question.isEmpty {
                            Text(question).font(.system(size: FontSize.sizeSm)).contentDirection(of: question)
                                .accessibilityIdentifier("workflow.run.question")
                        }
                        HStack(spacing: Space.s3) {
                            Button {
                                Task { await model.respond(approve: true, reason: nil) }
                            } label: {
                                LucideLabel(l10n("workflows.approve"), icon: .check, size: 16).frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(Tone.accent)
                            .accessibilityIdentifier("workflow.run.approve")
                            Button(role: .destructive) {
                                denying = true
                            } label: {
                                LucideLabel(l10n("workflows.deny"), icon: .x, size: 16).frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                            .accessibilityIdentifier("workflow.run.deny")
                        }
                        .disabled(model.acting)
                    }
                }
                Section(l10n("workflows.steps")) {
                    ForEach(WorkflowLogic.rows(workflow: workflow, run: run)) { row in
                        HStack(spacing: Space.s3) {
                            LucideIcon(WorkflowLogic.icon(row.kind), size: 16).foregroundStyle(Tone.textMuted)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.title).font(.system(size: FontSize.sizeSm, weight: .medium)).contentDirection(of: row.title)
                                Text(stepLine(row)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2)
                            }
                            Spacer()
                            StatusDot(kind: WorkflowLogic.stepKind(row.status), label: l10n("workflows.step_\(row.status.rawValue)"))
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("workflow.step.\(row.nodeID)")
                        .contextMenu {
                            if WorkflowLogic.finished(run) {
                                Button {
                                    Task { if let id = await model.rerun(from: row.nodeID) { next = id } }
                                } label: {
                                    Label { Text(l10n("workflow_editor.editor.rerun_from_here")) } icon: { Image(lucide: .rotateCcw) }
                                }
                            }
                        }
                        .swipeActions {
                            if WorkflowLogic.finished(run) {
                                Button(l10n("workflow_editor.editor.rerun_from_here")) {
                                    Task { if let id = await model.rerun(from: row.nodeID) { next = id } }
                                }
                                .tint(Tone.accent)
                            }
                        }
                    }
                }
                Section {
                    if WorkflowLogic.finished(run) {
                        Text(l10n("workflows.finished") + " " + l10n("workflow_editor.editor.rerun_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    } else {
                        Button(role: .destructive) {
                            Task { await model.cancel() }
                        } label: {
                            LucideLabel(l10n("workflows.cancel_run"), icon: .circleStop, size: 16)
                        }
                        .disabled(model.acting)
                        .accessibilityIdentifier("workflow.run.cancel")
                    }
                }
            } else if let error = model.error {
                NoticeView(text: error, tone: .danger)
            } else {
                SkeletonList(rows: 4).listRowBackground(Color.clear)
            }
        }
        .navigationTitle(workflow.name)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .navigationDestination(item: $next) { runID in
            WorkflowRunView(workflow: workflow, model: WorkflowRunModel(app: app, profile: model.profile, runID: runID))
        }
        .alert(l10n("workflows.deny"), isPresented: $denying) {
            TextField(l10n("workflows.deny_reason"), text: $reason)
            Button(l10n("workflows.deny"), role: .destructive) {
                let why = reason
                reason = ""
                Task { await model.respond(approve: false, reason: why) }
            }
            Button(l10n("common.cancel"), role: .cancel) {}
        }
    }

    private func stepLine(_ row: WorkflowLogic.StepRow) -> String {
        var parts = [l10n("workflows.step_\(row.status.rawValue)")]
        if let step = row.step, let start = step.startedAt {
            let seconds = Int((step.finishedAt ?? Date()).timeIntervalSince(start).rounded())
            parts.append(l10n("workflows.seconds", ["n": String(max(0, seconds))]))
        }
        if let text = row.step?.error ?? row.step?.output?.split(separator: "\n").first.map(String.init) {
            parts.append(text)
        }
        return parts.joined(separator: " · ")
    }
}

/// A workflow's saved limits (the web's WorkflowLimits form): the longest run, the most it may
/// cost, the longest step. An empty field has no limit. A run may still set its own.
struct WorkflowLimitsSheet: View {
    let workflow: Workflow
    let saved: (Workflow) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var minutes = ""
    @State private var cost = ""
    @State private var stepMinutes = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        let (limits, problem) = WorkflowEditRules.limits(minutes: minutes, cost: cost, stepMinutes: stepMinutes)
        Form {
            Section {
                field("workflows.limit_minutes", $minutes, bad: problem == .duration ? "workflows.limit_minutes_bad" : nil)
                    .accessibilityIdentifier("workflow.limits.minutes")
                field("workflows.limit_cost_label", $cost, bad: problem == .cost ? "workflows.limit_cost_bad" : nil, decimal: true)
                    .accessibilityIdentifier("workflow.limits.cost")
                field("workflows.limit_step_label", $stepMinutes, bad: problem == .step ? "workflows.limit_step_bad" : nil)
                    .accessibilityIdentifier("workflow.limits.step")
            } footer: {
                Text(l10n("workflow_editor.limits.hint"))
            }
            if let error { Section { NoticeView(text: error, tone: .danger) } }
        }
        .navigationTitle(l10n("workflow_editor.limits.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(l10n("common.save")) { Task { await save(limits ?? WorkflowLimits()) } }
                    .disabled(busy || problem != nil)
                    .accessibilityIdentifier("workflow.limits.save")
            }
        }
        .onAppear {
            minutes = workflow.limits.maxDurationSeconds.map { String(WorkflowLogic.minutes($0)) } ?? ""
            cost = workflow.limits.maxCost?.amount ?? ""
            stepMinutes = workflow.limits.stepTimeoutSeconds.map { String(WorkflowLogic.minutes($0)) } ?? ""
        }
    }

    private func field(_ label: String, _ text: Binding<String>, bad: String?, decimal: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(l10n(label)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            TextField(l10n("workflow_editor.limits.none"), text: text)
                .keyboardType(decimal ? .decimalPad : .numberPad)
            if let bad { Text(l10n(bad)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger) }
        }
    }

    private func save(_ limits: WorkflowLimits) async {
        busy = true
        defer { busy = false }
        let profile = workflow.profile, id = workflow.id
        do {
            let result = try await app.api.call {
                try await SchedulesAPI.schedulesUpdateWorkflow(xHubProfile: profile, workflowId: id, workflowWrite: WorkflowWrite(limits: limits), apiConfiguration: $0)
            }
            saved(result)
            dismiss()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

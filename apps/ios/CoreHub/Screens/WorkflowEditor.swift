// A workflow drawn on the phone (the web draws it on a canvas, WorkflowEditor.tsx): its name, its
// steps as a list — each opened to edit what the engine does with it and what follows it — and
// the hub's check of the drawing as it changes (`schedules.validateWorkflow`). Saving is
// `createWorkflow` / `updateWorkflow` with the contract's `WorkflowWrite`, so a workflow made here
// opens on the web's canvas, and one drawn there is edited here. Rules: WorkflowEditRules.swift.
import CoreHubClient
import SwiftUI
import UIKit

struct WorkflowEditorPage: View {
    /// The saved workflow, or nil for a new one.
    let original: Workflow?
    /// The profile the workflow lives in (a new one: the selector's).
    let profile: String
    /// Called with what the hub saved.
    let saved: (Workflow) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var draft: WorkflowEditRules.Draft
    @State private var validation: WorkflowValidation?
    @State private var checking = false
    @State private var checkError: String?
    @State private var saving = false
    @State private var error: String?
    @State private var removing: String?
    /// The workflow's inbound triggers (§123), shown read-only; empty on an older hub.
    @State private var triggers: [WorkflowTrigger] = []

    init(original: Workflow?, profile: String, start: WorkflowEditRules.Draft? = nil, saved: @escaping (Workflow) -> Void) {
        self.original = original
        self.profile = profile
        self.saved = saved
        _draft = State(initialValue: start ?? WorkflowEditRules.draft(original))
    }

    var body: some View {
        Form {
            Section {
                TextField(l10n("workflow_editor.editor.name"), text: $draft.name)
                    .contentDirection(of: draft.name)
                    .accessibilityIdentifier("workflow.edit.name")
                TextField(l10n("workflow_editor.description"), text: $draft.description, axis: .vertical)
                    .lineLimit(1...4)
                    .contentDirection(of: draft.description)
                    .accessibilityIdentifier("workflow.edit.description")
            }
            Section {
                checkRow
                ForEach(WorkflowEditRules.general(validation), id: \.self) { issue in
                    IssueLine(issue: issue, problem: WorkflowEditRules.isProblem(validation, issue))
                }
                if let error { NoticeView(text: error, tone: .danger) }
            }
            Section {
                if draft.nodes.isEmpty {
                    Text(l10n("workflow_editor.no_steps")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                }
                ForEach(draft.nodes, id: \.id) { node in
                    NavigationLink {
                        WorkflowStepPage(draft: $draft, nodeID: node.id, profile: profile, validation: validation, app: app)
                    } label: {
                        stepRow(node)
                    }
                    .swipeActions {
                        Button(l10n("workflow_editor.editor.delete_step"), role: .destructive) { removing = node.id }
                    }
                    .contextMenu {
                        Button { WorkflowEditRules.move(node.id, by: -1, in: &draft) } label: {
                            Label { Text(l10n("workflow_editor.move_up")) } icon: { Image(lucide: .chevronUp) }
                        }
                        Button { WorkflowEditRules.move(node.id, by: 1, in: &draft) } label: {
                            Label { Text(l10n("workflow_editor.move_down")) } icon: { Image(lucide: .chevronDown) }
                        }
                        Button(role: .destructive) { removing = node.id } label: {
                            Label { Text(l10n("workflow_editor.editor.delete_step")) } icon: { Image(lucide: .trash) }
                        }
                    }
                    .accessibilityIdentifier("workflow.edit.step.\(node.id)")
                }
                Menu {
                    ForEach(WorkflowEditRules.kinds, id: \.self) { kind in
                        Button {
                            WorkflowEditRules.add(kind, title: l10n("workflow_editor.kinds.\(kind.rawValue)"), to: &draft)
                        } label: {
                            Label { Text(l10n("workflow_editor.kinds.\(kind.rawValue)")) } icon: { Image(lucide: WorkflowLogic.icon(kind)) }
                        }
                        .accessibilityIdentifier("workflow.edit.add.\(kind.rawValue)")
                    }
                } label: {
                    LucideLabel(l10n("workflow_editor.editor.palette"), icon: .plus, size: 16)
                }
                .accessibilityIdentifier("workflow.edit.add")
            } header: {
                Text(l10n("workflows.steps"))
            } footer: {
                Text(l10n("workflow_editor.steps_hint"))
            }
            if !triggers.isEmpty {
                Section {
                    ForEach(triggers, id: \.id) { trigger in
                        TriggerRow(trigger: trigger, url: WorkflowEditRules.triggerURL(hub: hub, path: trigger.path))
                    }
                } header: {
                    Text(l10n("workflow_editor.triggers.title"))
                } footer: {
                    Text(l10n("workflow_editor.triggers.read_only"))
                }
            }
        }
        .navigationTitle(original == nil ? l10n("workflow_editor.new") : l10n("workflow_editor.edit"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(l10n("common.save")) { Task { await save() } }
                    .disabled(saving || !WorkflowEditRules.canSave(draft, validation: validation))
                    .accessibilityIdentifier("workflow.edit.save")
            }
        }
        .task(id: draft) { await check() }
        .task { await loadTriggers() }
        .alert(
            l10n("workflow_editor.remove_step_title"),
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })
        ) {
            Button(l10n("common.cancel"), role: .cancel) { removing = nil }
            Button(l10n("workflow_editor.editor.delete_step"), role: .destructive) {
                if let id = removing { WorkflowEditRules.remove(id, from: &draft) }
                removing = nil
            }
        }
    }

    @ViewBuilder
    private var checkRow: some View {
        HStack(spacing: Space.s2) {
            if checking {
                ProgressView()
                Text(l10n("workflow_editor.editor.checking")).foregroundStyle(Tone.textMuted)
            } else if let checkError {
                NoticeView(text: l10n("workflow_editor.editor.check_failed", ["message": checkError]), tone: .warning)
            } else if let validation {
                if validation.valid && validation.warnings.isEmpty {
                    StatusPill(text: l10n("workflow_editor.editor.valid"), kind: .good)
                } else {
                    if !validation.problems.isEmpty {
                        StatusPill(text: l10n("workflow_editor.editor.problems", ["count": String(validation.problems.count)]), kind: .bad)
                    }
                    if !validation.warnings.isEmpty {
                        StatusPill(text: l10n("workflow_editor.editor.warnings", ["count": String(validation.warnings.count)]), kind: .warn)
                    }
                }
            }
            Spacer()
        }
        .font(.system(size: FontSize.sizeSm))
        .accessibilityIdentifier("workflow.edit.check")
        if draft.name.trimmingCharacters(in: .whitespaces).isEmpty {
            Text(l10n("workflow_editor.editor.name_required")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
        } else if validation?.valid == false {
            Text(l10n("workflow_editor.editor.save_blocked")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
        }
    }

    private func stepRow(_ node: WorkflowNode) -> some View {
        let issues = WorkflowEditRules.issues(validation, node: node.id)
        let next = draft.edges.filter { $0.from == node.id }.compactMap { edge in draft.nodes.first { $0.id == edge.to }?.title }
        return HStack(spacing: Space.s3) {
            LucideIcon(WorkflowLogic.icon(node.kind), size: 16).foregroundStyle(Tone.textMuted)
            VStack(alignment: .leading, spacing: 2) {
                Text(node.title.isEmpty ? node.id : node.title)
                    .font(.system(size: FontSize.sizeSm, weight: .medium))
                    .contentDirection(of: node.title)
                Text(([l10n("workflow_editor.kinds.\(node.kind.rawValue)")] + (next.isEmpty ? [] : [l10n("workflow_editor.then", ["steps": next.joined(separator: "، ")])])).joined(separator: " · "))
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
                    .lineLimit(2)
            }
            Spacer()
            if !issues.isEmpty {
                let problem = issues.contains { WorkflowEditRules.isProblem(validation, $0) }
                StatusDot(kind: problem ? .bad : .warn, label: l10n("workflow_editor.editor.issue_count", ["count": String(issues.count)]))
            }
        }
    }

    private var hub: String { app.credentials?.hubURL.absoluteString ?? "" }

    /// The triggers of a saved workflow. An older hub has none to list: nothing is shown.
    private func loadTriggers() async {
        guard let id = original?.id else { return }
        let profile = profile
        let listed = try? await app.api.call {
            try await SchedulesAPI.schedulesListWorkflowTriggers(xHubProfile: profile, workflowId: id, apiConfiguration: $0)
        }
        triggers = listed?.items ?? []
    }

    /// The hub checks the drawing a moment after each change.
    private func check() async {
        try? await Task.sleep(for: .milliseconds(400))
        if Task.isCancelled { return }
        guard !draft.nodes.isEmpty || original != nil else {
            validation = nil
            checkError = nil
            return
        }
        checking = true
        defer { checking = false }
        let body = WorkflowEditRules.check(draft), profile = profile
        do {
            validation = try await app.api.call { try await SchedulesAPI.schedulesValidateWorkflow(xHubProfile: profile, workflowCheck: body, apiConfiguration: $0) }
            checkError = nil
        } catch is CancellationError {
            return
        } catch {
            if Task.isCancelled { return }
            checkError = HubFailure(error).describe(l10n)
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        let profile = profile
        do {
            let result: Workflow
            if let original {
                let write = WorkflowEditRules.write(draft, clearing: true), id = original.id
                result = try await app.api.call { try await SchedulesAPI.schedulesUpdateWorkflow(xHubProfile: profile, workflowId: id, workflowWrite: write, apiConfiguration: $0) }
            } else {
                let write = WorkflowEditRules.write(draft, clearing: false)
                result = try await app.api.call { try await SchedulesAPI.schedulesCreateWorkflow(xHubProfile: profile, workflowWrite: write, apiConfiguration: $0) }
            }
            error = nil
            saved(result)
            dismiss()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// One finding of the hub's check, a problem or a warning.
struct IssueLine: View {
    let issue: WorkflowIssue
    let problem: Bool
    @Environment(\.l10n) private var l10n

    var body: some View {
        NoticeView(text: WorkflowEditRules.describe(issue, l10n), tone: problem ? .danger : .warning)
    }
}

/// One step: its title, what the engine does with it, what follows it, and removing it.
struct WorkflowStepPage: View {
    @Binding var draft: WorkflowEditRules.Draft
    let nodeID: String
    let profile: String
    let validation: WorkflowValidation?

    init(draft: Binding<WorkflowEditRules.Draft>, nodeID: String, profile: String, validation: WorkflowValidation?, app: AppModel) {
        _draft = draft
        self.nodeID = nodeID
        self.profile = profile
        self.validation = validation
        _controls = State(initialValue: ChatControlsModel(app: app))
    }
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var target: String?
    @State private var route: WorkflowEdge.Route = .success
    @State private var rawCondition = false
    @State private var minutes = false
    @State private var pickingModel = false
    /// The profile's model catalogue, as the chat's chips read it.
    @State private var controls: ChatControlsModel

    private var index: Int? { draft.nodes.firstIndex { $0.id == nodeID } }

    var body: some View {
        Form {
            if let index {
                let node = draft.nodes[index]
                Section {
                    TextField(l10n("workflow_editor.form.title"), text: $draft.nodes[index].title)
                        .contentDirection(of: node.title)
                        .accessibilityIdentifier("workflow.step.title")
                    Text(l10n("workflow_editor.form.id") + ": " + node.id)
                        .font(.system(size: FontSize.sizeXs, design: .monospaced))
                        .foregroundStyle(Tone.textMuted)
                } footer: {
                    Text(l10n("workflow_editor.form.id_hint", ["path": "{{steps.\(node.id).output}}"]))
                }
                let issues = WorkflowEditRules.issues(validation, node: node.id)
                if !issues.isEmpty {
                    Section {
                        ForEach(issues, id: \.self) { IssueLine(issue: $0, problem: WorkflowEditRules.isProblem(validation, $0)) }
                    }
                }
                kindSection(index, node)
                if node.kind != .approval {
                    Section {
                        Toggle(l10n("workflow_editor.form.approval_required"), isOn: $draft.nodes[index].approvalRequired)
                            .accessibilityIdentifier("workflow.step.gate")
                    }
                }
                connections(node)
                Section {
                    Button(l10n("workflow_editor.editor.delete_step"), role: .destructive) {
                        // Leave the page first: its fields must not read a step that is gone.
                        let id = nodeID, drawing = $draft
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { WorkflowEditRules.remove(id, from: &drawing.wrappedValue) }
                    }
                    .accessibilityIdentifier("workflow.step.delete")
                }
            }
        }
        .navigationTitle(index.map { draft.nodes[$0].title.isEmpty ? nodeID : draft.nodes[$0].title } ?? nodeID)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if let index, draft.nodes[index].kind == .condition { rawCondition = WorkflowEditRules.split(draft.nodes[index].input ?? "") == nil }
            if let index, draft.nodes[index].kind == .delay, let seconds = Int((draft.nodes[index].input ?? "").trimmingCharacters(in: .whitespaces)) {
                minutes = seconds >= 60 && seconds % 60 == 0
            }
        }
    }

    private func input(_ index: Int) -> Binding<String> {
        Binding(get: { draft.nodes[index].input ?? "" }, set: { draft.nodes[index].input = $0 })
    }

    @ViewBuilder
    private func kindSection(_ index: Int, _ node: WorkflowNode) -> some View {
        switch node.kind {
        case .agent:
            Section {
                let agents = app.agentDirectory.agents(profile)
                Picker(l10n("workflow_editor.form.agent"), selection: Binding(get: { draft.nodes[index].agentId }, set: { draft.nodes[index].agentId = $0 })) {
                    Text(l10n("workflow_editor.form.agent_none")).tag(String?.none)
                    ForEach(agents, id: \.id) { agent in Text(agent.name).tag(String?.some(agent.id)) }
                }
                .accessibilityIdentifier("workflow.step.agent")
                // The profile's chat models, as the chat's model chip offers them; none = the agent's own.
                Button {
                    controls.load(profile: profile, agentID: draft.nodes[index].agentId)
                    pickingModel = true
                } label: {
                    LabeledContent(l10n("workflow_editor.form.model")) {
                        Text(WorkflowEditRules.modelLabel(draft.nodes[index].model, options: controls.models) ?? l10n("workflow_editor.form.model_default"))
                            .lineLimit(1)
                    }
                }
                .accessibilityIdentifier("workflow.step.model")
                .sheet(isPresented: $pickingModel) {
                    ModelPickerSheet(options: controls.models, loaded: controls.modelsLoaded, current: draft.nodes[index].model, allowDefault: true) { value in
                        draft.nodes[index].model = value
                        draft.nodes[index].provider = nil
                    }
                }
            } header: {
                Text(l10n("workflow_editor.form.agent"))
            }
            templateSection(index, label: "workflow_editor.form.prompt", footer: "workflow_editor.form.attachments_note")
        case .condition:
            conditionSection(index)
        case .delay:
            delaySection(index)
        case .notify:
            templateSection(index, label: "workflow_editor.form.notify_text", footer: "workflow_editor.form.notify_to")
        case .approval:
            templateSection(index, label: "workflow_editor.form.approval_question", footer: "workflow_editor.form.approval_who")
        }
    }

    /// A text the engine renders, with the values a step can read offered for insertion.
    @ViewBuilder
    private func templateSection(_ index: Int, label: String, footer: String) -> some View {
        Section {
            TextField(l10n(label), text: input(index), axis: .vertical)
                .lineLimit(3...10)
                .contentDirection(of: draft.nodes[index].input ?? "")
                .accessibilityIdentifier("workflow.step.text")
            Menu {
                Button { append("{{input}}", to: index) } label: { Text(l10n("workflow_editor.form.insert_input")) }
                let earlier = WorkflowEditRules.upstream(draft, of: nodeID)
                ForEach(earlier, id: \.id) { step in
                    Button { append("{{steps.\(step.id).output}}", to: index) } label: {
                        Text(l10n("workflow_editor.form.insert_step", ["name": step.title.isEmpty ? step.id : step.title]))
                    }
                }
            } label: {
                LucideLabel(l10n("workflow_editor.form.insert"), icon: .plus, size: 14)
            }
            .accessibilityIdentifier("workflow.step.insert")
        } header: {
            Text(l10n(label))
        } footer: {
            Text(l10n(footer))
        }
    }

    private func append(_ text: String, to index: Int) {
        let current = draft.nodes[index].input ?? ""
        draft.nodes[index].input = current.isEmpty || current.hasSuffix(" ") || current.hasSuffix("\n") ? current + text : current + " " + text
    }

    @ViewBuilder
    private func conditionSection(_ index: Int) -> some View {
        let parts = WorkflowEditRules.split(draft.nodes[index].input ?? "")
        let rules = WorkflowEditRules.ruleLines(draft.nodes[index].rules)
        if !rules.lines.isEmpty {
            // Several rules (§123): shown and kept as they are; edited on the web.
            Section {
                ForEach(Array(rules.lines.enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .font(.system(size: FontSize.sizeXs, design: .monospaced))
                        .environment(\.layoutDirection, .leftToRight)
                        .accessibilityIdentifier("workflow.step.rule")
                }
            } header: {
                Text(l10n(rules.match == "any" ? "workflow_editor.rules.any" : "workflow_editor.rules.all"))
            } footer: {
                Text(l10n("workflow_editor.rules.read_only"))
            }
        } else if rawCondition || parts == nil {
            Section {
                TextField(l10n("workflow_editor.form.condition_text"), text: Binding(
                    get: { draft.nodes[index].input ?? "" },
                    set: { value in
                        draft.nodes[index].input = value
                        if WorkflowEditRules.split(value) != nil { rawCondition = false }
                    }
                ))
                .monoField()
                .environment(\.layoutDirection, .leftToRight)
                .accessibilityIdentifier("workflow.step.condition_text")
            } header: {
                Text(l10n("workflow_editor.form.condition_text"))
            } footer: {
                Text(l10n("workflow_editor.form.condition_raw_hint") + " " + l10n("workflow_editor.form.condition_routes_hint"))
            }
        } else if let parts {
            let set: (WorkflowEditRules.Condition) -> Void = { draft.nodes[index].input = WorkflowEditRules.join($0) }
            Section {
                TextField(l10n("workflow_editor.form.condition_path"), text: Binding(get: { parts.path }, set: { var next = parts; next.path = $0; set(next) }))
                    .monoField()
                    .environment(\.layoutDirection, .leftToRight)
                    .accessibilityIdentifier("workflow.step.condition_path")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Space.s1) {
                        ForEach(["input"] + WorkflowEditRules.upstream(draft, of: nodeID).map { "steps.\($0.id).output" }, id: \.self) { path in
                            Button(path) { var next = parts; next.path = path; set(next) }
                                .font(.system(size: FontSize.sizeXs, design: .monospaced))
                                .buttonStyle(.bordered)
                        }
                    }
                }
                Picker(l10n("workflow_editor.form.condition_operator"), selection: Binding(get: { parts.op }, set: { var next = parts; next.op = $0; set(next) })) {
                    ForEach(WorkflowEditRules.operators, id: \.self) { op in
                        Text(l10n("workflow_editor.form.operators.\(WorkflowEditRules.operatorKey[op] ?? "eq")")).tag(op)
                    }
                }
                .accessibilityIdentifier("workflow.step.condition_operator")
                if !WorkflowEditRules.unary.contains(parts.op) {
                    TextField(l10n("workflow_editor.form.condition_value"), text: Binding(get: { parts.value }, set: { var next = parts; next.value = $0; set(next) }))
                        .accessibilityIdentifier("workflow.step.condition_value")
                }
                Text(draft.nodes[index].input ?? "")
                    .font(.system(size: FontSize.sizeXs, design: .monospaced))
                    .foregroundStyle(Tone.textMuted)
                    .environment(\.layoutDirection, .leftToRight)
            } header: {
                Text(l10n("workflow_editor.kinds.condition"))
            } footer: {
                Text(l10n("workflow_editor.form.condition_path_hint") + " " + l10n("workflow_editor.form.condition_routes_hint"))
            }
        }
    }

    @ViewBuilder
    private func delaySection(_ index: Int) -> some View {
        let text = (draft.nodes[index].input ?? "").trimmingCharacters(in: .whitespaces)
        if text.contains("{{") {
            Section {
                TextField(l10n("workflow_editor.form.delay_amount"), text: input(index)).monoField()
            } footer: {
                Text(l10n("workflow_editor.form.delay_template", ["value": text]))
            }
        } else {
            let factor = minutes ? 60 : 1
            Section {
                HStack {
                    TextField(l10n("workflow_editor.form.delay_amount"), text: Binding(
                        get: { Int(text).map { String($0 / factor) } ?? text },
                        set: { value in
                            let digits = value.filter(\.isNumber)
                            draft.nodes[index].input = digits.isEmpty ? "" : String((Int(digits) ?? 0) * factor)
                        }
                    ))
                    .keyboardType(.numberPad)
                    .accessibilityIdentifier("workflow.step.delay")
                    Picker(l10n("workflow_editor.form.delay_unit"), selection: $minutes) {
                        Text(l10n("workflow_editor.form.seconds")).tag(false)
                        Text(l10n("workflow_editor.form.minutes")).tag(true)
                    }
                    .labelsHidden()
                }
            } header: {
                Text(l10n("workflow_editor.form.delay_amount"))
            } footer: {
                Text(l10n("workflow_editor.form.delay_hint"))
            }
        }
    }

    private func routeName(_ route: WorkflowEdge.Route, from node: WorkflowNode) -> String {
        l10n(node.kind == .condition ? "workflow_editor.condition_routes.\(route.rawValue)" : "workflow_editor.routes.\(route.rawValue)")
    }

    @ViewBuilder
    private func connections(_ node: WorkflowNode) -> some View {
        let outgoing = draft.edges.filter { $0.from == node.id }
        let others = draft.nodes.filter { $0.id != node.id }
        Section {
            if outgoing.isEmpty {
                Text(l10n("workflow_editor.editor.no_connections")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
            }
            ForEach(outgoing, id: \.id) { edge in
                let name = draft.nodes.first { $0.id == edge.to }.map { $0.title.isEmpty ? $0.id : $0.title } ?? edge.to
                HStack {
                    Text(name).font(.system(size: FontSize.sizeSm)).contentDirection(of: name, fill: false)
                    StatusPill(text: routeName(edge.route, from: node), kind: edge.route == .failure ? .bad : edge.route == .success ? .good : .neutral)
                    Spacer()
                    Button {
                        draft.edges.removeAll { $0.id == edge.id }
                    } label: {
                        LucideIcon(.x, size: 14).tapTarget()
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(l10n("workflow_editor.editor.delete_edge") + ": " + name)
                }
                .accessibilityIdentifier("workflow.step.edge.\(edge.id)")
                ForEach(WorkflowEditRules.edgeIssues(validation, edge: edge.id), id: \.self) { IssueLine(issue: $0, problem: WorkflowEditRules.isProblem(validation, $0)) }
            }
            if !others.isEmpty {
                Picker(l10n("workflow_editor.editor.connect_target"), selection: $target) {
                    Text("—").tag(String?.none)
                    ForEach(others, id: \.id) { other in Text(other.title.isEmpty ? other.id : other.title).tag(String?.some(other.id)) }
                }
                .accessibilityIdentifier("workflow.step.connect_target")
                Picker(l10n("workflow_editor.editor.connect_route"), selection: $route) {
                    ForEach([WorkflowEdge.Route.success, .failure, .always], id: \.self) { value in
                        Text(routeName(value, from: node)).tag(value)
                    }
                }
                Button(l10n("workflow_editor.editor.connect_add")) {
                    if let target { WorkflowEditRules.connect(node.id, to: target, route: route, in: &draft) }
                    target = nil
                }
                .disabled(target == nil)
                .accessibilityIdentifier("workflow.step.connect")
            }
        } header: {
            Text(l10n("workflow_editor.editor.connections"))
        }
    }
}


/// One inbound trigger of a workflow (§123): its sender, its address to copy, and whether a
/// secret is stored. Changed on the web.
private struct TriggerRow: View {
    let trigger: WorkflowTrigger
    let url: String
    @Environment(\.l10n) private var l10n
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            HStack(spacing: Space.s1) {
                Text(trigger.name).font(.system(size: FontSize.sizeMd, weight: .semibold))
                StatusPill(text: l10n("workflow_editor.triggers.presets.\(trigger.preset.rawValue)"))
                if !trigger.enabled { StatusPill(text: l10n("workflow_editor.triggers.off"), kind: .warn) }
            }
            HStack {
                Text(url).font(.system(size: FontSize.sizeXs, design: .monospaced)).lineLimit(2).textSelection(.enabled)
                    .accessibilityIdentifier("workflow.trigger.\(trigger.id).url")
                Spacer()
                Button {
                    UIPasteboard.general.string = url
                    copied = true
                } label: {
                    Text(copied ? l10n("workflow_editor.triggers.copied") : l10n("workflow_editor.triggers.copy"))
                        .font(.system(size: FontSize.sizeXs))
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("workflow.trigger.\(trigger.id).copy")
            }
            .environment(\.layoutDirection, .leftToRight)
            Text(trigger.secretStored ? l10n("workflow_editor.triggers.secret_stored") : l10n("workflow_editor.triggers.secret_missing"))
                .font(.system(size: FontSize.sizeXs))
                .foregroundStyle(Tone.textMuted)
            if !trigger.events.isEmpty {
                Text(trigger.events.joined(separator: ", "))
                    .font(.system(size: FontSize.sizeXs, design: .monospaced))
                    .foregroundStyle(Tone.textMuted)
                    .environment(\.layoutDirection, .leftToRight)
            }
        }
    }
}

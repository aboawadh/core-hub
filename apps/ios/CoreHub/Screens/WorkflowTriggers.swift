// A workflow's inbound triggers on the phone (DECISIONS §123), as the web's WorkflowTriggers.tsx
// has them: each is an address on this hub another service (ClickUp first) posts events to. A
// card per trigger gives the address to copy, takes the secret the sender made (never shown
// again: "[stored]"), the events it takes, a "Send test event" that goes through the whole
// receiving path without contacting anyone, and the delivery log with a way to each run. A hub
// older than triggers answers 404: the section is then left out, quietly.
import CoreHubClient
import SwiftUI
import UIKit

/// The triggers' rules, apart from the views so they are unit-tested.
enum WorkflowTriggerRules {
    static let presets: [WorkflowTriggerPreset] = [.clickup, .github, .genericHmac, .token]

    /// The task events ClickUp sends, offered as the trigger's filter (the web's `CLICKUP_EVENTS`).
    static let clickUpEvents = [
        "taskCreated",
        "taskUpdated",
        "taskDeleted",
        "taskStatusUpdated",
        "taskAssigneeUpdated",
        "taskPriorityUpdated",
        "taskDueDateUpdated",
        "taskTagUpdated",
        "taskMoved",
        "taskCommentPosted",
        "taskCommentUpdated",
    ]

    /// "Add trigger": named after its sender; a ClickUp one takes new tasks and status changes.
    static func newTrigger(_ preset: WorkflowTriggerPreset, name: String) -> WorkflowTriggerWrite {
        WorkflowTriggerWrite(name: name, preset: preset, events: preset == .clickup ? ["taskCreated", "taskStatusUpdated"] : nil)
    }

    /// Typed events, comma-separated: each trimmed, the empty ones dropped.
    static func events(fromText text: String) -> [String] {
        text.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    /// A ClickUp event ticked on or off, the others kept in their order.
    static func toggle(_ event: String, on: Bool, in events: [String]) -> [String] {
        if on { return events.contains(event) ? events : events + [event] }
        return events.filter { $0 != event }
    }

    /// The header a signed or token webhook reads; empty goes back to the preset's default (null).
    static func headerPatch(_ text: String) -> WorkflowTriggerPatch {
        let value = text.trimmingCharacters(in: .whitespaces)
        return value.isEmpty ? WorkflowTriggerPatch(sendNull: [.signatureHeader]) : WorkflowTriggerPatch(signatureHeader: value)
    }

    /// The text before a signature (`sha256=`); empty is none (null).
    static func prefixPatch(_ text: String) -> WorkflowTriggerPatch {
        let value = text.trimmingCharacters(in: .whitespaces)
        return value.isEmpty ? WorkflowTriggerPatch(sendNull: [.signaturePrefix]) : WorkflowTriggerPatch(signaturePrefix: value)
    }

    static func encodingPatch(_ encoding: WorkflowTrigger.SignatureEncoding) -> WorkflowTriggerPatch {
        WorkflowTriggerPatch(signatureEncoding: WorkflowTriggerPatch.SignatureEncoding(rawValue: encoding.rawValue))
    }

    /// A new secret; nothing when the field is empty (Save is off then).
    static func secretPatch(_ text: String) -> WorkflowTriggerPatch? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : WorkflowTriggerPatch(secret: value)
    }

    static func headerPlaceholder(_ preset: WorkflowTriggerPreset) -> String {
        preset == .token ? "X-Webhook-Token" : "X-Signature"
    }

    /// A test event needs a stored secret and the trigger on.
    static func canTest(_ trigger: WorkflowTrigger) -> Bool { trigger.secretStored && trigger.enabled }

    /// "Send test event": the typed event, or the trigger's first when none is typed (null).
    static func test(event: String) -> WorkflowTriggerTest {
        let value = event.trimmingCharacters(in: .whitespaces)
        return value.isEmpty ? WorkflowTriggerTest(sendNull: [.event]) : WorkflowTriggerTest(event: value)
    }

    /// `event · task <id> · #<event id>`, when the delivery names an event or a task.
    static func line(_ delivery: WorkflowTriggerDelivery) -> String? {
        guard delivery.event != nil || delivery.taskId != nil else { return nil }
        return [delivery.event, delivery.taskId.map { "task \($0)" }, delivery.eventId.map { "#\($0)" }]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }

    /// The web's DELIVERY_TONE.
    static func kind(_ status: WorkflowTriggerDeliveryStatus) -> StatusPill.Kind {
        switch status {
        case .received, .runStarted: return .info
        case .runSucceeded: return .good
        case .signatureRejected, .runFailed: return .bad
        case .duplicate, .filteredOut: return .neutral
        }
    }

    /// A hub from before triggers (or before one of their calls) answers 404: hidden, not an error.
    static func unsupported(_ failure: HubFailure) -> Bool {
        failure.kind == .http && (failure.status == 404 || failure.status == 501)
    }
}

/// The Triggers section of a saved workflow's editor (a new one is asked to be saved first).
struct WorkflowTriggersSection: View {
    let workflowID: String?
    let profile: String
    /// "Open the run" of a delivery.
    let openRun: (String) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var triggers: [WorkflowTrigger] = []
    @State private var supported = true
    @State private var error: String?
    @State private var preset: WorkflowTriggerPreset = .clickup
    @State private var adding = false

    var body: some View {
        if supported {
            Section {
                if let workflowID {
                    if let error { NoticeView(text: error, tone: .danger) }
                    ForEach(triggers, id: \.id) { trigger in
                        TriggerCard(trigger: trigger, profile: profile, hub: hub, openRun: openRun, changed: { saved in
                            if let index = triggers.firstIndex(where: { $0.id == saved.id }) { triggers[index] = saved }
                        }, deleted: {
                            triggers.removeAll { $0.id == trigger.id }
                        })
                    }
                    Picker(l10n("workflow_editor.triggers.preset"), selection: $preset) {
                        ForEach(WorkflowTriggerRules.presets, id: \.self) { value in
                            Text(l10n("workflow_editor.triggers.presets.\(value.rawValue)")).tag(value)
                        }
                    }
                    .accessibilityIdentifier("workflow.trigger.preset")
                    Button {
                        Task { await add(workflowID) }
                    } label: {
                        LucideLabel(l10n("workflow_editor.triggers.add"), icon: .plus, size: 16)
                    }
                    .disabled(adding)
                    .accessibilityIdentifier("workflow.trigger.add")
                } else {
                    Text(l10n("workflow_editor.triggers.save_first"))
                        .font(.system(size: FontSize.sizeSm))
                        .foregroundStyle(Tone.textMuted)
                }
            } header: {
                Text(l10n("workflow_editor.triggers.title"))
            } footer: {
                Text(l10n("workflow_editor.triggers.hint"))
            }
            .task(id: workflowID ?? "") { await load() }
        }
    }

    private var hub: String { app.credentials?.hubURL.absoluteString ?? "" }

    private func load() async {
        guard let workflowID else { return }
        let profile = profile
        do {
            triggers = try await app.api.call {
                try await SchedulesAPI.schedulesListWorkflowTriggers(xHubProfile: profile, workflowId: workflowID, apiConfiguration: $0)
            }.items
            error = nil
        } catch is CancellationError {
            return
        } catch {
            let failure = HubFailure(error)
            if WorkflowTriggerRules.unsupported(failure) {
                supported = false
            } else {
                self.error = failure.describe(l10n)
            }
        }
    }

    private func add(_ workflowID: String) async {
        adding = true
        defer { adding = false }
        let profile = profile
        let body = WorkflowTriggerRules.newTrigger(preset, name: l10n("workflow_editor.triggers.presets.\(preset.rawValue)"))
        do {
            let made = try await app.api.call {
                try await SchedulesAPI.schedulesCreateWorkflowTrigger(xHubProfile: profile, workflowId: workflowID, workflowTriggerWrite: body, apiConfiguration: $0)
            }
            triggers.append(made)
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// One trigger, open by default: on/off, its address, its secret, its sender's details, its
/// events, a test event, what arrived, and deleting it.
private struct TriggerCard: View {
    let trigger: WorkflowTrigger
    let profile: String
    let hub: String
    let openRun: (String) -> Void
    let changed: (WorkflowTrigger) -> Void
    let deleted: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var open = true
    @State private var copied = false
    @State private var secret = ""
    @State private var header: String
    @State private var prefix: String
    @State private var eventsText: String
    @State private var testEvent = ""
    @State private var tested: WorkflowTriggerDelivery?
    @State private var testing = false
    @State private var saving = false
    @State private var error: String?
    @State private var deliveries: [WorkflowTriggerDelivery]?
    @State private var deliveriesKey = 0
    @State private var confirmingDelete = false
    @FocusState private var focus: Field?

    private enum Field: Hashable { case header, prefix, events }

    init(trigger: WorkflowTrigger, profile: String, hub: String, openRun: @escaping (String) -> Void,
         changed: @escaping (WorkflowTrigger) -> Void, deleted: @escaping () -> Void) {
        self.trigger = trigger
        self.profile = profile
        self.hub = hub
        self.openRun = openRun
        self.changed = changed
        self.deleted = deleted
        _header = State(initialValue: trigger.signatureHeader ?? "")
        _prefix = State(initialValue: trigger.signaturePrefix ?? "")
        _eventsText = State(initialValue: trigger.events.joined(separator: ", "))
    }

    private var url: String { WorkflowEditRules.triggerURL(hub: hub, path: trigger.path) }

    var body: some View {
        DisclosureGroup(isExpanded: $open) {
            Toggle(l10n("workflow_editor.triggers.enabled"), isOn: Binding(
                get: { trigger.enabled },
                set: { on in Task { await patch(WorkflowTriggerPatch(enabled: on)) } }
            ))
            .accessibilityIdentifier("workflow.trigger.\(trigger.id).enabled")
            address
            secretRows
            if trigger.preset == .genericHmac || trigger.preset == .token { senderRows }
            eventRows
            testRows
            if let error { NoticeView(text: error, tone: .danger) }
            deliveryRows
            Button(role: .destructive) {
                confirmingDelete = true
            } label: {
                LucideLabel(l10n("workflow_editor.triggers.delete"), icon: .trash, size: 16)
            }
            .accessibilityIdentifier("workflow.trigger.\(trigger.id).delete")
        } label: {
            HStack(spacing: Space.s2) {
                Text(trigger.name)
                    .font(.system(size: FontSize.sizeMd, weight: .semibold))
                    .contentDirection(of: trigger.name, fill: false)
                    .lineLimit(1)
                StatusPill(text: l10n("workflow_editor.triggers.presets.\(trigger.preset.rawValue)"), kind: .info)
            }
        }
        .accessibilityIdentifier("workflow.trigger.\(trigger.id)")
        .onChange(of: focus) { old, _ in
            // Header, prefix and typed events are saved when their editing ends.
            switch old {
            case .some(.header): saveHeader()
            case .some(.prefix): savePrefix()
            case .some(.events): saveEvents()
            case .none: break
            }
        }
        .task(id: deliveriesKey) { await followDeliveries() }
        .alert(l10n("workflow_editor.triggers.delete_title"), isPresented: $confirmingDelete) {
            Button(l10n("common.cancel"), role: .cancel) {}
            Button(l10n("workflow_editor.triggers.delete"), role: .destructive) { Task { await delete() } }
                .accessibilityIdentifier("dialog.confirm")
        } message: {
            Text(l10n("workflow_editor.triggers.delete_body"))
        }
    }

    // MARK: - Rows

    private var address: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            Text(l10n("workflow_editor.triggers.url")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            HStack(spacing: Space.s2) {
                Text(url)
                    .font(.system(size: FontSize.sizeXs, design: .monospaced))
                    .lineLimit(3)
                    .textSelection(.enabled)
                    .accessibilityIdentifier("workflow.trigger.\(trigger.id).url")
                Spacer(minLength: 0)
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
            Text(l10n("workflow_editor.triggers.url_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
        }
    }

    @ViewBuilder
    private var secretRows: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            Text(l10n("workflow_editor.triggers.secret")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            HStack(spacing: Space.s2) {
                // Never shown back: the field only ever holds what is typed now.
                SecureField(trigger.secretStored ? "[stored]" : "", text: $secret)
                    .monoField()
                    .accessibilityLabel(l10n("workflow_editor.triggers.secret"))
                    .accessibilityIdentifier("workflow.trigger.\(trigger.id).secret")
                Button(l10n("common.save")) {
                    guard let body = WorkflowTriggerRules.secretPatch(secret) else { return }
                    secret = ""
                    Task { await patch(body) }
                }
                .buttonStyle(.bordered)
                .disabled(WorkflowTriggerRules.secretPatch(secret) == nil || saving)
                .accessibilityIdentifier("workflow.trigger.\(trigger.id).secret_save")
            }
            Text(l10n("workflow_editor.triggers.secret_hint.\(trigger.preset.rawValue)"))
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            Text(l10n(trigger.secretStored ? "workflow_editor.triggers.secret_stored" : "workflow_editor.triggers.secret_missing"))
                .font(.system(size: FontSize.sizeXs))
                .foregroundStyle(trigger.secretStored ? Tone.textMuted : Tone.warningSoftText)
                .accessibilityIdentifier("workflow.trigger.\(trigger.id).secret_state")
        }
    }

    @ViewBuilder
    private var senderRows: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            Text(l10n("workflow_editor.triggers.header")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            TextField(WorkflowTriggerRules.headerPlaceholder(trigger.preset), text: $header)
                .monoField()
                .focused($focus, equals: .header)
                .onSubmit { focus = nil }
                .accessibilityLabel(l10n("workflow_editor.triggers.header"))
                .accessibilityIdentifier("workflow.trigger.\(trigger.id).header")
        }
        if trigger.preset == .genericHmac {
            VStack(alignment: .leading, spacing: Space.s1) {
                Text(l10n("workflow_editor.triggers.prefix")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                TextField("sha256=", text: $prefix)
                    .monoField()
                    .focused($focus, equals: .prefix)
                    .onSubmit { focus = nil }
                    .accessibilityLabel(l10n("workflow_editor.triggers.prefix"))
                    .accessibilityIdentifier("workflow.trigger.\(trigger.id).prefix")
            }
            Picker(l10n("workflow_editor.triggers.encoding"), selection: Binding(
                get: { trigger.signatureEncoding ?? .hex },
                set: { value in Task { await patch(WorkflowTriggerRules.encodingPatch(value)) } }
            )) {
                ForEach(WorkflowTrigger.SignatureEncoding.allCases, id: \.self) { value in
                    Text(value.rawValue).tag(value)
                }
            }
            .accessibilityIdentifier("workflow.trigger.\(trigger.id).encoding")
        }
    }

    @ViewBuilder
    private var eventRows: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            Text(l10n("workflow_editor.triggers.events")).font(.system(size: FontSize.sizeSm, weight: .medium))
            if trigger.preset == .clickup {
                ForEach(WorkflowTriggerRules.clickUpEvents, id: \.self) { event in
                    Toggle(isOn: Binding(
                        get: { trigger.events.contains(event) },
                        set: { on in
                            let events = WorkflowTriggerRules.toggle(event, on: on, in: trigger.events)
                            Task { await patch(WorkflowTriggerPatch(events: events)) }
                        }
                    )) {
                        Text(event).font(.system(size: FontSize.sizeSm, design: .monospaced))
                    }
                    .accessibilityIdentifier("workflow.trigger.\(trigger.id).event.\(event)")
                }
            } else {
                TextField("issues, pull_request", text: $eventsText)
                    .monoField()
                    .focused($focus, equals: .events)
                    .onSubmit { focus = nil }
                    .accessibilityLabel(l10n("workflow_editor.triggers.events"))
                    .accessibilityIdentifier("workflow.trigger.\(trigger.id).events_text")
            }
            Text(l10n("workflow_editor.triggers.events_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
        }
    }

    @ViewBuilder
    private var testRows: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            Text(l10n("workflow_editor.triggers.test_event")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            HStack(spacing: Space.s2) {
                TextField(trigger.events.first ?? "", text: $testEvent)
                    .monoField()
                    .accessibilityLabel(l10n("workflow_editor.triggers.test_event"))
                    .accessibilityIdentifier("workflow.trigger.\(trigger.id).test_event")
                Button(l10n("workflow_editor.triggers.test")) { Task { await test() } }
                    .buttonStyle(.bordered)
                    .disabled(!WorkflowTriggerRules.canTest(trigger) || testing)
                    .accessibilityIdentifier("workflow.trigger.\(trigger.id).test")
            }
            if !trigger.secretStored {
                Text(l10n("workflow_editor.triggers.secret_missing")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
            if let tested {
                Text(l10n("workflow_editor.triggers.status.\(tested.status.rawValue)"))
                    .font(.system(size: FontSize.sizeSm))
                    .accessibilityIdentifier("workflow.trigger.\(trigger.id).test_result")
            }
        }
    }

    @ViewBuilder
    private var deliveryRows: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text(l10n("workflow_editor.triggers.deliveries")).font(.system(size: FontSize.sizeSm, weight: .medium))
            if let deliveries {
                if deliveries.isEmpty {
                    Text(l10n("workflow_editor.triggers.no_deliveries")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
                ForEach(deliveries, id: \.id) { delivery in
                    DeliveryLine(delivery: delivery, openRun: openRun)
                }
            } else {
                ProgressView()
            }
        }
    }

    // MARK: - Calls

    private func saveHeader() {
        guard header.trimmingCharacters(in: .whitespaces) != (trigger.signatureHeader ?? "") else { return }
        Task { await patch(WorkflowTriggerRules.headerPatch(header)) }
    }

    private func savePrefix() {
        guard prefix.trimmingCharacters(in: .whitespaces) != (trigger.signaturePrefix ?? "") else { return }
        Task { await patch(WorkflowTriggerRules.prefixPatch(prefix)) }
    }

    private func saveEvents() {
        let events = WorkflowTriggerRules.events(fromText: eventsText)
        guard events != trigger.events else { return }
        Task { await patch(WorkflowTriggerPatch(events: events)) }
    }

    private func patch(_ body: WorkflowTriggerPatch) async {
        saving = true
        defer { saving = false }
        let profile = profile, id = trigger.id
        do {
            let saved = try await app.api.call {
                try await SchedulesAPI.schedulesUpdateWorkflowTrigger(xHubProfile: profile, workflowTriggerId: id, workflowTriggerPatch: body, apiConfiguration: $0)
            }
            error = nil
            changed(saved)
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func test() async {
        testing = true
        defer { testing = false }
        let profile = profile, id = trigger.id, body = WorkflowTriggerRules.test(event: testEvent)
        do {
            tested = try await app.api.call {
                try await SchedulesAPI.schedulesTestWorkflowTrigger(xHubProfile: profile, workflowTriggerId: id, workflowTriggerTest: body, apiConfiguration: $0)
            }
            error = nil
            deliveriesKey += 1
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    /// What arrived, read again every few seconds while the card is on screen.
    private func followDeliveries() async {
        let profile = profile, id = trigger.id
        while !Task.isCancelled {
            do {
                deliveries = try await app.api.call {
                    try await SchedulesAPI.schedulesListWorkflowTriggerDeliveries(xHubProfile: profile, workflowTriggerId: id, limit: 20, apiConfiguration: $0)
                }.items
            } catch is CancellationError {
                return
            } catch {
                if Task.isCancelled { return }
                // An older hub without the log shows it empty; another failure says so once.
                let failure = HubFailure(error)
                if deliveries == nil { deliveries = [] }
                if !WorkflowTriggerRules.unsupported(failure), self.error == nil { self.error = failure.describe(l10n) }
            }
            try? await Task.sleep(for: .seconds(10))
        }
    }

    private func delete() async {
        let profile = profile, id = trigger.id
        do {
            try await app.api.call {
                try await SchedulesAPI.schedulesDeleteWorkflowTrigger(xHubProfile: profile, workflowTriggerId: id, apiConfiguration: $0)
            }
            deleted()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// One delivery: its outcome, whether a condition filtered it, whether it was a test, when it
/// came, what it was about (ids left to right), its error, and its run.
private struct DeliveryLine: View {
    let delivery: WorkflowTriggerDelivery
    let openRun: (String) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: Space.s1) {
                StatusPill(text: l10n("workflow_editor.triggers.status.\(delivery.status.rawValue)"), kind: WorkflowTriggerRules.kind(delivery.status))
                if delivery.filtered { StatusPill(text: l10n("workflow_editor.triggers.filtered")) }
                if delivery.test { StatusPill(text: l10n("workflow_editor.triggers.test_badge"), kind: .info) }
                Text(delivery.receivedAt.shortText(app.language))
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
                    .lineLimit(1)
            }
            if let line = WorkflowTriggerRules.line(delivery) {
                Text(line)
                    .font(.system(size: FontSize.sizeXs, design: .monospaced))
                    .environment(\.layoutDirection, .leftToRight)
            }
            if let error = delivery.error, !error.isEmpty {
                Text(error).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger).contentDirection(of: error)
            }
            if let run = delivery.workflowRunId {
                Button(l10n("workflow_editor.triggers.open_run")) { openRun(run) }
                    .font(.system(size: FontSize.sizeXs))
                    .buttonStyle(.borderless)
                    .accessibilityIdentifier("workflow.delivery.\(delivery.id).run")
            }
        }
        .padding(.vertical, 2)
        .accessibilityIdentifier("workflow.delivery.\(delivery.id)")
    }
}

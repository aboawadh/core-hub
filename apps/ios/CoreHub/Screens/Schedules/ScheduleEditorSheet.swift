// Make or change a schedule (batch 3), as the web's Schedules form: a name, when it runs (the
// shared TriggerEditor with the web's "Common schedules" and the hub's next runs), the agent and
// what it is asked, and the two run options the hub keeps (Hermes decides them for its own jobs).
// A new schedule is made in the profile selector's profile; an edit stays in the schedule's own.
import CoreHubClient
import SwiftUI

struct ScheduleEditorSheet: View {
    /// nil makes a new one.
    let schedule: Schedule?
    let saved: (Schedule) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Environment(\.l10n) private var l10n
    @State private var draft = ScheduleDraft()
    @State private var ready = false
    @State private var tried = false
    @State private var saving = false
    @State private var failure: HubFailure?

    private var profile: String { schedule?.profile ?? app.currentProfile }
    private var agents: [Agent] { TaskRules.startable(app.agents) }
    /// Hermes's own scheduler keeps a job's run options: they are not offered for it.
    private var hermes: Bool {
        if let schedule { return !ScheduleRules.hasRunOptions(schedule) }
        return ScheduleRules.isHermes(draft.agentID, in: agents)
    }
    private var problem: ScheduleProblem? { ScheduleRules.problem(draft, editing: schedule != nil) }

    var body: some View {
        NavigationStack {
            Form {
                if let failure {
                    Section {
                        NoticeView(text: ScheduleRules.refusal(failure, l10n), tone: .danger)
                        if let zone = ScheduleRules.askedZone(failure) {
                            Button(l10n("schedules.form.use_zone", ["zone": zone])) {
                                draft = ScheduleRules.withZone(draft, zone)
                                Task { await submit() }
                            }
                            .accessibilityIdentifier("schedule.form.use_zone")
                        }
                    }
                }
                Section {
                    TextField(l10n("schedules.form.name"), text: $draft.name)
                        .accessibilityIdentifier("schedule.form.name")
                } header: {
                    Text(l10n("schedules.form.name"))
                } footer: {
                    if tried, problem == .name { Text(l10n("schedules.form.need_name")).foregroundStyle(Tone.danger) }
                }
                Section {
                    Menu {
                        ForEach(ScheduleRules.templates) { template in
                            Button(l10n("schedules.templates.\(template.id)")) {
                                draft.trigger = ScheduleRules.apply(template, to: draft.trigger)
                            }
                        }
                    } label: {
                        LucideLabel(l10n("schedules.form.templates"), icon: .calendarClock)
                    }
                    .accessibilityIdentifier("schedule.form.templates")
                    TriggerEditor(draft: $draft.trigger, preview: preview, tag: "schedule.form.trigger")
                } header: {
                    Text(l10n("schedules.form.when"))
                } footer: {
                    if tried, problem == .trigger { Text(l10n("schedules.form.need_trigger")).foregroundStyle(Tone.danger) }
                }
                Section {
                    agentRow
                    VStack(alignment: .leading, spacing: Space.s1) {
                        Text(l10n("schedules.form.prompt")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                        TextEditor(text: $draft.prompt)
                            .frame(minHeight: 90)
                            .environment(\.layoutDirection, ContentDirection.of(draft.prompt) ?? (app.language.isRTL ? .rightToLeft : .leftToRight))
                            .accessibilityIdentifier("schedule.form.prompt")
                    }
                } footer: {
                    if tried, problem == .agent { Text(l10n("schedules.form.need_agent")).foregroundStyle(Tone.danger) }
                }
                Section {
                    if hermes {
                        Text(l10n("schedules.options.hermes")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                    } else {
                        Toggle(isOn: $draft.runIfMissed) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(l10n("schedules.options.missed"))
                                Text(l10n("schedules.options.missed_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            }
                        }
                        .accessibilityIdentifier("schedule.form.missed")
                        Picker(l10n("schedules.options.overlap"), selection: $draft.overlap) {
                            ForEach(ScheduleRules.overlaps, id: \.self) { overlap in
                                Text(l10n("schedules.options.\(overlap.rawValue)")).tag(overlap)
                            }
                        }
                        .pickerStyle(.menu)
                        .accessibilityIdentifier("schedule.form.overlap")
                    }
                } header: {
                    Text(l10n("schedules.options.title"))
                } footer: {
                    if !hermes {
                        Text(l10n("schedules.options.\(draft.overlap.rawValue)_hint") + " " + l10n("schedules.options.overlap_hint"))
                    }
                }
                if schedule == nil, app.enterableProfiles.count > 1 {
                    Section {
                        Text(l10n("schedules.profile_note", ["name": app.profileName(profile)]))
                            .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                }
            }
            .navigationTitle(l10n(schedule == nil ? "schedules.new" : "schedules.edit_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(l10n("common.cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving {
                        ProgressView()
                    } else {
                        Button(l10n("common.save")) { Task { await submit() } }
                            .accessibilityIdentifier("schedule.form.save")
                    }
                }
            }
        }
        .onAppear {
            guard !ready else { return }
            draft = schedule.map(ScheduleRules.draft) ?? ScheduleRules.newDraft(agents: agents)
            ready = true
        }
        .accessibilityIdentifier("schedule.form")
    }

    @ViewBuilder
    private var agentRow: some View {
        if let schedule {
            // An edit keeps its agent: moving a job in or out of Hermes's scheduler is a new schedule.
            let name = app.agentDirectory.agents(schedule.profile).first { $0.id == schedule.target.agentId }?.name
            LabeledContent(l10n("schedules.form.agent"), value: name ?? (schedule.target.kind == .workflow ? l10n("schedules.detail.workflow") : "—"))
        } else if agents.isEmpty {
            Text(l10n("schedules.form.no_agents")).foregroundStyle(Tone.textMuted)
        } else {
            Picker(l10n("schedules.form.agent"), selection: $draft.agentID) {
                ForEach(agents, id: \.id) { agent in Text(agent.name).tag(Optional(agent.id)) }
            }
            .pickerStyle(.menu)
            .accessibilityIdentifier("schedule.form.agent")
        }
    }

    private func preview(_ trigger: ScheduleTrigger) async throws -> [Date] {
        let profile = profile
        return try await app.api.call {
            try await SchedulesAPI.schedulesPreviewTrigger(
                xHubProfile: profile, schedulePreviewRequest: SchedulePreviewRequest(trigger: trigger, count: 3), apiConfiguration: $0
            )
        }.nextRuns
    }

    private func submit() async {
        tried = true
        guard problem == nil else { return }
        saving = true
        defer { saving = false }
        do {
            let result: Schedule
            if let schedule {
                guard let write = ScheduleRules.update(schedule, draft) else {
                    dismiss()
                    return
                }
                result = try await app.api.call {
                    try await SchedulesAPI.schedulesUpdate(xHubProfile: schedule.profile, scheduleId: schedule.id, scheduleWrite: write, apiConfiguration: $0)
                }
            } else {
                guard let write = ScheduleRules.create(draft, hermes: hermes) else { return }
                let profile = profile
                result = try await app.api.call {
                    try await SchedulesAPI.schedulesCreate(xHubProfile: profile, scheduleWrite: write, apiConfiguration: $0)
                }
            }
            failure = nil
            saved(result)
            dismiss()
        } catch {
            failure = HubFailure(error)
        }
    }
}

// One schedule on its own (batch 3): when it runs and what it asks, run now, pause or resume,
// edit, delete (asks first), and its history page by page — each run's status, when it started,
// how long it took, what it said, and the conversation it ran in. Shown as a sheet over the list,
// so opening a conversation leaves it (the shell takes `pendingRoute`).
import CoreHubClient
import SwiftUI

struct ScheduleDetailView: View {
    @State var schedule: Schedule
    let changed: (Schedule) -> Void
    let deleted: (Schedule) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Environment(\.l10n) private var l10n
    @State private var runs: PagedList<ScheduleRunItem>?
    @State private var note: String?
    @State private var failure: String?
    @State private var busy = false
    @State private var editing = false
    @State private var pendingDelete: Schedule?

    var body: some View {
        NavigationStack {
            List {
                facts
                actions
                history
            }
            .navigationTitle(schedule.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(l10n("common.close")) { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        editing = true
                    } label: {
                        LucideIcon(.pencil, size: 18)
                    }
                    .accessibilityLabel(l10n("kit.edit"))
                    .accessibilityIdentifier("schedule.edit")
                }
            }
            .refreshable { await reload() }
        }
        .task {
            if runs == nil { runs = makeRuns() }
            await runs?.refresh()
        }
        // A run still going is asked about again every few seconds until it ends, as on the web.
        .task(id: live) {
            guard live else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3))
                if Task.isCancelled { return }
                await runs?.refresh()
            }
        }
        .sheet(isPresented: $editing) {
            ScheduleEditorSheet(schedule: schedule) { saved in
                schedule = saved
                changed(saved)
            }
        }
        .confirmDelete($pendingDelete, name: { $0.name }, delete: { target in
            try await app.api.call {
                try await SchedulesAPI.schedulesDelete(xHubProfile: target.profile, scheduleId: target.id, apiConfiguration: $0)
            }
        }, deleted: { target in
            deleted(target)
            dismiss()
        })
        .accessibilityIdentifier("schedule.detail")
    }

    private var live: Bool { ScheduleRules.live(runs?.items.map(\.run) ?? []) }

    // MARK: - What it is

    @ViewBuilder
    private var facts: some View {
        Section {
            HStack(spacing: Space.s2) {
                StatusPill(text: l10n("schedules.state_\(schedule.state.rawValue)"), kind: stateKind)
                if schedule.external?.source == .hermes {
                    StatusPill(text: l10n("schedules.hermes.origin"))
                }
                if app.enterableProfiles.count > 1 { ProfileBadge(name: app.profileName(schedule.profile)) }
            }
            LabeledContent(l10n("schedules.detail.when")) {
                Text(whenText)
                    .environment(\.layoutDirection, schedule.trigger.kind == .cron ? .leftToRight : app.language.layoutDirection)
            }
            LabeledContent(l10n("schedules.detail.next"), value: schedule.nextRunAt?.shortText(app.language) ?? l10n("schedules.detail.never"))
            if let last = schedule.lastRunAt {
                LabeledContent(l10n("schedules.detail.last"), value: last.shortText(app.language))
            }
            if schedule.target.kind == .workflow {
                LabeledContent(l10n("schedules.detail.agent"), value: l10n("schedules.detail.workflow"))
            } else if let agent = agentName {
                LabeledContent(l10n("schedules.detail.agent"), value: agent)
            }
            if let prompt = schedule.target.prompt, !prompt.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    Text(l10n("schedules.detail.prompt")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    Text(prompt).contentDirection(of: prompt).textSelection(.enabled)
                }
            }
            if schedule.delivery.kind == .channel, let channel = schedule.delivery.channel {
                Text(l10n("schedules.detail.delivers_to", ["channel": channel]))
                    .font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
            }
            if let error = schedule.lastError, !error.isEmpty {
                Text(l10n("schedules.detail.last_error", ["error": error]))
                    .font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.danger).contentDirection(of: error)
            }
        }
    }

    private var stateKind: StatusPill.Kind {
        switch schedule.state {
        case .running: return .good
        case .paused: return .warn
        default: return .neutral
        }
    }

    private var agentName: String? {
        guard let id = schedule.target.agentId else { return nil }
        return app.agentDirectory.agents(schedule.profile).first { $0.id == id }?.name
    }

    private var whenText: String {
        let trigger = schedule.trigger
        if let display = trigger.display, !display.isEmpty { return display }
        switch trigger.kind {
        case .cron: return "\(trigger.expression ?? "") · \(trigger.timezone)"
        case .interval:
            let (every, unit) = TriggerRules.split(trigger.everyMinutes ?? 60)
            let units = ["kit.trigger.minutes", "kit.trigger.hours", "kit.trigger.days"]
            return "\(l10n("kit.trigger.every")) \(every) \(l10n(units[EveryUnit.allCases.firstIndex(of: unit) ?? 0]))"
        case .once: return trigger.runAt?.shortText(app.language) ?? "—"
        }
    }

    // MARK: - What can be done

    @ViewBuilder
    private var actions: some View {
        Section {
            if let note { NoticeView(text: note, tone: .success) }
            if let failure { NoticeView(text: failure, tone: .danger) }
            Button {
                Task { await runNow() }
            } label: {
                LucideLabel(l10n("schedules.run_now"), icon: .play)
            }
            .disabled(busy)
            .accessibilityIdentifier("schedule.run")
            Toggle(isOn: Binding(get: { schedule.enabled }, set: { on in Task { await setEnabled(on) } })) {
                Text(l10n("schedules.detail.enabled"))
            }
            .disabled(busy)
            .accessibilityIdentifier("schedule.enabled")
            Button(role: .destructive) {
                pendingDelete = schedule
            } label: {
                LucideLabel(l10n("kit.delete"), icon: .trash)
            }
            .accessibilityIdentifier("schedule.delete")
        }
    }

    private func runNow() async {
        busy = true
        defer { busy = false }
        let target = schedule
        do {
            _ = try await app.api.call {
                try await SchedulesAPI.schedulesRunNow(xHubProfile: target.profile, scheduleId: target.id, apiConfiguration: $0)
            }
            failure = nil
            note = ScheduleRules.fired(target, l10n)
            await reload()
        } catch {
            note = nil
            failure = ScheduleRules.refusal(HubFailure(error), l10n)
        }
    }

    private func setEnabled(_ on: Bool) async {
        busy = true
        defer { busy = false }
        let target = schedule
        do {
            schedule = try await app.api.call {
                try await SchedulesAPI.schedulesUpdate(xHubProfile: target.profile, scheduleId: target.id, scheduleWrite: ScheduleWrite(enabled: on), apiConfiguration: $0)
            }
            failure = nil
            changed(schedule)
        } catch {
            failure = ScheduleRules.refusal(HubFailure(error), l10n)
        }
    }

    private func reload() async {
        let target = schedule
        if let fresh = try? await app.api.call({
            try await SchedulesAPI.schedulesGet(xHubProfile: target.profile, scheduleId: target.id, apiConfiguration: $0)
        }) {
            schedule = fresh
            changed(fresh)
        }
        await runs?.refresh()
    }

    // MARK: - What it did

    private func makeRuns() -> PagedList<ScheduleRunItem> {
        let api = app.api
        let profile = schedule.profile
        let id = schedule.id
        return PagedList { cursor in
            let page = try await api.call {
                try await SchedulesAPI.schedulesListRuns(xHubProfile: profile, scheduleId: id, cursor: cursor, limit: ScheduleRules.runsPage, apiConfiguration: $0)
            }
            return ListPage(items: page.items.map(ScheduleRunItem.init), next: page.nextCursor)
        }
    }

    @ViewBuilder
    private var history: some View {
        Section {
            if let runs {
                if runs.loading {
                    ProgressView()
                } else if runs.items.isEmpty, let failure = runs.failure {
                    NoticeView(text: failure.describe(l10n), tone: .danger)
                } else if runs.items.isEmpty {
                    Text(l10n("schedules.history.empty")).foregroundStyle(Tone.textMuted)
                        .accessibilityIdentifier("schedule.history.empty")
                }
                ForEach(runs.items) { item in RunRow(run: item.run, open: { open(item.run) }) }
                if runs.hasMore {
                    HStack {
                        Spacer()
                        if runs.loadingMore {
                            ProgressView()
                        } else {
                            Button(l10n("kit.load_more")) { Task { await runs.loadMore() } }
                                .accessibilityIdentifier("schedule.history.more")
                        }
                        Spacer()
                    }
                    .onAppear { if runs.failure == nil { Task { await runs.loadMore() } } }
                }
            }
        } header: {
            Text(l10n("schedules.history.title"))
        }
        .accessibilityIdentifier("schedule.history")
    }

    private func open(_ run: ScheduleRun) {
        guard let session = run.sessionId else { return }
        let profile = schedule.profile
        dismiss()
        app.pendingRoute = .chat(sessionID: session, profile: profile)
    }
}

/// One line of a schedule's history.
private struct RunRow: View {
    let run: ScheduleRun
    let open: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            HStack(spacing: Space.s2) {
                StatusPill(text: l10n(ScheduleRules.statusKey(run)), kind: kind)
                Text(meta).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(1)
            }
            if let preview = run.outputPreview, !preview.isEmpty {
                Text(preview).font(.system(size: FontSize.sizeSm)).lineLimit(2).contentDirection(of: preview)
            }
            if let error = run.error, !error.isEmpty {
                Text(error).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger).lineLimit(3).contentDirection(of: error)
            }
            if run.deliveryStatus == .failed, let error = run.deliveryError {
                Text(l10n("schedules.history.not_delivered", ["error": error])).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
            }
            if run.sessionId != nil {
                Button(action: open) {
                    LucideLabel(l10n("schedules.open_chat"), icon: .messagesSquare, size: 14)
                }
                .font(.system(size: FontSize.sizeSm))
                .buttonStyle(.borderless)
                .accessibilityIdentifier("schedule.run.\(run.id).chat")
            }
        }
        .accessibilityIdentifier("schedule.run.\(run.id)")
    }

    private var kind: StatusPill.Kind {
        if run.waiting { return .warn }
        switch run.status {
        case .succeeded, .running: return .good
        case .failed: return .bad
        default: return .neutral
        }
    }

    private var meta: String {
        var parts = [l10n(run.trigger == .manual ? "schedules.history.manual" : "schedules.history.on_time")]
        if let at = run.startedAt ?? run.finishedAt { parts.append(at.shortText(app.language)) }
        if let seconds = ScheduleRules.seconds(run) {
            parts.append(l10n("schedules.history.took", ["time": ScheduleRules.duration(seconds, l10n)]))
        }
        return parts.joined(separator: " · ")
    }
}

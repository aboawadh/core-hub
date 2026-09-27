// Schedules show every profile the person may enter, with no profile filter (profileScope.alwaysAll,
// ADR 0016): each item carries its profile's badge, and anything done to it goes to its own profile
// without moving the selector. A new one is made in the selector's profile. Workflows are
// Workflows.swift; a schedule on its own is ScheduleDetailView, its form ScheduleEditorSheet.
import CoreHubClient
import SwiftUI

enum SchedulesHalf: Hashable { case jobs, workflows }

struct SchedulesScreen: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var half: SchedulesHalf = .jobs
    @State private var list: PagedList<ScheduleItem>?
    @State private var opened: ScheduleItem?
    @State private var editing: ScheduleItem?
    @State private var creating = false
    @State private var pendingDelete: Schedule?
    @State private var note: String?
    @State private var failure: String?

    var body: some View {
        // Schedules and workflows share the page, as on the web: one segmented switch above them.
        VStack(spacing: 0) {
            Picker(l10n("nav.schedules"), selection: $half) {
                Text(l10n("schedules.tab_jobs")).tag(SchedulesHalf.jobs)
                Text(l10n("schedules.tab_workflows")).tag(SchedulesHalf.workflows)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, Space.s4)
            .padding(.vertical, Space.s2)
            .accessibilityIdentifier("schedules.half")
            switch half {
            case .jobs: jobs
            case .workflows: WorkflowsList()
            }
        }
        .navigationTitle(l10n("nav.schedules"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if half == .jobs {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        creating = true
                    } label: {
                        LucideIcon(.plus, size: 20)
                    }
                    .accessibilityLabel(l10n("schedules.new"))
                    .accessibilityIdentifier("schedules.new")
                }
            }
        }
        .onAppear { if list == nil { list = makeList() } }
        .sheet(item: $opened) { item in
            ScheduleDetailView(schedule: item.schedule, changed: { _ in reload() }, deleted: { gone in list?.remove(gone.id) })
        }
        .sheet(item: $editing) { item in
            ScheduleEditorSheet(schedule: item.schedule) { _ in reload() }
        }
        .sheet(isPresented: $creating) {
            ScheduleEditorSheet(schedule: nil) { _ in reload() }
        }
        .confirmDelete($pendingDelete, name: { $0.name }, delete: { target in
            try await app.api.call {
                try await SchedulesAPI.schedulesDelete(xHubProfile: target.profile, scheduleId: target.id, apiConfiguration: $0)
            }
        }, deleted: { gone in list?.remove(gone.id) })
        .accessibilityIdentifier("screen.schedules")
    }

    @ViewBuilder
    private var jobs: some View {
        VStack(spacing: 0) {
            if let note { NoticeView(text: note, tone: .success).padding(.horizontal, Space.s4) }
            if let failure { NoticeView(text: failure, tone: .danger).padding(.horizontal, Space.s4) }
            if let list {
                ListScaffold(
                    list,
                    emptyIcon: .calendarClock,
                    emptyTitle: l10n("schedules.empty"),
                    actions: actions,
                    swipe: { item in [RowAction(title: l10n("kit.delete"), icon: .trash, destructive: true) { pendingDelete = item.schedule }] },
                    tag: "schedules.list"
                ) { item in
                    Button {
                        opened = item
                    } label: {
                        ScheduleRow(schedule: item.schedule, showProfile: app.enterableProfiles.count > 1)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("schedule.row.\(item.id)")
                }
            }
        }
    }

    private func makeList() -> PagedList<ScheduleItem> {
        let api = app.api
        return PagedList { cursor in
            let page = try await api.call {
                try await SchedulesAPI.schedulesList(profiles: .all, cursor: cursor, limit: 50, apiConfiguration: $0)
            }
            return ListPage(items: page.items.map { ScheduleItem(schedule: $0) }, next: page.nextCursor)
        }
    }

    private func reload() {
        Task { await list?.refresh() }
    }

    private func actions(_ item: ScheduleItem) -> [RowAction] {
        let schedule = item.schedule
        return [
            RowAction(title: l10n("schedules.run_now"), icon: .play) { Task { await runNow(schedule) } },
            RowAction(title: l10n(schedule.enabled ? "schedules.pause" : "schedules.resume"), icon: schedule.enabled ? .pause : .play) {
                Task { await setEnabled(schedule, !schedule.enabled) }
            },
            RowAction(title: l10n("kit.edit"), icon: .pencil) { editing = item },
            RowAction(title: l10n("kit.delete"), icon: .trash, destructive: true) { pendingDelete = schedule },
        ]
    }

    private func runNow(_ schedule: Schedule) async {
        do {
            _ = try await app.api.call {
                try await SchedulesAPI.schedulesRunNow(xHubProfile: schedule.profile, scheduleId: schedule.id, apiConfiguration: $0)
            }
            failure = nil
            note = ScheduleRules.fired(schedule, l10n)
            await list?.refresh()
        } catch {
            note = nil
            failure = ScheduleRules.refusal(HubFailure(error), l10n)
        }
    }

    private func setEnabled(_ schedule: Schedule, _ on: Bool) async {
        do {
            _ = try await app.api.call {
                try await SchedulesAPI.schedulesUpdate(xHubProfile: schedule.profile, scheduleId: schedule.id, scheduleWrite: ScheduleWrite(enabled: on), apiConfiguration: $0)
            }
            failure = nil
            await list?.refresh()
        } catch {
            failure = ScheduleRules.refusal(HubFailure(error), l10n)
        }
    }
}

/// A schedule as a compact row: its name and profile, its state, when it runs and next, a last error.
struct ScheduleRow: View {
    let schedule: Schedule
    let showProfile: Bool
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            HStack(alignment: .firstTextBaseline) {
                Text(schedule.name).font(.system(size: FontSize.sizeMd, weight: .medium))
                    .contentDirection(of: schedule.name)
                    .lineLimit(2)
                Spacer(minLength: Space.s2)
                if showProfile { ProfileBadge(name: app.profileName(schedule.profile)) }
            }
            HStack(spacing: Space.s2) {
                StatusPill(text: l10n("schedules.state_\(schedule.state.rawValue)"), kind: schedule.state == .running ? .good : schedule.state == .paused ? .warn : .neutral)
                if let display = schedule.trigger.display ?? schedule.trigger.expression {
                    Text(display).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(1)
                }
            }
            if let next = schedule.nextRunAt {
                Text(l10n("schedules.next", ["time": next.shortText(app.language)]))
                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
            if let lastError = schedule.lastError, !lastError.isEmpty {
                Text(lastError).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger).lineLimit(2)
            }
        }
        .contentShape(Rectangle())
    }
}

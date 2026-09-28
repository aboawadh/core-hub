// Background (the web's BackgroundTasks, contract decision §56; apps batch 6a, left by the insight
// batch): what works for the person right now in every profile they may enter — chats, tasks,
// schedules and workflows, jobs, subagents — with where each lives and Stop where it can be
// stopped, and what finished in the last day under «Finished (n)». The top bar shows it only while
// something runs (as the web does on a phone); the chat's «⋯» opens it always. Android's
// BackgroundSheet.kt is the twin.
import CoreHubClient
import Observation
import SwiftUI

/// The plain rules of the sheet, tested without views.
enum BackgroundRules {
    /// With something running the list is read this often; otherwise this rarely (the web's poll).
    static let runningPoll: TimeInterval = 10
    static let idlePoll: TimeInterval = 60

    /// Where an item lives: the task board (a task run), the run on the Workflows page (a workflow
    /// run), its conversation (a chat or schedule run, a subagent — the Subagents sheet is there),
    /// or the page its job is about (Settings for the pages kept there, Agents for an agent's
    /// work); nil when it has no place.
    static func destination(of item: BackgroundItem) -> MainContent? {
        switch item.kind {
        case .taskRun: return .destination(.tasks)
        case .workflowRun:
            if let resource = item.resource, resource.kind == .workflowRun, !resource.id.isEmpty {
                return .workflowRun(runID: resource.id, profile: item.profile)
            }
            return .destination(.workflows)
        default: break
        }
        if let session = item.sessionId { return .chat(sessionID: session, profile: item.profile) }
        guard item.kind == .job else { return nil }
        switch item.jobKind?.rawValue ?? "" {
        case "export", "import", "refresh_catalogue", "webhook_test", "device_request": return .settings
        case "install", "update", "uninstall", "restart", "check_update", "discover", "plugin_install", "channel_login":
            return .destination(.agentManager)
        case "worktree": return .destination(.tasks)
        case "schedule_run": return .destination(.schedules)
        case "workflow_run": return .destination(.workflows)
        default: return item.resource?.kind == .agent ? .destination(.agentManager) : nil
        }
    }

    /// How long it has worked (or worked, once finished); nil while it waits in a queue.
    static func elapsed(_ item: BackgroundItem, now: Date) -> TimeInterval? {
        guard let start = item.startedAt else { return nil }
        return max(0, (item.finishedAt ?? now).timeIntervalSince(start))
    }

    /// «0:42», «12:05», «1:02:09»: always Latin digits (§113).
    static func clock(_ seconds: TimeInterval) -> String {
        let total = Int(seconds)
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    /// The row's title: the hub's words, a job's kind when it has none, else `untitled`.
    static func title(_ item: BackgroundItem, untitled: String) -> String {
        let words = item.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !words.isEmpty { return words }
        if item.kind == .job, let kind = item.jobKind?.rawValue, !kind.isEmpty { return kind }
        return untitled
    }

    /// The badge the top bar wears: how many things are working now.
    static func count(_ list: BackgroundList?) -> Int { list?.running.count ?? 0 }
}

/// The two calls, through the generated client only: every profile's list, and one item stopped in its own profile.
struct BackgroundOps {
    let api: HubAPI
    let profile: String

    func list() async throws -> BackgroundList {
        try await api.call { try await JobsAPI.backgroundList(xHubProfile: profile, profiles: .all, apiConfiguration: $0) }
    }

    func stop(_ item: BackgroundItem) async throws -> BackgroundItem {
        try await api.call { try await JobsAPI.backgroundStop(xHubProfile: item.profile, itemId: item.id, apiConfiguration: $0) }
    }
}

@MainActor
@Observable
final class BackgroundModel {
    private(set) var list: BackgroundList?
    var error: String?
    private(set) var stopping: String?
    @ObservationIgnored private weak var app: AppModel?
    @ObservationIgnored private var poll: Task<Void, Never>?

    init(app: AppModel) {
        self.app = app
    }

    var count: Int { BackgroundRules.count(list) }

    /// Reads the list now and then again: often while something runs, rarely otherwise.
    func start() {
        guard poll == nil else { return }
        poll = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.refresh()
                let pause = self.count > 0 ? BackgroundRules.runningPoll : BackgroundRules.idlePoll
                try? await Task.sleep(for: .seconds(pause))
            }
        }
    }

    func stopPolling() {
        poll?.cancel()
        poll = nil
    }

    func refresh() async {
        guard let app else { return }
        do {
            list = try await BackgroundOps(api: app.api, profile: app.currentProfile).list()
            error = nil
        } catch {
            self.error = HubFailure(error).describe(app.l10n)
        }
    }

    /// Stops one item; the list is read again, since the hub answers before the work has ended.
    func stop(_ item: BackgroundItem) {
        guard let app, stopping == nil else { return }
        stopping = item.id
        Task {
            do { _ = try await BackgroundOps(api: app.api, profile: app.currentProfile).stop(item) } catch {
                self.error = HubFailure(error).describe(app.l10n)
            }
            await refresh()
            stopping = nil
        }
    }
}

/// The top bar's button (beside the pending bell): the Activity icon with how many things run.
/// Like the web on a phone it steps aside while nothing runs; the chat's «⋯» still opens the sheet.
struct BackgroundButton: View {
    let model: BackgroundModel
    let open: () -> Void
    @Environment(\.l10n) private var l10n

    var body: some View {
        if model.count > 0 {
            Button(action: open) {
                ZStack(alignment: .topTrailing) {
                    LucideIcon(.activity, size: 20)
                    Text(model.count > 99 ? "99+" : "\(model.count)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(Tone.accentText)
                        .padding(.horizontal, 4)
                        .background(Tone.accent, in: Capsule())
                        .offset(x: 8, y: -6)
                }
            }
            .accessibilityLabel(l10n("background.open_count", ["n": String(model.count)]))
            .accessibilityIdentifier("background.open")
        }
    }
}

struct BackgroundSheet: View {
    let model: BackgroundModel
    let go: (MainContent) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var showFinished = false

    var body: some View {
        // Running rows count their time while the sheet is open.
        TimelineView(.periodic(from: .now, by: 1)) { context in
            ScrollView {
                VStack(alignment: .leading, spacing: Space.s3) {
                    if let error = model.error { NoticeView(text: error, tone: .danger) }
                    let running = model.list?.running ?? []
                    let finished = model.list?.finished ?? []
                    if running.isEmpty {
                        VStack(spacing: Space.s2) {
                            LucideIcon(.activity, size: 20).foregroundStyle(Tone.textMuted)
                            Text(l10n("background.none")).font(.system(size: FontSize.sizeMd, weight: .semibold))
                            Text(l10n("background.none_body")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Space.s6)
                        .accessibilityIdentifier("background.none")
                    }
                    ForEach(running, id: \.id) { item in row(item, now: context.date) }
                    if !finished.isEmpty {
                        Button {
                            showFinished.toggle()
                        } label: {
                            HStack(spacing: Space.s1) {
                                LucideIcon(showFinished ? .chevronDown : .chevronRight, size: 14)
                                Text(l10n("background.finished", ["n": String(finished.count)]))
                                    .font(.system(size: FontSize.sizeSm, weight: .semibold))
                            }
                            .foregroundStyle(Tone.textMuted)
                        }
                        .accessibilityIdentifier("background.finished")
                        if showFinished {
                            ForEach(finished, id: \.id) { item in row(item, now: context.date) }
                        }
                    }
                }
                .padding(Space.s4)
            }
        }
        .navigationTitle(l10n("background.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(l10n("common.close")) { dismiss() }
            }
        }
        .task { await model.refresh() }
        .accessibilityIdentifier("background.sheet")
    }

    private func row(_ item: BackgroundItem, now: Date) -> some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            HStack(spacing: Space.s2) {
                badge(l10n("background.kind.\(item.kind.rawValue)"), tone: (Tone.textMuted, Tone.surface2))
                if app.enterableProfiles.count > 1 { ProfileBadge(name: app.profileName(item.profile)) }
                Spacer()
                badge(l10n("background.status.\(item.status.rawValue)"), tone: statusTone(item.status))
            }
            let title = BackgroundRules.title(item, untitled: l10n("background.untitled"))
            Text(title)
                .font(.system(size: FontSize.sizeMd))
                .lineLimit(3)
                .contentDirection(of: title)
            HStack(spacing: Space.s3) {
                if let seconds = BackgroundRules.elapsed(item, now: now) {
                    Text(BackgroundRules.clock(seconds)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
                Spacer()
                if let target = BackgroundRules.destination(of: item) {
                    Button(l10n("background.open_item")) {
                        dismiss()
                        go(target)
                    }
                    .font(.system(size: FontSize.sizeSm))
                }
                if item.stoppable {
                    Button {
                        model.stop(item)
                    } label: {
                        if model.stopping == item.id {
                            ProgressView()
                        } else {
                            Label { Text(l10n("background.stop")) } icon: { Image(lucide: .circleStop) }
                        }
                    }
                    .font(.system(size: FontSize.sizeSm))
                    .accessibilityIdentifier("background.stop.\(item.id)")
                }
            }
        }
        .padding(Space.s3)
        .background(Tone.bgRaised, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityIdentifier("background.item.\(item.id)")
    }

    private func badge(_ text: String, tone: (text: Color, fill: Color)) -> some View {
        Text(text)
            .font(.system(size: FontSize.sizeXs, weight: .medium))
            .foregroundStyle(tone.text)
            .padding(.horizontal, Space.s2)
            .padding(.vertical, 2)
            .background(tone.fill, in: Capsule())
    }

    private func statusTone(_ status: BackgroundStatus) -> (text: Color, fill: Color) {
        switch status {
        case .queued: return (Tone.textMuted, Tone.surface2)
        case .running: return (Tone.infoSoftText, Tone.infoSoft)
        case .succeeded: return (Tone.successSoftText, Tone.successSoft)
        case .failed: return (Tone.dangerSoftText, Tone.dangerSoft)
        case .cancelled: return (Tone.warningSoftText, Tone.warningSoft)
        }
    }
}

/// Opens the Background sheet from inside a page (the chat's «⋯»); the shell provides it.
private struct OpenBackgroundKey: EnvironmentKey {
    static let defaultValue: (() -> Void)? = nil
}

extension EnvironmentValues {
    var openBackground: (() -> Void)? {
        get { self[OpenBackgroundKey.self] }
        set { self[OpenBackgroundKey.self] = newValue }
    }
}

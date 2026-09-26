// Tasks show every profile the person may enter, with no profile filter (profileScope.alwaysAll,
// ADR 0016): each item carries its profile's badge, and anything done to it goes to its own profile
// without moving the selector. The board itself is TasksBoard.swift.
import CoreHubClient
import SwiftUI

enum TaskColumns {
    /// The board's columns in the hub's order; `archived` stays off the phone's board.
    static let order: [TaskStatus] = [.triage, .todo, .ready, .scheduled, .running, .blocked, .review, .done]
}

struct TasksScreen: View {
    let openChat: (_ sessionID: String, _ profile: String) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        // Columns side by side with drag between them (B15), as on the web.
        TaskBoardView(openChat: openChat)
        .navigationTitle(l10n("nav.tasks"))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("screen.tasks")
    }
}

struct TaskRow: View {
    let task: HubTask
    let openChat: (_ sessionID: String, _ profile: String) -> Void
    let changed: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            HStack(alignment: .firstTextBaseline) {
                Text(task.title)
                    .font(.system(size: FontSize.sizeMd, weight: .medium))
                    .contentDirection(of: task.title)
                if app.enterableProfiles.count > 1 { ProfileBadge(name: app.profileName(task.profile)) }
            }
            HStack(spacing: Space.s2) {
                StatusPill(text: l10n("tasks.priority_\(task.priority.rawValue)"), kind: task.priority == .urgent || task.priority == .high ? .warn : .neutral)
                if let assignee = task.assignee {
                    Text(assignee.name).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
            }
            if let reason = task.blockedReason ?? task.statusReason {
                Text(reason).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2)
            }
            if let summary = task.latestSummary {
                Text(summary).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(3)
                    .contentDirection(of: summary)
            }
            if let error { NoticeView(text: error, tone: .danger) }
        }
        .contextMenu {
            if let sessionID = task.sessionId {
                Button(l10n("tasks.open_chat")) { openChat(sessionID, task.profile) }
            }
            Menu(l10n("tasks.move")) {
                ForEach(TaskColumns.order.filter { $0 != task.status }, id: \.self) { status in
                    Button(l10n("tasks.status_\(status.rawValue)")) { Task { await move(to: status) } }
                }
            }
        }
        .swipeActions {
            if let sessionID = task.sessionId {
                Button(l10n("tasks.open_chat")) { openChat(sessionID, task.profile) }.tint(Tone.accent)
            }
        }
    }

    private func move(to status: TaskStatus) async {
        // An existing task is acted on in its own profile.
        let profile = task.profile
        do {
            _ = try await app.api.call {
                try await TasksAPI.tasksMoveTask(xHubProfile: profile, taskId: task.id, taskMove: TaskMove(status: status), apiConfiguration: $0)
            }
            error = nil
            changed()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

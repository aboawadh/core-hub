// Tasks show every profile the person may enter, with no profile filter (profileScope.alwaysAll,
// ADR 0016): each item carries its profile's badge, and anything done to it goes to its own profile
// without moving the selector. The board itself is TasksBoard.swift; a task opened on its own is
// TaskDetailView.swift, a new one NewTaskSheet.swift.
import CoreHubClient
import SwiftUI

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

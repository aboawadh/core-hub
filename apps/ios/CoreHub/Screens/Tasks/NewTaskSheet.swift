// A new task from the phone (batch 2, Tasks I): title, description (Markdown), project, priority,
// and — when given to an agent — whether it starts now. Made in the profile the selector is on,
// as on the web ("New task in …"); starting is `tasks.assignTask(start: true)` right after the
// create, the web's "Assign and start". The values' rules are `TaskRules.create`.
import CoreHubClient
import SwiftUI

struct NewTaskSheet: View {
    /// Called with the new task once it is made (and started, when asked).
    let created: (HubTask) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var projects: [Project]?
    @State private var agents: [Agent] = []
    /// One create per sheet: Save pressed again after a failed start does not make a second task.
    @State private var key = ULID.make()

    var body: some View {
        Group {
            if let projects {
                form(projects)
            } else {
                NavigationStack {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { dismiss() } }
                        }
                }
            }
        }
        .task { await load() }
        .accessibilityIdentifier("task.new")
    }

    private func form(_ projects: [Project]) -> some View {
        let profile = app.currentProfile
        let fields = [
            FormField(key: "title", label: l10n("tasks.form.title"), required: true),
            FormField(key: "description", label: l10n("tasks.form.description"), kind: .multiline, help: l10n("tasks.form.description_help")),
            FormField(key: "project", label: l10n("tasks.form.project"), kind: .choice, required: true,
                      options: [FormOption(value: TaskRules.none, label: l10n("tasks.form.project_own"))] + projects.map { FormOption(value: $0.id, label: $0.name) }),
            FormField(key: "priority", label: l10n("tasks.form.priority"), kind: .choice, required: true,
                      options: TaskRules.priorities.map { FormOption(value: $0.rawValue, label: l10n("tasks.priority_\($0.rawValue)")) }),
            FormField(key: "agent", label: l10n("tasks.form.agent"), kind: .choice, required: true,
                      options: [FormOption(value: TaskRules.none, label: l10n("tasks.form.agent_none"))] + agents.map { FormOption(value: $0.id, label: $0.name) }),
            FormField(key: "start", label: l10n("tasks.form.start"), kind: .toggle, help: l10n("tasks.form.start_help")),
        ]
        let initial = ["project": TaskRules.none, "priority": TaskPriority.normal.rawValue, "agent": TaskRules.none, "start": "true"]
        return FormSheet(
            title: app.enterableProfiles.count > 1 ? l10n("tasks.new_task_in", ["name": app.profileName(profile)]) : l10n("tasks.new_task"),
            fields: fields,
            initial: initial,
            saveTitle: l10n("tasks.form.create"),
            tag: "task.new"
        ) { [key] values in
            let (request, start) = TaskRules.create(values)
            let task = try await app.api.call { try await TasksAPI.tasksCreateTask(xHubProfile: profile, taskCreate: request, idempotencyKey: key, apiConfiguration: $0) }
            if let agent = start {
                let assign = TaskAssign(agentId: agent, start: true)
                _ = try await app.api.call { try await TasksAPI.tasksAssignTask(xHubProfile: profile, taskId: task.id, taskAssign: assign, apiConfiguration: $0) }
            }
            created(task)
        }
    }

    private func load() async {
        let profile = app.currentProfile
        let list = try? await app.api.call { try await TasksAPI.tasksListProjects(xHubProfile: profile, apiConfiguration: $0) }
        let found = try? await app.api.call { try await AgentsAPI.agentsList(xHubProfile: profile, apiConfiguration: $0) }
        agents = TaskRules.startable(found?.items ?? [])
        projects = list?.items ?? []
    }
}

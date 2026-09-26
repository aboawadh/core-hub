// What a task's detail offers and what its forms send (batch 2, Tasks I), apart from the views so
// CoreHubTests checks them. The web's rules (packages/web/src/tasks/TasksScreen.tsx, TaskDialog.tsx,
// AssignDialog.tsx) in the phone's words; Android's TaskDetail.kt `TaskRules` is the twin.
import CoreHubClient
import Foundation

enum TaskRules {
    /// The moves a person may make from `status` — the board's own table (BoardLogic), inside its
    /// column too (todo → ready) and on to the archive — each meaning once, so the detail never
    /// offers a move the hub would refuse nor two buttons that say the same.
    static func moves(from status: TaskStatus) -> [BoardLogic.Drop] {
        let targets: [TaskStatus] = [.todo, .ready, .scheduled, .blocked, .review, .done, .archived]
        var seen = Set<String>()
        return targets.compactMap { to in
            guard let found = BoardLogic.transition(from: status, to: to), seen.insert(found.action.rawValue).inserted else { return nil }
            return BoardLogic.Drop(to: to, transition: found)
        }
    }

    /// A card from Hermes's own board: Hermes runs and assigns it (§103).
    static func fromHermes(_ external: HubTaskAllOfExternal?) -> Bool { external?.source == .hermes }

    static func canStop(_ status: TaskStatus) -> Bool { status == .running }

    /// A task can be given to an agent until it is finished; a Hermes card is not (Hermes dispatches it).
    static func canAssign(_ status: TaskStatus, hermes: Bool) -> Bool {
        !hermes && status != .done && status != .archived
    }

    static func canUnassign(_ assignee: Assignee?, hermes: Bool) -> Bool {
        !hermes && assignee?.kind == .agent
    }

    /// What it still waits for, said only before it runs (§93).
    static func waiting(_ status: TaskStatus, _ waitingOn: [TaskDependencyState]?) -> [TaskDependencyState] {
        [.running, .review, .done, .archived].contains(status) ? [] : (waitingOn ?? [])
    }

    /// How many of the tasks it depends on are done already.
    static func doneDependencies(dependsOn: [String], waitingOn: [TaskDependencyState]?) -> Int {
        max(0, dependsOn.count - (waitingOn ?? []).count)
    }

    /// The agents a task can be given to: the chat's rule (enabled, installed on the hub).
    static func startable(_ agents: [Agent]) -> [Agent] {
        agents.filter { $0.enabled && [.available, .limited, .updating].contains($0.status) }
    }

    static let priorities: [TaskPriority] = [.low, .normal, .high, .urgent]

    /// The choice that means "none" in the create form's project and agent pickers.
    static let none = "none"

    // MARK: - The forms' values

    /// The edit form filled from a task.
    static func editValues(title: String, description: String?, priority: TaskPriority, projectID: String) -> [String: String] {
        ["title": title, "description": description ?? "", "priority": priority.rawValue, "project": projectID]
    }

    /// Only what changed, or nil when nothing did. A cleared description is sent as empty text:
    /// the generated client leaves a nil field out, so it cannot send `null`.
    static func patch(from original: [String: String], to values: [String: String]) -> TaskPatch? {
        var patch = TaskPatch()
        var changed = false
        let title = (values["title"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !title.isEmpty, title != original["title"] { patch.title = title; changed = true }
        let description = values["description"] ?? ""
        if description != (original["description"] ?? "") { patch.description = description; changed = true }
        if let raw = values["priority"], raw != original["priority"], let priority = TaskPriority(rawValue: raw) {
            patch.priority = priority
            changed = true
        }
        if let project = values["project"], !project.isEmpty, project != original["project"] {
            patch.projectId = project
            changed = true
        }
        return changed ? patch : nil
    }

    /// A new task from the create form, and the agent to start it with when asked. Without a
    /// project it lands in the profile's own list; given an agent without "start now" it is only
    /// assigned. Starting is `tasks.assignTask(start: true)` after the create, as the web's
    /// "Assign and start".
    static func create(_ values: [String: String]) -> (task: TaskCreate, start: String?) {
        let clean = { (key: String) -> String? in
            let text = (values[key] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty || text == TaskRules.none ? nil : text
        }
        let agent = clean("agent")
        let task = TaskCreate(
            projectId: clean("project"),
            title: clean("title") ?? "",
            description: clean("description"),
            priority: clean("priority").flatMap(TaskPriority.init(rawValue:)),
            assigneeAgentId: agent
        )
        return (task, agent != nil && values["start"] == "true" ? agent : nil)
    }

    /// The assign form's answer: who, what to tell it, whether it starts now.
    static func assign(_ values: [String: String]) -> TaskAssign? {
        guard let agent = values["agent"], !agent.isEmpty else { return nil }
        let instructions = (values["instructions"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return TaskAssign(agentId: agent, instructions: instructions.isEmpty ? nil : instructions, start: values["start"] == "true")
    }
}

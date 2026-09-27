// Tasks II (batch 5), apart from the views so CoreHubTests checks them: a task's checklist
// (subtasks), its definition of done and constraints (§104), the board's bulk edits (§103) and the
// profile's projects. The web's rules (packages/web/src/tasks/CheckList.tsx, TasksScreen.tsx,
// ProjectDialog.tsx, queries.ts) in the phone's words; Android's TaskLists.kt is the twin.
import CoreHubClient
import Foundation
import SwiftUI

/// A task's checklist: lines ticked, added, dragged into another order, deleted.
enum SubtaskRules {
    static let titleMax = 300

    /// A tick: a done line goes back to do, any other line is done.
    static func toggled(_ status: Subtask.Status) -> SubtaskWrite.Status {
        status == .done ? .todo : .done
    }

    /// The title to add, or nil when there is nothing to add.
    static func title(_ typed: String) -> String? {
        let t = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : String(t.prefix(titleMax))
    }

    /// The lines' ids after a drag (`onMove`'s offsets).
    static func order(_ ids: [String], from: IndexSet, to: Int) -> [String] {
        var out = ids
        out.move(fromOffsets: from, toOffset: to)
        return out
    }

    /// What the hub is told after a drag: each line's new place, only for the lines whose place
    /// changed (the hub keeps an `index` per line and orders by it).
    static func reindex(_ lines: [Subtask], order: [String]) -> [(id: String, index: Int)] {
        let now = Dictionary(uniqueKeysWithValues: lines.map { ($0.id, $0.index) })
        return order.enumerated().compactMap { place, id in
            guard let index = now[id], index != place else { return nil }
            return (id, place)
        }
    }

    static func doneCount(_ lines: [Subtask]) -> Int { lines.filter { $0.status == .done }.count }
}

/// A definition of done or the constraints (§104): lines the person writes, the agent is given
/// with every run, and the reviewer ticks while the task is in review. The whole list is sent.
enum CheckLines {
    enum Kind: String { case done = "dod", constraints }

    static let lineMax = 500
    static let linesMax = 30

    /// Only the reviewer ticks, and only while the task is in review.
    static func canTick(_ status: TaskStatus) -> Bool { status == .review }

    /// The list with a new line, or nil when the line is empty or the list is full.
    static func adding(_ items: [TaskCheckItem], _ typed: String) -> [TaskCheckItem]? {
        let t = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, items.count < linesMax else { return nil }
        return items + [TaskCheckItem(text: String(t.prefix(lineMax)), checked: false)]
    }

    static func removing(_ items: [TaskCheckItem], at offsets: IndexSet) -> [TaskCheckItem] {
        items.enumerated().filter { !offsets.contains($0.offset) }.map(\.element)
    }

    static func ticking(_ items: [TaskCheckItem], at index: Int, _ checked: Bool) -> [TaskCheckItem] {
        items.enumerated().map { $0.offset == index ? TaskCheckItem(text: $0.element.text, checked: checked) : $0.element }
    }

    /// The edit that replaces one list.
    static func patch(_ kind: Kind, _ items: [TaskCheckItem]) -> TaskPatch {
        let lines = items.map { TaskCheckItemWrite(text: $0.text, checked: $0.checked) }
        return kind == .done ? TaskPatch(definitionOfDone: lines) : TaskPatch(constraints: lines)
    }
}

/// The same change on many cards (§103). The board holds every profile, so the cards go in one
/// call per profile they are in, at most a hundred ids a call.
enum BulkRules {
    static let perCall = 100

    /// The ticked cards by profile, in the order they were met, each list cut to calls.
    static func calls(_ tasks: [HubTask]) -> [(profile: String, ids: [String])] {
        var order: [String] = []
        var byProfile: [String: [String]] = [:]
        for task in tasks {
            if byProfile[task.profile] == nil { order.append(task.profile) }
            byProfile[task.profile, default: []].append(task.id)
        }
        return order.flatMap { profile in
            stride(from: 0, to: byProfile[profile]!.count, by: perCall).map { start in
                (profile, Array(byProfile[profile]![start ..< min(start + perCall, byProfile[profile]!.count)]))
            }
        }
    }

    /// `tasks.bulkDeleteTasks` takes the ids in one query value, separated by commas.
    static func idsParam(_ ids: [String]) -> String { ids.joined(separator: ",") }

    /// How many were changed and how many the hub (or Hermes) refused.
    static func tally(_ answers: [BulkResult]) -> (changed: Int, refused: Int) {
        let all = answers.flatMap(\.results)
        let changed = all.filter(\.ok).count
        return (changed, all.count - changed)
    }

    /// Archiving is for finished work: only a done card can go (the hub refuses the rest).
    static func archivable(_ tasks: [HubTask]) -> Bool { !tasks.isEmpty && tasks.allSatisfy { $0.status == .done } }
}

/// A profile's projects: made, renamed, paused or archived, their repository set, deleted.
enum ProjectRules {
    static let nameMax = 120
    static let statuses: [ProjectStatus] = [.active, .paused, .archived]

    /// The edit form filled from a project; empty for a new one.
    static func values(_ project: Project?) -> [String: String] {
        [
            "name": project?.name ?? "",
            "status": (project?.status ?? .active).rawValue,
            "repository": project?.workingDir ?? "",
            "branch": project?.defaultBranch ?? "",
        ]
    }

    private static func clean(_ values: [String: String], _ key: String) -> String {
        (values[key] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// A new project: its name, and its repository and branch when given.
    static func create(_ values: [String: String]) -> ProjectWrite {
        let repository = clean(values, "repository"), branch = clean(values, "branch")
        return ProjectWrite(
            name: String(clean(values, "name").prefix(nameMax)),
            workingDir: repository.isEmpty ? nil : repository,
            defaultBranch: branch.isEmpty ? nil : branch
        )
    }

    /// Only what changed, or nil. A repository emptied is sent as `null` (§114): the project's
    /// tasks then work in their conversation's own folder. An emptied branch is left as it was
    /// (the hub keeps one), as on the web.
    static func patch(from original: [String: String], to values: [String: String]) -> ProjectWrite? {
        var write = ProjectWrite()
        var changed = false
        let name = clean(values, "name")
        if !name.isEmpty, name != original["name"] { write.name = String(name.prefix(nameMax)); changed = true }
        if let raw = values["status"], raw != original["status"], let status = ProjectStatus(rawValue: raw) {
            write.status = status
            changed = true
        }
        let repository = clean(values, "repository")
        if repository != (original["repository"] ?? "") {
            if repository.isEmpty { write.sendNull.insert(.workingDir) } else { write.workingDir = repository }
            changed = true
        }
        let branch = clean(values, "branch")
        if !branch.isEmpty, branch != (original["branch"] ?? "") { write.defaultBranch = branch; changed = true }
        return changed ? write : nil
    }

    /// Archive, or bring back from the archive.
    static func toggledArchive(_ status: ProjectStatus) -> ProjectStatus { status == .archived ? .active : .archived }
}

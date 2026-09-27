// The rules of an agent's Skills, Memory and Plugins pages and of the cards on the Agents page (apps
// batch 8), apart from the screens so they are tested without drawing. They follow the web's
// `agents/AgentSkillsScreen.tsx`, `memoryEntries.ts`, `AgentPluginsScreen.tsx`, `AgentManagerScreen.tsx`
// and `versionNotes.ts`; Android's `ui/screens/agent/AgentToolRules.kt` is the twin.
import CoreHubClient
import Foundation

enum SkillRules {
    /// The category folder Core Hub's own library lives in (decision §71).
    static let libraryCategory = "core-hub"

    /// The filter chips over the list: all, the person's own (and imported), the library, Hermes's.
    enum Filter: String, CaseIterable, Identifiable {
        case all, yours, library, builtin
        var id: String { rawValue }
    }

    static func inFilter(_ skill: Skill, _ filter: Filter) -> Bool {
        switch filter {
        case .all: return true
        case .yours: return skill.source == .user || skill.source == .external
        case .library: return skill.source == .library
        case .builtin: return skill.source == .builtin
        }
    }

    /// The categories with only the skills the search and the chip keep; empty categories drop out.
    static func narrow(_ categories: [SkillCategory], query: String, filter: Filter) -> [SkillCategory] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return categories.compactMap { category in
            var copy = category
            copy.skills = category.skills.filter { skill in
                inFilter(skill, filter) && (needle.isEmpty || skill.name.lowercased().contains(needle)
                    || skill.key.lowercased().contains(needle) || (skill.description ?? "").lowercased().contains(needle))
            }
            return copy.skills.isEmpty ? nil : copy
        }
    }

    static func total(_ categories: [SkillCategory]) -> Int { categories.reduce(0) { $0 + $1.skills.count } }

    /// A new skill's key: its folder's name, as the hub's path accepts it.
    static func validKey(_ key: String) -> Bool {
        key.range(of: "^[a-z0-9][a-z0-9._-]{0,63}$", options: .regularExpression) != nil
    }

    /// What a new skill starts as: the front matter Hermes needs, to fill in.
    static let template = "---\nname: \ndescription: \n---\n\n"

    /// The hub marks a file it cannot read by putting the reason where the description goes.
    static func broken(_ skill: Skill) -> Bool { (skill.description ?? "").hasPrefix("[") }

    /// Hermes's own: read, pinned and switched, never written or deleted from here.
    static func readOnly(_ skill: Skill) -> Bool { skill.source == .builtin }

    static func edited(_ skill: Skill) -> Bool { skill.source == .library && skill.library == .edited }

    enum Action: Equatable { case open, pin, unpin, restore, delete }

    /// A row's menu: open, pin or unpin, restore an edited library skill, delete what is not Hermes's.
    static func actions(_ skill: Skill) -> [Action] {
        var list: [Action] = [.open, skill.pinned ? .unpin : .pin]
        if edited(skill) { list.append(.restore) }
        if !readOnly(skill) { list.append(.delete) }
        return list
    }

    /// What the library card's line says.
    enum LibraryLine: Equatable { case on, none, off }

    static func libraryLine(_ library: SkillLibrary) -> LibraryLine {
        if !library.enabled { return .off }
        return library.installed == 0 ? .none : .on
    }

    /// On, but not every skill of it is here: the card offers Install.
    static func libraryMissing(_ library: SkillLibrary) -> Bool { library.enabled && library.installed < library.available }

    /// A file the hub takes as a skill pack (`agents.importSkills`): a SKILL.md or a zip.
    static func importable(_ fileName: String) -> Bool {
        ["md", "markdown", "zip", "skill"].contains((fileName as NSString).pathExtension.lowercased())
    }
}

/// The two memory lists as Hermes keeps them (decision §102): short entries separated by a line
/// holding only `§`, within a budget in characters counted as code points (web `memoryEntries.ts`).
enum MemoryRules {
    static let separator = "\n§\n"

    static func entries(of text: String) -> [String] {
        let normal = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        var parts: [String] = []
        var current: [Substring] = []
        for line in normal.split(separator: "\n", omittingEmptySubsequences: false) {
            if line.trimmingCharacters(in: CharacterSet(charactersIn: " \t")) == "§" {
                parts.append(current.joined(separator: "\n"))
                current = []
            } else {
                current.append(line)
            }
        }
        parts.append(current.joined(separator: "\n"))
        return parts.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
    }

    static func join(_ entries: [String]) -> String { entries.joined(separator: separator) }

    /// Characters as the budget counts them: code points, so an emoji is one.
    static func length(_ entries: [String]) -> Int { join(entries).unicodeScalars.count }

    static func list(of item: MemoryItem) -> [String] { item.entries ?? entries(of: item.content ?? "") }

    /// The two memories are lists; the persona (`soul`) and an entry are one text.
    static func isList(_ item: MemoryItem) -> Bool { item.kind == .document && item.id != "soul" }

    /// One of the three documents Hermes keeps, which have names of ours.
    static func known(_ item: MemoryItem) -> Bool { ["soul", "memory", "user"].contains(item.id) }

    enum Tone: Equatable { case normal, warning, danger }

    /// Warning from 80 %, danger past the limit.
    static func tone(_ count: Int, limit: Int?) -> Tone {
        guard let limit, limit > 0 else { return .normal }
        if count > limit { return .danger }
        return Double(count) >= Double(limit) * 0.8 ? .warning : .normal
    }

    /// Within the budget, or — for a list already over it — not longer than it is now.
    static func fits(_ next: Int, current: Int, limit: Int?) -> Bool {
        guard let limit, limit > 0 else { return true }
        return next <= limit || next <= current
    }

    /// The list with entry `index` replaced by what was typed (added last when nil); a `§` line makes two.
    static func with(_ list: [String], at index: Int?, typed: String) -> [String] {
        let parts = entries(of: typed)
        guard let index, list.indices.contains(index) else { return list + parts }
        return Array(list[..<index]) + parts + Array(list[(index + 1)...])
    }

    static func without(_ list: [String], at index: Int) -> [String] {
        list.enumerated().filter { $0.offset != index }.map(\.element)
    }
}

enum PluginRules {
    /// A catalog name, `owner/repo` or a Git URL: one word, never an option (the contract's pattern).
    static func validIdentifier(_ text: String) -> Bool {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return !value.isEmpty && value.count <= 400 && !value.hasPrefix("-") && !value.contains(where: \.isWhitespace)
    }
}

/// What a card on the Agents page offers, as the web's card and its Updates card do.
enum AgentCardRules {
    enum Action: String, Equatable { case install, uninstall, restart, checkUpdate, upgrade, autoUpdateOn, autoUpdateOff }

    /// An agent that is actually here (web `sections.ts` `configurable`).
    static func installed(_ agent: Agent) -> Bool { agent.status != .notInstalled && agent.install.source != ._none }

    /// Only a Hermes the hub supervises restarts (web `canRestart`).
    static func canRestart(_ agent: Agent) -> Bool { agent.kind == .hermes && agent.runtime.state != .notApplicable }

    /// One the hub installed from a registry, not one that ships with the image.
    static func updatable(_ agent: Agent) -> Bool {
        installed(agent) && agent.install.source != .builtin && agent.install.source != ._none
    }

    /// The version an update would install, or nil.
    static func update(_ agent: Agent) -> String? {
        updatable(agent) && agent.install.updateAvailable ? agent.install.latestVersion : nil
    }

    /// The update on offer is past the version Core Hub was tested with.
    static func updateUntested(_ agent: Agent) -> Bool {
        guard let update = update(agent), let tested = agent.install.pinnedVersion else { return false }
        return update != tested
    }

    /// The one button on the card: install what is missing, else take an update, else restart.
    static func primary(_ agent: Agent) -> Action? {
        if !installed(agent) { return .install }
        if update(agent) != nil { return .upgrade }
        if canRestart(agent) { return .restart }
        return nil
    }

    /// The card's «⋯»: everything else it can do.
    static func menu(_ agent: Agent) -> [Action] {
        guard installed(agent) else { return [] }
        var list: [Action] = []
        if canRestart(agent) { list.append(.restart) }
        if updatable(agent) {
            list.append(.checkUpdate)
            if update(agent) != nil { list.append(.upgrade) }
            if agent.install.autoUpdateSupported { list.append(agent.install.autoUpdate ? .autoUpdateOff : .autoUpdateOn) }
        }
        if agent.install.source == .managed { list.append(.uninstall) }
        let first = primary(agent)
        return list.filter { $0 != first }
    }

    static func terminal(_ status: JobStatus) -> Bool { status == .succeeded || status == .failed || status == .cancelled }

    /// How far along the bar is, 0…1: the job's percent, else a third while it runs.
    static func progress(_ job: Job) -> Double {
        if let percent = job.progress.percent { return Double(min(max(percent, 0), 100)) / 100 }
        return terminal(job.status) ? 1 : 0.3
    }

    /// A string in the job's `result` (an install's `version`, a plugin's `name`).
    static func result(_ job: Job, _ key: String) -> String? {
        if case .string(let value)? = job.result?[key] { return value }
        return nil
    }

    /// A check's answer: whether its `result` says an update is available.
    static func resultFlag(_ job: Job, _ key: String) -> Bool {
        if case .bool(let value)? = job.result?[key] { return value }
        return false
    }
}

/// The pages' refusals in our words where the hub names why (web `toolErrors.ts`).
enum AgentToolErrors {
    static let importReasons: Set<String> = [
        "pack_unrecognised", "pack_has_no_skill", "pack_path_unsafe", "pack_unsupported", "pack_corrupt", "pack_too_large",
        "skill_empty", "skill_too_large", "skill_front_matter_missing", "skill_front_matter_unclosed", "skill_front_matter_invalid",
        "skill_name_required", "skill_description_required", "skill_description_too_long", "skill_body_empty", "skill_name_invalid",
        "skill_not_utf8", "skill_nested", "skill_duplicate", "skill_exists",
    ]

    static let others: [String: String] = [
        "skill_bundled": "agents.skill.bundled_refused",
        "skill_essential": "agents.skill.essential_refused",
        "skill_not_library": "agents.skill.not_library",
        "skill_library_off": "agents.skill.library_off_refused",
        "plugin_bundled": "agents.plugin.bundled_refused",
        "hermes_not_supervised": "agents.tools.not_supervised",
        "memory_too_long": "agents.memory.too_long_refused",
    ]

    /// The key that leads the refusal, and whether the hub's own sentence follows it (an import's precise reason).
    static func lead(reason: String?) -> (key: String, withHubText: Bool)? {
        guard let reason else { return nil }
        if importReasons.contains(reason) { return ("agents.import.\(reason)", true) }
        return others[reason].map { ($0, false) }
    }

    static func describe(_ error: Error, _ l10n: L10n) -> String {
        let failure = HubFailure(error)
        guard let lead = lead(reason: failure.reason) else { return failure.describe(l10n) }
        let words = l10n(lead.key)
        if lead.withHubText, let message = failure.message, !message.isEmpty { return "\(words): \(message)" }
        return words
    }
}

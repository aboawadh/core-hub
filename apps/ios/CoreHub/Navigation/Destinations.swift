// The app's navigation registry, one-to-one with docs/clients/navigation.json (NAVIGATION.md).
// Every entry list here is the manifest's list in the manifest's order; the parity test reads
// the manifest itself and fails on the first difference.
import CoreHubClient
import Foundation

enum DestinationID: String, CaseIterable, Hashable, Identifiable {
    case newChat = "new_chat"
    case search
    case deviceConnections = "device_connections"
    case agentManager = "agent_manager"
    case models
    case knowledge
    case linkedHubs = "linked_hubs"
    case chat
    case rooms
    case tasks
    case schedules
    case workflows
    case settings
    case account
    case users
    case webhooks
    case display
    case notifications
    case privacy
    case thisDevice = "this_device"
    case about
    case logs
    case usage
    case skillsUsage = "skills_usage"
    case performance
    case theme
    case workspaces
    case updates
    case plugins
    case files
    case terminal
    case agentSkills = "agent_skills"
    case agentMcp = "agent_mcp"
    case agentMemory = "agent_memory"
    case agentJobs = "agent_jobs"
    case agentChannels = "agent_channels"
    case agentPlugins = "agent_plugins"
    case agentConfigFiles = "agent_config_files"
    case agentSettings = "agent_settings"
    case globalAgent = "global_agent"

    var id: String { rawValue }

    /// The `terms` key of the screen's title; the entry that opens it shows the same key.
    var titleTerm: String {
        switch self {
        case .agentSkills: return "skills"
        case .agentMcp: return "mcp"
        case .agentMemory: return "memory"
        case .agentJobs: return "jobs"
        case .agentChannels: return "channels"
        case .agentPlugins: return "plugins"
        case .agentConfigFiles: return "config_files"
        default: return rawValue
        }
    }

    /// The locale key of the title (`nav.<term>`).
    var titleKey: String { "nav.\(titleTerm)" }

    /// Owners and admins only (`roles: ["admin"]`).
    var adminOnly: Bool {
        switch self {
        case .agentManager, .linkedHubs, .users, .webhooks, .logs, .performance, .workspaces, .updates, .plugins, .files,
             .agentSkills, .agentMcp, .agentMemory, .agentJobs, .agentChannels, .agentPlugins, .agentConfigFiles, .agentSettings:
            return true
        default:
            return false
        }
    }

    /// The owner alone (`roles: ["owner"]`): the terminal.
    var ownerOnly: Bool { self == .terminal }

    /// The adapter capability an agent-level page needs (`capability`); the client never
    /// decides it (NAVIGATION.md rule 3).
    var capability: String? {
        switch self {
        case .agentSkills: return "skills"
        case .agentMcp: return "mcp"
        case .agentMemory: return "memory"
        case .agentJobs: return "jobs"
        case .agentChannels: return "channels"
        case .agentPlugins: return "plugins"
        case .agentConfigFiles: return "config_files"
        case .agentSettings: return "settings"
        default: return nil
        }
    }

    /// Screens outside any profile say so (users, updates, performance).
    var isGlobal: Bool { self == .users || self == .performance || self == .updates }
}

enum NavigationMap {
    static let rail: [DestinationID] = [.newChat, .search, .agentManager, .tasks, .schedules]
    /// `railExtra`: rail entries added after `rail` was fixed, so an app built before them still
    /// matches `rail` exactly (DECISIONS §126). The drawer draws them with the rail.
    static let railExtra: [DestinationID] = [.workflows]
    /// `brandRow`: on a phone an icon in the drawer's header beside its close button, not a row
    /// (DECISIONS §128).
    static let brandRow: [DestinationID] = [.search]
    /// `sidebarGroups`: rail entries gathered under one heading that opens and closes.
    static let sidebarGroups: [SidebarGroup] = [
        SidebarGroup(id: "tools", title: "tools", items: [.agentManager, .tasks, .workflows, .schedules]),
    ]
    static let segments: [DestinationID] = [.chat, .rooms]
    static let footer: [DestinationID] = [.settings]
    static let settingsTabs: [DestinationID] = [.account, .users, .webhooks, .display, .notifications, .privacy, .thisDevice, .about]
    static let settingsManagement: [DestinationID] = [.models, .deviceConnections, .knowledge, .linkedHubs]
    static let settingsTools: [DestinationID] = [
        .logs, .usage, .skillsUsage, .performance, .theme, .workspaces, .updates, .plugins, .files, .terminal,
    ]
    static let agentLevel: [DestinationID] = [.agentSkills, .agentMcp, .agentMemory, .agentJobs, .agentChannels, .agentPlugins, .agentConfigFiles, .agentSettings]
    /// Reached only from these entries (`secondaryEntries`).
    static let secondaryEntries: [DestinationID: [DestinationID]] = [.chat: [.search], .globalAgent: [.search]]
    /// The pre-auth screens (`preAuth`): not destinations.
    static let preAuth: [String] = ["login", "setup"]
    /// The back row inside an agent's pages (`agentShell`).
    static let agentBackTerm = "back_to_agents"

    /// Entries a person with this role sees, in order.
    static func visible(_ list: [DestinationID], admin: Bool, owner: Bool = false) -> [DestinationID] {
        list.filter { (admin || !$0.adminOnly) && (owner || !$0.ownerOnly) }
    }

    /// The drawer's rail as drawn: `rail` then `railExtra` in order, without the brand row's
    /// entries; a group's members under its heading at the place of its first member, only those
    /// the role allows; a group with none is not drawn.
    static func drawer(admin: Bool, owner: Bool = false) -> [DrawerEntry] {
        var entries: [DrawerEntry] = []
        var drawn = Set<String>()
        for destination in rail + railExtra where !brandRow.contains(destination) {
            if let group = sidebarGroups.first(where: { $0.items.contains(destination) }) {
                guard drawn.insert(group.id).inserted else { continue }
                let members = visible(group.items, admin: admin, owner: owner)
                if !members.isEmpty { entries.append(.group(group, members)) }
            } else if admin || !destination.adminOnly, owner || !destination.ownerOnly {
                entries.append(.row(destination))
            }
        }
        return entries
    }

    /// An agent's menu: only what its adapter declares, in `agentLevel` order; Settings for
    /// every installed agent (navigation.json note on `agent_settings`).
    static func agentMenu(capabilities: [String], installed: Bool) -> [DestinationID] {
        agentLevel.filter { destination in
            guard let capability = destination.capability else { return false }
            if destination == .agentSettings { return installed || capabilities.contains(capability) }
            return capabilities.contains(capability)
        }
    }
}

/// One of `sidebarGroups`: not a destination. `title` is a term (`nav.<title>`); `items` are rail
/// entries in the order shown.
struct SidebarGroup: Equatable, Hashable {
    let id: String
    let title: String
    let items: [DestinationID]

    /// The locale key of the heading.
    var titleKey: String { "nav.\(title)" }
}

/// A line of the drawer's rail: a destination's row, or a group's heading with the members the
/// person may see.
enum DrawerEntry: Equatable, Identifiable {
    case row(DestinationID)
    case group(SidebarGroup, [DestinationID])

    var id: String {
        switch self {
        case .row(let destination): return destination.rawValue
        case .group(let group, _): return "group.\(group.id)"
        }
    }
}

/// Which drawer groups are closed on this phone: the device's own choice, open by default
/// (`sidebarGroups`, DECISIONS §128). Anything unreadable under the key counts as all open.
struct SidebarGroupState {
    static let key = "sidebar.groupsClosed"
    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The ids of the closed groups.
    var closed: Set<String> {
        guard let list = defaults.object(forKey: SidebarGroupState.key) as? [String] else { return [] }
        return Set(list)
    }

    func isOpen(_ id: String) -> Bool { !closed.contains(id) }

    /// Opens a closed group or closes an open one, remembers it, and returns the closed ids.
    @discardableResult
    func toggle(_ id: String) -> Set<String> {
        var next = closed
        if next.contains(id) { next.remove(id) } else { next.insert(id) }
        defaults.set(next.sorted(), forKey: SidebarGroupState.key)
        return next
    }

    /// Closed while the current page is one of its members, the heading is the current place.
    static func headingMarked(_ group: SidebarGroup, open: Bool, current: DestinationID?) -> Bool {
        guard !open, let current else { return false }
        return group.items.contains(current)
    }
}

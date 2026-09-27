// The phone's page registry (docs/clients/phone-pages.md). Every Settings page and every agent page
// is a `PhonePage` declared in its own file, next to the view it draws (`extension PhonePage {
// static let <destination> = … }`); `PageRegistry` names each one once, in the manifest's order, and
// is complete: a page that is not native yet already has its file, whose entry says `native: false`
// and draws the one fallback, `NotNativePage`. Making a page native edits only that page's file: draw
// the page and drop `native: false`. NavigationParityTests checks the lists against navigation.json.
import CoreHubClient
import SwiftUI

/// What a page gets: its destination, and for an agent-level page the agent it edits.
struct PageContext {
    let destination: DestinationID
    var agent: Agent?
}

/// One page of the phone: its destination, whether the phone draws it, and what it draws.
struct PhonePage {
    let destination: DestinationID
    let native: Bool
    private let make: @MainActor (PageContext) -> AnyView

    init<Content: View>(
        _ destination: DestinationID,
        native: Bool = true,
        @ViewBuilder _ view: @escaping @MainActor (PageContext) -> Content
    ) {
        self.destination = destination
        self.native = native
        self.make = { @MainActor context in AnyView(view(context)) }
    }

    @MainActor func view(_ context: PageContext) -> AnyView { make(context) }
}

enum PageRegistry {
    /// Every Settings destination, in the manifest's order (tabs, management, tools).
    static let settings: [PhonePage] = [
        .account, .users, .webhooks, .display, .notifications, .privacy, .thisDevice, .about,
        .models, .deviceConnections, .knowledge, .linkedHubs,
        .logs, .usage, .skillsUsage, .performance, .theme, .workspaces, .updates, .plugins, .files, .terminal,
    ]

    /// Every agent-level destination, in `agentLevel` order.
    static let agent: [PhonePage] = [
        .agentSkills, .agentMcp, .agentMemory, .agentJobs, .agentChannels, .agentPlugins, .agentConfigFiles, .agentSettings,
    ]

    static func page(_ destination: DestinationID) -> PhonePage? {
        settings.first { $0.destination == destination } ?? agent.first { $0.destination == destination }
    }

    /// The pages still drawn by the fallback.
    static var notNative: [DestinationID] { (settings + agent).filter { !$0.native }.map(\.destination) }
}

/// A registered page, or the fallback when it is not native (or not registered).
struct RegisteredPage: View {
    let destination: DestinationID
    var agent: Agent?

    var body: some View {
        if let page = PageRegistry.page(destination), page.native {
            page.view(PageContext(destination: destination, agent: agent))
        } else {
            NotNativePage(destination: destination)
        }
    }
}

/// The one fallback for a page the phone does not draw yet: its title and a sentence that says so
/// (PlaceholderScreen) — never an empty page.
struct NotNativePage: View {
    let destination: DestinationID

    var body: some View {
        PlaceholderScreen(destination: destination)
    }
}

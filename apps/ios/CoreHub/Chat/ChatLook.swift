// The person's display preferences the hub keeps (`auth.me.preferences`, Settings → Display), as the
// chat draws them: the reasoning and the tool steps shown or not, a compact conversation, the text
// size, where a link opens, and what sending does while the agent works. Read once at sign-in and
// again after Display saves; the defaults are the contract's.
import CoreHubClient
import SwiftUI

struct ChatLook: Equatable {
    var showReasoning = true
    var showToolCalls = true
    var compact = false
    var textScale: CGFloat = 1
    /// Links open inside the app (a Safari view) rather than in the Safari app.
    var linksInApp = true
    var busyInput: RunCreate.When = .queue

    init() {}

    init(_ preferences: Preferences?) {
        guard let preferences else { return }
        showReasoning = preferences.showReasoning
        showToolCalls = preferences.showToolCalls
        compact = preferences.compact
        // The contract's range (0.85–1.45); anything else is read as 1.
        textScale = (0.85...1.45).contains(preferences.textScale) ? CGFloat(preferences.textScale) : 1
        linksInApp = preferences.linkTarget == .inApp
        switch preferences.busyInputMode {
        case .queue: busyInput = .queue
        case .next: busyInput = .next
        case .interrupt: busyInput = .interrupt
        }
    }

    /// A text size in the person's scale.
    func size(_ base: CGFloat) -> CGFloat { (base * textScale).rounded() }

    /// The space above a message: tighter in a compact conversation.
    func gap(startsTurn: Bool) -> CGFloat {
        if compact { return startsTurn ? Space.s3 : Space.s1 / 2 }
        return startsTurn ? Layout.turnGap : Layout.groupGap
    }

    /// The padding inside a message.
    var padding: CGFloat { compact ? Space.s2 : Space.s3 }
}

private struct ChatLookKey: EnvironmentKey {
    static let defaultValue = ChatLook()
}

extension EnvironmentValues {
    var chatLook: ChatLook {
        get { self[ChatLookKey.self] }
        set { self[ChatLookKey.self] = newValue }
    }
}

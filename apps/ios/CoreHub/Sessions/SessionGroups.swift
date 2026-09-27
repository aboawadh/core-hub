// The chats list in groups, as on the web (contract decisions §60, §61, §88, §102): the profile's
// categories first, in their order, each with its colour; then one group per messaging channel —
// the hub's chats that came from it and the conversations Hermes keeps there (read-only
// transcripts); then everything else. Pure rules here, so the tests read them directly; the list
// draws what they return (SessionList.swift).
import CoreHubClient
import Foundation
import SwiftUI

struct SessionGroup: Identifiable, Equatable {
    enum Kind: Equatable {
        case category(SessionCategory)
        case channel(String)
        case rest
    }

    let id: String
    let kind: Kind
    var sessions: [Session]
    var conversations: [ChannelConversation] = []
}

enum SessionGroups {
    /// Which group a chat sits in: a category the person chose wins over where it came from; a
    /// category this list does not know is no group rather than a hidden row.
    static func key(_ session: Session, known: Set<String>) -> String {
        if let category = session.categoryId, known.contains(category) { return "category:\(category)" }
        if session.source == .channel { return "channel:\(session.channel ?? "other")" }
        return "rest"
    }

    /// The groups in the order they are shown. Every category is listed even when empty — it is
    /// where a chat is moved to — unless `keepEmpty` is false (a search is typed). Channel groups
    /// exist only when something came from that channel. In each group, pinned chats first.
    static func group(_ sessions: [Session], categories: [SessionCategory], conversations: [ChannelConversation], keepEmpty: Bool, manual: [String] = []) -> [SessionGroup] {
        let known = Set(categories.map(\.id))
        var buckets: [String: [Session]] = [:]
        for session in sessions { buckets[key(session, known: known), default: []].append(session) }
        var groups: [SessionGroup] = []
        for category in categories.sorted(by: { ($0.position, $0.name) < ($1.position, $1.name) }) {
            let items = buckets["category:\(category.id)"] ?? []
            if items.isEmpty && !keepEmpty { continue }
            groups.append(SessionGroup(id: "category:\(category.id)", kind: .category(category), sessions: pinnedFirst(items, manual)))
        }
        var byChannel: [String: [ChannelConversation]] = [:]
        for conversation in conversations { byChannel[conversation.channel, default: []].append(conversation) }
        let fromSessions = buckets.keys.filter { $0.hasPrefix("channel:") }.map { String($0.dropFirst("channel:".count)) }
        let channels = Set(fromSessions).union(byChannel.keys).sorted { (rank($0), $0) < (rank($1), $1) }
        for channel in channels {
            groups.append(SessionGroup(
                id: "channel:\(channel)", kind: .channel(channel),
                sessions: pinnedFirst(buckets["channel:\(channel)"] ?? [], manual),
                conversations: (byChannel[channel] ?? []).sorted { $0.lastMessageAt > $1.lastMessageAt }
            ))
        }
        groups.append(SessionGroup(id: "rest", kind: .rest, sessions: pinnedFirst(buckets["rest"] ?? [], manual)))
        return groups
    }

    static func pinnedFirst(_ sessions: [Session], _ manual: [String] = []) -> [Session] {
        SessionOrder.arrange(sessions, manual: manual)
    }

    /// Telegram, then WhatsApp, then any other channel by name.
    private static func rank(_ platform: String) -> Int {
        ["telegram", "whatsapp"].firstIndex(of: platform) ?? 2
    }

    // MARK: - Channel conversations

    /// The platform's name in the reader's language.
    static func channelName(_ platform: String, _ l10n: L10n) -> String {
        platform == "telegram" || platform == "whatsapp" ? l10n("session_groups.channels.\(platform)") : platform
    }

    /// A group's heading for a channel.
    static func channelHeading(_ platform: String, _ l10n: L10n) -> String {
        platform == "telegram" || platform == "whatsapp" ? channelName(platform, l10n) : l10n("session_groups.channels.other", ["name": platform])
    }

    /// What a row is called: the other party, as a messaging app names a chat — else Hermes's
    /// title, the party's id, or "A Telegram conversation".
    static func title(_ conversation: ChannelConversation, _ l10n: L10n) -> String {
        conversation.peerName ?? conversation.title ?? conversation.peerId
            ?? l10n("session_groups.channels.untitled", ["channel": channelName(conversation.channel, l10n)])
    }

    /// The line under the title: the latest message when known, else Hermes's preview.
    static func preview(_ conversation: ChannelConversation, _ l10n: L10n) -> String {
        if let last = conversation.lastMessage {
            return last.role == .assistant ? l10n("session_groups.channels.agent_said", ["text": last.text]) : last.text
        }
        return conversation.preview ?? ""
    }

    /// The search over a conversation: its title, the other party, and its preview.
    static func matches(_ conversation: ChannelConversation, _ query: String) -> Bool {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if needle.isEmpty { return true }
        return [conversation.title, conversation.peerName, conversation.peerId, conversation.lastMessage?.text, conversation.preview]
            .contains { ($0 ?? "").lowercased().contains(needle) }
    }

    /// The conversations a list shows: hidden ones only when asked, and only those the search finds.
    static func shown(_ conversations: [ChannelConversation], showHidden: Bool, query: String) -> [ChannelConversation] {
        conversations.filter { (showHidden || $0.hidden != true) && matches($0, query) }
    }

    // MARK: - Categories

    /// The eight colours a category may have (the web's CategoryColour.tsx): what is stored is the
    /// `#rrggbb` the contract asks for, so every client draws the same colour.
    static let colours: [(id: String, hex: String)] = [
        ("blue", "#3b82f6"), ("green", "#22a06b"), ("amber", "#d97706"), ("red", "#dc2626"),
        ("purple", "#8b5cf6"), ("pink", "#db2777"), ("teal", "#0d9488"), ("slate", "#64748b"),
    ]

    /// A `#rrggbb` as a colour; nil for anything else.
    static func colour(_ hex: String?) -> Color? {
        guard let hex, hex.count == 7, hex.hasPrefix("#"), let value = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        return Color(red: Double((value >> 16) & 0xFF) / 255, green: Double((value >> 8) & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
    }

    /// The position a category moves to, one place up or down among its profile's (the hub
    /// reorders the rest, as for the web); nil at either end.
    static func moved(_ category: SessionCategory, by offset: Int, in categories: [SessionCategory]) -> Int? {
        let last = categories.filter { $0.profile == category.profile }.map(\.position).max() ?? 0
        let next = category.position + offset
        guard next >= 0, next <= last else { return nil }
        return next
    }
}

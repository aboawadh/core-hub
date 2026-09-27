// The order a person drags the chats into (the web's `sessions/order.ts`). The contract has no sort
// field (only `pinned`), so the order is kept on this phone, per view — every profile together or
// one profile — and laid over the hub's newest-first order; a chat it does not know keeps the hub's
// place. Moving a chat also works without dragging, from its menu (Move up / Move down).
import CoreHubClient
import Foundation

enum SessionOrder {
    static func key(_ scope: String) -> String { Product.storagePrefix + "sessionOrder." + scope }

    static func read(_ scope: String, defaults: UserDefaults = .standard) -> [String] {
        defaults.stringArray(forKey: key(scope)) ?? []
    }

    static func write(_ ids: [String], scope: String, defaults: UserDefaults = .standard) {
        defaults.set(ids, forKey: key(scope))
    }

    /// Pinned first; within each, the remembered order, then the hub's.
    static func arrange(_ sessions: [Session], manual: [String]) -> [Session] {
        let rank = Dictionary(manual.enumerated().map { ($1, $0) }, uniquingKeysWith: { first, _ in first })
        return sessions.enumerated().sorted { a, b in
            if a.element.pinned != b.element.pinned { return a.element.pinned }
            switch (rank[a.element.id], rank[b.element.id]) {
            case let (ra?, rb?): return ra < rb
            case (.some, nil): return true
            case (nil, .some): return false
            default: return a.offset < b.offset
            }
        }.map(\.element)
    }

    /// The new manual order after dropping `moved` just before `target` among `shown` (the group
    /// as it is drawn); nil when nothing changes.
    static func drop(_ moved: String, before target: String, shown: [String]) -> [String]? {
        guard moved != target, let from = shown.firstIndex(of: moved), shown.contains(target) else { return nil }
        var next = shown
        next.remove(at: from)
        let to = next.firstIndex(of: target) ?? next.count
        next.insert(moved, at: to)
        return next == shown ? nil : next
    }

    /// One place up (-1) or down (+1) in `shown`; nil at either end.
    static func step(_ moved: String, by offset: Int, shown: [String]) -> [String]? {
        guard let from = shown.firstIndex(of: moved), shown.indices.contains(from + offset) else { return nil }
        var next = shown
        next.swapAt(from, from + offset)
        return next
    }

    /// The remembered order after a group's new order: the group's ids in their new places, the
    /// rest of what was remembered after them.
    static func merge(_ group: [String], into manual: [String]) -> [String] {
        let moved = Set(group)
        return group + manual.filter { !moved.contains($0) }
    }
}

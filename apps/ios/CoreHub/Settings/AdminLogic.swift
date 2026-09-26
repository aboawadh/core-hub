// The admin pages' rules (people, notification settings, devices, usage), unit-tested in ModelsAdminTests.
import AVFoundation
import CoreHubClient
import SwiftUI
import UIKit

enum AdminLogic {
    static func usernameOK(_ name: String) -> Bool {
        name.trimmingCharacters(in: .whitespaces).range(of: "^[a-z0-9._-]{2,40}$", options: .regularExpression) != nil
    }

    static func passwordOK(_ password: String) -> Bool { password.count >= 8 }

    static func create(username: String, displayName: String, password: String, admin: Bool, profiles: [String]) -> UserCreate? {
        guard usernameOK(username), passwordOK(password), admin || !profiles.isEmpty else { return nil }
        let name = displayName.trimmingCharacters(in: .whitespaces)
        return UserCreate(
            username: username.trimmingCharacters(in: .whitespaces), password: password,
            displayName: name.isEmpty ? nil : name, role: admin ? .admin : .member,
            profiles: admin ? nil : profiles, defaultProfile: admin ? nil : profiles.first
        )
    }

    /// A kind's two switches; a missing kind is on in both (the contract's rule).
    static func value(_ prefs: NotifyPreferences, _ kind: NoticeKind) -> NotifyPreferencesEventsValue {
        prefs.events[kind.rawValue] ?? NotifyPreferencesEventsValue(inApp: true, push: true)
    }

    static func set(_ prefs: NotifyPreferences, _ kind: NoticeKind, inApp: Bool? = nil, push: Bool? = nil) -> NotifyPreferences {
        let current = prefs.events[kind.rawValue] ?? NotifyPreferencesEventsValue(inApp: true, push: true)
        var next = prefs
        next.events[kind.rawValue] = NotifyPreferencesEventsValue(inApp: inApp ?? current.inApp, push: push ?? current.push)
        return next
    }

    static func timeOK(_ text: String) -> Bool {
        guard text.range(of: "^[0-2][0-9]:[0-5][0-9]$", options: .regularExpression) != nil, let hours = Int(text.prefix(2)) else { return false }
        return hours < 24
    }

    static func tokens(_ n: Int) -> String {
        if n >= 1_000_000 { return String(format: "%.1fM", Double(n) / 1_000_000) }
        if n >= 1_000 { return String(format: "%.1fK", Double(n) / 1_000) }
        return String(n)
    }

    static func devices(_ list: [Device]) -> [Device] {
        list.sorted { a, b in
            if a.thisDevice != b.thisDevice { return a.thisDevice }
            return (a.lastSeenAt ?? a.createdAt) > (b.lastSeenAt ?? b.createdAt)
        }
    }
}

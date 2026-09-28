// The person's own settings (batch 4: inbox, account, privacy): the plain rules the pages follow,
// kept apart from the views so CoreHubTests can check them. Android's OwnSettingsRules.kt is the twin.
import CoreHubClient
import Foundation

enum OwnSettingsRules {
    // MARK: - Account

    /// The most the hub keeps for a display name (`UserSelfPatch.display_name`).
    static let nameMax = 80
    /// The shortest new password the hub takes (`PasswordChange.new_password`).
    static let passwordMin = 8

    /// The name to send, trimmed; nil when there is nothing to save (empty, too long, or unchanged).
    static func nameToSave(_ typed: String, current: String) -> String? {
        let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= nameMax, name != current else { return nil }
        return name
    }

    enum PasswordProblem: Equatable {
        case short
        case mismatch
    }

    /// What is wrong with the new password as typed so far (nothing is said about an empty field).
    static func passwordProblem(new: String, again: String) -> PasswordProblem? {
        if !new.isEmpty, new.count < passwordMin { return .short }
        if !again.isEmpty, again != new { return .mismatch }
        return nil
    }

    /// Change password is offered once all three are filled and agree.
    static func canChangePassword(current: String, new: String, again: String) -> Bool {
        !current.isEmpty && new.count >= passwordMin && again == new
    }

    // MARK: - Avatar

    /// The most the hub keeps for a picture (512 KB decoded, `auth/avatars`).
    static let avatarMaxBytes = 512 * 1024
    /// The longest side a picture is sent at: a face in a circle, never the camera's full size.
    static let avatarSide: Double = 512

    /// The size a picture is drawn at before it is sent: its longest side at most `avatarSide`,
    /// the shape kept, never made larger.
    static func avatarSize(width: Double, height: Double) -> (width: Double, height: Double) {
        guard width > 0, height > 0 else { return (0, 0) }
        let scale = min(1, avatarSide / max(width, height))
        return ((width * scale).rounded(), (height * scale).rounded())
    }

    /// The `data:` URL the hub takes for a JPEG, or nil when it is empty or too big.
    static func avatarDataURL(jpeg: Data) -> String? {
        guard !jpeg.isEmpty, jpeg.count <= avatarMaxBytes else { return nil }
        return "data:image/jpeg;base64," + jpeg.base64EncodedString()
    }

    /// The two letters shown when there is no picture: the first of the first two words.
    static func initials(_ name: String) -> String {
        let words = name.split(whereSeparator: { $0.isWhitespace }).prefix(2)
        let letters = words.compactMap(\.first).map(String.init).joined()
        return letters.isEmpty ? "?" : letters.uppercased()
    }

    // MARK: - Messaging accounts

    /// A code is waiting and the list grew: the account it was sent from is linked now.
    static func linked(waiting: Bool, before: Int?, now: Int) -> Bool {
        guard waiting, let before else { return false }
        return now > before
    }

    // MARK: - Inbox

    /// One notice read or unread here at once, the unread count moved with it (the hub says the same
    /// on the next read). A notice already in that state changes nothing.
    static func marking(_ notices: [Notice], unread: Int, id: String, read: Bool, at now: Date = Date()) -> (notices: [Notice], unread: Int) {
        guard let index = notices.firstIndex(where: { $0.id == id }) else { return (notices, unread) }
        let wasRead = notices[index].readAt != nil
        guard wasRead != read else { return (notices, unread) }
        var list = notices
        list[index].readAt = read ? now : nil
        return (list, max(0, unread + (read ? -1 : 1)))
    }

    /// Every notice read. In the «Unread» view the list empties.
    static func allRead(_ notices: [Notice], unreadOnly: Bool, at now: Date = Date()) -> [Notice] {
        if unreadOnly { return [] }
        return notices.map { notice in
            var copy = notice
            if copy.readAt == nil { copy.readAt = now }
            return copy
        }
    }

    /// Where a tapped notice leads (the same place its push opens): a conversation, the board,
    /// Schedules, or Workflows (a workflow run's own view when the notice names it).
    static func route(for notice: Notice, selector: String) -> MainContent? {
        let resource = notice.resource
        return NoticeRouting.route(
            kind: resource?.kind.rawValue,
            sessionID: resource?.kind == .session ? resource?.id : nil,
            profile: notice.profile,
            selector: selector,
            runID: resource?.kind == .workflowRun ? resource?.id : nil
        )
    }

    // MARK: - Privacy

    /// A token a paired device holds, or one an integration holds.
    static func isDevice(_ token: AppToken) -> Bool { token.deviceId != nil }

    /// What revoking says will follow: a device is unlinked, a token just stops.
    static func revokeBodyKey(_ token: AppToken) -> String {
        isDevice(token) ? "own_settings.privacy_revoke_device_body" : "own_settings.privacy_revoke_body"
    }
}

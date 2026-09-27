// The admin pages on the phone (apps batches 12 and 14): People, Profiles, the push senders on
// Device connections and pairing requests in the pending sheet. The rules are plain functions,
// apart from the views, so AdminPagesTests checks them; Android's AdminKit.kt is the twin.
import CoreHubClient
import Foundation

/// What a row of People offers: the hub's own rules (`auth/users.ts`), asked before the tap.
enum PeopleRules {
    enum Row: Equatable {
        /// Somebody else's owner account: nothing to do here, a line says so.
        case ownerNote
        /// The owner's own account: the password only.
        case ownPassword
        /// Anyone else: password, role, profiles; disable and delete unless it is the person's own.
        case full
    }

    static func row(_ user: User, me: String?) -> Row {
        if user.role == .owner { return user.id == me ? .ownPassword : .ownerNote }
        return .full
    }

    /// Nobody disables or deletes their own account (the hub refuses it).
    static func canDisableOrDelete(_ user: User, me: String?) -> Bool { user.role != .owner && user.id != me }

    enum Reach: Equatable { case every, none, listed }

    /// Owners and admins enter every profile; a member exactly the ones listed, none when empty.
    static func reach(_ user: User) -> Reach {
        if user.role != .member { return .every }
        return user.profiles.isEmpty ? .none : .listed
    }

    /// A member's new list, or an admin made a member with it: the hub refuses a bare role change to
    /// member (an admin holds no list), so the list goes with it. Nil while nothing is chosen.
    static func profilesPatch(_ chosen: [String], makeMember: Bool) -> UserAdminPatch? {
        guard !chosen.isEmpty else { return nil }
        return makeMember ? UserAdminPatch(role: .member, profiles: chosen) : UserAdminPatch(profiles: chosen)
    }

    /// An admin's `profiles` is every profile, not a choice anyone made: a member-to-be starts empty.
    static func startingChoice(_ user: User, makeMember: Bool) -> [String] { makeMember ? [] : user.profiles }

    /// Whose link a messaging account is: the person's name, else the id the hub gave.
    static func nameOf(_ userID: String, in users: [User]) -> String {
        guard let user = users.first(where: { $0.id == userID }) else { return userID }
        return user.displayName.isEmpty ? user.username : user.displayName
    }
}

/// Profiles: new, renamed, archived, exported, imported (web `WorkspacesTab` / `ProfileTransfer`).
enum ProfileRules {
    /// The contract's `ProfileName`: Hermes's own limit for a display name.
    static let nameMax = 64
    /// The hub's largest attachment (the contract's `UploadStart.size_bytes` maximum).
    static let maxArchiveBytes = 50 * 1024 * 1024

    /// The contract's `ProfileSlug`.
    static func slugOK(_ slug: String) -> Bool {
        slug.range(of: "^[a-z0-9][a-z0-9-]{0,38}[a-z0-9]$", options: .regularExpression) != nil
    }

    private static func dashed(_ text: String) -> String {
        text.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
    }

    /// A name as a slug: lowercase, dashes, nothing else. An Arabic name gives nothing: typed then.
    static func suggest(_ name: String) -> String { String(dashed(name).prefix(40)) }

    /// The slug follows the name until somebody types one.
    static func followName(slug: String, old: String, new: String) -> String {
        slug.isEmpty || slug == suggest(old) ? suggest(new) : slug
    }

    enum SlugProblem: Equatable { case bad, taken }

    static func slugProblem(_ slug: String, taken: [String]) -> SlugProblem? {
        if slug.isEmpty { return nil }
        if !slugOK(slug) { return .bad }
        return taken.contains(slug) ? .taken : nil
    }

    /// A new profile, from scratch or as a copy of `cloneFrom` (asked, never defaulted); nil until ready.
    static func create(name: String, slug: String, copy: Bool, cloneFrom: String?, taken: [String]) -> ProfileCreate? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= nameMax, !slug.isEmpty, slugProblem(slug, taken: taken) == nil else { return nil }
        if copy && cloneFrom == nil { return nil }
        return ProfileCreate(slug: slug, name: trimmed, cloneFrom: copy ? cloneFrom : nil)
    }

    /// The name as sent: trimmed; nil when empty or unchanged.
    static func rename(current: String, typed: String) -> ProfilePatch? {
        let name = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= nameMax, name != current else { return nil }
        return ProfilePatch(name: name)
    }

    /// `default` always exists and the hub refuses to archive it.
    static func canArchive(_ slug: String) -> Bool { slug != "default" }

    private static func withoutArchive(_ fileName: String) -> String {
        fileName.replacingOccurrences(of: "\\.(tar\\.gz|tgz)$", with: "", options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: "-\\d{8}-\\d{6}$", with: "", options: .regularExpression)
    }

    /// `design-20260924-101500.tar.gz` → `design`: the slug an archive suggests.
    static func slugFromArchive(_ fileName: String) -> String {
        var slug = String(dashed(withoutArchive(fileName.lowercased())).prefix(40))
        while slug.hasSuffix("-") { slug.removeLast() }
        return slug
    }

    /// `الرئيسي-20260925-101500.tar.gz` → `الرئيسي`: an export is named after the profile's name.
    static func nameFromArchive(_ fileName: String) -> String {
        String(withoutArchive(fileName).trimmingCharacters(in: .whitespaces).prefix(nameMax)).trimmingCharacters(in: .whitespaces)
    }

    /// The suggestion made free: `design`, else `design-2`, `design-3` …
    static func freeSlug(_ base: String, taken: [String]) -> String {
        if base.isEmpty { return "" }
        if !taken.contains(base) { return base }
        for n in 2..<100 {
            let candidate = "\(base.prefix(37))-\(n)"
            if !taken.contains(candidate) { return candidate }
        }
        return ""
    }

    /// What a chosen archive fills in: the slug (made free) and the name it offers.
    static func fromArchive(_ fileName: String, taken: [String]) -> (slug: String, name: String) {
        let base = slugFromArchive(fileName)
        let slug = freeSlug(base, taken: taken)
        let named = nameFromArchive(fileName)
        return (slug, !named.isEmpty && named != base ? named : slug)
    }

    static func importBody(attachmentID: String, slug: String, name: String, replaceDefault: Bool = false) -> ProfileImport {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard replaceDefault else {
            return ProfileImport(attachmentId: attachmentID, slug: slug, name: trimmed.isEmpty ? nil : trimmed)
        }
        // Replacing the default makes no new profile: the hub does not use the slug, which is still sent (decision §116).
        let sent = !slug.isEmpty && slugProblem(slug, taken: []) == nil ? slug : "imported"
        return ProfileImport(attachmentId: attachmentID, slug: sent, name: trimmed.isEmpty ? nil : trimmed, replaceDefault: true)
    }

    /// The profile an import into the default kept the old default as (`default-backup`, `-2`, …; decision §116).
    /// Nil for any other import — and for a hub older than the option, which made a new profile instead.
    static func replacedBackup(_ job: Job?) -> String? {
        guard let job, job.status == .succeeded, let result = job.result,
              case .bool(true)? = result["replaced_default"], case .dictionary(let backup)? = result["backup"] else { return nil }
        return text(backup["slug"])
    }

    static func finished(_ job: Job?) -> Bool {
        guard let job else { return false }
        return job.status == .succeeded || job.status == .failed || job.status == .cancelled
    }

    /// What an export's job answers (contract `auth.exportProfile`).
    struct Exported: Equatable {
        var attachmentID: String
        var name: String
        var sizeBytes: Int
        var removed: [String]
        var masked: [String]
        var providers: Int
    }

    static func exported(_ job: Job?) -> Exported? {
        guard let job, job.status == .succeeded, let result = job.result, let id = text(result["attachment_id"]) else { return nil }
        return Exported(
            attachmentID: id, name: text(result["name"]) ?? "\(id).tar.gz", sizeBytes: number(result["size_bytes"]),
            removed: texts(result["removed"]), masked: texts(result["masked"]), providers: number(result["providers"])
        )
    }

    /// The name of the profile an import made.
    static func importedName(_ job: Job?) -> String? {
        guard let job, job.status == .succeeded, let result = job.result else { return nil }
        return text(result["name"]) ?? text(result["slug"])
    }

    /// Why an export or import ended without a result: the hub's sentence, else the status.
    static func failure(_ job: Job?) -> String? {
        guard let job, job.status == .failed || job.status == .cancelled else { return nil }
        return job.error?.error ?? job.status.rawValue
    }

    /// Hermes's own words when it refused (`details.reason = hermes_refused`); nil otherwise.
    static func hermesRefusal(_ failure: HubFailure?) -> String? {
        guard let failure, failure.reason == "hermes_refused", let words = failure.detailMessage, !words.isEmpty else { return nil }
        return words
    }

    /// «3.4 MB» / «120 KB», with Latin digits.
    static func size(_ bytes: Int) -> String {
        if bytes >= 1_048_576 { return String(format: "%.1f MB", locale: Locale(identifier: "en_US_POSIX"), Double(bytes) / 1_048_576) }
        return "\(max(1, Int((Double(bytes) / 1024).rounded()))) KB"
    }

    private static func text(_ value: JSONValue?) -> String? {
        if case .string(let text)? = value { return text }
        return nil
    }

    private static func number(_ value: JSONValue?) -> Int {
        switch value {
        case .int(let n)?: return n
        case .double(let d)?: return Int(d)
        default: return 0
        }
    }

    private static func texts(_ value: JSONValue?) -> [String] {
        guard case .array(let items)? = value else { return [] }
        return items.compactMap { text($0) }
    }
}

/// The push senders (Device connections, admin): read from the files Apple and Firebase hand out,
/// checked here before anything is sent; the hub checks again (and signs a test token) on save. A
/// secret is never shown back: a stored one reads `[stored]` and the form never fills it in.
enum PushSenderRules {
    static let stored = "[stored]"

    enum AccountProblem: Equatable { case notJSON, googleServices, notServiceAccount, missing }

    /// The project of a service account, or why the file is not one.
    static func inspectServiceAccount(_ text: String) -> Result<String, AccountProblemError> {
        guard let data = text.data(using: .utf8), let object = try? JSONSerialization.jsonObject(with: data), let json = object as? [String: Any] else {
            return .failure(.init(.notJSON))
        }
        if json["project_info"] != nil && json["client"] != nil { return .failure(.init(.googleServices)) }
        if let type = json["type"], (type as? String) != "service_account" { return .failure(.init(.notServiceAccount)) }
        func field(_ key: String) -> String? {
            guard let value = json[key] as? String, !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            return value
        }
        guard field("type") != nil, let project = field("project_id"), field("client_email") != nil, let key = field("private_key"),
              key.range(of: "-----BEGIN [A-Z ]*PRIVATE KEY-----", options: .regularExpression) != nil else {
            return .failure(.init(.missing))
        }
        return .success(project)
    }

    struct AccountProblemError: Error, Equatable {
        let problem: AccountProblem
        init(_ problem: AccountProblem) { self.problem = problem }
    }

    enum KeyProblem: Equatable { case notAKey, notP8 }

    struct KeyProblemError: Error, Equatable {
        let problem: KeyProblem
        init(_ problem: KeyProblem) { self.problem = problem }
    }

    /// The key id in `AuthKey_<KEY ID>.p8` (a phone may add ` (1)`), or nil for any other name.
    static func keyID(fromFileName name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard let match = trimmed.range(of: "^AuthKey_[A-Za-z0-9]+( ?\\(\\d+\\))?\\.p8$", options: .regularExpression) else { return nil }
        let inner = trimmed[match].dropFirst("AuthKey_".count)
        let id = String(inner.prefix { $0.isLetter || $0.isNumber }).uppercased()
        return id.range(of: "^[A-Z0-9]{10}$", options: .regularExpression) != nil ? id : nil
    }

    /// The key id its file name carries (nil when it does not), or why this is not the `.p8` key.
    static func inspectP8(fileName: String?, text: String) -> Result<String?, KeyProblemError> {
        if let fileName, !fileName.trimmingCharacters(in: .whitespaces).lowercased().hasSuffix(".p8"), !text.contains("PRIVATE KEY") {
            return .failure(.init(.notP8))
        }
        guard text.range(of: "-----BEGIN PRIVATE KEY-----[\\s\\S]+-----END PRIVATE KEY-----", options: .regularExpression) != nil else {
            return .failure(.init(.notAKey))
        }
        return .success(fileName.flatMap(keyID(fromFileName:)))
    }

    /// The last characters of an identifier, for «key ending …XXXX».
    static func ending(_ value: String, size: Int = 4) -> String { value.count <= size ? value : String(value.suffix(size)) }

    /// Set up or changed here; Web Push is the hub's own and the environment wins over Settings.
    static func editable(_ sender: PushSender) -> Bool { sender.provider != .webpush && sender.source != .environment }

    static func isStored(_ sender: PushSender) -> Bool { sender.source == .settings }

    /// The form of one sender, as typed.
    struct Form: Equatable {
        var enabled: Bool
        var serviceAccount = ""
        var keyID = ""
        var teamID = ""
        var bundleID = ""
        var sandbox = false
        var privateKey = ""
    }

    /// The form opened on `sender`: what is stored and not secret, never a secret.
    static func form(_ sender: PushSender) -> Form {
        Form(
            enabled: sender.state != .disabled, keyID: sender.details["key_id"] ?? "", teamID: sender.details["team_id"] ?? "",
            bundleID: sender.details["bundle_id"] ?? "", sandbox: sender.details["environment"] == "sandbox"
        )
    }

    private static func blank(_ text: String) -> Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// Save is on when the form holds what the hub needs; an empty secret keeps the stored one.
    static func ready(_ sender: PushSender, _ form: Form) -> Bool {
        switch sender.provider {
        case .fcm:
            if blank(form.serviceAccount) { return isStored(sender) }
            if case .success = inspectServiceAccount(form.serviceAccount) { return true }
            return false
        case .apns:
            guard !blank(form.keyID), !blank(form.teamID), !blank(form.bundleID) else { return false }
            if blank(form.privateKey) { return isStored(sender) }
            if case .success = inspectP8(fileName: nil, text: form.privateKey) { return true }
            return false
        case .webpush:
            return false
        }
    }

    /// What is sent: a secret left empty is `[stored]` (FCM) or left out (APNs), both «unchanged».
    static func body(_ sender: PushSender, _ form: Form) -> PushSenderUpdate {
        let trim = { (text: String) in text.trimmingCharacters(in: .whitespacesAndNewlines) }
        if sender.provider == .fcm {
            return PushSenderUpdate(enabled: form.enabled, serviceAccount: blank(form.serviceAccount) ? stored : trim(form.serviceAccount))
        }
        return PushSenderUpdate(
            enabled: form.enabled, keyId: trim(form.keyID), teamId: trim(form.teamID), bundleId: trim(form.bundleID),
            environment: form.sandbox ? .sandbox : .production, privateKey: blank(form.privateKey) ? nil : trim(form.privateKey)
        )
    }

    /// What is stored, without a secret: the project; the key's last characters, the team, sandbox.
    enum Saved: Equatable {
        case project(String), key(String), team(String), sandbox
    }

    static func saved(_ sender: PushSender) -> [Saved] {
        if sender.source == ._none || sender.provider == .webpush { return [] }
        let d = sender.details
        if sender.provider == .fcm { return d["project_id"].map { [.project($0)] } ?? [] }
        var parts: [Saved] = []
        if let key = d["key_id"] { parts.append(.key(ending(key))) }
        if let team = d["team_id"] { parts.append(.team(team)) }
        if d["environment"] == "sandbox" { parts.append(.sandbox) }
        return parts
    }

    enum Outcome: Equatable { case valid, incomplete, refused }

    /// What the hub made of a save: the key looks valid, it is not complete yet, or why not.
    static func outcome(_ saved: PushSender) -> Outcome {
        switch saved.state {
        case .ready, .disabled: return .valid
        case .notConfigured: return .incomplete
        case .error: return .refused
        }
    }

    /// The names a compose file takes instead (shown left to right, whatever the language).
    static func environmentNames(_ provider: PushProvider) -> [String] {
        switch provider {
        case .fcm: return ["COREHUB_FCM_SERVICE_ACCOUNT"]
        case .apns: return ["COREHUB_APNS_KEY_ID", "COREHUB_APNS_TEAM_ID", "COREHUB_APNS_BUNDLE_ID", "COREHUB_APNS_KEY", "COREHUB_APNS_ENVIRONMENT"]
        case .webpush: return []
        }
    }
}

/// Senders waiting to pair with the agent's channels: an admin's errand, in the profile they are in.
enum PairingRules {
    /// The agent whose channels are asked (the web's `usePendingActions`): installed, on, with channels.
    static func channelAgent(_ agents: [Agent]) -> Agent? {
        agents.first { $0.enabled && [.available, .updating, .limited].contains($0.status) && $0.capabilities.contains(.channels) }
    }
}

/// A sender waiting to pair, with where it waits: the profile and the agent whose channel it wrote to.
struct PendingPairing: Identifiable, Equatable {
    var profile: String
    var agentID: String
    var request: PairingRequest
    var id: String { "\(request.platform):\(request.requestId)" }
}

/// An archive uploaded as the import's attachment (`purpose: import`): the chat's uploader, in one
/// piece up to 25 MB and in chunks above it, with the purpose the import needs.
struct ImportAttachmentBackend: AttachmentBackend {
    let api: HubAPI

    func oneShot(_ file: URL, profile: String) async throws -> Attachment {
        try await api.call { try await SessionsAPI.sessionsUploadAttachment(xHubProfile: profile, file: file, purpose: ._import, apiConfiguration: $0) }
    }

    func start(_ start: UploadStart, profile: String) async throws -> Upload {
        var body = start
        body.purpose = ._import
        return try await HubAttachmentBackend(api: api).start(body, profile: profile)
    }

    func chunk(_ uploadID: String, offset: Int, body: URL, profile: String) async throws -> Upload {
        try await HubAttachmentBackend(api: api).chunk(uploadID, offset: offset, body: body, profile: profile)
    }

    func complete(_ uploadID: String, profile: String) async throws -> Attachment {
        try await HubAttachmentBackend(api: api).complete(uploadID, profile: profile)
    }

    func abort(_ uploadID: String, profile: String) async {
        await HubAttachmentBackend(api: api).abort(uploadID, profile: profile)
    }
}

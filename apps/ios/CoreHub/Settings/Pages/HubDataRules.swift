// Settings → Knowledge, Skills usage, Plugins and Webhooks (apps batch 10): the plain rules those
// four pages follow, kept apart from the views so HubDataRulesTests checks them. Android's
// HubDataKit.kt is the twin.
import CoreHubClient
import Foundation

extension CoreHubClientAPIConfiguration {
    /// This call in `profile`, for a global operation the hub still reads the header of (a profile's
    /// export and import, ProfileTransfer.swift). The notify webhooks declare the header now (§115)
    /// and take it as their `xHubProfile` argument.
    func inProfile(_ profile: String) -> CoreHubClientAPIConfiguration {
        customHeaders["X-Hub-Profile"] = profile
        return self
    }
}

/// A knowledge row as a list item.
struct KnowledgeRow: Identifiable, Equatable {
    var item: KnowledgeItem
    var id: String { item.id }
}

enum KnowledgeRules {
    /// The kind chips, «All» first (the web's order).
    static let kinds: [String?] = [nil, "journal", "note", "file"]

    static func kind(_ value: String?) -> KnowledgeAPI.Kind_knowledgeListItems? {
        value.flatMap { KnowledgeAPI.Kind_knowledgeListItems(rawValue: $0) }
    }

    /// The search as sent: trimmed, and none at all when only spaces were typed.
    static func query(_ typed: String) -> String? {
        let text = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }

    /// The day a row belongs to, as text: a journal entry's own date (a calendar day, read in UTC),
    /// else the day it was written (in the phone's zone).
    static func dayText(_ item: KnowledgeItem, language: AppLanguage) -> String {
        let formatter = DateFormatter()
        formatter.locale = language.locale
        // A fixed pattern: a localized template can carry the region's own digits (Arabic-Indic).
        formatter.dateFormat = "d MMM y"
        if let date = item.date {
            formatter.timeZone = TimeZone(identifier: "UTC")
            return formatter.string(from: date)
        }
        return formatter.string(from: item.createdAt)
    }
}

enum SkillsUsageRules {
    /// The periods the page offers (the contract takes 1–365), as on the web.
    static let periods = [7, 30, 90, 365]

    /// Minutes east of UTC at `date`, so a day on the page is the person's own calendar day.
    static func utcOffset(_ zone: TimeZone = .current, at date: Date = Date()) -> Int {
        zone.secondsFromGMT(for: date) / 60
    }

    /// The days anything was loaded, newest first (the chart as a compact list).
    static func activeDays(_ report: SkillUsageReport) -> [SkillUsageReportByDayInner] {
        Array(report.byDay.filter { $0.uses > 0 }.reversed())
    }

    /// A day's skills, most used first, then «other» (nil: the skills outside the top series).
    static func daySkills(_ day: SkillUsageReportByDayInner) -> [(skill: String?, uses: Int)] {
        let named = day.skills.filter { $0.value > 0 }
            .sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
            .map { (skill: Optional($0.key), uses: $0.value) }
        return day.other > 0 ? named + [(skill: nil, uses: day.other)] : named
    }

    /// A bar's length beside the busiest day, 0 to 1.
    static func fraction(_ uses: Int, most: Int) -> Double {
        guard most > 0, uses > 0 else { return 0 }
        return min(1, Double(uses) / Double(most))
    }

    /// A share (0.125) as the web writes it: at most one decimal, Latin digits («12.5%»).
    static func percent(_ share: Double) -> String {
        let tenths = (share * 1000).rounded() / 10
        return tenths == tenths.rounded() ? "\(Int(tenths))%" : String(format: "%.1f%%", locale: Locale(identifier: "en_US_POSIX"), tenths)
    }

    /// The agents offered: every agent active in the period, and the chosen one when the period no
    /// longer has it (so the choice can be taken back rather than silently dropped).
    static func agentChoices(_ agents: [ActiveAgent], chosen: String?) -> [ActiveAgent] {
        guard let chosen, !agents.contains(where: { $0.agentId == chosen }) else { return agents }
        return agents + [ActiveAgent(agentId: chosen, name: nil, reportsUsage: false)]
    }

    /// A calendar day of the report (read in UTC, as the hub writes it).
    static func dayText(_ date: Date, language: AppLanguage, year: Bool = false) -> String {
        let formatter = DateFormatter()
        formatter.locale = language.locale
        formatter.timeZone = TimeZone(identifier: "UTC")
        // A fixed pattern: a localized template can carry the region's own digits (Arabic-Indic).
        formatter.dateFormat = year ? "d MMM y" : "d MMM"
        return formatter.string(from: date)
    }
}

enum NotifyWebhookRules {
    static let defaultRetries = 5
    static let maxRetries = 10
    /// The header a receiver checks (`derived.webhookSignatureHeader` in the contract package).
    static let signatureHeader = "X-CoreHub-Signature"

    enum SecretChoice: String, Hashable { case keep, new, unsigned }

    /// What the add/edit sheet holds.
    struct Draft: Equatable {
        var name = ""
        var url = ""
        var events: Set<String> = []
        var allProfiles = true
        var profiles: Set<String> = []
        var enabled = true
        var includeContent = false
        var allowPrivate = false
        var retries = String(NotifyWebhookRules.defaultRetries)
        var secret: SecretChoice = .new
    }

    /// The sheet for `hook`, or a new one: signed unless somebody says otherwise.
    static func draft(_ hook: Webhook?) -> Draft {
        guard let hook else { return Draft() }
        return Draft(
            name: hook.name, url: hook.url, events: Set(hook.events), allProfiles: hook.profiles.isEmpty,
            profiles: Set(hook.profiles), enabled: hook.enabled, includeContent: hook.includeContent,
            allowPrivate: hook.allowPrivateNetwork, retries: String(hook.maxRetries),
            secret: hook.secret != nil ? .keep : .unsigned
        )
    }

    /// Keep/new/stop for a signed webhook; new/none otherwise.
    static func secretChoices(_ hook: Webhook?) -> [SecretChoice] {
        hook?.secret != nil ? [.keep, .new, .unsigned] : [.new, .unsigned]
    }

    /// A number for the retries field, within the contract's 0–10.
    static func retries(_ typed: String) -> Int {
        guard let value = Int(typed.trimmingCharacters(in: .whitespaces)) else { return defaultRetries }
        return min(max(value, 0), maxRetries)
    }

    static func urlOK(_ url: String) -> Bool {
        url.range(of: #"^https?://\S+$"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    /// Nothing is said about an empty address; a typed one must be http(s).
    static func badURL(_ url: String) -> Bool { !url.isEmpty && !urlOK(url) }

    /// «Only these profiles» with none chosen.
    static func noProfile(_ draft: Draft) -> Bool { !draft.allProfiles && draft.profiles.isEmpty }

    static func ready(_ draft: Draft) -> Bool {
        let name = draft.name.trimmingCharacters(in: .whitespaces)
        return !name.isEmpty && name.count <= 80 && urlOK(draft.url) && !noProfile(draft)
    }

    /// A new HMAC key: 32 random bytes as hex, with a prefix that says what it is.
    static func newSecret(_ random: (inout [UInt8]) -> Void = { bytes in
        for index in bytes.indices { bytes[index] = UInt8.random(in: 0...255) }
    }) -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        random(&bytes)
        return "whsec_" + bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// The body to save. Events the hub no longer sends are dropped (it refuses them); «every
    /// profile» is the empty list; the secret is left out to keep it, set to the new one, or sent as
    /// `null` to stop signing.
    static func write(_ draft: Draft, known: [String]?, secret: String?) -> WebhookWrite {
        WebhookWrite(
            name: draft.name.trimmingCharacters(in: .whitespaces),
            url: draft.url.trimmingCharacters(in: .whitespaces),
            events: draft.events.filter { known?.contains($0) ?? true }.sorted().compactMap(WebhookEventName.init(rawValue:)),
            profiles: draft.allProfiles ? [] : draft.profiles.sorted(),
            enabled: draft.enabled,
            secret: draft.secret == .new ? secret : nil,
            includeContent: draft.includeContent,
            allowPrivateNetwork: draft.allowPrivate,
            maxRetries: retries(draft.retries),
            sendNull: draft.secret == .unsigned ? [.secret] : []
        )
    }

    /// Whether a delivery may be sent again: it is over and did not arrive (one waiting for its
    /// retry is sent anyway).
    static func canRedeliver(_ delivery: WebhookDelivery) -> Bool {
        delivery.status == .dead || (delivery.status == .failed && delivery.nextAttemptAt == nil)
    }

    /// The deliveries table follows the hub while something waits to be sent.
    static func waiting(_ deliveries: [WebhookDelivery]) -> Bool {
        deliveries.contains { $0.status == .queued || $0.nextAttemptAt != nil }
    }

    /// What the test job reports when it is done.
    struct TestOutcome: Equatable {
        var delivered: Bool
        var status: Int
        var error: String?
    }

    /// The outcome of a finished test job, or nil while it runs.
    static func outcome(_ job: Job) -> TestOutcome? {
        switch job.status {
        case .queued, .running:
            return nil
        case .succeeded:
            if let result = job.result {
                var delivered = false, status = 0
                var error: String?
                if case .bool(let value) = result["delivered"] { delivered = value }
                if case .int(let value) = result["status"] { status = value }
                if case .double(let value) = result["status"] { status = Int(value) }
                if case .string(let value) = result["error"] { error = value }
                return TestOutcome(delivered: delivered, status: status, error: error)
            }
            return TestOutcome(delivered: false, status: 0, error: job.error?.error)
        default:
            return TestOutcome(delivered: false, status: 0, error: job.error?.error)
        }
    }

    /// The string key for the hub's reason for refusing an address; nil for anything else.
    static func urlRefusalKey(_ reason: String?) -> String? {
        switch reason {
        case "url_scheme": return "knowledge.webhooks_url_scheme"
        case "url_unresolvable": return "knowledge.webhooks_url_unresolvable"
        case "url_private": return "knowledge.webhooks_url_private"
        default: return nil
        }
    }

    /// The catalogue narrowed by `needle` (name or description, any case).
    static func filterEvents(_ events: [NotifyListWebhookEvents200ResponseItemsInner], needle: String, arabic: Bool)
        -> [NotifyListWebhookEvents200ResponseItemsInner] {
        let text = needle.trimmingCharacters(in: .whitespaces).lowercased()
        guard !text.isEmpty else { return events }
        return events.filter { event in
            event.name.lowercased().contains(text) || (arabic ? event.description.ar : event.description.en).lowercased().contains(text)
        }
    }
}

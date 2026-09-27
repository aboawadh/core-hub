@testable import CoreHub
import CoreHubClient
import Foundation
import XCTest

/// Apps batch 10: Knowledge, Skills usage, hub Plugins and Webhooks — the rules the pages follow.
/// Android's KnowledgeReportsTest is the twin.
final class HubDataRulesTests: XCTestCase {
    private let date = Date(timeIntervalSince1970: 1_790_000_000)

    private func day(_ offset: Int, uses: Int, skills: [String: Int], other: Int = 0) -> SkillUsageReportByDayInner {
        SkillUsageReportByDayInner(date: date.addingTimeInterval(Double(offset) * 86_400), uses: uses, skills: skills, other: other)
    }

    private func report(_ days: [SkillUsageReportByDayInner]) -> SkillUsageReport {
        SkillUsageReport(
            period: ReportPeriod(from: date, to: date, days: 3), generatedAt: date, profiles: ["work"], agents: [],
            totals: SkillUsageReportTotals(uses: 7, distinctSkills: 2), topSeries: ["web-research"], byDay: days, topSkills: []
        )
    }

    private func hook(secret: Bool = true, profiles: [String] = []) -> Webhook {
        Webhook(
            id: "W1", name: "Ops", url: "https://example.com/hook", events: ["run.completed"], profiles: profiles, enabled: true,
            secret: secret ? .leftSquareBracketStoredRightSquareBracket : nil, includeContent: false, allowPrivateNetwork: false,
            maxRetries: 3, stats: WebhookStats(delivered: 1, failed: 0), createdAt: date, updatedAt: date
        )
    }

    private func delivery(_ status: WebhookDelivery.Status, next: Date? = nil) -> WebhookDelivery {
        WebhookDelivery(id: "D1", webhookId: "W1", event: "run.completed", status: status, attempts: 2, createdAt: date, nextAttemptAt: next)
    }

    private func job(_ status: JobStatus, result: [String: JSONValue]? = nil, error: ModelError? = nil) -> Job {
        Job(id: "J1", profile: "work", ownerId: "u1", createdAt: date, updatedAt: date, kind: .webhookTest, status: status,
            progress: JobProgress(percent: 30), result: result, error: error)
    }

    private func encoded(_ write: WebhookWrite) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: JSONEncoder().encode(write)) as? [String: Any] ?? [:]
    }

    // MARK: - Knowledge

    func testTheKnowledgeKindAndSearchAreSentOnlyWhenThereIsOne() {
        XCTAssertNil(KnowledgeRules.kind(nil))
        XCTAssertEqual(KnowledgeRules.kind("journal"), .journal)
        XCTAssertNil(KnowledgeRules.kind("video"))
        XCTAssertNil(KnowledgeRules.query("   "))
        XCTAssertEqual(KnowledgeRules.query("  tax "), "tax")
        XCTAssertEqual(KnowledgeRules.kinds, [nil, "journal", "note", "file"])
    }

    // MARK: - Skills usage

    func testTheSkillsChartListsTheBusyDaysNewestFirstWithOtherLast() {
        let days = SkillsUsageRules.activeDays(report([
            day(0, uses: 3, skills: ["web-research": 2, "docker": 1]),
            day(1, uses: 0, skills: [:]),
            day(2, uses: 4, skills: ["web-research": 2, "docker": 0], other: 2),
        ]))
        XCTAssertEqual(days.map(\.uses), [4, 3])
        let newest = SkillsUsageRules.daySkills(days[0])
        XCTAssertEqual(newest.map(\.skill), ["web-research", nil])
        XCTAssertEqual(newest.map(\.uses), [2, 2])
        XCTAssertEqual(SkillsUsageRules.daySkills(days[1]).map(\.skill), ["web-research", "docker"])
        XCTAssertEqual(SkillsUsageRules.fraction(3, most: 4), 0.75)
        XCTAssertEqual(SkillsUsageRules.fraction(3, most: 0), 0)
    }

    func testAShareIsWrittenWithOneDecimalAndLatinDigits() {
        XCTAssertEqual(SkillsUsageRules.percent(0.5714), "57.1%")
        XCTAssertEqual(SkillsUsageRules.percent(0.5), "50%")
        XCTAssertEqual(SkillsUsageRules.percent(1), "100%")
        XCTAssertEqual(SkillsUsageRules.percent(0), "0%")
    }

    func testAChosenAgentGoneFromThePeriodStaysChoosable() {
        let agents = [ActiveAgent(agentId: "A1", name: "Hermes", reportsUsage: true)]
        XCTAssertEqual(SkillsUsageRules.agentChoices(agents, chosen: nil), agents)
        XCTAssertEqual(SkillsUsageRules.agentChoices(agents, chosen: "A1"), agents)
        XCTAssertEqual(SkillsUsageRules.agentChoices(agents, chosen: "A9").map(\.agentId), ["A1", "A9"])
        XCTAssertEqual(SkillsUsageRules.utcOffset(TimeZone(identifier: "Asia/Riyadh")!, at: date), 180)
        for text in [SkillsUsageRules.dayText(date, language: .ar), SkillsUsageRules.dayText(date, language: .ar, year: true)] {
            XCTAssertFalse(text.unicodeScalars.contains { (0x0660...0x0669).contains($0.value) }, text)
        }
    }

    // MARK: - Webhooks

    func testAWebhookSheetStartsSignedAndChecksTheAddressAndTheProfiles() {
        let fresh = NotifyWebhookRules.draft(nil)
        XCTAssertEqual(fresh.secret, .new)
        XCTAssertTrue(fresh.allProfiles)
        XCTAssertFalse(NotifyWebhookRules.ready(fresh))
        var typed = fresh
        typed.name = "Ops"
        typed.url = "ftp://example.com"
        XCTAssertTrue(NotifyWebhookRules.badURL(typed.url))
        XCTAssertFalse(NotifyWebhookRules.ready(typed))
        typed.url = "https://example.com/x"
        XCTAssertTrue(NotifyWebhookRules.ready(typed))
        XCTAssertFalse(NotifyWebhookRules.badURL(""))
        typed.allProfiles = false
        XCTAssertTrue(NotifyWebhookRules.noProfile(typed))
        XCTAssertFalse(NotifyWebhookRules.ready(typed))
        typed.profiles = ["work"]
        XCTAssertTrue(NotifyWebhookRules.ready(typed))

        let signed = NotifyWebhookRules.draft(hook())
        XCTAssertEqual(signed.secret, .keep)
        XCTAssertEqual(signed.retries, "3")
        XCTAssertEqual(NotifyWebhookRules.secretChoices(hook()), [.keep, .new, .unsigned])
        XCTAssertEqual(NotifyWebhookRules.draft(hook(secret: false)).secret, .unsigned)
        XCTAssertEqual(NotifyWebhookRules.secretChoices(hook(secret: false)), [.new, .unsigned])
        XCTAssertFalse(NotifyWebhookRules.draft(hook(profiles: ["work"])).allProfiles)
    }

    func testRetriesKeepToTheRangeAndANewSecretIs32BytesInHex() {
        XCTAssertEqual(NotifyWebhookRules.retries("99"), 10)
        XCTAssertEqual(NotifyWebhookRules.retries("0"), 0)
        XCTAssertEqual(NotifyWebhookRules.retries(""), 5)
        let secret = NotifyWebhookRules.newSecret { bytes in for index in bytes.indices { bytes[index] = UInt8(index) } }
        XCTAssertEqual(secret, "whsec_" + (0..<32).map { String(format: "%02x", $0) }.joined())
        XCTAssertEqual(NotifyWebhookRules.newSecret().count, 6 + 64)
    }

    func testTheBodyDropsUnknownEventsKeepsOrStopsTheSecret() throws {
        var draft = NotifyWebhookRules.draft(nil)
        draft.name = " Ops "
        draft.url = "https://example.com/hook"
        draft.events = ["run.completed", "gone.event"]
        draft.retries = "4"
        let created = try encoded(NotifyWebhookRules.write(draft, known: ["run.completed"], secret: "whsec_abc"))
        XCTAssertEqual(created["name"] as? String, "Ops")
        XCTAssertEqual(created["events"] as? [String], ["run.completed"])
        XCTAssertEqual(created["secret"] as? String, "whsec_abc")
        XCTAssertEqual(created["max_retries"] as? Int, 4)
        XCTAssertEqual((created["profiles"] as? [String])?.count, 0)

        var stop = NotifyWebhookRules.draft(hook())
        stop.secret = .unsigned
        let stopped = try encoded(NotifyWebhookRules.write(stop, known: nil, secret: nil))
        XCTAssertTrue(stopped["secret"] is NSNull)

        let kept = try encoded(NotifyWebhookRules.write(NotifyWebhookRules.draft(hook()), known: nil, secret: nil))
        XCTAssertNil(kept["secret"])

        let toggled = try encoded(WebhookWrite(enabled: false, maxRetries: nil))
        XCTAssertEqual(Set(toggled.keys), ["enabled"])
    }

    func testAFailedDeliveryWithNoRetryLeftCanBeSentAgain() {
        XCTAssertTrue(NotifyWebhookRules.canRedeliver(delivery(.dead)))
        XCTAssertTrue(NotifyWebhookRules.canRedeliver(delivery(.failed)))
        XCTAssertFalse(NotifyWebhookRules.canRedeliver(delivery(.failed, next: date)))
        XCTAssertFalse(NotifyWebhookRules.canRedeliver(delivery(.delivered)))
        XCTAssertTrue(NotifyWebhookRules.waiting([delivery(.queued)]))
        XCTAssertTrue(NotifyWebhookRules.waiting([delivery(.failed, next: date)]))
        XCTAssertFalse(NotifyWebhookRules.waiting([delivery(.dead)]))
    }

    func testATestJobSaysHowTheDeliveryWentOnceItIsOver() {
        XCTAssertNil(NotifyWebhookRules.outcome(job(.running)))
        XCTAssertEqual(
            NotifyWebhookRules.outcome(job(.succeeded, result: ["delivered": .bool(true), "status": .int(204), "error": .null])),
            NotifyWebhookRules.TestOutcome(delivered: true, status: 204, error: nil)
        )
        XCTAssertEqual(
            NotifyWebhookRules.outcome(job(.failed, error: ModelError(error: "refused", code: .badRequest))),
            NotifyWebhookRules.TestOutcome(delivered: false, status: 0, error: "refused")
        )
        XCTAssertEqual(NotifyWebhookRules.urlRefusalKey("url_private"), "knowledge.webhooks_url_private")
        XCTAssertNil(NotifyWebhookRules.urlRefusalKey("profile_not_allowed"))
    }

    func testEveryStringThePagesUseExistsInBothLanguages() {
        for language in AppLanguage.allCases {
            let l10n = L10n(language, bundle: Bundle(for: AppModel.self))
            for key in ["knowledge.intro", "knowledge.filter_all", "knowledge.skills_period_365", "knowledge.plugins_none",
                        "knowledge.webhooks_add", "knowledge.webhooks_url_private", "knowledge.webhooks_delivery_dead", "audit.period"] {
                XCTAssertTrue(l10n.has(key), "\(language) \(key)")
            }
        }
    }
}

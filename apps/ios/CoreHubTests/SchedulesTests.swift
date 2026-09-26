@testable import CoreHub
import CoreHubClient
import XCTest

/// Schedules on the phone (batch 3): what the form sends for a new schedule and for an edit, the
/// web's "Common schedules", who decides the run options, Hermes's refusals and the zone it asks
/// for, a run's time — and the trigger and target going out with their `null` fields, which the
/// hub requires (written by the generated models, §114). Android's SchedulesTest is the twin.
final class SchedulesTests: XCTestCase {
    private let en = L10n(.en, bundle: Bundle(for: AppModel.self))
    private let ar = L10n(.ar, bundle: Bundle(for: AppModel.self))

    private func agent(_ id: String, slug: String) -> Agent {
        Agent(id: id, profile: "work", ownerId: "u1", createdAt: Fixture.date, updatedAt: Fixture.date, slug: slug,
              name: slug.capitalized, kind: .hermes, avatar: Avatar(kind: .generated, seed: "a"), status: .available, enabled: true,
              install: AgentInstall(source: .managed, updateAvailable: false, newerThanTested: false, autoUpdate: false, autoUpdateSupported: false),
              runtime: AgentRuntime(state: .running), capabilities: [], sections: [], limited: false, subagents: ._none)
    }

    private func schedule(external: Bool = false, overlap: ScheduleOverlap? = .wait, prompt: String? = "Summarise the day") -> Schedule {
        Schedule(
            id: "S1", profile: "work", ownerId: "u1", createdAt: Fixture.date, updatedAt: Fixture.date, name: "Daily report",
            trigger: ScheduleTrigger(kind: .cron, expression: "0 9 * * *", timezone: "Asia/Riyadh", display: "Every day at 09:00"),
            target: ScheduleTarget(kind: .agentPrompt, agentId: "A1", prompt: prompt, skills: []),
            delivery: DeliveryTarget(kind: ._none), _repeat: ScheduleAllOfRepeat(completed: 3), enabled: true, state: .scheduled,
            runIfMissed: external ? nil : false, overlap: external ? nil : overlap,
            external: external ? ScheduleAllOfExternal(source: .hermes, id: "job1") : nil
        )
    }

    func testANewScheduleAsksHermesFirstAndLeavesItsRunOptionsToIt() {
        let agents = [agent("A2", slug: "claude-code"), agent("A1", slug: "hermes")]
        var draft = ScheduleRules.newDraft(agents: agents, zone: "Asia/Riyadh")
        XCTAssertEqual(draft.agentID, "A1")
        XCTAssertTrue(ScheduleRules.isHermes(draft.agentID, in: agents))
        XCTAssertEqual(ScheduleRules.problem(draft, editing: false), .name)
        draft.name = "  Morning brief "
        draft.prompt = "  What changed overnight? "
        let hermes = ScheduleRules.create(draft, hermes: true)!
        XCTAssertEqual(hermes.name, "Morning brief")
        XCTAssertEqual(hermes.trigger?.expression, "0 9 * * *")
        XCTAssertEqual(hermes.trigger?.timezone, "Asia/Riyadh")
        XCTAssertEqual(hermes.target?.kind, .agentPrompt)
        XCTAssertEqual(hermes.target?.agentId, "A1")
        XCTAssertEqual(hermes.target?.prompt, "What changed overnight?")
        XCTAssertNil(hermes.runIfMissed, "Hermes refuses run options in the body")
        XCTAssertNil(hermes.overlap)
        // Any other agent: the hub fires it and keeps the two options, off and «wait» by default.
        draft.agentID = "A2"
        let hub = ScheduleRules.create(draft, hermes: false)!
        XCTAssertEqual(hub.runIfMissed, false)
        XCTAssertEqual(hub.overlap, .wait)
        // A time the hub could not read is refused before it is sent.
        draft.trigger.cron = "0 9 *"
        XCTAssertEqual(ScheduleRules.problem(draft, editing: false), .trigger)
        XCTAssertNil(ScheduleRules.create(draft, hermes: false))
    }

    func testTheCommonSchedulesFillTheKindAndItsValue() {
        XCTAssertEqual(ScheduleRules.templates.map(\.id), ["every_hour", "daily_8", "weekdays_9", "monday_9", "monthly_1", "every_15"])
        let start = TriggerRules.draft(nil, zone: "UTC")
        let monthly = ScheduleRules.apply(ScheduleRules.templates[4], to: start)
        XCTAssertEqual(monthly.kind, .cron)
        XCTAssertEqual(TriggerRules.build(monthly)?.expression, "0 9 1 * *")
        let every = ScheduleRules.apply(ScheduleRules.templates[5], to: start)
        XCTAssertEqual(every.kind, .interval)
        XCTAssertEqual(TriggerRules.build(every)?.everyMinutes, 15)
        for template in ScheduleRules.templates {
            XCTAssertTrue(en.has("schedules.templates.\(template.id)"))
            XCTAssertTrue(ar.has("schedules.templates.\(template.id)"))
        }
    }

    func testAnEditSendsOnlyWhatChanged() {
        let saved = schedule()
        var draft = ScheduleRules.draft(saved)
        XCTAssertEqual(draft.trigger.timezone, "Asia/Riyadh")
        XCTAssertNil(ScheduleRules.update(saved, draft), "nothing changed")
        draft.name = "Daily report "
        XCTAssertNil(ScheduleRules.update(saved, draft), "spaces are not a change")
        draft.prompt = "Summarise the week"
        draft.overlap = .skip
        let write = ScheduleRules.update(saved, draft)!
        XCTAssertNil(write.name)
        XCTAssertNil(write.trigger)
        XCTAssertEqual(write.target?.prompt, "Summarise the week")
        XCTAssertEqual(write.target?.agentId, "A1", "the agent stays")
        XCTAssertEqual(write.overlap, .skip)
        XCTAssertNil(write.runIfMissed)
        draft = ScheduleRules.draft(saved)
        draft.trigger = ScheduleRules.apply(ScheduleRules.templates[5], to: draft.trigger)
        XCTAssertEqual(ScheduleRules.update(saved, draft)?.trigger?.everyMinutes, 15)
    }

    func testAHermesJobKeepsNoRunOptionsOfItsOwn() {
        let hermes = schedule(external: true)
        XCTAssertFalse(ScheduleRules.hasRunOptions(hermes))
        XCTAssertTrue(ScheduleRules.hasRunOptions(schedule()))
        var draft = ScheduleRules.draft(hermes)
        draft.overlap = .parallel
        draft.runIfMissed = true
        XCTAssertNil(ScheduleRules.update(hermes, draft), "never sent for Hermes, which refuses them")
        XCTAssertEqual(ScheduleRules.fired(hermes, en), en("schedules.hermes.fired"))
        XCTAssertEqual(ScheduleRules.fired(schedule(), en), "“Daily report” started.")
    }

    func testHermesRefusalsAreSaidInWordsAndTheZoneItAsksForIsOffered() throws {
        let body = #"{"error":"Conflict","code":"conflict","details":{"reason":"hermes_timezone","field":"trigger.timezone","timezone":"UTC"}}"#
        let failure = HubFailure(ErrorResponse.error(409, Data(body.utf8), nil, URLError(.badServerResponse)))
        XCTAssertEqual(failure.reason, "hermes_timezone")
        XCTAssertEqual(failure.timezone, "UTC")
        XCTAssertEqual(ScheduleRules.askedZone(failure), "UTC")
        XCTAssertEqual(ScheduleRules.refusal(failure, en), "Hermes runs every cron in UTC. Use that zone, or change Hermes's timezone.")
        let draft = ScheduleRules.withZone(ScheduleRules.newDraft(agents: [], zone: "Asia/Riyadh"), "UTC")
        XCTAssertEqual(draft.trigger.timezone, "UTC")
        var other = failure
        other.reason = "hermes_prompt_required"
        XCTAssertNil(ScheduleRules.askedZone(other))
        XCTAssertEqual(ScheduleRules.refusal(other, ar), ar("schedules.hermes.prompt_required"))
    }

    func testARunSaysHowLongItTookInLatinDigits() {
        let start = Fixture.date
        let run = ScheduleRun(id: "R1", scheduleId: "S1", jobId: "J1", sessionId: "C1", status: .succeeded, trigger: .schedule, waiting: false,
                              outputSizeBytes: 10, deliveryStatus: ._none, startedAt: start, finishedAt: start.addingTimeInterval(125))
        XCTAssertEqual(ScheduleRules.seconds(run), 125)
        XCTAssertEqual(ScheduleRules.duration(41, en), "41s")
        XCTAssertEqual(ScheduleRules.duration(125, en), "2m 5s")
        XCTAssertEqual(ScheduleRules.duration(3_780, en), "1h 3m")
        XCTAssertEqual(ScheduleRules.duration(125, ar), "2 د 5 ث")
        XCTAssertEqual(ScheduleRules.statusKey(run), "schedules.history.succeeded")
        let waiting = ScheduleRun(id: "R2", scheduleId: "S1", jobId: "J2", status: .queued, trigger: .schedule, waiting: true,
                                  outputSizeBytes: 0, deliveryStatus: ._none)
        XCTAssertNil(ScheduleRules.seconds(waiting))
        XCTAssertEqual(ScheduleRules.statusKey(waiting), "schedules.history.waiting")
        XCTAssertTrue(ScheduleRules.live([run, waiting]))
        XCTAssertFalse(ScheduleRules.live([run]))
        for status in JobStatus.allCases {
            XCTAssertTrue(en.has("schedules.history.\(status.rawValue)"), status.rawValue)
            XCTAssertTrue(ar.has("schedules.history.\(status.rawValue)"), status.rawValue)
        }
    }

    func testATriggerAndATargetGoOutWithTheirNullFields() throws {
        // The contract requires every field of both, some of them null: the generated models
        // write a required nullable field as `null` (contract decision §114), no interceptor.
        let write = ScheduleWrite(
            name: "Every 15",
            trigger: ScheduleTrigger(kind: .interval, everyMinutes: 15, timezone: "UTC"),
            target: ScheduleTarget(kind: .agentPrompt, agentId: "A1", prompt: "Ping", skills: []),
            overlap: .wait
        )
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: CodableHelper().jsonEncoder.encode(write)) as? [String: Any])
        let trigger = try XCTUnwrap(object["trigger"] as? [String: Any])
        XCTAssertTrue(trigger["expression"] is NSNull)
        XCTAssertTrue(trigger["run_at"] is NSNull)
        XCTAssertEqual(trigger["every_minutes"] as? Int, 15)
        XCTAssertNil(trigger["display"], "read-only and optional: left out")
        let target = try XCTUnwrap(object["target"] as? [String: Any])
        for field in ["model", "provider", "workflow_id", "input"] { XCTAssertTrue(target[field] is NSNull, field) }
        XCTAssertEqual(target["prompt"] as? String, "Ping")
        XCTAssertEqual(object["overlap"] as? String, "wait")
        XCTAssertNil(object["delivery"], "an optional field nobody set stays out")
        // The preview's body too, and a plain patch gains no nulls.
        let preview = SchedulePreviewRequest(trigger: ScheduleTrigger(kind: .cron, expression: "0 9 * * *", timezone: "UTC"))
        let sent = try XCTUnwrap(JSONSerialization.jsonObject(with: CodableHelper().jsonEncoder.encode(preview)) as? [String: Any])
        XCTAssertTrue((sent["trigger"] as? [String: Any])?["every_minutes"] is NSNull)
        let pause = try XCTUnwrap(JSONSerialization.jsonObject(with: CodableHelper().jsonEncoder.encode(ScheduleWrite(enabled: false))) as? [String: Bool])
        XCTAssertEqual(pause, ["enabled": false])
    }
}

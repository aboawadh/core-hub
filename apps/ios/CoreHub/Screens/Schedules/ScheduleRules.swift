// The rules of the Schedules page (batch 3), as plain functions so a test checks them without
// drawing: the form's draft and what it sends, the web's "Common schedules", who decides the run
// options, the hub's refusals in the person's words, and a run's time and status.
// Android's ScheduleRules (ScheduleEditor.kt) is its twin.
import CoreHubClient
import Foundation

/// One of the web's "Common schedules" (owner, 2026-09-25): fills the kind and its value.
struct ScheduleTemplate: Equatable, Identifiable {
    let id: String
    let kind: ScheduleTrigger.Kind
    let value: String
}

/// A schedule as it is being written.
struct ScheduleDraft: Equatable {
    var name = ""
    var trigger = TriggerDraft()
    var agentID: String?
    var prompt = ""
    var runIfMissed = false
    var overlap: ScheduleOverlap = .wait
}

enum ScheduleProblem: Equatable { case name, trigger, agent }

/// A schedule as a list row (the generated model is not `Identifiable`).
struct ScheduleItem: Identifiable, Equatable {
    var schedule: Schedule
    var id: String { schedule.id }
}

/// A run as a list row.
struct ScheduleRunItem: Identifiable, Equatable {
    let run: ScheduleRun
    var id: String { run.id }
}

enum ScheduleRules {
    static let templates: [ScheduleTemplate] = [
        ScheduleTemplate(id: "every_hour", kind: .cron, value: "0 * * * *"),
        ScheduleTemplate(id: "daily_8", kind: .cron, value: "0 8 * * *"),
        ScheduleTemplate(id: "weekdays_9", kind: .cron, value: "0 9 * * 1-5"),
        ScheduleTemplate(id: "monday_9", kind: .cron, value: "0 9 * * 1"),
        ScheduleTemplate(id: "monthly_1", kind: .cron, value: "0 9 1 * *"),
        ScheduleTemplate(id: "every_15", kind: .interval, value: "15"),
    ]

    static let overlaps: [ScheduleOverlap] = [.skip, .wait, .parallel, .replace]

    /// The history is read this many runs at a time.
    static let runsPage = 20

    static func apply(_ template: ScheduleTemplate, to trigger: TriggerDraft) -> TriggerDraft {
        var copy = trigger
        copy.kind = template.kind
        if template.kind == .interval {
            let (every, unit) = TriggerRules.split(Int(template.value) ?? 60)
            copy.every = String(every)
            copy.unit = unit
        } else {
            copy.cron = template.value
        }
        return copy
    }

    /// Hermes first: the one agent with a scheduler of its own (as on the web).
    static func defaultAgent(_ agents: [Agent]) -> String? {
        agents.first { $0.slug == "hermes" }?.id ?? agents.first?.id
    }

    static func isHermes(_ agentID: String?, in agents: [Agent]) -> Bool {
        agents.contains { $0.id == agentID && $0.slug == "hermes" }
    }

    /// Whether the hub keeps this schedule's run options; Hermes decides them for a job in its scheduler.
    static func hasRunOptions(_ schedule: Schedule) -> Bool { schedule.external == nil }

    static func newDraft(agents: [Agent], zone: String = TimeZone.current.identifier) -> ScheduleDraft {
        ScheduleDraft(trigger: TriggerRules.draft(nil, zone: zone), agentID: defaultAgent(agents))
    }

    static func draft(_ schedule: Schedule) -> ScheduleDraft {
        ScheduleDraft(
            name: schedule.name,
            trigger: TriggerRules.draft(schedule.trigger),
            agentID: schedule.target.agentId,
            prompt: schedule.target.prompt ?? "",
            runIfMissed: schedule.runIfMissed ?? false,
            overlap: schedule.overlap ?? .wait
        )
    }

    /// The same draft in `zone` (the one Hermes asked for).
    static func withZone(_ draft: ScheduleDraft, _ zone: String) -> ScheduleDraft {
        var copy = draft
        copy.trigger.timezone = zone
        return copy
    }

    /// Why it cannot be saved yet. An edit keeps its agent, so it needs none chosen.
    static func problem(_ draft: ScheduleDraft, editing: Bool) -> ScheduleProblem? {
        if draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .name }
        if TriggerRules.build(draft.trigger) == nil { return .trigger }
        if !editing, draft.agentID == nil { return .agent }
        return nil
    }

    private static func text(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// A new schedule: a prompt to the chosen agent. Hermes decides the run options for its own
    /// jobs and refuses them in the body, so they are left out for it.
    static func create(_ draft: ScheduleDraft, hermes: Bool) -> ScheduleWrite? {
        guard problem(draft, editing: false) == nil, let trigger = TriggerRules.build(draft.trigger) else { return nil }
        return ScheduleWrite(
            name: draft.name.trimmingCharacters(in: .whitespacesAndNewlines),
            trigger: trigger,
            target: ScheduleTarget(kind: .agentPrompt, agentId: draft.agentID, prompt: text(draft.prompt), skills: []),
            runIfMissed: hermes ? nil : draft.runIfMissed,
            overlap: hermes ? nil : draft.overlap
        )
    }

    static func same(_ a: ScheduleTrigger, _ b: ScheduleTrigger) -> Bool {
        a.kind == b.kind && a.expression == b.expression && a.everyMinutes == b.everyMinutes && a.runAt == b.runAt
            && a.timezone == b.timezone
    }

    /// An edit sends only what changed; nil when nothing did.
    static func update(_ schedule: Schedule, _ draft: ScheduleDraft) -> ScheduleWrite? {
        guard problem(draft, editing: true) == nil, let trigger = TriggerRules.build(draft.trigger) else { return nil }
        var write = ScheduleWrite()
        var changed = false
        let name = draft.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if name != schedule.name {
            write.name = name
            changed = true
        }
        if !same(trigger, schedule.trigger) {
            write.trigger = trigger
            changed = true
        }
        if schedule.target.kind == .agentPrompt, text(draft.prompt) != text(schedule.target.prompt ?? "") {
            var target = schedule.target
            target.prompt = text(draft.prompt)
            write.target = target
            changed = true
        }
        if hasRunOptions(schedule) {
            if draft.runIfMissed != (schedule.runIfMissed ?? false) {
                write.runIfMissed = draft.runIfMissed
                changed = true
            }
            if draft.overlap != (schedule.overlap ?? .wait) {
                write.overlap = draft.overlap
                changed = true
            }
        }
        return changed ? write : nil
    }

    /// The hub's refusal in the person's words; Hermes's own in Hermes's.
    static func refusal(_ failure: HubFailure, _ l10n: L10n) -> String {
        switch failure.reason {
        case "hermes_refused": return l10n("schedules.hermes.refused", ["message": failure.message ?? ""])
        case "hermes_unreachable": return l10n("schedules.hermes.unreachable")
        case "hermes_timezone": return l10n("schedules.hermes.timezone", ["zone": failure.timezone ?? ""])
        case "hermes_delivery": return l10n("schedules.hermes.delivery")
        case "hermes_prompt_required": return l10n("schedules.hermes.prompt_required")
        case "hermes_run_options": return l10n("schedules.hermes.run_options")
        case "target_unavailable": return l10n("schedules.hermes.target_unavailable", ["message": failure.message ?? ""])
        default: return failure.describe(l10n)
        }
    }

    /// The zone Hermes runs its cron in, when that is why it refused: offered as «Use {zone}».
    static func askedZone(_ failure: HubFailure) -> String? {
        failure.reason == "hermes_timezone" ? failure.timezone.flatMap { $0.isEmpty ? nil : $0 } : nil
    }

    /// What «Run now» says it did: Hermes runs its own on its next tick.
    static func fired(_ schedule: Schedule, _ l10n: L10n) -> String {
        schedule.external?.source == .hermes ? l10n("schedules.hermes.fired") : l10n("schedules.fired", ["name": schedule.name])
    }

    /// Whole seconds a run took; nil until it ended.
    static func seconds(_ run: ScheduleRun) -> Int? {
        guard let start = run.startedAt, let end = run.finishedAt else { return nil }
        return max(0, Int(end.timeIntervalSince(start).rounded()))
    }

    /// «41s», «2m 5s», «1h 3m», with Latin digits in both languages.
    static func duration(_ seconds: Int, _ l10n: L10n) -> String {
        if seconds < 60 { return l10n("schedules.history.seconds", ["s": String(seconds)]) }
        if seconds < 3600 { return l10n("schedules.history.minutes", ["m": String(seconds / 60), "s": String(seconds % 60)]) }
        return l10n("schedules.history.hours", ["h": String(seconds / 3600), "m": String(seconds % 3600 / 60)])
    }

    static func statusKey(_ run: ScheduleRun) -> String {
        run.waiting ? "schedules.history.waiting" : "schedules.history.\(run.status.rawValue)"
    }

    static func live(_ runs: [ScheduleRun]) -> Bool {
        runs.contains { $0.status == .queued || $0.status == .running }
    }
}

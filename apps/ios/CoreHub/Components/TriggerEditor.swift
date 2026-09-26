// When something runs (docs/clients/phone-pages.md): a cron expression, every N minutes/hours/days,
// or once at a date and time, in a time zone — the hub's `ScheduleTrigger`, shared by Schedules and an
// agent's Jobs. The rules are plain functions (`TriggerRules`); the hub has the last word
// (`schedules.previewTrigger`), which the editor shows as the next runs when the page passes it.
// Android's TriggerEditor.kt is its twin.
import CoreHubClient
import SwiftUI

enum EveryUnit: Int, CaseIterable, Hashable {
    case minutes = 1
    case hours = 60
    case days = 1440
}

/// A trigger as it is being typed: every kind's fields are kept, so switching kind loses nothing.
struct TriggerDraft: Equatable {
    var kind: ScheduleTrigger.Kind = .cron
    var cron = "0 9 * * *"
    var every = "1"
    var unit: EveryUnit = .hours
    var runAt: Date?
    var timezone = TimeZone.current.identifier
}

enum TriggerProblem: Equatable { case cron, every, when, zone }

enum TriggerRules {
    /// Every hour; daily, weekdays and Mondays at 09:00.
    static let presets = ["0 * * * *", "0 9 * * *", "0 9 * * 1-5", "0 9 * * 1"]
    static let presetKeys = ["kit.trigger.hourly", "kit.trigger.daily", "kit.trigger.weekdays", "kit.trigger.weekly"]

    /// The draft of a saved trigger (or a new one in `zone`).
    static func draft(_ trigger: ScheduleTrigger?, zone: String = TimeZone.current.identifier) -> TriggerDraft {
        guard let trigger else { return TriggerDraft(timezone: zone) }
        let (every, unit) = split(trigger.everyMinutes ?? 60)
        return TriggerDraft(kind: trigger.kind, cron: trigger.expression ?? "0 9 * * *", every: String(every), unit: unit,
                            runAt: trigger.runAt, timezone: trigger.timezone)
    }

    /// Minutes as the largest whole unit: 120 → 2 hours, 1440 → 1 day, 90 → 90 minutes.
    static func split(_ minutes: Int) -> (Int, EveryUnit) {
        let unit = EveryUnit.allCases.reversed().first { minutes % $0.rawValue == 0 } ?? .minutes
        return (minutes / unit.rawValue, unit)
    }

    static func fields(_ cron: String) -> [String] {
        cron.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
    }

    static func problem(_ d: TriggerDraft) -> TriggerProblem? {
        if TimeZone(identifier: d.timezone) == nil { return .zone }
        switch d.kind {
        case .cron: return fields(d.cron).count == 5 ? nil : .cron
        case .interval: return (Int(d.every.trimmingCharacters(in: .whitespaces)) ?? 0) >= 1 ? nil : .every
        case .once: return d.runAt == nil ? .when : nil
        }
    }

    /// The trigger to send, or nil while `problem` says why not.
    static func build(_ d: TriggerDraft) -> ScheduleTrigger? {
        guard problem(d) == nil else { return nil }
        switch d.kind {
        case .cron: return ScheduleTrigger(kind: .cron, expression: fields(d.cron).joined(separator: " "), timezone: d.timezone)
        case .interval: return ScheduleTrigger(kind: .interval, everyMinutes: Int(d.every.trimmingCharacters(in: .whitespaces))! * d.unit.rawValue, timezone: d.timezone)
        case .once: return ScheduleTrigger(kind: .once, runAt: d.runAt, timezone: d.timezone)
        }
    }

    /// A one-off an hour from `now`, to the minute.
    static func inAnHour(_ d: TriggerDraft, now: Date = Date()) -> TriggerDraft {
        var copy = d
        let whole = floor(now.addingTimeInterval(3600).timeIntervalSince1970 / 60) * 60
        copy.runAt = Date(timeIntervalSince1970: whole)
        return copy
    }

    /// Tomorrow at 09:00 in the draft's zone.
    static func tomorrowAtNine(_ d: TriggerDraft, now: Date = Date()) -> TriggerDraft {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: d.timezone) ?? .current
        var copy = d
        if let tomorrow = calendar.date(byAdding: .day, value: 1, to: now) {
            copy.runAt = calendar.date(bySettingHour: 9, minute: 0, second: 0, of: tomorrow)
        }
        return copy
    }
}

/// The rows of a trigger, for a Form or List section: the kind, its fields, the time zone, and —
/// with `preview` (the page's call to `schedules.previewTrigger`) — the next runs as the person types.
/// Identifiers: `<tag>.kind`, `<tag>.cron`, `<tag>.every`, `<tag>.when`, `<tag>.zone`, `<tag>.next`.
struct TriggerEditor: View {
    @Binding var draft: TriggerDraft
    var preview: ((ScheduleTrigger) async throws -> [Date])?
    var tag = "trigger"
    @Environment(\.l10n) private var l10n
    @State private var runs: Result<[Date], HubFailure>?

    var body: some View {
        Group {
            Picker("", selection: $draft.kind) {
                Text(l10n("kit.trigger.cron")).tag(ScheduleTrigger.Kind.cron)
                Text(l10n("kit.trigger.every")).tag(ScheduleTrigger.Kind.interval)
                Text(l10n("kit.trigger.once")).tag(ScheduleTrigger.Kind.once)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("\(tag).kind")
            fields
            NavigationLink {
                TimeZoneList(selection: $draft.timezone)
            } label: {
                LabeledContent(l10n("kit.trigger.timezone"), value: draft.timezone.replacingOccurrences(of: "_", with: " "))
            }
            .accessibilityIdentifier("\(tag).zone")
            if problem == .zone { problemText }
            if preview != nil { next }
        }
        .task(id: TriggerRules.build(draft)) { await readNext() }
        // A one-off starts at tomorrow 09:00, so it is a whole trigger from the first tap.
        .onChange(of: draft.kind) { _, kind in
            if kind == .once, draft.runAt == nil { draft = TriggerRules.tomorrowAtNine(draft) }
        }
    }

    private var problem: TriggerProblem? { TriggerRules.problem(draft) }

    private var problemText: some View {
        let key: String
        switch problem {
        case .cron: key = "kit.trigger.bad_cron"
        case .every: key = "kit.trigger.bad_every"
        case .zone: key = "kit.trigger.bad_zone"
        default: key = "kit.required"
        }
        return Text(l10n(key)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
    }

    @ViewBuilder
    private var fields: some View {
        switch draft.kind {
        case .cron:
            VStack(alignment: .leading, spacing: Space.s1) {
                TextField(l10n("kit.trigger.expression"), text: $draft.cron)
                    .font(.system(size: FontSize.sizeMd, design: .monospaced))
                    .environment(\.layoutDirection, .leftToRight)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("\(tag).cron")
                Text(l10n("kit.trigger.expression_help")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                if problem == .cron { problemText }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Space.s2) {
                    ForEach(TriggerRules.presets.indices, id: \.self) { index in
                        let cron = TriggerRules.presets[index]
                        Button(l10n(TriggerRules.presetKeys[index])) { draft.cron = cron }
                            .buttonStyle(ChipButtonStyle(quiet: draft.cron.trimmingCharacters(in: .whitespaces) != cron))
                    }
                }
            }
        case .interval:
            HStack {
                TextField("1", text: $draft.every)
                    .keyboardType(.numberPad)
                    .frame(maxWidth: 80)
                    .accessibilityIdentifier("\(tag).every")
                Picker("", selection: $draft.unit) {
                    Text(l10n("kit.trigger.minutes")).tag(EveryUnit.minutes)
                    Text(l10n("kit.trigger.hours")).tag(EveryUnit.hours)
                    Text(l10n("kit.trigger.days")).tag(EveryUnit.days)
                }
                .pickerStyle(.segmented)
            }
            if problem == .every { problemText }
        case .once:
            DatePicker(
                l10n("kit.trigger.when"),
                selection: Binding(get: { draft.runAt ?? TriggerRules.tomorrowAtNine(draft).runAt ?? Date() }, set: { draft.runAt = $0 }),
                displayedComponents: [.date, .hourAndMinute]
            )
            .environment(\.timeZone, TimeZone(identifier: draft.timezone) ?? .current)
            .accessibilityIdentifier("\(tag).when")
            HStack(spacing: Space.s2) {
                Button(l10n("kit.trigger.in_hour")) { draft = TriggerRules.inAnHour(draft) }
                    .buttonStyle(ChipButtonStyle(quiet: true))
                Button(l10n("kit.trigger.tomorrow")) { draft = TriggerRules.tomorrowAtNine(draft) }
                    .buttonStyle(ChipButtonStyle(quiet: true))
            }
        }
    }

    @ViewBuilder
    private var next: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(l10n("kit.trigger.next")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
            switch runs {
            case .success(let dates) where dates.isEmpty:
                Text(l10n("kit.trigger.never")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
            case .success(let dates):
                ForEach(dates, id: \.self) { date in
                    Text(date.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened, timeZone: TimeZone(identifier: draft.timezone) ?? .current)))
                        .font(.system(size: FontSize.sizeSm))
                }
            case .failure(let failure):
                NoticeView(text: failure.describe(l10n), tone: .danger)
            case nil:
                ProgressView()
            }
        }
        .accessibilityIdentifier("\(tag).next")
    }

    private func readNext() async {
        guard let preview, let trigger = TriggerRules.build(draft) else { runs = nil; return }
        try? await Task.sleep(for: .milliseconds(400))
        if Task.isCancelled { return }
        do {
            runs = .success(try await preview(trigger))
        } catch is CancellationError {
        } catch {
            runs = .failure(HubFailure(error))
        }
    }
}

/// Every time zone the phone knows, searched by any part of its name.
struct TimeZoneList: View {
    @Binding var selection: String
    @Environment(\.dismiss) private var dismiss
    @Environment(\.l10n) private var l10n
    @State private var query = ""

    private var zones: [String] {
        let all = TimeZone.knownTimeZoneIdentifiers.filter { $0.contains("/") && !$0.hasPrefix("Etc/") } + ["UTC"]
        let q = query.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "_")
        return q.isEmpty ? all : all.filter { $0.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        List(zones, id: \.self) { zone in
            Button {
                selection = zone
                dismiss()
            } label: {
                HStack {
                    Text(zone.replacingOccurrences(of: "_", with: " ")).foregroundStyle(Tone.text)
                    Spacer()
                    if zone == selection { LucideIcon(.check, size: 16).foregroundStyle(Tone.accent) }
                }
            }
        }
        .searchable(text: $query, prompt: l10n("kit.trigger.zone_search"))
        .navigationTitle(l10n("kit.trigger.timezone"))
    }
}

// Settings → Skills usage (`audit.getSkillUsage`, decision §50; the web's SkillsUsagePage): which
// skills the agents loaded in a period, how often, and which enabled skills no run loaded — the
// period as a segmented row, the profile (every one, or one) and the agent as menus, the totals, the
// per-day chart as a compact list of bars, the top skills. Android's SkillsUsagePage.kt is the twin.
import CoreHubClient
import SwiftUI

struct SkillsUsagePage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var days = 30
    /// One profile's slug, or nil for every profile the person may enter.
    @State private var only: String?
    @State private var agent: String?
    /// The agents offered survive a reload, so the menu does not empty while the next answer comes.
    @State private var agents: [ActiveAgent] = []

    var body: some View {
        AsyncContent(key: "\(app.currentProfile)/\(days)/\(only ?? "*")/\(agent ?? "*")") {
            // «All» still names a profile in the header, the one the person is in (ADR 0016).
            let scope = only ?? app.currentProfile, every = only == nil, period = days, chosen = agent
            let offset = SkillsUsageRules.utcOffset()
            return try await app.api.call {
                try await AuditAPI.auditGetSkillUsage(
                    xHubProfile: scope, days: period, profiles: every ? .all : nil, agentId: chosen,
                    utcOffsetMinutes: offset, apiConfiguration: $0
                )
            }
        } content: { report, reload in
            List {
                filters
                SkillsUsageBody(report: report)
            }
            .refreshable { reload() }
            .onAppear { agents = report.agents }
            .onChange(of: report.agents) { _, new in agents = new }
        }
        .accessibilityIdentifier("skills.page")
    }

    @ViewBuilder
    private var filters: some View {
        Section {
            Picker(l10n("audit.period"), selection: $days) {
                ForEach(SkillsUsageRules.periods, id: \.self) { count in
                    Text(l10n("knowledge.skills_period_\(count)")).tag(count)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("skills.days")
            if app.profiles.count > 1 {
                Picker(l10n("knowledge.skills_profile"), selection: $only) {
                    Text(l10n("knowledge.skills_all_profiles")).tag(String?.none)
                    ForEach(app.profiles, id: \.slug) { profile in Text(profile.name).tag(Optional(profile.slug)) }
                }
                .pickerStyle(.menu)
                .accessibilityIdentifier("skills.profile")
            }
            Picker(l10n("knowledge.skills_agent"), selection: $agent) {
                Text(l10n("knowledge.skills_all_agents")).tag(String?.none)
                ForEach(SkillsUsageRules.agentChoices(agents, chosen: agent), id: \.agentId) { choice in
                    Text(choice.name ?? l10n("knowledge.skills_removed_agent")).tag(Optional(choice.agentId))
                }
            }
            .pickerStyle(.menu)
            .accessibilityIdentifier("skills.agent")
        }
    }
}

/// The report: since when it counts, the four totals, the days as bars, the top skills, the never-used ones.
struct SkillsUsageBody: View {
    let report: SkillUsageReport
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        let totals = report.totals
        Section {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: Space.s2), GridItem(.flexible(), spacing: Space.s2)], spacing: Space.s2) {
                stat(String(totals.uses), l10n("knowledge.skills_total_uses"), nil)
                stat(String(totals.distinctSkills), l10n("knowledge.skills_distinct"), nil)
                stat(totals.topSkill?.skill ?? l10n("knowledge.skills_none"), l10n("knowledge.skills_top"),
                     totals.topSkill.map { l10n("knowledge.skills_uses_n", ["count": String($0.uses)]) })
                stat(totals.neverUsedCount.map(String.init) ?? l10n("knowledge.skills_unknown"), l10n("knowledge.skills_never_used"),
                     totals.neverUsedCount == nil ? l10n("knowledge.skills_unknown_hint") : nil)
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
        } footer: {
            Text(since + " " + l10n("knowledge.skills_not_counted_acp"))
                .accessibilityIdentifier("skills.since")
        }
        if totals.uses == 0 {
            EmptyStateView(icon: .chartColumn, title: l10n("knowledge.skills_nothing"))
                .listRowBackground(Color.clear)
                .accessibilityIdentifier("skills.nothing")
        } else {
            Section(l10n("knowledge.skills_daily")) { daily }
            Section(l10n("knowledge.skills_table")) {
                ForEach(report.topSkills, id: \.skill) { skill in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(skill.skill).font(.system(size: FontSize.sizeSm, weight: .medium))
                            Spacer()
                            Text(String(skill.uses)).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                        }
                        Text(l10n("knowledge.skills_row", [
                            "uses": String(skill.uses), "share": SkillsUsageRules.percent(skill.share),
                            "when": skill.lastUsedAt.shortText(app.language),
                        ]))
                        .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                    .accessibilityIdentifier("skills.skill.\(skill.skill)")
                }
            }
        }
        if let never = report.neverUsed, !never.isEmpty {
            Section(l10n("knowledge.skills_never_used_list")) {
                Text(never.joined(separator: " · "))
                    .font(.system(size: FontSize.sizeSm))
                    .accessibilityIdentifier("skills.never")
            }
        }
    }

    private var since: String {
        guard let date = report.countingSince else { return l10n("knowledge.skills_not_counting") }
        return l10n("knowledge.skills_counting_since", ["date": SkillsUsageRules.dayText(date, language: app.language, year: true)])
    }

    private func stat(_ value: String, _ label: String, _ hint: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.system(size: FontSize.sizeLg, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            if let hint { Text(hint).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Space.s3)
        .background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.lg, style: .continuous))
    }

    /// The web's stacked chart as a phone list: each day with a use, its bar beside the busiest day,
    /// and what was loaded.
    @ViewBuilder
    private var daily: some View {
        let days = SkillsUsageRules.activeDays(report)
        let most = days.map(\.uses).max() ?? 0
        ForEach(days, id: \.date) { day in
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: Space.s2) {
                    Text(SkillsUsageRules.dayText(day.date, language: app.language))
                        .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        .frame(width: 56, alignment: .leading)
                    GeometryReader { geometry in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Tone.surface2)
                            Capsule().fill(Tone.accent)
                                .frame(width: geometry.size.width * SkillsUsageRules.fraction(day.uses, most: most))
                        }
                    }
                    .frame(height: 8)
                    Text(String(day.uses)).font(.system(size: FontSize.sizeXs, weight: .medium))
                }
                Text(SkillsUsageRules.daySkills(day).map { "\($0.skill ?? l10n("knowledge.skills_other")) \($0.uses)" }.joined(separator: " · "))
                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint).lineLimit(2)
                    .padding(.leading, 64)
            }
        }
        .accessibilityIdentifier("skills.daily")
    }
}

extension PhonePage {
    static let skillsUsage = PhonePage(.skillsUsage) { _ in SkillsUsagePage() }
}

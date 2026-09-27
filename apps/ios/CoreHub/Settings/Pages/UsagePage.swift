// Settings → Usage at a glance.
import AVFoundation
import CoreHubClient
import SwiftUI
import UIKit

struct UsageNativePage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var days = 30
    @State private var all = false

    var body: some View {
        AsyncContent(key: "\(app.currentProfile)/\(days)/\(all)") {
            let profile = app.currentProfile, period = days, everyProfile = all
            return try await app.api.call {
                try await AuditAPI.auditGetUsage(xHubProfile: profile, days: period, profiles: everyProfile ? .all : nil, apiConfiguration: $0)
            }
        } content: { report, reload in
            List {
                Picker(l10n("nav.usage"), selection: $days) {
                    Text(l10n("usage_page.week")).tag(7)
                    Text(l10n("usage_page.month")).tag(30)
                }
                .pickerStyle(.segmented)
                if app.isAdmin { Toggle(l10n("usage_page.all_profiles"), isOn: $all) }
                Section {
                    HStack {
                        total(AdminLogic.tokens(report.totals.totalTokens), l10n("usage_page.tokens"))
                        total(String(report.totals.runs), l10n("usage_page.runs"))
                        total(report.totals.cost.map { "$\($0.amount)" } ?? "—", l10n("usage_page.cost"))
                    }
                    if report.totals.unreportedRuns > 0 {
                        Text(l10n("usage_page.unreported", ["count": String(report.totals.unreportedRuns)])).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                }
                if !report.byModel.isEmpty {
                    Section(l10n("usage_page.by_model")) {
                        ForEach(report.byModel.prefix(8), id: \.model) { model in
                            FactRow(label: model.model, value: "\(AdminLogic.tokens(model.totalTokens)) · \(Int((model.share * 100).rounded()))%")
                        }
                    }
                }
                if !report.byAgent.isEmpty {
                    Section(l10n("usage_page.by_agent")) {
                        ForEach(report.byAgent.prefix(8), id: \.agentId) { agent in
                            FactRow(label: agent.name ?? agent.agentId, value: "\(AdminLogic.tokens(agent.totalTokens ?? 0)) · \(agent.runs)")
                        }
                    }
                }
                if report.byModel.isEmpty && report.byAgent.isEmpty { EmptyRow(icon: .chartColumn, message: l10n("usage_page.none")) }
            }
            .refreshable { reload() }
        }
    }

    private func total(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.system(size: FontSize.sizeLg, weight: .semibold))
            Text(label).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension PhonePage {
    static let usage = PhonePage(.usage) { _ in UsageNativePage() }
}

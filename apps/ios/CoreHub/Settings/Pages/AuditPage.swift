// An audit report as a list (not a destination of its own; kept for the reports pages).
import CoreHubClient
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// Usage: the hub's usage report for the profile, shown as the hub writes it. Logs and
/// Performance read their own live endpoints (LiveTools.swift, contract decision §51).
struct AuditPage: View {
    let kind: AuditAPI.Kind_auditGetReport
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: "\(kind.rawValue)/\(app.currentProfile)") {
            let profile = app.currentProfile
            return try await app.api.call {
                try await AuditAPI.auditGetReport(kind: kind, xHubProfile: profile, apiConfiguration: $0)
            }
        } content: { report, reload in
            List {
                FactRow(label: l10n("audit.period"), value: "\(report.period.from.shortText(app.language)) – \(report.period.to.shortText(app.language))")
                ForEach(report.data.keys.sorted(), id: \.self) { key in
                    JSONOutline(label: key, value: report.data[key] ?? .null)
                }
            }
            .refreshable { reload() }
        }
    }
}

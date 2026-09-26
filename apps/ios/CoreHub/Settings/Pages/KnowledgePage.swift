// Settings → Knowledge: what the profile's agents know.
import CoreHubClient
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

struct KnowledgePage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call { try await KnowledgeAPI.knowledgeListItems(xHubProfile: profile, limit: 100, apiConfiguration: $0) }.items
        } content: { items, reload in
            List {
                if items.isEmpty { EmptyRow(icon: .bookOpen) }
                ForEach(items, id: \.id) { item in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(item.title ?? item.kind.rawValue).font(.system(size: FontSize.sizeSm, weight: .medium))
                            Spacer()
                            StatusPill(text: l10n("knowledge.kind_\(item.kind.rawValue)"))
                        }
                        if let content = item.content {
                            Text(content).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(3)
                                .contentDirection(of: content)
                        }
                    }
                }
            }
            .refreshable { reload() }
        }
    }
}

extension PhonePage {
    static let knowledge = PhonePage(.knowledge) { _ in KnowledgePage() }
}

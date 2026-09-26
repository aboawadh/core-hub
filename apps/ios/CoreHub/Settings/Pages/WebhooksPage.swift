// Settings → Webhooks (notify): the hooks and a test send.
import CoreHubClient
import SwiftUI

struct WebhooksPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var notes: [String: String] = [:]

    var body: some View {
        AsyncContent(key: "webhooks") {
            try await app.api.call { try await NotifyAPI.notifyListWebhooks(apiConfiguration: $0) }.items
        } content: { hooks, reload in
            List {
                if hooks.isEmpty { EmptyRow(icon: .webhook) }
                ForEach(hooks, id: \.id) { hook in
                    VStack(alignment: .leading, spacing: Space.s1) {
                        HStack {
                            Text(hook.name).font(.system(size: FontSize.sizeMd, weight: .medium))
                            Spacer()
                            StatusPill(text: hook.enabled ? l10n("common.on") : l10n("common.off"), kind: hook.enabled ? .good : .neutral)
                        }
                        Text(hook.url).font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textMuted)
                            .lineLimit(1).truncationMode(.middle)
                            .environment(\.layoutDirection, .leftToRight)
                        if let note = notes[hook.id] { NoticeView(text: note, tone: .info) }
                        Button(l10n("webhooks.test")) { Task { await test(hook) } }
                            .font(.system(size: FontSize.sizeSm))
                    }
                }
            }
            .refreshable { reload() }
        }
    }

    private func test(_ hook: Webhook) async {
        do {
            _ = try await app.api.call { try await NotifyAPI.notifyTestWebhook(webhookId: hook.id, apiConfiguration: $0) }
            notes[hook.id] = l10n("webhooks.test_sent")
        } catch {
            notes[hook.id] = HubFailure(error).describe(l10n)
        }
    }
}

extension PhonePage {
    static let webhooks = PhonePage(.webhooks) { _ in WebhooksPage() }
}

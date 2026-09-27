// A sender waiting to pair with the agent's channel, in the pending sheet: approved or denied here,
// or under Approvals on the agent's channels page (apps batch 14; Android: PendingPairingCard.kt).
import CoreHubClient
import SwiftUI

struct PairingRequestCard: View {
    let item: PendingPairing
    let model: PendingModel
    @Environment(\.l10n) private var l10n
    @State private var busy = false

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text(l10n("admin.pairing_request", ["name": item.request.userName ?? item.request.userId, "platform": item.request.platform]))
                .font(.system(size: FontSize.sizeSm, weight: .medium))
            Text(l10n("admin.pairing_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            HStack(spacing: Space.s2) {
                Button(l10n("admin.pairing_approve")) { answer(true) }
                    .buttonStyle(.borderedProminent).tint(Tone.accent)
                    .accessibilityIdentifier("pending.pairing.approve")
                Button(l10n("admin.pairing_deny")) { answer(false) }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("pending.pairing.deny")
            }
            .font(.system(size: FontSize.sizeSm))
            .disabled(busy)
        }
        .padding(Space.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.lg))
        .accessibilityIdentifier("pending.pairing.\(item.request.requestId)")
    }

    private func answer(_ approve: Bool) {
        busy = true
        Task {
            await model.answer(item, approve: approve)
            busy = false
        }
    }
}

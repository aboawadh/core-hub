// What the person sent while the agent was still working (the web's MessageQueue, owner's decision
// of 2026-09-22 «يصير بالطابور وفيه زر اجبار ارسال… وزر Steer»): with "Sending while the agent
// works" on «wait in line», a message sent during a live turn waits here on the phone, shown above
// the composer, where the person can still change their mind — **send now** (the hub's `next`: it
// runs right after the live turn), **steer** (`interrupt`: stop the turn and take this instead) or
// **remove** (it was never sent). When the turn ends, the waiting messages go in order. They live
// in this chat screen only: leaving it drops what was never sent, as the web's tab does.
import CoreHubClient
import SwiftUI

struct QueuedMessage: Identifiable, Equatable {
    let id: String
    let message: OutgoingMessage
    let replyTo: String?

    /// A few words of what was typed: enough to tell two waiting messages apart.
    var preview: String {
        MessageQueueRules.preview(message)
    }
}

enum MessageQueueRules {
    /// Only «wait in line» holds a message back, and only while a turn is alive.
    static func holdsBack(_ mode: RunCreate.When, busy: Bool) -> Bool {
        busy && mode == .queue
    }

    static func preview(_ message: OutgoingMessage) -> String {
        let text = message.text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        if !text.isEmpty { return text.count > 80 ? String(text.prefix(80)) + "…" : text }
        return message.attachments.first?.name ?? ""
    }
}

struct MessageQueueStrip: View {
    let items: [QueuedMessage]
    let sendNow: (QueuedMessage) -> Void
    let steer: (QueuedMessage) -> Void
    let remove: (QueuedMessage) -> Void
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            HStack {
                StatusPill(text: l10n("message_queue.title"))
                Spacer()
                Text(String(items.count)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    .accessibilityIdentifier("queue.count")
            }
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                HStack(spacing: Space.s2) {
                    Text(String(index + 1)).font(.system(size: FontSize.sizeXs, weight: .semibold)).foregroundStyle(Tone.textFaint)
                    Text(item.preview).font(.system(size: FontSize.sizeSm)).lineLimit(1).contentDirection(of: item.preview, fill: false)
                    Spacer(minLength: Space.s1)
                    Button { sendNow(item) } label: { LucideIcon(.arrowUp, size: 14).tapTarget(32) }
                        .accessibilityLabel(l10n("message_queue.send_now"))
                        .accessibilityIdentifier("queue.send_now")
                    Button(l10n("message_queue.steer")) { steer(item) }
                        .font(.system(size: FontSize.sizeXs, weight: .medium))
                        .accessibilityHint(l10n("message_queue.steer_hint"))
                        .accessibilityIdentifier("queue.steer")
                    Button { remove(item) } label: { LucideIcon(.x, size: 14).tapTarget(32) }
                        .accessibilityLabel(l10n("message_queue.remove"))
                        .accessibilityIdentifier("queue.remove")
                }
                .buttonStyle(.borderless)
                .accessibilityIdentifier("queue.item")
            }
        }
        .padding(Space.s2)
        .background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).stroke(Tone.border, lineWidth: 1))
        .accessibilityIdentifier("chat.queue")
    }
}

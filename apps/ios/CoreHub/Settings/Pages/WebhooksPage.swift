// Settings → Webhooks (the web's WebhooksTab, contract decision §59): the addresses this hub calls on
// its own, in the profile you are in. Each card switches on and off, sends a test and says how it went,
// shows its recent deliveries (followed while one waits) and sends a failed one again; add and edit are
// a sheet (WebhookSheet.swift), which then shows a new signing secret once with Copy. Hermes's incoming
// webhooks are the agent's Channels page, not this one. Android's WebhooksPage.kt is the twin.
import CoreHubClient
import SwiftUI
import UIKit

/// What the add/edit sheet is open for.
enum WebhookEditing: Identifiable {
    case new
    case edit(Webhook)

    var id: String {
        switch self {
        case .new: return "new"
        case .edit(let hook): return hook.id
        }
    }

    var hook: Webhook? {
        if case .edit(let hook) = self { return hook }
        return nil
    }
}

struct WebhooksPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var editing: WebhookEditing?
    @State private var deleting: Webhook?
    @State private var generation = 0

    var body: some View {
        AsyncContent(key: "webhooks/\(app.currentProfile)/\(generation)") {
            let profile = app.currentProfile
            return try await app.api.call { try await NotifyAPI.notifyListWebhooks(xHubProfile: profile, apiConfiguration: $0) }.items
        } content: { hooks, reload in
            List {
                Section {
                    Button {
                        editing = .new
                    } label: {
                        LucideLabel(l10n("knowledge.webhooks_add"), icon: .plus)
                    }
                    .accessibilityIdentifier("webhooks.add")
                } footer: {
                    Text(l10n("knowledge.webhooks_intro"))
                }
                Section {
                    NoticeView(text: l10n("knowledge.webhooks_forwarding_note"), tone: .info)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets())
                }
                if hooks.isEmpty {
                    EmptyStateView(icon: .webhook, title: l10n("knowledge.webhooks_none"), message: l10n("knowledge.webhooks_none_body"))
                        .listRowBackground(Color.clear)
                        .accessibilityIdentifier("webhooks.empty")
                }
                ForEach(hooks, id: \.id) { hook in
                    Section {
                        WebhookCard(hook: hook, profile: app.currentProfile, changed: reload,
                                    edit: { editing = .edit(hook) }, delete: { deleting = hook })
                    }
                }
            }
            .refreshable { reload() }
        }
        .sheet(item: $editing) { target in
            WebhookSheet(hook: target.hook, profile: app.currentProfile) { generation += 1 }
        }
        .confirmDelete($deleting, name: { $0.name }, delete: { [api = app.api, profile = app.currentProfile] hook in
            try await api.call { try await NotifyAPI.notifyDeleteWebhook(xHubProfile: profile, webhookId: hook.id, apiConfiguration: $0) }
        }, deleted: { _ in generation += 1 })
        .accessibilityIdentifier("webhooks.page")
    }
}

/// One webhook with its own switch, test and deliveries.
struct WebhookCard: View {
    let hook: Webhook
    let profile: String
    let changed: () -> Void
    let edit: () -> Void
    let delete: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var switching = false
    @State private var testing = false
    @State private var outcome: NotifyWebhookRules.TestOutcome?
    @State private var error: String?
    @State private var open = false
    @State private var deliveries: [WebhookDelivery]?
    @State private var redelivered = false
    @State private var tick = 0

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(hook.name).font(.system(size: FontSize.sizeMd, weight: .medium)).contentDirection(of: hook.name)
                    Text(hook.url).font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textMuted)
                        .lineLimit(2).truncationMode(.middle)
                        .environment(\.layoutDirection, .leftToRight)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                Toggle(l10n("knowledge.webhooks_enabled"), isOn: Binding(get: { hook.enabled }, set: { on in Task { await setEnabled(on) } }))
                    .labelsHidden()
                    .disabled(switching)
                    .accessibilityIdentifier("webhook.\(hook.id).enabled")
            }
            facts
            Text(stats).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            if let lastError = hook.stats.lastError {
                Text(l10n("knowledge.webhooks_last_error", ["error": lastError])).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
            }
            if testing { Text(l10n("knowledge.webhooks_testing")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
            if let outcome {
                NoticeView(text: describe(outcome), tone: outcome.delivered ? .success : .danger)
                    .accessibilityIdentifier("webhook.\(hook.id).outcome")
            }
            if let error { NoticeView(text: error, tone: .danger) }
            if open { deliveriesList }
            HStack(spacing: Space.s3) {
                Button { Task { await test() } } label: { LucideLabel(l10n("knowledge.webhooks_test"), icon: .play) }
                    .buttonStyle(.bordered).disabled(testing)
                    .accessibilityIdentifier("webhook.\(hook.id).test")
                Button { open.toggle(); redelivered = false } label: {
                    LucideLabel(l10n(open ? "knowledge.webhooks_hide_deliveries" : "knowledge.webhooks_show_deliveries"), icon: .activity)
                }
                .buttonStyle(.borderless)
                .accessibilityIdentifier("webhook.\(hook.id).deliveries")
                Spacer(minLength: 0)
                Menu {
                    Button(action: edit) { LucideLabel(l10n("knowledge.webhooks_edit"), icon: .pencil) }
                    Button(role: .destructive, action: delete) { LucideLabel(l10n("kit.delete"), icon: .trash) }
                } label: {
                    LucideIcon(.ellipsis, size: 18).frame(width: 32, height: 32)
                }
                .accessibilityLabel(l10n("kit.more_actions"))
                .accessibilityIdentifier("webhook.\(hook.id).more")
            }
            .font(.system(size: FontSize.sizeSm))
        }
        .padding(.vertical, Space.s1)
        .accessibilityIdentifier("webhook.\(hook.id)")
        // An open table follows the hub: quickly while something waits to be sent, slowly otherwise.
        .task(id: "\(open)/\(tick)") {
            while open, !Task.isCancelled {
                await loadDeliveries()
                let wait = NotifyWebhookRules.waiting(deliveries ?? []) ? 1_500 : 5_000
                try? await Task.sleep(for: .milliseconds(wait))
            }
        }
    }

    private var facts: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Space.s1) {
                StatusPill(text: l10n(hook.enabled ? "knowledge.webhooks_on" : "knowledge.webhooks_off"), kind: hook.enabled ? .good : .neutral)
                StatusPill(text: l10n(hook.secret != nil ? "knowledge.webhooks_signed" : "knowledge.webhooks_unsigned"), kind: hook.secret != nil ? .neutral : .warn)
                StatusPill(text: hook.events.isEmpty ? l10n("knowledge.webhooks_no_events")
                           : l10n("knowledge.webhooks_event_count", ["count": String(hook.events.count)]))
                StatusPill(text: hook.profiles.isEmpty ? l10n("knowledge.webhooks_all_profiles")
                           : l10n("knowledge.webhooks_profile_count", ["count": String(hook.profiles.count)]))
                if hook.includeContent { StatusPill(text: l10n("knowledge.webhooks_with_content"), kind: .warn) }
                if hook.allowPrivateNetwork { StatusPill(text: l10n("knowledge.webhooks_private_allowed"), kind: .warn) }
            }
        }
    }

    private var stats: String {
        var text = l10n("knowledge.webhooks_stats", ["delivered": String(hook.stats.delivered), "failed": String(hook.stats.failed)])
        if let last = hook.stats.lastDeliveryAt {
            text += " · " + l10n("knowledge.webhooks_last_delivery", ["when": last.shortText(app.language)])
        }
        return text
    }

    @ViewBuilder
    private var deliveriesList: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            if let deliveries {
                if deliveries.isEmpty {
                    Text(l10n("knowledge.webhooks_no_deliveries")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
                ForEach(deliveries, id: \.id) { delivery in
                    HStack(alignment: .center, spacing: Space.s2) {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: Space.s1) {
                                StatusPill(text: l10n("knowledge.webhooks_delivery_\(delivery.status.rawValue)"), kind: tone(delivery.status))
                                Text(delivery.event).font(.system(size: FontSize.sizeXs, design: .monospaced))
                            }
                            Text(line(delivery)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            if let failure = delivery.error {
                                Text(failure).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger).lineLimit(2)
                            }
                        }
                        Spacer(minLength: 0)
                        if NotifyWebhookRules.canRedeliver(delivery) {
                            Button(l10n("knowledge.webhooks_redeliver")) { Task { await redeliver(delivery) } }
                                .buttonStyle(.bordered).font(.system(size: FontSize.sizeXs))
                                .accessibilityIdentifier("delivery.\(delivery.id).redeliver")
                        }
                    }
                }
                if redelivered {
                    Text(l10n("knowledge.webhooks_redelivered")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
            } else {
                ProgressView()
            }
        }
        .accessibilityIdentifier("webhook.deliveries")
    }

    private func line(_ delivery: WebhookDelivery) -> String {
        var text = delivery.createdAt.shortText(app.language) + " · "
            + l10n("knowledge.webhooks_delivery_facts", ["attempts": String(delivery.attempts), "code": delivery.responseStatus.map(String.init) ?? "—"])
        if let next = delivery.nextAttemptAt {
            text += " · " + l10n("knowledge.webhooks_delivery_next", ["when": next.shortText(app.language)])
        }
        return text
    }

    private func tone(_ status: WebhookDelivery.Status) -> StatusPill.Kind {
        switch status {
        case .delivered: return .good
        case .failed, .dead: return .bad
        case .queued: return .neutral
        }
    }

    private func describe(_ outcome: NotifyWebhookRules.TestOutcome) -> String {
        if outcome.delivered { return l10n("knowledge.webhooks_test_ok", ["status": String(outcome.status)]) }
        if outcome.status > 0 { return l10n("knowledge.webhooks_test_status", ["status": String(outcome.status)]) }
        return l10n("knowledge.webhooks_test_failed", ["error": outcome.error ?? "—"])
    }

    private func setEnabled(_ on: Bool) async {
        switching = true
        defer { switching = false }
        let profile = profile, id = hook.id
        do {
            _ = try await app.api.call {
                try await NotifyAPI.notifyUpdateWebhook(xHubProfile: profile, webhookId: id, webhookWrite: WebhookWrite(enabled: on, maxRetries: nil), apiConfiguration: $0)
            }
            error = nil
            changed()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    /// Queues the test delivery and follows its job to the end (as the web polls it).
    private func test() async {
        testing = true
        outcome = nil
        defer { testing = false; tick += 1 }
        let profile = profile, id = hook.id
        do {
            let job = try await app.api.call { try await NotifyAPI.notifyTestWebhook(xHubProfile: profile, webhookId: id, apiConfiguration: $0) }
            for _ in 0..<60 {
                let state = try await app.api.call { try await JobsAPI.jobsGet(xHubProfile: profile, jobId: job.jobId, apiConfiguration: $0) }
                if let done = NotifyWebhookRules.outcome(state) {
                    outcome = done
                    error = nil
                    changed()
                    return
                }
                try await Task.sleep(for: .milliseconds(700))
            }
            outcome = NotifyWebhookRules.TestOutcome(delivered: false, status: 0, error: nil)
        } catch is CancellationError {
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func loadDeliveries() async {
        let profile = profile, id = hook.id
        do {
            deliveries = try await app.api.call {
                try await NotifyAPI.notifyListWebhookDeliveries(xHubProfile: profile, webhookId: id, limit: 10, apiConfiguration: $0)
            }.items
        } catch is CancellationError {
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func redeliver(_ delivery: WebhookDelivery) async {
        let profile = profile, id = hook.id
        do {
            _ = try await app.api.call {
                try await NotifyAPI.notifyRedeliverWebhookDelivery(xHubProfile: profile, webhookId: id, deliveryId: delivery.id, apiConfiguration: $0)
            }
            redelivered = true
            error = nil
            tick += 1
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// The new signing secret, once: the hub keeps it but never shows it again.
struct SecretOnceSheet: View {
    let secret: String
    @Environment(\.dismiss) private var dismiss
    @Environment(\.l10n) private var l10n
    @State private var copied = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Space.s4) {
                Text(l10n("knowledge.webhooks_secret_once")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                Text(secret)
                    .font(.system(size: FontSize.sizeSm, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(Space.s3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Tone.surface2, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
                    .environment(\.layoutDirection, .leftToRight)
                    .accessibilityIdentifier("webhook.secret.value")
                Button {
                    UIPasteboard.general.string = secret
                    copied = true
                } label: {
                    LucideLabel(l10n(copied ? "knowledge.webhooks_copied" : "knowledge.webhooks_copy"), icon: copied ? .check : .copy)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("webhook.secret.copy")
                Spacer()
            }
            .padding(Space.s4)
            .navigationTitle(l10n("knowledge.webhooks_secret_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(l10n("knowledge.webhooks_secret_done")) { dismiss() }
                        .accessibilityIdentifier("webhook.secret.done")
                }
            }
        }
        .interactiveDismissDisabled()
    }
}

extension PhonePage {
    static let webhooks = PhonePage(.webhooks) { _ in WebhooksPage() }
}

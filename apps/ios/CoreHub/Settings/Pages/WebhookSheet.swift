// Add or edit a webhook (the web's WebhookDialog): name and address (the hub's refusal of an address
// said beside it), private addresses, on/off, the signing secret (keep, new, stop), the events from the
// hub's catalogue with a filter, the profiles (every one, or these), message text, retries. A new
// secret is made here and, once saved, the sheet turns into it to be copied; the hub never shows it again.
// Android's WebhookSheet.kt is the twin.
import CoreHubClient
import SwiftUI

struct WebhookSheet: View {
    let hook: Webhook?
    let profile: String
    /// After a save (the list reads the hub again).
    let saved: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var draft = NotifyWebhookRules.Draft()
    @State private var ready = false
    @State private var events: [NotifyListWebhookEvents200ResponseItemsInner]?
    @State private var eventsError: String?
    @State private var filter = ""
    @State private var saving = false
    @State private var failure: HubFailure?
    /// The new secret after a save: the sheet turns into it, on screen once.
    @State private var shownSecret: String?

    private var arabic: Bool { app.language == .ar }
    private var refusalKey: String? { NotifyWebhookRules.urlRefusalKey(failure?.reason) }

    var body: some View {
        if let shownSecret {
            SecretOnceSheet(secret: shownSecret)
        } else {
            form
        }
    }

    private var form: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(l10n("knowledge.webhooks_name"), text: $draft.name)
                        .accessibilityIdentifier("webhook.form.name")
                    TextField("https://", text: $draft.url)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .font(.system(size: FontSize.sizeSm, design: .monospaced))
                        .environment(\.layoutDirection, .leftToRight)
                        .accessibilityIdentifier("webhook.form.url")
                        .onChange(of: draft.url) { _, _ in if refusalKey != nil { failure = nil } }
                    if NotifyWebhookRules.badURL(draft.url) {
                        Text(l10n("knowledge.webhooks_url_scheme")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
                    } else if let refusalKey {
                        Text(l10n(refusalKey)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
                    }
                    Toggle(isOn: $draft.allowPrivate) {
                        labelled("knowledge.webhooks_allow_private", hint: "knowledge.webhooks_allow_private_hint")
                    }
                    .accessibilityIdentifier("webhook.form.private")
                    Toggle(l10n("knowledge.webhooks_enabled"), isOn: $draft.enabled)
                        .accessibilityIdentifier("webhook.form.enabled")
                } footer: {
                    Text(l10n("knowledge.webhooks_url_hint"))
                }

                Section {
                    Text("\(NotifyWebhookRules.signatureHeader): sha256=<hex>")
                        .font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textMuted)
                        .environment(\.layoutDirection, .leftToRight)
                    Picker(l10n("knowledge.webhooks_signing"), selection: $draft.secret) {
                        ForEach(NotifyWebhookRules.secretChoices(hook), id: \.self) { choice in
                            Text(secretLabel(choice)).tag(choice)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    .accessibilityIdentifier("webhook.form.secret")
                    if draft.secret == .new {
                        Text(l10n("knowledge.webhooks_secret_new_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                } header: {
                    Text(l10n("knowledge.webhooks_signing"))
                } footer: {
                    Text(l10n("knowledge.webhooks_signing_hint"))
                }

                Section {
                    if let eventsError { NoticeView(text: eventsError, tone: .danger) }
                    if let events {
                        TextField(l10n("knowledge.webhooks_events_filter"), text: $filter)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .accessibilityIdentifier("webhook.form.events.filter")
                        let shown = NotifyWebhookRules.filterEvents(events, needle: filter, arabic: arabic)
                        ForEach(shown, id: \.name) { event in
                            Toggle(isOn: Binding(
                                get: { draft.events.contains(event.name) },
                                set: { on in if on { draft.events.insert(event.name) } else { draft.events.remove(event.name) } }
                            )) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(arabic ? event.description.ar : event.description.en).font(.system(size: FontSize.sizeSm))
                                    Text(event.name).font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textMuted)
                                }
                            }
                            .accessibilityIdentifier("webhook.form.event.\(event.name)")
                        }
                        if shown.isEmpty {
                            Text(l10n("knowledge.webhooks_events_no_match")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        }
                    } else if eventsError == nil {
                        ProgressView()
                    }
                } header: {
                    Text(l10n("knowledge.webhooks_events") + (draft.events.isEmpty ? "" : " (\(draft.events.count))"))
                } footer: {
                    Text(l10n("knowledge.webhooks_events_hint"))
                }

                Section {
                    Picker(l10n("knowledge.webhooks_profiles"), selection: $draft.allProfiles) {
                        Text(l10n("knowledge.webhooks_profiles_all")).tag(true)
                        Text(l10n("knowledge.webhooks_profiles_some")).tag(false)
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    .accessibilityIdentifier("webhook.form.profiles")
                    if !draft.allProfiles {
                        ForEach(app.profiles, id: \.slug) { choice in
                            Toggle(choice.name, isOn: Binding(
                                get: { draft.profiles.contains(choice.slug) },
                                set: { on in if on { draft.profiles.insert(choice.slug) } else { draft.profiles.remove(choice.slug) } }
                            ))
                            .accessibilityIdentifier("webhook.form.profile.\(choice.slug)")
                        }
                        if NotifyWebhookRules.noProfile(draft) {
                            Text(l10n("knowledge.webhooks_profiles_pick_one")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
                        }
                    }
                } header: {
                    Text(l10n("knowledge.webhooks_profiles"))
                } footer: {
                    Text(l10n("knowledge.webhooks_profiles_hint"))
                }

                Section {
                    Toggle(isOn: $draft.includeContent) {
                        labelled("knowledge.webhooks_include_content", hint: "knowledge.webhooks_include_content_hint")
                    }
                    .accessibilityIdentifier("webhook.form.content")
                    LabeledContent(l10n("knowledge.webhooks_max_retries")) {
                        TextField("5", text: $draft.retries)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 64)
                            .accessibilityIdentifier("webhook.form.retries")
                    }
                } footer: {
                    Text(l10n("knowledge.webhooks_max_retries_hint"))
                }

                if let failure, refusalKey == nil {
                    Section { NoticeView(text: failure.describe(l10n), tone: .danger) }
                }
            }
            .navigationTitle(hook.map { l10n("knowledge.webhooks_edit_title", ["name": $0.name]) } ?? l10n("knowledge.webhooks_add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(l10n("common.cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving {
                        ProgressView()
                    } else {
                        Button(l10n("common.save")) { Task { await submit() } }
                            .disabled(!NotifyWebhookRules.ready(draft))
                            .accessibilityIdentifier("webhook.form.save")
                    }
                }
            }
        }
        .onAppear {
            guard !ready else { return }
            draft = NotifyWebhookRules.draft(hook)
            ready = true
        }
        .task { await loadEvents() }
        .onChange(of: draft.retries) { _, typed in
            let digits = String(typed.filter(\.isNumber).prefix(2))
            if digits != typed { draft.retries = digits }
        }
        .accessibilityIdentifier("webhook.form")
    }

    private func labelled(_ key: String, hint: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(l10n(key))
            Text(l10n(hint)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
        }
    }

    private func secretLabel(_ choice: NotifyWebhookRules.SecretChoice) -> String {
        let signed = hook?.secret != nil
        switch choice {
        case .keep: return l10n("knowledge.webhooks_secret_keep")
        case .new: return l10n(signed ? "knowledge.webhooks_secret_new" : "knowledge.webhooks_secret_make")
        case .unsigned: return l10n(signed ? "knowledge.webhooks_secret_remove" : "knowledge.webhooks_secret_none")
        }
    }

    private func loadEvents() async {
        let profile = profile
        do {
            events = try await app.api.call { try await NotifyAPI.notifyListWebhookEvents(apiConfiguration: $0) }.items
            eventsError = nil
        } catch is CancellationError {
        } catch {
            eventsError = HubFailure(error).describe(l10n)
        }
    }

    private func submit() async {
        saving = true
        defer { saving = false }
        let secret = draft.secret == .new ? NotifyWebhookRules.newSecret() : nil
        let body = NotifyWebhookRules.write(draft, known: events?.map(\.name), secret: secret)
        let profile = profile, id = hook?.id
        do {
            if let id {
                _ = try await app.api.call { try await NotifyAPI.notifyUpdateWebhook(xHubProfile: profile, webhookId: id, webhookWrite: body, apiConfiguration: $0) }
            } else {
                _ = try await app.api.call { try await NotifyAPI.notifyCreateWebhook(xHubProfile: profile, webhookWrite: body, apiConfiguration: $0) }
            }
            failure = nil
            saved()
            if let secret { shownSecret = secret } else { dismiss() }
        } catch {
            failure = HubFailure(error)
        }
    }
}

// «Webhooks» under the channels (decision §97, the web's WebhooksSection): Hermes's incoming routes, each
// with its address on this hub and its secret (copy, show), a local test, and Delete for a route made
// here; New webhook takes a name, a prompt, a description, events and where the answer goes. An outside
// service reaches the address only if the hub is open to the internet: said plainly.
// Android's AgentWebhooks.kt is its twin.
import CoreHubClient
import SwiftUI
import UIKit

struct WebhooksSection: View {
    let agent: Agent
    let channels: [Channel]
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var list: HermesWebhookList?
    @State private var error: String?
    @State private var creating = false
    @State private var tests: [String: String] = [:]
    @State private var accepted: [String: Bool] = [:]
    @State private var testing: Set<String> = []
    @State private var question: ToolQuestion?

    private var hub: String { app.credentials?.hubURL.absoluteString ?? "" }

    var body: some View {
        Section {
            Text(l10n("agents2.wh.intro")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            NoticeView(text: l10n("agents2.wh.public_note", ["origin": hub]), tone: .info)
            if let error { NoticeView(text: error, tone: .danger) }
            if let list {
                if list.items.isEmpty {
                    Text(l10n("agents2.wh.none")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                        .accessibilityIdentifier("webhooks.empty")
                }
                ForEach(list.items, id: \.name) { route in
                    WebhookRow(route: route, url: WebhookRules.url(hub: hub, path: route.path), result: tests[route.name], accepted: accepted[route.name] ?? false,
                               testing: testing.contains(route.name)) {
                        Task { await test(route.name) }
                    } delete: {
                        question = ToolQuestion(title: l10n("agents2.wh.delete_title", ["name": route.name]), body: l10n("agents2.wh.delete_body"), confirm: l10n("agents2.wh.delete")) {
                            Task { await remove(route.name) }
                        }
                    }
                }
            }
            Button {
                creating = true
            } label: {
                LucideLabel(l10n("agents2.wh.new"), icon: .plus, size: 14)
            }
            .accessibilityIdentifier("webhook.new")
        } header: {
            HStack {
                Text(l10n("agents2.wh.title"))
                Spacer()
                if let listener = list?.listener { listenerPill(listener) }
            }
        }
        .task(id: app.currentProfile) { await load() }
        .toolQuestion($question)
        .sheet(isPresented: $creating, onDismiss: { Task { await load() } }) {
            CreateWebhookSheet(agent: agent, taken: Set((list?.items ?? []).map(\.name)), targets: WebhookRules.targets(channels))
        }
    }

    private func listenerPill(_ listener: HermesWebhookListener) -> some View {
        let state = listener.enabled ? listener.status.rawValue : "off"
        return StatusPill(text: l10n("agents2.wh.listener_\(state)"),
                          kind: state == "online" ? .good : state == "error" ? .bad : state == "off" ? .neutral : .warn)
            .accessibilityIdentifier("webhooks.listener")
    }

    private func load() async {
        let profile = app.currentProfile
        do {
            list = try await app.api.call { try await AgentsAPI.agentsListWebhooks(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }
            error = nil
            // A listener switched on a moment ago is starting with its gateway: read again until it says.
            if let listener = list?.listener, listener.enabled, listener.status == .unknown {
                try? await Task.sleep(for: .seconds(3))
                if !Task.isCancelled { await load() }
            }
        } catch {
            self.error = AgentToolErrors.describe(error, l10n)
        }
    }

    private func test(_ name: String) async {
        let profile = app.currentProfile
        testing.insert(name)
        defer { testing.remove(name) }
        do {
            let result = try await app.api.call { try await AgentsAPI.agentsTestWebhook(xHubProfile: profile, agentId: agent.id, routeName: name, apiConfiguration: $0) }
            let ok = WebhookRules.accepted(result.status)
            var said = ""
            if case .string(let text)? = result.body?["error"] { said = text }
            accepted[name] = ok
            tests[name] = ok ? l10n("agents2.wh.test_accepted") : l10n("agents2.wh.test_refused", ["status": String(result.status), "reason": said])
        } catch {
            accepted[name] = false
            tests[name] = AgentToolErrors.describe(error, l10n)
        }
    }

    private func remove(_ name: String) async {
        let profile = app.currentProfile
        do {
            try await app.api.call { try await AgentsAPI.agentsDeleteWebhook(xHubProfile: profile, agentId: agent.id, routeName: name, apiConfiguration: $0) }
            tests[name] = nil
            error = nil
        } catch {
            self.error = AgentToolErrors.describe(error, l10n)
        }
        await load()
    }
}

struct WebhookRow: View {
    let route: HermesWebhook
    let url: String
    let result: String?
    let accepted: Bool
    let testing: Bool
    let test: () -> Void
    let delete: () -> Void
    @Environment(\.l10n) private var l10n
    @State private var showSecret = false

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            HStack(spacing: Space.s1) {
                Text(route.name).font(.system(size: FontSize.sizeMd, weight: .semibold, design: .monospaced))
                if route._static { StatusPill(text: l10n("agents2.wh.static")) }
                if !route.events.isEmpty { StatusPill(text: route.events.joined(separator: ", ")) }
            }
            if let description = route.description { Text(description).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
            Text(l10n("agents2.wh.prompt_label") + " " + (route.prompt.isEmpty ? l10n("agents2.wh.prompt_empty") : route.prompt))
                .font(.system(size: FontSize.sizeXs)).lineLimit(3)
            Text(l10n("agents2.wh.url")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            HStack {
                Text(url).font(.system(size: FontSize.sizeXs, design: .monospaced)).lineLimit(2).textSelection(.enabled)
                    .accessibilityIdentifier("webhook.\(route.name).url")
                Spacer()
                copyButton(url, label: l10n("agents2.wh.copy_url"))
            }
            .environment(\.layoutDirection, .leftToRight)
            if let secret = route.secret {
                Text(l10n("agents2.wh.secret")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                HStack {
                    Text(showSecret ? secret : "••••••••••••").font(.system(size: FontSize.sizeXs, design: .monospaced))
                        .accessibilityIdentifier("webhook.\(route.name).secret")
                    Spacer()
                    Button {
                        showSecret.toggle()
                    } label: {
                        LucideIcon(showSecret ? .eyeOff : .eye, size: 14).foregroundStyle(Tone.textMuted)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(l10n(showSecret ? "agents2.wh.hide_secret" : "agents2.wh.show_secret"))
                    copyButton(secret, label: l10n("agents2.wh.copy_secret"))
                }
                .environment(\.layoutDirection, .leftToRight)
            } else {
                Text(l10n("agents2.wh.secret_global")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
            if let result {
                NoticeView(text: result, tone: accepted ? .success : .warning).accessibilityIdentifier("webhook.\(route.name).result")
            }
            HStack {
                Button(action: test) {
                    if testing { ProgressView() } else { LucideLabel(l10n("agents2.wh.test"), icon: .activity, size: 14) }
                }
                .buttonStyle(ChipButtonStyle())
                .disabled(testing)
                .accessibilityIdentifier("webhook.\(route.name).test")
                if !route._static {
                    Button(action: delete) { LucideLabel(l10n("agents2.wh.delete"), icon: .trash, size: 14) }
                        .buttonStyle(ChipButtonStyle(quiet: true))
                        .accessibilityIdentifier("webhook.\(route.name).delete")
                }
            }
        }
        .accessibilityIdentifier("webhook.\(route.name)")
    }

    private func copyButton(_ text: String, label: String) -> some View {
        Button {
            UIPasteboard.general.string = text
        } label: {
            LucideIcon(.copy, size: 14).foregroundStyle(Tone.textMuted)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

struct CreateWebhookSheet: View {
    let agent: Agent
    let taken: Set<String>
    let targets: [Channel]
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var prompt = ""
    @State private var description = ""
    @State private var events = ""
    @State private var deliver = "log"
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        let problem = WebhookRules.nameProblem(name, taken: taken)
        NavigationStack {
            Form {
                Section {
                    Text(l10n("agents2.wh.new_intro")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    if let error { NoticeView(text: error, tone: .danger) }
                }
                Section {
                    TextField(l10n("agents2.wh.name"), text: $name, prompt: Text(verbatim: "github-issues")).monoField()
                        .accessibilityIdentifier("webhook.create.name")
                    if let problem {
                        Text(l10n(problem == .invalid ? "agents2.wh.name_invalid" : "agents2.wh.name_taken")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
                    }
                } header: {
                    Text(l10n("agents2.wh.name"))
                } footer: {
                    Text(l10n("agents2.wh.name_hint"))
                }
                Section {
                    TextField(l10n("agents2.wh.prompt_placeholder"), text: $prompt, axis: .vertical).lineLimit(3...8)
                        .accessibilityIdentifier("webhook.create.prompt")
                } header: {
                    Text(l10n("agents2.wh.prompt"))
                } footer: {
                    Text(l10n("agents2.wh.prompt_hint"))
                }
                Section(l10n("agents2.wh.description")) {
                    TextField(l10n("agents2.wh.description"), text: $description).accessibilityIdentifier("webhook.create.description")
                }
                Section {
                    TextField(String("issues, push"), text: $events).monoField().accessibilityIdentifier("webhook.create.events")
                } header: {
                    Text(l10n("agents2.wh.events"))
                } footer: {
                    Text(l10n("agents2.wh.events_hint"))
                }
                Section {
                    Picker(l10n("agents2.wh.deliver"), selection: $deliver) {
                        Text(l10n("agents2.wh.deliver_log")).tag("log")
                        ForEach(targets, id: \.platform) { channel in
                            Text(l10n("agents2.wh.deliver_to", ["name": channel.label])).tag(channel.platform)
                        }
                    }
                    .accessibilityIdentifier("webhook.create.deliver")
                }
            }
            .navigationTitle(l10n("agents2.wh.new_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(l10n("agents2.wh.create")) { Task { await create() } }
                            .disabled(!WebhookRules.ready(name: name, prompt: prompt, taken: taken))
                            .accessibilityIdentifier("webhook.create.save")
                    }
                }
            }
        }
    }

    private func create() async {
        let profile = app.currentProfile
        let trimmed = description.trimmingCharacters(in: .whitespaces)
        let write = HermesWebhookCreate(name: name.trimmingCharacters(in: .whitespaces).lowercased(), prompt: prompt,
                                        description: trimmed.isEmpty ? nil : trimmed, events: WebhookRules.events(events), deliver: deliver)
        saving = true
        defer { saving = false }
        do {
            _ = try await app.api.call { try await AgentsAPI.agentsCreateWebhook(xHubProfile: profile, agentId: agent.id, hermesWebhookCreate: write, apiConfiguration: $0) }
            dismiss()
        } catch {
            self.error = AgentToolErrors.describe(error, l10n)
        }
    }
}

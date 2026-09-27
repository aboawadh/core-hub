// The cards of an agent's Settings page the web shows beside the fields (apps batch 9): signing a
// coding agent in to its own vendor account, context compression for the profile, what Hermes wrote
// and waits for review, and the sheet a list or JSON field is typed in. Android's AgentSettingsCards.kt
// is the twin.
import CoreHubClient
import SwiftUI
import UIKit

/// A list (one item per line) or JSON field typed in a sheet; Save sends it, a JSON that does not parse waits.
struct SettingTextSheet: View {
    let field: SettingsField
    let title: String
    let save: (JSONValue) async -> Void
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var typed: String

    init(field: SettingsField, title: String, save: @escaping (JSONValue) async -> Void) {
        self.field = field
        self.title = title
        self.save = save
        _typed = State(initialValue: SettingValues.typedText(field))
    }

    var body: some View {
        let parsed = SettingValues.parse(field, typed)
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $typed)
                        .font(.system(size: FontSize.sizeSm, design: field.kind == .json ? .monospaced : .default))
                        .frame(minHeight: 220)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("setting.editor")
                    if parsed == nil { NoticeView(text: l10n("agents2.settings.json_bad"), tone: .warning) }
                } footer: {
                    Text(l10n(field.kind == .list ? "agents2.settings.hint_list" : "agents2.settings.hint_json") + " " + l10n("agent_settings.empty_is_default"))
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(l10n("common.save")) {
                        guard let parsed else { return }
                        Task {
                            await save(parsed)
                            dismiss()
                        }
                    }
                    .disabled(parsed == nil)
                    .accessibilityIdentifier("setting.save")
                }
            }
        }
    }
}

/// Sign in to the agent's own account (Kimi Code, Grok Build) by device code: the code and the link
/// the agent printed, a quiet wait while the hub polls, then the outcome. Nothing here holds a token.
struct AgentSignInSection: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.openURL) private var openURL
    @State private var current: ProviderSignIn?
    @State private var starting = false
    @State private var error: String?
    @State private var copied = false

    var body: some View {
        Section {
            Text(l10n("agents2.signin.hint", ["name": agent.name])).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            if let error { NoticeView(text: error, tone: .danger) }
            if let signIn = current {
                Text(l10n("agents2.signin.steps")).font(.system(size: FontSize.sizeSm))
                if let code = signIn.userCode {
                    HStack(spacing: Space.s2) {
                        Text(l10n("agents2.signin.code")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                        Text(code).font(.system(size: FontSize.sizeLg, weight: .semibold, design: .monospaced)).tracking(2)
                            .textSelection(.enabled)
                            .accessibilityIdentifier("agent.signin.code")
                        Spacer()
                        Button {
                            UIPasteboard.general.string = code
                            copied = true
                        } label: {
                            LucideIcon(copied ? .check : .copy, size: 16)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(l10n(copied ? "agents2.signin.copied" : "agents2.signin.copy"))
                    }
                    .environment(\.layoutDirection, .leftToRight)
                }
                if let url = URL(string: signIn.verificationUrl) {
                    Button { openURL(url) } label: { LucideLabel(l10n("agents2.signin.open"), icon: .externalLink, size: 14) }
                        .accessibilityIdentifier("agent.signin.link")
                }
                switch signIn.status {
                case .pending:
                    HStack(spacing: Space.s2) {
                        ProgressView()
                        Text(l10n("agents2.signin.waiting")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                case .approved:
                    NoticeView(text: l10n("agents2.signin.approved", ["name": agent.name]), tone: .success).accessibilityIdentifier("agent.signin.approved")
                case .denied, .expired, .failed:
                    NoticeView(text: l10n("agents2.signin.\(signIn.status.rawValue)") + (signIn.error.map { " \($0)" } ?? ""), tone: .danger)
                }
            }
            if current?.status != .pending {
                Button {
                    Task { await start() }
                } label: {
                    if starting { ProgressView() } else { LucideLabel(l10n(current == nil ? "agents2.signin.action" : "agents2.signin.retry"), icon: .keyRound, size: 14) }
                }
                .disabled(starting)
                .accessibilityIdentifier("agent.signin.start")
            }
        } header: {
            Text(l10n("agents2.signin.title", ["name": agent.name]))
        }
        .task(id: current?.id) { await poll() }
    }

    private func start() async {
        let profile = app.currentProfile
        starting = true
        defer { starting = false }
        error = nil
        copied = false
        do {
            current = try await app.api.call { try await AgentsAPI.agentsStartSignIn(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    /// While the sign-in waits, the hub is asked again every two seconds (a failed read is tried again).
    private func poll() async {
        let profile = app.currentProfile
        while let signIn = current, signIn.status == .pending {
            try? await Task.sleep(for: .seconds(2))
            if Task.isCancelled { return }
            do {
                current = try await app.api.call { try await AgentsAPI.agentsGetSignIn(xHubProfile: profile, agentId: agent.id, signInId: signIn.id, apiConfiguration: $0) }
                error = nil
            } catch {
                self.error = HubFailure(error).describe(l10n)
            }
        }
    }
}

/// Context compression for the selected profile (decision §57): percentages here, ratios on the wire.
struct CompressionSection: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var profileId: String?
    @State private var stored: CompressionRules.Draft?
    @State private var draft: CompressionRules.Draft?
    @State private var invalid: CompressionRules.Field?
    @State private var saving = false
    @State private var saved = false
    @State private var error: String?

    var body: some View {
        Section {
            Text(l10n("agents2.compression.subtitle")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            if let error { NoticeView(text: error, tone: .danger) }
            if saved { NoticeView(text: l10n("agents2.compression.saved"), tone: .success) }
            if let shown = draft ?? stored {
                Toggle(isOn: Binding(get: { shown.enabled }, set: { on in edit { $0.enabled = on } })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(l10n("agents2.compression.enabled"))
                        Text(l10n("agents2.compression.enabled_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                }
                .accessibilityIdentifier("compression.enabled")
                number(.threshold, "agents2.compression.threshold", hint: "agents2.compression.threshold_hint", \.threshold, shown)
                number(.target, "agents2.compression.target", hint: "agents2.compression.target_hint", \.target, shown)
                number(.protectFirst, "agents2.compression.protect_first", hint: nil, \.protectFirst, shown)
                number(.protectLast, "agents2.compression.protect_last", hint: nil, \.protectLast, shown)
                number(.contextLength, "agents2.compression.context_length", hint: "agents2.compression.context_length_hint", \.contextLength, shown)
                if draft != nil {
                    HStack {
                        Button(l10n("common.cancel")) { draft = nil; invalid = nil }
                            .buttonStyle(ChipButtonStyle(quiet: true))
                        Spacer()
                        Button {
                            Task { await save() }
                        } label: {
                            if saving { ProgressView() } else { Text(l10n("common.save")) }
                        }
                        .buttonStyle(ChipButtonStyle())
                        .accessibilityIdentifier("compression.save")
                    }
                }
            } else if error == nil {
                ProgressView().frame(maxWidth: .infinity)
            }
        } header: {
            Text(l10n("agents2.compression.title"))
        }
        .task(id: app.currentProfile) { await load() }
    }

    private func edit(_ change: (inout CompressionRules.Draft) -> Void) {
        guard var next = draft ?? stored else { return }
        change(&next)
        draft = next
        invalid = nil
        saved = false
    }

    @ViewBuilder
    private func number(_ field: CompressionRules.Field, _ label: String, hint: String?, _ path: WritableKeyPath<CompressionRules.Draft, String>, _ shown: CompressionRules.Draft) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(l10n(label)).font(.system(size: FontSize.sizeSm))
                Spacer()
                TextField("", text: Binding(get: { shown[keyPath: path] }, set: { value in edit { $0[keyPath: path] = value } }))
                    .keyboardType(.numberPad)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 110)
                    .monoField()
                    .accessibilityIdentifier("compression.\(field.rawValue)")
            }
            if invalid == field { Text(l10n("agents2.compression.invalid")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger) }
            if let hint { Text(l10n(hint)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
        }
    }

    private func load() async {
        let slug = app.currentProfile
        do {
            let profiles = try await app.api.call { try await AuthAPI.authListProfiles(apiConfiguration: $0) }.items
            guard let id = profiles.first(where: { $0.slug == slug })?.id else { return }
            profileId = id
            let settings = try await app.api.call { try await AuthAPI.authGetProfileSettings(profileId: id, apiConfiguration: $0) }
            stored = CompressionRules.draft(settings.compression)
            draft = nil
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func save() async {
        guard let d = draft, let id = profileId else { return }
        switch CompressionRules.patch(d) {
        case .failure(let bad):
            invalid = bad.field
        case .success(let patch):
            saving = true
            defer { saving = false }
            do {
                let result = try await app.api.call {
                    try await AuthAPI.authUpdateProfileSettings(profileId: id, profileSettingsPatch: ProfileSettingsPatch(compression: patch), apiConfiguration: $0)
                }
                stored = CompressionRules.draft(result.settings.compression)
                draft = nil
                saved = true
                error = nil
            } catch {
                self.error = HubFailure(error).describe(l10n)
            }
        }
    }
}

/// «Waiting for review» (decision §58): what Hermes's agent wrote to its memory or skills while a write
/// approval is on. Approve applies it with Hermes's own code; Reject drops it. Read again every fifteen
/// seconds, as the web does.
struct PendingWritesSection: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var writes: [PendingWrite]?
    @State private var busy: String?
    @State private var error: String?

    var body: some View {
        Section {
            Text(l10n("agents2.pending.subtitle")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            if let error { NoticeView(text: error, tone: .danger) }
            if let writes {
                if writes.isEmpty { Text(l10n("agents2.pending.empty")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted) }
                ForEach(writes, id: \.id) { write in
                    PendingWriteRow(write: write, busy: busy == write.id) { approve in Task { await answer(write, approve: approve) } }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        } header: {
            Text(l10n("agents2.pending.title"))
        }
        .task(id: app.currentProfile) {
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(for: .seconds(15))
            }
        }
    }

    private func load() async {
        let profile = app.currentProfile
        do {
            writes = try await app.api.call { try await AgentsAPI.agentsListPendingWrites(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }.items
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func answer(_ write: PendingWrite, approve: Bool) async {
        let profile = app.currentProfile
        busy = write.id
        defer { busy = nil }
        do {
            if approve {
                let kind = AgentsAPI.WriteKind_agentsApprovePendingWrite(rawValue: write.kind.rawValue) ?? .memory
                _ = try await app.api.call { try await AgentsAPI.agentsApprovePendingWrite(xHubProfile: profile, agentId: agent.id, writeKind: kind, writeId: write.id, apiConfiguration: $0) }
            } else {
                let kind = AgentsAPI.WriteKind_agentsRejectPendingWrite(rawValue: write.kind.rawValue) ?? .memory
                try await app.api.call { try await AgentsAPI.agentsRejectPendingWrite(xHubProfile: profile, agentId: agent.id, writeKind: kind, writeId: write.id, apiConfiguration: $0) }
            }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        await load()
    }
}

struct PendingWriteRow: View {
    let write: PendingWrite
    let busy: Bool
    let answer: (Bool) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            HStack(spacing: Space.s1) {
                StatusPill(text: l10n(write.kind == .skills ? "agents2.pending.kind_skill" : write.target == "user" ? "agents2.pending.kind_user" : "agents2.pending.kind_memory"),
                           kind: write.kind == .memory ? .good : .warn)
                StatusPill(text: l10n(write.origin == "background_review" ? "agents2.pending.origin_review" : "agents2.pending.origin_chat"))
                if let at = write.createdAt { Text(at.shortText(app.language)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
            }
            let line = (write.name.map { "\($0) — " } ?? "") + (write.summary.isEmpty ? write.action : write.summary)
            Text(line).font(.system(size: FontSize.sizeSm, weight: .medium)).contentDirection(of: line)
            if let old = write.oldText {
                Text(old).font(.system(size: FontSize.sizeXs)).strikethrough().foregroundStyle(Tone.textMuted).lineLimit(8).contentDirection(of: old)
            }
            if let content = write.content {
                Text(content).font(.system(size: FontSize.sizeXs)).lineLimit(14).contentDirection(of: content)
                    .accessibilityIdentifier("pending.\(write.id).content")
            }
            HStack {
                Button { answer(true) } label: { LucideLabel(l10n("agents2.pending.approve"), icon: .check, size: 14) }
                    .buttonStyle(ChipButtonStyle())
                    .accessibilityIdentifier("pending.\(write.id).approve")
                Button { answer(false) } label: { LucideLabel(l10n("agents2.pending.reject"), icon: .x, size: 14) }
                    .buttonStyle(ChipButtonStyle(quiet: true))
                    .accessibilityIdentifier("pending.\(write.id).reject")
            }
            .disabled(busy)
        }
        .accessibilityIdentifier("pending.\(write.id)")
    }
}

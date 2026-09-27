// Device connections → the admin's push senders, folded at the bottom (the owner, 2026-09-25: the
// senders under the devices distracted from them). Folded, one line says how each stands; open, one
// row per sender with its state and Set up / Change. The setup is file first (the owner, 2026-09-26:
// Apple and Firebase hand out files): the service-account JSON or the `.p8` key, read and checked here
// (PushSenderRules), with «paste instead» behind it. A secret is never shown back (Android: PushSendersCard.kt).
import CoreHubClient
import SwiftUI
import UniformTypeIdentifiers

private func stateKind(_ state: PushSender.State) -> StatusPill.Kind {
    switch state {
    case .ready: return .good
    case .disabled: return .neutral
    case .notConfigured: return .warn
    case .error: return .bad
    }
}

private func stateKey(_ state: PushSender.State) -> String {
    switch state {
    case .ready: return "admin.push_state_ready"
    case .disabled: return "admin.push_state_disabled"
    case .notConfigured: return "admin.push_state_not_configured"
    case .error: return "admin.push_state_error"
    }
}

/// A sender a sheet opens on.
private struct SenderItem: Identifiable {
    let sender: PushSender
    var id: String { sender.provider.rawValue }
}

struct PushSendersSection: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var senders: [PushSender]?
    @State private var open = false
    @State private var editing: SenderItem?
    @State private var saved: PushSender?
    @State private var error: String?

    var body: some View {
        Section {
            Button { withAnimation { open.toggle() } } label: {
                HStack(spacing: Space.s2) {
                    LucideIcon(open ? .chevronUp : .chevronDown, size: 14).foregroundStyle(Tone.textMuted)
                    Text(l10n("admin.push_title")).font(.system(size: FontSize.sizeMd, weight: .semibold)).foregroundStyle(Tone.text)
                    Spacer()
                    if !open {
                        ForEach(senders ?? [], id: \.provider) { sender in
                            StatusDot(kind: dotKind(sender.state), label: l10n("admin.push_provider_\(sender.provider.rawValue)"))
                        }
                    }
                }
            }
            .accessibilityIdentifier("push.senders.toggle")
            if open {
                Text(l10n("admin.push_intro")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                if let error { NoticeView(text: error, tone: .danger) }
                ForEach(senders ?? [], id: \.provider) { sender in row(sender) }
            }
        }
        .task { await load() }
        .sheet(item: $editing) { item in
            NavigationStack {
                SenderSetupView(sender: item.sender) { result in
                    editing = nil
                    saved = result
                    Task { await load() }
                }
            }
        }
    }

    private func dotKind(_ state: PushSender.State) -> StatusDot.Kind {
        switch state {
        case .ready: return .good
        case .disabled: return .neutral
        case .notConfigured: return .warn
        case .error: return .bad
        }
    }

    @ViewBuilder
    private func row(_ sender: PushSender) -> some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            HStack(spacing: Space.s2) {
                Text(l10n("admin.push_provider_\(sender.provider.rawValue)")).font(.system(size: FontSize.sizeSm, weight: .medium))
                StatusPill(text: l10n(stateKey(sender.state)), kind: stateKind(sender.state))
                if sender.source == .environment { StatusPill(text: l10n("admin.push_source_environment")) }
                if sender.source == .relay { StatusPill(text: l10n("admin.push_source_relay")) }
                Spacer(minLength: Space.s1)
                if PushSenderRules.editable(sender) {
                    Button(l10n(PushSenderRules.isStored(sender) ? "admin.push_change" : "admin.push_set_up")) {
                        saved = nil
                        editing = SenderItem(sender: sender)
                    }
                    .buttonStyle(.bordered)
                    .font(.system(size: FontSize.sizeSm))
                    .accessibilityIdentifier("push.sender.\(sender.provider.rawValue).edit")
                }
            }
            Text(l10n("admin.push_devices", ["count": String(sender.devices)])).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            let parts = PushSenderRules.saved(sender).map(savedText)
            if !parts.isEmpty {
                Text(l10n("admin.push_saved", ["what": parts.joined(separator: " · ")])).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
            if let saved, saved.provider == sender.provider {
                let outcome = PushSenderRules.outcome(saved)
                NoticeView(text: outcomeText(saved, outcome), tone: outcome == .valid ? .success : .warning)
                    .accessibilityIdentifier("push.sender.saved")
            } else if let problem = sender.lastError {
                Text(problem).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
            }
        }
        .accessibilityIdentifier("push.sender.\(sender.provider.rawValue)")
    }

    private func savedText(_ part: PushSenderRules.Saved) -> String {
        switch part {
        case .project(let id): return l10n("admin.push_saved_project", ["project": id])
        case .key(let ending): return l10n("admin.push_saved_key", ["ending": ending])
        case .team(let id): return l10n("admin.push_saved_team", ["team": id])
        case .sandbox: return l10n("admin.push_sandbox")
        }
    }

    private func outcomeText(_ saved: PushSender, _ outcome: PushSenderRules.Outcome) -> String {
        switch outcome {
        case .valid: return l10n(saved.provider == .fcm ? "admin.push_valid_fcm" : "admin.push_valid_apns")
        case .incomplete: return l10n("admin.push_saved_incomplete")
        case .refused: return l10n("admin.push_refused", ["detail": saved.lastError ?? ""])
        }
    }

    private func load() async {
        do {
            senders = try await app.api.call { try await DevicesAPI.devicesListPushSenders(apiConfiguration: $0) }.items
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// One sender's setup. `done` hears what the hub made of a save, or nil when it was forgotten or closed.
private struct SenderSetupView: View {
    let sender: PushSender
    let done: (PushSender?) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var form: PushSenderRules.Form
    @State private var fileName: String?
    @State private var fileProblem: String?
    @State private var picking = false
    @State private var pasting = false
    @State private var showEnvironment = false
    @State private var forgetting = false
    @State private var busy = false
    @State private var error: String?

    init(sender: PushSender, done: @escaping (PushSender?) -> Void) {
        self.sender = sender
        self.done = done
        _form = State(initialValue: PushSenderRules.form(sender))
    }

    private var fcm: Bool { sender.provider == .fcm }
    private var stored: Bool { PushSenderRules.isStored(sender) }
    private var fileTypes: [UTType] { fcm ? [.json, .plainText, .data] : [UTType(filenameExtension: "p8") ?? .data, .plainText, .data] }

    private func accountProblem(_ problem: PushSenderRules.AccountProblem) -> String {
        switch problem {
        case .notJSON: return l10n("admin.push_fcm_not_json")
        case .googleServices: return l10n("admin.push_fcm_google_services")
        case .notServiceAccount: return l10n("admin.push_fcm_not_service_account")
        case .missing: return l10n("admin.push_fcm_missing")
        }
    }

    private func keyProblem(_ problem: PushSenderRules.KeyProblem) -> String {
        l10n(problem == .notAKey ? "admin.push_apns_not_a_key" : "admin.push_apns_not_p8")
    }

    var body: some View {
        Form {
            Section {
                Text(l10n(fcm ? "admin.push_fcm_intro" : "admin.push_apns_intro")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                Toggle(l10n("admin.push_enabled"), isOn: $form.enabled).accessibilityIdentifier("push.form.enabled")
            }
            Section {
                HStack {
                    Button { picking = true } label: { LucideLabel(l10n(fcm ? "admin.push_fcm_upload" : "admin.push_apns_upload"), icon: .file, size: 14) }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("push.form.file")
                    if let fileName { Text(fileName).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(1) }
                }
                if let fileProblem { NoticeView(text: fileProblem, tone: .danger).accessibilityIdentifier("push.form.file_problem") }
                fileState
                if stored && (fcm ? form.serviceAccount : form.privateKey).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text(l10n("admin.push_secret_kept")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
                Button(l10n(pasting ? "admin.push_paste_hide" : "admin.push_paste_instead")) { pasting.toggle() }
                    .accessibilityIdentifier("push.form.paste")
                if pasting {
                    TextEditor(text: Binding(
                        get: { fcm ? form.serviceAccount : form.privateKey },
                        set: { value in
                            fileName = nil
                            fileProblem = nil
                            if fcm { form.serviceAccount = value } else { form.privateKey = value }
                        }
                    ))
                    .font(.system(size: FontSize.sizeXs, design: .monospaced))
                    .frame(minHeight: 120)
                    .environment(\.layoutDirection, .leftToRight)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("push.form.secret")
                }
            } header: {
                Text(l10n(fcm ? "admin.push_service_account" : "admin.push_private_key"))
            } footer: {
                Text(l10n("admin.push_secret_hint"))
            }
            if !fcm {
                Section {
                    idField("admin.push_key_id", text: $form.keyID, tag: "key_id")
                    idField("admin.push_team_id", text: $form.teamID, tag: "team_id")
                    idField("admin.push_bundle_id", text: $form.bundleID, tag: "bundle_id")
                    Picker(l10n("admin.push_environment"), selection: $form.sandbox) {
                        Text(l10n("admin.push_production")).tag(false)
                        Text(l10n("admin.push_sandbox")).tag(true)
                    }
                }
            }
            Section {
                DisclosureGroup(l10n("admin.push_env_title"), isExpanded: $showEnvironment) {
                    Text(l10n("admin.push_env_body")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    ForEach(PushSenderRules.environmentNames(sender.provider), id: \.self) { name in
                        Text(name).font(.system(size: FontSize.sizeXs, design: .monospaced)).textSelection(.enabled)
                            .environment(\.layoutDirection, .leftToRight)
                    }
                }
                .font(.system(size: FontSize.sizeSm))
                .accessibilityIdentifier("push.form.env")
                if let error { NoticeView(text: error, tone: .danger) }
                if stored {
                    Button(l10n("admin.push_forget"), role: .destructive) { forgetting = true }
                        .accessibilityIdentifier("push.form.forget")
                }
            }
        }
        .navigationTitle(l10n("admin.push_provider_\(sender.provider.rawValue)"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { done(nil) } }
            ToolbarItem(placement: .confirmationAction) {
                if busy {
                    ProgressView()
                } else {
                    Button(l10n("common.save")) { Task { await save() } }
                        .disabled(!PushSenderRules.ready(sender, form))
                        .accessibilityIdentifier("push.form.save")
                }
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: fileTypes) { result in
            if case .success(let url) = result { read(url) }
        }
        .alert(l10n("admin.push_forget_title", ["provider": l10n("admin.push_provider_\(sender.provider.rawValue)")]), isPresented: $forgetting) {
            Button(l10n("common.cancel"), role: .cancel) {}
            Button(l10n("admin.push_forget"), role: .destructive) { Task { await forget() } }
        } message: {
            Text(l10n("admin.push_forget_body"))
        }
    }

    /// What the picked or pasted secret is: the project it belongs to, the key read, or why not.
    @ViewBuilder
    private var fileState: some View {
        if fcm, !form.serviceAccount.isEmpty {
            switch PushSenderRules.inspectServiceAccount(form.serviceAccount) {
            case .success(let project):
                NoticeView(text: l10n("admin.push_fcm_ok", ["project": project]), tone: .success).accessibilityIdentifier("push.form.file_ok")
            case .failure(let problem):
                if pasting { NoticeView(text: accountProblem(problem.problem), tone: .warning) }
            }
        } else if !fcm, !form.privateKey.isEmpty {
            switch PushSenderRules.inspectP8(fileName: nil, text: form.privateKey) {
            case .success:
                if let fileName {
                    let named = PushSenderRules.keyID(fromFileName: fileName) != nil
                    NoticeView(text: l10n(named ? "admin.push_apns_ok" : "admin.push_apns_ok_no_id"), tone: .success).accessibilityIdentifier("push.form.file_ok")
                }
            case .failure(let problem):
                if pasting { NoticeView(text: keyProblem(problem.problem), tone: .warning) }
            }
        }
    }

    private func idField(_ key: String, text: Binding<String>, tag: String) -> some View {
        TextField(l10n(key), text: text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.system(size: FontSize.sizeMd, design: .monospaced))
            .environment(\.layoutDirection, .leftToRight)
            .accessibilityIdentifier("push.form.\(tag)")
    }

    /// A picked file read as text (a key is small: at most 256 KB), then checked.
    private func read(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        fileName = url.lastPathComponent
        guard let data = try? Data(contentsOf: url), data.count <= 256 * 1024, let text = String(data: data, encoding: .utf8) else {
            fileProblem = l10n("admin.push_file_unreadable")
            return
        }
        if fcm {
            switch PushSenderRules.inspectServiceAccount(text) {
            case .success: form.serviceAccount = text; fileProblem = nil
            case .failure(let problem): form.serviceAccount = ""; fileProblem = accountProblem(problem.problem)
            }
        } else {
            switch PushSenderRules.inspectP8(fileName: url.lastPathComponent, text: text) {
            case .success(let keyID):
                form.privateKey = text
                if let keyID { form.keyID = keyID }
                fileProblem = nil
            case .failure(let problem):
                form.privateKey = ""
                fileProblem = keyProblem(problem.problem)
            }
        }
    }

    private func save() async {
        busy = true
        defer { busy = false }
        let body = PushSenderRules.body(sender, form)
        let provider = sender.provider
        do {
            let result = try await app.api.call { try await DevicesAPI.devicesSetPushSender(provider: provider, pushSenderUpdate: body, apiConfiguration: $0) }
            // Emptied once sent: nothing on the phone keeps the secret after that.
            form.serviceAccount = ""
            form.privateKey = ""
            done(result)
        } catch {
            let failure = HubFailure(error)
            if let words = failure.detailMessage, !words.isEmpty {
                self.error = l10n("admin.push_refused", ["detail": words])
            } else {
                self.error = failure.describe(l10n)
            }
        }
    }

    private func forget() async {
        let provider = sender.provider
        do {
            try await app.api.call { try await DevicesAPI.devicesDeletePushSender(provider: provider, apiConfiguration: $0) }
            done(nil)
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

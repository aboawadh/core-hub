// Settings → Models, native on the phone: providers with adding by key or by signing in, the defaults
// with the fallbacks dragged into order, speech with a voice picker and a preview, the drawing model.
import AVFoundation
import CoreHubClient
import SwiftUI
import UIKit

private enum ModelsTab: Hashable { case providers, defaults, speech, pictures }

struct ModelsNativePage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var tab: ModelsTab = .providers

    var body: some View {
        VStack(spacing: 0) {
            Picker(l10n("nav.models"), selection: $tab) {
                Text(l10n("models_page.providers")).tag(ModelsTab.providers)
                Text(l10n("models_page.defaults")).tag(ModelsTab.defaults)
                Text(l10n("models_page.speech")).tag(ModelsTab.speech)
                Text(l10n("models_page.pictures")).tag(ModelsTab.pictures)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, Space.s4)
            .padding(.vertical, Space.s2)
            .accessibilityIdentifier("models.tabs")
            Text(l10n("models_page.in_profile", ["profile": app.profileName(app.currentProfile)]))
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Space.s4)
            switch tab {
            case .providers: ProvidersList()
            case .defaults: DefaultsList()
            case .speech: SpeechList()
            case .pictures: PicturesList()
            }
        }
    }
}

private struct ProvidersList: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var providers: [Provider]?
    @State private var note: (text: String, tone: NoticeView.Kind)?
    @State private var adding = false
    @State private var signingIn: SignInTarget?
    @State private var deleting: Provider?

    var body: some View {
        List {
            if let note { NoticeView(text: note.text, tone: note.tone) }
            if app.isAdmin {
                Button { adding = true } label: { LucideLabel(l10n("models_page.add"), icon: .plus, size: 16) }
                    .accessibilityIdentifier("models.add")
            }
            if let providers {
                if providers.isEmpty {
                    EmptyStateView(icon: .box, title: l10n("models_page.none"), message: l10n("models_page.none_body"))
                        .listRowBackground(Color.clear)
                }
                ForEach([ProviderKind.llm, .stt, .tts], id: \.self) { kind in
                    let group = providers.filter { $0.kind == kind }
                    if !group.isEmpty {
                        Section(l10n("models_page.kind_\(kind.rawValue)")) {
                            ForEach(group, id: \.id) { provider in row(provider) }
                        }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .refreshable { await load() }
        .task(id: app.currentProfile) { await load() }
        .sheet(isPresented: $adding) {
            NavigationStack { AddProviderView { added in
                adding = false
                Task { await load() }
                if let added, added.auth.kind == .oauth { signingIn = SignInTarget(provider: added) }
            } }
        }
        .sheet(item: $signingIn) { target in
            NavigationStack { SignInView(provider: target.provider) { signingIn = nil; Task { await load() } } }
        }
        .confirmationDialog(deleting.map { l10n("models_page.delete_confirm", ["name": $0.label]) } ?? "", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button(l10n("presets.delete"), role: .destructive) {
                if let provider = deleting { Task { await act { _ = try await ModelsAPI.modelsDeleteProvider(xHubProfile: $0, providerId: provider.id, apiConfiguration: $1) } } }
                deleting = nil
            }
        } message: {
            Text(l10n("models_page.delete_body"))
        }
    }

    private func row(_ provider: Provider) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(provider.label).font(.system(size: FontSize.sizeMd, weight: .medium))
                Spacer()
                if provider.auth.kind == .oauth && !provider.auth.signedIn {
                    StatusPill(text: l10n("models_page.sign_in_needed"), kind: .warn)
                } else if provider.auth.kind == .apiKey && provider.apiKey == nil {
                    StatusPill(text: l10n("models_page.key_needed"), kind: .warn)
                } else {
                    StatusDot(kind: provider.enabled ? .good : .neutral, label: provider.enabled ? l10n("common.on") : l10n("common.off"))
                }
            }
            Text("\(l10n(provider.scope == .profile ? "models_page.scope_profile" : "models_page.scope_all")) · \(l10n("models.count", ["count": String(provider.models.count)]))")
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
        }
        .accessibilityIdentifier("provider.\(provider.slug)")
        .contextMenu {
            if app.isAdmin {
                Button(l10n(provider.enabled ? "models_page.turn_off" : "models_page.turn_on")) {
                    Task { await act { _ = try await ModelsAPI.modelsUpdateProvider(xHubProfile: $0, providerId: provider.id, providerPatch: ProviderPatch(enabled: !provider.enabled), apiConfiguration: $1) } }
                }
                Button(l10n("mcp.test")) { Task { await test(provider) } }
                Button(l10n("models_page.refresh")) {
                    Task { await act { _ = try await ModelsAPI.modelsRefreshProvider(xHubProfile: $0, providerId: provider.id, apiConfiguration: $1) } }
                }
                if provider.auth.kind == .oauth { Button(l10n("models_page.sign_in")) { signingIn = SignInTarget(provider: provider) } }
                Button(l10n("presets.delete"), role: .destructive) { deleting = provider }
            }
        }
        .swipeActions {
            if app.isAdmin { Button(l10n("presets.delete"), role: .destructive) { deleting = provider } }
        }
    }

    private func load() async {
        let profile = app.currentProfile
        do {
            providers = try await app.api.call { try await ModelsAPI.modelsListProviders(xHubProfile: profile, apiConfiguration: $0) }.items
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
    }

    private func test(_ provider: Provider) async {
        let profile = app.currentProfile
        do {
            let result = try await app.api.call { try await ModelsAPI.modelsTestProvider(xHubProfile: profile, providerId: provider.id, apiConfiguration: $0) }
            note = result.ok ? (l10n("models_page.test_ok", ["ms": String(result.durationMs)]), .success) : (result.message ?? "—", .danger)
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
    }

    private func act(_ operation: @escaping (String, CoreHubClientAPIConfiguration) async throws -> Void) async {
        let profile = app.currentProfile
        do {
            try await app.api.call { try await operation(profile, $0) }
            note = nil
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
        await load()
    }
}

/// A provider to sign in to, for `.sheet(item:)`.
private struct SignInTarget: Identifiable {
    let provider: Provider
    var id: String { provider.id }
}

private struct AddProviderView: View {
    let done: (Provider?) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call { try await ModelsAPI.modelsListProviderPresets(xHubProfile: profile, apiConfiguration: $0) }.items
        } content: { presets, _ in
            List {
                ForEach([ProviderKind.llm, .stt, .tts], id: \.self) { kind in
                    let group = presets.filter { $0.kind == kind }
                    if !group.isEmpty {
                        Section(l10n("models_page.kind_\(kind.rawValue)")) {
                            ForEach(group, id: \.id) { preset in
                                NavigationLink {
                                    ProviderForm(preset: preset, done: done)
                                } label: {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(preset.label)
                                        if preset.signIn {
                                            Text(l10n("models_page.by_sign_in")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                                        } else if preset.local {
                                            Text(l10n("models_page.local")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                                        }
                                    }
                                }
                                .accessibilityIdentifier("preset.\(preset.id)")
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(l10n("models_page.add"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { done(nil) } } }
    }
}

private struct ProviderForm: View {
    let preset: ProviderPreset
    let done: (Provider?) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.openURL) private var openURL
    @State private var key = ""
    @State private var baseURL = ""
    @State private var scope: ProviderScope = .all
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        Form {
            if let error { NoticeView(text: error, tone: .danger) }
            if preset.signIn {
                NoticeView(text: l10n("models_page.sign_in_after"), tone: .info)
            } else {
                Section {
                    SecureField(l10n(preset.key == ._required ? "models_page.key" : "models_page.key_optional"), text: $key)
                        .font(.system(size: FontSize.sizeSm, design: .monospaced))
                        .accessibilityIdentifier("provider.key")
                    if let keys = preset.keysUrl, let url = URL(string: keys) {
                        Button(l10n("models_page.get_key")) { openURL(url) }
                    }
                }
            }
            if preset.baseUrlRequired || preset.baseUrl != nil {
                Section(l10n("models_page.base_url")) {
                    TextField(preset.baseUrlExample ?? "https://", text: $baseURL)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                        .font(.system(size: FontSize.sizeSm, design: .monospaced))
                }
            }
            Section(l10n("models_page.for_whom")) {
                Picker(l10n("models_page.for_whom"), selection: $scope) {
                    Text(l10n("models_page.scope_all")).tag(ProviderScope.all)
                    Text(l10n("models_page.scope_profile")).tag(ProviderScope.profile)
                }
                .pickerStyle(.segmented)
            }
            Section {
                Button {
                    Task { await add() }
                } label: {
                    LucideLabel(l10n("models_page.add_do"), icon: .plus, size: 16).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(Tone.accent)
                .disabled(busy || !ModelLogic.ready(preset, key: key, baseURL: baseURL))
                .accessibilityIdentifier("provider.add")
            }
        }
        .navigationTitle(preset.label)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if baseURL.isEmpty { baseURL = preset.baseUrl ?? "" } }
    }

    private func add() async {
        busy = true
        defer { busy = false }
        let profile = app.currentProfile, body = ModelLogic.create(preset, key: key, baseURL: baseURL, scope: scope)
        do {
            let provider = try await app.api.call { try await ModelsAPI.modelsCreateProvider(xHubProfile: profile, providerCreate: body, apiConfiguration: $0) }
            done(provider)
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// A sign-in by device code: the code, the provider's page, and waiting for the answer.
private struct SignInView: View {
    let provider: Provider
    let done: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.openURL) private var openURL
    @State private var signIn: ProviderSignIn?
    @State private var error: String?

    var body: some View {
        VStack(spacing: Space.s4) {
            if let error { NoticeView(text: error, tone: .danger) }
            if let signIn {
                switch signIn.status {
                case .approved:
                    NoticeView(text: l10n("models_page.signed_in"), tone: .success)
                case .pending:
                    Text(l10n("models_page.sign_in_steps")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                    if let code = signIn.userCode {
                        Text(code)
                            .font(.system(size: FontSize.sizeXl, weight: .bold, design: .monospaced))
                            .textSelection(.enabled)
                            .environment(\.layoutDirection, .leftToRight)
                            .padding(Space.s4)
                            .frame(maxWidth: .infinity)
                            .background(Tone.surface2, in: RoundedRectangle(cornerRadius: Radius.md))
                            .accessibilityIdentifier("signin.code")
                        Button(l10n("models_page.copy")) { UIPasteboard.general.string = code }.buttonStyle(.bordered)
                    }
                    if let url = URL(string: signIn.verificationUrl) {
                        Button {
                            openURL(url)
                        } label: {
                            LucideLabel(l10n("models_page.open_sign_in"), icon: .externalLink, size: 16).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent).tint(Tone.accent)
                    }
                    HStack { ProgressView(); Text(l10n("models_page.waiting_sign_in")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
                default:
                    NoticeView(text: signIn.error ?? l10n("models_page.sign_in_failed"), tone: .danger)
                }
            } else if error == nil {
                ProgressView()
            }
            Spacer()
        }
        .padding(Space.s4)
        .navigationTitle(l10n("models_page.sign_in_to", ["name": provider.label]))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button(l10n("share.done")) { done() } } }
        .task { await run() }
    }

    private func run() async {
        let profile = app.currentProfile, id = provider.id
        do {
            var current = try await app.api.call { try await ModelsAPI.modelsStartProviderSignIn(xHubProfile: profile, providerId: id, apiConfiguration: $0) }
            signIn = current
            while !ModelLogic.settled(current) && !Task.isCancelled {
                try await Task.sleep(nanoseconds: 3_000_000_000)
                let signInID = current.id
                current = try await app.api.call { try await ModelsAPI.modelsGetProviderSignIn(xHubProfile: profile, providerId: id, signInId: signInID, apiConfiguration: $0) }
                signIn = current
            }
        } catch is CancellationError {
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// Defaults: the chat model, and the fallbacks in the order they are tried — dragged by their handles.
private struct DefaultsList: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var providers: [Provider] = []
    @State private var defaults: ModelDefaults?
    @State private var order: [ModelRef] = []
    @State private var error: String?
    @State private var picking: String?

    var body: some View {
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            if let defaults {
                if !(defaults.inherited ?? []).isEmpty { NoticeView(text: l10n("models_page.inherited"), tone: .info) }
                Section(l10n("models_page.default")) {
                    Button { picking = "default" } label: {
                        HStack {
                            LucideIcon(.sparkles, size: 16).foregroundStyle(Tone.textMuted)
                            Text(ModelLogic.label(defaults._default, providers) ?? l10n("models_page.none_chosen")).foregroundStyle(Tone.text)
                        }
                    }
                    .disabled(!app.isAdmin)
                    .accessibilityIdentifier("defaults.default")
                }
                Section {
                    ForEach(Array(order.enumerated()), id: \.offset) { index, ref in
                        HStack {
                            Text("\(index + 1)").foregroundStyle(Tone.textMuted)
                            Text(ModelLogic.label(ref, providers) ?? ref.model)
                        }
                        .accessibilityIdentifier("fallback.\(index)")
                    }
                    .onMove { from, to in save(ModelLogic.move(order, from: from, to: to)) }
                    .onDelete { offsets in
                        var next = order
                        next.remove(atOffsets: offsets)
                        save(next)
                    }
                    if order.isEmpty {
                        Text(l10n("models_page.no_fallbacks")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                    }
                    if app.isAdmin {
                        Button { picking = "fallback" } label: { LucideLabel(l10n("models_page.add_fallback"), icon: .plus, size: 16) }
                            .accessibilityIdentifier("defaults.add_fallback")
                    }
                } header: {
                    Text(l10n("models_page.fallbacks"))
                } footer: {
                    Text(l10n("models_page.fallbacks_hint"))
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .environment(\.editMode, .constant(app.isAdmin ? .active : .inactive))
        .task(id: app.currentProfile) { await load() }
        .sheet(item: Binding(get: { picking.map(PickTarget.init) }, set: { picking = $0?.id })) { target in
            NavigationStack {
                ModelPicker(title: l10n(target.id == "default" ? "models_page.default" : "models_page.add_fallback"), models: ModelLogic.chatModels(providers), providers: providers) { ref in
                    picking = nil
                    if target.id == "default" {
                        Task { await setDefault(ref) }
                    } else {
                        save(ModelLogic.add(order, ref, default: defaults?._default))
                    }
                }
            }
        }
    }

    private func load() async {
        let profile = app.currentProfile
        do {
            async let p = app.api.call { try await ModelsAPI.modelsListProviders(xHubProfile: profile, apiConfiguration: $0) }
            async let d = app.api.call { try await ModelsAPI.modelsGetDefaults(xHubProfile: profile, apiConfiguration: $0) }
            let (list, loaded) = try await (p.items, d)
            providers = list
            defaults = loaded
            order = loaded.fallbacks
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func save(_ next: [ModelRef]) {
        order = next
        let profile = app.currentProfile
        Task {
            do {
                defaults = try await app.api.call { try await ModelsAPI.modelsSetDefaults(xHubProfile: profile, modelDefaultsWrite: ModelDefaultsWrite(fallbacks: next), apiConfiguration: $0) }
                error = nil
            } catch {
                self.error = HubFailure(error).describe(l10n)
                await load()
            }
        }
    }

    private func setDefault(_ ref: ModelRef) async {
        let profile = app.currentProfile
        do {
            defaults = try await app.api.call { try await ModelsAPI.modelsSetDefaults(xHubProfile: profile, modelDefaultsWrite: ModelDefaultsWrite(_default: ref), apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

private struct PickTarget: Identifiable { let id: String }

private struct ModelPicker: View {
    let title: String
    let models: [Model]
    let providers: [Provider]
    let pick: (ModelRef) -> Void
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        let shown = models.filter { query.isEmpty || $0.model.localizedCaseInsensitiveContains(query) || ($0.alias?.localizedCaseInsensitiveContains(query) ?? false) }
        List {
            ForEach(Array(Dictionary(grouping: shown, by: \.providerId).keys).sorted(), id: \.self) { providerID in
                Section(providers.first { $0.id == providerID }?.label ?? providerID) {
                    ForEach(shown.filter { $0.providerId == providerID }, id: \.key) { model in
                        Button(model.alias ?? model.model) { pick(ModelRef(providerId: model.providerId, model: model.model)) }
                            .accessibilityIdentifier("model.\(model.key)")
                    }
                }
            }
        }
        .searchable(text: $query)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { dismiss() } } }
    }
}

/// Speech: which provider listens, which speaks, and its voice.
private struct SpeechList: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var speech: SpeechSettings?
    @State private var error: String?

    var body: some View {
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            if let speech {
                side("models_page.stt", speech.stt, isSTT: true)
                side("models_page.tts", speech.tts, isSTT: false)
                if speech.stt.providers.isEmpty && speech.tts.providers.isEmpty {
                    NoticeView(text: l10n("models_page.speech_none"), tone: .info)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .task(id: app.currentProfile) { await load() }
        .refreshable { await load() }
    }

    @ViewBuilder
    private func side(_ title: String, _ s: SpeechSide, isSTT: Bool) -> some View {
        Section(l10n(title)) {
            if app.isAdmin && s.providers.count > 1 {
                Picker(l10n("models_page.provider"), selection: Binding(get: { s.activeProviderId ?? "" }, set: { id in
                    Task { await use(isSTT ? SpeechSettingsPatch(sttProviderId: id) : SpeechSettingsPatch(ttsProviderId: id)) }
                })) {
                    ForEach(s.providers, id: \.id) { Text($0.label).tag($0.id) }
                }
            } else {
                FactRow(label: l10n("models_page.provider"), value: s.providers.first { $0.id == s.activeProviderId }?.label ?? l10n("models_page.none_chosen"))
            }
            HStack {
                StatusDot(kind: s.ready ? .good : .warn, label: l10n(s.ready ? "models_page.ready" : "models_page.not_ready"))
                Text(s.ready ? l10n("models_page.ready") : (s.reason ?? l10n("models_page.not_ready"))).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
            if !isSTT, let active = s.providers.first(where: { $0.id == s.activeProviderId }) {
                NavigationLink {
                    VoicePickerView(provider: active) { Task { await load() } }
                } label: {
                    HStack {
                        LucideLabel(l10n("models_page.voice"), icon: .volume2, size: 16)
                        Spacer()
                        Text(active.settings.voice ?? "—").foregroundStyle(Tone.textMuted)
                    }
                }
                .disabled(!app.isAdmin)
                .accessibilityIdentifier("speech.voice")
            }
        }
    }

    private func load() async {
        let profile = app.currentProfile
        do {
            speech = try await app.api.call { try await ModelsAPI.modelsGetSpeech(xHubProfile: profile, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func use(_ patch: SpeechSettingsPatch) async {
        let profile = app.currentProfile
        do {
            speech = try await app.api.call { try await ModelsAPI.modelsUpdateSpeech(xHubProfile: profile, speechSettingsPatch: patch, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// Every voice the provider offers, the person's languages first, searchable, heard before it is kept.
private struct VoicePickerView: View {
    let provider: SpeechProvider
    let picked: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var voices: [CoreHubClient.Voice]?
    @State private var query = ""
    @State private var error: String?
    @State private var player: AVAudioPlayer?
    @State private var playing: String?

    var body: some View {
        let preferred = [app.language.rawValue] + Locale.preferredLanguages.map { String($0.prefix(2)) } + ["en"]
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            if let voices {
                let shown = ModelLogic.voices(voices, preferred: preferred, query: query)
                if shown.isEmpty { Text(l10n("models_page.voice_none")).foregroundStyle(Tone.textMuted) }
                ForEach(shown, id: \.id) { voice in
                    HStack {
                        Button {
                            Task { await choose(voice) }
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text(voice.name).foregroundStyle(Tone.text)
                                    if voice.id == provider.settings.voice { LucideIcon(.check, size: 14).foregroundStyle(Tone.accent) }
                                }
                                let line = [voice.language, voice.description].compactMap { $0 }.joined(separator: " · ")
                                if !line.isEmpty { Text(line).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2) }
                            }
                        }
                        Spacer()
                        Button {
                            Task { await preview(voice) }
                        } label: {
                            LucideIcon(playing == voice.id ? .circleStop : .play, size: 18)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(l10n("models_page.voice_preview"))
                        .accessibilityIdentifier("voice.\(voice.id).play")
                    }
                    .accessibilityIdentifier("voice.\(voice.id)")
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .searchable(text: $query, prompt: l10n("models_page.voice_search"))
        .navigationTitle(l10n("models_page.voice_of", ["name": provider.label]))
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .onDisappear { player?.stop() }
    }

    private func load() async {
        let profile = app.currentProfile, id = provider.id, model = provider.settings.model
        do {
            voices = try await app.api.call { try await ModelsAPI.modelsListVoices(xHubProfile: profile, providerId: id, model: model, apiConfiguration: $0) }.items
        } catch {
            self.error = HubFailure(error).describe(l10n)
            voices = []
        }
    }

    private func preview(_ voice: CoreHubClient.Voice) async {
        let profile = app.currentProfile
        let request = SpeechRequest(text: ModelLogic.sample(voice.language, fallback: l10n("models_page.voice_sample")), language: voice.language, voice: voice.id, providerId: provider.id, format: .mp3)
        playing = voice.id
        do {
            let file = try await app.api.call { try await ModelsAPI.modelsSynthesize(xHubProfile: profile, speechRequest: request, apiConfiguration: $0) }
            let audio = try AVAudioPlayer(contentsOf: file)
            player = audio
            audio.play()
            try? await Task.sleep(nanoseconds: UInt64(max(audio.duration, 0.5) * 1_000_000_000))
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        if playing == voice.id { playing = nil }
    }

    private func choose(_ voice: CoreHubClient.Voice) async {
        let profile = app.currentProfile
        let patch = SpeechSettingsPatch(providers: [SpeechSettingsPatchProvidersInner(id: provider.id, settings: ["voice": .string(voice.id)])])
        do {
            _ = try await app.api.call { try await ModelsAPI.modelsUpdateSpeech(xHubProfile: profile, speechSettingsPatch: patch, apiConfiguration: $0) }
            picked()
            dismiss()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// Pictures: the model the agents draw with.
private struct PicturesList: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var providers: [Provider] = []
    @State private var image: ModelRef?
    @State private var loaded = false
    @State private var picking = false
    @State private var error: String?

    var body: some View {
        let models = ModelLogic.imageModels(providers)
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            Section(l10n("models_page.image_model")) {
                Button { picking = true } label: {
                    HStack {
                        LucideIcon(.image, size: 16).foregroundStyle(Tone.textMuted)
                        Text(ModelLogic.label(image, providers) ?? l10n("models_page.none_chosen")).foregroundStyle(Tone.text)
                    }
                }
                .disabled(!app.isAdmin || models.isEmpty)
                .accessibilityIdentifier("images.model")
            }
            if loaded {
                Text(l10n(models.isEmpty ? "models_page.images_none" : "models_page.images_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
        }
        .task(id: app.currentProfile) { await load() }
        .sheet(isPresented: $picking) {
            NavigationStack {
                ModelPicker(title: l10n("models_page.image_model"), models: models, providers: providers) { ref in
                    picking = false
                    Task { await set(ref) }
                }
            }
        }
    }

    private func load() async {
        let profile = app.currentProfile
        do {
            async let p = app.api.call { try await ModelsAPI.modelsListProviders(xHubProfile: profile, apiConfiguration: $0) }
            async let d = app.api.call { try await ModelsAPI.modelsGetDefaults(xHubProfile: profile, apiConfiguration: $0) }
            let (list, defaults) = try await (p.items, d)
            providers = list
            image = defaults.image
            loaded = true
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func set(_ ref: ModelRef) async {
        let profile = app.currentProfile
        do {
            image = try await app.api.call { try await ModelsAPI.modelsSetDefaults(xHubProfile: profile, modelDefaultsWrite: ModelDefaultsWrite(image: ref), apiConfiguration: $0) }.image
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

extension PhonePage {
    static let models = PhonePage(.models) { _ in ModelsNativePage() }
}

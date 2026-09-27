// Models → Speech: which provider listens and which speaks, and for each its model (from the
// provider's own list, or typed), its language (detected, or chosen) and — for speaking — its voice,
// searched in every language and heard before it is kept.
import AVFoundation
import CoreHubClient
import SwiftUI

struct ModelsSpeechTab: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var speech: SpeechSettings?
    @State private var error: String?

    var body: some View {
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            if let speech {
                side("models_page.stt", speech.stt, kind: .stt)
                side("models_page.tts", speech.tts, kind: .tts)
                if speech.stt.providers.isEmpty && speech.tts.providers.isEmpty {
                    NoticeView(text: l10n("models_page.speech_none"), tone: .info)
                }
            } else if error == nil {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .task(id: app.currentProfile) { await load() }
        .refreshable { await load() }
    }

    @ViewBuilder
    private func side(_ title: String, _ s: SpeechSide, kind: ModelKind) -> some View {
        Section {
            if app.isAdmin && s.providers.count > 1 {
                Picker(l10n("models_page.provider"), selection: Binding(get: { s.activeProviderId ?? "" }, set: { id in
                    Task { await use(kind == .stt ? SpeechSettingsPatch(sttProviderId: id) : SpeechSettingsPatch(ttsProviderId: id)) }
                })) {
                    ForEach(s.providers, id: \.id) { Text($0.label).tag($0.id) }
                }
                .accessibilityIdentifier("speech.\(kind.rawValue).provider")
            } else {
                FactRow(label: l10n("models_page.provider"), value: s.providers.first { $0.id == s.activeProviderId }?.label ?? l10n("models_page.none_chosen"))
            }
            HStack {
                StatusDot(kind: s.ready ? .good : .warn, label: l10n(s.ready ? "models_page.ready" : "models_page.not_ready"))
                Text(s.ready ? l10n("models_page.ready") : (s.reason ?? l10n("models_page.not_ready"))).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
            if let active = s.providers.first(where: { $0.id == s.activeProviderId }) {
                NavigationLink {
                    SpeechModelPicker(provider: active, kind: kind) { Task { await load() } }
                } label: {
                    settingRow(l10n("models.speech_model"), icon: .cpu, value: active.settings.model ?? l10n("models.provider_default"))
                }
                .disabled(!app.isAdmin)
                .accessibilityIdentifier("speech.\(kind.rawValue).model")
                NavigationLink {
                    SpeechLanguagePicker(provider: active, kind: kind) { Task { await load() } }
                } label: {
                    settingRow(l10n(kind == .stt ? "models.language_stt" : "models.language_tts"), icon: .globe, value: languageName(active.settings.language))
                }
                .disabled(!app.isAdmin)
                .accessibilityIdentifier("speech.\(kind.rawValue).language")
                if kind == .tts {
                    NavigationLink {
                        VoicePickerView(provider: active) { Task { await load() } }
                    } label: {
                        settingRow(l10n("models_page.voice"), icon: .volume2, value: active.settings.voice ?? "—")
                    }
                    .disabled(!app.isAdmin)
                    .accessibilityIdentifier("speech.voice")
                }
            }
        } header: {
            Text(l10n(title))
        } footer: {
            Text(l10n(kind == .stt ? "models.hint_stt" : "models.hint_tts"))
        }
    }

    private func settingRow(_ title: String, icon: Lucide, value: String) -> some View {
        HStack {
            LucideLabel(title, icon: icon, size: 16)
            Spacer()
            Text(value).foregroundStyle(Tone.textMuted).lineLimit(1)
        }
    }

    private func languageName(_ code: String?) -> String {
        guard let code, !code.isEmpty else { return l10n("models.language_auto") }
        return app.language.locale.localizedString(forIdentifier: code) ?? code
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

/// Keeps one speech setting (`model`, `language`, `voice`) of one provider; an empty value is the
/// provider's default ("detect" for a language).
private func keepSpeechSetting(_ app: AppModel, provider: String, key: String, value: String) async throws {
    let profile = app.currentProfile
    let patch = SpeechSettingsPatch(providers: [SpeechSettingsPatchProvidersInner(id: provider, settings: [key: ModelLogic.speechSetting(value)])])
    _ = try await app.api.call { try await ModelsAPI.modelsUpdateSpeech(xHubProfile: profile, speechSettingsPatch: patch, apiConfiguration: $0) }
}

/// The provider's own models of the tab's kind, searchable; any id can be typed, and "the provider's
/// default" clears the choice.
private struct SpeechModelPicker: View {
    let provider: SpeechProvider
    let kind: ModelKind
    let picked: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var models: [Model]?
    @State private var query = ""
    @State private var error: String?

    var body: some View {
        let q = query.trimmingCharacters(in: .whitespaces)
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            Button { Task { await choose("") } } label: {
                HStack {
                    LucideLabel(l10n("models.provider_default"), icon: .rotateCcw, size: 16)
                    if provider.settings.model == nil { Spacer(); LucideIcon(.check, size: 14).foregroundStyle(Tone.accent) }
                }
            }
            if !q.isEmpty && !(models ?? []).contains(where: { $0.model == q }) {
                Button { Task { await choose(q) } } label: { LucideLabel(l10n("models.use_typed", ["value": q]), icon: .pencil, size: 16) }
                    .accessibilityIdentifier("speech.model.typed")
            }
            if let models {
                let shown = models.filter { q.isEmpty || $0.model.localizedCaseInsensitiveContains(q) || ($0.alias?.localizedCaseInsensitiveContains(q) ?? false) }
                if models.isEmpty { Text(l10n("models.speech_models_none")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted) }
                ForEach(shown, id: \.key) { model in
                    Button { Task { await choose(model.model) } } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(model.alias ?? model.model).foregroundStyle(Tone.text)
                                if model.alias != nil {
                                    Text(model.model).font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textMuted)
                                }
                            }
                            Spacer()
                            if model.model == provider.settings.model { LucideIcon(.check, size: 14).foregroundStyle(Tone.accent) }
                        }
                    }
                    .accessibilityIdentifier("speech.model.\(model.model)")
                }
            } else if error == nil {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .searchable(text: $query, prompt: l10n("models.model_search"))
        .navigationTitle(l10n("models.speech_model_of", ["name": provider.label]))
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        let profile = app.currentProfile, id = provider.id, wanted = self.kind
        do {
            models = try await app.api.call { try await ModelsAPI.modelsListCatalogue(xHubProfile: profile, kind: wanted, providerId: id, limit: 200, apiConfiguration: $0) }.items
        } catch {
            self.error = HubFailure(error).describe(l10n)
            models = []
        }
    }

    private func choose(_ model: String) async {
        do {
            try await keepSpeechSetting(app, provider: provider.id, key: "model", value: model)
            picked()
            dismiss()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// "Detect automatically", the popular languages, then any code typed (ar-EG); names in the app's language.
private struct SpeechLanguagePicker: View {
    let provider: SpeechProvider
    let kind: ModelKind
    let picked: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var error: String?

    var body: some View {
        let q = query.trimmingCharacters(in: .whitespaces)
        let locale = app.language.locale
        let codes = ModelLogic.popularLanguages.filter { code in
            q.isEmpty || code.localizedCaseInsensitiveContains(q) || (locale.localizedString(forIdentifier: code)?.localizedCaseInsensitiveContains(q) ?? false)
        }
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            row(l10n("models.language_auto"), code: "")
            if !q.isEmpty && !ModelLogic.popularLanguages.contains(q) && q.range(of: "^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$", options: .regularExpression) != nil {
                Button { Task { await choose(q) } } label: { LucideLabel(l10n("models.use_typed", ["value": q]), icon: .pencil, size: 16) }
                    .accessibilityIdentifier("speech.language.typed")
            }
            Section(l10n("models.lang_popular")) {
                ForEach(codes, id: \.self) { code in
                    row(locale.localizedString(forIdentifier: code) ?? code, code: code)
                }
            }
        }
        .searchable(text: $query, prompt: l10n("models.language_search"))
        .navigationTitle(l10n(kind == .stt ? "models.language_stt" : "models.language_tts"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func row(_ name: String, code: String) -> some View {
        Button { Task { await choose(code) } } label: {
            HStack {
                Text(name).foregroundStyle(Tone.text)
                if !code.isEmpty { Text(code).font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textMuted) }
                Spacer()
                if (provider.settings.language ?? "") == code { LucideIcon(.check, size: 14).foregroundStyle(Tone.accent) }
            }
        }
        .accessibilityIdentifier("speech.language.\(code.isEmpty ? "auto" : code)")
    }

    private func choose(_ code: String) async {
        do {
            try await keepSpeechSetting(app, provider: provider.id, key: "language", value: code)
            picked()
            dismiss()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

/// Every voice the provider offers, the person's languages first, searchable, heard before it is kept;
/// a voice id can be typed where the provider lists none.
private struct VoicePickerView: View {
    let provider: SpeechProvider
    let picked: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var voices: [CoreHubClient.Voice]?
    @State private var source: VoiceListSource?
    @State private var query = ""
    @State private var error: String?
    @State private var player: AVAudioPlayer?
    @State private var playing: String?

    var body: some View {
        let preferred = [app.language.rawValue] + Locale.preferredLanguages.map { String($0.prefix(2)) } + ["en"]
        let q = query.trimmingCharacters(in: .whitespaces)
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            if source == .documented { NoticeView(text: l10n("models.voices_documented"), tone: .info) }
            if source == ._none { NoticeView(text: l10n("models.voices_none"), tone: .info) }
            if !q.isEmpty && !(voices ?? []).contains(where: { $0.id == q }) {
                Button { Task { await choose(q) } } label: { LucideLabel(l10n("models.use_typed", ["value": q]), icon: .pencil, size: 16) }
                    .accessibilityIdentifier("voice.typed")
            }
            if let voices {
                let shown = ModelLogic.voices(voices, preferred: preferred, query: query)
                if shown.isEmpty && source != ._none { Text(l10n("models_page.voice_none")).foregroundStyle(Tone.textMuted) }
                ForEach(shown, id: \.id) { voice in
                    HStack {
                        Button {
                            Task { await choose(voice.id) }
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
            } else if error == nil {
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
            let answer = try await app.api.call { try await ModelsAPI.modelsListVoices(xHubProfile: profile, providerId: id, model: model, apiConfiguration: $0) }
            voices = answer.items
            source = answer.source
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

    private func choose(_ voice: String) async {
        do {
            try await keepSpeechSetting(app, provider: provider.id, key: "voice", value: voice)
            picked()
            dismiss()
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

// Settings → Models, native on the phone, as the web's Models page: providers (added by key, by
// signing in, or as a custom endpoint; edited, tested, refreshed, removed; display names per model),
// the defaults (chat model, fallbacks dragged into order, the auxiliary roles), speech (provider,
// model, language, voice) and the image model. Each tab is a file of its own next to this one.
import CoreHubClient
import SwiftUI

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
            case .providers: ModelsProvidersTab()
            case .defaults: ModelsDefaultsTab()
            case .speech: ModelsSpeechTab()
            case .pictures: ModelsImagesTab()
            }
        }
    }
}

/// A model picker: the models given, by provider, searchable; an optional first row clears the choice.
struct ModelsPicker: View {
    let title: String
    let models: [Model]
    let providers: [Provider]
    var clear: String?
    var name: (Model) -> String = { $0.alias ?? $0.model }
    let pick: (ModelRef?) -> Void
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        let q = query.trimmingCharacters(in: .whitespaces)
        let shown = models.filter { q.isEmpty || $0.model.localizedCaseInsensitiveContains(q) || name($0).localizedCaseInsensitiveContains(q) }
        let groups = shown.reduce(into: [String]()) { order, model in if !order.contains(model.providerId) { order.append(model.providerId) } }
        List {
            if let clear {
                Button { pick(nil) } label: { LucideLabel(clear, icon: .rotateCcw, size: 16) }
                    .accessibilityIdentifier("model.clear")
            }
            if shown.isEmpty {
                Text(l10n("models.picker_none")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
            }
            ForEach(groups, id: \.self) { providerID in
                Section(providers.first { $0.id == providerID }?.label ?? providerID) {
                    ForEach(shown.filter { $0.providerId == providerID }, id: \.key) { model in
                        Button {
                            pick(ModelRef(providerId: model.providerId, model: model.model))
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(name(model)).foregroundStyle(Tone.text)
                                if name(model) != model.model {
                                    Text(model.model)
                                        .font(.system(size: FontSize.sizeXs, design: .monospaced))
                                        .foregroundStyle(Tone.textMuted)
                                        .environment(\.layoutDirection, .leftToRight)
                                }
                            }
                        }
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

/// A picked model's target, for `.sheet(item:)`.
struct ModelsPickTarget: Identifiable {
    let id: String
}

extension PhonePage {
    static let models = PhonePage(.models) { _ in ModelsNativePage() }
}

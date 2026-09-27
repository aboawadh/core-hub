// Models → Images: the model this profile draws and edits images with — one that draws, on a chat
// provider the hub can draw with (§72, §84), the image-only ones first (§87, §110). A profile that
// chose none uses the default profile's, and says so; choosing again can go back to it.
import CoreHubClient
import SwiftUI

struct ModelsImagesTab: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var providers: [Provider] = []
    @State private var defaults: ModelDefaults?
    @State private var picking = false
    @State private var error: String?

    var body: some View {
        let models = ModelLogic.imageModels(providers)
        let image = defaults?.image
        let inherited = (defaults?.inherited ?? []).contains("image")
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            Section {
                Button { picking = true } label: {
                    HStack {
                        LucideIcon(.image, size: 16).foregroundStyle(Tone.textMuted)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(name(image) ?? l10n("models_page.none_chosen")).foregroundStyle(Tone.text)
                            if inherited && image != nil {
                                Text(l10n("models.inherited_short")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            }
                        }
                    }
                }
                .disabled(!app.isAdmin || models.isEmpty)
                .accessibilityIdentifier("images.model")
            } header: {
                Text(l10n("models_page.image_model"))
            } footer: {
                Text(l10n("models.images_hint"))
            }
            if defaults != nil && models.isEmpty && image == nil {
                NoticeView(text: l10n("models.images_none_hint"), tone: .info)
            } else if defaults != nil {
                Text(l10n("models.images_used_by")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            }
        }
        .refreshable { await load() }
        .task(id: app.currentProfile) { await load() }
        .sheet(isPresented: $picking) {
            NavigationStack {
                ModelsPicker(
                    title: l10n("models_page.image_model"), models: models, providers: providers,
                    clear: image != nil && !inherited ? l10n(app.currentProfile == "default" ? "models.clear_choice" : "models.use_inherited") : nil,
                    name: { model in label(model) }
                ) { ref in
                    picking = false
                    Task { await set(ref) }
                }
            }
        }
    }

    /// The subscription's image model is named by how it draws (§84).
    private func label(_ model: Model) -> String {
        if let via = ModelLogic.viaSubscription(model, providers) {
            return l10n("models.images_via_subscription", ["provider": via, "model": model.model])
        }
        return model.alias ?? model.model
    }

    private func name(_ ref: ModelRef?) -> String? {
        guard let ref else { return nil }
        if let model = providers.first(where: { $0.id == ref.providerId })?.models.first(where: { $0.model == ref.model }),
           ModelLogic.viaSubscription(model, providers) != nil {
            return label(model)
        }
        return ModelLogic.label(ref, providers)
    }

    private func load() async {
        let profile = app.currentProfile
        do {
            async let p = app.api.call { try await ModelsAPI.modelsListProviders(xHubProfile: profile, apiConfiguration: $0) }
            async let d = app.api.call { try await ModelsAPI.modelsGetDefaults(xHubProfile: profile, apiConfiguration: $0) }
            let (list, loaded) = try await (p.items, d)
            providers = list
            defaults = loaded
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func set(_ ref: ModelRef?) async {
        let profile = app.currentProfile
        let body = ref.map { ModelDefaultsWrite(image: $0) } ?? ModelDefaultsWrite(sendNull: [.image])
        do {
            defaults = try await app.api.call { try await ModelsAPI.modelsSetDefaults(xHubProfile: profile, modelDefaultsWrite: body, apiConfiguration: $0) }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }
}

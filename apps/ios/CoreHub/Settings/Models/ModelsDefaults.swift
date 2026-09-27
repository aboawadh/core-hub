// Models → Defaults: the chat model, the fallbacks in the order they are tried (dragged by their
// handles), and the auxiliary roles the hub names (titles, summaries…). A role this profile left
// alone shows the default profile's choice and says so (§37).
import CoreHubClient
import SwiftUI

struct ModelsDefaultsTab: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var providers: [Provider] = []
    @State private var defaults: ModelDefaults?
    @State private var order: [ModelRef] = []
    @State private var error: String?
    @State private var picking: ModelsPickTarget?

    var body: some View {
        List {
            if let error { NoticeView(text: error, tone: .danger) }
            if let defaults {
                let inherited = Set(defaults.inherited ?? [])
                Section {
                    choice(defaults._default, icon: .sparkles, inherited: inherited.contains("default"), tag: "default")
                } header: {
                    Text(l10n("models_page.default"))
                } footer: {
                    Text(l10n("models.defaults_hint"))
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
                        Text(l10n(defaults._default == nil ? "models.fallbacks_need_default" : "models_page.no_fallbacks"))
                            .font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                    }
                    if app.isAdmin && defaults._default != nil {
                        Button { picking = ModelsPickTarget(id: "fallback") } label: { LucideLabel(l10n("models_page.add_fallback"), icon: .plus, size: 16) }
                            .accessibilityIdentifier("defaults.add_fallback")
                    }
                } header: {
                    Text(l10n("models_page.fallbacks"))
                } footer: {
                    Text(l10n("models_page.fallbacks_hint"))
                }
                if !defaults.auxiliary.tasks.isEmpty {
                    Section {
                        ForEach(defaults.auxiliary.tasks, id: \.key) { task in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(app.language == .ar ? task.label.ar : task.label.en).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                                choice(defaults.auxiliary.assignments[task.key], icon: .wrench, inherited: inherited.contains(task.key), tag: "aux:\(task.key)")
                            }
                        }
                    } header: {
                        Text(l10n("models.auxiliary"))
                    } footer: {
                        Text(l10n("models.auxiliary_hint"))
                    }
                }
            } else if error == nil {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .environment(\.editMode, .constant(app.isAdmin ? .active : .inactive))
        .refreshable { await load() }
        .task(id: app.currentProfile) { await load() }
        .sheet(item: $picking) { target in
            NavigationStack {
                ModelsPicker(
                    title: title(target.id),
                    models: ModelLogic.chatModels(providers),
                    providers: providers,
                    clear: target.id == "default" && defaults?._default != nil && !(defaults?.inherited ?? []).contains("default")
                        ? l10n(app.currentProfile == "default" ? "models.clear_choice" : "models.use_inherited") : nil
                ) { ref in
                    picking = nil
                    Task { await choose(target.id, ref) }
                }
            }
        }
    }

    private func title(_ target: String) -> String {
        if target == "default" { return l10n("models_page.default") }
        if target == "fallback" { return l10n("models_page.add_fallback") }
        let key = String(target.dropFirst(4))
        let task = defaults?.auxiliary.tasks.first { $0.key == key }
        return task.map { app.language == .ar ? $0.label.ar : $0.label.en } ?? key
    }

    private func choice(_ ref: ModelRef?, icon: Lucide, inherited: Bool, tag: String) -> some View {
        Button { picking = ModelsPickTarget(id: tag) } label: {
            HStack {
                LucideIcon(icon, size: 16).foregroundStyle(Tone.textMuted)
                VStack(alignment: .leading, spacing: 2) {
                    Text(ModelLogic.label(ref, providers) ?? l10n("models_page.none_chosen")).foregroundStyle(Tone.text)
                    if inherited {
                        Text(l10n("models.inherited_short")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    }
                }
            }
        }
        .disabled(!app.isAdmin)
        .accessibilityIdentifier("defaults.\(tag)")
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
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
    }

    private func write(_ body: ModelDefaultsWrite) async {
        let profile = app.currentProfile
        do {
            let saved = try await app.api.call { try await ModelsAPI.modelsSetDefaults(xHubProfile: profile, modelDefaultsWrite: body, apiConfiguration: $0) }
            defaults = saved
            order = saved.fallbacks
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
            await load()
        }
    }

    private func save(_ next: [ModelRef]) {
        order = next
        let body = ModelLogic.fallbackWrite(next, defaults: defaults)
        Task { await write(body) }
    }

    private func choose(_ target: String, _ ref: ModelRef?) async {
        switch target {
        case "default":
            await write(ref.map { ModelDefaultsWrite(_default: $0) } ?? ModelDefaultsWrite(sendNull: [._default]))
        case "fallback":
            if let ref { save(ModelLogic.add(order, ref, default: defaults?._default)) }
        default:
            guard let ref else { return }
            let key = String(target.dropFirst(4))
            await write(ModelDefaultsWrite(assignments: [key: .typeModelRef(ref)]))
        }
    }
}

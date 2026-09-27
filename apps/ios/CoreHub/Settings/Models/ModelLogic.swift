// The Models page's rules, unit-tested in ModelsAdminTests.
import AVFoundation
import CoreHubClient
import SwiftUI
import UIKit

enum ModelLogic {
    static func same(_ a: ModelRef, _ b: ModelRef) -> Bool { a.providerId == b.providerId && a.model == b.model }

    static func move(_ list: [ModelRef], from: IndexSet, to: Int) -> [ModelRef] {
        var out = list
        out.move(fromOffsets: from, toOffset: to)
        return out
    }

    static func add(_ list: [ModelRef], _ ref: ModelRef, default current: ModelRef?) -> [ModelRef] {
        if list.contains(where: { same($0, ref) }) { return list }
        if let current, same(current, ref) { return list }
        return list + [ref]
    }

    static func ready(_ preset: ProviderPreset, key: String, baseURL: String, scope: ProviderScope = .all) -> Bool {
        (preset.signIn || keyOptional(preset, scope: scope) || !key.trimmingCharacters(in: .whitespaces).isEmpty)
            && (!preset.baseUrlRequired || !baseURL.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    /// The key can be left out: the preset does not demand one, or its account already has one saved
    /// in the scope it is added to (a Groq chat key lends itself to Groq's speech rows, §94).
    static func keyOptional(_ preset: ProviderPreset, scope: ProviderScope) -> Bool {
        preset.key == ._optional || keyOnFile(preset, scope: scope)
    }

    static func keyOnFile(_ preset: ProviderPreset, scope: ProviderScope) -> Bool {
        (preset.keyOnFile ?? []).contains { $0.rawValue == scope.rawValue }
    }

    /// The presets that can still be added in `scope`: a repeatable one always, another only once per
    /// scope (once shared and once as this profile's own is allowed; a second is `409`).
    static func offered(_ presets: [ProviderPreset], added: [Provider], scope: ProviderScope) -> [ProviderPreset] {
        let taken = Set(added.filter { $0.scope == scope }.map(\.slug))
        return presets.filter { $0.repeatable || !taken.contains($0.id) }
    }

    /// A bare OpenAI-compatible endpoint (the web's "Custom"): a name and an address are needed, a key
    /// only when typed; nil until both are there.
    static func custom(label: String, kind: ProviderKind, baseURL: String, key: String, scope: ProviderScope) -> ProviderCreate? {
        let l = label.trimmingCharacters(in: .whitespaces), u = baseURL.trimmingCharacters(in: .whitespaces)
        let k = key.trimmingCharacters(in: .whitespaces)
        guard !l.isEmpty, !u.isEmpty else { return nil }
        return ProviderCreate(label: l, kind: kind, baseUrl: u, apiKey: k.isEmpty ? nil : k, apiMode: .chatCompletions, scope: scope)
    }

    /// What editing a provider sends: its name and address as they are now (an empty address clears
    /// it), the key only when a new one was typed — an empty field means "leave it", never "remove".
    static func edit(_ provider: Provider, label: String, baseURL: String, key: String, enabled: Bool) -> ProviderPatch {
        let l = label.trimmingCharacters(in: .whitespaces), u = baseURL.trimmingCharacters(in: .whitespaces)
        let k = key.trimmingCharacters(in: .whitespaces)
        return ProviderPatch(
            label: l.isEmpty || l == provider.label ? nil : l,
            enabled: enabled == provider.enabled ? nil : enabled,
            apiKey: k.isEmpty ? nil : k,
            baseUrl: u.isEmpty ? nil : (u == provider.baseUrl ? nil : u),
            sendNull: u.isEmpty && provider.baseUrl != nil ? [.baseUrl] : []
        )
    }

    /// A display name: typed text sets it, an empty field goes back to the provider's own id.
    static func alias(_ text: String) -> ModelPatch {
        let t = text.trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? ModelPatch(sendNull: [.alias]) : ModelPatch(alias: t)
    }

    /// A model id as the `{model}` of `models.putModel`: encoded once here, as the web does, so an id
    /// with `/` (OpenRouter's `anthropic/claude-…`) stays one path segment after the client encodes it
    /// again and the hub decodes it twice.
    static func pathModel(_ model: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return model.addingPercentEncoding(withAllowedCharacters: allowed) ?? model
    }

    /// A fallback chain saved with an inherited chat model saves that model too, so the chain has a
    /// model of this profile's own to fall back from (§37, §54) — as the web does.
    static func fallbackWrite(_ fallbacks: [ModelRef], defaults: ModelDefaults?) -> ModelDefaultsWrite {
        if let defaults, (defaults.inherited ?? []).contains("default"), let chat = defaults._default {
            return ModelDefaultsWrite(_default: chat, fallbacks: fallbacks)
        }
        return ModelDefaultsWrite(fallbacks: fallbacks)
    }

    /// Where a provider's model list came from (§83), and whether it is being fetched or failed.
    enum CatalogueNote: Equatable {
        case refreshing
        case failed(String)
        case fallback(String?)
        case fromAccount
    }

    static func catalogueNotes(_ provider: Provider) -> [CatalogueNote] {
        var out: [CatalogueNote] = []
        if provider.catalogue.status == .loading { out.append(.refreshing) }
        if let error = provider.catalogue.error, !error.isEmpty { out.append(.failed(error)) }
        if provider.catalogue.source == .fallback {
            out.append(.fallback(provider.catalogue.fallbackReason))
        } else if provider.auth.kind == .oauth && provider.catalogue.source == .provider {
            out.append(.fromAccount)
        }
        return out
    }

    /// The same address with the container's alias for the host, when a loopback address would reach
    /// the hub's own container (the hub says it runs in one); nil when there is nothing to warn about.
    static func loopbackSuggestion(_ url: String, host: ProviderHost?) -> String? {
        guard let host, host.containerized, var parts = URLComponents(string: url.trimmingCharacters(in: .whitespaces)),
              let name = parts.host?.lowercased(), ["localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]"].contains(name)
        else { return nil }
        parts.host = host.loopbackAlias
        var out = parts.string ?? url
        while out.hasSuffix("/") { out.removeLast() }
        return out
    }

    /// Speech settings as the hub keeps them: an empty field is `null` (the provider's default, or
    /// "detect the language").
    static func speechSetting(_ text: String) -> JSONValue {
        let t = text.trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? .null : .string(t)
    }

    /// The languages offered first for speech, as the web's list; any other code can be typed.
    static let popularLanguages = ["ar", "en", "es", "fr", "de", "zh", "hi", "pt", "ru", "ja", "ko", "it", "tr", "ur", "fa", "id"]

    static func create(_ preset: ProviderPreset, key: String, baseURL: String, scope: ProviderScope) -> ProviderCreate {
        let k = key.trimmingCharacters(in: .whitespaces), u = baseURL.trimmingCharacters(in: .whitespaces)
        return ProviderCreate(
            preset: preset.id, label: preset.label, kind: preset.kind,
            baseUrl: u.isEmpty ? nil : u, apiKey: k.isEmpty || preset.signIn ? nil : k, scope: scope
        )
    }

    static func settled(_ signIn: ProviderSignIn) -> Bool { signIn.status != .pending }

    /// Voices in the person's languages first, then by language and name; every language stays.
    static func voices(_ all: [CoreHubClient.Voice], preferred: [String], query: String) -> [CoreHubClient.Voice] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let wanted = preferred.map { $0.lowercased().components(separatedBy: "-")[0] }
        func rank(_ v: CoreHubClient.Voice) -> Int {
            guard let lang = v.language?.lowercased().components(separatedBy: "-").first else { return wanted.count + 1 }
            return wanted.firstIndex(of: lang) ?? wanted.count
        }
        return all.filter { v in
            q.isEmpty || v.name.lowercased().contains(q) || (v.language?.lowercased().contains(q) ?? false) || (v.description?.lowercased().contains(q) ?? false)
        }.sorted { a, b in
            let ra = rank(a), rb = rank(b)
            if ra != rb { return ra < rb }
            let la = a.language ?? "~", lb = b.language ?? "~"
            if la != lb { return la < lb }
            return a.name.lowercased() < b.name.lowercased()
        }
    }

    static func sample(_ language: String?, fallback: String) -> String {
        switch language?.lowercased().components(separatedBy: "-").first {
        case "ar": return "مرحبًا، هذا صوتي في كور هب."
        case "en": return "Hello, this is how I sound in Core Hub."
        case "fr": return "Bonjour, voici ma voix dans Core Hub."
        case "es": return "Hola, así sueno en Core Hub."
        case "de": return "Hallo, so klinge ich in Core Hub."
        default: return fallback
        }
    }

    /// Models a chat can be held with: visible chat models of the enabled chat providers, never one
    /// that only draws (§87).
    static func chatModels(_ providers: [Provider]) -> [Model] {
        providers.filter { $0.enabled && $0.kind == .llm }.flatMap { p in p.models.filter { $0.kind == .chat && $0.imageOnly != true && !$0.disabled && $0.visible } }
    }

    /// The providers the hub can draw with (§72, §84): it says so, or — an older hub — any chat provider
    /// not signed in to.
    static func draws(_ provider: Provider) -> Bool {
        provider.enabled && provider.kind == .llm && (provider.drawsImages ?? (provider.auth.kind != .oauth))
    }

    /// The Images tab's models: those that draw (`image_output`) on a provider that draws, the ones
    /// that only draw first (§87, §110).
    static func imageModels(_ providers: [Provider]) -> [Model] {
        let models: [Model] = providers.filter { draws($0) }.flatMap { provider in
            provider.models.filter { !$0.disabled && $0.visible && $0.capabilities.contains(.imageOutput) }
        }
        let only: [Model] = models.filter { $0.imageOnly == true }
        let rest: [Model] = models.filter { $0.imageOnly != true }
        return only + rest
    }

    /// A subscription's image model is not a model the provider lists; its name says how it draws.
    static func viaSubscription(_ model: Model, _ providers: [Provider]) -> String? {
        guard model.alias == nil, let provider = providers.first(where: { $0.id == model.providerId }), provider.auth.kind == .oauth else { return nil }
        return provider.label
    }

    static func label(_ ref: ModelRef?, _ providers: [Provider]) -> String? {
        guard let ref else { return nil }
        let provider = providers.first { $0.id == ref.providerId }
        let alias = provider?.models.first { $0.model == ref.model }?.alias
        return "\(alias ?? ref.model) · \(provider?.label ?? ref.providerId)"
    }
}

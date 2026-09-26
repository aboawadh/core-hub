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

    static func ready(_ preset: ProviderPreset, key: String, baseURL: String) -> Bool {
        (preset.signIn || preset.key != ._required || !key.trimmingCharacters(in: .whitespaces).isEmpty)
            && (!preset.baseUrlRequired || !baseURL.trimmingCharacters(in: .whitespaces).isEmpty)
    }

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

    static func chatModels(_ providers: [Provider]) -> [Model] {
        providers.filter { $0.enabled && $0.kind == .llm }.flatMap { p in p.models.filter { $0.kind == .chat && $0.imageOnly != true && !$0.disabled } }
    }

    static func imageModels(_ providers: [Provider]) -> [Model] {
        let drawing: [Provider] = providers.filter { $0.enabled && $0.drawsImages == true }
        let models: [Model] = drawing.flatMap { provider in provider.models.filter { !$0.disabled } }
        let only: [Model] = models.filter { $0.imageOnly == true }
        let rest: [Model] = models.filter { $0.imageOnly != true }
        return only + rest
    }

    static func label(_ ref: ModelRef?, _ providers: [Provider]) -> String? {
        guard let ref else { return nil }
        let provider = providers.first { $0.id == ref.providerId }
        let alias = provider?.models.first { $0.model == ref.model }?.alias
        return "\(alias ?? ref.model) · \(provider?.label ?? ref.providerId)"
    }
}

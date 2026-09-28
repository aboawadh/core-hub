// Every string a person reads comes from the catalogues in i18n/ — `<lang>.json` and each area's
// `<area>.<lang>.json` — one set per language of the registry (locales/languages.json, ADR 0028,
// generated into Generated/Languages.swift). Arabic and English hold every key (`pnpm i18n:check`,
// L10nTests); a key another language lacks comes from its fallback chain and then English, never
// the bare key. The app has its own language setting, so the catalogue is read here rather than
// through the system's localisation, and switching is immediate.
import Foundation
import SwiftUI

/// A UI language of the registry. Arabic and English are always there (`.ar`, `.en`).
struct AppLanguage: RawRepresentable, Hashable, Identifiable, Codable {
    let rawValue: String

    /// A registered language (or a test-only pseudo-locale); nil for anything else.
    init?(rawValue: String) {
        guard Languages.all.contains(where: { $0.code == rawValue })
            || Languages.pseudo.contains(where: { $0.code == rawValue }) else { return nil }
        self.rawValue = rawValue
    }

    private init(known: String) { rawValue = known }

    static let ar = AppLanguage(known: "ar")
    static let en = AppLanguage(known: "en")

    /// Every language a person can choose, in the registry's order (never a pseudo-locale).
    static var allCases: [AppLanguage] { Languages.all.map { AppLanguage(known: $0.code) } }

    var id: String { rawValue }
    var info: LanguageInfo? { Languages.all.first { $0.code == rawValue } }
    var pseudo: PseudoLocale? { Languages.pseudo.first { $0.code == rawValue } }
    /// How the language names itself (the pickers).
    var nativeName: String { info?.nativeName ?? rawValue }
    var isRTL: Bool { info?.rtl ?? pseudo?.rtl ?? false }
    var layoutDirection: LayoutDirection { isRTL ? .rightToLeft : .leftToRight }
    /// The language a pseudo-locale borrows its words from, or this one.
    var base: AppLanguage { pseudo.flatMap { AppLanguage(rawValue: $0.base) } ?? self }
    /// The languages a key is looked up in: this one, its fallbacks, then English.
    var chain: [AppLanguage] {
        var out: [AppLanguage] = []
        for code in [rawValue, base.rawValue] + (base.info?.fallback ?? []) + ["en"] {
            if let language = AppLanguage(rawValue: code), !out.contains(language) { out.append(language) }
        }
        return out
    }
    /// The locale every number, date, size and percentage is formatted in: Latin digits (123) in
    /// every language, also in Arabic (owner, 2026-09-26, DECISIONS §113). Words, plural forms and
    /// RTL stay.
    var locale: Locale { Locale(identifier: base.rawValue).latinDigits }

    /// The registered language for a BCP 47 tag: the exact tag, with its likely script
    /// (`zh-TW` → `zh-Hant`), the bare language, then any variant of it.
    static func match(_ tag: String) -> AppLanguage? {
        let codes = allCases.map(\.rawValue)
        let lower = tag.replacingOccurrences(of: "_", with: "-").lowercased()
        if let exact = codes.first(where: { $0.lowercased() == lower }) { return AppLanguage(known: exact) }
        let parts = lower.split(separator: "-").map(String.init)
        guard let language = parts.first else { return nil }
        let region = parts.dropFirst().first { $0.count == 2 }
        var script = parts.dropFirst().first { $0.count == 4 }
        if script == nil, language == "zh" { script = ["tw", "hk", "mo"].contains(region ?? "") ? "hant" : "hans" }
        var candidates: [String] = []
        if let script, let region { candidates.append("\(language)-\(script)-\(region)") }
        if let script { candidates.append("\(language)-\(script)") }
        if let region { candidates.append("\(language)-\(region)") }
        candidates.append(language)
        for candidate in candidates {
            if let found = codes.first(where: { $0.lowercased() == candidate }) { return AppLanguage(known: found) }
        }
        return codes.first { $0.lowercased().split(separator: "-").first.map(String.init) == language }
            .map { AppLanguage(known: $0) }
    }

    /// The phone's own language when it is one of ours, else Arabic (Arabic first, DESIGN.md).
    /// iOS's per-app language (Settings → Core Hub → Language) comes first in this list.
    static var preferred: AppLanguage {
        for code in Locale.preferredLanguages {
            if let found = match(code) { return found }
        }
        return .ar
    }

    /// The contract's `Locale` (`ar` | `en`) nearest to this language: the hub's own words and
    /// the `Accept-Language` of the operations that document only those two (DECISIONS §129).
    var hubLocale: String { chain.first { $0 == .ar || $0 == .en }?.rawValue ?? "en" }

    /// What the one-press language switch goes to: the other of two languages, else the next.
    var next: AppLanguage {
        let all = AppLanguage.allCases
        guard let at = all.firstIndex(of: self) else { return .ar }
        return all[(at + 1) % all.count]
    }
}

extension Locale {
    /// This locale with the Latin numbering system (`@numbers=latn`); language and region stay.
    var latinDigits: Locale {
        var components = Locale.Components(locale: self)
        components.numberingSystem = Locale.NumberingSystem("latn")
        return Locale(components: components)
    }
}

/// Byte counts («3.4 MB») in the phone's language with Latin digits. `ByteCountFormatter`'s class
/// method follows the phone's digits, which are Arabic-Indic on many Arabic phones.
enum ByteCount {
    static func text(_ bytes: Int64, style: ByteCountFormatStyle.Style) -> String {
        bytes.formatted(ByteCountFormatStyle(style: style, locale: Locale.current.latinDigits))
    }
}

struct L10n {
    let language: AppLanguage
    private let table: [String: String]

    init(_ language: AppLanguage, bundle: Bundle = .main) {
        self.language = language
        // English first, then each language of the chain over it: the nearest one wins.
        var merged: [String: String] = [:]
        for each in language.base.chain.reversed() {
            merged.merge(L10n.load(each, bundle: bundle).filter { !$0.value.trimmingCharacters(in: .whitespaces).isEmpty }) { _, new in new }
        }
        if let pseudo = language.pseudo {
            merged = merged.mapValues { Pseudo.transform($0, style: pseudo.style) }
        }
        self.table = merged
    }

    /// The text for `key` with `{name}` placeholders filled; the key itself when no language of
    /// the chain has it, so a gap is visible instead of silent. A plural form (`….few`) a
    /// language lacks reads its `….other`.
    func t(_ key: String, _ params: [String: String] = [:]) -> String {
        var text = table[key] ?? pluralOther(key) ?? key
        for (name, value) in params {
            text = text.replacingOccurrences(of: "{\(name)}", with: value)
        }
        return text
    }

    private func pluralOther(_ key: String) -> String? {
        guard let dot = key.lastIndex(of: "."),
              ["zero", "one", "two", "few", "many"].contains(String(key[key.index(after: dot)...])) else { return nil }
        return table[String(key[..<dot]) + ".other"]
    }

    func callAsFunction(_ key: String, _ params: [String: String] = [:]) -> String {
        t(key, params)
    }

    func has(_ key: String) -> Bool { table[key] != nil }

    /// A language's name in a picker: Arabic and English in the catalogue's own words, any other
    /// language in its own name from the registry (ADR 0028).
    func name(of language: AppLanguage) -> String {
        has("shell.language_\(language.rawValue)") ? t("shell.language_\(language.rawValue)") : language.nativeName
    }

    var keys: Set<String> { Set(table.keys) }

    /// The product's name in this language (from product.ts, not the catalogue).
    var productName: String { language.base == .ar ? Product.nameAr : Product.name }

    private static var cache: [String: [String: String]] = [:]
    private static let lock = NSLock()

    /// One language's own catalogue, without its fallbacks.
    static func load(_ language: AppLanguage, bundle: Bundle) -> [String: String] {
        let cacheKey = "\(bundle.bundlePath)#\(language.rawValue)"
        lock.lock()
        defer { lock.unlock() }
        if let hit = cache[cacheKey] { return hit }
        var table: [String: String] = [:]
        for url in catalogues(language, bundle: bundle) {
            if let data = try? Data(contentsOf: url),
               let object = try? JSONSerialization.jsonObject(with: data) {
                flatten(object, prefix: "", into: &table)
            }
        }
        cache[cacheKey] = table
        return table
    }

    /// The catalogue files of a language: `<lang>.json`, then each area's `<area>.<lang>.json` by name
    /// (docs/clients/phone-pages.md: a batch adds its strings in a file of its own; no key is in two files).
    static func catalogues(_ language: AppLanguage, bundle: Bundle) -> [URL] {
        let base = bundle.url(forResource: language.rawValue, withExtension: "json")
        let areas = (bundle.urls(forResourcesWithExtension: "json", subdirectory: nil) ?? [])
            .filter { $0.lastPathComponent.hasSuffix(".\(language.rawValue).json") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        return (base.map { [$0] } ?? []) + areas
    }

    /// Keys found in more than one catalogue file of a language (none, or a batch collided).
    static func duplicates(_ language: AppLanguage, bundle: Bundle) -> [String] {
        var seen: [String: String] = [:]
        var twice: [String] = []
        for url in catalogues(language, bundle: bundle) {
            guard let data = try? Data(contentsOf: url), let object = try? JSONSerialization.jsonObject(with: data) else { continue }
            var table: [String: String] = [:]
            flatten(object, prefix: "", into: &table)
            for key in table.keys {
                if seen[key] != nil { twice.append(key) }
                seen[key] = url.lastPathComponent
            }
        }
        return twice.sorted()
    }

    static func flatten(_ value: Any, prefix: String, into table: inout [String: String]) {
        if let object = value as? [String: Any] {
            for (key, child) in object {
                flatten(child, prefix: prefix.isEmpty ? key : "\(prefix).\(key)", into: &table)
            }
        } else if let text = value as? String {
            table[prefix] = text
        }
    }
}

private struct L10nKey: EnvironmentKey {
    static let defaultValue = L10n(.en)
}

extension EnvironmentValues {
    var l10n: L10n {
        get { self[L10nKey.self] }
        set { self[L10nKey.self] = newValue }
    }
}

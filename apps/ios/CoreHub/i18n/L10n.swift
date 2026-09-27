// Every string a person reads comes from i18n/ar.json or i18n/en.json (the same keys, checked
// by `pnpm i18n:check` and L10nTests). The app has its own language setting, so the catalogue
// is read here rather than through the system's localisation, and switching is immediate.
import Foundation
import SwiftUI

enum AppLanguage: String, CaseIterable, Identifiable, Codable {
    case ar
    case en

    var id: String { rawValue }
    var isRTL: Bool { self == .ar }
    var layoutDirection: LayoutDirection { isRTL ? .rightToLeft : .leftToRight }
    /// The locale every number, date, size and percentage is formatted in: Latin digits (123) in
    /// both languages, also in Arabic (owner, 2026-09-26, DECISIONS §113). Arabic words, plural
    /// forms and RTL stay.
    var locale: Locale { Locale(identifier: rawValue).latinDigits }

    /// The phone's own language when it is one of ours, else Arabic (Arabic first, DESIGN.md).
    static var preferred: AppLanguage {
        for code in Locale.preferredLanguages {
            if code.hasPrefix("en") { return .en }
            if code.hasPrefix("ar") { return .ar }
        }
        return .ar
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
        self.table = L10n.load(language, bundle: bundle)
    }

    /// The text for `key` with `{name}` placeholders filled; the key itself when missing, so a
    /// gap is visible instead of silent.
    func t(_ key: String, _ params: [String: String] = [:]) -> String {
        var text = table[key] ?? key
        for (name, value) in params {
            text = text.replacingOccurrences(of: "{\(name)}", with: value)
        }
        return text
    }

    func callAsFunction(_ key: String, _ params: [String: String] = [:]) -> String {
        t(key, params)
    }

    func has(_ key: String) -> Bool { table[key] != nil }

    var keys: Set<String> { Set(table.keys) }

    /// The product's name in this language (from product.ts, not the catalogue).
    var productName: String { language == .ar ? Product.nameAr : Product.name }

    private static var cache: [String: [String: String]] = [:]
    private static let lock = NSLock()

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

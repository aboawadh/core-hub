@testable import CoreHub
import SwiftUI
import UIKit
import XCTest

/// The UI languages on iOS (ADR 0028): the registry's list, the fallback chain to English, the
/// plural forms of each language, the test-only pseudo-locales, and single-line labels that stay
/// one line when a translation is longer, wider or taller.
final class LanguagesTests: XCTestCase {
    func testTheRegistryListsArabicAndEnglishAndNoPseudoLocale() {
        XCTAssertEqual(AppLanguage.allCases.map(\.rawValue), Languages.all.map(\.code))
        XCTAssertTrue(AppLanguage.allCases.contains(.ar))
        XCTAssertTrue(AppLanguage.allCases.contains(.en))
        for pseudo in ["en-XA", "ar-XB", "zh-XC", "th-XD"] {
            XCTAssertNotNil(AppLanguage(rawValue: pseudo), pseudo)
            XCTAssertFalse(AppLanguage.allCases.map(\.rawValue).contains(pseudo), pseudo)
        }
        XCTAssertNil(AppLanguage(rawValue: "xx"))
        XCTAssertTrue(AppLanguage.ar.isRTL)
        XCTAssertTrue(AppLanguage(rawValue: "ar-XB")!.isRTL)
        XCTAssertEqual(AppLanguage.ar.nativeName, "العربية")
    }

    func testATagFindsItsRegisteredLanguage() {
        XCTAssertEqual(AppLanguage.match("ar-SA"), .ar)
        XCTAssertEqual(AppLanguage.match("en_GB"), .en)
        XCTAssertNil(AppLanguage.match("xx-YY"))
        XCTAssertEqual(AppLanguage.ar.next, .en)
        XCTAssertEqual(AppLanguage.en.next, .ar)
        XCTAssertEqual(AppLanguage.ar.hubLocale, "ar")
        XCTAssertEqual(AppLanguage(rawValue: "en-XA")!.hubLocale, "en")
    }

    func testAKeyFallsBackAlongTheChainNeverToARawKeyWhileAnyLanguageHasIt() {
        XCTAssertEqual(AppLanguage.ar.chain, [.ar, .en])
        XCTAssertEqual(AppLanguage(rawValue: "ar-XB")!.chain.map(\.rawValue), ["ar-XB", "ar", "en"])
        let en = L10n(.en)
        XCTAssertEqual(en("shell.language"), "Language")
        XCTAssertEqual(en("no.such.key"), "no.such.key")
        XCTAssertEqual(L10n(.ar).name(of: .en), "English")
    }

    func testPseudoLocalesTransformTheWordsAndKeepThePlaceholders() {
        let accented = L10n(AppLanguage(rawValue: "en-XA")!)
        let language = accented("shell.language")
        XCTAssertTrue(language.hasPrefix("[") && language.hasSuffix("]"), language)
        XCTAssertGreaterThanOrEqual(language.count, Int(Double("Language".count) * 1.4))
        XCTAssertTrue(accented("shell.version", ["version": "1.2.3"]).contains("1.2.3"))
        XCTAssertTrue(L10n(AppLanguage(rawValue: "ar-XB")!)("shell.language").hasPrefix("«"))
        XCTAssertFalse(L10n(AppLanguage(rawValue: "zh-XC")!)("shell.language").contains("a"))
        XCTAssertEqual(Pseudo.transform("{count} files", style: "tagged"), "{count} files")
    }

    func testPluralFormsFollowEachLanguage() {
        XCTAssertEqual(PluralCategory.of(3, .ar), "few")
        XCTAssertEqual(PluralCategory.of(11, .ar), "many")
        XCTAssertEqual(PluralCategory.of(1, .en), "one")
        XCTAssertEqual(PluralCategory.of(2, .en), "other")
        XCTAssertEqual(PluralCategory.of(2, AppLanguage(rawValue: "ar-XB")!), "two")
    }

    /// A chip holds one line in every pseudo-locale: as tall with a long label in a narrow place
    /// as with a short one, so no letter drops to a second line.
    @MainActor
    func testAChipStaysOneLineInEveryPseudoLocale() {
        func height(_ text: String) -> CGFloat {
            let host = UIHostingController(rootView: Button(text) {}.buttonStyle(ChipButtonStyle()).frame(maxWidth: 110))
            return host.sizeThatFits(in: CGSize(width: 110, height: CGFloat.greatestFiniteMagnitude)).height
        }
        let short = height("Tasks")
        for code in ["en-XA", "ar-XB", "zh-XC", "th-XD"] {
            let l10n = L10n(AppLanguage(rawValue: code)!)
            for key in ["nav.device_connections", "nav.agent_manager", "shell.connecting"] {
                let text = l10n(key)
                XCTAssertEqual(height(text), short, accuracy: 6, "\(code) \(key): “\(text)”")
            }
        }
    }
}

// Settings → Display: the language, and the hub's display preferences for this person.
import CoreHubClient
import SwiftUI

/// Display: the person's preferences the hub keeps (`auth.me.preferences`), plus the app's
/// own language.
struct DisplayPage: View {
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: "preferences") {
            try await app.api.call { try await AuthAPI.authGetPreferences(apiConfiguration: $0) }
        } content: { preferences, reload in
            PreferencesForm(initial: preferences, saved: reload) { draft in
                Section {
                    Picker(l10n("shell.language"), selection: Binding(get: { app.language }, set: { app.language = $0 })) {
                        ForEach(AppLanguage.allCases) { Text(l10n("shell.language_\($0.rawValue)")).tag($0) }
                    }
                    Picker(l10n("display.link_target"), selection: draft.linkTarget) {
                        Text(l10n("display.link_in_app")).tag(Preferences.LinkTarget.inApp)
                        Text(l10n("display.link_browser")).tag(Preferences.LinkTarget.browser)
                    }
                    Picker(l10n("display.busy_input"), selection: draft.busyInputMode) {
                        Text(l10n("display.busy_queue")).tag(Preferences.BusyInputMode.queue)
                        Text(l10n("display.busy_next")).tag(Preferences.BusyInputMode.next)
                        Text(l10n("display.busy_interrupt")).tag(Preferences.BusyInputMode.interrupt)
                    }
                }
                Section {
                    Toggle(l10n("display.show_reasoning"), isOn: draft.showReasoning)
                    Toggle(l10n("display.show_tool_calls"), isOn: draft.showToolCalls)
                    Toggle(l10n("display.compact"), isOn: draft.compact)
                    VStack(alignment: .leading) {
                        Text(l10n("display.text_scale", ["percent": String(Int((draft.wrappedValue.textScale * 100).rounded()))]))
                        Slider(value: draft.textScale, in: 0.85...1.45, step: 0.05)
                    }
                }
            }
        }
    }
}

/// Edits a copy of the preferences and saves the whole document (`auth.setPreferences`).
struct PreferencesForm<Fields: View>: View {
    let initial: Preferences
    let saved: () -> Void
    @ViewBuilder let fields: (Binding<Preferences>) -> Fields
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var draft: Preferences?
    @State private var note: (String, NoticeView.Kind)?

    var body: some View {
        Form {
            if let note { NoticeView(text: note.0, tone: note.1) }
            fields(Binding(get: { draft ?? initial }, set: { draft = $0 }))
            Section {
                Button(l10n("common.save")) { Task { await save() } }
                    .disabled(draft == nil || draft == initial)
            }
        }
    }

    private func save() async {
        guard let draft else { return }
        do {
            _ = try await app.api.call { try await AuthAPI.authSetPreferences(preferences: draft, apiConfiguration: $0) }
            note = (l10n("common.saved"), .success)
            saved()
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
    }
}

extension PhonePage {
    static let display = PhonePage(.display) { _ in DisplayPage() }
}

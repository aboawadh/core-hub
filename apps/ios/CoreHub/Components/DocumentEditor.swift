// The text/Markdown editor (docs/clients/phone-pages.md), taken from the agents' config files page:
// skills, memory, room memory and profile files edit their text the same way. Android's
// TextEditorSheet.kt is its twin.
import SwiftUI

/// The editing field of a document: Markdown in the reading font, in its own direction; anything
/// else (JSON, TOML, YAML, code) left to right in monospace.
struct DocumentEditor: View {
    @Binding var text: String
    let markdown: Bool
    var identifier = "editor.text"

    var body: some View {
        TextEditor(text: $text)
            .font(markdown ? .system(size: FontSize.sizeSm) : .system(size: FontSize.sizeSm, design: .monospaced))
            .environment(\.layoutDirection, markdown ? (ContentDirection.of(text) ?? .leftToRight) : .leftToRight)
            .autocorrectionDisabled(!markdown)
            .textInputAutocapitalization(.never)
            .padding(Space.s2)
            .background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.md))
            .overlay(RoundedRectangle(cornerRadius: Radius.md).strokeBorder(Tone.border, lineWidth: 0.5))
            .accessibilityIdentifier(identifier)
    }
}

enum DocumentRules {
    /// Whether a save was refused because the text changed elsewhere since it was read: `409` with
    /// `details.reason = changed` (the hub's `conflict` code), or a `changed` code.
    static func changedElsewhere(_ error: Error) -> Bool {
        let failure = HubFailure(error)
        return failure.status == 409 && (failure.reason == "changed" || failure.code == "changed")
    }
}

/// A sheet that edits `initial` and saves it with `save` (which sends the revision it read, so a
/// change made elsewhere is refused, not overwritten). Markdown gets an Edit/Preview switch. When a
/// save is refused as changed elsewhere, `reload` (if given) is offered. Identifiers:
/// `<tag>.text`, `<tag>.save`, `<tag>.preview`.
struct TextEditorSheet: View {
    let title: String
    let initial: String
    var markdown = true
    var subtitle: String?
    var tag = "editor"
    var reload: (() -> Void)?
    let save: (String) async throws -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.l10n) private var l10n
    @State private var text = ""
    @State private var preview = false
    @State private var saving = false
    @State private var failure: String?
    @State private var changedElsewhere = false
    @State private var started = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Space.s2) {
                if let subtitle {
                    Text(subtitle).font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textMuted).lineLimit(2)
                }
                if let failure { NoticeView(text: failure, tone: .danger) }
                if changedElsewhere, let reload {
                    Button(l10n("kit.reload")) { reload(); dismiss() }.buttonStyle(.bordered)
                }
                if markdown {
                    Picker("", selection: $preview) {
                        Text(l10n("kit.edit")).tag(false)
                        Text(l10n("kit.preview")).tag(true)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("\(tag).preview")
                }
                if preview {
                    ScrollView {
                        MarkdownView(text: text, foreground: Tone.text)
                            .environment(\.layoutDirection, ContentDirection.of(text) ?? .leftToRight)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                } else {
                    DocumentEditor(text: $text, markdown: markdown, identifier: "\(tag).text")
                }
                Button(l10n("kit.revert")) { text = initial }
                    .disabled(text == initial)
            }
            .padding(Space.s4)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(l10n("common.cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving {
                        ProgressView()
                    } else {
                        Button(l10n("common.save")) { Task { await submit() } }
                            .disabled(text == initial)
                            .accessibilityIdentifier("\(tag).save")
                    }
                }
            }
        }
        .onAppear {
            if !started { text = initial; started = true }
        }
    }

    private func submit() async {
        saving = true
        defer { saving = false }
        do {
            try await save(text)
            dismiss()
        } catch {
            changedElsewhere = DocumentRules.changedElsewhere(error)
            failure = changedElsewhere ? l10n("kit.changed_elsewhere") : HubFailure(error).describe(l10n)
        }
    }
}

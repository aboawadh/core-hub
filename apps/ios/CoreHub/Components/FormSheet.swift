// The form sheet every create/edit on the phone uses (docs/clients/phone-pages.md): fields of a few
// kinds, typed as text, checked before saving, and the hub's own answer when it refuses. The rules
// are plain functions (`FormRules`) so a page's test checks them without drawing; Android's
// FormSheet.kt has the same rules.
import SwiftUI

/// What a field holds. Values travel as text: a toggle is "true"/"false", a choice its option's value.
enum FormKind: Equatable { case text, multiline, number, toggle, choice, secret }

struct FormOption: Equatable, Hashable {
    let value: String
    let label: String
}

/// One field of a `FormSheet`. `min`/`max` and `integer` apply to `.number`; `options` to `.choice`.
struct FormField: Equatable, Identifiable {
    let key: String
    let label: String
    var kind: FormKind = .text
    var required = false
    var help: String?
    var placeholder: String?
    var options: [FormOption] = []
    var min: Decimal?
    var max: Decimal?
    var integer = false
    /// Left to right in monospace (ids, cron, commands); otherwise the text keeps its own direction.
    var mono = false

    var id: String { key }
}

/// Why a value is refused.
enum FormProblem: Equatable {
    case required, notANumber, notWhole, notAnOption
    case tooSmall(Decimal)
    case tooLarge(Decimal)

    func text(_ l10n: L10n) -> String {
        switch self {
        case .required: return l10n("kit.required")
        case .notANumber: return l10n("kit.not_number")
        case .notWhole: return l10n("kit.not_whole")
        case .notAnOption: return l10n("kit.not_option")
        case .tooSmall(let limit): return l10n("kit.too_small", ["limit": "\(limit)"])
        case .tooLarge(let limit): return l10n("kit.too_large", ["limit": "\(limit)"])
        }
    }
}

enum FormRules {
    /// A number as a person types it: a comma is the decimal point too.
    static func number(_ text: String) -> Decimal? {
        let t = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard !t.isEmpty, t.allSatisfy({ $0.isASCII && ($0.isNumber || $0 == "." || $0 == "-" || $0 == "+") }) else { return nil }
        return Decimal(string: t, locale: Locale(identifier: "en_US_POSIX"))
    }

    static func problem(_ field: FormField, _ value: String) -> FormProblem? {
        let t = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || (field.kind == .toggle && t != "true" && t != "false") {
            return field.required && field.kind != .toggle ? .required : nil
        }
        switch field.kind {
        case .number:
            guard let n = number(t) else { return .notANumber }
            if field.integer {
                var whole = Decimal()
                var copy = n
                NSDecimalRound(&whole, &copy, 0, .plain)
                if whole != n { return .notWhole }
            }
            if let min = field.min, n < min { return .tooSmall(min) }
            if let max = field.max, n > max { return .tooLarge(max) }
            return nil
        case .choice:
            return field.options.contains { $0.value == t } ? nil : .notAnOption
        default:
            return nil
        }
    }

    /// Every refused field, by key; empty when the form can be saved.
    static func problems(_ fields: [FormField], _ values: [String: String]) -> [String: FormProblem] {
        var out: [String: FormProblem] = [:]
        for field in fields {
            if let problem = problem(field, values[field.key] ?? "") { out[field.key] = problem }
        }
        return out
    }
}

/// A sheet with `fields` filled from `initial`, Cancel and Save. Save checks every field first (a
/// refused one says why under it), then calls `save` with every value; a failure stays in the
/// sheet as the hub's words, a success closes it. Identifiers: `<tag>.<key>`, `<tag>.save`.
struct FormSheet: View {
    let title: String
    let fields: [FormField]
    let initial: [String: String]
    var intro: String?
    var saveTitle: String?
    var tag = "form"
    let save: ([String: String]) async throws -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.l10n) private var l10n
    @State private var values: [String: String] = [:]
    @State private var tried = false
    @State private var saving = false
    @State private var failure: String?
    @State private var shown: Set<String> = []

    var body: some View {
        NavigationStack {
            Form {
                if let intro {
                    Text(intro).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                }
                if let failure { NoticeView(text: failure, tone: .danger) }
                ForEach(fields) { field in
                    Section {
                        row(field)
                    } footer: {
                        VStack(alignment: .leading, spacing: 2) {
                            if tried, let problem = FormRules.problem(field, values[field.key] ?? "") {
                                Text(problem.text(l10n)).foregroundStyle(Tone.danger)
                                    .accessibilityIdentifier("\(tag).\(field.key).problem")
                            }
                            if let help = field.help, field.kind != .toggle { Text(help) }
                        }
                    }
                }
            }
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
                        Button(saveTitle ?? l10n("common.save")) { Task { await submit() } }
                            .accessibilityIdentifier("\(tag).save")
                    }
                }
            }
        }
        .onAppear {
            if values.isEmpty {
                values = Dictionary(uniqueKeysWithValues: fields.map { ($0.key, initial[$0.key] ?? "") })
            }
        }
    }

    private func binding(_ key: String) -> Binding<String> {
        Binding(get: { values[key] ?? "" }, set: { values[key] = $0 })
    }

    @ViewBuilder
    private func row(_ field: FormField) -> some View {
        let label = field.label + (field.required ? " *" : "")
        switch field.kind {
        case .toggle:
            Toggle(isOn: Binding(get: { values[field.key] == "true" }, set: { values[field.key] = $0 ? "true" : "false" })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(field.label)
                    if let help = field.help { Text(help).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
                }
            }
            .accessibilityIdentifier("\(tag).\(field.key)")
        case .choice:
            let picker = Picker(label, selection: binding(field.key)) {
                if (values[field.key] ?? "").isEmpty { Text(l10n("kit.choose")).tag("") }
                ForEach(field.options, id: \.value) { option in Text(option.label).tag(option.value) }
            }
            // A few options side by side; more in a menu.
            if field.options.count <= 3 {
                picker.pickerStyle(.segmented).accessibilityIdentifier("\(tag).\(field.key)")
            } else {
                picker.pickerStyle(.menu).accessibilityIdentifier("\(tag).\(field.key)")
            }
        case .multiline:
            VStack(alignment: .leading, spacing: Space.s1) {
                Text(label).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                TextEditor(text: binding(field.key))
                    .frame(minHeight: 100)
                    .font(field.mono ? .system(size: FontSize.sizeSm, design: .monospaced) : .system(size: FontSize.sizeMd))
                    .environment(\.layoutDirection, field.mono ? .leftToRight : (ContentDirection.of(values[field.key] ?? "") ?? .leftToRight))
                    .accessibilityIdentifier("\(tag).\(field.key)")
            }
        case .secret:
            HStack {
                Group {
                    if shown.contains(field.key) {
                        TextField(label, text: binding(field.key), prompt: field.placeholder.map { Text($0) })
                    } else {
                        SecureField(label, text: binding(field.key), prompt: field.placeholder.map { Text($0) })
                    }
                }
                .font(.system(size: FontSize.sizeMd, design: .monospaced))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier("\(tag).\(field.key)")
                Button {
                    if shown.contains(field.key) { shown.remove(field.key) } else { shown.insert(field.key) }
                } label: {
                    LucideIcon(shown.contains(field.key) ? .eyeOff : .eye, size: 16).foregroundStyle(Tone.textMuted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(l10n(shown.contains(field.key) ? "kit.hide_secret" : "kit.show_secret"))
                .accessibilityIdentifier("\(tag).\(field.key).reveal")
            }
        default:
            TextField(label, text: binding(field.key), prompt: field.placeholder.map { Text($0) })
                .keyboardType(field.kind == .number ? (field.integer ? .numberPad : .decimalPad) : .default)
                .font(field.mono ? .system(size: FontSize.sizeMd, design: .monospaced) : .system(size: FontSize.sizeMd))
                .textInputAutocapitalization(field.mono ? .never : .sentences)
                .autocorrectionDisabled(field.mono)
                .accessibilityIdentifier("\(tag).\(field.key)")
        }
    }

    private func submit() async {
        tried = true
        guard FormRules.problems(fields, values).isEmpty else { return }
        saving = true
        defer { saving = false }
        do {
            try await save(values)
            failure = nil
            dismiss()
        } catch {
            failure = HubFailure(error).describe(l10n)
        }
    }
}

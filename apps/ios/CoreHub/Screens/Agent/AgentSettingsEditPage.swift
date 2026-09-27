// An agent's Settings on the phone (B14, apps batch 9): the fields edited in place — lists one item per
// line and JSON included — with the web's cards: signing a coding agent in to its own account, the
// Presets of contract decision §100, context compression (§57), and what Hermes wrote and waits for
// review (§58). Every call carries the profile the page edits.
import CoreHubClient
import SwiftUI

/// A settings field's value, shown and typed (every kind the adapter declares; web AgentSettingsScreen).
enum SettingValues {
    /// A list or JSON is typed over several lines, in a sheet.
    static func multiline(_ field: SettingsField) -> Bool { field.kind == .list || field.kind == .json }

    static func text(_ value: JSONValue?) -> String? {
        guard let value else { return nil }
        if case .null = value { return nil }
        return JSONText.scalar(value)
    }

    /// What the field reads as in its row: a list's items, JSON as `{…}`.
    static func shown(_ field: SettingsField) -> String? {
        switch field.kind {
        case .json:
            guard let value = field.value, value != .null else { return nil }
            if case .array = value { return "[…]" }
            return "{…}"
        default: return text(field.value)
        }
    }

    /// The text the editor starts with: a list one item per line, JSON pretty, a secret empty.
    static func typedText(_ field: SettingsField) -> String {
        switch field.kind {
        case .secret: return ""
        case .list: return SettingsCardRules.listText(field.value)
        case .json: return SettingsCardRules.jsonText(field.value)
        default: return text(field.value) ?? ""
        }
    }

    /// What was typed as the field's value, or nil when it is not one; empty puts the default back.
    static func parse(_ field: SettingsField, _ typed: String) -> JSONValue? {
        let t = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return .null }
        func inRange(_ n: Double) -> Bool { (field.min.map { n >= $0 } ?? true) && (field.max.map { n <= $0 } ?? true) }
        switch field.kind {
        case .integer:
            guard let n = Int(t), inRange(Double(n)) else { return nil }
            return .int(n)
        case .number:
            guard let n = Double(t.replacingOccurrences(of: ",", with: ".")), inRange(n) else { return nil }
            return .double(n)
        case .toggle:
            if t == "true" { return .bool(true) }
            if t == "false" { return .bool(false) }
            return nil
        case .choice:
            return field.options.contains { $0.value == t } ? .string(t) : nil
        case .list:
            return SettingsCardRules.listValue(typed)
        case .json:
            return SettingsCardRules.jsonValue(typed)
        default:
            return .string(typed)
        }
    }

    static func on(_ field: SettingsField) -> Bool {
        if case .bool(let on)? = field.value { return on }
        if case .bool(let on)? = field._default { return on }
        return false
    }
}

/// A list or JSON field being edited in its sheet.
private struct LongEdit: Identifiable {
    let section: String
    let field: SettingsField
    var id: String { section + "." + field.key }
}

struct AgentSettingsEditPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var error: String?
    @State private var editing: (section: String, field: SettingsField)?
    @State private var long: LongEdit?
    @State private var typed = ""
    @State private var saved: SettingsCardRules.Saved?

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call { try await AgentsAPI.agentsGetSettings(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }
        } content: { settings, reload in
            List {
                if agent.install.signIn == true && agent.install.source == .managed { AgentSignInSection(agent: agent) }
                AgentPresetsSection(agent: agent, applied: reload)
                if agent.capabilities.contains(.compress) { CompressionSection() }
                if let error { NoticeView(text: error, tone: .danger) }
                if let saved { NoticeView(text: savedText(saved), tone: .success).accessibilityIdentifier("setting.saved") }
                ForEach(settings.sections, id: \.key) { section in
                    Section {
                        ForEach(section.fields, id: \.key) { field in
                            row(section: section.key, field: field, reload: reload)
                        }
                    } header: {
                        Text(text(section.title))
                    } footer: {
                        if let note = section.note {
                            Text(text(note))
                        } else if section.applies == .nextMessage {
                            Text(l10n("agents2.settings.applies_next_message"))
                        } else if section.restartRequired {
                            Text(l10n("agents2.settings.restart_required"))
                        }
                    }
                }
                if agent.kind == .hermes { PendingWritesSection(agent: agent) }
            }
            .refreshable { reload() }
            .sheet(item: $long) { edit in
                SettingTextSheet(field: edit.field, title: text(edit.field.label)) { value in
                    await set(section: edit.section, field: edit.field.key, value: value, reload: reload)
                }
            }
            .alert(editing.map { text($0.field.label) } ?? "", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
                if let editing {
                    if editing.field.kind == .secret {
                        SecureField(editing.field.hint ?? "", text: $typed)
                    } else {
                        TextField(editing.field.hint ?? SettingValues.text(editing.field._default) ?? "", text: $typed)
                            .keyboardType(editing.field.kind == .integer ? .numberPad : editing.field.kind == .number ? .decimalPad : .default)
                    }
                    Button(l10n("common.save")) {
                        if let value = SettingValues.parse(editing.field, typed) {
                            let section = editing.section, key = editing.field.key
                            self.editing = nil
                            Task { await set(section: section, field: key, value: value, reload: reload) }
                        } else {
                            error = l10n("agent_settings.value_bad")
                            self.editing = nil
                        }
                    }
                    Button(l10n("common.cancel"), role: .cancel) { self.editing = nil }
                }
            } message: {
                Text(l10n("agent_settings.empty_is_default"))
            }
        }
    }

    @ViewBuilder
    private func row(section: String, field: SettingsField, reload: @escaping () -> Void) -> some View {
        switch field.kind {
        case .toggle:
            Toggle(isOn: Binding(get: { SettingValues.on(field) }, set: { value in
                Task { await set(section: section, field: field.key, value: .bool(value), reload: reload) }
            })) {
                label(field)
            }
        case .choice:
            Picker(selection: Binding(get: { SettingValues.text(field.value ?? field._default) ?? "" }, set: { value in
                Task { await set(section: section, field: field.key, value: .string(value), reload: reload) }
            })) {
                ForEach(field.options, id: \.value) { option in
                    Text(option.labels.map(text) ?? option.label).tag(option.value)
                }
            } label: {
                label(field)
            }
        default:
            Button {
                if SettingValues.multiline(field) {
                    long = LongEdit(section: section, field: field)
                } else {
                    typed = SettingValues.typedText(field)
                    editing = (section, field)
                }
            } label: {
                HStack {
                    label(field)
                    Spacer()
                    Text(field.kind == .secret ? (SettingValues.text(field.value) == nil ? "—" : "••••") : (SettingValues.shown(field) ?? SettingValues.text(field._default).map { "(\($0))" } ?? "—"))
                        .foregroundStyle(Tone.textMuted)
                        .lineLimit(1)
                }
            }
            .accessibilityIdentifier("setting.\(section).\(field.key)")
        }
    }

    private func label(_ field: SettingsField) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(text(field.label)).foregroundStyle(Tone.text)
            if let help = field.help {
                Text(text(help)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(3)
            }
        }
    }

    private func text(_ localized: LocalizedText) -> String { app.language == .ar ? localized.ar : localized.en }

    private func set(section: String, field: String, value: JSONValue, reload: @escaping () -> Void) async {
        let profile = app.currentProfile
        do {
            let result = try await app.api.call {
                try await AgentsAPI.agentsUpdateSettings(xHubProfile: profile, agentId: agent.id, agentSettingsPatch: AgentSettingsPatch(section: section, values: [field: value]), apiConfiguration: $0)
            }
            error = nil
            saved = SettingsCardRules.saved(restartJobId: result.restartJobId, applies: result.section.applies)
        } catch {
            self.error = HubFailure(error).describe(l10n)
            saved = nil
        }
        reload()
    }

    private func savedText(_ saved: SettingsCardRules.Saved) -> String {
        switch saved {
        case .restarting: return l10n("agents2.settings.saved_restarting")
        case .restartNeeded: return l10n("agents2.settings.saved_restart_needed")
        case .nextMessage: return l10n("agents2.settings.saved_next_message")
        case .saved: return l10n("agents2.settings.saved")
        }
    }
}

/// Presets (§100): saved bundles of this agent's settings in the profile.
struct AgentPresetsSection: View {
    let agent: Agent
    let applied: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var presets: [AgentPreset]?
    @State private var note: (text: String, tone: NoticeView.Kind)?
    @State private var naming = false
    @State private var name = ""
    @State private var deleting: AgentPreset?

    var body: some View {
        Section(l10n("presets.title")) {
            if let note { NoticeView(text: note.text, tone: note.tone) }
            if let presets {
                if presets.isEmpty {
                    Text(l10n("presets.none")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                }
                ForEach(presets, id: \.id) { preset in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(preset.name).contentDirection(of: preset.name)
                            if let last = preset.lastActivatedAt {
                                Text(l10n("presets.last", ["time": last.shortText(app.language)])).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            }
                        }
                        Spacer()
                        Button(l10n("presets.activate")) { Task { await activate(preset) } }
                            .buttonStyle(ChipButtonStyle())
                            .accessibilityIdentifier("preset.\(preset.id).activate")
                    }
                    .swipeActions {
                        Button(role: .destructive) { deleting = preset } label: { Text(l10n("presets.delete")) }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
            Button {
                name = ""
                naming = true
            } label: {
                LucideLabel(l10n("presets.save_current"), icon: .plus, size: 16)
            }
            .accessibilityIdentifier("preset.new")
        }
        .task(id: app.currentProfile) { await load() }
        .alert(l10n("presets.save_current"), isPresented: $naming) {
            TextField(l10n("presets.name"), text: $name)
            Button(l10n("common.save")) { Task { await save() } }
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            Button(l10n("common.cancel"), role: .cancel) {}
        }
        .confirmationDialog(deleting.map { l10n("presets.delete_confirm", ["name": $0.name]) } ?? "", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button(l10n("presets.delete"), role: .destructive) {
                if let preset = deleting { Task { await remove(preset) } }
                deleting = nil
            }
        }
    }

    private func load() async {
        let profile = app.currentProfile
        presets = (try? await app.api.call { try await AgentsAPI.agentsListPresets(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }.items) ?? []
    }

    private func save() async {
        let profile = app.currentProfile, write = AgentPresetWrite(name: name.trimmingCharacters(in: .whitespaces))
        do {
            _ = try await app.api.call { try await AgentsAPI.agentsCreatePreset(xHubProfile: profile, agentId: agent.id, agentPresetWrite: write, apiConfiguration: $0) }
            note = nil
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
        await load()
    }

    private func activate(_ preset: AgentPreset) async {
        let profile = app.currentProfile
        do {
            let result = try await app.api.call { try await AgentsAPI.agentsActivatePreset(xHubProfile: profile, agentId: agent.id, presetId: preset.id, apiConfiguration: $0) }
            note = result.skipped.isEmpty ? (l10n("presets.applied"), .success) : (l10n("presets.skipped", ["count": String(result.skipped.count)]), .warning)
            applied()
        } catch {
            note = (HubFailure(error).describe(l10n), .danger)
        }
        await load()
    }

    private func remove(_ preset: AgentPreset) async {
        let profile = app.currentProfile
        do {
            try await app.api.call { try await AgentsAPI.agentsDeletePreset(xHubProfile: profile, agentId: agent.id, presetId: preset.id, apiConfiguration: $0) }
            note = nil
        } catch {
            // A refusal is said, never swallowed.
            note = (HubFailure(error).describe(l10n), .danger)
        }
        await load()
    }
}

extension PhonePage {
    static let agentSettings = PhonePage(.agentSettings) { context in
        if let agent = context.agent { AgentSettingsEditPage(agent: agent) }
    }
}

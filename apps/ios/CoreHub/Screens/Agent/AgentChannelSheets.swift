// A channel's card and what it opens (apps batch 9): the gateway line, the card itself, its settings
// (web ChannelSettingsPanel), WhatsApp's mode and reply header, and the fields Hermes reads for a
// platform the hub does not know. Android's AgentChannelsPage.kt / AgentChannelSheets.kt are the twins.
import CoreHubClient
import SwiftUI

/// When a change on the page takes effect, and how the profile's messaging gateway is.
struct GatewayNote: View {
    let gateway: ChannelGateway?
    @Environment(\.l10n) private var l10n

    var body: some View {
        if let gateway, gateway.applies == .now {
            VStack(alignment: .leading, spacing: Space.s1) {
                HStack(alignment: .top) {
                    Text(l10n("agents2.ch.applies_now")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    Spacer()
                    StatusPill(text: l10n("agents2.ch.gateway.\(gateway.state.rawValue)"),
                               kind: gateway.state == .running ? .good : gateway.state == .error ? .bad : .neutral)
                }
                if let error = gateway.error { Text(error).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger) }
            }
            .accessibilityIdentifier("channels.gateway")
        } else {
            Text(l10n("agents2.ch.restart_note")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                .accessibilityIdentifier("channels.gateway")
        }
    }
}

/// One linked platform: its switch, name and marks, whose account it is, what it offers (the first as a
/// button, the rest under «⋯»), and what needs attention: a restart, Hermes's error, senders waiting.
struct ChannelCard: View {
    let channel: Channel
    let spec: ChannelPlatform?
    let waiting: Int
    let canRestart: Bool
    let restarting: Bool
    let busy: Bool
    let switched: (Bool) -> Void
    let restart: () -> Void
    let choose: (ChannelRules.Action) -> Void
    @Environment(\.l10n) private var l10n

    var body: some View {
        let link = channel.link
        let mode = ChannelRules.mode(channel)
        let actions = ChannelRules.actions(channel, spec)
        let name = spec?.label ?? channel.label
        VStack(alignment: .leading, spacing: Space.s2) {
            HStack {
                Text(name).font(.system(size: FontSize.sizeMd, weight: .semibold))
                Spacer()
                Toggle(name, isOn: Binding(get: { channel.enabled }, set: switched))
                    .labelsHidden()
                    .disabled(busy)
                    .accessibilityIdentifier("channel.\(channel.platform).switch")
            }
            FlowLayout(spacing: Space.s1) {
                if channel.exclusive { StatusPill(text: l10n("agents2.ch.exclusive"), kind: .warn) }
                if let link {
                    StatusPill(text: l10n(link.linked ? "agents2.ch.linked" : "agents2.ch.not_linked"), kind: link.linked ? .good : .neutral)
                } else if !channel.configured {
                    StatusPill(text: l10n("agents2.ch.not_configured"))
                }
                if channel.status != .unknown {
                    StatusPill(text: l10n("agents2.ch.status.\(channel.status.rawValue)"),
                               kind: channel.status == .online ? .good : channel.status == .error ? .bad : .neutral)
                        .accessibilityIdentifier("channel.\(channel.platform).status")
                }
                if let mode { StatusPill(text: l10n(mode == .selfChat ? "agents2.ch.mode.badge_self" : "agents2.ch.mode.badge_bot")) }
            }
            let line = link?.linked == true ? ChannelRules.account(link).map { l10n("agents2.ch.linked_as", ["account": $0]) } : nil
            Text(line ?? l10n("agents2.ch.fields_n", ["count": String(channel.fields.count)]))
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                .contentDirection(of: line ?? "")
                .accessibilityIdentifier("channel.\(channel.platform).account")
            if channel.restartNeeded {
                NoticeView(text: l10n("agents2.ch.restart_needed", ["name": name]), tone: .warning)
                    .accessibilityIdentifier("channel.\(channel.platform).restart_needed")
                if canRestart {
                    Button(action: restart) {
                        if restarting { ProgressView() } else { LucideLabel(l10n("agents2.ch.restart_now"), icon: .rotateCw, size: 14) }
                    }
                    .buttonStyle(ChipButtonStyle())
                    .disabled(restarting)
                    .accessibilityIdentifier("channel.\(channel.platform).restart")
                }
            }
            if channel.status == .error, let error = channel.error { NoticeView(text: error, tone: .danger) }
            if waiting > 0 {
                Text(l10n("agents2.ch.waiting_review", ["count": String(waiting)])).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.warningSoftText)
                    .accessibilityIdentifier("channel.\(channel.platform).waiting")
            }
            if let first = actions.first {
                HStack {
                    Button { choose(first) } label: { LucideLabel(label(first), icon: icon(first), size: 14) }
                        .buttonStyle(ChipButtonStyle(quiet: first == .unlink || first == .clear))
                        .accessibilityIdentifier("channel.\(channel.platform).\(first.rawValue)")
                    Spacer()
                    if actions.count > 1 {
                        Menu {
                            ForEach(actions.dropFirst(), id: \.self) { action in
                                Button(role: action == .unlink || action == .clear ? .destructive : nil) { choose(action) } label: {
                                    LucideLabel(label(action), icon: icon(action))
                                }
                            }
                        } label: {
                            LucideIcon(.ellipsis, size: 16).foregroundStyle(Tone.textMuted).frame(width: 32, height: 32)
                        }
                        .accessibilityIdentifier("channel.\(channel.platform).more")
                    }
                }
            }
        }
        .accessibilityIdentifier("channel.\(channel.platform)")
    }

    private func label(_ action: ChannelRules.Action) -> String {
        switch action {
        case .pair: return l10n("agents2.ch.pair")
        case .link: return l10n("agents2.ch.link")
        case .settings: return l10n("agents2.chs.open")
        case .mode: return l10n("agents2.ch.mode.change")
        case .replyHeader: return l10n("agents2.ch.reply.open")
        case .fields: return l10n("agents2.ch.edit")
        case .unlink: return l10n("channels.unlink")
        case .clear: return l10n("agents2.ch.clear")
        }
    }

    private func icon(_ action: ChannelRules.Action) -> Lucide {
        switch action {
        case .pair: return .qrCode
        case .link: return .link
        case .settings: return .slidersHorizontal
        case .mode: return .smartphone
        case .replyHeader: return .type
        case .fields: return .pencil
        case .unlink: return .x
        case .clear: return .trash
        }
    }
}

/// «How WhatsApp is used»: the phone stays linked; the hub rewrites the mode and restarts the gateway.
struct ChannelModeSheet: View {
    let agent: Agent
    let channel: Channel
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var chosen: ChannelLink.Mode?
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(l10n("agents2.ch.mode.change_note")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                    if let error { NoticeView(text: error, tone: .danger) }
                }
                Section(l10n("agents2.ch.mode.title")) {
                    choice(.bot, "agents2.ch.mode.bot", "agents2.ch.mode.bot_hint")
                    choice(.selfChat, "agents2.ch.mode.self", "agents2.ch.mode.self_hint")
                }
            }
            .navigationTitle(l10n("agents2.ch.mode.change_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(l10n("agents2.ch.mode.save")) { Task { await save() } }
                            .disabled(chosen == nil || chosen == channel.link?.mode)
                            .accessibilityIdentifier("channel.mode.save")
                    }
                }
            }
            .onAppear { if chosen == nil { chosen = channel.link?.mode } }
        }
    }

    private func choice(_ mode: ChannelLink.Mode, _ title: String, _ hint: String) -> some View {
        Button {
            chosen = mode
        } label: {
            HStack(alignment: .top, spacing: Space.s3) {
                SelectionMark(chosen: chosen == mode)
                VStack(alignment: .leading, spacing: 2) {
                    Text(l10n(title)).foregroundStyle(Tone.text)
                    Text(l10n(hint)).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                }
            }
        }
        .accessibilityIdentifier("channel.mode.\(mode.rawValue)")
    }

    private func save() async {
        guard let chosen, let write = ChannelModeWrite.Mode(rawValue: chosen.rawValue) else { return }
        let profile = app.currentProfile
        saving = true
        defer { saving = false }
        do {
            _ = try await app.api.call {
                try await AgentsAPI.agentsSetChannelMode(xHubProfile: profile, agentId: agent.id, platform: channel.platform, channelModeWrite: ChannelModeWrite(mode: write), apiConfiguration: $0)
            }
            dismiss()
        } catch {
            self.error = AgentToolErrors.describe(error, l10n)
        }
    }
}

/// «Reply header» (WhatsApp in «Message yourself»): the agent's name, or a typed title, shown as a reply
/// will start. There is no "no header": Hermes's bridge puts its own back.
struct ReplyHeaderSheet: View {
    let agent: Agent
    let channel: Channel
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var custom: Bool
    @State private var typed: String
    @State private var saving = false
    @State private var error: String?

    init(agent: Agent, channel: Channel) {
        self.agent = agent
        self.channel = channel
        let current = channel.link?.replyTitle
        let isCustom = ChannelRules.replyCustom(current: current, agentName: agent.name)
        _custom = State(initialValue: isCustom)
        _typed = State(initialValue: isCustom ? (current ?? "") : "")
    }

    var body: some View {
        let current = channel.link?.replyTitle
        let title = ChannelRules.replyTitle(custom: custom, agentName: agent.name, typed: typed)
        let usable = ChannelRules.replyUsable(title)
        NavigationStack {
            Form {
                Section {
                    Text(l10n("agents2.ch.reply.note")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
                    if current == nil { Text(l10n("agents2.ch.reply.hermes_now")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
                    if let error { NoticeView(text: error, tone: .danger) }
                }
                Section {
                    choice(false, agent.name.isEmpty ? l10n("agents2.ch.reply.agent_name_plain") : l10n("agents2.ch.reply.agent_name", ["name": agent.name]), "channel.reply.agent")
                    choice(true, l10n("agents2.ch.reply.custom"), "channel.reply.custom")
                    if custom {
                        TextField(l10n("agents2.ch.reply.custom_label"), text: Binding(get: { typed }, set: { typed = String($0.unicodeScalars.prefix(ChannelRules.replyTitleMax).map(Character.init)) }))
                            .accessibilityIdentifier("channel.reply.text")
                    }
                } footer: {
                    if custom { Text(l10n("agents2.ch.reply.custom_hint")) }
                }
                if usable {
                    Section(l10n("agents2.ch.reply.preview")) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(title).bold().contentDirection(of: title)
                            Text(ChannelRules.replyRule).foregroundStyle(Tone.textMuted)
                            Text(l10n("agents2.ch.reply.preview_body")).foregroundStyle(Tone.textMuted)
                        }
                        .font(.system(size: FontSize.sizeSm))
                        .accessibilityIdentifier("channel.reply.preview")
                    }
                }
                Section { Text(l10n("agents2.ch.reply.no_none")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted) }
            }
            .navigationTitle(l10n("agents2.ch.reply.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(l10n("agents2.ch.reply.save")) { Task { await save(title) } }
                            .disabled(!usable || (current != nil && title == current))
                            .accessibilityIdentifier("channel.reply.save")
                    }
                }
            }
        }
    }

    private func choice(_ isCustom: Bool, _ title: String, _ id: String) -> some View {
        Button {
            custom = isCustom
        } label: {
            HStack(spacing: Space.s3) {
                SelectionMark(chosen: custom == isCustom)
                Text(title).foregroundStyle(Tone.text)
            }
        }
        .accessibilityIdentifier(id)
    }

    private func save(_ title: String) async {
        let profile = app.currentProfile
        let write = custom ? ChannelReplyHeaderWrite(use: .custom, title: title) : ChannelReplyHeaderWrite(use: .agentName)
        saving = true
        defer { saving = false }
        do {
            _ = try await app.api.call {
                try await AgentsAPI.agentsSetChannelReplyHeader(xHubProfile: profile, agentId: agent.id, platform: channel.platform, channelReplyHeaderWrite: write, apiConfiguration: $0)
            }
            dismiss()
        } catch {
            self.error = AgentToolErrors.describe(error, l10n)
        }
    }
}

/// A platform's fields as Hermes reads them (the web's channel editor): what each field declared itself
/// to be decides where it goes; a secret reads as [stored] and leaving it keeps the one on file.
struct ChannelFieldsSheet: View {
    let agent: Agent
    let channel: Channel
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var draft: [String: JSONValue] = [:]
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(l10n("agents2.ch.editor_note")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    if let error { NoticeView(text: error, tone: .danger) }
                    if channel.fields.isEmpty { Text(l10n("agents2.ch.no_fields")).foregroundStyle(Tone.textMuted) }
                }
                Section {
                    ForEach(channel.fields, id: \.key) { field in
                        let value = draft[field.key] ?? field.value
                        let label = (app.language == .ar ? field.label.ar : field.label.en).isEmpty ? field.key : (app.language == .ar ? field.label.ar : field.label.en)
                        if field.kind == .toggle {
                            Toggle(label, isOn: Binding(get: { value == .bool(true) }, set: { draft[field.key] = .bool($0) }))
                                .accessibilityIdentifier("channel.field.\(field.key)")
                        } else {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(label).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                                let binding = Binding(get: { JSONCoding.string(value) }, set: { draft[field.key] = .string($0) })
                                if field.kind == .secret {
                                    SecretValueField(placeholder: field.hint ?? "", text: binding)
                                    Text(l10n("agents2.ch.secret_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                                } else {
                                    TextField(field.hint ?? "", text: binding).monoField()
                                }
                            }
                            .accessibilityIdentifier("channel.field.\(field.key)")
                        }
                    }
                }
            }
            .navigationTitle(channel.label)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(l10n("common.save")) { Task { await save() } }
                            .disabled(draft.isEmpty)
                            .accessibilityIdentifier("channel.fields.save")
                    }
                }
            }
        }
    }

    private func save() async {
        let profile = app.currentProfile, write = ChannelRules.write(channel.fields, draft: draft)
        saving = true
        defer { saving = false }
        do {
            _ = try await app.api.call { try await AgentsAPI.agentsUpdateChannel(xHubProfile: profile, agentId: agent.id, platform: channel.platform, channelWrite: write, apiConfiguration: $0) }
            dismiss()
        } catch {
            self.error = AgentToolErrors.describe(error, l10n)
        }
    }
}

/// «<Platform> settings»: every option Hermes has for the channel in this profile, in sections, each
/// saying what it does and what applies while it is unset. Changes are gathered and saved together:
/// every save restarts the profile's gateway, so one save is one restart.
struct ChannelSettingsSheet: View {
    let agent: Agent
    let platform: String
    let name: String
    let gateway: ChannelGateway?
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var options: [ChannelSetting]?
    @State private var draft: [String: JSONValue] = [:]
    @State private var saving = false
    @State private var saved = false
    @State private var error: String?

    var body: some View {
        let problems = ChannelSettingRules.problems(options ?? [], draft)
        NavigationStack {
            Form {
                Section {
                    Text(l10n(gateway?.applies == .now ? "agents2.chs.applies_now" : "agents2.chs.applies_restart"))
                        .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    if let error { NoticeView(text: error, tone: .danger) }
                    if saved && draft.isEmpty {
                        NoticeView(text: l10n(gateway?.applies == .now ? "agents2.chs.saved_now" : "agents2.chs.saved_restart"), tone: .success)
                            .accessibilityIdentifier("channel.settings.saved")
                    }
                    if options == nil && error == nil { ProgressView().frame(maxWidth: .infinity) }
                }
                ForEach(ChannelSettingRules.sections, id: \.self) { section in
                    let inSection = (options ?? []).filter { $0.section == section }
                    if !inSection.isEmpty {
                        Section {
                            if inSection.contains(where: \.shared) {
                                Text(l10n("agents2.chs.media_note")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            }
                            ForEach(inSection, id: \.key) { option in control(option, invalid: problems.contains(option.key)) }
                        } header: {
                            Text(words("section.\(section.rawValue)"))
                        }
                        .accessibilityIdentifier("channel.settings.\(section.rawValue)")
                    }
                }
            }
            .navigationTitle(platform == "telegram" ? l10n("agents2.chs.title") : l10n("agents2.chs.title_of", ["name": name]))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(draft.isEmpty ? l10n("common.close") : l10n("agents2.chs.discard")) {
                        if draft.isEmpty { dismiss() } else { draft = [:] }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(l10n("common.save")) { Task { await save() } }
                            .disabled(draft.isEmpty || !problems.isEmpty)
                            .accessibilityIdentifier("channel.settings.save")
                    }
                }
            }
            .task { await load() }
        }
    }

    /// The platform's own words for a key where they differ, the shared ones otherwise, the key itself last.
    private func words(_ key: String) -> String {
        let own = "agents2.chs.platform.\(platform).\(key)"
        let text = l10n(own)
        if text != own { return text }
        let shared = "agents2.chs.\(key)"
        let said = l10n(shared)
        return said == shared ? key : said
    }

    private func choiceWords(_ key: String, _ value: String) -> String {
        let k = "agents2.chs.choice.\(key).\(value)"
        let said = l10n(k)
        return said == k ? value : said
    }

    private func defaultText(_ option: ChannelSetting) -> String {
        switch ChannelSettingRules.default(option) {
        case .none: return l10n("agents2.chs.default_none")
        case .on(let on): return l10n("agents2.chs.default_is", ["value": l10n(on ? "agents2.chs.on" : "agents2.chs.off")])
        case .choice(let value): return l10n("agents2.chs.default_is", ["value": choiceWords(option.key, value)])
        case .text(let text): return l10n("agents2.chs.default_is", ["value": text])
        }
    }

    @ViewBuilder
    private func control(_ option: ChannelSetting, invalid: Bool) -> some View {
        let label = words("option.\(option.key).label")
        let helpKey = "option.\(option.key).help"
        let help = [words(helpKey) == helpKey ? nil : words(helpKey), defaultText(option)].compactMap { $0 }.joined(separator: "\n")
        VStack(alignment: .leading, spacing: Space.s1) {
            switch option.kind {
            case .toggle:
                Toggle(isOn: Binding(get: { ChannelSettingRules.effective(option, draft) == .bool(true) }, set: { draft[option.key] = .bool($0); saved = false })) {
                    Text(label)
                }
                .accessibilityIdentifier("channel.setting.\(option.key)")
            case .select:
                Picker(label, selection: Binding(get: { JSONCoding.string(ChannelSettingRules.effective(option, draft)) }, set: { draft[option.key] = .string($0); saved = false })) {
                    ForEach(option.choices ?? [], id: \.self) { choice in Text(choiceWords(option.key, choice)).tag(choice) }
                }
                .accessibilityIdentifier("channel.setting.\(option.key)")
            default:
                Text(label)
                let placeholder = option.kind == .list ? words("list_placeholder") : JSONCoding.string(option._default)
                TextField(placeholder, text: Binding(get: { ChannelSettingRules.shown(option, draft) }, set: { draft[option.key] = ChannelSettingRules.typed($0); saved = false }))
                    .keyboardType(option.kind == .number ? .numberPad : .default)
                    .monoField()
                    .accessibilityIdentifier("channel.setting.\(option.key)")
                if invalid { Text(l10n("agents2.chs.invalid")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger) }
            }
            Text(help).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            HStack {
                if option.shared { StatusPill(text: l10n("agents2.chs.shared"), kind: .warn) }
                if let current = ChannelSettingRules.current(option, draft), current != .null {
                    Button(l10n("agents2.chs.reset")) { draft[option.key] = .null; saved = false }
                        .buttonStyle(ChipButtonStyle(quiet: true))
                        .accessibilityIdentifier("channel.setting.\(option.key).reset")
                }
            }
        }
    }

    private func load() async {
        let profile = app.currentProfile
        do {
            options = try await app.api.call { try await AgentsAPI.agentsGetChannelSettings(xHubProfile: profile, agentId: agent.id, platform: platform, apiConfiguration: $0) }.options
            error = nil
        } catch {
            self.error = AgentToolErrors.describe(error, l10n)
        }
    }

    private func save() async {
        let profile = app.currentProfile, values = ChannelSettingRules.values(options ?? [], draft)
        saving = true
        defer { saving = false }
        do {
            options = try await app.api.call {
                try await AgentsAPI.agentsUpdateChannelSettings(xHubProfile: profile, agentId: agent.id, platform: platform,
                                                               channelSettingsWrite: ChannelSettingsWrite(values: values), apiConfiguration: $0)
            }.options
            draft = [:]
            saved = true
            error = nil
        } catch {
            self.error = AgentToolErrors.describe(error, l10n)
        }
    }
}

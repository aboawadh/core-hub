// An agent's Skills, as the web's page (apps batch 8): search and a chip for whose skills, the Core Hub
// library card, the skills by category with their switch, each skill opened to read (Hermes's own) or
// edit its SKILL.md; pin, restore an edited library skill and delete (after asking) from its menu; a new
// skill, and a pack imported from the phone's files. Android's AgentSkillsPage.kt is its twin.
import CoreHubClient
import SwiftUI
import UniformTypeIdentifiers

/// A skill read with its text, to show in a sheet.
private struct OpenSkill: Identifiable {
    let skill: Skill
    var id: String { skill.key }
}

struct AgentSkillsPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var query = ""
    @State private var filter: SkillRules.Filter = .all
    @State private var note: ToolNote?
    @State private var opened: OpenSkill?
    @State private var creating = false
    @State private var importing = false
    @State private var choosingFiles = false
    @State private var question: ToolQuestion?

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call { try await AgentsAPI.agentsListSkills(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }
        } content: { answer, reload in
            list(answer, reload)
                .refreshable { reload() }
                .toolbar {
                    ToolbarItemGroup(placement: .primaryAction) {
                        Button { choosingFiles = true } label: { LucideIcon(.download, size: 18) }
                            .accessibilityLabel(l10n("agents.skill.import"))
                            .accessibilityIdentifier("skills.import")
                            .disabled(importing)
                        Button { creating = true } label: { LucideIcon(.plus, size: 18) }
                            .accessibilityLabel(l10n("agents.skill.new"))
                            .accessibilityIdentifier("skills.new")
                    }
                }
                .fileImporter(isPresented: $choosingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                    guard case .success(let urls) = result, !urls.isEmpty else { return }
                    Task { await importPacks(urls, reload) }
                }
                .sheet(item: $opened) { open in editor(open.skill, reload) }
                .sheet(isPresented: $creating) {
                    NewSkillSheet { key, text in
                        let profile = app.currentProfile
                        _ = try await app.api.call {
                            try await AgentsAPI.agentsPutSkill(xHubProfile: profile, agentId: agent.id, skillKey: key, skillWrite: SkillWrite(content: text), apiConfiguration: $0)
                        }
                        reload()
                    }
                }
                .toolQuestion($question)
        }
    }

    private func list(_ answer: AgentsListSkills200Response, _ reload: @escaping () -> Void) -> some View {
        let shown = SkillRules.narrow(answer.categories, query: query, filter: filter)
        return List {
            HStack(spacing: Space.s2) {
                LucideIcon(.search, size: 16).foregroundStyle(Tone.textFaint)
                TextField(l10n("agents.skill.search"), text: $query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("skills.search")
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Space.s2) {
                    ForEach(SkillRules.Filter.allCases) { chip in
                        Button(filterLabel(chip)) { filter = chip }
                            .buttonStyle(ChipButtonStyle(quiet: filter != chip))
                            .accessibilityAddTraits(filter == chip ? .isSelected : [])
                            .accessibilityIdentifier("skills.filter.\(chip.rawValue)")
                    }
                }
            }
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            if importing { NoticeView(text: l10n("agents.skill.importing"), tone: .info) }
            if let note { NoticeView(text: note.text, tone: note.tone) }
            if let library = answer.library {
                SkillLibraryCard(library: library) { on in setLibrary(on, reload) }
            }
            if SkillRules.total(answer.categories) == 0 {
                EmptyStateView(icon: .sparkles, title: l10n("agents.skill.none"),
                               message: [l10n("agents.skill.none_body"), answer.home].compactMap { $0 }.joined(separator: "\n"))
                    .listRowBackground(Color.clear)
            } else if shown.isEmpty {
                EmptyStateView(icon: .search, title: l10n("kit.no_matches")).listRowBackground(Color.clear)
            }
            ForEach(shown, id: \.key) { category in
                Section {
                    ForEach(category.skills, id: \.key) { skill in
                        SkillRow(skill: skill, switched: { on in switchSkill(skill, on, reload) }, act: { action in act(action, skill, reload) })
                    }
                } header: {
                    Text(categoryTitle(category))
                }
            }
        }
    }

    @ViewBuilder
    private func editor(_ skill: Skill, _ reload: @escaping () -> Void) -> some View {
        if SkillRules.readOnly(skill) {
            SkillReader(skill: skill)
        } else {
            TextEditorSheet(
                title: skill.name, initial: skill.content ?? "", markdown: true,
                subtitle: l10n(skill.source == .library ? "agents.skill.library_editor_note" : "agents.skill.editor_note"),
                tag: "skill.editor",
                reload: { reopen(skill.key) }
            ) { text in
                let profile = app.currentProfile
                _ = try await app.api.call {
                    try await AgentsAPI.agentsPutSkill(xHubProfile: profile, agentId: agent.id, skillKey: skill.key, skillWrite: SkillWrite(content: text), apiConfiguration: $0)
                }
                reload()
            }
        }
    }

    private func filterLabel(_ filter: SkillRules.Filter) -> String {
        switch filter {
        case .all: return l10n("kit.filter_all")
        case .yours: return l10n("agents.skill.filter_yours")
        case .library: return l10n("agents.skill.filter_library")
        case .builtin: return l10n("agents.skill.filter_builtin")
        }
    }

    private func categoryTitle(_ category: SkillCategory) -> String {
        if category.key == "user" { return l10n("agents.skill.category_user") }
        if category.key == SkillRules.libraryCategory { return l10n("agents.skill.library") }
        return category.name
    }

    private func switchSkill(_ skill: Skill, _ on: Bool, _ reload: @escaping () -> Void) {
        let id = agent.id
        Task {
            await run(reload) { profile in
                _ = try await app.api.call { try await AgentsAPI.agentsUpdateSkill(xHubProfile: profile, agentId: id, skillKey: skill.key, skillPatch: SkillPatch(enabled: on), apiConfiguration: $0) }
            }
        }
    }

    private func setLibrary(_ on: Bool, _ reload: @escaping () -> Void) {
        let id = agent.id
        let change = {
            Task {
                await run(reload) { profile in
                    _ = try await app.api.call { try await AgentsAPI.agentsUpdateSkillLibrary(xHubProfile: profile, agentId: id, skillLibraryPatch: SkillLibraryPatch(enabled: on), apiConfiguration: $0) }
                }
            }
        }
        if on {
            change()
        } else {
            question = ToolQuestion(title: l10n("agents.skill.library_off_title"), body: l10n("agents.skill.library_off_body"),
                                    confirm: l10n("agents.skill.library_off_confirm")) { change() }
        }
    }

    private func act(_ action: SkillRules.Action, _ skill: Skill, _ reload: @escaping () -> Void) {
        let id = agent.id
        switch action {
        case .open:
            reopen(skill.key)
        case .pin, .unpin:
            Task {
                await run(reload) { profile in
                    _ = try await app.api.call { try await AgentsAPI.agentsUpdateSkill(xHubProfile: profile, agentId: id, skillKey: skill.key, skillPatch: SkillPatch(pinned: action == .pin), apiConfiguration: $0) }
                }
            }
        case .restore:
            let done = l10n("agents.skill.restored", ["name": skill.name])
            question = ToolQuestion(title: l10n("agents.skill.restore_title", ["name": skill.name]), body: l10n("agents.skill.restore_body"),
                                    confirm: l10n("agents.skill.restore"), destructive: false) {
                Task {
                    await run(reload, done: done) { profile in
                        _ = try await app.api.call { try await AgentsAPI.agentsRestoreSkill(xHubProfile: profile, agentId: id, skillKey: skill.key, apiConfiguration: $0) }
                    }
                }
            }
        case .delete:
            question = ToolQuestion(title: l10n("agents.skill.delete_title", ["name": skill.name]), body: l10n("agents.skill.delete_body"),
                                    confirm: l10n("agents.skill.delete")) {
                Task {
                    await run(reload) { profile in
                        try await app.api.call { try await AgentsAPI.agentsDeleteSkill(xHubProfile: profile, agentId: id, skillKey: skill.key, apiConfiguration: $0) }
                    }
                }
            }
        }
    }

    /// Reads the skill with its text and opens it (again, after a save refused as changed elsewhere).
    private func reopen(_ key: String) {
        let id = agent.id
        Task {
            if opened != nil {
                opened = nil
                try? await Task.sleep(for: .milliseconds(400))
            }
            let profile = app.currentProfile
            do {
                let skill = try await app.api.call { try await AgentsAPI.agentsGetSkill(xHubProfile: profile, agentId: id, skillKey: key, apiConfiguration: $0) }
                opened = OpenSkill(skill: skill)
            } catch {
                note = ToolNote(text: AgentToolErrors.describe(error, l10n), tone: .danger)
            }
        }
    }

    /// One change, then the list read again; its refusal (or `done`) on the line above the list.
    private func run(_ reload: @escaping () -> Void, done: String? = nil, _ change: @escaping (String) async throws -> Void) async {
        do {
            try await change(app.currentProfile)
            note = done.map { ToolNote(text: $0) }
        } catch {
            note = ToolNote(text: AgentToolErrors.describe(error, l10n), tone: .danger)
        }
        reload()
    }

    /// Uploads the packs (`purpose: skill`), installs them, and deletes the uploads whatever happened (web `useImportSkills`).
    private func importPacks(_ urls: [URL], _ reload: @escaping () -> Void) async {
        guard urls.allSatisfy({ SkillRules.importable($0.lastPathComponent) }) else {
            note = ToolNote(text: l10n("agents.skill.import_not_pack"), tone: .danger)
            return
        }
        importing = true
        defer { importing = false }
        let profile = app.currentProfile
        let id = agent.id
        var ids: [String] = []
        do {
            for url in urls {
                let copy = try SkillPacks.copy(url)
                defer { try? FileManager.default.removeItem(at: copy.deletingLastPathComponent()) }
                let attachment = try await app.api.call {
                    try await SessionsAPI.sessionsUploadAttachment(xHubProfile: profile, file: copy, purpose: .skill, apiConfiguration: $0)
                }
                ids.append(attachment.id)
            }
            let chosen = ids
            let imported = try await app.api.call {
                try await AgentsAPI.agentsImportSkills(xHubProfile: profile, agentId: id, skillImport: SkillImport(attachmentIds: chosen), apiConfiguration: $0)
            }.items
            note = ToolNote(text: l10n("agents.skill.imported", ["count": "\(imported.count)", "names": imported.map(\.name).joined(separator: ", ")]))
        } catch {
            note = ToolNote(text: AgentToolErrors.describe(error, l10n), tone: .danger)
        }
        for attachmentID in ids {
            _ = try? await app.api.call { try await SessionsAPI.sessionsDeleteAttachment(xHubProfile: profile, attachmentId: attachmentID, apiConfiguration: $0) }
        }
        reload()
    }
}

enum SkillPacks {
    /// A chosen file (security-scoped) copied into a folder of its own, under its own name, to upload.
    static func copy(_ url: URL) throws -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("skill-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = folder.appendingPathComponent(url.lastPathComponent)
        try FileManager.default.copyItem(at: url, to: target)
        return target
    }
}

/// One skill: its switch, its name with its marks, its description; tap opens it, the menu does the rest.
struct SkillRow: View {
    let skill: Skill
    let switched: (Bool) -> Void
    let act: (SkillRules.Action) -> Void
    @Environment(\.l10n) private var l10n

    var body: some View {
        HStack(spacing: Space.s3) {
            Button { act(.open) } label: {
                VStack(alignment: .leading, spacing: 2) {
                    FlowLayout(spacing: Space.s1) {
                        Text(skill.name).font(.system(size: FontSize.sizeMd, weight: .medium)).foregroundStyle(Tone.text)
                        if skill.pinned { LucideIcon(.pin, size: 13).foregroundStyle(Tone.textMuted).accessibilityLabel(l10n("agents.skill.pinned")) }
                        if SkillRules.broken(skill) { StatusPill(text: l10n("agents.skill.broken"), kind: .warn) }
                        if SkillRules.readOnly(skill) { StatusPill(text: l10n("agents.skill.builtin")) }
                        if skill.source == .library { StatusPill(text: l10n("agents.skill.library"), kind: .good) }
                        if SkillRules.edited(skill) { StatusPill(text: l10n("agents.skill.edited"), kind: .warn) }
                    }
                    if let description = skill.description, !description.isEmpty, !SkillRules.broken(skill) {
                        Text(description).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2)
                            .contentDirection(of: description)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Toggle(l10n("agents.skill.enabled"), isOn: Binding(get: { skill.enabled }, set: { switched($0) }))
                .labelsHidden()
                .accessibilityIdentifier("skill.\(skill.key).switch")
            Menu {
                menuItems
            } label: {
                LucideIcon(.ellipsis, size: 16).foregroundStyle(Tone.textMuted).frame(width: 32, height: 32).contentShape(Rectangle())
            }
            .accessibilityLabel(l10n("kit.more_actions"))
            .accessibilityIdentifier("skill.\(skill.key).more")
        }
        .contextMenu { menuItems }
        .accessibilityIdentifier("skill.\(skill.key)")
    }

    @ViewBuilder
    private var menuItems: some View {
        ForEach(SkillRules.actions(skill), id: \.self) { action in
            Button(role: action == .delete ? .destructive : nil) { act(action) } label: { label(action) }
        }
    }

    private func label(_ action: SkillRules.Action) -> LucideLabel {
        switch action {
        case .open: return LucideLabel(l10n("agents.skill.open"), icon: .fileText)
        case .pin: return LucideLabel(l10n("agents.skill.pin"), icon: .pin)
        case .unpin: return LucideLabel(l10n("agents.skill.unpin"), icon: .pinOff)
        case .restore: return LucideLabel(l10n("agents.skill.restore"), icon: .rotateCcw)
        case .delete: return LucideLabel(l10n("agents.skill.delete"), icon: .trash)
        }
    }
}

/// Core Hub's library in this profile: how many are here, how many were edited, Install, and the switch.
struct SkillLibraryCard: View {
    let library: SkillLibrary
    let switched: (Bool) -> Void
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            HStack(spacing: Space.s2) {
                LucideIcon(.sparkles, size: 18).foregroundStyle(Tone.accent)
                Text(l10n("agents.skill.library")).font(.system(size: FontSize.sizeMd, weight: .semibold))
                Spacer(minLength: Space.s1)
                if library.edited > 0 { StatusPill(text: l10n("agents.skill.edited_count", ["count": "\(library.edited)"]), kind: .warn) }
                Toggle(l10n("agents.skill.library_switch"), isOn: Binding(get: { library.enabled }, set: { switched($0) }))
                    .labelsHidden()
                    .accessibilityIdentifier("skills.library.switch")
            }
            Text(line).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textMuted)
            if SkillRules.libraryMissing(library) {
                Button(l10n("agents.skill.library_install")) { switched(true) }
                    .buttonStyle(ChipButtonStyle())
                    .accessibilityIdentifier("skills.library.install")
            }
        }
        .padding(.vertical, Space.s1)
        .accessibilityIdentifier("skills.library")
    }

    private var line: String {
        switch SkillRules.libraryLine(library) {
        case .on: return l10n("agents.skill.library_on", ["installed": "\(library.installed)", "available": "\(library.available)"])
        case .none: return l10n("agents.skill.library_none", ["available": "\(library.available)"])
        case .off: return l10n("agents.skill.library_off")
        }
    }
}

/// One of Hermes's own skills, read (it is not edited here).
private struct SkillReader: View {
    let skill: Skill
    @Environment(\.dismiss) private var dismiss
    @Environment(\.l10n) private var l10n

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Space.s3) {
                    NoticeView(text: l10n("agents.skill.builtin_hint"), tone: .info)
                    MarkdownView(text: skill.content ?? "", foreground: Tone.text)
                        .environment(\.layoutDirection, ContentDirection.of(skill.content ?? "") ?? .leftToRight)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(Space.s4)
            }
            .navigationTitle(skill.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(l10n("agents.skill.close")) { dismiss() } }
            }
            .accessibilityIdentifier("skill.view")
        }
    }
}

/// A new skill: its key (the folder's name) and its SKILL.md, starting from the front matter Hermes needs.
private struct NewSkillSheet: View {
    let save: (String, String) async throws -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.l10n) private var l10n
    @State private var key = ""
    @State private var text = SkillRules.template
    @State private var saving = false
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Space.s2) {
                Text(l10n("agents.skill.editor_note")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                if let failure { NoticeView(text: failure, tone: .danger) }
                TextField(l10n("agents.skill.key"), text: $key, prompt: Text(verbatim: "my-skill"))
                    .font(.system(size: FontSize.sizeSm, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .environment(\.layoutDirection, .leftToRight)
                    .padding(Space.s2)
                    .background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.md))
                    .accessibilityIdentifier("skill.new.key")
                if !key.isEmpty, !SkillRules.validKey(key) {
                    Text(l10n("agents.skill.key_hint")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
                }
                DocumentEditor(text: $text, markdown: false, identifier: "skill.new.text")
            }
            .padding(Space.s4)
            .navigationTitle(l10n("agents.skill.new"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving {
                        ProgressView()
                    } else {
                        Button(l10n("common.save")) { Task { await submit() } }
                            .disabled(!SkillRules.validKey(key))
                            .accessibilityIdentifier("skill.new.save")
                    }
                }
            }
        }
    }

    private func submit() async {
        saving = true
        defer { saving = false }
        do {
            try await save(key, text)
            dismiss()
        } catch {
            failure = AgentToolErrors.describe(error, l10n)
        }
    }
}

extension PhonePage {
    static let agentSkills = PhonePage(.agentSkills) { context in
        if let agent = context.agent { AgentSkillsPage(agent: agent) }
    }
}

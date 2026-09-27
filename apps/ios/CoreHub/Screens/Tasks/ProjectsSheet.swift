// A profile's projects on the phone (Tasks II, batch 5): the list with how many tasks each holds,
// a new one, and each one's name, state (active, paused, archived) and git repository — the web's
// project settings (packages/web/src/tasks/ProjectDialog.tsx) plus the contract's create, archive
// and delete. Projects belong to the profile the selector is on, as on the web. Deleting a project
// deletes its tasks, so the question says how many. The rules are ProjectRules
// (TaskListsRules.swift); Android's twin is TaskProjects.kt.
import CoreHubClient
import SwiftUI

struct ProjectsSheet: View {
    /// The board reads again after anything changed here.
    let changed: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @Environment(\.dismiss) private var dismiss
    @State private var active: [Project]?
    @State private var archived: [Project] = []
    @State private var failure: String?
    @State private var editing: Editing?
    @State private var deleting: Project?

    private enum Editing: Identifiable {
        case new
        case project(Project)
        var id: String {
            switch self {
            case .new: return "new"
            case .project(let project): return project.id
            }
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if let failure { Section { NoticeView(text: failure, tone: .danger) } }
                if let active {
                    Section {
                        if active.isEmpty {
                            Text(l10n("tasks.projects.none")).font(.system(size: FontSize.sizeSm)).foregroundStyle(Tone.textFaint)
                        }
                        ForEach(active, id: \.id) { row($0) }
                    } footer: {
                        Text(l10n("tasks.projects.hint"))
                    }
                    if !archived.isEmpty {
                        Section(l10n("tasks.projects.archived")) {
                            ForEach(archived, id: \.id) { row($0) }
                        }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(app.enterableProfiles.count > 1 ? l10n("tasks.projects.title_in", ["name": app.profileName(app.currentProfile)]) : l10n("tasks.projects.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(l10n("common.close")) { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button { editing = .new } label: { LucideIcon(.plus, size: 20) }
                        .accessibilityLabel(l10n("tasks.projects.new"))
                        .accessibilityIdentifier("projects.new")
                }
            }
            .refreshable { await load() }
            .task { await load() }
            .sheet(item: $editing) { which in form(which) }
            .alert(
                deleting.map { l10n("kit.delete_confirm", ["name": $0.name]) } ?? "",
                isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })
            ) {
                Button(l10n("common.cancel"), role: .cancel) { deleting = nil }
                Button(l10n("kit.delete"), role: .destructive) {
                    if let project = deleting {
                        deleting = nil
                        Task { await delete(project) }
                    }
                }
                .accessibilityIdentifier("dialog.confirm")
            } message: {
                Text(l10n("tasks.projects.delete_body", ["count": String(deleting?.counts.total ?? 0)]))
            }
        }
        .accessibilityIdentifier("projects.sheet")
    }

    private func row(_ project: Project) -> some View {
        Button { editing = .project(project) } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(project.name).foregroundStyle(Tone.text).contentDirection(of: project.name)
                    Spacer(minLength: Space.s2)
                    if project.status != .active {
                        StatusPill(text: l10n("tasks.projects.status_\(project.status.rawValue)"), kind: project.status == .paused ? .warn : .neutral)
                    }
                }
                Text(l10n("tasks.projects.count", ["count": String(project.counts.total)]))
                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                if let path = project.workingDir {
                    Text(path).font(.system(size: FontSize.sizeXs, design: .monospaced)).foregroundStyle(Tone.textFaint)
                        .environment(\.layoutDirection, .leftToRight).lineLimit(1).truncationMode(.middle)
                }
            }
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { deleting = project } label: { LucideLabel(l10n("kit.delete"), icon: .trash) }
                .accessibilityIdentifier("project.delete")
            Button { Task { await setStatus(project, ProjectRules.toggledArchive(project.status)) } } label: {
                LucideLabel(l10n(project.status == .archived ? "tasks.projects.restore" : "tasks.projects.archive"), icon: project.status == .archived ? .archiveRestore : .archive)
            }
            .tint(Tone.textMuted)
            .accessibilityIdentifier("project.archive")
        }
        .contextMenu {
            Button { editing = .project(project) } label: { LucideLabel(l10n("kit.edit"), icon: .pencil) }
            Button { Task { await setStatus(project, ProjectRules.toggledArchive(project.status)) } } label: {
                LucideLabel(l10n(project.status == .archived ? "tasks.projects.restore" : "tasks.projects.archive"), icon: .archive)
            }
            Button(role: .destructive) { deleting = project } label: { LucideLabel(l10n("kit.delete"), icon: .trash) }
        }
        .accessibilityIdentifier("project.\(project.id)")
    }

    private func form(_ which: Editing) -> some View {
        let profile = app.currentProfile
        var original: Project?
        if case .project(let project) = which { original = project }
        let initial = ProjectRules.values(original)
        var fields = [
            FormField(key: "name", label: l10n("tasks.projects.name"), required: true),
            FormField(key: "repository", label: l10n("tasks.projects.repository"), help: l10n("tasks.projects.repository_help"), mono: true),
            FormField(key: "branch", label: l10n("tasks.projects.branch"), help: l10n("tasks.projects.branch_help"), mono: true),
        ]
        if original != nil {
            fields.insert(FormField(key: "status", label: l10n("tasks.projects.status"), kind: .choice, required: true,
                                    options: ProjectRules.statuses.map { FormOption(value: $0.rawValue, label: l10n("tasks.projects.status_\($0.rawValue)")) }), at: 1)
        }
        return FormSheet(
            title: l10n(original == nil ? "tasks.projects.new" : "tasks.projects.edit"),
            fields: fields,
            initial: initial,
            saveTitle: original == nil ? l10n("tasks.form.create") : nil,
            tag: "project.form"
        ) { values in
            if let project = original {
                guard let write = ProjectRules.patch(from: initial, to: values) else { return }
                _ = try await app.api.call { try await TasksAPI.tasksUpdateProject(xHubProfile: profile, projectId: project.id, projectWrite: write, apiConfiguration: $0) }
            } else {
                let write = ProjectRules.create(values)
                _ = try await app.api.call { try await TasksAPI.tasksCreateProject(xHubProfile: profile, projectWrite: write, apiConfiguration: $0) }
            }
            await load()
            changed()
        }
    }

    private func load() async {
        let profile = app.currentProfile
        do {
            active = try await app.api.call { try await TasksAPI.tasksListProjects(xHubProfile: profile, apiConfiguration: $0) }.items
            archived = (try? await app.api.call { try await TasksAPI.tasksListProjects(xHubProfile: profile, status: .archived, apiConfiguration: $0) }.items) ?? []
            failure = nil
        } catch {
            if active == nil { active = [] }
            failure = HubFailure(error).describe(l10n)
        }
    }

    private func setStatus(_ project: Project, _ status: ProjectStatus) async {
        let profile = app.currentProfile
        do {
            _ = try await app.api.call { try await TasksAPI.tasksUpdateProject(xHubProfile: profile, projectId: project.id, projectWrite: ProjectWrite(status: status), apiConfiguration: $0) }
            failure = nil
        } catch {
            failure = HubFailure(error).describe(l10n)
        }
        await load()
        changed()
    }

    private func delete(_ project: Project) async {
        let profile = app.currentProfile
        do {
            try await app.api.call { try await TasksAPI.tasksDeleteProject(xHubProfile: profile, projectId: project.id, apiConfiguration: $0) }
            failure = nil
        } catch {
            failure = HubFailure(error).describe(l10n)
        }
        await load()
        changed()
    }
}

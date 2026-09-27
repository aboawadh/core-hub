// Settings → Files: the header (trail, the page's own actions, uploads, what the page has to say) and
// one entry's row. The page is FilesPage.swift; Android draws the same in FilesPage.kt.
import CoreHubClient
import SwiftUI

/// The trail, the page's actions, what is uploading, and anything the page has to say.
struct FilesHeader: View {
    let profileName: String
    let folder: String
    @Binding var sort: FilesRules.Sort
    let busy: Bool
    let problem: String?
    let uploads: [FilesUpload]
    let folderInfo: WorkspaceFolder?
    let open: (String) -> Void
    let uploadFiles: () -> Void
    let uploadPhotos: () -> Void
    let newFolder: () -> Void
    let newFile: () -> Void
    let shareZip: () -> Void
    let dismissProblem: () -> Void
    let dismissUpload: (UUID) -> Void
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Text(l10n("files.intro", ["a": profileName]))
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
            crumbs
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Space.s2) {
                    Menu {
                        Button(action: uploadFiles) { LucideLabel(l10n("files.upload_files"), icon: .file) }
                        Button(action: uploadPhotos) { LucideLabel(l10n("files.upload_photos"), icon: .image) }
                    } label: {
                        LucideLabel(l10n("files.upload"), icon: .upload)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("files.upload")
                    Button(action: newFolder) { LucideLabel(l10n("files.new_folder"), icon: .folderPlus) }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("files.new_folder")
                    Button(action: newFile) { LucideLabel(l10n("files.new_file"), icon: .filePlus) }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("files.new_file")
                    Menu {
                        Picker(l10n("files.sort"), selection: $sort) {
                            Text(l10n("files.sort_name")).tag(FilesRules.Sort.name)
                            Text(l10n("files.sort_newest")).tag(FilesRules.Sort.newest)
                            Text(l10n("files.sort_largest")).tag(FilesRules.Sort.largest)
                        }
                    } label: {
                        LucideIcon(.arrowUpDown, size: 16).frame(width: 32, height: 32)
                    }
                    .accessibilityLabel(l10n("files.sort"))
                    .accessibilityIdentifier("files.sort")
                    if busy {
                        ProgressView().frame(width: 32, height: 32)
                    } else {
                        Button(action: shareZip) { LucideIcon(.share2, size: 16).frame(width: 32, height: 32) }
                            .accessibilityLabel(l10n("files.share_zip"))
                            .accessibilityIdentifier("files.zip")
                    }
                }
                .controlSize(.small)
            }
            if let problem {
                HStack(alignment: .top, spacing: Space.s2) {
                    NoticeView(text: problem, tone: .danger)
                    Button(action: dismissProblem) { LucideIcon(.x, size: 14).frame(width: 28, height: 28) }
                        .accessibilityLabel(l10n("common.close"))
                }
                .accessibilityIdentifier("files.problem")
            }
            ForEach(uploads) { upload in uploadRow(upload) }
            if folderInfo?.truncated == true { NoticeView(text: l10n("files.truncated"), tone: .warning) }
            if let limits = folderInfo?.limits {
                Text(l10n("files.limits", [
                    "a": FilesRules.size(limits.maxUploadBytes), "b": FilesRules.size(limits.maxEditBytes), "c": FilesRules.size(limits.maxArchiveBytes),
                ]))
                .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textFaint)
            }
        }
        .padding(.horizontal, Space.s4)
        .padding(.vertical, Space.s2)
        .background(Tone.bg)
    }

    /// The folder trail: the profile's top folder, then each folder down to this one; «Up» when inside one.
    private var crumbs: some View {
        let trail = [(name: profileName, path: "")] + FilesRules.crumbs(folder)
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Space.s1) {
                if !folder.isEmpty {
                    Button { open(FilesRules.parent(folder)) } label: { LucideIcon(.arrowUp, size: 16).frame(width: 28, height: 28) }
                        .accessibilityLabel(l10n("files.up"))
                        .accessibilityIdentifier("files.up")
                }
                ForEach(Array(trail.enumerated()), id: \.offset) { index, crumb in
                    if index > 0 { LucideIcon(.chevronRight, size: 12).foregroundStyle(Tone.textFaint).flipsForRightToLeftLayoutDirection(true) }
                    let last = index == trail.count - 1
                    Button { open(crumb.path) } label: {
                        Text(crumb.name)
                            .font(.system(size: FontSize.sizeSm, weight: last ? .semibold : .regular))
                            .foregroundStyle(last ? Tone.text : Tone.accent)
                            .lineLimit(1)
                    }
                    .buttonStyle(.plain)
                    .disabled(last)
                    .accessibilityIdentifier("files.crumb.\(index)")
                }
            }
        }
        .accessibilityLabel(l10n("files.breadcrumb"))
    }

    private func uploadRow(_ upload: FilesUpload) -> some View {
        HStack(spacing: Space.s2) {
            LucideIcon(upload.error == nil ? .upload : .triangleAlert, size: 16)
                .foregroundStyle(upload.error == nil ? Tone.textMuted : Tone.danger)
            VStack(alignment: .leading, spacing: 2) {
                Text(upload.error == nil ? l10n("files.uploading", ["a": upload.name]) : upload.name)
                    .font(.system(size: FontSize.sizeSm)).lineLimit(1).truncationMode(.middle)
                if let error = upload.error {
                    Text(error).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger).lineLimit(2)
                } else if let fraction = upload.fraction {
                    ProgressView(value: fraction).tint(Tone.accent)
                } else {
                    ProgressView().controlSize(.small)
                }
            }
            if upload.error != nil {
                Button { dismissUpload(upload.id) } label: { LucideIcon(.x, size: 14).frame(width: 28, height: 28) }
                    .accessibilityLabel(l10n("common.close"))
            }
        }
        .padding(Space.s2)
        .background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.md))
        .accessibilityIdentifier("files.uploading")
    }
}

/// One entry: its kind's icon, its name, its size and time (or how much of it has arrived), a badge for
/// a link, and «⋯» with its actions (the same as a long press).
struct FilesEntryRow: View {
    let entry: WorkspaceFileEntry
    let state: FileDownloads.State?
    let language: AppLanguage
    let actions: [RowAction]
    let open: () -> Void
    let cancel: () -> Void
    @Environment(\.l10n) private var l10n

    private var icon: Lucide {
        switch entry.kind {
        case .directory: return .folder
        case .link: return .link
        case .file:
            let type = FileKinds.mime(of: entry.name, entry.mime)
            if type.hasPrefix("image/") { return .image }
            if type.hasPrefix("audio/") { return .music }
            if type.hasPrefix("video/") { return .film }
            return entry.editable || FileKinds.openAs(entry.name, entry.mime) == .viewer ? .fileText : .file
        }
    }

    var body: some View {
        HStack(spacing: Space.s3) {
            Button(action: open) {
                HStack(spacing: Space.s3) {
                    LucideIcon(icon, size: 18)
                        .foregroundStyle(entry.kind == .directory ? Tone.accent : Tone.textMuted)
                        .frame(width: 34, height: 34)
                        .background(Tone.surface2, in: RoundedRectangle(cornerRadius: Radius.sm))
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: Space.s1) {
                            Text(entry.name)
                                .font(.system(size: FontSize.sizeSm, weight: .medium))
                                .foregroundStyle(entry.kind == .link ? Tone.textMuted : Tone.text)
                                .lineLimit(1).truncationMode(.middle)
                                .contentDirection(of: entry.name, fill: false)
                            if entry.kind == .link {
                                StatusPill(text: l10n("files.link_outside"), kind: .warn)
                            } else if entry.link {
                                StatusPill(text: l10n("files.link"))
                            }
                        }
                        detail
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if case .loading = state {
                Button(action: cancel) { LucideIcon(.x, size: 14).frame(width: 32, height: 32) }
                    .buttonStyle(.plain)
                    .accessibilityLabel(l10n("attachments.cancel"))
            } else if !actions.isEmpty {
                Menu {
                    ForEach(actions) { action in
                        Button(role: action.destructive ? .destructive : nil, action: action.run) { LucideLabel(action.title, icon: action.icon) }
                    }
                } label: {
                    LucideIcon(.ellipsis, size: 18).foregroundStyle(Tone.textMuted).frame(width: 32, height: 32)
                }
                .accessibilityLabel(l10n("kit.more_actions"))
                .accessibilityIdentifier("files.entry.\(entry.path).actions")
            }
        }
        .accessibilityIdentifier("files.entry.\(entry.path)")
    }

    @ViewBuilder
    private var detail: some View {
        switch state {
        case .loading(let fraction?):
            ProgressView(value: fraction).tint(Tone.accent)
        case .loading:
            Text(l10n("attachments.fetching")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
        default:
            if let text = FilesRules.detail(entry, language: language) {
                Text(text).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(1)
            }
        }
    }
}

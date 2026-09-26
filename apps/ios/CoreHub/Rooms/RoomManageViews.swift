// Managing a room on the phone, as on the web (DECISIONS §69): the room's «⋯» in the top bar
// (rename, settings, clear the context, archive, delete — or leave, for a member), the seat form
// (add an agent, edit its name, role, instructions and model), the seat's own actions (edit, make
// lead, remove), the summary, and the strip that says an agent passed the turn. The rules are in
// RoomManage.swift.
import CoreHubClient
import SwiftUI

/// Words and icons of the room actions, shared by the room's menu and the rooms list.
enum RoomActionLabel {
    static func title(_ action: RoomManage.Action, _ l10n: L10n) -> String {
        switch action {
        case .rename: return l10n("rooms.manage.rename")
        case .settings: return l10n("rooms.manage.settings")
        case .clearContext: return l10n("rooms.manage.clear")
        case .archive: return l10n("rooms.manage.archive")
        case .unarchive: return l10n("rooms.manage.unarchive")
        case .delete: return l10n("rooms.manage.delete")
        case .leave: return l10n("rooms.manage.leave")
        }
    }

    static func icon(_ action: RoomManage.Action) -> Lucide {
        switch action {
        case .rename: return .pencil
        case .settings: return .slidersHorizontal
        case .clearContext: return .rotateCcw
        case .archive: return .archive
        case .unarchive: return .archiveRestore
        case .delete: return .trash
        case .leave: return .logOut
        }
    }

    static func destructive(_ action: RoomManage.Action) -> Bool {
        action == .delete || action == .leave
    }

    /// The action as a menu item (a menu takes `Image(lucide:)`).
    static func label(_ action: RoomManage.Action, _ l10n: L10n) -> some View {
        Label { Text(title(action, l10n)) } icon: { Image(lucide: icon(action)) }
    }
}

/// The room's «⋯»: archive and bring back act at once; the rest open their sheet or question.
struct RoomMenu: View {
    let model: RoomModel
    @Binding var pending: RoomManage.Action?
    @Environment(\.l10n) private var l10n

    var body: some View {
        let actions = RoomManage.actions(canManage: model.state.canManage, archived: model.state.archived, owner: model.isOwner)
        if !actions.isEmpty {
            Menu {
                ForEach(actions, id: \.self) { action in
                    if RoomActionLabel.destructive(action) { Divider() }
                    Button(role: RoomActionLabel.destructive(action) ? .destructive : nil) {
                        switch action {
                        case .archive: Task { await model.change(RoomPatch(archived: true)) }
                        case .unarchive: Task { await model.change(RoomPatch(archived: false)) }
                        default: pending = action
                        }
                    } label: {
                        RoomActionLabel.label(action, l10n)
                    }
                    .accessibilityIdentifier("room.menu.\(action.rawValue)")
                }
            } label: {
                LucideIcon(.ellipsis, size: 20)
            }
            .accessibilityLabel(l10n("rooms.manage.more"))
            .accessibilityIdentifier("room.menu")
        }
    }
}

extension View {
    /// The sheets and questions the room's menu opens.
    func roomManagement(_ model: RoomModel, pending: Binding<RoomManage.Action?>) -> some View {
        modifier(RoomManagement(model: model, pending: pending))
    }
}

private struct RoomManagement: ViewModifier {
    let model: RoomModel
    @Binding var pending: RoomManage.Action?
    @Environment(\.l10n) private var l10n

    private func showing(_ action: RoomManage.Action) -> Binding<Bool> {
        Binding(get: { pending == action }, set: { if !$0, pending == action { pending = nil } })
    }

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: showing(.rename)) {
                RoomRenameSheet(current: model.state.name) { patch in try await model.update(patch) }
            }
            .sheet(isPresented: showing(.settings)) {
                FormSheet(
                    title: l10n("rooms.manage.settings"),
                    fields: RoomManage.settingsFields(l10n),
                    initial: RoomManage.settingsValues(canMentionAll: model.state.canMentionAll, handoff: model.state.handoff),
                    tag: "room.settings"
                ) { values in
                    if let patch = RoomManage.settingsPatch(values, canMentionAll: model.state.canMentionAll, handoff: model.state.handoff) {
                        try await model.update(patch)
                    }
                }
            }
            .alert(l10n("rooms.manage.clear_title"), isPresented: showing(.clearContext)) {
                Button(l10n("common.cancel"), role: .cancel) {}
                Button(l10n("rooms.manage.clear_confirm"), role: .destructive) { Task { await model.clearContext() } }
                    .accessibilityIdentifier("dialog.confirm")
            } message: {
                Text(l10n("rooms.manage.clear_body"))
            }
            .alert(l10n("rooms.manage.leave_title"), isPresented: showing(.leave)) {
                Button(l10n("common.cancel"), role: .cancel) {}
                Button(l10n("rooms.manage.leave"), role: .destructive) { Task { await model.leave() } }
                    .accessibilityIdentifier("dialog.confirm")
            } message: {
                Text(l10n("rooms.manage.leave_body", ["name": model.state.name]))
            }
            .confirmDelete(
                Binding<String?>(get: { pending == .delete ? model.state.name : nil }, set: { if $0 == nil, pending == .delete { pending = nil } }),
                name: { $0 },
                delete: { _ in try await model.delete() }
            )
    }
}

/// A room's new name.
struct RoomRenameSheet: View {
    let current: String
    let save: (RoomPatch) async throws -> Void
    @Environment(\.l10n) private var l10n

    var body: some View {
        FormSheet(
            title: l10n("rooms.manage.rename_title"),
            fields: [FormField(key: RoomManage.nameField, label: l10n("rooms.manage.name"), required: true)],
            initial: [RoomManage.nameField: current],
            tag: "room.rename"
        ) { values in
            if let patch = RoomManage.renamed(values[RoomManage.nameField] ?? "", from: current) { try await save(patch) }
        }
    }
}

/// A seat being added, or the seat being edited.
enum SeatEdit: Identifiable {
    case add
    case edit(Seat)

    var id: String {
        switch self {
        case .add: return "add"
        case .edit(let seat): return seat.id
        }
    }
}

/// The seat form: which agent (when adding), its name in the room, role, instructions and model.
struct SeatFormSheet: View {
    let model: RoomModel
    let edit: SeatEdit
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        let agents = app.agentDirectory.agents(model.profile)
        let seat: Seat? = { if case .edit(let seat) = edit { return seat } else { return nil } }()
        FormSheet(
            title: l10n(seat == nil ? "rooms.manage.seat_add_title" : "rooms.manage.seat_edit_title"),
            fields: RoomManage.seatFields(adding: seat == nil, agents: agents, l10n: l10n),
            initial: RoomManage.seatValues(seat, agents: agents),
            intro: l10n("rooms.manage.seat_intro"),
            saveTitle: seat == nil ? l10n("rooms.manage.seat_add") : nil,
            tag: "room.seat"
        ) { values in
            if let seat {
                try await model.updateSeat(seat, RoomManage.seatPatch(values))
            } else if let config = RoomManage.seatConfig(values, agents: agents) {
                try await model.addSeat(config)
            }
        }
    }
}

/// The strip over the composer: an agent passing the turn now, or a stopped pass with its one
/// more round.
struct HandoffStrip: View {
    let model: RoomModel
    @Environment(\.l10n) private var l10n

    var body: some View {
        if let line = RoomManage.handoffLine(active: model.state.activeChains, listed: model.state.handoffs, seats: model.state.seats) {
            switch line {
            case .active:
                Text(RoomManage.handoffText(line, l10n))
                    .font(.system(size: FontSize.sizeXs))
                    .foregroundStyle(Tone.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("room.handoff.active")
            case let .stopped(id, _, _, _, more):
                VStack(alignment: .leading, spacing: Space.s2) {
                    NoticeView(text: RoomManage.handoffText(line, l10n), tone: .warning)
                    if more, !model.state.archived {
                        Button(l10n("rooms.manage.handoff_continue")) { Task { await model.continueHandoff(id) } }
                            .buttonStyle(.bordered)
                            .accessibilityIdentifier("room.handoff.continue")
                    }
                }
                .accessibilityIdentifier("room.handoff.stopped")
            }
        }
    }
}

/// The room's summary: what agents are told of what came before. The manager may have it
/// rewritten now or write it by hand.
struct RoomMemorySection: View {
    let model: RoomModel
    @Environment(\.l10n) private var l10n
    @State private var editing = false

    var body: some View {
        let memory = model.state.memory
        Section {
            Text(memory?.summary ?? l10n("rooms.manage.memory_none"))
                .font(.system(size: FontSize.sizeSm))
                .foregroundStyle(memory?.summary == nil ? Tone.textMuted : Tone.text)
                .contentDirection(of: memory?.summary ?? "")
                .accessibilityIdentifier("room.memory.text")
            if let error = memory?.error, memory?.status == .error {
                Text(error).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.danger)
            }
            if model.state.canManage {
                Button {
                    Task { await model.refreshMemory() }
                } label: {
                    LucideLabel(l10n("rooms.manage.memory_refresh"), icon: .refreshCw)
                }
                .disabled(memory?.status == .summarizing)
                .accessibilityIdentifier("room.memory.refresh")
                Button {
                    editing = true
                } label: {
                    LucideLabel(l10n("rooms.manage.memory_edit"), icon: .pencil)
                }
                .accessibilityIdentifier("room.memory.edit")
            }
        } header: {
            HStack(spacing: Space.s2) {
                Text(l10n("rooms.manage.memory_title"))
                if memory?.status == .summarizing { StatusPill(text: l10n("rooms.manage.memory_working"), kind: .warn) }
                if memory?.status == .error { StatusPill(text: l10n("rooms.manage.memory_failed"), kind: .bad) }
            }
        }
        .sheet(isPresented: $editing) {
            TextEditorSheet(
                title: l10n("rooms.manage.memory_edit"), initial: memory?.summary ?? "", markdown: false, tag: "room.memory.editor"
            ) { text in
                try await model.putMemory(text)
            }
        }
    }
}

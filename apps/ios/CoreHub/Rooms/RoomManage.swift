// The rules of managing a room on the phone, as the web's Rooms page has them (DECISIONS §69):
// which actions a person may take on a room, the seat form (agent, name in the room, role,
// instructions, model), the room's settings (@all, passing the turn and how far), and what the
// handoff strip says. Plain functions, so RoomManageTests check them without drawing; Android's
// RoomManage.kt has the same rules.
import CoreHubClient
import Foundation

enum RoomManage {
    /// What can be done to a room from its menu or a long press on its row.
    enum Action: String, CaseIterable {
        case rename, settings, clearContext, archive, unarchive, delete, leave
    }

    /// The room's own menu: a manager renames, sets, clears, archives or deletes it; anyone else
    /// may only leave. The maker of a room never leaves it (they delete it instead).
    static func actions(canManage: Bool, archived: Bool, owner: Bool = false) -> [Action] {
        if canManage {
            return [.rename, .settings, .clearContext, archived ? .unarchive : .archive, .delete]
        }
        return owner ? [] : [.leave]
    }

    /// A long press on a row of the rooms list: the same, without the room's inner settings.
    static func rowActions(canManage: Bool, archived: Bool) -> [Action] {
        actions(canManage: canManage, archived: archived).filter { $0 != .settings && $0 != .clearContext }
    }

    // MARK: - Renaming

    static let nameField = "name"

    /// A room's new name, trimmed; nil when nothing is left or nothing changed.
    static func renamed(_ value: String, from current: String) -> RoomPatch? {
        let name = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= 120, name != current else { return nil }
        return RoomPatch(name: name)
    }

    // MARK: - Seats

    enum SeatKey {
        static let agent = "agent"
        static let name = "name"
        static let role = "role"
        static let instructions = "instructions"
        static let model = "model"
    }

    /// Agents a seat can be: installed and switched on (the web's `installedAgents`).
    static func seatable(_ agents: [Agent]) -> [Agent] {
        agents.filter { $0.enabled && ($0.status == .available || $0.status == .limited) }
    }

    /// The seat form. The agent is chosen only when adding: an existing seat keeps its agent
    /// (a different agent is a new seat).
    static func seatFields(adding: Bool, agents: [Agent], l10n: L10n) -> [FormField] {
        var fields: [FormField] = []
        if adding {
            fields.append(FormField(
                key: SeatKey.agent, label: l10n("rooms.manage.seat_agent"), kind: .choice, required: true,
                options: seatable(agents).map { FormOption(value: $0.id, label: $0.name) }
            ))
        }
        fields += [
            FormField(key: SeatKey.name, label: l10n("rooms.manage.seat_name"), required: !adding, help: adding ? l10n("rooms.manage.seat_name_add_hint") : l10n("rooms.manage.seat_name_hint")),
            FormField(key: SeatKey.role, label: l10n("rooms.manage.seat_role")),
            FormField(key: SeatKey.instructions, label: l10n("rooms.manage.seat_instructions"), kind: .multiline, help: l10n("rooms.manage.seat_instructions_hint")),
            FormField(key: SeatKey.model, label: l10n("rooms.manage.seat_model"), help: l10n("rooms.manage.seat_model_hint"), mono: true),
        ]
        return fields
    }

    static func seatValues(_ seat: Seat?, agents: [Agent]) -> [String: String] {
        guard let seat else {
            return [SeatKey.agent: seatable(agents).first?.id ?? ""]
        }
        return [
            SeatKey.name: seat.name,
            SeatKey.role: seat.description ?? "",
            SeatKey.instructions: seat.instructions ?? "",
            SeatKey.model: seat.model ?? "",
        ]
    }

    private static func trimmed(_ values: [String: String], _ key: String) -> String {
        (values[key] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// A new seat. Its name defaults to the agent's; empty role, instructions and model are left out
    /// (the model then is the agent's own).
    static func seatConfig(_ values: [String: String], agents: [Agent]) -> SeatConfig? {
        let agentID = trimmed(values, SeatKey.agent)
        guard let agent = agents.first(where: { $0.id == agentID }) else { return nil }
        var name = trimmed(values, SeatKey.name)
        if name.isEmpty { name = agent.name.trimmingCharacters(in: .whitespaces) }
        guard !name.isEmpty else { return nil }
        let role = trimmed(values, SeatKey.role)
        let instructions = trimmed(values, SeatKey.instructions)
        let model = trimmed(values, SeatKey.model)
        return SeatConfig(
            agentId: agent.id, name: name,
            description: role.isEmpty ? nil : role,
            model: model.isEmpty ? nil : model,
            instructions: instructions.isEmpty ? nil : instructions
        )
    }

    /// An edited seat, every field sent: an emptied role or instructions goes as "" (the hub keeps
    /// no text then); an emptied model is left out, since "" is not a model.
    static func seatPatch(_ values: [String: String]) -> SeatPatch {
        let name = trimmed(values, SeatKey.name)
        let model = trimmed(values, SeatKey.model)
        return SeatPatch(
            name: name.isEmpty ? nil : name,
            description: trimmed(values, SeatKey.role),
            model: model.isEmpty ? nil : model,
            instructions: trimmed(values, SeatKey.instructions)
        )
    }

    /// The seat's second line: its role and its model (or «the agent's own model»).
    static func seatLine(_ seat: Seat, defaultModel: String) -> String {
        [seat.description, seat.model ?? defaultModel]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }

    // MARK: - Settings

    enum SettingsKey {
        static let mentionAll = "mention_all"
        static let handoff = "handoff"
        static let maxDepth = "max_depth"
    }

    static func settingsFields(_ l10n: L10n) -> [FormField] {
        [
            FormField(key: SettingsKey.mentionAll, label: l10n("rooms.manage.settings_mention_all"), kind: .toggle),
            FormField(key: SettingsKey.handoff, label: l10n("rooms.manage.settings_handoff"), kind: .toggle, help: l10n("rooms.manage.settings_handoff_hint")),
            FormField(
                key: SettingsKey.maxDepth, label: l10n("rooms.manage.settings_max_depth"), kind: .number,
                help: l10n("rooms.manage.settings_max_depth_hint"), min: 1, max: 20, integer: true, mono: true
            ),
        ]
    }

    static func settingsValues(canMentionAll: Bool, handoff: HandoffPolicy?) -> [String: String] {
        [
            SettingsKey.mentionAll: canMentionAll ? "true" : "false",
            SettingsKey.handoff: (handoff?.enabled ?? true) ? "true" : "false",
            SettingsKey.maxDepth: handoff?.maxDepth.map(String.init) ?? "",
        ]
    }

    /// The settings to send. The passing-the-turn policy goes only when it changed, whole (`enabled`
    /// with `max_depth`, empty = no limit); nil when nothing changed at all.
    static func settingsPatch(_ values: [String: String], canMentionAll: Bool, handoff: HandoffPolicy?) -> RoomPatch? {
        let all = values[SettingsKey.mentionAll] == "true"
        let enabled = values[SettingsKey.handoff] == "true"
        let depthText = trimmed(values, SettingsKey.maxDepth)
        let depth = depthText.isEmpty ? nil : Int(depthText)
        let policy = HandoffPolicy(enabled: enabled, maxDepth: depth)
        let policyChanged = handoff.map { $0.enabled != enabled || $0.maxDepth != depth } ?? true
        let allChanged = all != canMentionAll
        guard policyChanged || allChanged else { return nil }
        return RoomPatch(handoff: policyChanged ? policy : nil, canMentionAll: allChanged ? all : nil)
    }

    // MARK: - Passing the turn

    enum HandoffLine: Equatable {
        /// A chain going on now: `from` passed to `to`, the `depth`th pass in a row.
        case active(from: String, to: String, depth: Int)
        /// The newest chain was stopped; `more` when a person may give it one more round.
        case stopped(id: String, reason: HandoffChain.StopReason, from: String, to: String, more: Bool)
    }

    /// What the strip over the composer says: the chain going on now, else the newest chain when
    /// the guard stopped it and its one more round is unused (an older stopped chain was overtaken
    /// by what the room did since).
    static func handoffLine(active: [HandoffChain], listed: [HandoffChain], seats: [Seat]) -> HandoffLine? {
        let name: (String) -> String = { id in seats.first { $0.id == id }?.name ?? "?" }
        if let chain = active.first(where: { $0.status == .active }) {
            return .active(from: name(chain.fromSeatId), to: name(chain.toSeatId), depth: chain.depth)
        }
        guard let newest = listed.max(by: { $0.updatedAt < $1.updatedAt }),
              newest.status == .stopped, !newest.continueUsed else { return nil }
        let reason = newest.stopReason ?? .interrupted
        return .stopped(id: newest.id, reason: reason, from: name(newest.fromSeatId), to: name(newest.toSeatId), more: reason != .interrupted)
    }

    static func handoffText(_ line: HandoffLine, _ l10n: L10n) -> String {
        switch line {
        case let .active(from, to, depth):
            return l10n("rooms.manage.handoff_active", ["from": from, "to": to, "depth": String(depth)])
        case let .stopped(_, reason, from, to, _):
            return l10n("rooms.manage.handoff_stopped_\(reason.rawValue)", ["from": from, "to": to])
        }
    }
}

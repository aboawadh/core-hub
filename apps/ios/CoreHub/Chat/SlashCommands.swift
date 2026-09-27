// The composer's `/` commands (decision §57), as on the web: typing `/` offers what this agent can
// be given and what the app does itself; a finished `/command words` runs it instead of being sent.
//
// - `hub`: this app, with what it already does — a new chat, a fork, archiving, the model, clearing
//   the screen. Every agent gets them.
// - `action`: the hub asks the agent (`sessions.compress`, `sessions.steerRun`), only with the
//   capability.
// - `message`: sent as the message it is; the agent reads the word itself (`/goal`, `/plan`,
//   `/learn`, `/skill <name>`), only with the capability.
//
// Anything else that starts with `/` is an ordinary message, sent as typed.
import CoreHubClient
import SwiftUI

enum SlashCommands {
    enum Kind: Equatable { case hub, action, message }
    enum Argument: Equatable { case none, optional, required }

    struct Command: Equatable, Identifiable {
        let name: String
        let kind: Kind
        let capability: AgentCapability?
        let argument: Argument
        var id: String { name }
    }

    /// In the order the list shows them: the agent's own first, then the app's.
    static let all: [Command] = [
        Command(name: "compress", kind: .action, capability: .compress, argument: .optional),
        Command(name: "steer", kind: .action, capability: .steer, argument: .required),
        Command(name: "skill", kind: .message, capability: .skillCommands, argument: .required),
        Command(name: "plan", kind: .message, capability: .plans, argument: .required),
        Command(name: "goal", kind: .message, capability: .goals, argument: .optional),
        Command(name: "learn", kind: .message, capability: .learn, argument: .optional),
        Command(name: "new", kind: .hub, capability: nil, argument: .none),
        Command(name: "fork", kind: .hub, capability: nil, argument: .none),
        Command(name: "archive", kind: .hub, capability: nil, argument: .none),
        Command(name: "model", kind: .hub, capability: nil, argument: .optional),
        Command(name: "clear-screen", kind: .hub, capability: nil, argument: .none),
    ]

    /// What this agent can be given: one without a capability never sees its command.
    static func available(_ capabilities: [AgentCapability]) -> [Command] {
        all.filter { $0.capability == nil || capabilities.contains($0.capability!) }
    }

    /// The word being typed after `/`, while the composer holds nothing else; nil otherwise.
    static func query(_ text: String) -> String? {
        guard text.hasPrefix("/") else { return nil }
        let rest = text.dropFirst()
        guard !rest.contains(where: { $0.isWhitespace || $0 == "/" }) else { return nil }
        return String(rest)
    }

    /// `/skill rev` → `rev` while a skill's name is being typed; nil otherwise.
    static func skillQuery(_ text: String) -> String? {
        guard text.hasPrefix("/skill"), let space = text.dropFirst(6).first, space == " " || space == "\t" else { return nil }
        let rest = text.dropFirst(6).drop { $0 == " " || $0 == "\t" }
        guard !rest.contains(where: \.isWhitespace) else { return nil }
        return String(rest)
    }

    /// A skill the menu offers after `/skill `: its key is what is typed.
    struct SkillChoice: Equatable, Identifiable {
        let key: String
        let name: String
        let description: String
        var id: String { key }
    }

    /// The agent's enabled skills.
    static func skills(_ categories: [SkillCategory]) -> [SkillChoice] {
        categories.flatMap(\.skills).filter(\.enabled).map { SkillChoice(key: $0.key, name: $0.name, description: $0.description ?? "") }
    }

    /// Skills whose key starts with the word first, then those whose key, name or words contain it.
    static func filterSkills(_ skills: [SkillChoice], _ query: String) -> [SkillChoice] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return skills }
        let starts = skills.filter { $0.key.lowercased().hasPrefix(q) }
        return starts + skills.filter { skill in
            !starts.contains(skill) && [skill.key, skill.name, skill.description].contains { $0.lowercased().contains(q) }
        }
    }

    /// Names that start with the word first, then names that contain it.
    static func filter(_ commands: [Command], _ query: String) -> [Command] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return commands }
        let starts = commands.filter { $0.name.hasPrefix(q) }
        return starts + commands.filter { !starts.contains($0) && $0.name.contains(q) }
    }

    /// The command a finished message is, among those offered, with its words; nil for anything else.
    static func parse(_ text: String, offered: [Command]) -> (command: Command, argument: String)? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("/") else { return nil }
        let body = trimmed.dropFirst()
        let name = String(body.prefix { $0.isLetter || $0 == "-" })
        guard !name.isEmpty, let command = offered.first(where: { $0.name == name }) else { return nil }
        let rest = body.dropFirst(name.count)
        guard rest.isEmpty || rest.first?.isWhitespace == true else { return nil }
        return (command, rest.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    /// A model named by `/model <words>`: its id or its label, case aside.
    static func model(named words: String, in options: [ChatControls.ModelOption]) -> String? {
        let needle = words.lowercased()
        return options.first { $0.value.lowercased() == needle || $0.label.lowercased() == needle }?.value
    }
}

enum SlashCommandRun {
    /// `/clear-screen`: what comes after the message the screen was cleared at.
    static func afterClear(_ messages: [Message], _ id: String?) -> [Message] {
        guard let id, let index = messages.firstIndex(where: { $0.id == id }) else { return messages }
        return Array(messages[(index + 1)...])
    }
}

/// The agent's skills after `/skill `: a tap puts the skill's key in the composer.
struct SkillMenu: View {
    let skills: [SlashCommands.SkillChoice]
    let loaded: Bool
    let pick: (SlashCommands.SkillChoice) -> Void
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(l10n("slash_commands.skills_title")).font(.system(size: FontSize.sizeXs, weight: .semibold)).foregroundStyle(Tone.textFaint)
                .padding(.horizontal, Space.s3).padding(.top, Space.s2)
            if !loaded {
                ProgressView().padding(Space.s3)
            } else if skills.isEmpty {
                Text(l10n("skill_picker.none")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).padding(Space.s3)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(skills) { skill in
                        Button {
                            pick(skill)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(skill.key).font(.system(size: FontSize.sizeSm, weight: .medium, design: .monospaced))
                                    .environment(\.layoutDirection, .leftToRight)
                                Text(skill.description.isEmpty ? skill.name : skill.description)
                                    .font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2)
                                    .contentDirection(of: skill.description.isEmpty ? skill.name : skill.description)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, Space.s3)
                            .padding(.vertical, Space.s2)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("skill.\(skill.key)")
                    }
                }
            }
            .frame(maxHeight: 220)
        }
        .background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).stroke(Tone.border, lineWidth: 1))
        .accessibilityIdentifier("chat.skills")
    }
}

/// The list over the composer while `/…` is typed: each command with what it does.
struct SlashMenu: View {
    let commands: [SlashCommands.Command]
    let pick: (SlashCommands.Command) -> Void
    @Environment(\.l10n) private var l10n

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(l10n("slash_commands.title")).font(.system(size: FontSize.sizeXs, weight: .semibold)).foregroundStyle(Tone.textFaint)
                .padding(.horizontal, Space.s3).padding(.top, Space.s2)
            if commands.isEmpty {
                Text(l10n("slash_commands.no_match")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                    .padding(Space.s3)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(commands) { command in
                        Button {
                            pick(command)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("/" + command.name).font(.system(size: FontSize.sizeSm, weight: .medium, design: .monospaced))
                                    .environment(\.layoutDirection, .leftToRight)
                                Text(l10n("slash_commands.describe.\(command.name)")).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, Space.s3)
                            .padding(.vertical, Space.s2)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("slash.\(command.name)")
                    }
                }
            }
            .frame(maxHeight: 220)
        }
        .background(Tone.surface, in: RoundedRectangle(cornerRadius: Radius.md, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Radius.md, style: .continuous).stroke(Tone.border, lineWidth: 1))
        .accessibilityIdentifier("chat.slash")
    }
}

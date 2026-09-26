// An agent's Skills: on and off.
import CoreHubClient
import SwiftUI

struct AgentSkillsPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var error: String?

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call { try await AgentsAPI.agentsListSkills(xHubProfile: profile, agentId: agent.id, apiConfiguration: $0) }
        } content: { list, reload in
            List {
                if let error { NoticeView(text: error, tone: .danger) }
                if list.categories.allSatisfy({ $0.skills.isEmpty }) {
                    EmptyRow(icon: .sparkles)
                }
                ForEach(list.categories, id: \.key) { category in
                    Section(category.name) {
                        ForEach(category.skills, id: \.key) { skill in
                            Toggle(isOn: Binding(get: { skill.enabled }, set: { value in
                                Task { await set(skill, enabled: value, reload: reload) }
                            })) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(skill.name)
                                    if let description = skill.description {
                                        Text(description).font(.system(size: FontSize.sizeXs)).foregroundStyle(Tone.textMuted).lineLimit(2)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .refreshable { reload() }
        }
    }

    private func set(_ skill: Skill, enabled: Bool, reload: @escaping () -> Void) async {
        let profile = app.currentProfile
        do {
            _ = try await app.api.call {
                try await AgentsAPI.agentsUpdateSkill(xHubProfile: profile, agentId: agent.id, skillKey: skill.key, skillPatch: SkillPatch(enabled: enabled), apiConfiguration: $0)
            }
            error = nil
        } catch {
            self.error = HubFailure(error).describe(l10n)
        }
        reload()
    }
}

extension PhonePage {
    static let agentSkills = PhonePage(.agentSkills) { context in
        if let agent = context.agent { AgentSkillsPage(agent: agent) }
    }
}

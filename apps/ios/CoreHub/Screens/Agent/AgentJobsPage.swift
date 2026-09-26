// An agent's Jobs: its schedules.
import CoreHubClient
import SwiftUI

/// The agent's jobs: the Schedules list narrowed to this agent and profile (§٤ rule 8).
struct AgentJobsPage: View {
    let agent: Agent
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n

    var body: some View {
        AsyncContent(key: app.currentProfile) {
            let profile = app.currentProfile
            return try await app.api.call {
                try await SchedulesAPI.schedulesList(profile: profile, agentId: agent.id, apiConfiguration: $0)
            }.items
        } content: { schedules, reload in
            List {
                if schedules.isEmpty { EmptyRow(icon: .rotateCcwClock) }
                ForEach(schedules, id: \.id) { schedule in
                    ScheduleRow(schedule: schedule, showProfile: false, changed: reload)
                }
            }
            .refreshable { reload() }
        }
    }
}

extension PhonePage {
    static let agentJobs = PhonePage(.agentJobs) { context in
        if let agent = context.agent { AgentJobsPage(agent: agent) }
    }
}

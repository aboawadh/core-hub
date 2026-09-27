// What the Skills, Memory and Plugins pages and the agent cards draw and do alike (apps batch 8):
// a job followed to its end, the budget bar, a line after an action, and a confirm with its own words.
import CoreHubClient
import SwiftUI

enum AgentJobs {
    /// Reads the job again every `every` until it ends, telling `update` each time (web `useJob`). A read
    /// that fails is tried again; cancelling the task ends the wait.
    static func follow(_ id: String, profile: String, api: HubAPI, every: Duration = .milliseconds(1500),
                       update: @escaping @MainActor (Job) -> Void) async throws -> Job {
        while true {
            try Task.checkCancellation()
            if let job = try? await api.call({ try await JobsAPI.jobsGet(xHubProfile: profile, jobId: id, apiConfiguration: $0) }) {
                await update(job)
                if AgentCardRules.terminal(job.status) { return job }
            }
            try await Task.sleep(for: every)
        }
    }
}

/// A line after an action: our words in a tone.
struct ToolNote: Equatable {
    var text: String
    var tone: NoticeView.Kind = .success
}

/// «1,234 of 2,200 characters» as a thin bar and a line: warning from 80 %, danger past the limit.
struct BudgetMeter: View {
    let count: Int
    let limit: Int
    var identifier = "memory.budget"
    @Environment(\.l10n) private var l10n

    var body: some View {
        let tone = MemoryRules.tone(count, limit: limit)
        let color: Color = tone == .danger ? Tone.danger : tone == .warning ? Tone.warningSoftText : Tone.accent
        VStack(alignment: .leading, spacing: Space.s1) {
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(Tone.surface2)
                    Capsule().fill(color)
                        .frame(width: geometry.size.width * min(1, max(0.01, Double(count) / Double(max(limit, 1)))))
                }
            }
            .frame(height: 4)
            .environment(\.layoutDirection, .leftToRight)
            Text(l10n(tone == .danger ? "agents.memory.budget_over" : "agents.memory.budget",
                      ["count": count.formatted(.number.locale(Locale(identifier: "en_US_POSIX"))),
                       "limit": limit.formatted(.number.locale(Locale(identifier: "en_US_POSIX")))]))
                .font(.system(size: FontSize.sizeXs))
                .foregroundStyle(tone == .danger ? Tone.danger : Tone.textMuted)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(identifier)
    }
}

/// A question before an action, in the action's own words (a title, what follows, the button).
struct ToolQuestion: Identifiable {
    let id = UUID()
    let title: String
    let body: String?
    let confirm: String
    var destructive = true
    let run: () -> Void
}

extension View {
    func toolQuestion(_ question: Binding<ToolQuestion?>) -> some View {
        modifier(ToolQuestionModifier(question: question))
    }
}

private struct ToolQuestionModifier: ViewModifier {
    @Binding var question: ToolQuestion?
    @Environment(\.l10n) private var l10n

    func body(content: Content) -> some View {
        content.alert(
            question?.title ?? "",
            isPresented: Binding(get: { question != nil }, set: { if !$0 { question = nil } }),
            presenting: question
        ) { asked in
            Button(l10n("common.cancel"), role: .cancel) { question = nil }
            Button(asked.confirm, role: asked.destructive ? .destructive : nil) {
                question = nil
                asked.run()
            }
            .accessibilityIdentifier("dialog.confirm")
        } message: { asked in
            if let body = asked.body { Text(body) }
        }
    }
}

// Workflows, a page of its own since 2026-09-28 (DECISIONS §126, phones §128): until then the
// second half of Schedules. Every workflow of every profile the person may enter (profileScope
// .alwaysAll), a new one made in the selector's profile, each opened to run, edit and follow its
// runs (Workflows.swift, WorkflowEditor.swift). A waiting step, a notice or a link about one run
// opens that run here (`MainContent.workflowRun`).
import CoreHubClient
import SwiftUI

/// One workflow run to open, in its own profile.
struct WorkflowRunRef: Hashable, Identifiable {
    let runID: String
    let profile: String
    var id: String { runID }
}

struct WorkflowsScreen: View {
    /// A run to open on arrival (a pending approval, a notice, a link).
    var openRun: WorkflowRunRef? = nil
    @Environment(AppModel.self) private var app
    @Environment(\.l10n) private var l10n
    @State private var refresh = 0
    @State private var newWorkflow = false
    @State private var run: WorkflowRunRef?
    @State private var tookRun = false

    var body: some View {
        WorkflowsList(refresh: refresh)
            .navigationTitle(l10n("nav.workflows"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        newWorkflow = true
                    } label: {
                        LucideIcon(.plus, size: 20)
                    }
                    .accessibilityLabel(l10n("workflow_editor.new"))
                    .accessibilityIdentifier("workflows.new")
                }
            }
            .navigationDestination(isPresented: $newWorkflow) {
                WorkflowEditorPage(original: nil, profile: app.currentProfile) { _ in refresh += 1 }
            }
            .navigationDestination(item: $run) { ref in
                WorkflowRunLoader(ref: ref)
            }
            .task {
                guard !tookRun, let openRun else { return }
                tookRun = true
                run = openRun
            }
            // Workflows changed by an agent or another device (`/rt/schedules`).
            .liveReload("/rt/schedules", events: ["workflow."]) {
                refresh += 1
            }
            .accessibilityIdentifier("screen.workflows")
    }
}

/// A run known only by its id: the run is read, then its workflow (for the steps' titles), then
/// shown as the run view shows any run.
struct WorkflowRunLoader: View {
    let ref: WorkflowRunRef
    @Environment(AppModel.self) private var app

    var body: some View {
        AsyncContent(key: ref.runID) {
            let profile = ref.profile, runID = ref.runID
            let run = try await app.api.call {
                try await SchedulesAPI.schedulesGetWorkflowRun(xHubProfile: profile, workflowRunId: runID, apiConfiguration: $0)
            }
            let workflowID = run.workflowId
            return try await app.api.call {
                try await SchedulesAPI.schedulesGetWorkflow(xHubProfile: profile, workflowId: workflowID, apiConfiguration: $0)
            }
        } content: { workflow, _ in
            WorkflowRunView(workflow: workflow, model: WorkflowRunModel(app: app, profile: ref.profile, runID: ref.runID))
        }
    }
}

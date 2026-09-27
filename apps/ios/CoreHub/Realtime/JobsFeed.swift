// `/rt/jobs` (events/README.md): every job of the profile — an agent installing, a skill being
// imported, a channel pairing, a restart, an export — as it moves, and `agent.updated` when an
// agent's registry entry changed. A page following a job (AgentJobs.follow) wakes on its events
// instead of waiting for the next poll, and the Agents page reads again when an agent changed —
// the web's `useJobs`. The poll stays as the fallback for a dropped connection.
import CoreHubClient
import Foundation
import Observation

@MainActor
@Observable
final class JobsFeed {
    static let shared = JobsFeed()

    /// The latest state of each job heard, by id.
    private(set) var jobs: [String: Job] = [:]
    /// Bumped on every `agent.updated`: the Agents page reads again.
    private(set) var agentsRevision = 0

    @ObservationIgnored private var namespace: RealtimeNamespace?
    @ObservationIgnored private var listener: UUID?
    @ObservationIgnored private var waiters: [String: [(UUID, CheckedContinuation<Void, Never>)]] = [:]

    private struct JobPayload: Decodable { let job: Job }

    func start(app: AppModel) {
        if let listener { namespace?.remove(listener) }
        let namespace = app.realtime.namespace("/rt/jobs") { [weak app] in await app?.handshake(all: true) ?? [:] }
        self.namespace = namespace
        listener = namespace.onEvent { [weak self] name, argument in self?.receive(name, argument) }
    }

    func stop() {
        if let listener { namespace?.remove(listener) }
        listener = nil
        namespace = nil
        jobs = [:]
        for (_, list) in waiters { for (_, continuation) in list { continuation.resume() } }
        waiters = [:]
    }

    /// One event of the namespace.
    func receive(_ name: String, _ argument: Data?) {
        if name == "agent.updated" {
            agentsRevision += 1
            return
        }
        guard name.hasPrefix("job."), let envelope = Envelope.parse(argument),
              let payload = try? HubJSON.decoder.decode(JobPayload.self, from: envelope.payload) else { return }
        jobs[payload.job.id] = payload.job
        for (_, continuation) in waiters.removeValue(forKey: payload.job.id) ?? [] { continuation.resume() }
    }

    /// Waits until an event about the job arrives, or `timeout` passes, whichever is first.
    func wait(for id: String, timeout: Duration) async {
        let token = UUID()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            waiters[id, default: []].append((token, continuation))
            Task { [weak self] in
                try? await Task.sleep(for: timeout)
                self?.expire(id, token)
            }
        }
    }

    private func expire(_ id: String, _ token: UUID) {
        guard var list = waiters[id], let index = list.firstIndex(where: { $0.0 == token }) else { return }
        let (_, continuation) = list.remove(at: index)
        waiters[id] = list.isEmpty ? nil : list
        continuation.resume()
    }
}

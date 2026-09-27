// Drawing a workflow on the phone: the rules, apart from the views so they are unit-tested (the
// web's `schedules/workflows/model.ts` does the same for its canvas). A phone draws the steps as a
// list and the links as each step's "what follows"; what is saved is the contract's own
// `WorkflowWrite`, positions included, so the same workflow opens on the web's canvas unchanged.
// The hub stays the judge of what is valid (`schedules.validateWorkflow`).
import CoreHubClient
import Foundation

enum WorkflowEditRules {
    /// The longest wait a delay step takes (`MAX_DELAY_SECONDS` on the hub).
    static let maxDelaySeconds = 3600
    /// The size the web draws a step at, and the gap it leaves, so a step added here lands on the
    /// canvas where the web would put it.
    static let nodeWidth: Double = 208
    static let nodeHeight: Double = 76
    private static let gapX: Double = 72
    private static let gapY: Double = 40
    static let kinds: [WorkflowNode.Kind] = [.agent, .condition, .delay, .notify, .approval]

    /// What is being drawn.
    struct Draft: Hashable {
        var name: String
        var description: String
        var workingDir: String?
        var nodes: [WorkflowNode]
        var edges: [WorkflowEdge]
        var limits: WorkflowLimits?
    }

    static func draft(_ workflow: Workflow?) -> Draft {
        guard let workflow else { return Draft(name: "", description: "", workingDir: nil, nodes: [], edges: [], limits: nil) }
        return Draft(name: workflow.name, description: workflow.description ?? "", workingDir: workflow.workingDir,
                     nodes: workflow.nodes, edges: workflow.edges, limits: workflow.limits)
    }

    /// A copy of a saved workflow under a new name («… (copy)»).
    static func copy(_ workflow: Workflow, name: String) -> Draft {
        var copy = draft(workflow)
        copy.name = name
        return copy
    }

    // MARK: - Steps

    /// A fresh id for a step of this kind: `agent_1`, `agent_2`, … never one already taken.
    static func nextNodeID(_ kind: WorkflowNode.Kind, _ nodes: [WorkflowNode]) -> String {
        let taken = Set(nodes.map(\.id))
        var n = 1
        while taken.contains("\(kind.rawValue)_\(n)") { n += 1 }
        return "\(kind.rawValue)_\(n)"
    }

    static func nextEdgeID(_ edges: [WorkflowEdge]) -> String {
        let taken = Set(edges.map(\.id))
        var n = 1
        while taken.contains("e\(n)") { n += 1 }
        return "e\(n)"
    }

    /// What a new step says until someone changes it: runnable where it can be.
    static func defaultInput(_ kind: WorkflowNode.Kind) -> String {
        switch kind {
        case .delay: return "60"
        case .condition: return "input exists"
        default: return ""
        }
    }

    /// Where a new step goes on the canvas: after `after` (or the last step), along the reading
    /// direction; below it when that spot is taken.
    static func place(_ draft: Draft, after: String?) -> WorkflowNodePosition {
        guard let anchor = draft.nodes.first(where: { $0.id == after }) ?? draft.nodes.last else {
            return WorkflowNodePosition(x: 40, y: 40)
        }
        var spot = WorkflowNodePosition(x: anchor.position.x + nodeWidth + gapX, y: anchor.position.y)
        func overlaps(_ p: WorkflowNodePosition) -> Bool {
            draft.nodes.contains { abs($0.position.x - p.x) < nodeWidth && abs($0.position.y - p.y) < nodeHeight }
        }
        var guardCount = 0
        while overlaps(spot) && guardCount < 50 {
            spot = WorkflowNodePosition(x: spot.x, y: spot.y + nodeHeight + gapY)
            guardCount += 1
        }
        return spot
    }

    /// Adds a step after `after` (or at the end) and, when there is a step before it, links the two
    /// on success, as the phone has no canvas to draw that link on. Returns the new step's id.
    @discardableResult
    static func add(_ kind: WorkflowNode.Kind, title: String, to draft: inout Draft, after: String? = nil, agentID: String? = nil) -> String {
        let id = nextNodeID(kind, draft.nodes)
        let previous = after ?? draft.nodes.last?.id
        let node = WorkflowNode(
            id: id, kind: kind, title: title, agentId: kind == .agent ? agentID : nil,
            skills: [], input: defaultInput(kind), approvalRequired: false, position: place(draft, after: previous)
        )
        draft.nodes.append(node)
        if let previous { connect(previous, to: id, route: .success, in: &draft) }
        return id
    }

    /// Removes a step and every link to or from it: a link never outlives either end.
    static func remove(_ nodeID: String, from draft: inout Draft) {
        draft.nodes.removeAll { $0.id == nodeID }
        draft.edges.removeAll { $0.from == nodeID || $0.to == nodeID }
    }

    /// Links two steps on a route once; the id of the link, or nil when it cannot be made.
    @discardableResult
    static func connect(_ from: String, to: String, route: WorkflowEdge.Route, in draft: inout Draft) -> String? {
        let ids = Set(draft.nodes.map(\.id))
        guard from != to, ids.contains(from), ids.contains(to) else { return nil }
        if let existing = draft.edges.first(where: { $0.from == from && $0.to == to && $0.route == route }) { return existing.id }
        let id = nextEdgeID(draft.edges)
        draft.edges.append(WorkflowEdge(id: id, from: from, to: to, route: route))
        return id
    }

    /// Moves a step one place up or down the list (and so in the order a phone reads them).
    static func move(_ nodeID: String, by offset: Int, in draft: inout Draft) {
        guard let index = draft.nodes.firstIndex(where: { $0.id == nodeID }) else { return }
        let target = index + offset
        guard draft.nodes.indices.contains(target) else { return }
        draft.nodes.swapAt(index, target)
    }

    /// The steps that can have finished before this one: everything with a path to it. These are
    /// the `{{steps.<id>.output}}` a prompt can use.
    static func upstream(_ draft: Draft, of nodeID: String) -> [WorkflowNode] {
        var into: [String: [String]] = [:]
        for edge in draft.edges { into[edge.to, default: []].append(edge.from) }
        var seen = Set<String>()
        var stack = into[nodeID] ?? []
        while let id = stack.popLast() {
            if seen.contains(id) || id == nodeID { continue }
            seen.insert(id)
            stack.append(contentsOf: into[id] ?? [])
        }
        return draft.nodes.filter { seen.contains($0.id) }
    }

    // MARK: - Saving

    /// The drawing as the contract's `WorkflowWrite`: what `createWorkflow`/`updateWorkflow` take.
    static func write(_ draft: Draft, clearing: Bool) -> WorkflowWrite {
        let description = draft.description.trimmingCharacters(in: .whitespacesAndNewlines)
        let nodes = draft.nodes.map { node -> WorkflowNode in
            var out = node
            if node.kind != .agent {
                out.agentId = nil
                out.model = nil
                out.provider = nil
            }
            if node.kind == .approval { out.approvalRequired = false }
            return out
        }
        return WorkflowWrite(
            name: draft.name.trimmingCharacters(in: .whitespacesAndNewlines),
            description: description.isEmpty ? nil : description,
            workingDir: draft.workingDir,
            nodes: nodes,
            edges: draft.edges,
            limits: draft.limits,
            sendNull: clearing && description.isEmpty ? [.description] : []
        )
    }

    /// The drawing as the hub's live check takes it (`WorkflowCheck`): what saving sends, without
    /// the name — the check never reads it, and a hub older than the check's own body refused an
    /// empty one, so an unnamed drawing is checked on every hub.
    static func check(_ draft: Draft) -> WorkflowCheck {
        let saved = write(draft, clearing: false)
        return WorkflowCheck(
            description: saved.description,
            workingDir: saved.workingDir,
            nodes: saved.nodes,
            edges: saved.edges,
            limits: saved.limits
        )
    }

    /// Saving waits for a name and for the hub's check to find no problem.
    static func canSave(_ draft: Draft, validation: WorkflowValidation?) -> Bool {
        !draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && (validation?.valid ?? true)
    }

    // MARK: - The hub's check

    static func issues(_ validation: WorkflowValidation?, node: String) -> [WorkflowIssue] {
        ((validation?.problems ?? []) + (validation?.warnings ?? [])).filter { $0.nodeId == node }
    }

    static func general(_ validation: WorkflowValidation?) -> [WorkflowIssue] {
        ((validation?.problems ?? []) + (validation?.warnings ?? [])).filter { $0.nodeId == nil && $0.edgeId == nil }
    }

    static func edgeIssues(_ validation: WorkflowValidation?, edge: String) -> [WorkflowIssue] {
        ((validation?.problems ?? []) + (validation?.warnings ?? [])).filter { $0.edgeId == edge }
    }

    static func isProblem(_ validation: WorkflowValidation?, _ issue: WorkflowIssue) -> Bool {
        (validation?.problems ?? []).contains(issue)
    }

    /// A finding in the person's language; a code this app does not know, in the hub's words.
    static func describe(_ issue: WorkflowIssue, _ l10n: L10n) -> String {
        let key = "workflow_editor.issues.\(issue.code)"
        return l10n.has(key) ? l10n(key, ["detail": issue.detail ?? ""]) : issue.message
    }

    // MARK: - Conditions

    /// The comparisons the engine understands, in the order offered.
    static let operators = ["==", "!=", ">", ">=", "<", "<=", "contains", "matches", "exists", "empty"]
    static let unary: Set<String> = ["exists", "empty"]
    static let operatorKey: [String: String] = [
        "==": "eq", "!=": "ne", ">": "gt", ">=": "gte", "<": "lt", "<=": "lte",
        "contains": "contains", "matches": "matches", "exists": "exists", "empty": "empty",
    ]

    struct Condition: Equatable {
        var path: String
        var op: String
        var value: String
    }

    /// A condition's text as its three parts, or nil when it is not one comparison the form can
    /// show (the form then offers the text as it is).
    static func split(_ text: String) -> Condition? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return Condition(path: "", op: "==", value: "") }
        for op in ["exists", "empty"] where trimmed.hasSuffix(" \(op)") {
            return Condition(path: String(trimmed.dropLast(op.count + 1)).trimmingCharacters(in: .whitespaces), op: op, value: "")
        }
        for op in ["==", "!=", ">=", "<=", "contains", "matches", ">", "<"] {
            guard let range = trimmed.range(of: " \(op) ") else { continue }
            var raw = String(trimmed[range.upperBound...]).trimmingCharacters(in: .whitespaces)
            if raw.count >= 2, (raw.hasPrefix("\"") && raw.hasSuffix("\"")) || (raw.hasPrefix("'") && raw.hasSuffix("'")) {
                raw = String(raw.dropFirst().dropLast())
            }
            return Condition(path: String(trimmed[..<range.lowerBound]).trimmingCharacters(in: .whitespaces), op: op, value: raw)
        }
        return nil
    }

    /// The three parts as the text the engine reads; a value that is not a number is quoted.
    static func join(_ condition: Condition) -> String {
        let path = condition.path.trimmingCharacters(in: .whitespaces)
        if unary.contains(condition.op) { return "\(path) \(condition.op)" }
        let value = condition.value.trimmingCharacters(in: .whitespaces)
        let numeric = !value.isEmpty && Double(value) != nil
        return "\(path) \(condition.op) \(numeric ? value : "\"\(condition.value)\"")"
    }

    // MARK: - Models

    /// A step's model as the picker names it: the catalogue's label, else the id as saved; nil for
    /// none (the agent's own model).
    static func modelLabel(_ value: String?, options: [ChatControls.ModelOption]) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return options.first { $0.value == value }?.label ?? value
    }

    // MARK: - Limits

    /// The saved limits as typed: an empty field has none; minutes become seconds within the hub's
    /// bounds (a week, a day); a cost is a positive USD amount.
    static func limits(minutes: String, cost: String, stepMinutes: String) -> (WorkflowLimits?, WorkflowLogic.LimitProblem?) {
        let (override, problem) = WorkflowLogic.limits(minutes: minutes, cost: cost, stepMinutes: stepMinutes)
        if let problem { return (nil, problem) }
        return (WorkflowLimits(maxDurationSeconds: override?.maxDurationSeconds, maxCost: override?.maxCost, stepTimeoutSeconds: override?.stepTimeoutSeconds), nil)
    }
}

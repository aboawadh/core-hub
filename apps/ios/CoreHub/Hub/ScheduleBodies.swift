// The contract lists every field of a `ScheduleTrigger` and of a `ScheduleTarget` as required, some
// of them `null` (`expression` of an interval, `model` of a prompt). The generated models leave a
// nil field out, and the hub checks bodies against the contract: without them it refuses the
// preview, the create and the edit (400 "must have required property"). This puts each missing one
// back as JSON `null` before the request leaves. Android's ScheduleBodies.kt is its twin.
import Foundation

enum ScheduleBodies {
    private static let triggerKinds: Set<String> = ["cron", "interval", "once"]
    private static let triggerFields = ["expression", "every_minutes", "run_at"]
    private static let targetKinds: Set<String> = ["agent_prompt", "workflow"]
    private static let targetFields = ["agent_id", "prompt", "model", "provider", "workflow_id", "input"]

    static func complete(_ request: URLRequest) -> URLRequest {
        guard let body = request.httpBody, let filled = complete(body) else { return request }
        var copy = request
        copy.httpBody = filled
        return copy
    }

    /// The body with the missing fields as `null`; nil when nothing was missing or it is not a JSON object.
    static func complete(_ body: Data) -> Data? {
        guard var object = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else { return nil }
        var changed = false
        if var trigger = object["trigger"] as? [String: Any], let kind = trigger["kind"] as? String, triggerKinds.contains(kind) {
            for field in triggerFields where trigger[field] == nil {
                trigger[field] = NSNull()
                changed = true
            }
            object["trigger"] = trigger
        }
        if var target = object["target"] as? [String: Any], let kind = target["kind"] as? String, targetKinds.contains(kind) {
            for field in targetFields where target[field] == nil {
                target[field] = NSNull()
                changed = true
            }
            if target["skills"] == nil {
                target["skills"] = [String]()
                changed = true
            }
            object["target"] = target
        }
        guard changed else { return nil }
        return try? JSONSerialization.data(withJSONObject: object)
    }
}

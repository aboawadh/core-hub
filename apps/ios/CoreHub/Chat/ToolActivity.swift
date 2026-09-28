// What a turn's tool calls show — the web's `toolActivity` (packages/web/src/chat/toolActivity.ts)
// and Android's `ToolActivity`, with the phone's window of two (owner, 2026-09-26; DECISIONS §111):
//
// - while the turn runs, the last `window` calls, plus any call still running or waiting and any
//   that failed (an error stays in view until the turn ends); the rest are "+k earlier steps";
// - once it has ended, every call folds into one summary row that opens to the full list.
import CoreHubClient
import Foundation

struct ToolActivitySummary: Equatable {
    var count: Int
    var failed: Int
    /// Wall-clock time from the first start to the last finish, else the calls' durations summed.
    var durationMs: Int?
    /// The latest distinct tool names, most recent first.
    var names: [String]
}

struct ToolActivity: Equatable {
    static let window = 2
    static let names = 2

    /// True once the turn has ended and nothing is still running or waiting.
    var folded: Bool
    /// The calls a live turn shows, in order; empty when folded (the row opens to all of them).
    var visible: [ToolCall]
    /// How many calls the "+k earlier steps" line (live) or the folded row stands for.
    var hidden: Int
    var summary: ToolActivitySummary

    private static func busy(_ call: ToolCall) -> Bool {
        call.status == .running || call.status == .awaitingApproval
    }

    static func of(_ calls: [ToolCall], live: Bool, window: Int = ToolActivity.window, maxNames: Int = ToolActivity.names) -> ToolActivity {
        let summary = summarize(calls, maxNames: maxNames)
        let folded = !live && !calls.contains(where: busy)
        if folded { return ToolActivity(folded: true, visible: [], hidden: calls.count, summary: summary) }
        let start = max(0, calls.count - window)
        let visible = calls.enumerated()
            .filter { $0.offset >= start || busy($0.element) || $0.element.status == .failed }
            .map(\.element)
        return ToolActivity(folded: false, visible: visible, hidden: calls.count - visible.count, summary: summary)
    }

    static func summarize(_ calls: [ToolCall], maxNames: Int = ToolActivity.names) -> ToolActivitySummary {
        var names: [String] = []
        for call in calls.reversed() where names.count < maxNames && !names.contains(call.name) {
            names.append(call.name)
        }
        return ToolActivitySummary(
            count: calls.count,
            failed: calls.filter { $0.status == .failed }.count,
            durationMs: totalDuration(calls),
            names: names
        )
    }

    private static func totalDuration(_ calls: [ToolCall]) -> Int? {
        let starts = calls.compactMap(\.startedAt)
        let ends = calls.compactMap(\.finishedAt)
        if !calls.isEmpty, starts.count == calls.count, ends.count == calls.count,
           let first = starts.min(), let last = ends.max() {
            return max(0, Int((last.timeIntervalSince(first) * 1000).rounded()))
        }
        let durations = calls.compactMap(\.durationMs)
        return durations.isEmpty ? nil : durations.reduce(0, +)
    }

    /// Minutes and seconds of the folded row's time, never below one second ("1m 05s", "42s").
    static func durationParts(_ ms: Int) -> (minutes: Int, seconds: Int) {
        let total = max(1, Int((Double(ms) / 1000).rounded()))
        return (total / 60, total % 60)
    }
}

/// The CLDR plural category of a whole `count` in a language: Arabic has six, English two,
/// Chinese one. iOS has no public plural-rules API, so the integer rules of CLDR for the languages
/// people ask for are written here (ADR 0028); any other language counts as English does. The
/// catalogue keeps one key per category (`tool.activity.steps.few`), as the web's does.
enum PluralCategory {
    static func of(_ count: Int, _ language: AppLanguage) -> String {
        let n = abs(count)
        let ten = n % 10
        let hundred = n % 100
        switch language.base.rawValue.split(separator: "-").first.map(String.init) ?? "en" {
        case "ar":
            switch n {
            case 0: return "zero"
            case 1: return "one"
            case 2: return "two"
            default:
                if (3...10).contains(hundred) { return "few" }
                if (11...99).contains(hundred) { return "many" }
                return "other"
            }
        case "zh", "ja", "ko", "th", "vi", "id", "ms", "lo", "km", "my", "yo":
            return "other"
        case "fr", "pt", "hi", "bn", "fa", "am", "zu", "hy", "gu", "kn", "mr":
            return n <= 1 ? "one" : "other"
        case "ru", "uk", "be":
            if ten == 1 && hundred != 11 { return "one" }
            if (2...4).contains(ten) && !(12...14).contains(hundred) { return "few" }
            return "many"
        case "pl":
            if n == 1 { return "one" }
            if (2...4).contains(ten) && !(12...14).contains(hundred) { return "few" }
            return "many"
        case "cs", "sk":
            if n == 1 { return "one" }
            if (2...4).contains(n) { return "few" }
            return "other"
        case "he":
            if n == 1 { return "one" }
            if n == 2 { return "two" }
            return "other"
        case "ro":
            if n == 1 { return "one" }
            if n == 0 || (1...19).contains(hundred) { return "few" }
            return "other"
        default:
            return n == 1 ? "one" : "other"
        }
    }
}

extension L10n {
    /// `key.<category>` for `count`, with `{count}` filled in.
    func plural(_ key: String, _ count: Int) -> String {
        t("\(key).\(PluralCategory.of(count, language))", ["count": String(count)])
    }
}

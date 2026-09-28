// Test-only pseudo-locales (ADR 0028), as the web draws them (packages/contracts
// src/languages.ts, `pseudoize`): the words transformed, `{placeholders}` untouched, the whole in
// brackets. `en-XA` accented and at least 40% longer, `ar-XB` stretched with tatweel, `zh-XC`
// full-width ideographs without spaces, `th-XD` Thai with stacked marks. No picker lists one.
import Foundation

enum Pseudo {
    private static let accented: [Character: String] = [
        "a": "á", "b": "ƀ", "c": "ç", "d": "ð", "e": "é", "f": "ƒ", "g": "ĝ", "h": "ĥ", "i": "î", "j": "ĵ",
        "k": "ķ", "l": "ļ", "m": "ɱ", "n": "ñ", "o": "ö", "p": "þ", "q": "ǫ", "r": "ŕ", "s": "š", "t": "ţ",
        "u": "û", "v": "ṽ", "w": "ŵ", "x": "ẋ", "y": "ý", "z": "ž", "A": "Å", "B": "Ɓ", "C": "Ç", "D": "Ð",
        "E": "É", "F": "Ƒ", "G": "Ĝ", "H": "Ĥ", "I": "Î", "J": "Ĵ", "K": "Ķ", "L": "Ļ", "M": "Ṁ", "N": "Ñ",
        "O": "Ö", "P": "Þ", "Q": "Ǫ", "R": "Ŕ", "S": "Š", "T": "Ţ", "U": "Û", "V": "Ṽ", "W": "Ŵ", "X": "Ẋ",
        "Y": "Ý", "Z": "Ž",
    ]
    private static let han = Array("设置聊天任务模型代理文件记忆频道工具技能搜索新建删除保存取消确认打开关闭显示隐藏更多帮助用户账户通知隐私更新插件日志用量性能主题语言")
    private static let thai = Array("กขคฆงจฉชซญฎฏฐฑฒณดตถทธนบปผฝพฟภมยรลวศษสหฬอฮ")
    private static let above = ["\u{0E34}\u{0E48}", "\u{0E35}\u{0E49}", "\u{0E36}\u{0E4A}", "\u{0E37}\u{0E4B}", "\u{0E31}\u{0E49}"]
    private static let below = ["\u{0E38}", "\u{0E39}"]
    private static let joinsNext: Set<Character> = Set("بتثجحخسشصضطظعغفقكلمنهيئ")

    static func transform(_ template: String, style: String) -> String {
        if style == "tagged" { return template }
        // Keep `{placeholders}` as they are.
        var body = ""
        var rest = Substring(template)
        while let open = rest.firstIndex(of: "{"), let close = rest[open...].firstIndex(of: "}") {
            body += words(String(rest[..<open]), style)
            body += rest[open...close]
            rest = rest[rest.index(after: close)...]
        }
        body += words(String(rest), style)
        switch style {
        case "long-rtl": return "«\(body)»"
        case "accented":
            let plain = template.replacingOccurrences(of: #"\{[A-Za-z0-9_]+\}"#, with: "", options: .regularExpression)
            let made = body.replacingOccurrences(of: #"\{[A-Za-z0-9_]+\}"#, with: "", options: .regularExpression)
            let short = Int((Double(plain.count) * 1.4).rounded(.up)) - made.count
            return "[\(body)\(short > 0 ? " " + String(repeating: "ẋ", count: max(short - 1, 3)) : "")]"
        default: return "[\(body)]"
        }
    }

    private static func words(_ text: String, _ style: String) -> String {
        switch style {
        case "accented":
            return text.map { ch in
                let mapped = accented[ch] ?? String(ch)
                return "aeiouAEIOU".contains(ch) ? mapped + mapped : mapped
            }.joined()
        case "long-rtl":
            var out = ""
            for (index, ch) in text.enumerated() {
                out.append(ch)
                if joinsNext.contains(ch) && index % 2 == 0 { out += "\u{0640}\u{0640}" }
            }
            return out
        case "cjk":
            var out = ""
            var seed = 0
            for word in text.split(separator: " ", omittingEmptySubsequences: true) {
                let letters = word.filter { $0.isASCII && $0.isLetter }.count
                let count = letters == 0 ? 0 : max(1, Int((Double(letters) * 0.6).rounded(.up)))
                for i in 0..<count { out.append(han[(seed + i * 7) % han.count]) }
                seed += count + 3
                out += word.filter { !($0.isASCII && $0.isLetter) }.map { ch -> String in
                    switch ch { case ":": return "：" case "?": return "？" case "!": return "！" case ",": return "，" case ".": return "。" default: return String(ch) }
                }.joined()
            }
            return out
        case "tall":
            var out = ""
            var index = 0
            for ch in text {
                if ch.isASCII && ch.isLetter, let scalar = ch.unicodeScalars.first {
                    out.append(thai[Int(scalar.value) % thai.count])
                    if index % 2 == 0 { out += above[index % above.count] } else if index % 3 == 0 { out += below[index % below.count] }
                    index += 1
                } else { out.append(ch) }
            }
            return out
        default: return text
        }
    }
}

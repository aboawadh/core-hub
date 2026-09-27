// The owner's terminal on the phone (Settings → Terminal): what the shell wrote, kept as a screen of
// cells, so a prompt, `ls`, `top` or an editor look on the phone as they do in the web's emulator.
// A small VT100/xterm subset, written here rather than depended on (as the realtime client is): the
// hub sends UTF-8 text with its escape sequences (`terminal.output`), this turns it into rows. Pure
// logic, no UIKit, so TerminalTests drives it with plain strings.
import Foundation

/// A colour a cell asks for: one of the 256 indexed ones, or a true colour.
enum TermColor: Equatable, Hashable {
    case indexed(Int)
    case rgb(UInt8, UInt8, UInt8)
}

/// How a cell is drawn.
struct TermStyle: Equatable, Hashable {
    var foreground: TermColor?
    var background: TermColor?
    var bold = false
    var dim = false
    var italic = false
    var underline = false
    var inverse = false

    static let plain = TermStyle()
}

/// One place on the screen: what is in it (a grapheme; "" is empty) and how it is drawn.
struct TermCell: Equatable {
    var text: String
    var style: TermStyle

    static let blank = TermCell(text: "", style: .plain)
}

/// The screen and its history. `feed` takes what the shell wrote; `respond` carries what the terminal
/// must answer (the cursor position, what kind of terminal it is) back to the shell.
final class TerminalScreenBuffer {
    private(set) var cols: Int
    private(set) var rows: Int
    /// The visible rows, top first.
    private(set) var lines: [[TermCell]]
    /// Rows that scrolled off the top of the main screen, oldest first (never the alternate one's).
    private(set) var scrollback: [[TermCell]] = []
    let scrollbackLimit: Int
    private(set) var cursorX = 0
    private(set) var cursorY = 0
    private(set) var cursorVisible = true
    /// Arrow keys send `ESC O A` instead of `ESC [ A` (DECCKM), as full-screen programs ask.
    private(set) var applicationCursorKeys = false
    /// Pasted text is wrapped in `ESC [200~ … ESC [201~` when the program asked (bracketed paste).
    private(set) var bracketedPaste = false
    private(set) var usingAlternateScreen = false
    /// The window title the shell set (OSC 0/2).
    private(set) var title = ""
    var respond: ((String) -> Void)?

    private var style = TermStyle.plain
    private var wrapPending = false
    private var autoWrap = true
    private var originMode = false
    private var scrollTop = 0
    private var scrollBottom: Int
    private var saved: (x: Int, y: Int, style: TermStyle)?
    private var mainScreen: (lines: [[TermCell]], x: Int, y: Int)?
    private var tabStops: Set<Int> = []

    private enum Parse {
        case ground
        case escape
        case csi
        case osc
        case oscEscape
        /// `ESC (`, `ESC )` and friends: the next character names a character set, and is skipped.
        case charset
    }

    private var parse = Parse.ground
    private var params = ""
    private var oscText = ""

    init(cols: Int = 80, rows: Int = 24, scrollbackLimit: Int = 2000) {
        self.cols = max(2, cols)
        self.rows = max(2, rows)
        self.scrollbackLimit = scrollbackLimit
        self.lines = Array(repeating: Array(repeating: .blank, count: max(2, cols)), count: max(2, rows))
        self.scrollBottom = max(2, rows) - 1
        resetTabStops()
    }

    // MARK: - Reading

    /// A row as plain text, trailing blanks dropped (tests and copying).
    static func text(of row: [TermCell]) -> String {
        var out = row.map { $0.text.isEmpty ? " " : $0.text }.joined()
        while out.hasSuffix(" ") { out.removeLast() }
        return out
    }

    /// Everything the person can scroll through: the history, then the screen.
    var allLines: [[TermCell]] { scrollback + lines }

    /// The whole text, for Copy.
    var plainText: String {
        var rows = allLines.map(Self.text(of:))
        while rows.last?.isEmpty == true { rows.removeLast() }
        return rows.joined(separator: "\n")
    }

    // MARK: - Size

    /// The phone turned or the keyboard came up: keep what fits, top-left anchored, and keep the
    /// cursor on the screen (the shell repaints after the hub passes the new size on).
    func resize(cols newCols: Int, rows newRows: Int) {
        let newCols = max(2, newCols), newRows = max(2, newRows)
        guard newCols != cols || newRows != rows else { return }
        func fit(_ row: [TermCell]) -> [TermCell] {
            if row.count >= newCols { return Array(row.prefix(newCols)) }
            return row + Array(repeating: .blank, count: newCols - row.count)
        }
        var grid = lines.map(fit)
        scrollback = scrollback.map(fit)
        if grid.count > newRows {
            // Rows below the cursor go first; then the top ones scroll into the history.
            let spare = grid.count - newRows
            let below = max(0, grid.count - 1 - cursorY)
            let dropBottom = min(spare, below)
            grid.removeLast(dropBottom)
            let dropTop = spare - dropBottom
            if dropTop > 0 {
                if !usingAlternateScreen { appendScrollback(Array(grid.prefix(dropTop))) }
                grid.removeFirst(dropTop)
                cursorY -= dropTop
            }
        } else if grid.count < newRows {
            grid += Array(repeating: Array(repeating: .blank, count: newCols), count: newRows - grid.count)
        }
        lines = grid
        cols = newCols
        rows = newRows
        scrollTop = 0
        scrollBottom = newRows - 1
        cursorX = min(cursorX, newCols - 1)
        cursorY = min(max(cursorY, 0), newRows - 1)
        wrapPending = false
        resetTabStops()
        if var main = mainScreen {
            main.lines = main.lines.map(fit)
            if main.lines.count > newRows { main.lines = Array(main.lines.suffix(newRows)) }
            while main.lines.count < newRows { main.lines.append(Array(repeating: .blank, count: newCols)) }
            main.x = min(main.x, newCols - 1)
            main.y = min(main.y, newRows - 1)
            mainScreen = main
        }
    }

    // MARK: - Writing

    func feed(_ text: String) {
        for scalar in text.unicodeScalars { take(scalar) }
    }

    private func take(_ scalar: Unicode.Scalar) {
        switch parse {
        case .ground:
            ground(scalar)
        case .escape:
            escape(scalar)
        case .csi:
            let v = scalar.value
            if v >= 0x40 && v <= 0x7E {
                parse = .ground
                csi(Character(scalar), params)
                params = ""
            } else if v == 0x1B {
                params = ""
                parse = .escape
            } else if v == 0x18 || v == 0x1A {
                params = ""
                parse = .ground
            } else if v >= 0x20 {
                params.unicodeScalars.append(scalar)
            } else {
                control(scalar)
            }
        case .osc:
            if scalar.value == 0x07 {
                osc(oscText)
                parse = .ground
            } else if scalar.value == 0x1B {
                parse = .oscEscape
            } else if oscText.unicodeScalars.count < 4096 {
                oscText.unicodeScalars.append(scalar)
            }
        case .oscEscape:
            // `ESC \` ends the string; anything else starts over as an escape.
            osc(oscText)
            if scalar == "\\" {
                parse = .ground
            } else {
                parse = .escape
                escape(scalar)
            }
        case .charset:
            parse = .ground
        }
    }

    private func ground(_ scalar: Unicode.Scalar) {
        if scalar.value < 0x20 || scalar.value == 0x7F {
            control(scalar)
            return
        }
        if scalar.value >= 0x80 && scalar.value < 0xA0 { return }
        // A combining mark joins the character before it.
        if scalar.properties.generalCategory == .nonspacingMark || scalar.properties.generalCategory == .enclosingMark
            || scalar.value == 0x200D || (0xFE00...0xFE0F).contains(scalar.value) {
            let (x, y) = previousCell()
            if y >= 0 && y < rows && x >= 0 && x < cols && !lines[y][x].text.isEmpty {
                lines[y][x].text.unicodeScalars.append(scalar)
                return
            }
        }
        put(String(scalar))
    }

    private func previousCell() -> (Int, Int) {
        if wrapPending { return (cols - 1, cursorY) }
        return (cursorX - 1, cursorY)
    }

    private func put(_ grapheme: String) {
        if wrapPending {
            if autoWrap {
                cursorX = 0
                lineFeed()
            }
            wrapPending = false
        }
        lines[cursorY][cursorX] = TermCell(text: grapheme, style: style)
        if cursorX == cols - 1 {
            wrapPending = true
        } else {
            cursorX += 1
        }
    }

    private func control(_ scalar: Unicode.Scalar) {
        switch scalar.value {
        case 0x07: break // bell
        case 0x08: // backspace
            if wrapPending { wrapPending = false } else { cursorX = max(0, cursorX - 1) }
        case 0x09: // tab
            wrapPending = false
            cursorX = tabStops.filter { $0 > cursorX }.min() ?? (cols - 1)
            cursorX = min(cursorX, cols - 1)
        case 0x0A, 0x0B, 0x0C: // line feed (the shell's newline also returns, through its own CR)
            wrapPending = false
            lineFeed()
        case 0x0D:
            wrapPending = false
            cursorX = 0
        case 0x1B:
            parse = .escape
        default:
            break
        }
    }

    private func escape(_ scalar: Unicode.Scalar) {
        parse = .ground
        switch scalar {
        case "[":
            params = ""
            parse = .csi
        case "]":
            oscText = ""
            parse = .osc
        case "(", ")", "*", "+", "-", ".", "/", "#", "%":
            parse = .charset
        case "7": saveCursor()
        case "8": restoreCursor()
        case "D": // index
            wrapPending = false
            lineFeed()
        case "E": // next line
            wrapPending = false
            cursorX = 0
            lineFeed()
        case "M": // reverse index
            wrapPending = false
            if cursorY == scrollTop { scrollDown(1) } else { cursorY = max(0, cursorY - 1) }
        case "H": tabStops.insert(cursorX)
        case "c": reset()
        case "=", ">": break // keypad modes
        case "P", "X", "^", "_":
            // A device control or private string: skip it like an OSC.
            oscText = ""
            parse = .osc
        default:
            break
        }
    }

    private func numbers(_ text: String) -> [Int] {
        text.split(separator: ";", omittingEmptySubsequences: false).map { Int($0.filter(\.isNumber)) ?? 0 }
    }

    private func csi(_ final: Character, _ raw: String) {
        let isPrivate = raw.hasPrefix("?")
        let isSecondary = raw.hasPrefix(">") || raw.hasPrefix("=")
        let body = (isPrivate || isSecondary) ? String(raw.dropFirst()) : raw
        // Intermediate characters (a space, `!`, `"`…) end the parameters.
        let paramText = String(body.prefix { $0.isNumber || $0 == ";" || $0 == ":" })
        let intermediate = body.dropFirst(paramText.count)
        let args = numbers(paramText.replacingOccurrences(of: ":", with: ";"))
        func arg(_ i: Int, _ fallback: Int = 1) -> Int {
            guard i < args.count, args[i] != 0 else { return fallback }
            return args[i]
        }
        if !intermediate.isEmpty {
            if final == "p" && intermediate == "!" { softReset() }
            return
        }
        if isSecondary {
            if final == "c" { respond?("\u{1B}[>0;0;0c") }
            return
        }
        switch final {
        case "A": moveCursor(y: cursorY - arg(0), clampToRegion: true)
        case "B", "e": moveCursor(y: cursorY + arg(0), clampToRegion: true)
        case "C", "a": moveCursor(x: cursorX + arg(0))
        case "D": moveCursor(x: cursorX - arg(0))
        case "E": moveCursor(x: 0, y: cursorY + arg(0), clampToRegion: true)
        case "F": moveCursor(x: 0, y: cursorY - arg(0), clampToRegion: true)
        case "G", "`": moveCursor(x: arg(0) - 1)
        case "d": moveCursor(y: (originMode ? scrollTop : 0) + arg(0) - 1)
        case "H", "f": moveCursor(x: arg(1) - 1, y: (originMode ? scrollTop : 0) + arg(0) - 1)
        case "J": eraseDisplay(args.first ?? 0)
        case "K": eraseLine(args.first ?? 0)
        case "L": insertLines(arg(0))
        case "M": deleteLines(arg(0))
        case "@": insertBlanks(arg(0))
        case "P": deleteChars(arg(0))
        case "X": eraseChars(arg(0))
        case "S": scrollUp(arg(0))
        case "T": scrollDown(arg(0))
        case "I": for _ in 0..<arg(0) { control("\t") }
        case "Z":
            for _ in 0..<arg(0) { cursorX = tabStops.filter { $0 < cursorX }.max() ?? 0 }
        case "g":
            if (args.first ?? 0) == 3 { tabStops.removeAll() } else { tabStops.remove(cursorX) }
        case "m": graphics(args.isEmpty ? [0] : args)
        case "r":
            let top = arg(0) - 1, bottom = (args.count > 1 && args[1] > 0 ? args[1] : rows) - 1
            if top < bottom && bottom < rows {
                scrollTop = max(0, top)
                scrollBottom = bottom
                moveCursor(x: 0, y: originMode ? scrollTop : 0)
            }
        case "s": saveCursor()
        case "u": restoreCursor()
        case "h", "l": setModes(args, on: final == "h", isPrivate: isPrivate)
        case "n":
            if isPrivate { return }
            switch args.first ?? 0 {
            case 5: respond?("\u{1B}[0n")
            case 6: respond?("\u{1B}[\(cursorY + 1 - (originMode ? scrollTop : 0));\(cursorX + 1)R")
            default: break
            }
        case "c":
            if !isPrivate { respond?("\u{1B}[?1;2c") }
        case "b":
            // Repeat the last character.
            let (x, y) = previousCell()
            if x >= 0, y >= 0, y < rows, x < cols, !lines[y][x].text.isEmpty {
                let last = lines[y][x].text
                for _ in 0..<min(arg(0), cols * rows) { put(last) }
            }
        default:
            break
        }
    }

    private func setModes(_ args: [Int], on: Bool, isPrivate: Bool) {
        guard isPrivate else { return }
        for mode in args {
            switch mode {
            case 1: applicationCursorKeys = on
            case 6:
                originMode = on
                moveCursor(x: 0, y: on ? scrollTop : 0)
            case 7: autoWrap = on
            case 25: cursorVisible = on
            case 47, 1047: switchScreen(alternate: on, saveCursor: false)
            case 1049: switchScreen(alternate: on, saveCursor: true)
            case 2004: bracketedPaste = on
            default: break
            }
        }
    }

    private func graphics(_ args: [Int]) {
        var i = 0
        func color(at index: Int) -> (TermColor?, Int) {
            guard index < args.count else { return (nil, 0) }
            if args[index] == 5, index + 1 < args.count { return (.indexed(max(0, min(255, args[index + 1]))), 2) }
            if args[index] == 2, index + 3 < args.count {
                let c = { (n: Int) in UInt8(max(0, min(255, n))) }
                return (.rgb(c(args[index + 1]), c(args[index + 2]), c(args[index + 3])), 4)
            }
            return (nil, 1)
        }
        while i < args.count {
            let code = args[i]
            switch code {
            case 0: style = .plain
            case 1: style.bold = true
            case 2: style.dim = true
            case 3: style.italic = true
            case 4: style.underline = true
            case 7: style.inverse = true
            case 21, 22:
                style.bold = false
                style.dim = false
            case 23: style.italic = false
            case 24: style.underline = false
            case 27: style.inverse = false
            case 30...37: style.foreground = .indexed(code - 30)
            case 39: style.foreground = nil
            case 40...47: style.background = .indexed(code - 40)
            case 49: style.background = nil
            case 90...97: style.foreground = .indexed(code - 90 + 8)
            case 100...107: style.background = .indexed(code - 100 + 8)
            case 38, 48:
                let (value, used) = color(at: i + 1)
                if code == 38 { style.foreground = value } else { style.background = value }
                i += used
            default: break
            }
            i += 1
        }
    }

    private func osc(_ text: String) {
        let parts = text.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)
        if parts.count == 2, parts[0] == "0" || parts[0] == "2" { title = String(parts[1]) }
    }

    // MARK: - Cursor

    private func moveCursor(x: Int? = nil, y: Int? = nil, clampToRegion: Bool = false) {
        wrapPending = false
        if let x { cursorX = max(0, min(cols - 1, x)) }
        if let y {
            if clampToRegion && cursorY >= scrollTop && cursorY <= scrollBottom {
                cursorY = max(scrollTop, min(scrollBottom, y))
            } else if originMode {
                cursorY = max(scrollTop, min(scrollBottom, y))
            } else {
                cursorY = max(0, min(rows - 1, y))
            }
        }
    }

    private func saveCursor() { saved = (cursorX, cursorY, style) }

    private func restoreCursor() {
        guard let saved else { return }
        cursorX = min(saved.x, cols - 1)
        cursorY = min(saved.y, rows - 1)
        style = saved.style
        wrapPending = false
    }

    // MARK: - Scrolling and erasing

    private func blankRow() -> [TermCell] {
        // An erased cell keeps the background the program chose, as xterm does.
        Array(repeating: TermCell(text: "", style: TermStyle(background: style.background)), count: cols)
    }

    private func lineFeed() {
        if cursorY == scrollBottom {
            scrollUp(1)
        } else if cursorY < rows - 1 {
            cursorY += 1
        }
    }

    private func scrollUp(_ count: Int) {
        let n = min(count, scrollBottom - scrollTop + 1)
        guard n > 0 else { return }
        let gone = Array(lines[scrollTop..<(scrollTop + n)])
        lines.removeSubrange(scrollTop..<(scrollTop + n))
        lines.insert(contentsOf: Array(repeating: blankRow(), count: n), at: scrollBottom - n + 1)
        if scrollTop == 0 && !usingAlternateScreen { appendScrollback(gone) }
    }

    private func scrollDown(_ count: Int) {
        let n = min(count, scrollBottom - scrollTop + 1)
        guard n > 0 else { return }
        lines.removeSubrange((scrollBottom - n + 1)...scrollBottom)
        lines.insert(contentsOf: Array(repeating: blankRow(), count: n), at: scrollTop)
    }

    private func appendScrollback(_ rows: [[TermCell]]) {
        scrollback.append(contentsOf: rows)
        if scrollback.count > scrollbackLimit { scrollback.removeFirst(scrollback.count - scrollbackLimit) }
    }

    private func eraseDisplay(_ mode: Int) {
        switch mode {
        case 0:
            eraseLine(0)
            for y in (cursorY + 1)..<rows { lines[y] = blankRow() }
        case 1:
            eraseLine(1)
            for y in 0..<cursorY { lines[y] = blankRow() }
        case 2:
            for y in 0..<rows { lines[y] = blankRow() }
        case 3:
            scrollback.removeAll()
        default:
            break
        }
    }

    private func eraseLine(_ mode: Int) {
        let blank = TermCell(text: "", style: TermStyle(background: style.background))
        switch mode {
        case 0: for x in cursorX..<cols { lines[cursorY][x] = blank }
        case 1: for x in 0...min(cursorX, cols - 1) { lines[cursorY][x] = blank }
        case 2: lines[cursorY] = blankRow()
        default: break
        }
        wrapPending = false
    }

    private func insertLines(_ count: Int) {
        guard cursorY >= scrollTop && cursorY <= scrollBottom else { return }
        let n = min(count, scrollBottom - cursorY + 1)
        lines.removeSubrange((scrollBottom - n + 1)...scrollBottom)
        lines.insert(contentsOf: Array(repeating: blankRow(), count: n), at: cursorY)
        cursorX = 0
        wrapPending = false
    }

    private func deleteLines(_ count: Int) {
        guard cursorY >= scrollTop && cursorY <= scrollBottom else { return }
        let n = min(count, scrollBottom - cursorY + 1)
        lines.removeSubrange(cursorY..<(cursorY + n))
        lines.insert(contentsOf: Array(repeating: blankRow(), count: n), at: scrollBottom - n + 1)
        cursorX = 0
        wrapPending = false
    }

    private func insertBlanks(_ count: Int) {
        let n = min(count, cols - cursorX)
        var row = lines[cursorY]
        row.insert(contentsOf: Array(repeating: TermCell(text: "", style: TermStyle(background: style.background)), count: n), at: cursorX)
        lines[cursorY] = Array(row.prefix(cols))
        wrapPending = false
    }

    private func deleteChars(_ count: Int) {
        let n = min(count, cols - cursorX)
        var row = lines[cursorY]
        row.removeSubrange(cursorX..<(cursorX + n))
        row += Array(repeating: TermCell(text: "", style: TermStyle(background: style.background)), count: n)
        lines[cursorY] = row
        wrapPending = false
    }

    private func eraseChars(_ count: Int) {
        let n = min(count, cols - cursorX)
        for x in cursorX..<(cursorX + n) { lines[cursorY][x] = TermCell(text: "", style: TermStyle(background: style.background)) }
        wrapPending = false
    }

    // MARK: - Screens and resets

    private func switchScreen(alternate: Bool, saveCursor keep: Bool) {
        if alternate && !usingAlternateScreen {
            mainScreen = (lines, cursorX, cursorY)
            if keep { saveCursor() }
            lines = Array(repeating: Array(repeating: .blank, count: cols), count: rows)
            usingAlternateScreen = true
        } else if !alternate && usingAlternateScreen {
            if let main = mainScreen {
                lines = main.lines
                cursorX = main.x
                cursorY = main.y
            }
            mainScreen = nil
            usingAlternateScreen = false
            if keep { restoreCursor() }
        }
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
    }

    private func resetTabStops() {
        tabStops = Set(stride(from: 8, to: cols, by: 8))
    }

    private func softReset() {
        style = .plain
        cursorVisible = true
        applicationCursorKeys = false
        originMode = false
        autoWrap = true
        scrollTop = 0
        scrollBottom = rows - 1
        saved = nil
    }

    private func reset() {
        softReset()
        if usingAlternateScreen { switchScreen(alternate: false, saveCursor: false) }
        lines = Array(repeating: Array(repeating: .blank, count: cols), count: rows)
        cursorX = 0
        cursorY = 0
        wrapPending = false
        bracketedPaste = false
        resetTabStops()
    }
}

/// What the keys the phone's keyboard cannot type send to the shell.
enum TerminalKeys {
    enum Key: String, CaseIterable {
        case escape, tab, up, down, left, right, home, end, pageUp, pageDown
    }

    static func sequence(_ key: Key, applicationCursor: Bool) -> String {
        let lead = applicationCursor ? "\u{1B}O" : "\u{1B}["
        switch key {
        case .escape: return "\u{1B}"
        case .tab: return "\t"
        case .up: return lead + "A"
        case .down: return lead + "B"
        case .right: return lead + "C"
        case .left: return lead + "D"
        case .home: return lead + "H"
        case .end: return lead + "F"
        case .pageUp: return "\u{1B}[5~"
        case .pageDown: return "\u{1B}[6~"
        }
    }

    /// A letter typed while Ctrl is held: its control character (Ctrl+C is 0x03). Anything else is
    /// sent as typed.
    static func control(_ text: String) -> String {
        guard text.count == 1, let scalar = text.lowercased().unicodeScalars.first else { return text }
        switch scalar {
        case "a"..."z": return String(UnicodeScalar(scalar.value - 0x60)!)
        case "[": return "\u{1B}"
        case "\\": return "\u{1C}"
        case "]": return "\u{1D}"
        case " ", "@", "2": return "\u{0}"
        default: return text
        }
    }

    /// What the keyboard typed, as the shell expects it: Return is a carriage return.
    static func typed(_ text: String) -> String {
        text.replacingOccurrences(of: "\n", with: "\r")
    }

    /// Pasted text, wrapped when the program asked for bracketed paste.
    static func paste(_ text: String, bracketed: Bool) -> String {
        let body = text.replacingOccurrences(of: "\r\n", with: "\r").replacingOccurrences(of: "\n", with: "\r")
        return bracketed ? "\u{1B}[200~" + body + "\u{1B}[201~" : body
    }
}

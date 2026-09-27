package hub.core.android.terminal

/*
 * The owner's terminal on the phone (DECISIONS §70): a small screen emulator for what the hub's
 * shell writes (`terminal.output`, escape sequences included). It keeps a grid of [rows] × [cols]
 * under a scrollback, moves the cursor, erases, wraps and scrolls as a VT100 does, and leaves out
 * what a phone screen does not draw (colours, titles, bracketed paste, mouse modes). Enough for a
 * prompt, commands and their output, `less`, `top` and the like; pure, so TerminalTest feeds it.
 */
class TerminalScreen(cols: Int = 80, rows: Int = 24, private val scrollback: Int = 2000) {
    var cols: Int = cols.coerceAtLeast(10); private set
    var rows: Int = rows.coerceAtLeast(4); private set

    /** Every line kept: the scrollback, then the screen (its last [rows] lines). */
    private val lines = ArrayList<StringBuilder>()
    private var row = 0
    private var col = 0
    private var savedRow = 0
    private var savedCol = 0

    private enum class State { NORMAL, ESC, CSI, OSC, OSC_ESC, CHARSET }
    private var state = State.NORMAL
    private val params = StringBuilder()

    /** Bumped on every change, so a view knows to draw again. */
    var version: Long = 0; private set

    init { repeat(this.rows) { lines += StringBuilder() } }

    private val top: Int get() = lines.size - rows

    private fun line(r: Int): StringBuilder = lines[top + r.coerceIn(0, rows - 1)]

    fun resize(cols: Int, rows: Int) {
        val c = cols.coerceAtLeast(10)
        val r = rows.coerceAtLeast(4)
        while (lines.size < r) lines.add(0, StringBuilder())
        // The cursor keeps its line: rows added above it come from the scrollback.
        val absolute = top + row
        this.cols = c
        this.rows = r
        row = (absolute - top).coerceIn(0, r - 1)
        col = col.coerceIn(0, c - 1)
        version++
    }

    fun feed(data: String) {
        for (ch in data) step(ch)
        version++
    }

    /** The lines as drawn, trailing spaces trimmed; the screen's empty end is dropped. */
    fun text(): List<String> {
        val out = lines.map { it.toString().trimEnd() }
        var end = out.size
        while (end > 1 && end > top + row + 1 && out[end - 1].isEmpty()) end--
        return out.subList(0, end)
    }

    /** Where the cursor is, as (line in [text], column). */
    fun cursor(): Pair<Int, Int> = (top + row) to col

    private fun step(ch: Char) {
        when (state) {
            State.NORMAL -> normal(ch)
            State.ESC -> escape(ch)
            State.CSI -> csi(ch)
            State.OSC -> when (ch) {
                '\u0007' -> state = State.NORMAL
                '\u001b' -> state = State.OSC_ESC
                else -> Unit
            }
            State.OSC_ESC -> state = if (ch == '\\') State.NORMAL else State.OSC
            State.CHARSET -> state = State.NORMAL
        }
    }

    private fun normal(ch: Char) {
        when (ch) {
            '\u001b' -> state = State.ESC
            '\r' -> col = 0
            '\n', '\u000b', '\u000c' -> lineFeed()
            '\b' -> col = (col - 1).coerceAtLeast(0)
            '\t' -> col = minOf(((col / 8) + 1) * 8, cols - 1)
            '\u0007', '\u0000', '\u000e', '\u000f' -> Unit
            else -> if (ch >= ' ') put(ch)
        }
    }

    private fun escape(ch: Char) {
        state = State.NORMAL
        when (ch) {
            '[' -> { state = State.CSI; params.setLength(0) }
            ']' -> state = State.OSC
            '(', ')', '*', '+' -> state = State.CHARSET
            '7' -> { savedRow = row; savedCol = col }
            '8' -> { row = savedRow; col = savedCol }
            // Reverse index: at the top, a blank line comes in above and the bottom one goes.
            'M' -> if (row > 0) row-- else { lines.add(top, StringBuilder()); lines.removeAt(lines.size - 1) }
            'D' -> lineFeed()
            'E' -> { col = 0; lineFeed() }
            'c' -> clearAll()
            else -> Unit
        }
    }

    private fun csi(ch: Char) {
        if (ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>' || ch == '!' || ch == ' ') {
            params.append(ch)
            return
        }
        state = State.NORMAL
        val private = params.startsWith("?")
        val numbers = params.toString().trimStart('?', '>', '!').trim().split(';').map { it.toIntOrNull() }
        fun n(i: Int, default: Int = 1): Int = numbers.getOrNull(i)?.takeIf { it > 0 } ?: default
        when (ch) {
            'A' -> row = (row - n(0)).coerceAtLeast(0)
            'B', 'e' -> row = (row + n(0)).coerceAtMost(rows - 1)
            'C', 'a' -> col = (col + n(0)).coerceAtMost(cols - 1)
            'D' -> col = (col - n(0)).coerceAtLeast(0)
            'E' -> { row = (row + n(0)).coerceAtMost(rows - 1); col = 0 }
            'F' -> { row = (row - n(0)).coerceAtLeast(0); col = 0 }
            'G', '`' -> col = (n(0) - 1).coerceIn(0, cols - 1)
            'd' -> row = (n(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { row = (n(0) - 1).coerceIn(0, rows - 1); col = (n(1) - 1).coerceIn(0, cols - 1) }
            'J' -> eraseDisplay(numbers.getOrNull(0) ?: 0)
            'K' -> eraseLine(numbers.getOrNull(0) ?: 0)
            'X' -> { val l = line(row); pad(l, col); for (i in col until minOf(col + n(0), l.length)) l.setCharAt(i, ' ') }
            'P' -> { val l = line(row); if (col < l.length) l.delete(col, minOf(l.length, col + n(0))) }
            '@' -> { val l = line(row); pad(l, col); l.insert(col, " ".repeat(n(0))); if (l.length > cols) l.setLength(cols) }
            'L' -> repeat(n(0)) { lines.add(top + row, StringBuilder()); lines.removeAt(lines.size - 1) }
            'M' -> repeat(n(0)) { if (top + row < lines.size) { lines.removeAt(top + row); lines.add(StringBuilder()) } }
            'S' -> repeat(n(0)) { lines.add(StringBuilder()); trim() }
            's' -> { savedRow = row; savedCol = col }
            'u' -> { row = savedRow; col = savedCol }
            'h', 'l' -> if (private && numbers.any { it == 1049 || it == 47 || it == 1047 }) clearScreen()
            else -> Unit // m (colours), r (scroll region), n, t… are not drawn on the phone
        }
    }

    private fun pad(l: StringBuilder, to: Int) { while (l.length < to) l.append(' ') }

    private fun put(ch: Char) {
        if (col >= cols) { col = 0; lineFeed() }
        val l = line(row)
        pad(l, col)
        if (col < l.length) l.setCharAt(col, ch) else l.append(ch)
        col++
    }

    private fun lineFeed() {
        if (row < rows - 1) row++ else { lines.add(StringBuilder()); trim() }
    }

    private fun trim() {
        while (lines.size > scrollback + rows) lines.removeAt(0)
    }

    private fun eraseLine(mode: Int) {
        val l = line(row)
        when (mode) {
            0 -> if (col < l.length) l.setLength(col)
            1 -> { pad(l, col + 1); for (i in 0..col) l.setCharAt(i, ' ') }
            else -> l.setLength(0)
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { eraseLine(0); for (r in row + 1 until rows) line(r).setLength(0) }
            1 -> { eraseLine(1); for (r in 0 until row) line(r).setLength(0) }
            else -> clearScreen()
        }
    }

    private fun clearScreen() { for (r in 0 until rows) line(r).setLength(0) }

    private fun clearAll() {
        lines.clear()
        repeat(rows) { lines += StringBuilder() }
        row = 0
        col = 0
    }
}

/** The keys a phone keyboard lacks, as the bytes a terminal sends. */
object TerminalKeys {
    const val TAB = "\t"
    const val ENTER = "\r"
    const val ESC = "\u001b"
    const val CTRL_C = "\u0003"
    const val CTRL_D = "\u0004"
    const val CTRL_L = "\u000c"
    const val UP = "\u001b[A"
    const val DOWN = "\u001b[B"
    const val RIGHT = "\u001b[C"
    const val LEFT = "\u001b[D"

    /** A typed line as sent: its text, then Enter. */
    fun line(text: String): String = text + ENTER
}

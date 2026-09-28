package hub.core.android.shots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import java.text.BreakIterator

/**
 * The text audit of the pseudo-locale tests (ADR 0028): every laid-out text under a node, read
 * back through `GetTextLayoutResult`, is cut off without an ellipsis or leaves a lone letter on the
 * last line of a wrapped short label.
 */
object TextAudit {
    /** Every laid-out text on screen: cut without an ellipsis, or a lone letter on a wrapped label. */
    fun of(root: SemanticsNode): List<String> {
        val found = mutableListOf<String>()
        fun visit(node: SemanticsNode) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
            if (action != null) {
                val results = mutableListOf<TextLayoutResult>()
                action.action?.invoke(results)
                for (layout in results) check(node, layout)?.let(found::add)
            }
            node.children.forEach(::visit)
        }
        visit(root)
        return found
    }

    fun check(node: SemanticsNode, layout: TextLayoutResult): String? {
        val text = layout.layoutInput.text.text
        if (text.isBlank() || node.boundsInRoot.width <= 0f) return null
        val tag = node.config.getOrNull(SemanticsProperties.TestTag) ?: ""
        val last = layout.lineCount - 1
        // Sideways past its box, lines hidden by `maxLines`, or lines taller than the box, with no
        // ellipsis to say so. (A layout's own `didOverflowWidth` compares with the width it was
        // offered, not the box it got, so the lines are measured instead.)
        val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
        // Offered unbounded width (a scrolling row), a text is never cut sideways; its line
        // positions are then too large for float precision to compare.
        val sideways = layout.layoutInput.constraints.hasBoundedWidth && widest > layout.size.width + 1
        val hidden = layout.multiParagraph.didExceedMaxLines
        val tall = layout.multiParagraph.height > layout.size.height + 1
        if ((sideways || hidden || tall) && !layout.isLineEllipsized(last))
            return "cut without an ellipsis${if (tag.isNotEmpty()) " [$tag]" else ""}: “$text”" +
                " (${if (sideways) "${widest.toInt()}px in ${layout.size.width}px" else if (hidden) "lines past maxLines" else "${layout.multiParagraph.height.toInt()}px tall in ${layout.size.height}px"})"
        if (layout.lineCount > 1 && text.length <= 48) {
            val tail = text.substring(layout.getLineStart(last), layout.getLineEnd(last)).trim()
            val letters = BreakIterator.getCharacterInstance().run {
                setText(tail)
                var count = 0
                var start = first()
                var end = next()
                while (end != BreakIterator.DONE) {
                    if (tail.substring(start, end).any(Char::isLetterOrDigit)) count += 1
                    start = end
                    end = next()
                }
                count
            }
            if (letters == 1) return "a lone letter on the last line${if (tag.isNotEmpty()) " [$tag]" else ""}: “$text”"
        }
        return null
    }

}

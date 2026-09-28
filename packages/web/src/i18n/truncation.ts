/**
 * A label cut short with an ellipsis says its whole text on hover and focus (ADR 0028).
 *
 * Every single-line label in the kit — a button, a tab, a chip, a badge, a nav row, a column
 * header — ends in an ellipsis when a translation is longer than its place. One listener on the
 * document gives such a label a `title` with its full text while, and only while, it is cut: a
 * label that fits gets nothing, and one that already names itself (its own `title`, or one of
 * our tooltips) is left alone. No screen has to remember it.
 */
const MARK = 'data-truncated-title';
// Our tooltip's trigger (Radix) carries one of these while closed or opening.
const TOOLTIP_STATES = new Set(['closed', 'delayed-open', 'instant-open']);

function hasTooltip(element: Element): boolean {
  const trigger = element.closest('button, a, [role]');
  const state = trigger?.getAttribute('data-state');
  return !!state && TOOLTIP_STATES.has(state);
}

/** Refreshes the title of the ellipsis-cut element at or just above `target`. */
export function titleIfTruncated(target: EventTarget | null): void {
  let node = target instanceof Element ? target : null;
  for (let depth = 0; node && depth < 4; depth += 1, node = node.parentElement) {
    if (!(node instanceof HTMLElement)) continue;
    if (node.hasAttribute('title') && !node.hasAttribute(MARK)) return;
    if (getComputedStyle(node).textOverflow !== 'ellipsis') continue;
    const cut = node.scrollWidth > node.clientWidth + 1;
    if (cut && !hasTooltip(node)) {
      node.title = (node.textContent ?? '').replace(/\s+/g, ' ').trim();
      node.setAttribute(MARK, '');
    } else if (!cut && node.hasAttribute(MARK)) {
      node.removeAttribute('title');
      node.removeAttribute(MARK);
    }
    return;
  }
}

export function installTruncationTitles(doc: Document = document): () => void {
  const listener = (event: Event) => titleIfTruncated(event.target);
  doc.addEventListener('pointerover', listener, { passive: true });
  doc.addEventListener('focusin', listener);
  return () => {
    doc.removeEventListener('pointerover', listener);
    doc.removeEventListener('focusin', listener);
  };
}

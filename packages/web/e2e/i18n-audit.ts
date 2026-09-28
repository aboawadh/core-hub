// The space audit the pseudo-locale journeys run on every screen (ADR 0028). It runs inside the
// page (`page.evaluate(auditLayout)`) and returns what would look broken to a person reading a
// longer, wider or taller language:
//
// - `overflow`: a single-line element (a button, tab, chip, badge, nav item, table or column
//   header — anything with `white-space: nowrap`) whose text runs past its box without being
//   cut with an ellipsis;
// - `clipped`: text cut off by `overflow: hidden` without an ellipsis, sideways or — for tall
//   scripts under a tight line height — at the top or bottom;
// - `lone-letter`: a short label or title that wrapped and left one letter on its last line
//   (the iPhone task-board column title, PR #210);
// - `overlap`: two controls drawn over each other;
// - `page-scroll`: the page itself scrolls sideways.
//
// Nothing here knows a screen: the rules are about boxes and text, so a new screen is covered by
// visiting it. An element (and what is inside it) opts out with `data-i18n-audit="skip"` —
// for content that is meant to scroll sideways, like code.

export interface AuditFinding {
  rule: 'overflow' | 'clipped' | 'lone-letter' | 'overlap' | 'page-scroll';
  /** A short CSS path to the element, for the report. */
  where: string;
  text: string;
  detail: string;
}

/** Runs in the browser. Self-contained: `page.evaluate` sends its source, not its closure. */
export function auditLayout(): AuditFinding[] {
  const findings: AuditFinding[] = [];
  const seen = new Set<string>();
  const add = (finding: AuditFinding) => {
    const key = `${finding.rule}|${finding.where}|${finding.text}`;
    if (seen.has(key)) return;
    seen.add(key);
    findings.push(finding);
  };

  const describe = (element: Element): string => {
    const parts: string[] = [];
    let node: Element | null = element;
    for (let depth = 0; node && depth < 4; depth += 1) {
      const testId = node.getAttribute('data-testid');
      const cls = [...node.classList]
        .filter((c) => c.startsWith('ch-'))
        .slice(0, 2)
        .join('.');
      parts.unshift(
        `${node.tagName.toLowerCase()}${testId ? `[data-testid=${testId}]` : ''}${cls ? `.${cls}` : ''}`,
      );
      if (testId) break;
      node = node.parentElement;
    }
    return parts.join(' > ');
  };
  const textOf = (element: Element) => (element.textContent ?? '').replace(/\s+/g, ' ').trim();

  const skipped = (element: Element) => element.closest('[data-i18n-audit="skip"]') !== null;
  /**
   * The part of an element a person can see: its box cut by every ancestor that clips (a
   * scrolled list, a card with `overflow: hidden`) and by the window. Null when nothing shows.
   */
  const shownBox = (element: Element): DOMRect | null => {
    if (!(element instanceof HTMLElement)) return null;
    const box = element.getBoundingClientRect();
    let left = Math.max(box.left, 0);
    let top = Math.max(box.top, 0);
    let right = Math.min(box.right, innerWidth);
    let bottom = Math.min(box.bottom, innerHeight);
    for (
      let node = element.parentElement;
      node && node !== document.body;
      node = node.parentElement
    ) {
      const style = getComputedStyle(node);
      if (style.overflowX === 'visible' && style.overflowY === 'visible') continue;
      const clip = node.getBoundingClientRect();
      if (style.overflowX !== 'visible') {
        left = Math.max(left, clip.left);
        right = Math.min(right, clip.right);
      }
      if (style.overflowY !== 'visible') {
        top = Math.max(top, clip.top);
        bottom = Math.min(bottom, clip.bottom);
      }
    }
    if (right - left < 1 || bottom - top < 1) return null;
    return new DOMRect(left, top, right - left, bottom - top);
  };
  const visible = (element: Element) => {
    if (!(element instanceof HTMLElement)) return false;
    if (!shownBox(element)) return false;
    const style = getComputedStyle(element);
    if (style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
    // A visually hidden label (`sr-only`) is for screen readers; it has no box to overflow.
    const box = element.getBoundingClientRect();
    if (style.position === 'absolute' && box.width <= 1 && box.height <= 1) return false;
    return !element.closest('[aria-hidden="true"], [hidden], [inert]');
  };
  // Inside a box that scrolls sideways (a code block, a wide table), running long is the point.
  const scrollsSideways = (element: Element) => {
    for (let node = element.parentElement; node; node = node.parentElement) {
      const overflowX = getComputedStyle(node).overflowX;
      if ((overflowX === 'auto' || overflowX === 'scroll') && node !== document.documentElement)
        return node.scrollWidth > node.clientWidth + 1 ? true : false;
    }
    return false;
  };

  const measure = document.createElement('canvas').getContext('2d');
  /** Where a one-line element's glyphs run past its clipping box, or null. */
  const inkOutside = (element: HTMLElement, style: CSSStyleDeclaration, text: string) => {
    if (!measure) return null;
    const fontSize = Number.parseFloat(style.fontSize);
    const lineHeight = Number.parseFloat(style.lineHeight) || fontSize * 1.2;
    const paddingTop = Number.parseFloat(style.paddingTop) || 0;
    const paddingBottom = Number.parseFloat(style.paddingBottom) || 0;
    const inner = element.clientHeight - paddingTop - paddingBottom;
    if (inner > lineHeight * 1.5) return null; // more than one line: not measured here
    measure.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
    const metrics = measure.measureText(text);
    const ascent = metrics.fontBoundingBoxAscent;
    const descent = metrics.fontBoundingBoxDescent;
    const box = element.getBoundingClientRect();
    const top = box.top + (Number.parseFloat(style.borderTopWidth) || 0);
    const baseline = top + paddingTop + (inner - (ascent + descent)) / 2 + ascent;
    const inkTop = baseline - metrics.actualBoundingBoxAscent;
    const inkBottom = baseline + metrics.actualBoundingBoxDescent;
    const clipTop = top;
    const clipBottom = top + element.clientHeight;
    if (inkTop < clipTop - 1)
      return `${Math.round(clipTop - inkTop)}px of the glyphs cut at the top of a ${Math.round(element.clientHeight)}px box`;
    if (inkBottom > clipBottom + 1)
      return `${Math.round(inkBottom - clipBottom)}px of the glyphs cut at the bottom of a ${Math.round(element.clientHeight)}px box`;
    return null;
  };

  // ---- elements that carry text directly ------------------------------------------------
  const carriers: HTMLElement[] = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if (!(node.textContent ?? '').trim()) continue;
    const parent = node.parentElement;
    if (!parent || carriers.includes(parent)) continue;
    if (['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEXTAREA', 'OPTION'].includes(parent.tagName)) continue;
    carriers.push(parent);
  }

  for (const element of carriers) {
    if (skipped(element) || !visible(element)) continue;
    const style = getComputedStyle(element);
    const text = textOf(element);
    if (!text) continue;
    const singleLine = style.whiteSpace === 'nowrap' || style.whiteSpace === 'pre';
    const ellipsis = style.textOverflow === 'ellipsis';
    const hiddenX = style.overflowX === 'hidden' || style.overflowX === 'clip';
    const hiddenY = style.overflowY === 'hidden' || style.overflowY === 'clip';
    const wide = element.scrollWidth > element.clientWidth + 1;
    // Inline elements have no scroll box: measure the nearest block that holds them.
    if (style.display === 'inline') {
      const block = element.parentElement;
      if (block && visible(block)) {
        const blockStyle = getComputedStyle(block);
        const blockWide = block.scrollWidth > block.clientWidth + 1;
        if (
          blockWide &&
          blockStyle.whiteSpace !== 'normal' &&
          blockStyle.overflowX === 'visible' &&
          !scrollsSideways(block)
        )
          add({
            rule: 'overflow',
            where: describe(block),
            text,
            detail: `${block.scrollWidth}px of text in ${block.clientWidth}px`,
          });
      }
      continue;
    }
    if (wide && !scrollsSideways(element)) {
      if (hiddenX && !ellipsis)
        add({
          rule: 'clipped',
          where: describe(element),
          text,
          detail: `${element.scrollWidth}px cut to ${element.clientWidth}px without an ellipsis`,
        });
      else if (!hiddenX && singleLine)
        add({
          rule: 'overflow',
          where: describe(element),
          text,
          detail: `${element.scrollWidth}px of text in ${element.clientWidth}px`,
        });
    }
    // Tall scripts: ink cut at the top or bottom by a box that clips. The box's scroll height
    // counts the font's whole em box, which Arabic and accented fonts exceed without any glyph
    // being cut, so the glyphs' own ink is measured (canvas `measureText`) and placed on the
    // line the way the browser does: half the leading above the font's ascent.
    if (hiddenY) {
      const clipped = inkOutside(element, style, text);
      if (clipped) add({ rule: 'clipped', where: describe(element), text, detail: clipped });
    }
    // A short label or title that wrapped: its last line must hold more than one letter.
    if (!singleLine && [...text].length > 2 && [...text].length <= 48) {
      const textNodes = [...element.childNodes].filter(
        (child): child is Text =>
          child.nodeType === Node.TEXT_NODE && !!(child as Text).data.trim(),
      );
      const last = textNodes[textNodes.length - 1];
      if (!last) continue;
      const range = document.createRange();
      range.selectNodeContents(last);
      const rects = [...range.getClientRects()].filter((rect) => rect.width > 0);
      if (rects.length < 2) continue;
      const lastTop = rects[rects.length - 1]!.top;
      // The first character of the last line: the first whose box starts on that line.
      const data = last.data.replace(/\s+$/, '');
      let first = data.length;
      for (let index = data.length - 1; index >= 0; index -= 1) {
        range.setStart(last, index);
        range.setEnd(last, index + 1);
        const box = range.getBoundingClientRect();
        if (box.width === 0) continue;
        if (Math.abs(box.top - lastTop) > 2) break;
        first = index;
      }
      const tail = data.slice(first).trim();
      const graphemes = [
        ...new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(tail),
      ].filter((segment) => /\p{L}|\p{N}/u.test(segment.segment));
      if (graphemes.length === 1 && [...data.trim()].length > 2)
        add({
          rule: 'lone-letter',
          where: describe(element),
          text,
          detail: `the last line holds only "${tail}"`,
        });
    }
  }

  // ---- rows whose content spills past their edge ---------------------------------------
  // A row of labels and controls that does not fit pushes its last item out of its box (or the
  // window) when its children cannot shrink: the `min-width: 0` a flex child needs.
  for (const element of document.querySelectorAll<HTMLElement>('body *')) {
    const style = getComputedStyle(element);
    if (!style.display.includes('flex') || style.flexDirection.startsWith('column')) continue;
    if (style.overflowX !== 'visible' || skipped(element) || !visible(element)) continue;
    if (element.scrollWidth <= element.clientWidth + 1 || scrollsSideways(element)) continue;
    // Only what is in the row itself counts: a child's own absolutely placed decoration does not.
    const box = element.getBoundingClientRect();
    const spill = [...element.children].some((child) => {
      const childStyle = getComputedStyle(child);
      if (childStyle.position === 'absolute' || childStyle.position === 'fixed') return false;
      const c = child.getBoundingClientRect();
      return c.width > 0 && (c.right > box.right + 1 || c.left < box.left - 1);
    });
    if (spill)
      add({
        rule: 'overflow',
        where: describe(element),
        text: textOf(element).slice(0, 80),
        detail: `a ${element.clientWidth}px row holds ${element.scrollWidth}px of content`,
      });
  }

  // ---- controls drawn over each other ---------------------------------------------------
  const controls = [
    ...document.querySelectorAll(
      'button, a[href], input:not([type=hidden]), select, [role="tab"], [role="button"], [role="switch"]',
    ),
  ].filter((element) => !skipped(element) && visible(element)) as HTMLElement[];
  const boxes = controls.map((element) => shownBox(element)!);
  for (let i = 0; i < controls.length; i += 1) {
    for (let j = i + 1; j < controls.length; j += 1) {
      const a = controls[i]!;
      const b = controls[j]!;
      if (a.contains(b) || b.contains(a)) continue;
      const ra = boxes[i]!;
      const rb = boxes[j]!;
      const width = Math.min(ra.right, rb.right) - Math.max(ra.left, rb.left);
      const height = Math.min(ra.bottom, rb.bottom) - Math.max(ra.top, rb.top);
      if (width <= 2 || height <= 2) continue;
      // Only controls that share a row or a stack can collide; an overlay over the page is a
      // layer, not a collision.
      const layerOf = (element: Element) =>
        element.closest(
          '[role="dialog"], [role="menu"], [role="listbox"], [data-radix-popper-content-wrapper], .ch-toast',
        );
      if (layerOf(a) !== layerOf(b)) continue;
      add({
        rule: 'overlap',
        where: `${describe(a)} ⟷ ${describe(b)}`,
        text: `${textOf(a) || a.getAttribute('aria-label') || ''} ⟷ ${textOf(b) || b.getAttribute('aria-label') || ''}`,
        detail: `${Math.round(width)}×${Math.round(height)}px overlap`,
      });
    }
  }

  // ---- the page itself ------------------------------------------------------------------
  const root = document.scrollingElement ?? document.documentElement;
  if (root.scrollWidth > innerWidth + 1)
    add({
      rule: 'page-scroll',
      where: 'html',
      text: '',
      detail: `the page is ${root.scrollWidth}px wide in a ${innerWidth}px window`,
    });

  return findings;
}

export interface SpaceSample {
  /** The catalogue key the label shows, read from its `en-XK` tag. */
  key: string | null;
  text: string;
  /** The label's class (or its parent's), which names the kind of place it is. */
  area: string;
  /** The widest text the label can show before its ellipsis, in CSS pixels. */
  width: number;
  fontSize: number;
  fontWeight: number;
}

/**
 * Runs in the browser: the room every single-line label has. For each visible label that ends
 * in an ellipsis, its text is swapped for one far too long for a moment, and the width the
 * label then takes is the most it can ever show; the text is put back before the next.
 * `pnpm i18n:limits --measure` turns these into locales/limits.json (ADR 0028).
 */
export function measureSpaces(): SpaceSample[] {
  const samples: SpaceSample[] = [];
  const labels = [...document.querySelectorAll<HTMLElement>('body *')].filter((element) => {
    if (element.closest('[data-i18n-audit="skip"], [aria-hidden="true"], [hidden]')) return false;
    const style = getComputedStyle(element);
    if (style.textOverflow !== 'ellipsis' || style.whiteSpace !== 'nowrap') return false;
    const box = element.getBoundingClientRect();
    if (box.width < 1 || box.height < 1 || box.bottom < 0 || box.top > innerHeight) return false;
    // Only a label whose words are its own text (not a row holding other labels).
    return [...element.childNodes].some(
      (node) => node.nodeType === Node.TEXT_NODE && node.textContent?.trim(),
    );
  });
  // `en-XK` appends each string's key in zero-width characters (packages/contracts
  // src/languages.ts, `keyTag`): U+2064, then four of these per byte of the key.
  const DIGITS = ['\u200C', '\u200D', '\u2060', '\uFEFF'];
  const TAG = /\u2064((?:\u200C|\u200D|\u2060|\uFEFF)+)/g;
  const readTags = (raw: string) => {
    const keys: string[] = [];
    for (const match of raw.matchAll(TAG)) {
      const digits = [...(match[1] ?? '')].map((ch) => DIGITS.indexOf(ch));
      const bytes = new Uint8Array(Math.floor(digits.length / 4));
      for (let i = 0; i < bytes.length; i += 1)
        bytes[i] =
          ((digits[i * 4] ?? 0) << 6) |
          ((digits[i * 4 + 1] ?? 0) << 4) |
          ((digits[i * 4 + 2] ?? 0) << 2) |
          (digits[i * 4 + 3] ?? 0);
      keys.push(new TextDecoder().decode(bytes));
    }
    return { keys, text: raw.replace(TAG, '') };
  };
  for (const element of labels) {
    const style = getComputedStyle(element);
    const tagged = readTags(element.textContent ?? '');
    const text = tagged.text.replace(/\s+/g, ' ').trim();
    // The label's own string is the last one: its tag closes the whole text. (Not `trimEnd`:
    // it would take the tag's U+FEFF for a space.)
    const key = (element.textContent ?? '')
      .replace(/[ \t\n\r]+$/, '')
      .match(/\u2064(?:\u200C|\u200D|\u2060|\uFEFF)+$/)
      ? (tagged.keys.at(-1) ?? null)
      : null;
    const saved = [...element.childNodes];
    element.textContent = 'W'.repeat(400);
    const padding =
      (Number.parseFloat(style.paddingInlineStart) || 0) +
      (Number.parseFloat(style.paddingInlineEnd) || 0);
    const width = element.clientWidth - padding;
    element.replaceChildren(...saved);
    const own = [...element.classList].find((c) => c.startsWith('ch-'));
    const parent = [...(element.parentElement?.classList ?? [])].find((c) => c.startsWith('ch-'));
    samples.push({
      key,
      text,
      area:
        own ??
        (parent ? `${parent} > ${element.tagName.toLowerCase()}` : element.tagName.toLowerCase()),
      width: Math.floor(width),
      fontSize: Number.parseFloat(style.fontSize),
      fontWeight: Number.parseInt(style.fontWeight, 10) || 400,
    });
  }
  return samples;
}

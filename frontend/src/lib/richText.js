/*
 * The assistant's text as blocks to render: paragraphs, bulleted and numbered lists, and inside them
 * plain text, **bold** (a button's name) and `code` (a value or a name). Never HTML: what a model
 * writes is rendered as text, whatever it contains.
 */
export function blocks(text) {
  const out = [];
  for (const line of String(text || '').replace(/\r/g, '').split('\n')) {
    const trimmed = line.trim();
    const bullet = /^[-*•]\s+(.*)$/.exec(trimmed);
    const numbered = /^(\d+)[.)]\s+(.*)$/.exec(trimmed);
    const last = out[out.length - 1];
    if (!trimmed) {
      if (last) last.closed = true;
    } else if (bullet || numbered) {
      const type = bullet ? 'ul' : 'ol';
      const item = inline(bullet ? bullet[1] : numbered[2]);
      if (last && last.type === type && !last.closed) last.items.push(item);
      else out.push({ type, items: [item], start: numbered ? Number(numbered[1]) : 1 });
    } else if (last && last.type === 'p' && !last.closed) {
      last.lines.push(inline(trimmed));
    } else if (last && last.type !== 'p' && !last.closed && /^\s{2,}/.test(line)) {
      // An indented line continues the list item above it.
      last.items[last.items.length - 1].push({ t: 'text', v: ` ${trimmed}` });
    } else {
      out.push({ type: 'p', lines: [inline(trimmed)] });
    }
  }
  return out;
}

/** Text, **bold** and `code`, in order. An unclosed marker is just text. */
export function inline(text) {
  const parts = [];
  const re = /`([^`]+)`|\*\*([^*]+)\*\*/g;
  let at = 0;
  let m;
  while ((m = re.exec(text))) {
    if (m.index > at) parts.push({ t: 'text', v: text.slice(at, m.index) });
    parts.push(m[1] !== undefined ? { t: 'code', v: m[1] } : { t: 'b', v: m[2] });
    at = m.index + m[0].length;
  }
  if (at < text.length) parts.push({ t: 'text', v: text.slice(at) });
  return parts;
}

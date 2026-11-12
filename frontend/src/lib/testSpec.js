/*
 * The test spec as grids: one block per decision, a `scenario` column, supplied variables, and
 * `=> output` expectations.
 *
 * A cell is shown as the YAML scalar it will be stored as, so `50` is the number fifty, `"50"` is
 * text, and an empty cell is "not supplied". Quoting mirrors the server's canonical writer
 * (TestSpecWriter), and parsing uses YAML 1.1 like the server's reader, so what the grid shows is
 * exactly what the file will say after a save.
 */
import YAML from 'yaml';

export const SCENARIO = 'scenario';
export const EXPECT = '=>';

export const isExpectation = (column) => String(column).trim().startsWith(EXPECT);
export const expectationName = (column) => String(column).trim().slice(EXPECT.length).trim();
export const expectationColumn = (name) => `${EXPECT} ${name}`;

export function parseSpec(text) {
  if (!text || !text.trim()) return [];
  const root = YAML.parse(text, { version: '1.1' });
  const tests = root?.tests;
  if (!Array.isArray(tests)) return [];
  return tests.map((block) => {
    const columns = (block.columns || []).map((c) => String(c));
    // The scenario is a name, plain text; every other cell is a typed value.
    const rows = (block.rows || []).map((row) =>
      columns.map((c, i) => {
        const v = Array.isArray(row) ? row[i] : undefined;
        return c === SCENARIO ? (v === null || v === undefined ? '' : String(v)) : toCellText(v);
      }));
    return { decision: String(block.decision ?? ''), columns, rows };
  });
}

export function serializeSpec(blocks) {
  if (!blocks.length) return 'tests: []\n';
  const out = ['tests:'];
  for (const block of blocks) {
    out.push(`  - decision: ${scalar(block.decision)}`);
    out.push(`    columns: [${block.columns.map((c) => (isExpectation(c) ? quote(c) : scalar(c))).join(', ')}]`);
    if (!block.rows.length) {
      out.push('    rows: []');
      continue;
    }
    out.push('    rows:');
    for (const row of block.rows) {
      out.push(`      - [${block.columns.map((c, i) => scalar(c === SCENARIO ? String(row[i] ?? '') : fromCellText(row[i] ?? ''))).join(', ')}]`);
    }
  }
  return `${out.join('\n')}\n`;
}

/** The value a typed cell stands for. Blank means not supplied. */
export function fromCellText(text) {
  const trimmed = String(text ?? '').trim();
  if (!trimmed) return null;
  try {
    const value = YAML.parse(trimmed, { version: '1.1' });
    if (value === null || ['string', 'number', 'boolean'].includes(typeof value) || Array.isArray(value)) {
      return value;
    }
  } catch {
    // not YAML on its own; it is text
  }
  return trimmed;
}

export function toCellText(value) {
  if (value === undefined || value === null) return '';
  return scalar(value);
}

export function scalar(value) {
  if (value === null || value === undefined) return '~';
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) return `[${value.map(scalar).join(', ')}]`;
  const text = String(value);
  return needsQuoting(text) ? quote(text) : text;
}

function needsQuoting(text) {
  if (!text) return true;
  if (text.trim() !== text) return true;
  if (/[,[\]{}#:"'\n]/.test(text)) return true;
  if ('-?&*!>|%@`'.includes(text[0])) return true;
  if (looksLikeNumber(text)) return true;
  if (['true', 'false', 'yes', 'no', 'on', 'off'].includes(text.toLowerCase())) return true;
  return text === '~' || text === 'null';
}

function looksLikeNumber(text) {
  return /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?[dDfF]?$/.test(text) || /^[+-]?(Infinity|NaN)$/.test(text);
}

function quote(text) {
  return `"${String(text).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

/** A fresh block for a decision, shaped from what that decision needs supplied and produces. */
export function blockFor(hint) {
  const columns = [SCENARIO, ...(hint?.suppliedVariables || []), ...(hint?.outputs || []).map(expectationColumn)];
  return { decision: hint?.decision || '', columns, rows: [columns.map(() => '')] };
}

/** A test case is identified by its decision and the values it supplies, like a rule by its inputs. */
export function caseKey(block, row) {
  const inputs = block.columns
    .map((c, i) => [c, row[i]])
    .filter(([c]) => c !== SCENARIO && !isExpectation(c))
    .map(([c, v]) => `${c}=${String(v ?? '').trim()}`)
    .sort();
  return `${block.decision}|${inputs.join('|')}`;
}

/**
 * Three-way merge of test cases, for a save refused because someone else saved first. Cases are
 * matched by their inputs; a case both sides changed differently, one side deleted while the other
 * edited, or both added with different expectations, is a conflict with a choice — the same shape the
 * server's rule conflicts have, so one resolver shows both.
 */
export function mergeSpecs(baseText, oursText, theirsText) {
  const index = (blocks) => {
    const map = new Map();
    for (const block of blocks) {
      block.rows.forEach((row) => map.set(caseKey(block, row), { block, row }));
    }
    return map;
  };
  const base = index(parseSpec(baseText));
  const ours = index(parseSpec(oursText));
  const theirs = index(parseSpec(theirsText));
  const same = (a, b) => (!a && !b) || (a && b && rowText(a) === rowText(b));
  const rowText = (e) => JSON.stringify(e.block.columns.map((c, i) => [c, e.row[i]]));

  const keys = [...new Set([...theirs.keys(), ...ours.keys(), ...base.keys()])];
  const merged = new Map();
  const conflicts = [];
  for (const key of keys) {
    const b = base.get(key);
    const o = ours.get(key);
    const t = theirs.get(key);
    if (same(o, t)) {
      if (o) merged.set(key, o);
    } else if (same(o, b)) {
      if (t) merged.set(key, t);
    } else if (same(t, b)) {
      if (o) merged.set(key, o);
    } else {
      const entry = o || t;
      const scenario = entry.row[entry.block.columns.indexOf(SCENARIO)] || 'a test case';
      const kind = !o || !t ? 'TEST_DELETE_MODIFY' : b ? 'TEST_EXPECTATION' : 'TEST_ADD_ADD';
      const describe = (e) => (e ? e.block.columns.filter(isExpectation).map((c) => `${expectationName(c)} ${e.row[e.block.columns.indexOf(c)] || '—'}`).join(', ') : 'deleted');
      conflicts.push({
        id: `tests|${key}`,
        kind,
        tier: 'SEMANTIC',
        decisionId: entry.block.decision,
        label: `${entry.block.decision}: ${scenario}`,
        ours: describe(o),
        theirs: describe(t),
        choices: [
          { key: 'OURS', label: o ? `Yours: ${describe(o)}` : 'Keep it deleted', value: o },
          { key: 'THEIRS', label: t ? `Theirs: ${describe(t)}` : 'Keep it deleted', value: t }
        ]
      });
      merged.set(key, { pending: true, key });
    }
  }
  return {
    conflicts,
    /** The merged spec once every conflict has an answer. */
    build(resolutions) {
      const blocks = new Map();
      for (const [key, entry] of merged) {
        let chosen = entry;
        if (entry.pending) {
          const conflict = conflicts.find((c) => c.id === `tests|${key}`);
          const answer = resolutions[conflict.id];
          chosen = conflict.choices.find((c) => c.key === answer)?.value;
          if (!chosen) continue;
        }
        const name = chosen.block.decision;
        if (!blocks.has(name)) blocks.set(name, { decision: name, columns: [...chosen.block.columns], rows: [] });
        const target = blocks.get(name);
        for (const column of chosen.block.columns) if (!target.columns.includes(column)) target.columns.push(column);
        target.rows.push(target.columns.map((c) => chosen.row[chosen.block.columns.indexOf(c)] ?? ''));
      }
      for (const block of blocks.values()) {
        block.rows = block.rows.map((row) => block.columns.map((_, i) => row[i] ?? ''));
      }
      return serializeSpec([...blocks.values()]);
    }
  };
}

/**
 * What changed in one decision's test cases since they were stored, keyed for TestGrid: cells as
 * `${row}|${column}`, new cases by row, new columns by name. Cases have no identity of their own while
 * being typed, so unchanged ones are matched first, in order; what is left between two matches is
 * paired in order as edited, and the rest is new or removed. A case copied, added or removed in the
 * middle so marks only itself, not every case after it.
 */
export function testEdits(saved, current) {
  const cells = {};
  const rows = {};
  const columns = {};
  let count = 0;
  if (!current) return { cells, rows, columns, count, removedRows: 0 };
  if (!saved) {
    current.rows.forEach((_, r) => { rows[r] = 'new'; });
    current.columns.forEach((c) => { columns[c] = 'new'; });
    return { cells, rows, columns, count: current.rows.length || 1, removedRows: 0 };
  }
  current.columns.forEach((c) => {
    if (!saved.columns.includes(c)) {
      columns[c] = 'new';
      count++;
    }
  });
  const shared = current.columns.filter((c) => saved.columns.includes(c));
  const cellOf = (row, columns, col) => String(row[columns.indexOf(col)] ?? '').trim();
  const keyOf = (row, columns) => shared.map((col) => cellOf(row, columns, col)).join('\u0000');
  const was = saved.rows.map((row) => keyOf(row, saved.columns));
  const now = current.rows.map((row) => keyOf(row, current.columns));
  // Longest run of unchanged cases, in order.
  const n = was.length;
  const m = now.length;
  const lcs = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) lcs[i][j] = was[i] === now[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
  }
  const anchors = [];
  for (let i = 0, j = 0; i < n && j < m;) {
    if (was[i] === now[j]) anchors.push([i++, j++]);
    else if (lcs[i + 1][j] >= lcs[i][j + 1]) i++;
    else j++;
  }
  anchors.push([n, m]);
  const alike = (before, row) => shared.filter((col) => cellOf(before, saved.columns, col) === cellOf(row, current.columns, col)).length;
  // Between two matches, each case is paired, in order, with the stored case it has most in common with;
  // one with nothing in common is new.
  let removedRows = 0;
  let fromSaved = 0;
  let fromNow = 0;
  for (const [i, j] of anchors) {
    let next = fromSaved;
    let paired = 0;
    for (let r = fromNow; r < j; r++) {
      let best = -1;
      let most = 0;
      for (let k = next; k < i; k++) {
        const same = alike(saved.rows[k], current.rows[r]);
        if (same > most) [best, most] = [k, same];
      }
      if (best < 0) {
        rows[r] = 'new';
        count++;
        continue;
      }
      current.columns.forEach((col, c) => {
        if (!saved.columns.includes(col)) return;
        if (cellOf(saved.rows[best], saved.columns, col) !== cellOf(current.rows[r], current.columns, col)) {
          cells[`${r}|${c}`] = 'edited';
          count++;
        }
      });
      next = best + 1;
      paired++;
    }
    removedRows += i - fromSaved - paired;
    fromSaved = i + 1;
    fromNow = j + 1;
  }
  const removedColumns = saved.columns.filter((c) => !current.columns.includes(c)).length;
  return { cells, rows, columns, count: count + removedRows + removedColumns, removedRows };
}

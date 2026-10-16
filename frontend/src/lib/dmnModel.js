/*
 * The DMN file as the grid sees it.
 *
 * Edits are applied to the parsed XML document itself, never to a separate model that is later turned
 * back into XML, so everything the grid does not show — diagram layout, extension elements, literal
 * expression decisions — survives a save untouched. `read()` derives the view after every edit; at a
 * few hundred cells that is cheaper than keeping two representations in step.
 */

const HIT_POLICIES = [
  { value: 'UNIQUE', label: 'Only one rule may match' },
  { value: 'FIRST', label: 'First matching rule wins' },
  { value: 'PRIORITY', label: 'Highest-priority result wins' },
  { value: 'ANY', label: 'Any may match, all must agree' },
  { value: 'COLLECT', label: 'Collect every match' },
  { value: 'RULE ORDER', label: 'Every match, in rule order' },
  { value: 'OUTPUT ORDER', label: 'Every match, by result priority' }
];

const AGGREGATIONS = [
  { value: '', label: 'as a list' },
  { value: 'SUM', label: 'added up' },
  { value: 'COUNT', label: 'counted' },
  { value: 'MIN', label: 'smallest' },
  { value: 'MAX', label: 'largest' }
];

const TYPES = ['string', 'number', 'integer', 'long', 'double', 'boolean', 'date'];

export { HIT_POLICIES, AGGREGATIONS, TYPES };

export function hitPolicyLabel(hitPolicy, aggregation) {
  const found = HIT_POLICIES.find((p) => p.value === (hitPolicy || 'UNIQUE'));
  const base = found ? found.label : hitPolicy;
  if ((hitPolicy || '') === 'COLLECT' && aggregation) {
    const agg = AGGREGATIONS.find((a) => a.value === aggregation);
    return `${base}, ${agg ? agg.label : aggregation}`;
  }
  return base;
}

const kids = (el, name) => Array.from(el?.children || []).filter((c) => c.localName === name);
const kid = (el, name) => kids(el, name)[0] || null;
const textOf = (el) => (el ? (kid(el, 'text')?.textContent ?? '') : '');

function makeId(prefix) {
  return `${prefix}_${Math.random().toString(36).slice(2, 9)}`;
}

export class DmnDocument {
  constructor(xml) {
    this.doc = new DOMParser().parseFromString(xml, 'application/xml');
    const error = this.doc.getElementsByTagName('parsererror')[0];
    if (error || !this.doc.documentElement || this.doc.documentElement.localName !== 'definitions') {
      throw new Error('This file is not a readable decision file.');
    }
    this.root = this.doc.documentElement;
  }

  static blank(name) {
    // Every id is unique to this table: a file holds several tables, and a repeated id makes it invalid.
    const k = Math.random().toString(36).slice(2, 8);
    const decisionId = `Decision_${k}`;
    return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="Definitions_${k}" name="${escapeXml(name)}" namespace="http://camunda.org/schema/1.0/dmn">
  <decision id="${decisionId}" name="${escapeXml(titleCase(name))}">
    <decisionTable id="Table_${k}" hitPolicy="FIRST">
      <input id="Input_${k}" label="Input">
        <inputExpression id="InputExpression_${k}" typeRef="string"><text>input</text></inputExpression>
      </input>
      <output id="Output_${k}" label="Result" name="result" typeRef="string" />
      <rule id="Rule_${k}">
        <inputEntry id="InputEntry_${k}"><text>-</text></inputEntry>
        <outputEntry id="OutputEntry_${k}"><text>"value"</text></outputEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>
`;
  }

  /**
   * Gives every repeated id a fresh one. Ids must be unique in a file, and a file edited before new
   * tables got unique ids may repeat them; decisions keep theirs, since other places refer to them.
   */
  ensureUniqueIds() {
    const seen = new Set();
    let changed = 0;
    for (const el of Array.from(this.doc.getElementsByTagName('*'))) {
      const id = el.getAttribute && el.getAttribute('id');
      if (!id) continue;
      if (seen.has(id) && el.localName !== 'decision') {
        let fresh;
        do fresh = `${id.replace(/_[a-z0-9]+$/i, '')}_${Math.random().toString(36).slice(2, 8)}`; while (seen.has(fresh));
        el.setAttribute('id', fresh);
        seen.add(fresh);
        changed++;
      } else {
        seen.add(id);
      }
    }
    return changed;
  }

  serialize() {
    this.ensureUniqueIds();
    let xml = new XMLSerializer().serializeToString(this.doc);
    if (!xml.startsWith('<?xml')) xml = `<?xml version="1.0" encoding="UTF-8"?>\n${xml}`;
    return xml;
  }

  // --- reading -----------------------------------------------------------------------------------

  read() {
    return kids(this.root, 'decision').map((d) => this.readDecision(d));
  }

  readDecision(d) {
    const table = kid(d, 'decisionTable');
    const requires = kids(d, 'informationRequirement')
      .map((r) => kid(r, 'requiredDecision')?.getAttribute('href') || '')
      .map((href) => href.replace(/^#/, ''))
      .filter(Boolean);
    const base = { id: d.getAttribute('id'), name: d.getAttribute('name') || d.getAttribute('id'), requires };
    if (!table) {
      return { ...base, tabular: false, inputs: [], outputs: [], rules: [], variable: kid(d, 'variable')?.getAttribute('name') || null };
    }
    const inputs = kids(table, 'input').map((i) => {
      const expr = kid(i, 'inputExpression');
      return {
        id: i.getAttribute('id'),
        label: i.getAttribute('label') || '',
        expression: textOf(expr),
        typeRef: expr?.getAttribute('typeRef') || ''
      };
    });
    const outputs = kids(table, 'output').map((o) => ({
      id: o.getAttribute('id'),
      label: o.getAttribute('label') || '',
      name: o.getAttribute('name') || '',
      typeRef: o.getAttribute('typeRef') || ''
    }));
    const rules = kids(table, 'rule').map((r) => ({
      id: r.getAttribute('id'),
      description: kid(r, 'description')?.textContent || '',
      inputs: kids(r, 'inputEntry').map(textOf),
      outputs: kids(r, 'outputEntry').map(textOf)
    }));
    return {
      ...base,
      tabular: true,
      hitPolicy: table.getAttribute('hitPolicy') || 'UNIQUE',
      aggregation: table.getAttribute('aggregation') || '',
      inputs,
      outputs,
      rules
    };
  }

  // --- element helpers ---------------------------------------------------------------------------

  create(parent, name) {
    const ns = this.root.namespaceURI;
    const prefix = this.root.prefix;
    return this.doc.createElementNS(ns, prefix ? `${prefix}:${name}` : name);
  }

  textElement(parent, name, text) {
    const el = this.create(parent, name);
    const t = this.create(el, 'text');
    t.textContent = text;
    el.appendChild(t);
    return el;
  }

  decision(id) {
    const d = kids(this.root, 'decision').find((x) => x.getAttribute('id') === id);
    if (!d) throw new Error(`No decision ${id}`);
    return d;
  }

  table(id) {
    return kid(this.decision(id), 'decisionTable');
  }

  rule(decisionId, index) {
    return kids(this.table(decisionId), 'rule')[index];
  }

  /** Inserts after the last child of any of the given names, or before the first of `before`. */
  insertInOrder(parent, el, afterNames, beforeNames) {
    const children = Array.from(parent.children);
    let anchor = null;
    for (const c of children) if (afterNames.includes(c.localName)) anchor = c;
    if (anchor) {
      parent.insertBefore(el, anchor.nextElementSibling);
      return;
    }
    const first = children.find((c) => beforeNames.includes(c.localName));
    parent.insertBefore(el, first || null);
  }

  // --- decisions ---------------------------------------------------------------------------------

  renameDecision(id, name) {
    this.decision(id).setAttribute('name', name);
  }

  addDecision(name) {
    const probe = new DmnDocument(DmnDocument.blank(name));
    const imported = this.doc.importNode(probe.root.getElementsByTagNameNS('*', 'decision')[0], true);
    const lastDecision = kids(this.root, 'decision').pop();
    this.root.insertBefore(imported, lastDecision ? lastDecision.nextElementSibling : null);
    // Its result is named after the table, so it never clashes with another table's.
    const output = imported.getElementsByTagNameNS('*', 'output')[0];
    output.setAttribute('name', this.uniqueResultName(camel(name), imported.getAttribute('id')));
    output.setAttribute('label', name);
    return imported.getAttribute('id');
  }

  /** A result name no other table in this file gives. */
  uniqueResultName(base, exceptId = null) {
    const taken = new Set(this.read().filter((d) => d.id !== exceptId).flatMap((d) => (d.tabular ? d.outputs.map((o) => o.name) : [d.variable])));
    let name = base || 'result';
    for (let n = 2; taken.has(name); n++) name = `${base}${n}`;
    return name;
  }

  removeDecision(id) {
    const d = this.decision(id);
    d.parentNode.removeChild(d);
    // Nothing may keep requiring a decision that no longer exists.
    for (const other of kids(this.root, 'decision')) {
      for (const req of kids(other, 'informationRequirement')) {
        if (kid(req, 'requiredDecision')?.getAttribute('href') === `#${id}`) other.removeChild(req);
      }
    }
    this.dropDiagramRefs(id);
  }

  dropDiagramRefs(id) {
    for (const shape of Array.from(this.doc.getElementsByTagNameNS('*', 'DMNShape'))) {
      if (shape.getAttribute('dmnElementRef') === id) shape.parentNode.removeChild(shape);
    }
  }

  setHitPolicy(id, hitPolicy, aggregation) {
    const table = this.table(id);
    table.setAttribute('hitPolicy', hitPolicy);
    if (hitPolicy === 'COLLECT' && aggregation) table.setAttribute('aggregation', aggregation);
    else table.removeAttribute('aggregation');
  }

  // --- relations ---------------------------------------------------------------------------------

  addRequirement(decisionId, requiredId) {
    const d = this.decision(decisionId);
    const exists = kids(d, 'informationRequirement')
      .some((r) => kid(r, 'requiredDecision')?.getAttribute('href') === `#${requiredId}`);
    if (exists || decisionId === requiredId) return;
    const req = this.create(d, 'informationRequirement');
    req.setAttribute('id', makeId('InformationRequirement'));
    const target = this.create(req, 'requiredDecision');
    target.setAttribute('href', `#${requiredId}`);
    req.appendChild(target);
    this.insertInOrder(d, req, ['description', 'extensionElements', 'question', 'allowedAnswers', 'variable', 'informationRequirement'],
      ['knowledgeRequirement', 'authorityRequirement', 'decisionTable', 'literalExpression']);
  }

  removeRequirement(decisionId, requiredId) {
    const d = this.decision(decisionId);
    for (const req of kids(d, 'informationRequirement')) {
      if (kid(req, 'requiredDecision')?.getAttribute('href') === `#${requiredId}`) {
        const reqId = req.getAttribute('id');
        d.removeChild(req);
        for (const edge of Array.from(this.doc.getElementsByTagNameNS('*', 'DMNEdge'))) {
          if (reqId && edge.getAttribute('dmnElementRef') === reqId) edge.parentNode.removeChild(edge);
        }
      }
    }
  }

  /**
   * Keeps "uses" records in step with names: a table that checks a value another table in this file
   * gives uses that table; one that no longer checks any of its results stops using it. Never makes a
   * loop. Returns what it changed, so the caller can say so.
   */
  syncRequirements() {
    const decisions = this.read();
    const byId = new Map(decisions.map((d) => [d.id, d]));
    const producer = new Map();
    for (const d of decisions) {
      for (const name of (d.tabular ? d.outputs.map((o) => o.name) : [d.variable || d.name])) {
        if (name && !producer.has(name)) producer.set(name, d.id);
      }
    }
    const uses = new Map(decisions.map((d) => [d.id, new Set(d.requires)]));
    const reaches = (from, to, seen = new Set()) => {
      if (from === to) return true;
      if (seen.has(from)) return false;
      seen.add(from);
      return [...(uses.get(from) || [])].some((next) => reaches(next, to, seen));
    };
    const added = [];
    const removed = [];
    for (const d of decisions) {
      const wanted = new Set();
      for (const input of d.inputs) {
        const p = producer.get(input.expression.trim());
        if (p && p !== d.id && !reaches(p, d.id)) wanted.add(p);
      }
      for (const p of wanted) {
        if (!uses.get(d.id).has(p)) {
          this.addRequirement(d.id, p);
          uses.get(d.id).add(p);
          added.push({ to: d.id, from: p });
        }
      }
      for (const r of [...uses.get(d.id)]) {
        if (byId.has(r) && !wanted.has(r)) {
          this.removeRequirement(d.id, r);
          uses.get(d.id).delete(r);
          removed.push({ to: d.id, from: r });
        }
      }
    }
    return { added, removed };
  }

  // --- rules -------------------------------------------------------------------------------------

  setCell(decisionId, ruleIndex, side, columnIndex, value) {
    const entries = kids(this.rule(decisionId, ruleIndex), side === 'in' ? 'inputEntry' : 'outputEntry');
    const entry = entries[columnIndex];
    let t = kid(entry, 'text');
    if (!t) {
      t = this.create(entry, 'text');
      entry.appendChild(t);
    }
    t.textContent = value;
  }

  setDescription(decisionId, ruleIndex, value) {
    const rule = this.rule(decisionId, ruleIndex);
    let desc = kid(rule, 'description');
    if (!value) {
      if (desc) rule.removeChild(desc);
      return;
    }
    if (!desc) {
      desc = this.create(rule, 'description');
      rule.insertBefore(desc, rule.firstElementChild);
    }
    desc.textContent = value;
  }

  addRule(decisionId, afterIndex) {
    const table = this.table(decisionId);
    const rules = kids(table, 'rule');
    const rule = this.create(table, 'rule');
    rule.setAttribute('id', makeId('Rule'));
    for (let i = 0; i < kids(table, 'input').length; i++) {
      const e = this.textElement(rule, 'inputEntry', '-');
      e.setAttribute('id', makeId('UnaryTests'));
      rule.appendChild(e);
    }
    for (let i = 0; i < kids(table, 'output').length; i++) {
      const e = this.textElement(rule, 'outputEntry', '');
      e.setAttribute('id', makeId('LiteralExpression'));
      rule.appendChild(e);
    }
    const anchor = afterIndex == null || afterIndex >= rules.length - 1 ? null : rules[afterIndex + 1];
    if (anchor) table.insertBefore(rule, anchor);
    else table.appendChild(rule);
    return afterIndex == null ? rules.length : afterIndex + 1;
  }

  removeRule(decisionId, index) {
    const rule = this.rule(decisionId, index);
    rule.parentNode.removeChild(rule);
  }

  moveRule(decisionId, index, delta) {
    const table = this.table(decisionId);
    const rules = kids(table, 'rule');
    const target = index + delta;
    if (target < 0 || target >= rules.length) return index;
    const rule = rules[index];
    if (delta < 0) table.insertBefore(rule, rules[target]);
    else table.insertBefore(rule, rules[target].nextElementSibling);
    return target;
  }

  // --- columns -----------------------------------------------------------------------------------

  updateInput(decisionId, index, { label, expression, typeRef }) {
    const input = kids(this.table(decisionId), 'input')[index];
    if (label !== undefined) {
      if (label) input.setAttribute('label', label);
      else input.removeAttribute('label');
    }
    let expr = kid(input, 'inputExpression');
    if (!expr) {
      expr = this.textElement(input, 'inputExpression', '');
      expr.setAttribute('id', makeId('InputExpression'));
      input.appendChild(expr);
    }
    if (expression !== undefined) {
      let t = kid(expr, 'text');
      if (!t) {
        t = this.create(expr, 'text');
        expr.appendChild(t);
      }
      t.textContent = expression;
    }
    if (typeRef !== undefined) expr.setAttribute('typeRef', typeRef);
  }

  updateOutput(decisionId, index, { label, name, typeRef }) {
    const output = kids(this.table(decisionId), 'output')[index];
    if (label !== undefined) {
      if (label) output.setAttribute('label', label);
      else output.removeAttribute('label');
    }
    if (name !== undefined) output.setAttribute('name', name);
    if (typeRef !== undefined) output.setAttribute('typeRef', typeRef);
  }

  addInput(decisionId, { label, expression, typeRef }) {
    const table = this.table(decisionId);
    const input = this.create(table, 'input');
    input.setAttribute('id', makeId('Input'));
    if (label) input.setAttribute('label', label);
    const expr = this.textElement(input, 'inputExpression', expression);
    expr.setAttribute('id', makeId('InputExpression'));
    expr.setAttribute('typeRef', typeRef || 'string');
    input.appendChild(expr);
    this.insertInOrder(table, input, ['input'], ['output', 'annotation', 'rule']);
    for (const rule of kids(table, 'rule')) {
      const entry = this.textElement(rule, 'inputEntry', '-');
      entry.setAttribute('id', makeId('UnaryTests'));
      this.insertInOrder(rule, entry, ['description', 'inputEntry'], ['outputEntry', 'annotationEntry']);
    }
  }

  addOutput(decisionId, { label, name, typeRef }) {
    const table = this.table(decisionId);
    const output = this.create(table, 'output');
    output.setAttribute('id', makeId('Output'));
    if (label) output.setAttribute('label', label);
    output.setAttribute('name', name);
    output.setAttribute('typeRef', typeRef || 'string');
    this.insertInOrder(table, output, ['input', 'output'], ['annotation', 'rule']);
    for (const rule of kids(table, 'rule')) {
      const entry = this.textElement(rule, 'outputEntry', '');
      entry.setAttribute('id', makeId('LiteralExpression'));
      this.insertInOrder(rule, entry, ['description', 'inputEntry', 'outputEntry'], ['annotationEntry']);
    }
  }

  removeColumn(decisionId, side, index) {
    const table = this.table(decisionId);
    const column = kids(table, side === 'in' ? 'input' : 'output')[index];
    if (side === 'out' && kids(table, 'output').length <= 1) {
      throw new Error('A table needs at least one result.');
    }
    table.removeChild(column);
    for (const rule of kids(table, 'rule')) {
      const entry = kids(rule, side === 'in' ? 'inputEntry' : 'outputEntry')[index];
      if (entry) rule.removeChild(entry);
    }
  }
}

/**
 * How decisions in one file feed each other: a decision's result feeds another when that other one
 * requires it, and the link is named by the input that reads the result.
 */
export function relationsOf(decisions) {
  const byId = new Map(decisions.map((d) => [d.id, d]));
  const resultNames = (d) => (d.tabular ? d.outputs.map((o) => o.name) : [d.variable || d.name]).filter(Boolean);
  // The first table giving a name is the one tables checking that name use (as syncRequirements).
  const producers = new Map();
  const givers = new Map();
  for (const d of decisions) {
    for (const name of resultNames(d)) {
      if (!producers.has(name)) producers.set(name, d.id);
      givers.set(name, [...(givers.get(name) || []), d.id]);
    }
  }
  // Several tables giving one name: only the first can be used by it, so say so.
  const clashes = [...givers].filter(([, ids]) => ids.length > 1).map(([name, ids]) => ({ name, ids }));

  const links = [];
  const suggestions = [];
  for (const d of decisions) {
    for (const reqId of d.requires) {
      const from = byId.get(reqId);
      if (!from) {
        links.push({ from: reqId, to: d.id, via: [], missing: true });
        continue;
      }
      const names = resultNames(from);
      const via = d.inputs.filter((i) => names.includes(i.expression.trim())).map((i) => i.expression.trim());
      links.push({ from: reqId, to: d.id, via, missing: false });
    }
    for (const input of d.inputs) {
      const producer = producers.get(input.expression.trim());
      if (producer && producer !== d.id && !d.requires.includes(producer)) {
        suggestions.push({ from: producer, to: d.id, via: input.expression.trim() });
      }
    }
  }

  // Depth from the sources, so the picture always reads left to right in the direction data flows —
  // counting results a table already reads but has not been connected to, so even those point right.
  const feedsFrom = new Map(decisions.map((d) => [d.id, new Set(d.requires.filter((r) => byId.has(r)))]));
  for (const sug of suggestions) feedsFrom.get(sug.to)?.add(sug.from);
  const depth = new Map();
  const visit = (id, trail = new Set()) => {
    if (depth.has(id)) return depth.get(id);
    if (trail.has(id)) return 0;
    trail.add(id);
    const from = [...(feedsFrom.get(id) || [])];
    const level = from.length ? 1 + Math.max(...from.map((r) => visit(r, trail))) : 0;
    depth.set(id, level);
    return level;
  };
  decisions.forEach((d) => visit(d.id));
  return { links, suggestions, depth, clashes };
}

function escapeXml(s) {
  return String(s).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
}

function camel(s) {
  const words = String(s).replace(/[^A-Za-z0-9]+/g, ' ').trim().split(' ').filter(Boolean);
  const out = words.map((w, i) => (i === 0 ? w.charAt(0).toLowerCase() + w.slice(1) : w.charAt(0).toUpperCase() + w.slice(1))).join('');
  return /^[A-Za-z_]/.test(out) ? out : `t${out}`;
}

function titleCase(s) {
  return String(s).replace(/[-_]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

/**
 * What changed between two readings of a file, keyed the way RuleGrid marks things: cells by
 * `${ruleId}|${columnId}`, rules by id, columns by id, plus decisions whose name or match policy moved.
 * Rules and columns are matched by id, so moving a rule or renaming a column is one change, not many.
 */
export function editStates(before, after) {
  const cells = {};
  const rows = {};
  const columns = {};
  const decisions = {};
  let removedRules = 0;
  let count = 0;
  const beforeById = new Map(before.map((d) => [d.id, d]));
  for (const d of after) {
    const b = beforeById.get(d.id);
    if (!b) {
      decisions[d.id] = 'new';
      count++;
      continue;
    }
    if (b.name !== d.name || b.hitPolicy !== d.hitPolicy || b.aggregation !== d.aggregation) {
      decisions[d.id] = 'edited';
      count++;
    }
    if (!d.tabular || !b.tabular) continue;
    const colIndex = (cols, id) => cols.findIndex((c) => c.id === id);
    for (const side of ['inputs', 'outputs']) {
      for (const c of d[side]) {
        const old = b[side][colIndex(b[side], c.id)];
        if (!old || JSON.stringify(old) !== JSON.stringify(c)) {
          columns[c.id] = old ? 'edited' : 'new';
          count++;
        }
      }
    }
    const beforeRules = new Map(b.rules.map((r, i) => [r.id, { r, i }]));
    d.rules.forEach((rule, i) => {
      const old = beforeRules.get(rule.id);
      if (!old) {
        rows[rule.id] = 'new';
        count++;
        return;
      }
      if (old.r.description !== rule.description) {
        cells[`${rule.id}|description`] = 'edited';
        count++;
      }
      for (const [side, entries] of [['inputs', 'inputs'], ['outputs', 'outputs']]) {
        d[side].forEach((col, c) => {
          const oldIndex = colIndex(b[side], col.id);
          const oldValue = oldIndex < 0 ? undefined : old.r[entries][oldIndex];
          if (oldValue !== rule[entries][c]) {
            cells[`${rule.id}|${col.id}`] = 'edited';
            count++;
          }
        });
      }
      if (old.i !== i && !rows[rule.id]) rows[rule.id] = 'moved';
    });
    const afterIds = new Set(d.rules.map((r) => r.id));
    removedRules += b.rules.filter((r) => !afterIds.has(r.id)).length;
  }
  count += removedRules;
  return { cells, rows, columns, decisions, removedRules, count };
}

/**
 * The values someone must supply to evaluate a decision: every input of it and of the decisions it
 * uses, minus whatever those decisions produce themselves.
 */
export function suppliedVariables(decisions, id) {
  const byId = new Map(decisions.map((d) => [d.id, d]));
  const seen = new Set();
  const inputs = [];
  const produced = new Set();
  const visit = (did) => {
    if (seen.has(did) || !byId.has(did)) return;
    seen.add(did);
    const d = byId.get(did);
    d.requires.forEach(visit);
    d.inputs.forEach((i) => { if (i.expression.trim()) inputs.push({ name: i.expression.trim(), typeRef: i.typeRef, label: i.label }); });
    (d.tabular ? d.outputs.map((o) => o.name) : [d.variable || d.name]).forEach((n) => produced.add(n));
  };
  visit(id);
  const out = [];
  const names = new Set();
  for (const i of inputs) {
    if (!produced.has(i.name) && !names.has(i.name)) {
      names.add(i.name);
      out.push(i);
    }
  }
  return out;
}

/**
 * What a decision's test cases really exercise. A test of a connected table supplies values at the
 * start of its chain and runs every table on the way, so it is a test of the chain; and a table
 * further back is also run by the cases of every table that uses it.
 */
export function chainNoteFor(decisions, id, blocks = []) {
  const byId = new Map(decisions.map((d) => [d.id, d]));
  const order = [];
  const seen = new Set();
  const visit = (did) => {
    if (seen.has(did) || !byId.has(did)) return;
    seen.add(did);
    byId.get(did).requires.forEach(visit);
    order.push(byId.get(did).name);
  };
  visit(id);
  const users = [];
  const reached = new Set([id]);
  let frontier = [id];
  while (frontier.length) {
    const next = decisions.filter((d) => !reached.has(d.id) && d.requires.some((r) => frontier.includes(r)));
    next.forEach((d) => { reached.add(d.id); users.push(d); });
    frontier = next.map((d) => d.id);
  }
  const casesOf = (d) => blocks.filter((b) => b.decision === d.id || b.decision === d.name).reduce((n, b) => n + b.rows.length, 0);
  const parts = [];
  if (order.length > 1) {
    parts.push(`These cases supply values at the start of ${order.join(' → ')} and check what ${byId.get(id).name} gives, so they test the whole chain.`);
  }
  const usedBy = users.map((d) => ({ name: d.name, n: casesOf(d) })).filter((u) => u.n);
  if (usedBy.length) {
    parts.push(`${usedBy.map((u) => `${u.name}’s ${u.n} ${u.n === 1 ? 'case' : 'cases'}`).join(' and ')} also run through this table.`);
  }
  return parts.join(' ');
}

/** Results of the tables a decision runs through, by output name, to the name of the table giving each. */
export function chainOutputsOf(decisions, id) {
  const byId = new Map(decisions.map((d) => [d.id, d]));
  const out = {};
  const own = new Set((byId.get(id)?.outputs || []).map((o) => o.name));
  const seen = new Set([id]);
  const visit = (did) => {
    for (const r of byId.get(did)?.requires || []) {
      if (seen.has(r) || !byId.has(r)) continue;
      seen.add(r);
      visit(r);
      for (const o of byId.get(r).outputs || []) if (!own.has(o.name) && !(o.name in out)) out[o.name] = byId.get(r).name;
    }
  };
  visit(id);
  return out;
}

/** Tables at the end of a chain: they use another table, and none uses them. */
export function chainEnds(decisions) {
  const used = new Set(decisions.flatMap((d) => d.requires));
  return decisions.filter((d) => d.tabular && d.requires.length && !used.has(d.id));
}

/** The tables a decision runs through, starting tables first, ending with it. */
export function chainOf(decisions, id) {
  const byId = new Map(decisions.map((d) => [d.id, d]));
  const order = [];
  const seen = new Set();
  const visit = (did) => {
    if (seen.has(did) || !byId.has(did)) return;
    seen.add(did);
    byId.get(did).requires.forEach(visit);
    order.push(byId.get(did));
  };
  visit(id);
  return order;
}

/*
 * Scopes: what a test or a try of a table runs is decided by what it supplies. Supplying everything a
 * required table gives replaces that table, so the chain is cut there. These mirror the server's
 * ChainScope, so what the screen promises is what the runner does.
 */
const resultNamesOf = (d) => (d.tabular ? d.outputs.map((o) => o.name) : [d.variable || d.name]).filter(Boolean);

export function scopeOf(decisions, endId, supplied) {
  const byId = new Map(decisions.map((d) => [d.id, d]));
  const given = new Set(supplied);
  const runs = [];
  const cut = new Set();
  const seen = new Set();
  const visit = (id) => {
    if (seen.has(id) || !byId.has(id)) return;
    seen.add(id);
    for (const r of byId.get(id).requires) {
      const req = byId.get(r);
      if (!req) continue;
      const outs = resultNamesOf(req);
      if (outs.length && outs.every((o) => given.has(o))) cut.add(r);
      else visit(r);
    }
    runs.push(byId.get(id));
  };
  visit(endId);
  const produced = new Set(runs.flatMap(resultNamesOf));
  const needed = [...new Set(runs.flatMap((d) => d.inputs.map((i) => i.expression.trim()).filter(Boolean)))].filter((n) => !produced.has(n));
  const outputs = {};
  for (const d of runs) for (const n of resultNamesOf(d)) if (!(n in outputs)) outputs[n] = d;
  return { end: byId.get(endId), runs, cut, needed, outputs };
}

/**
 * A scope said in words. A straight line reads as one ("Customer tier → Discount"); anything that
 * branches says what the table uses ("Table 6 using Table 3, Table 4 and Table 5").
 */
export function scopeLabel(scope) {
  if (!scope.end) return '';
  const runs = scope.runs;
  if (runs.length <= 1) return `${scope.end.name} alone`;
  const ids = new Set(runs.map((d) => d.id));
  const straight = runs.every((d, i) => {
    const used = d.requires.filter((r) => ids.has(r));
    return i === 0 ? used.length === 0 : used.length === 1 && used[0] === runs[i - 1].id;
  });
  if (straight) return runs.map((d) => d.name).join(' → ');
  const others = runs.slice(0, -1).map((d) => d.name);
  return `${scope.end.name} using ${others.slice(0, -1).join(', ')}${others.length > 1 ? ' and ' : ''}${others[others.length - 1]}`;
}

/** The tables a table depends on, the first to run first. */
export function upstreamOf(decisions, id) {
  return chainOf(decisions, id).filter((d) => d.id !== id);
}

/**
 * The scope where the given tables do not run: their results are supplied instead, and anything only
 * they needed is not needed either.
 */
export function scopeGiving(decisions, endId, givenIds) {
  const given = decisions.filter((d) => givenIds.includes(d.id));
  return scopeOf(decisions, endId, given.flatMap(resultNamesOf));
}

/**
 * The ways to run a table: alone, then from each table further back, down to the whole chain. Each
 * is the set of tables between the start and the end; ways that run the same tables are one.
 */
export function scopeOptions(decisions, endId) {
  const byId = new Map(decisions.map((d) => [d.id, d]));
  const ancestors = new Set();
  const up = (id) => byId.get(id)?.requires.forEach((r) => { if (!ancestors.has(r) && byId.has(r)) { ancestors.add(r); up(r); } });
  up(endId);
  const reaches = (from, to, seen = new Set()) => from === to || (!seen.has(from) && (seen.add(from), decisions.some((d) => d.requires.includes(from) && reaches(d.id, to, seen))));
  const starts = [endId, ...chainOf(decisions, endId).map((d) => d.id).filter((id) => id !== endId).reverse()];
  const options = [];
  const seenRuns = new Set();
  for (const start of starts) {
    const run = [endId, ...[...ancestors].filter((a) => reaches(start, a) && reaches(a, endId))];
    if (start !== endId) run.push(start);
    const runSet = new Set(run);
    const runDecisions = decisions.filter((d) => runSet.has(d.id));
    const produced = new Set(runDecisions.flatMap(resultNamesOf));
    const supplied = [...new Set(runDecisions.flatMap((d) => d.inputs.map((i) => i.expression.trim()).filter(Boolean)))].filter((n) => !produced.has(n));
    const scope = scopeOf(decisions, endId, supplied);
    const key = scope.runs.map((d) => d.id).join('>');
    if (seenRuns.has(key)) continue;
    seenRuns.add(key);
    options.push({ key, start, label: scopeLabel(scope), supplied, scope, whole: false });
  }
  if (options.length) options[options.length - 1].whole = options.length > 1;
  return options;
}

/** Tables that connect to each other, as groups, each in the order data flows. */
export function groupsOf(decisions) {
  const byId = new Map(decisions.map((d) => [d.id, d]));
  const neighbours = new Map(decisions.map((d) => [d.id, new Set()]));
  for (const d of decisions) for (const r of d.requires) if (byId.has(r)) { neighbours.get(d.id).add(r); neighbours.get(r).add(d.id); }
  const depth = relationsOf(decisions).depth;
  const seen = new Set();
  const groups = [];
  for (const d of decisions) {
    if (seen.has(d.id)) continue;
    const group = [];
    const stack = [d.id];
    seen.add(d.id);
    while (stack.length) {
      const id = stack.pop();
      group.push(byId.get(id));
      for (const n of neighbours.get(id)) if (!seen.has(n)) { seen.add(n); stack.push(n); }
    }
    group.sort((a, b) => (depth.get(a.id) || 0) - (depth.get(b.id) || 0) || decisions.indexOf(a) - decisions.indexOf(b));
    groups.push(group);
  }
  return groups;
}

/**
 * What runs when exactly these tables are switched on: each that runs, in the order data flows; the
 * values to give (what they check and none of them gives); the tables switched off whose results are
 * therefore given; and the ends — running tables no other running table uses.
 */
export function runningScope(decisions, runIds) {
  const on = new Set(runIds);
  const depth = relationsOf(decisions).depth;
  const runs = decisions.filter((d) => on.has(d.id)).sort((a, b) => (depth.get(a.id) || 0) - (depth.get(b.id) || 0));
  const produced = new Set(runs.flatMap(resultNamesOf));
  const needed = [...new Set(runs.flatMap((d) => d.inputs.map((i) => i.expression.trim()).filter(Boolean)))].filter((n) => !produced.has(n));
  const given = decisions.filter((d) => !on.has(d.id) && runs.some((r) => r.requires.includes(d.id)));
  const ends = runs.filter((d) => !runs.some((r) => r.requires.includes(d.id)));
  return { runs, needed, given, ends };
}

/** Everything a table runs through, itself included. */
export function closureOf(decisions, id) {
  return new Set(chainOf(decisions, id).map((d) => d.id));
}

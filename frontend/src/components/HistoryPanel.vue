<template>
  <aside class="history grid content-start gap-3 rounded border border-rule bg-ground p-4" aria-labelledby="history-title">
    <header class="flex items-start gap-2">
      <div class="min-w-0 flex-1">
        <h2 id="history-title" class="text-lg">History</h2>
        <p class="meta mt-0.5 mb-0 truncate" :title="subject">{{ subject }}</p>
      </div>
      <button type="button" class="-mt-1 -mr-2 grid size-8 flex-none place-items-center rounded text-ink-2 hover:bg-hover hover:text-ink" aria-label="Close the history" title="Close" @click="$emit('close')"><X :size="18" :stroke-width="1.75" /></button>
    </header>

    <!-- How fine: one cell up to the whole domain. Read only when asked: a level pressed here, or the
         cell picked in the table asked for below — clicking about the table to edit it reads nothing. -->
    <div class="flex flex-wrap gap-1.5" role="group" aria-label="What to show the history of">
      <button
        v-for="l in LEVELS"
        :key="l.id"
        type="button"
        class="btn-option"
        :aria-pressed="target.level === l.id"
        :disabled="!available(l.id)"
        :title="available(l.id) ? l.title : 'Pick a cell in the table first'"
        @click="ask(l.id)"
      >{{ l.label }}</button>
    </div>
    <p v-if="!selection?.ruleId && (target.level === 'TABLE' || target.level === 'DOMAIN')" class="meta m-0">Pick a condition or result cell in the table, then Cell, Rule or Column shows its history.</p>
    <button v-else-if="offer" type="button" class="btn btn-small justify-self-start" @click="ask(offer.level)">Show the history of {{ offer.text }}</button>
    <p v-if="unsaved" class="m-0 rounded border border-edit bg-edit-tint px-2.5 py-1.5 text-sm">{{ unsaved }}</p>

    <p v-if="loading && !result" class="loading m-0">Reading the history…</p>
    <p v-else-if="error" class="notice notice-needs m-0 text-sm">{{ error }}</p>
    <template v-else-if="result">
      <p v-if="!result.entries.length" class="meta m-0">No commit changed {{ noun }}{{ result.searched ? ` in the ${result.searched} ${result.searched === 1 ? 'commit' : 'commits'} that changed ${domain}` : '' }}.</p>
      <ol class="m-0 grid list-none gap-0 p-0" :class="{ 'opacity-60': loading }" :aria-busy="loading">
        <li v-for="(e, i) in result.entries" :key="e.sha || 'uncommitted'" class="relative grid gap-1.5 border-l-2 pb-4 pl-4" :class="[e.uncommitted ? 'border-edit' : 'border-rule', i === result.entries.length - 1 ? 'pb-1' : '']">
          <span class="absolute top-[5px] -left-[6px] size-2.5 rounded-full border-2 border-ground" :class="e.uncommitted ? 'bg-edit' : e.merge ? 'bg-ink-3' : 'bg-ink'" aria-hidden="true"></span>
          <p v-if="e.uncommitted" class="m-0 flex flex-wrap items-baseline gap-x-2">
            <span class="state state-edit">Not committed</span>
            <span class="meta">saved in this work space</span>
          </p>
          <template v-else>
            <p class="m-0 text-sm font-semibold leading-snug break-words">{{ plainMessage(e.message) }}</p>
            <p class="meta m-0 flex flex-wrap items-baseline gap-x-1.5 text-xs">
              <span>{{ e.author }}</span><span aria-hidden="true">·</span>
              <time :datetime="e.when" :title="new Date(e.when).toLocaleString()">{{ whenText(e.when) }}</time><span aria-hidden="true">·</span>
              <router-link :to="`/commit/${e.sha}`" class="mono">{{ e.shortSha }}</router-link>
              <span v-if="e.merge" class="tag ml-1" title="A merge that made this itself: neither side had it">merge</span>
            </p>
          </template>

          <!-- One cell: before and after. -->
          <p v-if="result.level === 'CELL'" class="m-0 text-sm">
            <template v-if="e.change === 'ADDED'">{{ e.ruleBefore == null ? 'Rule added with' : 'Column added with' }} <code :class="val(false)">{{ show(e.after) }}</code></template>
            <template v-else-if="e.change === 'REMOVED'">{{ e.ruleAfter == null ? 'Rule removed; it was' : 'Column removed; it was' }} <code :class="val(true)">{{ show(e.before) }}</code></template>
            <template v-else><code :class="val(true)">{{ show(e.before) }}</code> <span class="text-ink-3">→</span> <code :class="val(false)">{{ show(e.after) }}</code></template>
            <span v-if="thenRule(e)" class="meta"> · then rule {{ thenRule(e) }}</span>
          </p>

          <!-- A column: its heading, and each rule's cell in it. -->
          <div v-else-if="result.level === 'COLUMN'" class="grid gap-1 text-sm">
            <p v-if="e.headingBefore || e.headingAfter" class="m-0">
              <template v-if="!e.headingBefore">Column added: <strong>{{ e.headingAfter }}</strong></template>
              <template v-else-if="!e.headingAfter">Column removed: <s class="text-ink-2">{{ e.headingBefore }}</s></template>
              <template v-else>Heading <s class="text-ink-2">{{ e.headingBefore }}</s> <span class="text-ink-3">→</span> {{ e.headingAfter }}</template>
            </p>
            <ul v-if="e.cells.length" class="m-0 grid list-none gap-0.5 p-0">
              <li v-for="(c, j) in e.cells.slice(0, open[e.sha] ? undefined : 6)" :key="j" class="flex flex-wrap items-baseline gap-x-1.5">
                <span class="meta w-14 flex-none">Rule {{ c.ruleAfter ?? c.ruleBefore }}</span>
                <template v-if="c.change === 'ADDED'"><span class="meta">added</span> <code :class="val(false)">{{ show(c.after) }}</code></template>
                <template v-else-if="c.change === 'REMOVED'"><span class="meta">removed</span> <code :class="val(true)">{{ show(c.before) }}</code></template>
                <template v-else-if="c.before == null"><code :class="val(false)">{{ show(c.after) }}</code></template>
                <template v-else-if="c.after == null"><code :class="val(true)">{{ show(c.before) }}</code></template>
                <template v-else><code :class="val(true)">{{ show(c.before) }}</code> <span class="text-ink-3">→</span> <code :class="val(false)">{{ show(c.after) }}</code></template>
              </li>
            </ul>
            <button v-if="e.cells.length > 6 && !open[e.sha]" type="button" class="btn btn-quiet btn-small -ml-2 justify-self-start" @click="open[e.sha] = true">{{ e.cells.length - 6 }} more</button>
          </div>

          <!-- A rule, a table, the domain: the rule diff, as on a commit. -->
          <template v-else>
            <p v-if="result.level === 'RULE' && e.change === 'MOVED'" class="m-0 text-sm">Moved from rule {{ e.ruleBefore }} to rule {{ e.ruleAfter }}</p>
            <ul v-if="result.level !== 'RULE' && columnChanges(e).length" class="m-0 grid list-none gap-0.5 p-0 text-sm">
              <li v-for="(c, j) in columnChanges(e)" :key="j">{{ c }}</li>
            </ul>
            <!-- Each rule's change as a short list: what a narrow panel can show without scrolling sideways. -->
            <div v-for="t in (result.level === 'RULE' && e.change === 'MOVED' ? [] : ruleChanges(e))" :key="t.table" class="grid gap-1.5 text-sm">
              <p v-if="result.level === 'DOMAIN'" class="m-0 font-semibold">{{ t.table }}<span v-if="t.state" class="meta font-normal"> · {{ t.state }}</span></p>
              <p v-if="t.hitPolicy" class="m-0">When several rules match: <s class="text-ink-2">{{ t.hitPolicy[0] }}</s> <span class="text-ink-3">→</span> {{ t.hitPolicy[1] }}</p>
              <div v-for="(r, j) in t.rules.slice(0, open[`${e.sha}|${t.table}`] ? undefined : 5)" :key="j" class="grid gap-0.5">
                <p v-if="result.level !== 'RULE' || r.kind !== 'Changed'" class="m-0 text-xs font-semibold text-ink-2">{{ r.title }}</p>
                <p v-for="(c, k) in r.cells" :key="k" class="m-0 flex flex-wrap items-baseline gap-x-1.5" :class="{ 'line-through decoration-ink-3': r.kind === 'Removed' }">
                  <span class="meta">{{ c.name }}</span>
                  <template v-if="c.before != null && c.after != null"><code :class="val(true)">{{ show(c.before) }}</code> <span class="text-ink-3">→</span> <code :class="val(false)">{{ show(c.after) }}</code></template>
                  <code v-else :class="val(r.kind === 'Removed' || c.after == null)">{{ show(c.after ?? c.before) }}</code>
                </p>
              </div>
              <button v-if="t.rules.length > 5 && !open[`${e.sha}|${t.table}`]" type="button" class="btn btn-quiet btn-small -ml-2 justify-self-start" @click="open[`${e.sha}|${t.table}`] = true">{{ t.rules.length - 5 }} more {{ t.rules.length - 5 === 1 ? 'rule' : 'rules' }}</button>
            </div>
            <p v-if="!ruleChanges(e).length && !columnChanges(e).length && !e.testsChanged && !(result.level === 'RULE' && e.change === 'MOVED')" class="meta m-0">{{ e.change === 'MOVED' ? 'Rules moved' : 'Changed' }}</p>
            <p v-if="e.testsChanged" class="m-0 text-sm">Test cases changed<template v-if="e.casesBefore !== e.casesAfter">: {{ e.casesBefore }} <span class="text-ink-3">→</span> {{ e.casesAfter }}</template></p>
          </template>
        </li>
      </ol>
      <p v-if="result.more" class="meta m-0 text-xs">Only the newest changes are shown; older ones are in git.</p>
    </template>
  </aside>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { X } from 'lucide-vue-next';
import { api } from '../lib/api';
import { hitPolicyLabel } from '../lib/dmnModel';
import { plainMessage, whenText } from '../store';

const props = defineProps({
  domain: { type: String, required: true },
  // Exactly one: a work space (what it saved but did not commit comes first) or a commit.
  ws: { type: String, default: null },
  rev: { type: String, default: null },
  // The table on screen, as the grid has it: names, rule numbers and headings come from here.
  decision: { type: Object, default: null },
  // The cell picked in the grid: { ruleId, columnId } (columnId null for a rule's note). Picking one
  // reads nothing; it is what a level pressed here, or "Show the history of", is about.
  selection: { type: Object, default: null },
  // Said when what is shown has edits not saved yet: they are not history.
  unsaved: { type: String, default: null },
  // Bumped by the screen after a save or a commit, so what is shown is read again.
  reloadKey: { type: Number, default: 0 }
});
// target: what is shown, { level, ruleId, columnId } — the screen outlines it in the grid.
const emit = defineEmits(['target', 'close']);

const LEVELS = [
  { id: 'CELL', label: 'Cell', title: 'Every change of this one cell' },
  { id: 'RULE', label: 'Rule', title: 'Every change of this rule: its cells, its note, its place' },
  { id: 'COLUMN', label: 'Column', title: 'Every change of this column: its heading and its cell in each rule' },
  { id: 'TABLE', label: 'Table', title: 'Every change of this table' },
  { id: 'DOMAIN', label: 'Domain', title: 'Every change of the domain: all its tables and its tests' }
];

// What a level is about: the cell picked in the table, or, with none picked, what is shown now.
const basis = (level) => {
  const from = props.selection?.ruleId ? props.selection : target.value;
  return {
    level,
    ruleId: level === 'CELL' || level === 'RULE' ? from.ruleId || null : null,
    columnId: level === 'CELL' || level === 'COLUMN' ? from.columnId || null : null
  };
};
const sameAs = (a, b) => a.level === b.level && a.ruleId === b.ruleId && a.columnId === b.columnId;
const available = (level) => {
  const b = basis(level);
  return { CELL: !!(b.ruleId && b.columnId), RULE: !!b.ruleId, COLUMN: !!b.columnId, TABLE: !!props.decision, DOMAIN: true }[level];
};

// Opened, it shows what was picked, if anything: opening it was the asking.
const first = props.selection?.ruleId ? (props.selection.columnId ? 'CELL' : 'RULE') : 'TABLE';
const target = ref({ level: first, ruleId: null, columnId: null });
target.value = { ...basis(first) };
function ask(level) {
  if (available(level)) target.value = basis(level);
}
watch(target, (t) => emit('target', t), { immediate: true });
// Another table: its own history, from the table up.
watch(() => props.decision?.id, (now, before) => {
  if (before !== undefined && now !== before && target.value.level !== 'DOMAIN') target.value = { level: 'TABLE', ruleId: null, columnId: null };
});

// The cell picked in the table, when it is not what is shown: offered, not read.
const offer = computed(() => {
  const sel = props.selection;
  if (!sel?.ruleId) return null;
  const level = ['RULE', 'COLUMN'].includes(target.value.level) ? target.value.level : sel.columnId ? 'CELL' : 'RULE';
  const want = basis(level);
  if (sameAs(want, target.value) || !available(level)) return null;
  return { level, text: name(want) };
});

const ruleNumberOf = (id) => {
  const i = (props.decision?.rules || []).findIndex((r) => r.id === id);
  return i >= 0 ? i + 1 : null;
};
const columnOf = (id) => {
  const d = props.decision;
  if (!d || !id) return null;
  const input = d.inputs.find((c) => c.id === id);
  if (input) return { name: input.label || input.expression || 'condition', side: 'condition' };
  const output = d.outputs.find((c) => c.id === id);
  return output ? { name: output.label || output.name || 'result', side: 'result' } : null;
};
const ruleTextOf = (id) => (ruleNumberOf(id) ? `rule ${ruleNumberOf(id)}` : 'a rule not saved yet');
// How a thing is named: "rule 4, Fee Code", "rule 4", "result Fee Code".
function name(t) {
  const column = columnOf(t.columnId);
  switch (t.level) {
    case 'CELL': return column ? `${ruleTextOf(t.ruleId)}, ${column.name}` : ruleTextOf(t.ruleId);
    case 'RULE': return ruleTextOf(t.ruleId);
    case 'COLUMN': return column ? `${column.side} ${column.name}` : 'a column';
    case 'TABLE': return props.decision?.name || 'this table';
    default: return props.domain;
  }
}
const ruleNumber = computed(() => ruleNumberOf(target.value.ruleId));
const subject = computed(() => {
  const table = props.decision?.name || '';
  const t = target.value;
  if (t.level === 'TABLE') return table;
  if (t.level === 'DOMAIN') return `${props.domain}: every table and the tests`;
  return `${table}, ${name(t)}`;
});
const noun = computed(() => ({ CELL: 'this cell', RULE: 'this rule', COLUMN: 'this column', TABLE: 'this table', DOMAIN: props.domain }[target.value.level]));

// --- reading ------------------------------------------------------------------------------------
const result = ref(null);
const loading = ref(false);
const error = ref(null);
const open = reactive({});
let seq = 0;

function query() {
  const t = target.value;
  const q = { domain: props.domain, ws: props.ws, rev: props.rev };
  if (t.level !== 'DOMAIN') q.decision = props.decision?.id;
  if (t.level === 'CELL' || t.level === 'RULE') q.rule = t.ruleId;
  if (t.level === 'CELL' || t.level === 'COLUMN') q.column = t.columnId;
  return q;
}

async function load() {
  const mine = ++seq;
  loading.value = true;
  error.value = null;
  try {
    const r = await api.elementHistory(query());
    if (mine === seq) result.value = r;
  } catch (e) {
    if (mine === seq) {
      error.value = `The history could not be read: ${e.message}`;
      result.value = null;
    }
  } finally {
    if (mine === seq) loading.value = false;
  }
}

// Read when what is asked for changes, and after a save — never because a cell was clicked.
watch(
  () => [props.domain, props.ws, props.rev, props.decision?.id, target.value.level, target.value.ruleId, target.value.columnId, props.reloadKey].join('|'),
  load,
  { immediate: true }
);

// --- showing ------------------------------------------------------------------------------------
const show = (v) => (v === null || v === undefined ? '' : v === '' || v === '-' ? '– (any)' : v);
// Per table: its rule changes, each as the cells that changed (an added or removed rule: all its cells
// that are not "any"), so the changed value is in view however wide the table is.
function ruleChanges(e) {
  return (e.diff?.decisions || []).map((d) => {
    const heading = new Map([...(d.inputs || []), ...(d.outputs || [])].map((c) => [c.id, c.rightLabel || c.rightExpression || c.leftLabel || c.leftExpression || 'unnamed']));
    const rules = (d.rules || []).map((r) => {
      const cells = [...r.inputs, ...r.outputs];
      const n = r.change === 'REMOVED' ? r.leftIndex + 1 : r.rightIndex + 1;
      const whole = r.change === 'ADDED' || r.change === 'REMOVED';
      const listed = whole
        ? cells.filter((c) => !isAny(r.change === 'ADDED' ? c.right : c.left)).map((c) => ({ name: heading.get(c.columnId), before: null, after: r.change === 'ADDED' ? c.right : c.left }))
        : cells.filter((c) => c.change !== 'UNCHANGED').map((c) => ({ name: heading.get(c.columnId), before: c.left, after: c.right }));
      if ((r.leftDescription || '') !== (r.rightDescription || '') && !whole) listed.push({ name: 'Note', before: r.leftDescription || '', after: r.rightDescription || '' });
      const kind = { ADDED: 'Added', REMOVED: 'Removed', MOVED: 'Moved', CHANGED: 'Changed' }[r.change];
      const title = r.change === 'MOVED' ? `Rule ${r.leftIndex + 1} moved to ${r.rightIndex + 1}` : `Rule ${n} ${kind.toLowerCase()}`;
      return { kind, title, cells: listed };
    });
    const hp = d.leftHitPolicy && d.rightHitPolicy && d.leftHitPolicy !== d.rightHitPolicy ? [hitPolicyLabel(d.leftHitPolicy), hitPolicyLabel(d.rightHitPolicy)] : null;
    const state = d.change === 'ADDED' ? 'new table' : d.change === 'REMOVED' ? 'removed' : '';
    return { table: d.rightName || d.leftName, state, hitPolicy: hp, rules };
  }).filter((t) => t.rules.length || t.hitPolicy || t.state);
}
const isAny = (v) => v == null || v === '' || v === '-';
// Columns added, removed or changed in a table (a column of "any" changes no rule, so the rule diff alone would not show it).
function columnChanges(e) {
  const out = [];
  for (const d of e.diff?.decisions || []) {
    if (d.change === 'ADDED' || d.change === 'REMOVED') continue;
    const where = result.value?.level === 'DOMAIN' ? ` in ${d.rightName || d.leftName}` : '';
    const name = (c, side) => (side === 'left' ? c.leftLabel || c.leftExpression : c.rightLabel || c.rightExpression) || 'unnamed';
    const each = (cols, kind) => cols.forEach((c) => {
      if (c.change === 'ADDED') out.push(`${kind} added${where}: ${name(c, 'right')}`);
      else if (c.change === 'REMOVED') out.push(`${kind} removed${where}: ${name(c, 'left')}`);
      else if (c.change === 'CHANGED') out.push(`${kind} ${name(c, 'left')} changed${where}${name(c, 'left') !== name(c, 'right') ? `: now ${name(c, 'right')}` : ''}${c.leftTypeRef !== c.rightTypeRef ? ` (${c.leftTypeRef || 'any'} → ${c.rightTypeRef || 'any'})` : ''}`);
    });
    each(d.inputs || [], 'Condition');
    each(d.outputs || [], 'Result');
  }
  return out;
}
const val = (old) => ['rounded-sm bg-platform px-1 py-px font-mono text-[0.8125rem] break-all', old ? 'text-ink-2 line-through' : 'text-ink'];
// The rule's number at that commit, when it is not the number it has now.
const thenRule = (e) => {
  const n = e.ruleAfter ?? e.ruleBefore;
  return n && ruleNumber.value && n !== ruleNumber.value ? n : null;
};
</script>

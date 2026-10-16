<template>
  <section class="tests grid min-w-0 grid-cols-[minmax(0,1fr)] gap-3" :aria-label="`Test cases for ${decisionName}`">
    <header class="flex items-baseline gap-4">
      <h3>Test cases</h3>
      <p class="meta">{{ summary }}</p>
    </header>

    <p v-if="chainNote" class="meta -mt-1.5 max-w-[80ch]">{{ chainNote }}</p>

    <ul v-if="decisionProblems.length" class="m-0 grid list-none gap-1 p-0">
      <li v-for="(p, i) in decisionProblems" :key="i" :class="p.severity === 'ERROR' ? 'state state-needs' : 'state state-quiet'">{{ sentence(p.message) }}</li>
    </ul>

    <div v-if="!block" class="grid max-w-[70ch] justify-items-start gap-3 border-t border-rule py-4">
      <p>No test cases for {{ decisionName }} yet. Tests prove the table does what you mean, and landing needs every rule covered.</p>
      <button v-if="!readonly" type="button" class="btn" @click="$emit('create-block')"><Plus :size="16" aria-hidden="true" /> Add test cases</button>
    </div>

    <div v-else ref="wrap" class="grid-wrap max-h-[max(320px,calc(100vh-320px))] min-w-0 max-w-full overflow-auto">
      <table class="min-w-[min(100%,720px)] border-separate border-spacing-0 rounded border border-rule bg-ground">
        <thead>
          <tr>
            <th class="res w-[34px] min-w-[34px] border-b border-rule bg-platform p-0 text-center align-middle" scope="col"><span class="sr-only">Result</span></th>
            <th
              v-for="(col, c) in block.columns"
              :key="col + c"
              scope="col"
              class="group/th border-b border-l border-rule p-0 text-left align-top font-normal"
              :class="[kindOf(col), columnStates[col], { flagged: columnProblem(col) }, headClass(col, c)]"
            >
              <div class="relative grid min-w-[120px] gap-px py-2 pr-[30px] pl-3">
                <span class="max-w-[200px] truncate text-xs font-bold" :class="columnStates[col] === 'new' ? 'text-edit-ink' : 'text-ink'">{{ headingOf(col) }}</span>
                <span class="text-xs text-ink-2">{{ subOf(col) }}</span>
                <button
                  v-if="col !== SCENARIO && !readonly"
                  type="button"
                  class="x absolute top-2 right-1 grid size-[22px] place-items-center rounded text-ink-3 opacity-0 group-hover/th:opacity-100 hover:bg-hover hover:text-ink focus-visible:opacity-100 [@media(hover:none)]:opacity-100"
                  :aria-label="`Remove column ${headingOf(col)}`"
                  title="Remove column"
                  @click="$emit('remove-column', c)"
                ><X :size="14" :stroke-width="1.75" /></button>
              </div>
              <p v-if="columnProblem(col)" class="col-problem max-w-[260px] px-3 pb-2 text-xs" :class="columnProblem(col).severity === 'ERROR' ? 'err text-needs-ink' : 'warn text-ink-2'">{{ sentence(columnProblem(col).message) }}</p>
            </th>
            <th v-if="!readonly" class="add w-[130px] border-b border-l border-rule bg-platform p-0 text-left align-middle font-normal" scope="col">
              <form v-if="otherOpen" class="other" @submit.prevent="addOther">
                <input ref="otherField" v-model="otherName" class="block min-h-9 w-full min-w-[130px] border-0 bg-transparent px-3 py-2 font-mono text-[0.8125rem] leading-5 focus:bg-select focus:outline-none focus:ring-[1.5px] focus:ring-inset focus:ring-ink" placeholder="value name" aria-label="Name of the value this case supplies" @keydown.esc="otherOpen = false" @blur="addOther" />
              </form>
              <div v-else class="relative px-1.5">
                <button type="button" class="add-col inline-flex items-center gap-0.5 rounded px-2 py-1 text-sm text-ink-2 hover:bg-hover hover:text-ink" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen"><Plus :size="14" :stroke-width="1.75" aria-hidden="true" />column</button>
                <div v-if="menuOpen" class="menu absolute top-[calc(100%+4px)] right-0 z-[5] grid min-w-[220px] rounded border border-rule-strong bg-ground p-1.5 shadow-[0_10px_28px_-10px_oklch(20%_0.02_260/0.22)]" role="menu" @keydown.esc="menuOpen = false">
                  <p v-if="missingInputs.length" class="px-2 pt-1.5 pb-0.5 text-xs font-bold text-ink-3">Values this table needs</p>
                  <button v-for="v in missingInputs" :key="v" type="button" role="menuitem" class="menu-item rounded px-2 py-1.5 text-left font-mono text-[0.8125rem] hover:bg-hover focus-visible:bg-hover" @click="pick(v)">{{ v }}</button>
                  <p v-if="missingOutputs.length" class="px-2 pt-1.5 pb-0.5 text-xs font-bold text-ink-3">Results to expect</p>
                  <button v-for="o in missingOutputs" :key="o" type="button" role="menuitem" class="menu-item rounded px-2 py-1.5 text-left hover:bg-hover focus-visible:bg-hover" @click="pick(expectationColumn(o))">expects <span class="mono">{{ o }}</span><span v-if="chainFrom[o]" class="text-xs text-ink-3"> from {{ chainFrom[o] }}</span></button>
                  <button type="button" role="menuitem" class="menu-item other-item mt-1 border-t border-rule px-2 py-1.5 text-left text-ink-2 hover:bg-hover focus-visible:bg-hover" @click="pick('__other')">Another value…</button>
                </div>
              </div>
            </th>
          </tr>
        </thead>
        <tbody>
          <template v-for="(row, r) in block.rows" :key="r">
            <tr class="group" :class="[resultOf(row)?.passed === false ? 'failed' : '', rowStates[r]]">
              <td class="res w-[34px] min-w-[34px] border-b border-rule p-0 text-center align-middle" :class="resultOf(row)?.passed === false ? 'bg-needs-tint' : rowStates[r] === 'new' ? 'bg-edit-tint' : 'bg-ground'">
                <span v-if="resultOf(row)" class="inline-grid size-5 place-items-center rounded-full text-white" :class="resultOf(row).passed ? 'ok bg-clean' : 'bad bg-needs'" :title="resultOf(row).passed ? 'Passed' : 'Failed'">
                  <Check v-if="resultOf(row).passed" :size="14" :stroke-width="2.5" />
                  <X v-else :size="14" :stroke-width="2.5" />
                  <span class="sr-only">{{ resultOf(row).passed ? 'passed' : 'failed' }}</span>
                </span>
              </td>
              <td v-for="(col, c) in block.columns" :key="c" class="cell border-b border-l border-rule p-0 text-left align-top" :class="[kindOf(col), cellStates[`${r}|${c}`], cellClass(r, c, col)]">
                <input
                  :value="row[c]"
                  :readonly="readonly"
                  class="text block min-h-9 w-full border-0 bg-transparent px-3 py-2 leading-5 placeholder:text-ink-3 focus:bg-select focus:outline-none focus:ring-[1.5px] focus:ring-inset focus:ring-ink"
                  :class="[{ prose: col === SCENARIO }, col === SCENARIO ? 'min-w-[220px] font-sans text-md' : 'font-mono text-[0.8125rem]', cellStates[`${r}|${c}`] === 'edited' ? 'font-semibold text-edit-ink' : '']"
                  spellcheck="false"
                  :placeholder="col === SCENARIO ? 'what this case checks' : ''"
                  :aria-label="`Case ${r + 1}, ${headingOf(col)}`"
                  :data-cell="`${r}:${c}`"
                  @input="$emit('cell', r, c, $event.target.value)"
                  @keydown="onKey($event, r, c)"
                />
              </td>
              <td v-if="!readonly" class="tools w-10 border-b border-l border-rule px-1 align-middle">
                <button type="button" class="tool grid size-7 place-items-center rounded text-ink-3 opacity-0 group-focus-within:opacity-100 group-hover:opacity-100 hover:bg-needs-tint hover:text-needs-ink [@media(hover:none)]:opacity-100" :aria-label="`Remove case ${r + 1}`" title="Remove case" @click="$emit('remove-row', r)"><Trash2 :size="16" :stroke-width="1.75" /></button>
              </td>
            </tr>
            <tr v-if="resultOf(row)?.passed === false" class="why">
              <td class="border-b border-rule bg-needs-tint"></td>
              <td :colspan="block.columns.length + (readonly ? 0 : 1)" class="border-b border-rule bg-needs-tint px-3 pt-1.5 pb-2.5 text-sm text-needs-ink"><span>{{ plainFailure(resultOf(row).error) }}</span></td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
    <!-- Outside the scrolling grid, so they stay put however far the columns are scrolled. -->
    <div v-if="block">
      <button v-if="!readonly" type="button" class="btn btn-quiet add-row -ml-2" @click="addRow"><Plus :size="16" aria-hidden="true" /> Add a case</button>
      <p class="meta legend mt-1.5">A blank cell supplies nothing. <span class="mono">50</span> is a number, <span class="mono">"50"</span> is text, <span class="mono">[A, B]</span> is a list.</p>
    </div>
  </section>
</template>

<script setup>
import { plainFailure } from '../lib/failures';
import { computed, nextTick, ref } from 'vue';
import { Check, Plus, Trash2, X } from 'lucide-vue-next';
import { SCENARIO, expectationColumn, expectationName, isExpectation } from '../lib/testSpec';

const props = defineProps({
  decisionName: { type: String, required: true },
  block: { type: Object, default: null },
  hint: { type: Object, default: null }, // { suppliedVariables, outputs }
  problems: { type: Array, default: () => [] },
  results: { type: Array, default: null }, // [{ name, passed, error }] from the last run
  // Changed here and not saved, from testEdits(): cells by `${row}|${col}`, new rows, new columns.
  cellStates: { type: Object, default: () => ({}) },
  rowStates: { type: Object, default: () => ({}) },
  columnStates: { type: Object, default: () => ({}) },
  // What these cases exercise beyond this table: the chain they run, the tables whose cases run it.
  chainNote: { type: String, default: '' },
  // Results of tables on the way that these cases may also expect: { [output]: tableName }
  chainFrom: { type: Object, default: () => ({}) },
  // How the runner names this set's cases, when a table has several sets ("Discount #2").
  namePrefix: { type: String, default: null },
  // Look, run and compare only: nothing can be typed, added or removed (a deploy-only instance).
  readonly: { type: Boolean, default: false }
});
const emit = defineEmits(['cell', 'add-row', 'remove-row', 'add-column', 'remove-column', 'create-block']);
const wrap = ref(null);

const kindOf = (col) => (col === SCENARIO ? 'scenario' : isExpectation(col) ? 'expect' : 'supply');
const headingOf = (col) => (col === SCENARIO ? 'Case' : isExpectation(col) ? expectationName(col) : col);
const subOf = (col) => {
  if (col === SCENARIO) return '';
  if (!isExpectation(col)) return 'given';
  const from = props.chainFrom[expectationName(col)];
  return from ? `expected from ${from}` : 'expected';
};
const sentence = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : '');
const decisionProblems = computed(() => props.problems.filter((p) => !p.column));
const columnProblem = (col) => {
  const bare = isExpectation(col) ? expectationName(col) : col;
  return props.problems.find((p) => p.column && (p.column === col || p.column === bare));
};

const missingInputs = computed(() => (props.hint?.suppliedVariables || []).filter((v) => !props.block?.columns.includes(v)));
const missingOutputs = computed(() => [...(props.hint?.outputs || []), ...Object.keys(props.chainFrom)]
  .filter((o, i, all) => all.indexOf(o) === i)
  .filter((o) => !props.block?.columns.includes(expectationColumn(o))));

const summary = computed(() => {
  if (!props.block) return '';
  const n = props.block.rows.length;
  const cases = `${n} ${n === 1 ? 'case' : 'cases'}`;
  const own = props.block.rows.map(resultOf).filter(Boolean);
  if (!props.results || !own.length) return `${cases}, not run since the last change`;
  const failed = own.filter((r) => !r.passed).length;
  return failed ? `${cases}, ${failed} failing` : `${cases}, all passing`;
});

// A run names a case "<table> / <scenario>" (just "<table>" without one). Rows sharing a name are
// matched to the results of that name in order, so every row gets its own result.
function nameOf(row) {
  const scenario = String(row[props.block.columns.indexOf(SCENARIO)] ?? '').trim();
  const prefix = props.namePrefix || props.block.decision;
  return scenario ? `${prefix} / ${scenario}` : prefix;
}
function resultOf(row) {
  if (!props.results || !props.block) return null;
  const name = nameOf(row);
  const nth = props.block.rows.slice(0, props.block.rows.indexOf(row)).filter((r) => nameOf(r) === name).length;
  return props.results.filter((r) => r.name === name)[nth] || null;
}

const menuOpen = ref(false);
function pick(value) {
  menuOpen.value = false;
  if (value === '__other') {
    otherOpen.value = true;
    otherName.value = '';
    nextTick(() => otherField.value?.focus());
    return;
  }
  if (value) emit('add-column', value);
}

// A value outside the suggestions is still legal: the test columns are variables, not the table's inputs.
const otherOpen = ref(false);
const otherName = ref('');
const otherField = ref(null);
function addOther() {
  if (!otherOpen.value) return;
  const name = otherName.value.trim();
  otherOpen.value = false;
  if (name && !props.block?.columns.includes(name)) emit('add-column', name);
}

async function addRow() {
  emit('add-row');
  await nextTick();
  wrap.value?.querySelector(`[data-cell="${props.block.rows.length - 1}:0"]`)?.focus();
}

async function onKey(event, row, col) {
  if (event.key === 'Enter' || event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    const target = event.key === 'ArrowUp' ? row - 1 : row + 1;
    if (event.key === 'Enter' && target >= props.block.rows.length) emit('add-row');
    await nextTick();
    wrap.value?.querySelector(`[data-cell="${target}:${col}"]`)?.focus();
  }
}

// --- how a state looks ---------------------------------------------------------------------------
// The first expectation column is set off from what the case supplies by a stronger line.
const opensExpectations = (c) => c > 0 && isExpectation(props.block.columns[c]) && !isExpectation(props.block.columns[c - 1]);
const headClass = (col, c) => [
  opensExpectations(c) ? 'border-l-rule-strong' : '',
  props.columnStates[col] === 'new' ? 'bg-edit-tint' : 'bg-platform',
  columnProblem(col) ? 'shadow-[inset_0_-2px_0_var(--color-needs)]' : props.columnStates[col] === 'new' ? 'shadow-[inset_0_-2px_0_var(--color-edit)]' : ''
];
const cellClass = (r, c, col) => [
  opensExpectations(c) ? 'border-l-rule-strong' : '',
  props.cellStates[`${r}|${c}`] === 'edited' || props.rowStates[r] === 'new' ? 'bg-edit-tint' : 'bg-ground'
];
</script>

<style scoped>
/* Sticky layering, kept here because it spans the header row and two left columns: cases scroll
   inside the grid, the headings stay on top, the result mark and the case name stay on the left. */
thead th { position: sticky; top: 0; z-index: 3; }
.res { position: sticky; left: 0; z-index: 2; }
thead .res { z-index: 4; }
td.scenario, th.scenario { position: sticky; left: 34px; z-index: 2; box-shadow: 1px 0 0 var(--color-rule); }
thead th.scenario { z-index: 4; }
tbody tr:last-child > * { border-bottom: 0; }
</style>

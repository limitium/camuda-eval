<template>
  <section class="sets grid gap-3" aria-label="Test sets">
    <header class="grid gap-1">
      <h3>Test sets</h3>
      <p class="meta">{{ intro }}</p>
    </header>

    <div class="rows">
      <div v-for="s in sets" :key="s.key" class="set" :class="{ open: openKey === s.key }">
        <button
          type="button"
          class="set-row grid min-h-12 w-full grid-cols-[16px_minmax(0,1fr)_auto_5rem_7rem] items-center gap-3.5 px-1.5 py-3 text-left hover:bg-hover max-[760px]:grid-cols-[16px_1fr_auto]"
          :aria-expanded="openKey === s.key"
          @click="openKey = openKey === s.key ? null : s.key"
        >
          <ChevronRight :size="16" :stroke-width="1.75" class="text-ink-3 transition-[rotate] duration-160 ease-out" :class="openKey === s.key ? 'rotate-90' : ''" aria-hidden="true" />
          <span class="truncate font-semibold">{{ s.label }}</span>
          <span class="meta max-[760px]:hidden">{{ s.block.rows.length }} {{ s.block.rows.length === 1 ? 'case' : 'cases' }}</span>
          <span v-if="s.edited" class="state state-edit max-[760px]:hidden">changed</span>
          <span v-else class="max-[760px]:hidden"></span>
          <span class="justify-self-end">
            <span v-if="!s.results" class="state state-quiet">not run</span>
            <span v-else-if="s.failed" class="state state-needs">{{ s.failed }} failing</span>
            <span v-else class="state state-clean">all pass</span>
          </span>
        </button>

        <div v-if="openKey === s.key" class="grid min-w-0 grid-cols-[minmax(0,1fr)] gap-3 pt-1 pb-5 pl-[30px]">
          <p class="meta max-w-[90ch]">{{ whatText(s) }}</p>
          <TestGrid
            :decision-name="s.label"
            :block="s.block"
            :hint="{ suppliedVariables: s.scope.needed, outputs: s.scope.end.outputs.map((o) => o.name) }"
            :problems="problemsFor(s)"
            :results="results"
            :name-prefix="s.namePrefix"
            :chain-from="s.chainFrom"
            :cell-states="s.changes.cells"
            :row-states="s.changes.rows"
            :column-states="s.changes.columns"
            :readonly="readonly"
            v-on="handlersFor(s)"
          />
          <div class="flex items-center gap-2">
            <template v-if="confirmRemove === s.key">
              <span class="meta">Remove this set and its {{ s.block.rows.length }} cases?</span>
              <button type="button" class="btn btn-small btn-danger" @click="remove(s)">Remove</button>
              <button type="button" class="btn btn-small btn-quiet" @click="confirmRemove = null">Keep</button>
            </template>
            <button v-else-if="!readonly" type="button" class="btn btn-small btn-quiet" @click="confirmRemove = s.key">Remove this set</button>
          </div>
        </div>
      </div>
      <p v-if="!sets.length" class="meta px-1.5 py-3.5">No test sets yet.</p>
    </div>

    <form v-if="adding" class="grid max-w-[760px] gap-3.5 border-y border-rule py-4" @submit.prevent="create">
      <RunPicker v-model="newRun" :decisions="decisions" :focus="newDecision" />
      <div v-if="newEnds.length > 1" class="grid gap-1.5">
        <span class="label m-0">The set checks what this table gives</span>
        <div class="flex flex-wrap gap-1.5" role="radiogroup" aria-label="The set checks">
          <button
            v-for="d in newEnds"
            :key="d.id"
            type="button"
            role="radio"
            class="choice btn-option"
            :class="{ picked: newEnd === d.id }"
            :aria-checked="newEnd === d.id"
            @click="newEnd = d.id"
          >{{ d.name }}</button>
        </div>
      </div>
      <p class="meta">{{ newHint }}</p>
      <div class="flex gap-2">
        <button type="submit" class="btn">Add the set</button>
        <button type="button" class="btn btn-quiet" @click="adding = false">Cancel</button>
      </div>
    </form>
    <button v-else-if="!readonly" type="button" class="btn btn-quiet -ml-2 justify-self-start" @click="startAdding"><Plus :size="16" aria-hidden="true" /> New test set</button>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import { ChevronRight, Plus } from 'lucide-vue-next';
import TestGrid from './TestGrid.vue';
import RunPicker from './RunPicker.vue';
import { closureOf, runningScope, scopeLabel, scopeOf } from '../lib/dmnModel';
import { SCENARIO, expectationColumn, isExpectation, testEdits } from '../lib/testSpec';

/*
 * A table's tests as sets: each set tests one table over one stretch of its chain — alone, from a
 * table further back, or from the very start — decided by what its cases supply. The sets are compact
 * rows; one opens at a time, so a file with several chains stays readable. Changes to the cases go
 * back to the parent as functions, so it owns the one copy of the tests.
 */
const props = defineProps({
  decisions: { type: Array, required: true },
  blocks: { type: Array, required: true },
  savedBlocks: { type: Array, default: () => [] },
  results: { type: Array, default: null },
  problems: { type: Array, default: () => [] },
  // Only the sets of these tables; null for every table in the file.
  ends: { type: Array, default: null },
  // Look and run only: no case or set can be changed, added or removed.
  readonly: { type: Boolean, default: false },
});
const emit = defineEmits(['edit-tests']);

const openKey = ref(null);
const confirmRemove = ref(null);
const adding = ref(false);
const newEnd = ref(null);

const tabular = computed(() => props.decisions.filter((d) => d.tabular));
const fixedEnd = computed(() => (props.ends && props.ends.length === 1 ? props.ends[0] : null));
const decisionFor = (b) => props.decisions.find((d) => d.id === b.decision) || props.decisions.find((d) => d.name === b.decision);

const sets = computed(() => {
  const counts = {};
  props.blocks.forEach((b) => { counts[b.decision] = (counts[b.decision] || 0) + 1; });
  const seen = {};
  const savedSeen = {};
  const savedByKey = new Map();
  props.savedBlocks.forEach((b) => {
    savedSeen[b.decision] = (savedSeen[b.decision] || 0) + 1;
    savedByKey.set(`${b.decision}#${savedSeen[b.decision]}`, b);
  });
  return props.blocks.map((block, i) => {
    seen[block.decision] = (seen[block.decision] || 0) + 1;
    const n = seen[block.decision];
    const d = decisionFor(block);
    if (!d || (props.ends && !props.ends.includes(d.id))) return null;
    const supplied = block.columns.filter((c) => c !== SCENARIO && !isExpectation(c));
    const scope = scopeOf(props.decisions, d.id, supplied);
    const namePrefix = counts[block.decision] > 1 ? `${block.decision} #${n}` : block.decision;
    const own = (props.results || []).filter((r) => r.name === namePrefix || r.name.startsWith(`${namePrefix} / `));
    const changes = testEdits(savedByKey.get(`${block.decision}#${n}`) || null, block);
    const chainFrom = {};
    for (const [name, producer] of Object.entries(scope.outputs)) if (producer.id !== d.id) chainFrom[name] = producer.name;
    return {
      key: `${i}`,
      block,
      scope,
      label: scopeLabel(scope),
      namePrefix,
      chainFrom,
      changes,
      edited: changes.count > 0,
      results: own.length ? own : null,
      failed: own.filter((r) => !r.passed).length
    };
  }).filter(Boolean);
});

const intro = computed(() => {
  if (fixedEnd.value) {
    const d = props.decisions.find((x) => x.id === fixedEnd.value);
    return d?.requires.length
      ? `Test ${d.name} alone, or together with the tables it uses. What a set supplies decides where the run starts.`
      : `Each set gives ${d?.name} values and checks what it gives.`;
  }
  return 'Every set in this domain. Each tests one table, alone or with part or all of the chain before it.';
});

// Inside a set, only what concerns its own columns: whole-table notes like "no test rows" belong to
// the set rows, which already say how many cases each has.
const problemsFor = (s) => props.problems.filter((p) => (p.decision === s.block.decision || p.decision === s.scope.end.name)
  && p.column && s.block.columns.some((c) => c === p.column || c.endsWith(` ${p.column}`)));

const whatText = (s) => {
  const cut = [...s.scope.cut].map((id) => props.decisions.find((d) => d.id === id)?.name).filter(Boolean);
  const parts = [`Runs ${s.scope.runs.map((d) => d.name).join(' → ')}.`];
  if (cut.length) parts.push(`The cases supply what ${cut.join(' and ')} would give, so ${cut.length === 1 ? 'it does' : 'they do'} not run.`);
  if (s.scope.runs.length > 1) parts.push('Expected columns can check any step, so a wrong early step is caught even when the final answer is right.');
  return parts.join(' ');
};

// --- a new set ------------------------------------------------------------------------------------

// A new set: which tables run (RunPicker), and which of the ends it checks.
const newRun = ref([]);
const newEnds = computed(() => runningScope(props.decisions, newRun.value).ends);
watch(newEnds, (ends) => {
  if (!ends.some((d) => d.id === newEnd.value)) newEnd.value = ends[ends.length - 1]?.id || null;
});
const newDecision = computed(() => props.decisions.find((d) => d.id === newEnd.value) || null);
// The set runs what is on and feeds the table it checks: anything else that is on belongs to another set.
const newScope = computed(() => {
  const d = newDecision.value;
  if (!d) return null;
  const closure = closureOf(props.decisions, d.id);
  const running = runningScope(props.decisions, newRun.value.filter((id) => closure.has(id)));
  return scopeOf(props.decisions, d.id, running.needed);
});
const newHint = computed(() => {
  const sc = newScope.value;
  if (!sc) return '';
  return sc.needed.length ? `The set will be called “${scopeLabel(sc)}”. Its cases supply: ${sc.needed.join(', ')}.` : `The set will be called “${scopeLabel(sc)}”. Nothing needs to be supplied.`;
});

function startAdding() {
  adding.value = true;
  const focus = fixedEnd.value || props.decisions.filter((d) => d.tabular).slice(-1)[0]?.id || null;
  newRun.value = focus ? [focus] : [];
  newEnd.value = focus;
}

function create() {
  const d = newDecision.value;
  const sc = newScope.value;
  if (!d || !sc) return;
  // Expect what every table that runs gives, the tested table's results last.
  const expected = [...sc.runs.filter((x) => x.id !== d.id), d].flatMap((x) => x.outputs.map((out) => expectationColumn(out.name)));
  const columns = [SCENARIO, ...sc.needed, ...expected];
  emit('edit-tests', () => props.blocks.push({ decision: d.id, columns, rows: [columns.map(() => '')] }));
  adding.value = false;
  setTimeout(() => { openKey.value = sets.value[sets.value.length - 1]?.key || null; });
}

function remove(s) {
  confirmRemove.value = null;
  openKey.value = null;
  emit('edit-tests', () => props.blocks.splice(props.blocks.indexOf(s.block), 1));
}

function handlersFor(s) {
  const edit = (change) => emit('edit-tests', change);
  const b = s.block;
  return {
    cell: (r, c, v) => edit(() => { b.rows[r][c] = v; }),
    'add-row': () => edit(() => b.rows.push(b.columns.map(() => ''))),
    'remove-row': (r) => edit(() => b.rows.splice(r, 1)),
    'add-column': (col) => edit(() => {
      if (b.columns.includes(col)) return;
      const firstExpectation = b.columns.findIndex((c) => isExpectation(c));
      const at = isExpectation(col) || firstExpectation < 0 ? b.columns.length : firstExpectation;
      b.columns.splice(at, 0, col);
      b.rows.forEach((row) => row.splice(at, 0, ''));
    }),
    'remove-column': (c) => edit(() => {
      b.columns.splice(c, 1);
      b.rows.forEach((row) => row.splice(c, 1));
    })
  };
}
</script>

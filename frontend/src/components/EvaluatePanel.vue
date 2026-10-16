<template>
  <section class="evaluate grid gap-3.5 border-t border-rule pt-5" :aria-label="`Try ${decision.name}`">
    <header class="flex flex-wrap items-baseline gap-x-4 gap-y-1.5">
      <h3>Try it</h3>
      <p class="meta">Evaluates what is on screen{{ edited ? ', including changes not saved' : '' }}. Nothing is saved.</p>
    </header>

    <RunPicker v-model="runIds" :decisions="decisions" :focus="defaultScope === 'whole' ? null : decision" />

    <form class="flex flex-wrap items-end gap-x-4 gap-y-3" @submit.prevent="run">
      <div v-for="v in variables" :key="v.name" class="grid min-w-[150px]">
        <label class="label mb-1" :for="`ev-${uid}-${v.name}`">{{ v.label || v.name }} <span class="ml-1 text-xs font-normal text-ink-3">{{ v.typeRef || 'any' }}</span></label>
        <input :id="`ev-${uid}-${v.name}`" v-model="values[v.name]" class="field mono h-[34px]" spellcheck="false" autocomplete="off" :placeholder="v.name" />
      </div>
      <p v-if="!variables.length" class="meta">Nothing needs to be supplied.</p>
      <div class="flex items-center gap-2">
        <button type="submit" class="btn" :disabled="busy" :aria-busy="busy ? 'true' : undefined">{{ busy ? 'Evaluating…' : chain.length > 1 ? `Evaluate ${chain.length} tables` : `Evaluate ${chain[0]?.name || ''}` }}</button>
        <select v-if="cases.length" class="field h-[34px] w-auto text-ink-2" aria-label="Fill from a test case" :value="''" @change="fill($event)">
          <option value="" disabled>Fill from a test case…</option>
          <option v-for="(c, i) in cases" :key="i" :value="i">{{ c.decisionName }}: {{ c.scenario || `case ${i + 1}` }}</option>
        </select>
      </div>
    </form>

    <div v-if="steps.length" class="grid gap-4" aria-live="polite">
      <!-- One line per group of connected tables: unrelated tables are never drawn as a chain. Each
           table that ran is a station; an arrow between two says the later one uses the earlier. -->
      <ol v-for="(g, gi) in stepGroups" :key="gi" class="m-0 grid max-w-[80ch] list-none border-t border-rule p-0">
        <li
          v-for="(s, i) in g"
          :key="s.id"
          class="step relative grid grid-cols-[14px_minmax(140px,auto)_1fr] items-center gap-x-4 gap-y-1 border-b border-rule px-2 py-2.5"
          :class="[{ current: focused && s.id === decision.id, failed: s.failed, skipped: s.skipped }, s.failed && !s.skipped ? 'bg-needs-tint' : '']"
        >
          <span
            class="size-3 rounded-full border-2 bg-ground"
            :class="s.skipped ? 'border-dashed border-ink-3' : s.failed ? 'border-needs' : focused && s.id === decision.id ? 'border-ink bg-ink' : 'border-ink'"
            aria-hidden="true"
          ></span>
          <span class="font-semibold" :class="s.skipped ? 'text-ink-2' : ''">{{ s.name }}<span v-if="focused && s.id === decision.id" class="meta font-normal"> · this table</span></span>
          <span v-if="s.skipped" class="meta">Did not run: {{ s.skipped }} gave no result.</span>
          <span v-else-if="s.overlap" class="state state-needs">{{ s.overlap }}</span>
          <span v-else-if="s.noMatch" class="state state-needs">No rule matches.</span>
          <span v-else-if="s.error" class="state state-needs">{{ s.error }}</span>
          <span v-else class="flex flex-wrap items-baseline gap-x-3.5 gap-y-1">
            <span v-for="(value, name) in s.result" :key="name"><span class="meta">{{ name }}</span> <strong class="mono text-md">{{ show(value) }}</strong></span>
            <span class="meta">· rule {{ s.rules.join(', ') }}</span>
          </span>
          <span v-if="s.uses.length" class="meta col-start-3 -mt-0.5">uses {{ s.uses.join(' and ') }}</span>
          <span v-if="i < g.length - 1 && g[i + 1].usesIds.includes(s.id)" class="absolute -bottom-[9px] left-[6px] z-[1] bg-ground text-ink-3" aria-hidden="true"><ArrowDown :size="16" :stroke-width="1.75" /></span>
        </li>
      </ol>
    </div>
  </section>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { ArrowDown } from 'lucide-vue-next';
import { api } from '../lib/api';
import RunPicker from './RunPicker.vue';
import { groupsOf, runningScope } from '../lib/dmnModel';
import { fromCellText, isExpectation, SCENARIO } from '../lib/testSpec';

/*
 * Evaluates whichever tables are switched on (RunPicker) — one table, a pair, a long chain, a tree —
 * giving the results of any switched-off table they use as values. Each table that runs shows its
 * result and the rule that gave it; one that failed says why, plainly, and tables after it say they did
 * not run because of it. The parent marks the answering rules from the `answer` event.
 */
const props = defineProps({
  decisions: { type: Array, required: true },
  decision: { type: Object, required: true },
  xml: { type: Function, required: true }, // () => current content
  blocks: { type: Array, default: () => [] }, // all test blocks of the file, to fill from
  edited: { type: Boolean, default: false },
  // Which tables can be evaluated here (default: this one and those that use it), which first.
  ends: { type: Array, default: null },
  startAt: { type: String, default: null },
  // 'alone' in a table's own tab; 'whole' where the chain is the subject.
  defaultScope: { type: String, default: 'alone' }
});
const emit = defineEmits(['answer']);

const uid = Math.random().toString(36).slice(2, 7);
const values = reactive({});
const steps = ref([]);
const busy = ref(false);

const byId = computed(() => new Map(props.decisions.map((d) => [d.id, d])));

// Which tables run (RunPicker): this one alone in its own tab; its whole group where the chain is the subject.
const runIds = ref([]);
// In a table's tab the panel is about that table; in the overview it is about no table in particular.
const focused = computed(() => props.defaultScope !== 'whole');
watch(() => props.decision.id, () => {
  const group = groupsOf(props.decisions.filter((d) => d.tabular)).find((g) => g.some((d) => d.id === props.decision.id)) || [props.decision];
  runIds.value = props.defaultScope === 'whole' ? group.map((d) => d.id) : [props.decision.id];
  steps.value = [];
  emit('answer', null);
}, { immediate: true });
watch(runIds, () => { steps.value = []; });

const scope = computed(() => runningScope(props.decisions, runIds.value));
const chain = computed(() => scope.value.runs.filter((d) => d.tabular));

const variables = computed(() => {
  const names = scope.value.needed;
  const all = props.decisions.flatMap((d) => d.inputs);
  return names.map((n) => {
    const input = all.find((i) => i.expression.trim() === n);
    return { name: n, label: input?.label || n, typeRef: input?.typeRef || '' };
  });
});

const cases = computed(() => props.blocks.flatMap((b) => {
  const d = byId.value.get(b.decision) || props.decisions.find((x) => x.name === b.decision);
  return b.rows.map((row) => {
    const c = { decisionName: d?.name || b.decision, scenario: row[b.columns.indexOf(SCENARIO)] || '', values: {} };
    b.columns.forEach((col, i) => {
      if (col !== SCENARIO && !isExpectation(col)) c.values[col] = row[i];
    });
    return c;
  });
}));

function fill(event) {
  const c = cases.value[Number(event.target.value)];
  event.target.value = '';
  if (!c) return;
  for (const v of variables.value) values[v.name] = c.values[v.name] ?? '';
  run();
}

const ruleNumber = (decisionId, ruleId) => {
  const i = byId.value.get(decisionId)?.rules.findIndex((r) => r.id === ruleId) ?? -1;
  return i >= 0 ? i + 1 : ruleId;
};

// Which running tables a step uses, and through which value, said in words ("Customer tier's tier").
function usesOf(d) {
  const running = new Set(chain.value.map((x) => x.id));
  const usesIds = d.requires.filter((r) => running.has(r));
  const uses = usesIds.map((r) => {
    const from = byId.value.get(r);
    const via = from.outputs.map((o) => o.name).filter((n) => d.inputs.some((i) => i.expression.trim() === n));
    return via.length ? `${from.name}’s ${via.join(', ')}` : from.name;
  });
  return { uses, usesIds };
}

// Steps in groups of connected tables, each in the order data flows.
const stepGroups = computed(() => groupsOf(props.decisions.filter((d) => d.tabular))
  .map((g) => g.map((d) => steps.value.find((st) => st.id === d.id)).filter(Boolean))
  .filter((g) => g.length));

// The engine's words, said plainly.
function plainError(message) {
  const m = String(message || '');
  const missing = m.match(/no variable found for name '([^']+)'/);
  if (missing) return `No value was given for ${missing[1]}.`;
  return m.replace(/^FEEL\/SCALA-\d+ Error while evaluating expression: /, '').replace(/^failed to evaluate expression '[^']*': /, '');
}

async function run() {
  busy.value = true;
  // Every value this scope needs is sent, blank ones as "no value", so a rule saying "any" still matches.
  const inputs = {};
  for (const v of variables.value) inputs[v.name] = fromCellText(values[v.name] ?? '');
  const xml = props.xml();
  try {
    const answers = await Promise.all(chain.value.map((d) => api.evaluate(xml, d.id, inputs).catch((e) => ({ error: e.message, results: [], matched: [] }))));
    const matched = [];
    const overlap = [];
    const failedIds = new Set();
    const runIds = new Set(chain.value.map((d) => d.id));
    steps.value = chain.value.map((d, i) => {
      const a = answers[i];
      // A table whose input table failed fails for that reason; say that once, where it happened.
      const blockedBy = d.requires.filter((r) => runIds.has(r) && failedIds.has(r)).map((r) => byId.value.get(r)?.name);
      const failed = !!(a.noMatch || a.overlap?.length || a.error);
      if (failed || blockedBy.length) failedIds.add(d.id);
      if (blockedBy.length) return { ...usesOf(d), id: d.id, name: d.name, skipped: blockedBy.join(' and '), result: {}, rules: [] };
      const own = (a.matched || []).filter((m) => m.decision === d.id);
      matched.push(...own);
      if (a.overlap?.length) overlap.push(...a.overlap);
      const overlapNumbers = (a.overlap || []).map((id) => ruleNumber(d.id, id));
      return {
        ...usesOf(d),
        id: d.id,
        name: d.name,
        result: a.results?.[0] || {},
        rules: own.map((m) => ruleNumber(d.id, m.rule)),
        noMatch: a.noMatch,
        overlap: a.overlap?.length ? `Rules ${overlapNumbers.join(' and ')} both match, but this table allows only one.` : null,
        error: a.error ? plainError(a.error) : null,
        failed
      };
    });
    emit('answer', { matched, overlap });
  } finally {
    busy.value = false;
  }
}

const show = (v) => (v === null || v === undefined ? '—' : typeof v === 'string' ? v : JSON.stringify(v));
</script>

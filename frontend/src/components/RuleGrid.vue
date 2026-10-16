<template>
  <div ref="wrap" class="grid-wrap max-h-[max(360px,calc(100vh-300px))] min-w-0 max-w-full overflow-auto rounded pb-1">
    <table class="min-w-full border-separate border-spacing-0 rounded border border-rule bg-ground text-md" :class="{ readonly }">
      <colgroup>
        <col class="w-11" />
        <col v-for="c in decision.inputs" :key="c.id" class="w-[150px]" />
        <col v-for="c in decision.outputs" :key="c.id" class="w-[150px]" />
        <col class="w-[200px]" />
        <col v-if="!readonly" class="w-32" />
      </colgroup>
      <thead>
        <tr class="groups">
          <th class="h-[30px] border-b border-rule bg-platform px-3"></th>
          <th :colspan="Math.max(decision.inputs.length, 1)" class="g-when h-[30px] border-b border-rule bg-platform px-3 text-left text-xs font-bold whitespace-nowrap text-ink-2">
            When
            <button v-if="!readonly" type="button" class="add-col ml-2.5 inline-flex items-center gap-0.5 rounded px-1.5 py-px text-xs font-medium text-ink-2 hover:bg-hover hover:text-ink" @click="addColumn('in')"><Plus :size="13" aria-hidden="true" />condition</button>
          </th>
          <th :colspan="decision.outputs.length" class="g-then h-[30px] border-b border-l border-rule border-l-rule-strong bg-platform px-3 text-left text-xs font-bold whitespace-nowrap text-ink-2">
            Then
            <button v-if="!readonly" type="button" class="add-col ml-2.5 inline-flex items-center gap-0.5 rounded px-1.5 py-px text-xs font-medium text-ink-2 hover:bg-hover hover:text-ink" @click="addColumn('out')"><Plus :size="13" aria-hidden="true" />result</button>
          </th>
          <th class="g-note h-[30px] border-b border-rule bg-platform px-3 text-left text-xs font-bold whitespace-nowrap text-ink-2">Note</th>
          <th v-if="!readonly" class="tools-head border-b border-rule bg-platform"></th>
        </tr>
        <tr class="heads">
          <th class="num border-r border-b border-rule bg-platform px-2.5 text-right text-xs font-medium text-ink-3" scope="col"><span class="sr-only">Rule</span>#</th>
          <th v-if="!decision.inputs.length" class="head in empty-head border-b border-rule bg-platform px-3 py-2 text-left font-normal text-ink-3">No conditions — every rule matches</th>
          <th
            v-for="(c, i) in decision.inputs"
            :key="c.id"
            scope="col"
            :data-col="`in${i}`"
            class="head in border-b border-rule bg-platform p-0 text-left align-middle"
            :class="[columnStates[c.id], headClass(c.id, i > 0 ? 'border-l border-l-rule' : '', highlightColumn(c.id, `in${i}`), editing?.side === 'in' && editing.index === i), { flagged: highlightColumn(c.id, `in${i}`), open: editing?.side === 'in' && editing.index === i }]"
          >
            <button type="button" class="head-btn grid w-full gap-px px-3 py-2 text-left disabled:cursor-default" :class="headBtnClass(c.id, editing?.side === 'in' && editing.index === i)" :disabled="readonly" @click="toggle('in', i)">
              <span class="max-w-[220px] truncate text-xs font-bold" :class="isNewOrEdited(c.id) ? 'text-edit-ink' : 'text-ink'" :title="c.label || c.expression">{{ c.label || c.expression || 'unnamed' }}</span>
              <span class="flex gap-2 text-xs font-normal text-ink-2">
                <span v-if="c.label && c.expression" class="font-mono">{{ c.expression }}</span>
                <span class="text-ink-3">{{ c.typeRef || 'any' }}</span>
              </span>
              <span v-if="fedBy[c.expression?.trim()]" class="inline-flex items-center gap-1 text-xs font-normal text-ink-2"><CornerDownRight :size="12" class="flex-none text-ink-3" aria-hidden="true" />from {{ fedBy[c.expression.trim()] }}</span>
            </button>
            <ColumnEditor
              v-if="editing?.side === 'in' && editing.index === i"
              side="in"
              :column="c"
              :can-remove="true"
              :sources="sources"
              @save="(v) => { $emit('column', 'in', i, v); editing = null; }"
              @remove="() => { $emit('remove-column', 'in', i); editing = null; }"
              @close="editing = null"
            />
          </th>
          <th
            v-for="(c, i) in decision.outputs"
            :key="c.id"
            scope="col"
            :data-col="`out${i}`"
            class="head out border-b border-l border-rule bg-platform p-0 text-left align-middle"
            :class="[columnStates[c.id], headClass(c.id, i === 0 ? 'border-l-rule-strong' : 'border-l-rule', false, editing?.side === 'out' && editing.index === i), { open: editing?.side === 'out' && editing.index === i, first: i === 0 }]"
          >
            <button type="button" class="head-btn grid w-full gap-px px-3 py-2 text-left disabled:cursor-default" :class="headBtnClass(c.id, editing?.side === 'out' && editing.index === i)" :disabled="readonly" @click="toggle('out', i)">
              <span class="max-w-[220px] truncate text-xs font-bold" :class="isNewOrEdited(c.id) ? 'text-edit-ink' : 'text-ink'" :title="c.label || c.name">{{ c.label || c.name || 'unnamed' }}</span>
              <span class="flex gap-2 text-xs font-normal text-ink-2">
                <span v-if="c.label && c.name" class="font-mono">{{ c.name }}</span>
                <span class="text-ink-3">{{ c.typeRef || 'any' }}</span>
              </span>
              <span v-if="feeds[c.name]" class="inline-flex items-center gap-1 text-xs font-normal text-ink-2"><CornerDownRight :size="12" class="flex-none text-ink-3" aria-hidden="true" />to {{ feeds[c.name] }}</span>
            </button>
            <ColumnEditor
              v-if="editing?.side === 'out' && editing.index === i"
              side="out"
              :column="c"
              :can-remove="decision.outputs.length > 1"
              @save="(v) => { $emit('column', 'out', i, v); editing = null; }"
              @remove="() => { $emit('remove-column', 'out', i); editing = null; }"
              @close="editing = null"
            />
          </th>
          <th class="head note border-b border-l border-rule bg-platform p-0" scope="col"><span class="sr-only">Note</span></th>
          <th v-if="!readonly" class="tools-head border-b border-rule bg-platform"></th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(rule, r) in decision.rules"
          :key="rule.id || r"
          class="group"
          :class="[rowState(rule), { focused: focusRule === rule.id }]"
          :data-rule="rule.id"
        >
          <th class="num border-r border-b border-rule px-2.5 text-right text-xs font-medium" :class="numClass(rule)" scope="row">{{ r + 1 }}</th>
          <td v-if="!decision.inputs.length" class="cell in empty-cell border-b border-rule p-0" :class="cellClass(rule, '')"></td>
          <td
            v-for="(value, c) in rule.inputs"
            :key="`i${c}`"
            class="cell in border-b border-rule p-0 text-left align-middle"
            :class="[cellState(rule, decision.inputs[c]?.id, `in${c}`), cellClass(rule, cellState(rule, decision.inputs[c]?.id, `in${c}`)), c > 0 ? 'border-l' : '']"
          >
            <span v-if="readonly" class="text" :class="textClass(rule, cellState(rule, decision.inputs[c]?.id, `in${c}`), true)">{{ shown(value, 'in') }}</span>
            <input
              v-else
              :value="value"
              class="text"
              :class="textClass(rule, cellState(rule, decision.inputs[c]?.id, `in${c}`))"
              spellcheck="false"
              :aria-label="`Rule ${r + 1}, ${decision.inputs[c]?.label || decision.inputs[c]?.expression}`"
              :placeholder="value === '' ? 'any' : ''"
              :data-cell="`${r}:${c}`"
              @input="$emit('cell', r, 'in', c, $event.target.value)"
              @keydown="onKey($event, r, c)"
            />
          </td>
          <td
            v-for="(value, c) in rule.outputs"
            :key="`o${c}`"
            class="cell out border-b border-l border-rule p-0 text-left align-middle"
            :class="[cellState(rule, decision.outputs[c]?.id, `out${c}`), cellClass(rule, cellState(rule, decision.outputs[c]?.id, `out${c}`)), { first: c === 0, 'border-l-rule-strong': c === 0 }]"
          >
            <span v-if="readonly" class="text" :class="textClass(rule, cellState(rule, decision.outputs[c]?.id, `out${c}`), true)">{{ value }}</span>
            <input
              v-else
              :value="value"
              class="text"
              :class="textClass(rule, cellState(rule, decision.outputs[c]?.id, `out${c}`))"
              spellcheck="false"
              :aria-label="`Rule ${r + 1}, result ${decision.outputs[c]?.label || decision.outputs[c]?.name}`"
              :data-cell="`${r}:${decision.inputs.length + c}`"
              @input="$emit('cell', r, 'out', c, $event.target.value)"
              @keydown="onKey($event, r, decision.inputs.length + c)"
            />
          </td>
          <td class="cell note border-b border-l border-rule p-0 text-left align-middle" :class="[cellStates[`${rule.id}|description`], cellClass(rule, cellStates[`${rule.id}|description`] || '')]">
            <span v-if="readonly" class="text" :class="textClass(rule, cellStates[`${rule.id}|description`] || '', true, true)">{{ rule.description }}</span>
            <input
              v-else
              :value="rule.description"
              class="text prose"
              :class="textClass(rule, cellStates[`${rule.id}|description`] || '', false, true)"
              :aria-label="`Rule ${r + 1}, note`"
              :data-cell="`${r}:${decision.inputs.length + decision.outputs.length}`"
              @input="$emit('description', r, $event.target.value)"
              @keydown="onKey($event, r, decision.inputs.length + decision.outputs.length)"
            />
          </td>
          <td
            v-if="!readonly"
            class="tools border-b border-rule bg-transparent px-1.5 whitespace-nowrap group-focus-within:bg-ground group-focus-within:shadow-[-1px_0_0_var(--color-rule)] group-hover:bg-ground group-hover:shadow-[-1px_0_0_var(--color-rule)]"
            :class="ringClass(rule)"
          >
            <span class="tool-set flex gap-0.5 opacity-0 transition-opacity duration-120 ease-out group-focus-within:opacity-100 group-hover:opacity-100 [@media(hover:none)]:opacity-100">
              <button type="button" :class="toolClass" :aria-label="`Insert a rule after rule ${r + 1}`" title="Insert a rule below" @click="$emit('add-rule', r)"><Plus :size="16" :stroke-width="1.75" /></button>
              <button type="button" :class="toolClass" :aria-label="`Move rule ${r + 1} up`" title="Move up" :disabled="r === 0" @click="$emit('move-rule', r, -1)"><ArrowUp :size="16" :stroke-width="1.75" /></button>
              <button type="button" :class="toolClass" :aria-label="`Move rule ${r + 1} down`" title="Move down" :disabled="r === decision.rules.length - 1" @click="$emit('move-rule', r, 1)"><ArrowDown :size="16" :stroke-width="1.75" /></button>
              <button type="button" :class="[toolClass, 'danger hover:!bg-needs-tint hover:!text-needs-ink']" :aria-label="`Remove rule ${r + 1}`" title="Remove rule" @click="$emit('remove-rule', r)"><Trash2 :size="16" :stroke-width="1.75" /></button>
            </span>
          </td>
        </tr>
        <tr v-if="!decision.rules.length" class="none">
          <td :colspan="decision.inputs.length + decision.outputs.length + 3" class="meta px-3 py-4">This table has no rules yet.</td>
        </tr>
      </tbody>
    </table>
    <button v-if="!readonly" type="button" class="btn btn-quiet add-rule mt-2 -ml-2" @click="$emit('add-rule', null)">
      <Plus :size="16" aria-hidden="true" /> Add a rule
    </button>
  </div>
</template>

<script setup>
import { nextTick, ref } from 'vue';
import { ArrowDown, ArrowUp, CornerDownRight, Plus, Trash2 } from 'lucide-vue-next';
import ColumnEditor from './ColumnEditor.vue';

const props = defineProps({
  decision: { type: Object, required: true },
  readonly: { type: Boolean, default: false },
  // { [inputExpression]: decisionName } — which table produces the value an input reads.
  fedBy: { type: Object, default: () => ({}) },
  // { [outputName]: decisionName } — which table reads a result.
  feeds: { type: Object, default: () => ({}) },
  // { [ruleId]: 'added'|'removed'|'changed' } and { [`${ruleId}|${columnId}`]: 'contested'|'changed' }
  rowStates: { type: Object, default: () => ({}) },
  cellStates: { type: Object, default: () => ({}) },
  focusRule: { type: String, default: null },
  // { [columnId]: 'edited'|'new' } — headings changed here and not saved.
  columnStates: { type: Object, default: () => ({}) },
  // Other tables' results a condition here could check: [{ name, typeRef, from, fromName, label }]
  sources: { type: Array, default: () => [] }
});
const emit = defineEmits(['cell', 'description', 'add-rule', 'remove-rule', 'move-rule', 'column', 'add-column', 'remove-column']);

const wrap = ref(null);
const editing = ref(null);

const toggle = async (side, index) => {
  editing.value = editing.value?.side === side && editing.value.index === index ? null : { side, index };
  if (editing.value) {
    // The panel takes the right of the screen; bring the column being edited into what is left.
    await nextTick();
    wrap.value?.querySelector(`[data-col="${side}${index}"]`)?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' });
  }
};

// The new column opens straight into its editor, so it gets a real name before anything else.
async function addColumn(side) {
  const index = side === 'in' ? props.decision.inputs.length : props.decision.outputs.length;
  emit('add-column', side);
  await nextTick();
  toggle(side, index);
}

const rowState = (rule) => (rule.id && props.rowStates[rule.id]) || '';
// Merge conflicts name a column by position in the merged table (in0, out1); diffs by its id.
const cellState = (rule, columnId, position) => (rule.id && (props.cellStates[`${rule.id}|${columnId}`] || props.cellStates[`${rule.id}|${position}`])) || '';
const highlightColumn = (id, position) => Object.keys(props.cellStates).some((k) => (k.endsWith(`|${id}`) || k.endsWith(`|${position}`)) && props.cellStates[k] === 'contested');
const shown = (value) => (value === '' || value === '-' ? '–' : value);

// --- how a state looks ---------------------------------------------------------------------------
// Row marks can stack ("new matched"); cell marks are one word. Edited here = blue, contested = red,
// resolved = green, the rule that answered = an ink ring, rules that both answered = a red ring.
const flags = (rule) => new Set(rowState(rule).split(' ').filter(Boolean));
const isNewOrEdited = (id) => props.columnStates[id] === 'edited' || props.columnStates[id] === 'new';

const headClass = (id, border, flagged, open) => [
  border,
  flagged ? 'shadow-[inset_0_-2px_0_var(--color-needs)]' : isNewOrEdited(id) ? 'shadow-[inset_0_-2px_0_var(--color-edit)]' : ''
];
const headBtnClass = (id, open) => (open
  ? 'bg-select ring-1 ring-inset ring-ink'
  : isNewOrEdited(id) ? 'bg-edit-tint enabled:hover:bg-edit-tint' : 'enabled:hover:bg-hover');

function numClass(rule) {
  const f = flags(rule);
  if (f.has('overlap')) return 'bg-needs-tint text-needs-ink font-bold';
  if (f.has('matched') || props.focusRule === rule.id) return 'bg-select text-ink font-bold ring-1 ring-inset ring-ink';
  if (f.has('new') || f.has('moved')) return 'bg-edit-tint text-edit-ink font-bold';
  if (f.has('changed') || f.has('added')) return 'bg-select text-ink';
  return 'bg-platform text-ink-3';
}

// The rule that answered (or the rules that both answered when only one may) is ringed top and bottom.
function ringClass(rule) {
  const f = flags(rule);
  if (f.has('overlap')) return 'shadow-[inset_0_1px_0_var(--color-needs),inset_0_-1px_0_var(--color-needs)]';
  if (f.has('matched')) return 'shadow-[inset_0_1px_0_var(--color-ink),inset_0_-1px_0_var(--color-ink)]';
  return '';
}

function cellClass(rule, state) {
  const f = flags(rule);
  if (state === 'contested') return 'bg-needs-tint shadow-[inset_0_0_0_1.5px_var(--color-needs)]';
  const ring = ringClass(rule);
  if (state === 'resolved') return ['bg-clean-tint', ring];
  if (state === 'edited') return ['bg-edit-tint', ring];
  if (state === 'changed') return ['bg-select', ring];
  if (f.has('new')) return ['bg-edit-tint', ring];
  if (f.has('added')) return ['bg-select', ring];
  if (f.has('removed')) return ['bg-hover', ring];
  return ['bg-ground group-hover:bg-[oklch(99%_0.003_250)]', ring];
}

function textClass(rule, state, isSpan = false, prose = false) {
  const f = flags(rule);
  return [
    'block min-h-9 w-full border-0 bg-transparent px-3 py-2 leading-5',
    prose ? 'font-sans text-sm text-ink-2' : 'font-mono text-[0.8125rem]',
    isSpan ? 'whitespace-pre-wrap wrap-anywhere' : 'placeholder:text-ink-3 focus:bg-select focus:outline-none focus:ring-[1.5px] focus:ring-inset focus:ring-ink',
    state === 'edited' ? '!text-edit-ink font-semibold' : '',
    f.has('removed') ? 'line-through !text-ink-2' : ''
  ];
}

const toolClass = 'tool grid size-7 place-items-center rounded border-0 bg-transparent text-ink-2 enabled:hover:bg-hover enabled:hover:text-ink disabled:cursor-default disabled:text-rule-strong';

async function focusCell(row, col) {
  await nextTick();
  wrap.value?.querySelector(`[data-cell="${row}:${col}"]`)?.focus();
}

function onKey(event, row, col) {
  if (event.key === 'Enter' || (event.key === 'ArrowDown' && !event.shiftKey)) {
    event.preventDefault();
    focusCell(row + 1, col);
  } else if (event.key === 'ArrowUp' && !event.shiftKey) {
    event.preventDefault();
    focusCell(row - 1, col);
  }
}
</script>

<style scoped>
/* Sticky layering, kept here because it spans two header rows and both table edges: wide tables
   (twenty columns is normal) scroll inside the grid, headings stay on top, the rule number on the
   left and the row tools on the right stay in view, and the corners sit above both. */
.groups th { position: sticky; top: 0; z-index: 3; }
.heads th { position: sticky; top: 30px; z-index: 3; }
.heads th.num, .groups th:first-child { left: 0; z-index: 4; }
tbody .num { position: sticky; left: 0; z-index: 2; }
.tools, .tools-head { position: sticky; right: 0; }
.tools { z-index: 2; }
.tools-head { z-index: 4; }
tbody tr:last-child > * { border-bottom: 0; }
</style>

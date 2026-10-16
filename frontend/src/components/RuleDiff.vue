<template>
  <div class="diff grid gap-6">
    <p v-if="diff.identical" class="meta">No rule changes.</p>
    <section v-for="d in changedDecisions" :key="d.id" class="grid gap-2">
      <h4 class="flex items-center gap-3 text-md font-bold">
        {{ d.rightName || d.leftName }}
        <span v-if="d.change === 'ADDED'" class="state state-edit">new table</span>
        <span v-else-if="d.change === 'REMOVED'" class="state state-needs">removed</span>
      </h4>
      <p v-if="d.leftHitPolicy && d.rightHitPolicy && d.leftHitPolicy !== d.rightHitPolicy" class="text-sm">
        When several rules match: <s class="text-ink-2">{{ hitPolicyLabel(d.leftHitPolicy) }}</s> → <strong>{{ hitPolicyLabel(d.rightHitPolicy) }}</strong>
      </p>
      <p v-if="d.nonTabular" class="meta">A single-expression decision; its content changed.</p>
      <div v-else class="min-w-0 max-w-full overflow-x-auto">
        <table class="[&_tbody_tr:last-child>*]:border-b-0 border-separate border-spacing-0 rounded border border-rule bg-ground text-sm">
          <thead>
            <tr>
              <th :class="[cellBase, whatBase, 'bg-platform']" scope="col"><span class="sr-only">Change</span></th>
              <th v-for="c in d.inputs" :key="`i${c.id}`" scope="col" :class="[cellBase, headBase, 'border-l', colClass(c)]">{{ colName(c, 'in') }}</th>
              <th v-for="(c, i) in d.outputs" :key="`o${c.id}`" scope="col" :class="[cellBase, headBase, 'border-l', i === 0 ? 'first border-l-rule-strong' : '', colClass(c)]">{{ colName(c, 'out') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in visibleRules(d)" :key="i" :class="r.change.toLowerCase()">
              <th :class="[cellBase, whatBase, rowBg(r), r.change === 'ADDED' ? '!text-edit-ink' : '']" scope="row">{{ changeWord(r) }}</th>
              <td v-for="cell in r.inputs" :key="`i${cell.columnId}`" :class="[cellBase, monoBase, 'border-l', rowBg(r), cellClass(cell, r)]">
                <template v-if="cell.change === 'CHANGED'"><s class="font-normal text-ink-2">{{ show(cell.left) }}</s> <span class="font-normal text-ink-3">→</span> {{ show(cell.right) }}</template>
                <template v-else>{{ show(cell.right ?? cell.left) }}</template>
              </td>
              <td v-for="(cell, j) in r.outputs" :key="`o${cell.columnId}`" :class="[cellBase, monoBase, 'border-l', rowBg(r), cellClass(cell, r), j === 0 ? 'first border-l-rule-strong' : '']">
                <template v-if="cell.change === 'CHANGED'"><s class="font-normal text-ink-2">{{ show(cell.left) }}</s> <span class="font-normal text-ink-3">→</span> {{ show(cell.right) }}</template>
                <template v-else>{{ show(cell.right ?? cell.left) }}</template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="unchangedCount(d)" class="meta">{{ unchangedCount(d) }} other {{ unchangedCount(d) === 1 ? 'rule is' : 'rules are' }} unchanged.</p>
    </section>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { hitPolicyLabel } from '../lib/dmnModel';

const props = defineProps({ diff: { type: Object, required: true } });

const changedDecisions = computed(() => (props.diff.decisions || []).filter((d) => d.change !== 'UNCHANGED'));
const visibleRules = (d) => (d.rules || []).filter((r) => r.change !== 'UNCHANGED');
const unchangedCount = (d) => (d.rules || []).filter((r) => r.change === 'UNCHANGED').length;
const changeWord = (r) => ({ ADDED: 'Added', REMOVED: 'Removed', CHANGED: 'Changed', MOVED: `Moved ${r.leftIndex + 1} → ${r.rightIndex + 1}` }[r.change] || '');
const colName = (c, side) => {
  return c.rightLabel || c.rightExpression || c.leftLabel || c.leftExpression || (side === 'in' ? 'condition' : 'result');
};
// Added here = blue, as everywhere a change is shown; removed = struck through, grey.
const colClass = (c) => ({
  added: c.change === 'ADDED', removed: c.change === 'REMOVED', changed: c.change === 'CHANGED',
  'bg-edit-tint text-edit-ink': c.change === 'ADDED',
  'text-ink-2 line-through': c.change === 'REMOVED',
  'shadow-[inset_0_-2px_0_var(--color-edit)]': c.change === 'CHANGED'
});
const cellClass = (cell, r) => ({
  changed: cell.change === 'CHANGED',
  'bg-edit-tint text-edit-ink font-semibold': cell.change === 'CHANGED',
  'line-through text-ink-2': r.change === 'REMOVED'
});
const cellBase = 'border-b border-rule px-3 py-[7px] text-left align-top';
const headBase = 'bg-platform text-xs font-bold whitespace-nowrap';
const whatBase = 'font-sans text-xs font-medium whitespace-nowrap text-ink-2';
const monoBase = 'font-mono text-[0.8125rem]';
const rowBg = (r) => (r.change === 'ADDED' ? 'bg-edit-tint' : r.change === 'REMOVED' ? 'bg-platform' : 'bg-ground');
const show = (v) => (v === null || v === undefined ? '' : v === '' || v === '-' ? '–' : v);
</script>


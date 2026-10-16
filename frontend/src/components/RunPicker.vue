<template>
  <div class="run-picker grid gap-2">
    <div class="flex flex-wrap items-center gap-x-2.5 gap-y-1.5">
      <span class="label m-0 mr-1.5">Run</span>
      <button v-if="focus" type="button" :class="quickClass(onlyFocus)" @click="set([focus.id])">Only {{ focus.name }}</button>
      <button v-if="focusGroup && focusGroup.length > 1" type="button" :class="quickClass(wholeGroup)" @click="set(focusGroup.map((d) => d.id))">Its whole chain</button>
      <template v-if="!focus">
        <button
          v-for="(g, gi) in groups.filter((x) => x.length > 1)"
          :key="`all${gi}`"
          type="button"
          :class="quickClass(isExactly(g))"
          @click="set(g.map((d) => d.id))"
        >All of {{ groupName(g) }}</button>
      </template>
    </div>
    <div class="flex flex-wrap gap-2.5">
      <div v-for="(g, gi) in groups" :key="gi" class="flex flex-wrap gap-1.5 rounded border border-rule p-1.5" :aria-label="`Connected tables ${gi + 1}`" role="group">
        <button
          v-for="d in g"
          :key="d.id"
          type="button"
          class="chip inline-grid rounded border px-3 py-1 text-left transition-shadow duration-120 ease-out hover:ring-[3px] hover:ring-select"
          :class="[stateOf(d), {
            'border-ink bg-ground': stateOf(d) === 'runs',
            'border-dashed border-ink-3 bg-platform': stateOf(d) === 'given',
            'border-rule-strong bg-platform': stateOf(d) === 'off'
          }]"
          :aria-pressed="stateOf(d) === 'runs'"
          :title="titleOf(d)"
          @click="toggle(d)"
        >
          <span class="text-sm font-semibold" :class="stateOf(d) === 'runs' ? 'text-ink' : 'text-ink-2'">{{ d.name }}</span>
          <span class="text-xs" :class="stateOf(d) === 'runs' ? 'text-ink-2' : 'text-ink-3'">{{ { runs: 'runs', given: 'you give its result', off: 'off' }[stateOf(d)] }}</span>
        </button>
      </div>
    </div>
    <p class="meta">{{ summary }}</p>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { groupsOf, runningScope } from '../lib/dmnModel';

/*
 * Which tables run. Every table of the file, grouped by how they connect and in the order data flows;
 * switch any on or off, so any stretch can be run — one table, a pair, a long chain, a tree. A table
 * that is off but used by one that runs has its result given instead; one used by nothing that runs
 * is simply left out.
 */
const props = defineProps({
  decisions: { type: Array, required: true },
  focus: { type: Object, default: null }, // the table this is about, for the shortcuts
  modelValue: { type: Array, required: true } // ids of tables that run
});
const emit = defineEmits(['update:modelValue']);

const groups = computed(() => groupsOf(props.decisions.filter((d) => d.tabular)));
const focusGroup = computed(() => (props.focus ? groups.value.find((g) => g.some((d) => d.id === props.focus.id)) : null));
const scope = computed(() => runningScope(props.decisions, props.modelValue));
const givenIds = computed(() => new Set(scope.value.given.map((d) => d.id)));
const stateOf = (d) => (props.modelValue.includes(d.id) ? 'runs' : givenIds.value.has(d.id) ? 'given' : 'off');
const onlyFocus = computed(() => props.focus && props.modelValue.length === 1 && props.modelValue[0] === props.focus.id);
const wholeGroup = computed(() => focusGroup.value && focusGroup.value.length === props.modelValue.length && focusGroup.value.every((d) => props.modelValue.includes(d.id)));

// With no table in focus (the overview), each connected group is one shortcut.
const isExactly = (g) => g.length === props.modelValue.length && g.every((d) => props.modelValue.includes(d.id));
const groupName = (g) => (g.length <= 4 ? g.map((d) => d.name).join(', ') : `${g.slice(0, 3).map((d) => d.name).join(', ')} and ${g.length - 3} more`);

const set = (ids) => emit('update:modelValue', ids);
function toggle(d) {
  const on = new Set(props.modelValue);
  if (on.has(d.id)) {
    if (on.size === 1) return; // something has to run
    on.delete(d.id);
  } else {
    on.add(d.id);
  }
  set([...on]);
}

const titleOf = (d) => ({
  runs: `${d.name} runs. Click to switch it off.`,
  given: `${d.name} is off; a table that runs uses it, so you give its result. Click to run it.`,
  off: `${d.name} is off. Click to run it.`
}[stateOf(d)]);

const summary = computed(() => {
  const s = scope.value;
  const parts = [`Runs ${s.runs.map((d) => d.name).join(', ')}.`];
  if (s.given.length) parts.push(`You give what ${s.given.map((d) => d.name).join(' and ')} would give.`);
  return parts.join(' ');
});

const quickClass = (on) => ['quick btn-option', on ? 'on' : ''];
</script>

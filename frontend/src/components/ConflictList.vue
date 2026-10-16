<template>
  <section class="conflicts grid content-start gap-3" aria-label="Conflicts">
    <header class="grid gap-2.5">
      <h3>{{ remaining ? `${remaining} of ${conflicts.length} need you` : `All ${conflicts.length} answered` }}</h3>
      <div class="flex flex-wrap gap-1.5">
        <button v-if="cosmetic.length" type="button" class="btn btn-small" @click="takeCosmetic">{{ cosmeticLabel }} ({{ cosmetic.length }})</button>
        <button type="button" class="btn btn-small btn-quiet" @click="takeAll('OURS')">{{ allOursLabel }}</button>
        <button type="button" class="btn btn-small btn-quiet" @click="takeAll('THEIRS')">{{ allTheirsLabel }}</button>
      </div>
    </header>

    <template v-for="group in groups" :key="group.name">
      <h4 v-if="groups.length > 1" class="mt-2 text-sm font-bold text-ink-2">{{ group.name }}</h4>
      <ol class="m-0 list-none border-t border-rule p-0">
        <li
          v-for="c in group.items"
          :key="c.id"
          class="conflict grid cursor-pointer gap-1.5 border-b border-rule p-3 transition-colors duration-100 ease-out"
          :class="[
            { answered: !!resolutions[c.id], focused: focused === c.id },
            focused === c.id ? 'bg-select' : 'hover:bg-hover'
          ]"
          @click="$emit('focus', c.id)"
        >
          <p class="flex items-baseline gap-3 text-sm">
            <span class="min-w-0">
              <span class="font-semibold" :class="resolutions[c.id] ? 'text-ink-2' : 'text-ink'">{{ kindText(c) }}</span>
              <span v-if="c.tier === 'COSMETIC'" class="meta"> · wording only</span>
            </span>
            <span class="ml-auto flex-none" :class="resolutions[c.id] ? 'state state-clean' : 'state state-needs'">{{ resolutions[c.id] ? 'answered' : 'needs you' }}</span>
          </p>
          <p class="text-md">{{ c.label }}</p>
          <p v-if="c.detail && c.kind !== 'CELL' && c.kind !== 'TEXT'" class="meta -mt-0.5">{{ sentence(c.detail) }}</p>

          <div v-for="side in (sidesOf ? [] : sides(c))" :key="side.key" class="meta grid gap-0.5">
            <span class="text-ink-3">{{ side.title }}</span>
            <span v-for="a in side.changes" :key="a.sha">
              “{{ a.message }}” — {{ a.author }}, {{ whenText(a.when) }} <span class="mono text-ink-3">{{ a.shortSha }}</span>
            </span>
          </div>

          <div class="mt-1 flex flex-wrap gap-1.5" role="radiogroup" :aria-label="c.label">
            <button
              v-for="choice in c.choices"
              :key="choice.key"
              type="button"
              role="radio"
              :aria-checked="resolutions[c.id] === choice.key"
              class="choice grid max-w-full min-w-24 gap-px rounded border px-2.5 py-1.5 text-left transition-colors duration-100 ease-out"
              :class="[
                { picked: resolutions[c.id] === choice.key },
                resolutions[c.id] === choice.key ? 'border-ink bg-select ring-1 ring-ink' : 'border-rule-strong bg-ground hover:border-ink-3'
              ]"
              @click.stop="$emit('choose', c.id, choice.key); $emit('focus', c.id)"
            >
              <span class="text-xs font-semibold text-ink-2">{{ choiceSide(choice.key, c) }}</span>
              <span class="[overflow-wrap:anywhere]" :class="{ 'font-mono text-[0.8125rem]': looksLikeCell(c) }">{{ choice.label }}</span>
              <span v-if="sidesOf && commitOf(c, choice.key)" class="mt-0.5 block text-xs text-ink-3"><span class="mono">{{ commitOf(c, choice.key).shortSha }}</span> · {{ whenText(commitOf(c, choice.key).when) }}</span>
            </button>
          </div>
        </li>
      </ol>
    </template>
  </section>
</template>

<script setup>
import { computed } from 'vue';
import { whenText } from '../store';

const props = defineProps({
  conflicts: { type: Array, required: true },
  resolutions: { type: Object, required: true },
  focused: { type: String, default: null },
  theirName: { type: String, default: 'them' },
  // Optional grouping, e.g. by the step of a landing chain ("1.4 → 1.5"). Defaults to one group.
  groupOf: { type: Function, default: null },
  // Names for the two sides of a conflict, e.g. { OURS: 'Release 1.5', THEIRS: 'Your work space' }.
  sidesOf: { type: Function, default: null },
  // Bulk answers, named for this context.
  allOursLabel: { type: String, default: 'All mine' },
  allTheirsLabel: { type: String, default: 'All theirs' },
  cosmeticLabel: { type: String, default: 'Take their wording' }
});
const emit = defineEmits(['choose', 'focus']);

const remaining = computed(() => props.conflicts.filter((c) => !props.resolutions[c.id]).length);
const cosmetic = computed(() => props.conflicts.filter((c) => c.tier === 'COSMETIC'));

const groups = computed(() => {
  if (!props.groupOf) return [{ name: '', items: props.conflicts }];
  const map = new Map();
  for (const c of props.conflicts) {
    const name = props.groupOf(c);
    if (!map.has(name)) map.set(name, []);
    map.get(name).push(c);
  }
  return [...map].map(([name, items]) => ({ name, items }));
});

const KIND_TEXT = {
  DOMAIN_DELETE_MODIFY: 'One removed a domain the other changed',
  DOMAIN_ADD_ADD: 'Both created a domain with this name',
  DECISION_DELETE_MODIFY: 'One removed a table the other changed',
  DECISION_ADD_ADD: 'Both added this table, differently',
  EXPRESSION: 'Both changed this expression',
  HIT_POLICY: 'Both changed how matches are combined',
  COLUMN_RENAME: 'Both renamed this column, differently',
  COLUMN_TYPE: 'Both changed a column’s type',
  COLUMN_DELETE_MODIFY: 'One removed a column the other changed',
  COLUMN_DELETE_IN_USE: 'A removed column is used by new rules',
  CELL: 'Both changed the same cell',
  RULE_DELETE_MODIFY: 'One removed a rule the other changed',
  RULE_ADD_ADD: 'Both added a rule for the same case',
  RULE_ORDER: 'Both reordered the rules',
  CONNECTION_LOST: 'A connection would silently break',
  RESULT_CLASH: 'Two tables would give the same result',
  TEST_EXPECTATION: 'Both changed what a test expects',
  TEST_DELETE_MODIFY: 'One removed a test the other changed',
  TEST_ADD_ADD: 'Both added a test for the same case',
  TEST_COLUMN_DELETE_IN_USE: 'A removed check is used by new tests',
  TEXT: 'Both changed the same wording',
  LOW_CONFIDENCE_MATCH: 'Is this the same rule?'
};
const kindText = (c) => KIND_TEXT[c.kind] || c.kind;
const choiceSide = (key, c) => {
  const named = props.sidesOf ? props.sidesOf(c) : null;
  if (named && named[key]) return named[key];
  return { OURS: 'Yours', THEIRS: props.theirName === 'them' ? 'Theirs' : `${props.theirName}’s`, BOTH: 'Both' }[key] || key;
};
const looksLikeCell = (c) => c.kind === 'CELL' || c.kind === 'COLUMN_TYPE' || c.kind === 'COLUMN_RENAME';
const sentence = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) + (s.endsWith('.') ? '' : '.') : '');

// The commit that made a side's value: what a choice is named by.
const commitOf = (c, key) => (key === 'OURS' ? c.oursChanges?.[0] : key === 'THEIRS' ? c.theirsChanges?.[0] : null) || null;

const sides = (c) => {
  const named = props.sidesOf ? props.sidesOf(c) : null;
  return [
    { key: 'ours', title: named ? `On ${named.OURS.toLowerCase()}` : 'Your change', changes: (c.oursChanges || []).slice(0, 3) },
    { key: 'theirs', title: named ? `In ${named.THEIRS.toLowerCase()}` : 'Their change', changes: (c.theirsChanges || []).slice(0, 3) }
  ].filter((s) => s.changes.length);
};

function takeAll(key) {
  for (const c of props.conflicts) {
    if (c.choices.some((choice) => choice.key === key)) emit('choose', c.id, key);
  }
}

function takeCosmetic() {
  for (const c of cosmetic.value) emit('choose', c.id, 'THEIRS');
}
</script>

<template>
  <div class="resolver grid grid-cols-[minmax(300px,420px)_minmax(0,1fr)] items-start gap-8 max-[960px]:grid-cols-1">
    <ConflictList
      class="list"
      :conflicts="conflicts"
      :resolutions="resolutions"
      :focused="focused"
      :group-of="groupOf"
      :sides-of="sidesOf"
      :all-ours-label="oursAll || 'Keep every line’s'"
      :all-theirs-label="theirsAll || `Take all of ${spaceName}`"
      :cosmetic-label="`Take ${theirsName || spaceName}’s wording`"
      @choose="(id, key) => $emit('choose', id, key)"
      @focus="(id) => (focused = id)"
    />

    <section class="sticky top-4 grid min-w-0 gap-3 max-[960px]:static" aria-live="polite">
      <template v-if="current">
        <p class="flex flex-wrap items-center gap-1.5 text-xs text-ink-2">
          <LineBullet v-if="isLine(current.hop)" :line="current.hop" :main-branch="mainBranch" size="sm" />
          <span class="font-semibold text-ink">{{ placeName(current.hop) }}</span>
          <span>· {{ current.domain }}</span>
          <span v-if="hopOf(current)?.provisional">· shown as it would be with the earlier steps’ open answers kept as the line’s</span>
        </p>
        <h3>{{ current.label }}</h3>

        <!-- A table conflict: the merged table, the contested place marked. -->
        <template v-if="decision">
          <p class="meta">The merged table, with open answers showing {{ possessive(placeName(current.hop)) }} value for now.</p>
          <div class="overflow-x-auto rounded border border-rule bg-ground">
            <RuleGrid :decision="decision" readonly :cell-states="cellStates" :focus-rule="current.ruleId" />
          </div>
        </template>

        <!-- A test conflict: the cases of that table, as merged so far. -->
        <template v-else-if="testBlock">
          <p class="meta">{{ testBlock.decision }}’s cases as merged so far.</p>
          <div class="overflow-x-auto rounded border border-rule bg-ground">
            <table class="w-full border-collapse text-sm">
              <thead class="bg-platform">
                <tr><th v-for="c in testBlock.columns" :key="c" class="border-b border-rule px-2.5 py-1.5 text-left text-xs font-bold whitespace-nowrap text-ink-2">{{ c }}</th></tr>
              </thead>
              <tbody>
                <tr v-for="(row, i) in testBlock.rows" :key="i">
                  <td v-for="(cell, j) in row" :key="j" class="border-b border-rule px-2.5 py-1.5 text-left font-mono text-[0.8125rem] whitespace-nowrap">{{ cell }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>

        <div class="grid gap-1.5 border-t border-rule pt-3">
          <div v-if="current.ours != null" class="grid grid-cols-[12rem_minmax(0,1fr)] items-baseline gap-3 max-[960px]:grid-cols-1 max-[960px]:gap-0.5">
            <span class="meta">{{ sidesOf(current).OURS }}</span>
            <span class="font-mono text-[0.8125rem] [overflow-wrap:anywhere]">{{ current.ours }}</span>
          </div>
          <div v-if="current.theirs != null" class="grid grid-cols-[12rem_minmax(0,1fr)] items-baseline gap-3 max-[960px]:grid-cols-1 max-[960px]:gap-0.5">
            <span class="meta">{{ sidesOf(current).THEIRS }}</span>
            <span class="font-mono text-[0.8125rem] [overflow-wrap:anywhere]">{{ current.theirs }}</span>
          </div>
          <div v-if="current.base != null" class="grid grid-cols-[12rem_minmax(0,1fr)] items-baseline gap-3 max-[960px]:grid-cols-1 max-[960px]:gap-0.5">
            <span class="meta">Before either changed it</span>
            <span class="font-mono text-[0.8125rem] text-ink-2 [overflow-wrap:anywhere]">{{ current.base }}</span>
          </div>
        </div>
      </template>
      <p v-else class="meta">Choose a conflict to see where it is.</p>
    </section>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import ConflictList from './ConflictList.vue';
import LineBullet from './LineBullet.vue';
import RuleGrid from './RuleGrid.vue';
import { DmnDocument } from '../lib/dmnModel';
import { parseSpec } from '../lib/testSpec';
import { branchName } from '../store';

/*
 * Every conflict of a landing, across every step of the chain, beside the place it is about. The
 * answers are data: each conflict brings its own choices, so nothing here switches on the kind.
 */
const props = defineProps({
  result: { type: Object, required: true }, // Landing.Result
  resolutions: { type: Object, required: true },
  mainBranch: { type: String, default: 'main' },
  spaceName: { type: String, default: 'your work space' },
  // Bulk answers named for the context (landing by default).
  oursAll: { type: String, default: null },
  theirsAll: { type: String, default: null },
  theirsName: { type: String, default: null }
});
defineEmits(['choose']);

const conflicts = computed(() => props.result.hops.flatMap((h) => h.conflicts));
const focused = ref(null);
watch(conflicts, (list) => {
  if (!list.some((c) => c.id === focused.value)) focused.value = list.find((c) => !props.resolutions[c.id])?.id || list[0]?.id || null;
}, { immediate: true });

const current = computed(() => conflicts.value.find((c) => c.id === focused.value) || null);
const isLine = (b) => b === props.mainBranch || /^release\//.test(b || '');
const hopOf = (c) => props.result.hops.find((h) => h.into === c.hop);
const capital = (s) => (/^(main|master|release\/|feature\/|hotfix\/)/.test(s) ? s : s.charAt(0).toUpperCase() + s.slice(1));
const possessive = (s) => (s.endsWith('s') ? `${s}’` : `${s}’s`);
const placeName = (branch) => (branch === props.result.space ? props.spaceName : branchName(branch, props.mainBranch));
// Each side is named by the commit that made its value; the place is only a fallback.
const commitName = (a) => (a ? `${a.author}: “${a.message}”` : null);
const sidesOf = (c) => {
  const hop = hopOf(c);
  return {
    OURS: commitName(c.oursChanges?.[0]) || capital(placeName(c.hop)),
    THEIRS: commitName(c.theirsChanges?.[0]) || (hop ? capital(placeName(hop.from)) : 'Coming in'),
    BOTH: 'Both'
  };
};
const groupOf = (c) => {
  const hop = hopOf(c);
  return `Into ${placeName(c.hop)}${hop ? `, from ${placeName(hop.from)}` : ''} · ${c.domain}`;
};

const domain = computed(() => {
  if (!current.value) return null;
  return hopOf(current.value)?.domains.find((d) => d.name === current.value.domain) || null;
});
const decision = computed(() => {
  const c = current.value;
  if (!c || !c.decisionId || c.kind.startsWith('TEST_') || !domain.value?.previewDmn) return null;
  try {
    return new DmnDocument(domain.value.previewDmn).read().find((d) => d.id === c.decisionId && d.tabular) || null;
  } catch {
    return null;
  }
});
const testBlock = computed(() => {
  const c = current.value;
  if (!c || !c.kind.startsWith('TEST_') || !domain.value?.previewYaml) return null;
  try {
    return parseSpec(domain.value.previewYaml).find((b) => b.decision === c.decisionId) || null;
  } catch {
    return null;
  }
});
const cellStates = computed(() => {
  const states = {};
  for (const c of conflicts.value) {
    if (c.hop === current.value?.hop && c.domain === current.value?.domain && c.ruleId && c.columnId) {
      states[`${c.ruleId}|${c.columnId}`] = props.resolutions[c.id] ? 'resolved' : 'contested';
    }
  }
  return states;
});
</script>

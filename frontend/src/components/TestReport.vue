<template>
  <div
    class="report grid gap-2"
    :class="[
      { failed: report && !report.passed, passed: report?.passed },
      plain ? '' : ['rounded border px-3.5 py-3', report?.passed ? 'border-clean bg-clean-tint' : report && !report.passed ? 'border-[oklch(85%_0.06_25)] bg-needs-tint' : 'border-rule bg-ground']
    ]"
    aria-live="polite"
  >
    <p v-if="busy" class="loading">Running every domain’s tests at {{ label }}…</p>
    <p v-else-if="error" class="state state-needs">The tests could not run: {{ error }}</p>
    <template v-else-if="report">
      <p class="flex items-center gap-2" :class="plain ? 'text-sm font-semibold' : 'font-bold'">
        <span :class="report.passed ? 'state state-clean' : 'state state-needs'">{{ report.passed ? 'Passing' : 'Failing' }}</span>
        <span>
          <template v-if="report.passed">All {{ report.total }} test {{ report.total === 1 ? 'case passes' : 'cases pass' }} across {{ report.domains.length }} {{ report.domains.length === 1 ? 'domain' : 'domains' }} at {{ label }}.</template>
          <template v-else>{{ report.failed }} {{ report.failed === 1 ? 'test fails' : 'tests fail' }} at {{ label }}.</template>
        </span>
      </p>
      <ul class="m-0 grid list-none gap-1.5 p-0">
        <li v-for="d in report.domains" :key="d.domain" class="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <router-link :to="linkTo ? linkTo(d.domain) : `/commit/${report.commit}/${d.domain}`" class="min-w-32 font-semibold">{{ d.domain }}</router-link>
          <span v-if="d.error" class="state state-needs">its tests cannot run: {{ d.error }}</span>
          <span v-else-if="d.noTests" class="meta">no tests</span>
          <span v-else-if="d.failures.length" class="state state-needs">{{ d.failures.length }} of {{ d.total }} fail</span>
          <span v-else class="meta">{{ d.total }} pass</span>
          <span v-if="notes?.[d.domain]" class="meta basis-full">{{ notes[d.domain] }}</span>
          <ul v-if="d.failures.length" class="mt-0.5 mb-1 ml-4 grid basis-full list-none gap-0.5 p-0 text-sm">
            <li v-for="f in d.failures.slice(0, 5)" :key="f.name"><strong class="font-semibold">{{ f.name }}</strong> <span class="text-needs-ink">{{ plainFailure(f.error) }}</span></li>
            <li v-if="d.failures.length > 5" class="meta">and {{ d.failures.length - 5 }} more</li>
          </ul>
        </li>
      </ul>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { plainFailure } from '../lib/failures';

/* Every domain's tests at one version — what a deploy of that version runs first. */
const props = defineProps({
  report: { type: Object, default: null }, // DeployService.TestReport
  busy: { type: Boolean, default: false },
  error: { type: String, default: null },
  rev: { type: String, default: '' },
  // Where a domain opens (default: as it is at this commit), and a word on a domain, e.g. where a failure comes from.
  linkTo: { type: Function, default: null },
  notes: { type: Object, default: null },
  // Inside a notice that already says what is wrong: no box of its own, just the rows.
  plain: { type: Boolean, default: false }
});

const label = computed(() => props.rev || props.report?.commit?.slice(0, 7) || 'this version');
</script>

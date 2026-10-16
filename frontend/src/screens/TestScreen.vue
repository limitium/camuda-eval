<template>
  <div class="page-body">
    <h1>Test {{ scopeText }}</h1>
    <p class="lede">{{ intro }}</p>

    <div class="rows mt-8">
      <div v-for="t in targets" :key="t" class="grid grid-cols-[1fr_auto] gap-x-6 gap-y-2 px-1 py-4">
        <div class="flex items-baseline gap-3">
          <router-link :to="spacePath(ws, `/table/${t}`)" class="text-lg font-bold no-underline hover:underline">{{ t }}</router-link>
          <span v-if="space.changeOf(t) === 'new' || space.changeOf(t) === 'changed'" class="state state-edit">{{ space.changeOf(t) === 'new' ? 'New' : space.changeOf(t) === 'changed' ? 'Changed' : '' }}</span>
        </div>
        <div class="self-center">
          <span v-if="runningNow === t" class="state state-quiet">Running…</span>
          <span v-else-if="!verdicts[t]" class="state state-quiet">Not run since the last change</span>
          <span v-else-if="verdicts[t].passed" class="state state-clean">All {{ verdicts[t].total }} pass</span>
          <span v-else-if="verdicts[t].error && !verdicts[t].total" class="state state-needs">Could not run</span>
          <span v-else class="state state-needs">{{ verdicts[t].failed }} of {{ verdicts[t].total }} fail</span>
        </div>
        <ul v-if="verdicts[t] && !verdicts[t].passed" class="col-span-full m-0 grid list-none border-t border-rule p-0">
          <li v-for="f in failuresOf(t)" :key="f.name" class="grid gap-0.5 border-b border-rule py-2 pl-3 last:border-b-0">
            <strong class="font-semibold">{{ f.name }}</strong>
            <span class="font-mono text-[0.8125rem] text-needs-ink [overflow-wrap:anywhere]">{{ firstLine(f.error) }}</span>
          </li>
        </ul>
      </div>
      <p v-if="!targets.length && space.loading" class="loading px-1 py-5">Reading what changed…</p>
      <p v-if="!targets.length && !space.loading && !savedReport" class="meta px-1 py-5">{{ ws ? 'Nothing has changed in this work space since its last version, so there is nothing new to test.' : 'There are no tables yet.' }}</p>
    </div>

    <!-- Nothing unsaved: the saved version is what lands, so its own tests are the ones that count. -->
    <template v-if="savedReport">
      <!-- Failing: one notice — the problem, the failing rows, and the way back. -->
      <div v-if="!savedReport.passed" class="notice notice-needs mt-7 grid max-w-[980px] gap-3">
        <p class="font-semibold">The saved version “{{ space.info?.lastMessage }}” fails its tests, so it cannot land.</p>
        <TestReport plain :report="savedReport" rev="your saved version" :link-to="(d) => spacePath(ws, `/table/${d}`)" />
        <p class="text-sm">Open the table, fix the marked rows, save, and save a version again.</p>
      </div>
      <TestReport v-else class="mt-7" :report="savedReport" rev="your saved version" :link-to="(d) => spacePath(ws, `/table/${d}`)" />
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import TestReport from '../components/TestReport.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { space } from '../lib/space';
import { app, spaceIdFromRoute, spacePath } from '../store';

const route = useRoute();
const router = useRouter();
const ws = spaceIdFromRoute(route.params);
const runningNow = ref(null);
const busy = ref(false);

// In a work space, what changed; in the shared folder, everything.
const targets = computed(() => (ws ? space.changedTables.filter((t) => space.changeOf(t) !== 'removed') : space.tables));
const verdicts = computed(() => app.verdictsFor(ws));
const allPass = computed(() => targets.value.length > 0 && targets.value.every((t) => verdicts.value[t]?.passed));
const scopeText = computed(() => (ws ? 'what you changed' : 'every table'));
const intro = computed(() => (ws
  ? 'Each changed table is run against its own test cases. A version can be saved with failing tests, but it cannot land until they pass.'
  : 'Each table is run against its own test cases.'));

const failuresOf = (t) => {
  const v = verdicts.value[t];
  if (!v) return [];
  if (v.error && !v.total) return [{ name: 'The run', error: v.error }];
  return v.tests.filter((x) => !x.passed);
};
const firstLine = (s) => String(s || '').split('\n')[0];

// The saved version's own run, when nothing is unsaved (the server keeps it per commit).
const savedReport = ref(null);
onMounted(async () => {
  await space.open(ws);
  if (ws && !space.changes.length && (space.info?.ahead || 0) > 0) {
    api.commitTests(space.info.head).then((r) => { savedReport.value = r; }).catch(() => {});
  }
});

async function runAll() {
  if (busy.value) return;
  busy.value = true;
  let failing = 0;
  try {
    for (const t of targets.value) {
      runningNow.value = t;
      try {
        const [dmn, tests] = await Promise.all([api.readDmn(ws, t), api.readTests(ws, t)]);
        const status = await api.runTests(t, dmn.content, tests.content);
        const cases = status.tests || [];
        const passed = status.result === 'passed' && !status.error;
        if (!passed) failing++;
        app.recordVerdict(ws, t, { passed, failed: cases.filter((c) => !c.passed).length, total: cases.length, tests: cases, error: status.error || null });
      } catch (error) {
        failing++;
        app.recordVerdict(ws, t, { passed: false, failed: 0, total: 0, tests: [], error: error.message });
      }
    }
  } finally {
    runningNow.value = null;
    busy.value = false;
  }
  app.say(failing
    ? `${failing} of ${targets.value.length} ${targets.value.length === 1 ? 'table has' : 'tables have'} failing tests. Open a table to see the failing rows.`
    : `Every changed table passes its tests.`, failing ? 'needs' : 'clean');
}

useBar(() => {
  const broken = savedReport.value?.domains.find((d) => d.failures.length || d.error);
  if (broken) {
    return { primary: { label: `Open ${broken.domain} to fix it`, run: () => router.push(spacePath(ws, `/table/${broken.domain}`)) } };
  }
  if (ws && allPass.value) {
    return {
      secondary: [{ label: 'Run again', run: runAll, disabled: busy.value }],
      primary: { label: 'Review and save version', run: () => router.push(spacePath(ws, '/version')) }
    };
  }
  return { primary: { label: busy.value ? `Running ${runningNow.value || ''}` : 'Run the tests', run: runAll, busy: busy.value, disabled: !targets.value.length } };
});
</script>

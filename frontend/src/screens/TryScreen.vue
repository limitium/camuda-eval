<template>
  <div class="page-body">
    <h1>Try it in an application</h1>
    <p class="lede">Publishing puts this work space’s saved version of the rules an application reads onto that application’s topic, so the application uses it straight away. Every test runs first.</p>

    <p v-if="loadError" class="notice notice-needs mt-7">{{ loadError }}</p>
    <p v-else-if="!rows" class="loading mt-7">Reading the applications…</p>

    <template v-else>
      <p v-if="!app.info.allowDraftPublish" class="notice mt-7">Drafts are not published from the {{ app.info.env }} instance. Land it and release it instead.</p>
      <p v-else-if="space.changes.length" class="notice notice-needs mt-7">You have changes that are not in a saved version. Only a saved version can be tried — <router-link :to="spacePath(spaceId, '/version')" class="font-semibold">save one first</router-link>.</p>

      <!-- Done: the result, highlighted, in place of the form. -->
      <section
        v-else-if="published"
        class="result mt-7 grid max-w-[760px] gap-2.5 rounded border px-5 py-5"
        :class="published.complete ? 'ok border-clean bg-clean-tint' : 'failed border-needs bg-needs-tint'"
        role="status"
      >
        <template v-if="published.complete">
          <p class="flex items-center gap-2 text-lg font-bold"><Check :size="20" :stroke-width="1.75" class="text-clean-ink" aria-hidden="true" /> {{ published.application }} runs this work space’s version now</p>
          <p>Published {{ published.files.join(', ') }} at <router-link :to="`/commit/${published.commit}`" class="mono">{{ published.commit.slice(0, 7) }}</router-link> to {{ published.application }}’s topic, after all {{ published.tests.total }} test {{ published.tests.total === 1 ? 'case' : 'cases' }} passed. Everyone using {{ published.application }} uses it until a release is put back.</p>
          <div class="mt-1 flex flex-wrap gap-2">
            <router-link :to="`/applications/${published.application}`" class="btn">See {{ published.application }}</router-link>
            <button type="button" class="btn btn-quiet" @click="again">Publish to another application</button>
          </div>
        </template>
        <template v-else>
          <p class="flex items-center gap-2 text-lg font-bold"><span class="state state-needs">Not taken</span> {{ published.application }}’s topic did not take it</p>
          <p>{{ published.error }}. Nothing else changed; publishing again repeats it.</p>
          <div class="mt-1 flex flex-wrap gap-2"><button type="button" class="btn" @click="again">Try again</button></div>
        </template>
      </section>

      <template v-else>
        <!-- 1. Which application -->
        <fieldset class="m-0 mt-7 grid max-w-[860px] gap-0 border-0 p-0">
          <legend class="label !mb-2.5 flex items-center text-ink"><span class="mr-1.5 inline-grid size-5 place-items-center rounded-full border border-rule-strong text-xs font-bold text-ink-2">1</span> Which application</legend>
          <div class="rows">
            <label
              v-for="d in rows"
              :key="d.name"
              class="grid cursor-pointer grid-cols-[20px_minmax(120px,0.6fr)_minmax(0,1fr)_minmax(0,1.2fr)] items-baseline gap-x-3 gap-y-0.5 px-2.5 py-3 transition-colors duration-100 ease-out max-md:grid-cols-[20px_minmax(0,1fr)]"
              :class="domain === d.name ? 'picked bg-select ring-1 ring-ink ring-inset' : 'hover:bg-hover'"
            >
              <input v-model="domain" type="radio" name="domain" :value="d.name" class="m-0 self-center accent-ink" />
              <span class="font-semibold">{{ d.name }}</span>
              <span class="meta max-md:col-start-2">reads {{ d.files.join(', ') }}</span>
              <span class="max-md:col-start-2" :class="d.changed.length ? 'state state-live' : 'meta'">{{ d.changed.length ? `this work space changed ${d.changed.join(', ')}` : 'unchanged by this work space' }}</span>
              <span class="col-start-2 col-end-[-1] text-xs text-ink-3">now: {{ describe(d.version) }}</span>
            </label>
          </div>
        </fieldset>

        <!-- 2. The tests, already running -->
        <div class="mt-7 grid max-w-[860px] gap-2.5">
          <p class="label !mb-0 flex items-center text-ink"><span class="mr-1.5 inline-grid size-5 place-items-center rounded-full border border-rule-strong text-xs font-bold text-ink-2">2</span> Every test at this version</p>
          <TestReport :report="report" :busy="testing" :rev="space.info?.head?.slice(0, 7) || ''" />
        </div>

        <!-- 3. Agree, knowing what it replaces -->
        <div v-if="picked" class="mt-7 grid max-w-[860px] gap-2.5">
          <p class="label !mb-0 flex items-center text-ink"><span class="mr-1.5 inline-grid size-5 place-items-center rounded-full border border-rule-strong text-xs font-bold text-ink-2">3</span> What it replaces</p>
          <p v-if="runsThis" class="notice">{{ picked.name }} already runs this version (published {{ whenText(picked.version.publishedAt) }}). Publishing again repeats it.</p>
          <div class="notice notice-live grid gap-1.5">
            <p><strong class="font-bold">This replaces what {{ picked.name }} runs, for everyone using it</strong> — the application reads each file under a fixed name, so there is no private copy. Putting the last release back is one step on {{ picked.name }}’s page.</p>
            <p v-if="picked.version && picked.version.workspace && picked.version.workspace !== spaceId">It runs {{ branchName(picked.version.workspace, app.info.mainBranch) }} now ({{ whenText(picked.version.publishedAt) }}); publishing replaces it — tell them.</p>
          </div>
          <label class="mt-3 inline-flex cursor-pointer items-center gap-2 font-semibold">
            <input v-model="confirmed" type="checkbox" class="m-0 size-4 accent-ink" />
            I understand this changes what everyone using {{ picked.name }} runs.
          </label>
        </div>
      </template>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Check } from 'lucide-vue-next';
import TestReport from '../components/TestReport.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { space } from '../lib/space';
import { app, branchName, spaceIdFromRoute, spacePath, whenText } from '../store';

const route = useRoute();
const router = useRouter();
const spaceId = spaceIdFromRoute(route.params);
const rows = ref(null);
const loadError = ref(null);
const domain = ref('');
const confirmed = ref(false);
const busy = ref(false);
const report = ref(null);
const testing = ref(false);

const published = ref(null);
// The picked application already runs exactly this saved version.
const runsThis = computed(() => !!picked.value?.version && picked.value.version.commit === space.info?.head);
function again() {
  published.value = null;
  confirmed.value = false;
}
const picked = computed(() => (rows.value || []).find((d) => d.name === domain.value) || null);
const describe = (v) => {
  if (!v) return 'nothing deployed';
  if (v.release) return `${v.release}`;
  if (v.workspace === spaceId) return `this work space’s draft, ${whenText(v.publishedAt)}`;
  return `${branchName(v.workspace, app.info.mainBranch)} (draft)`;
};

onMounted(async () => {
  try {
    await space.open(spaceId);
    rows.value = await api.draftPreview(spaceId);
    domain.value = rows.value.find((d) => d.changed.length)?.name || '';
  } catch (e) {
    loadError.value = `The applications could not be read: ${e.message}`;
    return;
  }
  if (!space.changes.length && space.info?.head) {
    testing.value = true;
    try {
      report.value = await api.commitTests(space.info.head);
    } catch {
      report.value = null;
    } finally {
      testing.value = false;
    }
  }
});

async function publish() {
  busy.value = true;
  try {
    const result = await api.publishDraft(domain.value, spaceId);
    published.value = { application: domain.value, ...result };
    window.scrollTo?.({ top: 0, behavior: 'smooth' });
    document.getElementById('stage')?.scrollTo?.({ top: 0, behavior: 'smooth' });
    if (result.complete) {
      app.say(`${domain.value} runs this work space’s version now. Everyone using it does, until a release is put back.`, 'live');
      rows.value = await api.draftPreview(spaceId);
      confirmed.value = false;
    } else {
      app.say(`${domain.value}’s topic did not take it: ${result.error}`, 'needs');
    }
  } catch (e) {
    if (e.body?.code === 'TESTS_FAILED') report.value = e.body.details;
    app.say(`Nothing was published: ${e.message}`, 'needs');
  } finally {
    busy.value = false;
  }
}

useBar(() => {
  if (published.value?.complete) {
    return {
      secondary: [{ label: `See ${published.value.application}`, quiet: true, run: () => router.push(`/applications/${published.value.application}`) }],
      primary: { label: 'Go on to landing', run: () => router.push(spacePath(spaceId, '/land')) }
    };
  }
  const ready = app.info.allowDraftPublish && !space.changes.length && picked.value;
  return {
    secondary: [{ label: 'Skip to landing', quiet: true, run: () => router.push(spacePath(spaceId, '/land')) }],
    hint: !domain.value ? 'Choose an application.'
      : testing.value ? 'Running every test…'
        : report.value && !report.value.passed ? 'Tests fail in this version, so it cannot be published.'
          : !confirmed.value ? 'Tick the box to agree, then publish.' : null,
    primary: ready
      ? { label: `${runsThis.value ? 'Publish again to' : 'Publish to'} ${picked.value.name}`, run: publish, busy: busy.value, disabled: !confirmed.value || testing.value || !report.value?.passed }
      : null
  };
});
</script>

<style scoped>
/* A published result arrives with a brief green halo; keyframes are not expressible as utilities. */
.result { animation: arrive 600ms var(--ease-out); }
@keyframes arrive { from { box-shadow: 0 0 0 6px oklch(70% 0.14 150 / 0.35); } to { box-shadow: 0 0 0 0 transparent; } }
@media (prefers-reduced-motion: reduce) { .result { animation: none; } }
</style>

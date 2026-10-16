<template>
  <div class="page-body">
    <p v-if="error" class="notice notice-needs">{{ error }}</p>
    <p v-else-if="!domain" class="loading">Reading {{ name }}’s topic…</p>

    <template v-else>
      <h1>{{ domain.name }}</h1>
      <p class="meta mt-1.5">reads <strong class="font-semibold text-ink">{{ domain.files.join(', ') }}</strong> · topic <span class="mono">{{ domain.server.topic }}</span> on <span class="mono">{{ domain.server.where }}</span></p>
      <ul v-if="domain.problems?.length || !domain.ok" class="mt-3 grid list-none gap-1 p-0">
        <li v-if="!domain.ok" class="state state-needs">The topic cannot be read: {{ domain.error }}</li>
        <li v-for="p in domain.problems" :key="p" class="state state-needs">{{ p }}</li>
      </ul>

      <!-- 1. What runs, to look at and try. -->
      <section class="mt-9 grid max-w-[860px] gap-2.5">
        <h2 class="text-sm font-bold text-ink-2">Runs now</h2>
        <p v-if="!domain.version" class="meta">Nothing has been deployed to {{ domain.name }} yet.</p>
        <template v-else>
          <div v-if="domain.version.release" class="flex flex-wrap items-center gap-x-3 gap-y-1">
            <LineBullet v-if="runsOn" :line="runsOn" :main-branch="app.info.mainBranch" :title="lineName(runsOn, app.info.mainBranch)" />
            <span class="text-lg leading-none"><span class="tag">{{ domain.version.release }}</span></span>
            <span class="font-semibold">release {{ domain.version.line?.slice(8) }}</span>
            <span class="state" :class="domain.inStep && !domain.problems?.length ? 'state-clean' : 'state-needs'">{{ domain.inStep && !domain.problems?.length ? 'running' : 'problem' }}</span>
            <span class="meta">deployed by {{ domain.version.publishedBy }} {{ whenText(domain.version.publishedAt) }}</span>
            <span v-if="!domain.inStep" class="state state-needs">its files run different versions — redeploy</span>
          </div>
          <!-- A draft runs: loud through the state word and one amber rule, not a box. -->
          <div v-else class="flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-live px-1 pt-3">
            <span class="state state-live text-md font-bold">draft live</span>
            <LineBullet v-if="runsOn" :line="runsOn" :main-branch="app.info.mainBranch" size="sm" :title="lineName(runsOn, app.info.mainBranch)" />
            <span class="font-semibold">{{ branchName(domain.version.workspace, app.info.mainBranch) }}, built on {{ builtOn }}</span>
            <span class="meta">deployed by {{ domain.version.publishedBy }} {{ whenText(domain.version.publishedAt) }}</span>
            <span v-if="!domain.inStep" class="state state-needs">its files run different versions — redeploy</span>
          </div>
          <div class="rows">
            <router-link v-for="f in domain.running" :key="f.name" :to="`/commit/${f.commit}/${f.name}`" class="group flex min-h-12 items-center gap-3.5 px-1 py-3 no-underline hover:bg-hover">
              <span class="min-w-40 text-lg font-semibold">{{ f.name }}</span>
              <span class="font-mono text-xs text-ink-3">{{ f.commit.slice(0, 7) }}</span>
              <span class="ml-auto inline-flex items-center gap-0.5 text-sm text-ink-2 group-hover:text-ink">Look and try <ChevronRight :size="16" :stroke-width="1.75" aria-hidden="true" /></span>
            </router-link>
          </div>
        </template>
      </section>

      <!-- 2. Start a work space from what runs. -->
      <section v-if="app.author && domain.version" class="mt-9 grid max-w-[860px] gap-2.5">
        <h2 class="text-sm font-bold text-ink-2">Change it</h2>
        <p class="meta">Start a work space from exactly what runs here{{ domain.version.release ? ` — a fix to ${domain.version.release}, landing in release ${domain.version.line?.slice(8)} and carried forward` : '' }}.</p>
        <form class="flex flex-wrap items-center gap-2" @submit.prevent="startSpace">
          <label class="label m-0 w-full" for="space-name">Name of the work space</label>
          <input id="space-name" v-model="spaceName" class="field max-w-[320px]" autocomplete="off" />
          <button type="submit" class="btn" :disabled="!validName || starting" :aria-busy="starting">{{ starting ? 'Starting…' : 'Start the work space' }}</button>
          <p class="meta w-full">Becomes the branch <span class="mono">{{ domain.version.release ? 'hotfix' : 'feature' }}/{{ app.info.user }}/{{ slug || '…' }}</span></p>
        </form>
      </section>

      <!-- 3. Deploy a version. -->
      <section class="mt-9 grid max-w-[860px] gap-2.5">
        <h2 class="text-sm font-bold text-ink-2">Deploy a version</h2>
        <p class="meta">{{ domain.name }} takes the version’s {{ domain.files.join(', ') }}. Every test runs first; one failing test stops it.</p>
        <select v-model="version" class="field max-w-[460px]" aria-label="Version to deploy">
          <option value="" disabled>choose a released version</option>
          <option v-for="v in versions" :key="v.name" :value="v.name">{{ v.name }}{{ v.verified ? '' : ' (made by hand, not verified)' }}{{ v.notes && v.notes !== v.name ? ` — ${v.notes}` : '' }}</option>
        </select>
        <TestReport v-if="version" :report="report" :busy="testing" :error="testError" :rev="version" />
        <p v-if="outcome" class="notice" :class="outcome.complete ? 'notice-clean' : 'notice-needs'">
          <template v-if="outcome.complete">{{ domain.name }} now runs it{{ outcome.stopped?.length ? `; stopped ${outcome.stopped.join(', ')}` : '' }}.</template>
          <template v-else>The topic did not take it: {{ outcome.error }}. Deploys are idempotent — redeploy once it can be reached.</template>
        </p>
      </section>

      <!-- 4. History, from the topic: a timetable — when, what, who. -->
      <section class="mt-9 grid max-w-[860px] gap-2.5">
        <h2 class="text-sm font-bold text-ink-2">History</h2>
        <p v-if="domain.historyError" class="meta">The history could not be read: {{ domain.historyError }}</p>
        <p v-else-if="!domain.history?.length" class="meta">Nothing yet.</p>
        <ol v-else class="rows m-0 list-none p-0">
          <li
            v-for="(h, i) in domain.history.slice(0, showAll ? undefined : 8)"
            :key="i"
            class="grid grid-cols-[8.5rem_minmax(0,1.3fr)_minmax(0,1fr)_4.5rem] items-baseline gap-x-4 gap-y-0.5 px-1 py-2.5 max-md:grid-cols-[1fr_auto]"
          >
            <span class="text-xs text-ink-3 tabular-nums max-md:col-span-2">{{ whenText(h.at) }}</span>
            <span class="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
              <template v-if="h.release">
                <LineBullet v-if="lineOfTag(h.release)" class="self-center" :line="lineOfTag(h.release)" :main-branch="app.info.mainBranch" size="sm" :title="lineName(lineOfTag(h.release), app.info.mainBranch)" />
                <span class="tag">{{ h.release }}</span>
              </template>
              <span v-else-if="h.workspace" class="state state-live">Draft {{ branchName(h.workspace, app.info.mainBranch) }}</span>
              <span v-else class="state state-quiet">stopped</span>
            </span>
            <span class="min-w-0 text-sm">
              <span class="font-semibold">{{ h.by || '' }}</span>
              <span class="block truncate text-xs text-ink-3">{{ h.files.join(', ') }}{{ h.stopped.length ? ` · stopped ${h.stopped.join(', ')}` : '' }}</span>
            </span>
            <router-link v-if="h.commit" :to="`/commit/${h.commit}`" class="justify-self-end font-mono text-xs text-ink-2 hover:text-ink max-md:row-start-2 max-md:col-start-2">{{ h.commit.slice(0, 7) }}</router-link>
          </li>
        </ol>
        <button v-if="domain.history?.length > 8 && !showAll" type="button" class="btn btn-quiet btn-small justify-self-start" @click="showAll = true">Show all {{ domain.history.length }}</button>
        <p class="text-xs text-ink-3">Read from the topic. A compacted topic keeps only recent records, so the oldest deployments may no longer be listed.</p>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ChevronRight } from 'lucide-vue-next';
import LineBullet from '../components/LineBullet.vue';
import TestReport from '../components/TestReport.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { app, branchName, branchSlug, lineName, spacePath, whenText } from '../store';

/*
 * One application: what it runs — to look at and try, or to start a work space from — deploying
 * a version to it, and its history, all read from its own topic.
 */
const route = useRoute();
const router = useRouter();
const name = String(route.params.name);
const domain = ref(null);
const error = ref(null);
const versions = ref([]);
const version = ref('');
const report = ref(null);
const testing = ref(false);
const testError = ref(null);
const outcome = ref(null);
const busy = ref(false);
const starting = ref(false);
const showAll = ref(false);
const stamp = new Date().toISOString().slice(5, 10).replace('-', '');
const spaceName = ref(`${name}-${stamp}`);
const slug = computed(() => branchSlug(spaceName.value));
const validName = computed(() => slug.value.length > 0);

const builtOn = computed(() => {
  const b = domain.value?.version?.baseline;
  return !b ? 'an unknown starting point' : /^v\d/.test(b) ? b : lineName(b, app.info.mainBranch);
});

// release/M.m for a version vM.m.p.
const lineOfTag = (tag) => {
  const m = /^v(\d+)\.(\d+)/.exec(tag || '');
  return m ? `release/${m[1]}.${m[2]}` : null;
};

// The line what runs belongs to: a release's own line; a draft's, the line it lands in.
const runsOn = computed(() => {
  const v = domain.value?.version;
  if (!v) return null;
  if (v.line) return v.line;
  if (v.release) return lineOfTag(v.release);
  if (/^hotfix\//.test(v.workspace || '')) return lineOfTag(v.baseline);
  if (/^release\//.test(v.baseline || '')) return v.baseline;
  return app.info.mainBranch;
});

async function load() {
  try {
    domain.value = await api.application(name);
  } catch (e) {
    error.value = e.status === 404 ? `This instance controls no application called ${name}.` : e.message;
  }
}

async function loadVersions() {
  try {
    const lines = await api.lines();
    const all = new Map();
    for (const l of lines) for (const v of l.versions) all.set(v.name, v);
    versions.value = [...all.values()].sort((a, b) => b.name.localeCompare(a.name, undefined, { numeric: true }));
  } catch {
    versions.value = [];
  }
}

watch(version, async (v) => {
  report.value = null;
  testError.value = null;
  outcome.value = null;
  if (!v) return;
  testing.value = true;
  try {
    const r = await api.commitTests(v);
    if (version.value === v) report.value = r;
  } catch (e) {
    testError.value = e.message;
  } finally {
    testing.value = false;
  }
});

async function deploy() {
  busy.value = true;
  try {
    outcome.value = await api.deploy(name, version.value);
    app.say(outcome.value.complete ? `${name} runs ${version.value} now.` : `${name}’s topic did not take ${version.value}; redeploy once it can be reached.`, outcome.value.complete ? 'live' : 'needs');
    version.value = '';
  } catch (e) {
    if (e.body?.code === 'TESTS_FAILED') report.value = e.body.details;
    app.say(`Nothing was deployed: ${e.message}`, 'needs');
  } finally {
    busy.value = false;
    load();
  }
}

async function act(call, said) {
  busy.value = true;
  try {
    const r = await call();
    app.say(r.complete === false ? `The topic did not take it: ${r.error}` : said, r.complete === false ? 'needs' : 'live');
  } catch (e) {
    app.say(`Nothing changed: ${e.message}`, 'needs');
  } finally {
    busy.value = false;
    load();
  }
}

// A fix to the version that runs, or more work on the draft that runs: always from exactly what runs.
async function startSpace() {
  const v = domain.value.version;
  const baseline = v.release || v.workspace || v.commit;
  starting.value = true;
  try {
    const created = await api.createSpace(slug.value, baseline);
    app.say(`Started the work space ${created.name} from what ${name} runs.`, 'clean');
    router.push(spacePath(created.id));
  } catch (e) {
    app.say(`No work space was started: ${e.message}`, 'needs');
  } finally {
    starting.value = false;
  }
}

onMounted(() => {
  load();
  loadVersions();
});

useBar(() => {
  const d = domain.value;
  if (!d) return {};
  const redeploy = { label: 'Redeploy what runs', quiet: true, run: () => act(() => api.redeploy(name), `Redeployed ${name}.`), disabled: busy.value };
  const putBack = { label: 'Put the last release back', run: () => act(() => api.restoreRelease(name), `${name} is back on its last release.`), disabled: busy.value };
  const secondary = [];
  if (d.version) secondary.push(redeploy);
  if (d.version && !d.version.release) secondary.push(putBack);
  // The one next action is deploying the chosen version; until one is chosen it waits, with a hint.
  return {
    secondary,
    hint: !version.value ? 'Choose a version to deploy it.' : testing.value ? 'Running every test…' : report.value && !report.value.passed ? 'Tests fail at this version, so it cannot be deployed.' : null,
    primary: {
      label: version.value ? `Deploy ${version.value} to ${name}` : 'Deploy a version',
      run: deploy,
      busy: busy.value,
      disabled: !version.value || !report.value?.passed || testing.value
    }
  };
});
</script>

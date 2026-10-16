<template>
  <div class="page-body wide">
    <h1>Releases</h1>
    <p class="lede">A release is stabilised on its own line, opened from main — each line after the one before. Fixes landing in a line are carried into every newer line and main. When a line is ready, tag a commit on it as a version: that tag is what production deploys, and only when every test passes there.</p>

    <p v-if="loadError" class="notice notice-needs mt-7">{{ loadError }}</p>
    <p v-else-if="!lines" class="loading mt-7">Reading the lines…</p>

    <!-- The lines and the network side by side, one surface: both columns start on the same line. -->
    <div v-else class="mt-8 grid grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] items-start gap-10 max-[1200px]:grid-cols-1">
      <div class="grid min-w-0 gap-2">
        <!-- What happens at the picked commit: hairline rows, the commit itself selected like in the graph. -->
        <section v-if="at" class="rows mb-3 grid">
          <p class="grid gap-0.5 bg-select px-2 py-3">
            <span class="flex items-center gap-2">
              <LineBullet v-if="isLine(at.lane)" :line="at.lane" :main-branch="app.info.mainBranch" size="sm" />
              <strong class="min-w-0">“{{ plainMessage(at.message.split('\n')[0], app.info.mainBranch) }}”</strong>
            </span>
            <span class="text-sm text-ink-2">on {{ branchName(at.lane, app.info.mainBranch) }} · {{ at.author }} · <span class="text-xs text-ink-3">{{ whenText(at.when) }}</span> · <router-link :to="`/commit/${at.sha}`" class="mono">{{ at.sha.slice(0, 7) }}</router-link></span>
          </p>
          <p v-if="mode === 'line'" class="grid gap-1 px-2 py-3">
            <span class="flex flex-wrap items-center gap-2"><strong>Open release line</strong> <input v-model="number" class="field inline-block h-7 w-[5.5rem] px-2 font-mono" aria-label="Line number" /> here</span>
            <span class="text-sm text-ink-2">A new branch to stabilise the release on; no version yet. Work for it lands there and is carried back into main. Tag it when it is ready.</span>
          </p>
          <p v-else-if="mode === 'version'" class="grid gap-1 px-2 py-3">
            <span class="flex items-center gap-2"><strong>Tag as</strong> <span class="tag">{{ versionLine.nextVersion }}</span></span>
            <span class="text-sm text-ink-2">Only a tag on release {{ versionLine.branch.slice(8) }} — no new branch. It names this exact commit for deploying to production.</span>
          </p>
          <p v-else-if="!app.author" class="px-2 py-3 text-sm text-ink-2">This instance only deploys: open the version to look at it and run its tests; deploy a released version from its application’s page.</p>
          <p v-else class="px-2 py-3 text-sm text-ink-2">This commit is on a work space, not on a line. Land it first: lines open from main, and versions are tagged on release lines.</p>

          <p v-if="mode === 'version' && blocked" class="px-2 py-3"><span class="state state-quiet">not taggable</span> <span class="text-sm text-ink-2">{{ blocked }}</span></p>
          <div v-else-if="mode === 'version' && versionLine" class="grid gap-2.5 px-2 py-3">
            <label class="label mb-0" for="notes">What is in it (optional)</label>
            <textarea id="notes" v-model="notes" class="field max-w-[60ch]" rows="2" placeholder="for example: Q4 promo discounts, rounding fix for fees"></textarea>
            <TestReport :report="report" :busy="testing" :error="testError" :rev="at.sha.slice(0, 7)" />
          </div>
        </section>
        <p v-else class="pb-3 text-sm text-ink-2">Pick a commit in the graph — or a line by its label, for its newest commit.</p>

        <!-- Carrying a line forward: the same chain as a landing, decided here. -->
        <section v-if="carry" class="grid gap-3 border-y border-needs py-4" aria-live="polite">
          <h2 class="flex items-center gap-2 text-lg"><LineBullet :line="carry.line" :main-branch="app.info.mainBranch" /> Carry release {{ carry.line.slice(8) }} forward</h2>
          <p v-if="!carry.result" class="loading">Combining it with every newer line and main, and running every test…</p>
          <template v-else>
            <p class="text-sm">{{ carryText }}</p>
            <template v-if="carry.result.status === 'TESTS_FAILED'">
              <TestReport v-for="hop in carry.result.hops.filter((h) => h.tests && !h.tests.passed)" :key="hop.into" :report="hop.tests" :rev="`${hop.into === app.info.mainBranch ? 'main' : 'release ' + hop.into.slice(8)} after combining`" />
            </template>
            <Resolver
              v-if="carry.result.status === 'CONFLICTS'"
              :result="carry.result"
              :resolutions="carry.answers"
              :main-branch="app.info.mainBranch"
              :space-name="`release ${carry.line.slice(8)}`"
              @choose="(id, key) => { carry.answers[id] = key; carry.stale = true; }"
            />
          </template>
        </section>

        <section
          v-for="line in shown"
          :key="line.branch"
          class="grid justify-items-start gap-2 rounded-sm border-t border-rule px-2 pt-4 pb-[18px]"
          :class="at?.lane === line.branch ? 'bg-select' : ''"
        >
          <header class="flex flex-wrap items-center gap-x-3.5 gap-y-1.5">
            <LineBullet :line="line.branch" :main-branch="app.info.mainBranch" size="lg" />
            <h2 class="text-xl">{{ line.main ? 'main' : `Release ${line.branch.slice(8)}` }}</h2>
            <span v-if="line.main" class="text-sm text-ink-2">where new work lands; never tagged itself</span>
            <span v-else-if="line.unreleased && line.versions.length" class="state state-live">{{ line.unreleased }} {{ line.unreleased === 1 ? 'change' : 'changes' }} since {{ line.versions[0].name }}</span>
            <span v-else-if="line.unreleased" class="state state-live">{{ line.unreleased }} {{ line.unreleased === 1 ? 'change' : 'changes' }}, never released</span>
            <span v-else class="state state-clean">released as {{ line.versions[0]?.name }}</span>
          </header>
          <ul v-if="line.versions.length && !line.main" class="versions m-0 grid w-full list-none gap-1 p-0 pl-[46px] max-md:pl-0" :aria-label="`Versions of ${line.branch}`">
            <li v-for="v in line.versions.slice(0, 4)" :key="v.name" class="flex flex-wrap items-baseline gap-x-2.5 gap-y-1 text-sm">
              <router-link :to="`/commit/${v.commit}`" class="tag mono">{{ v.name }}</router-link>
              <span v-if="v.verified" class="inline-flex items-center gap-1 self-center font-semibold text-clean-ink" :title="`Every test passed: ${v.verdict?.total} cases`"><Check :size="14" :stroke-width="1.75" aria-hidden="true" /> tests green</span>
              <span v-else class="state state-needs">made by hand, not verified</span>
              <span class="text-ink-2">{{ v.by ? `${v.by}, ` : '' }}<span class="text-xs text-ink-3">{{ whenText(v.when) }}</span><template v-if="v.notes && v.notes !== v.name"> · {{ v.notes }}</template></span>
            </li>
          </ul>
          <p v-if="!line.main" class="pl-[46px] text-sm text-ink-2 max-md:pl-0">A fix landing here is carried into {{ newerOf(line) }} in the same step.</p>
          <!-- A line whose changes stopped short: say where they are missing, and carry them on. -->
          <div v-if="line.missingFrom?.length" class="flex w-full flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-needs pt-2 pl-[46px] max-md:pl-0">
            <span class="state state-needs">not carried forward</span>
            <span class="text-sm">Its newest changes are not in {{ line.missingFrom.map((l) => (l === app.info.mainBranch ? 'main' : `release ${l.slice(8)}`)).join(' or ') }}.</span>
            <button v-if="app.author" type="button" class="btn btn-small" :aria-busy="carry?.line === line.branch && carry.busy ? 'true' : undefined" @click="startCarry(line)">Carry it forward</button>
          </div>
          <button v-if="app.author && (line.main || line.unreleased)" type="button" class="btn btn-small ml-[46px] max-md:ml-0" @click="pickTip(line)">
            {{ line.main ? `Open release ${line.opensLine.slice(8)} from main` : `Tag ${line.nextVersion}` }}
          </button>
        </section>
        <button v-if="hidden" type="button" class="btn btn-quiet justify-self-start" @click="showAll = true">Show {{ hidden }} older {{ hidden === 1 ? 'line' : 'lines' }}</button>
      </div>

      <div ref="graphSide" class="sticky min-w-0 max-[1200px]:static" :style="{ top: `${stickTop}px` }">
        <BranchGraph
          v-if="history"
          :history="history"
          caption="Lines and their versions"
          :pickable="(lines || []).map((l) => l.branch)"
          commit-pickable
          :picked-commit="at?.sha || null"
          :focus="at?.lane || null"
          :ghost="ghost"
          :ghost-text="ghostText"
          :main-branch="app.info.mainBranch"
          @pick="(lane) => pickTip(lines.find((l) => l.branch === lane))"
          @pick-commit="pickCommit"
        />
        <p v-else class="loading">Reading the branches…</p>
      </div>
    </div>

    <!-- Where versions went: each application's deployments, read from its topic. -->
    <p v-if="deploymentsLoading" class="loading mt-14">Reading every application’s deployments…</p>
    <section v-else-if="deployments.length" class="mt-14 grid max-w-[860px] gap-4">
      <h2 class="text-lg">Deployments on {{ app.info.env }}</h2>
      <div v-for="d in deployments" :key="d.name">
        <h3 class="mb-1 text-md"><router-link :to="`/applications/${d.name}`">{{ d.name }}</router-link></h3>
        <p v-if="!d.history?.length" class="text-sm text-ink-2">{{ d.historyError ? `Its topic could not be read: ${d.historyError}` : 'Nothing deployed yet.' }}</p>
        <ol v-else class="rows m-0 list-none p-0">
          <li v-for="(h, i) in d.history.slice(0, 5)" :key="i" class="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-1 py-1.5 text-sm">
            <span class="min-w-28 text-xs text-ink-3">{{ whenText(h.at) }}</span>
            <router-link v-if="h.release" :to="`/commit/${h.commit}`" class="tag mono">{{ h.release }}</router-link>
            <span v-else-if="h.workspace" class="state state-live">Draft {{ branchName(h.workspace, app.info.mainBranch) }}</span>
            <span v-else class="text-ink-2">stopped {{ h.stopped.join(', ') }}</span>
            <span class="text-ink-2">{{ h.by ? `by ${h.by}` : '' }}</span>
          </li>
        </ol>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Check } from 'lucide-vue-next';
import BranchGraph from '../components/BranchGraph.vue';
import LineBullet from '../components/LineBullet.vue';
import TestReport from '../components/TestReport.vue';
import Resolver from '../components/Resolver.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { useStickyFit } from '../lib/stickyFit';
import { app, branchName, plainMessage, whenText } from '../store';

/*
 * Lines, their versions, and how fixes travel between them. Any commit picked in the graph can get a
 * version (its tests run at once; made only when green) or, on main, open a release line.
 */
const router = useRouter();
const KEEP = 4;
const graphSide = ref(null);
const stickTop = useStickyFit(graphSide);
const isLine = (branch) => branch === app.info.mainBranch || /^release\//.test(branch || '');
const lines = ref(null);
const history = ref(null);
const loadError = ref(null);
const showAll = ref(false);
const at = ref(null); // the picked commit, from the history
const mode = ref(null); // 'version' | 'line'
const number = ref('');
const notes = ref('');
const report = ref(null);
const testing = ref(false);
const testError = ref(null);
const busy = ref(false);
// Why the picked commit cannot be tagged at all (already a version, or older than the last one).
const blocked = ref(null);

const shown = computed(() => (showAll.value ? lines.value : (lines.value || []).filter((l, i) => i < KEEP || l.unreleased || l.branch === at.value?.lane)));
const hidden = computed(() => (lines.value || []).length - shown.value.length);
const mainLine = computed(() => (lines.value || []).find((l) => l.main));
// A version is tagged on the release line the commit was picked on; main is never tagged.
const versionLine = computed(() => (at.value && (lines.value || []).find((l) => !l.main && l.branch === at.value.lane)) || null);
const onMain = computed(() => at.value?.lane === app.info.mainBranch);
// Carrying a stalled line forward: { line, result, answers, stale, busy }.
const carry = ref(null);
const carryText = computed(() => {
  const r = carry.value?.result;
  if (!r) return '';
  const where = r.hops.filter((h) => !h.already).map((h) => (h.into === app.info.mainBranch ? 'main' : `release ${h.into.slice(8)}`)).join(', then ');
  if (r.status === 'CLEAN') return `Everything combines and every test passes. Carrying it moves ${where} together.`;
  if (r.status === 'CONFLICTS') return `${r.open} ${r.open === 1 ? 'thing needs' : 'things need'} a decision before it can be carried into ${where}.`;
  if (r.status === 'TESTS_FAILED') return 'It combines cleanly, but tests fail after combining, so it cannot be carried yet. Fix the failing cases on the line, then carry it.';
  if (r.status === 'LANDED') return `Carried into ${where}.`;
  return r.message || r.status;
});
async function runCarry(apply) {
  const c = carry.value;
  c.busy = true;
  try {
    const r = await api.carry(c.line, { resolutions: { ...c.answers }, tips: apply ? c.result?.tips : null, apply });
    c.result = r;
    c.stale = false;
    if (r.status === 'LANDED') {
      app.say(`Carried release ${c.line.slice(8)} forward. Every test passed at every step.`, 'clean');
      carry.value = null;
      await load();
    } else if (r.status === 'NOTHING_TO_LAND') {
      app.say(r.message, 'clean');
      carry.value = null;
      await load();
    }
  } catch (error) {
    app.say(`Not carried: ${error.message}`, 'needs');
  } finally {
    if (carry.value) carry.value.busy = false;
  }
}
function startCarry(line) {
  at.value = null;
  carry.value = reactive({ line: line.branch, result: null, answers: {}, stale: false, busy: false });
  runCarry(false);
}

const newerOf = (line) => {
  const newer = (lines.value || [])
    .filter((l) => !l.main && l.branch.localeCompare(line.branch, undefined, { numeric: true }) > 0)
    .sort((a, b) => a.branch.localeCompare(b.branch, undefined, { numeric: true }));
  return [...newer.map((l) => `release ${l.branch.slice(8)}`), 'main'].join(', then ');
};

const ghost = computed(() => {
  if (!at.value) return [];
  if (mode.value === 'line' && number.value.trim()) return [{ lane: `release/${number.value.trim()}`, from: at.value.lane, at: at.value.sha, open: true }];
  return [];
});
const ghostText = computed(() => {
  if (!ghost.value.length) return '';
  return `Release ${number.value.trim()} opens here`;
});

async function load() {
  try {
    [lines.value, history.value] = await Promise.all([api.lines(), api.history(42).catch(() => null)]);
  } catch (error) {
    loadError.value = `The lines could not be read: ${error.message}`;
  }
}

function pickCommit(c) {
  if (at.value?.sha === c.sha) {
    at.value = null;
    return;
  }
  at.value = c;
  notes.value = '';
  number.value = mainLine.value?.opensLine?.slice(8) || '';
  // A deploy-only instance makes no lines and no versions: a picked commit is only looked at.
  mode.value = !app.author ? null : onMain.value ? 'line' : versionLine.value ? 'version' : null;
}
function pickTip(line) {
  if (!line || !history.value) return;
  const tip = history.value.commits.find((c) => c.sha === line.tip);
  if (tip) pickCommit({ ...tip, lane: line.branch });
}

// The tests at the picked commit, as soon as making a version there is on the table.
watch([at, mode], async () => {
  report.value = null;
  testError.value = null;
  blocked.value = null;
  if (!at.value || mode.value !== 'version' || !versionLine.value) return;
  const tagged = versionLine.value.versions.find((v) => v.commit === at.value.sha);
  if (tagged) {
    blocked.value = `This commit is ${tagged.name} already. Tag a newer commit of release ${versionLine.value.branch.slice(8)} once fixes land there.`;
    return;
  }
  const sha = at.value.sha;
  testing.value = true;
  try {
    const check = await api.release(versionLine.value.branch, null, true, sha);
    if (at.value?.sha === sha) report.value = check.tests;
  } catch (error) {
    if (at.value?.sha !== sha) return;
    if (error.body?.code === 'TESTS_FAILED') report.value = error.body.details;
    else if (['NOT_NEWER', 'NOTHING_NEW', 'NOT_ON_LINE'].includes(error.body?.code)) blocked.value = `It cannot be tagged: ${error.message}.`;
    else testError.value = error.message;
  } finally {
    testing.value = false;
  }
});

async function make() {
  const line = versionLine.value;
  busy.value = true;
  try {
    const made = await api.release(line.branch, notes.value.trim() || null, false, at.value.sha);
    app.say(`Tagged ${made.commit.slice(0, 7)} as ${made.version}. Every test passed; it can be deployed now.`, 'clean');
    at.value = null;
    await load();
  } catch (error) {
    if (error.body?.code === 'TESTS_FAILED') report.value = error.body.details;
    app.say(`Not tagged: ${error.message}`, 'needs');
    if (error.body?.code === 'VERSION_TAKEN') await load();
  } finally {
    busy.value = false;
  }
}

async function openLine() {
  busy.value = true;
  try {
    const opened = await api.openLine(at.value.sha, number.value.trim() || null);
    app.say(`Opened release ${opened.line.slice(8)} at ${opened.commit.slice(0, 7)}. Its first version will be ${opened.nextVersion}.`, 'clean');
    at.value = null;
    await load();
  } catch (error) {
    app.say(`No line was opened: ${error.message}`, 'needs');
  } finally {
    busy.value = false;
  }
}

const deployments = ref([]);
const deploymentsLoading = ref(true);
async function loadDeployments() {
  deploymentsLoading.value = true;
  try {
    const { applications } = await api.applications();
    deployments.value = await Promise.all(applications.map((d) => api.application(d.name).catch(() => ({ name: d.name, history: [] }))));
  } catch {
    deployments.value = [];
  } finally {
    deploymentsLoading.value = false;
  }
}

onMounted(() => {
  load();
  loadDeployments();
});

useBar(() => {
  // Deploy only: the one thing to do with a picked commit here is open it.
  if (!app.author) {
    return at.value
      ? { secondary: [], primary: { label: 'Open this version', run: () => router.push(`/commit/${at.value.sha}`) } }
      : { secondary: [], hint: 'This instance only deploys. Pick a commit to look at it; deploy a released version from its application’s page.' };
  }
  if (carry.value) {
    const c = carry.value;
    const secondary = [{ label: 'Cancel', quiet: true, run: () => { carry.value = null; } }];
    if (c.result?.status === 'CLEAN' && !c.stale) return { secondary, primary: { label: `Carry release ${c.line.slice(8)} forward`, run: () => runCarry(true), busy: c.busy } };
    return { secondary, primary: { label: 'Check again', run: () => runCarry(false), busy: c.busy, disabled: !c.result } };
  }
  if (!at.value) {
    // Nothing picked: the most useful next step is tagging the newest line that has changes waiting, or else opening the next line from main.
    const waiting = (lines.value || []).filter((l) => !l.main && l.unreleased).sort((a, b) => b.branch.localeCompare(a.branch, undefined, { numeric: true }))[0];
    const next = waiting || mainLine.value;
    if (!next) return { secondary: [], hint: 'Pick a commit in the graph. Versions are deployed from each application’s page.' };
    return {
      secondary: [],
      hint: !app.author ? 'Only an author can tag versions or open lines. Versions are deployed from each application’s page.' : 'Or pick any commit in the graph. Versions are deployed from each application’s page.',
      primary: { label: waiting ? `Tag ${waiting.nextVersion}` : `Open release ${next.opensLine?.slice(8) || '…'} from main`, run: () => pickTip(next), disabled: !history.value || !app.author }
    };
  }
  if (mode.value === 'line') {
    return { secondary: [], primary: { label: `Open release ${number.value.trim() || '…'} here`, run: openLine, busy: busy.value, disabled: !number.value.trim() || !app.author } };
  }
  if (mode.value === 'version' && blocked.value) return { secondary: [] };
  if (mode.value === 'version' && versionLine.value) {
    return {
      secondary: [],
      hint: testing.value ? 'Running every domain’s tests at this commit…' : report.value && !report.value.passed ? 'Tests fail here, so it cannot be tagged.' : null,
      primary: { label: `Tag as ${versionLine.value.nextVersion}`, run: make, busy: busy.value, disabled: !report.value?.passed || testing.value || !app.author }
    };
  }
  return { secondary: [] };
});
</script>

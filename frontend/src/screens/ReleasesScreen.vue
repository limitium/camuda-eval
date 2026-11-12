<template>
  <div class="page-body wide">
    <h1>Releases</h1>
    <p class="lede">A release is stabilised on its own release branch, created from main or from another release branch and numbered as a version — release/1.1, release/1.0.1, release/2.0. A fix merged into a release branch is merged into every newer release branch and main. When a release branch is ready, tag a commit on it, like v1.1.0: that tag is what production deploys, and only when every test passes there.</p>

    <p v-if="loadError" class="notice notice-needs mt-7">{{ loadError }}</p>
    <p v-else-if="!lines" class="loading mt-7">Reading the branches…</p>

    <!-- The branches and the graph side by side, one surface: both columns start on the same line. -->
    <div v-else class="mt-8 grid grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] items-start gap-10 @max-[1200px]:grid-cols-1">
      <div class="grid min-w-0 gap-2">
        <!-- Merging a release branch into newer branches: the same chain as a merge, decided here. -->
        <section v-if="carry" class="grid gap-3 border-y border-needs py-4" aria-live="polite">
          <h2 class="flex items-center gap-2 text-lg"><LineBullet :line="carry.line" :main-branch="app.info.mainBranch" /> Merge {{ carry.line }} into newer branches</h2>
          <p v-if="!carry.result" class="loading">Merging it into every newer release branch and main, and running every test…</p>
          <template v-else>
            <p class="text-sm">{{ carryText }}</p>
            <template v-if="carry.result.status === 'TESTS_FAILED'">
              <TestReport v-for="hop in carry.result.hops.filter((h) => h.tests && !h.tests.passed)" :key="hop.into" :report="hop.tests" :rev="`${hop.into} after the merge`" />
            </template>
            <Resolver
              v-if="carry.result.status === 'CONFLICTS'"
              :result="carry.result"
              :resolutions="carry.answers"
              :main-branch="app.info.mainBranch"
              :space-name="carry.line"
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
            <h2 class="text-xl">{{ line.branch }}</h2>
            <span v-if="line.main" class="text-sm text-ink-2">where new work is merged; never tagged itself<template v-if="line.opensAfter"> · a release branch created from it starts after {{ line.opensAfter.slice(0, 7) }}, where {{ line.opensAfterLine }} started</template></span>
            <span v-else-if="line.unreleased && line.versions.length" class="state state-live">{{ line.unreleased }} {{ line.unreleased === 1 ? 'change' : 'changes' }} since {{ line.versions[0].name }}</span>
            <span v-else-if="line.unreleased" class="state state-live">{{ line.unreleased }} {{ line.unreleased === 1 ? 'change' : 'changes' }}, no tags yet</span>
            <span v-else class="state state-clean">tagged {{ line.versions[0]?.name }}</span>
          </header>
          <ul v-if="line.versions.length && !line.main" class="versions m-0 grid w-full list-none gap-1 p-0 pl-[46px] max-md:pl-0" :aria-label="`Tags on ${line.branch}`">
            <li v-for="v in line.versions.slice(0, 4)" :key="v.name" class="flex flex-wrap items-baseline gap-x-2.5 gap-y-1 text-sm">
              <router-link :to="`/commit/${v.commit}`" class="tag mono">{{ v.name }}</router-link>
              <span v-if="v.verified" class="inline-flex items-center gap-1 self-center font-semibold text-clean-ink" :title="`Every test passed: ${v.verdict?.total} cases`"><Check :size="14" :stroke-width="1.75" aria-hidden="true" /> tests green</span>
              <span v-else class="state state-needs">made by hand, not verified</span>
              <span class="text-ink-2">{{ v.by ? `${v.by}, ` : '' }}<span class="text-xs text-ink-3">{{ whenText(v.when) }}</span><template v-if="v.notes && v.notes !== v.name"> · {{ v.notes }}</template></span>
            </li>
          </ul>
          <p v-if="!line.main" class="pl-[46px] text-sm text-ink-2 max-md:pl-0">A fix merged here is merged into {{ newerOf(line) }} in the same step.</p>
          <!-- A release branch whose changes stopped short: say where they are missing, and merge them on. -->
          <div v-if="line.missingFrom?.length" class="flex w-full flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-needs pt-2 pl-[46px] max-md:pl-0">
            <span class="state state-needs">not merged into newer branches</span>
            <span class="text-sm">Its newest commits are not in {{ line.missingFrom.join(' or ') }}.</span>
            <button v-if="app.author" type="button" class="btn btn-small" :aria-busy="carry?.line === line.branch && carry.busy ? 'true' : undefined" @click="startCarry(line)">Merge into newer branches</button>
          </div>
        </section>
        <button v-if="hidden" type="button" class="btn btn-quiet justify-self-start" @click="showAll = true">Show {{ hidden }} older {{ hidden === 1 ? 'branch' : 'branches' }}</button>
      </div>

      <div ref="graphSide" class="sticky min-w-0 @max-[1200px]:static" :style="{ top: `${stickTop}px` }">
        <!-- What happens at the picked commit, above the graph it was picked in: hairline rows, the commit selected as there. -->
        <section v-if="at" class="rows mb-4 grid">
          <p class="grid gap-0.5 bg-select px-2 py-3">
            <span class="flex items-center gap-2">
              <LineBullet v-if="isLine(at.lane)" :line="at.lane" :main-branch="app.info.mainBranch" size="sm" />
              <strong class="min-w-0">“{{ plainMessage(at.message.split('\n')[0], app.info.mainBranch) }}”</strong>
            </span>
            <span class="text-sm text-ink-2">on {{ branchName(at.lane, app.info.mainBranch) }} · {{ at.author }} · <span class="text-xs text-ink-3">{{ whenText(at.when) }}</span> · <router-link :to="`/commit/${at.sha}`" class="mono">{{ at.sha.slice(0, 7) }}</router-link></span>
          </p>
          <!-- What can be made at this commit, as tabs: on a release branch a tag or a release branch, on main a release branch. -->
          <div v-if="app.author && (versionLine || onMain)" class="flex items-end gap-0.5 overflow-x-auto px-2 pt-2" role="tablist" aria-label="What to make at this commit">
            <button v-if="versionLine" type="button" role="tab" :aria-selected="mode === 'version'" :class="tabClass(mode === 'version')" @click="mode = 'version'">Tag</button>
            <button type="button" role="tab" :aria-selected="mode === 'line'" :class="tabClass(mode === 'line')" @click="mode = 'line'">Release branch</button>
          </div>
          <p v-if="mode === 'line' && lineBlocked" class="px-2 py-3"><span class="state state-quiet">no new branch here</span> <span class="text-sm text-ink-2">{{ lineBlocked }}</span></p>
          <div v-else-if="mode === 'line'" class="grid gap-1.5 px-2 py-3">
            <label class="flex flex-wrap items-center gap-2" for="branch-number"><strong>Create branch</strong>
              <span class="inline-flex items-center gap-0.5 font-mono">release/<input id="branch-number" v-model="number" class="field inline-block h-7 w-[6.5rem] px-2 font-mono" :class="branchProblem ? '!border-needs' : ''" spellcheck="false" autocomplete="off" :aria-invalid="!!branchProblem" aria-describedby="branch-help" @input="actionError = null" /></span> here</label>
            <span v-if="branchProblem || actionError" class="text-sm font-semibold text-needs-ink" role="alert">{{ branchProblem || actionError }}</span>
            <span id="branch-help" class="text-sm text-ink-2">A version number, like {{ branchExamples }}. It stabilises the release; work for it is merged there, and from there into every newer release branch and main. Tag it when it is ready.</span>
          </div>
          <div v-else-if="mode === 'version'" class="grid gap-1.5 px-2 py-3">
            <label class="flex flex-wrap items-center gap-2" for="tag-number"><strong>Tag as</strong>
              <span class="inline-flex items-center gap-0.5 font-mono">v<input id="tag-number" v-model="tagNumber" class="field inline-block h-7 w-[6.5rem] px-2 font-mono" :class="tagProblem ? '!border-needs' : ''" spellcheck="false" autocomplete="off" :aria-invalid="!!tagProblem" aria-describedby="tag-help" @input="actionError = null" /></span></label>
            <span v-if="tagProblem || actionError" class="text-sm font-semibold text-needs-ink" role="alert">{{ tagProblem || actionError }}</span>
            <span id="tag-help" class="text-sm text-ink-2">A version, X.Y.Z — on {{ versionLine.branch }}, from v{{ branchNumbers(versionLine.branch).join('.') }} up to before v{{ tagLimit }}{{ versionLine.versions.length ? `, after ${versionLine.versions[0].name}` : '' }}. Only a tag, no new branch: it names this exact commit for deploying to production.</span>
          </div>
          <p v-else-if="!app.author" class="px-2 py-3 text-sm text-ink-2">This instance only deploys: open the commit to look at it and run its tests; deploy a tag from its application’s page.</p>
          <p v-else class="px-2 py-3 text-sm text-ink-2">This commit is on a work space, not on main or a release branch. Merge it first: release branches are created from main or a release branch, and tags are made on release branches.</p>

          <p v-if="mode === 'version' && blocked" class="px-2 py-3"><span class="state state-quiet">not taggable</span> <span class="text-sm text-ink-2">{{ blocked }}</span></p>
          <div v-else-if="mode === 'version' && versionLine" class="grid gap-2.5 px-2 py-3">
            <label class="label mb-0" for="notes">What is in it (optional)</label>
            <textarea id="notes" v-model="notes" class="field max-w-[60ch]" rows="2" placeholder="for example: Q4 promo discounts, rounding fix for fees"></textarea>
            <TestReport :report="report" :busy="testing" :error="testError" :rev="at.sha.slice(0, 7)" />
          </div>
        </section>
        <p v-else class="mb-4 text-sm text-ink-2">Pick a commit in the graph — or a branch by its label, for its newest commit — to tag it or create a release branch from it.</p>

        <BranchGraph
          v-if="history"
          :history="history"
          caption="Branches and their tags"
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

    <!-- Where tags went: each application's deployments, read from its topic. -->
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
import { testsText, useAssistant } from '../lib/assistant';
import { useStickyFit } from '../lib/stickyFit';
import { app, branchName, plainMessage, whenText } from '../store';

/*
 * main and the release branches, their tags, and how fixes are merged between them. Any commit picked
 * in the graph can be tagged (its tests run at once; tagged only when green) or, on main, get a new
 * release branch.
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
const tagNumber = ref('');
const notes = ref('');
// What the server refused, said where it was typed.
const actionError = ref(null);
const report = ref(null);
const testing = ref(false);
const testError = ref(null);
const busy = ref(false);
// Why the picked commit cannot be tagged at all (already tagged, or older than the last tag).
const blocked = ref(null);

const shown = computed(() => (showAll.value ? lines.value : (lines.value || []).filter((l, i) => i < KEEP || l.unreleased || l.branch === at.value?.lane)));
const hidden = computed(() => (lines.value || []).length - shown.value.length);
const mainLine = computed(() => (lines.value || []).find((l) => l.main));
// A tag is made on the release branch the commit was picked on; main is never tagged.
const versionLine = computed(() => (at.value && (lines.value || []).find((l) => !l.main && l.branch === at.value.lane)) || null);
const onMain = computed(() => at.value?.lane === app.info.mainBranch);

// --- names follow version order (the server checks the same, and also that a branch starts after the one below it) ---
const VERSION = /^(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})(?:\.(0|[1-9]\d{0,5}))?$/;
const numbersOf = (text, parts) => {
  const m = String(text || '').trim().match(VERSION);
  if (!m || (parts === 3 && m[3] === undefined)) return null;
  return [Number(m[1]), Number(m[2]), Number(m[3] || 0)];
};
const branchNumbers = (branch) => numbersOf(String(branch || '').replace(/^release\//, ''), 2);
const compare = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];
const ownNumber = (branch) => String(branch || '').replace(/^release\//, '');
// Every tag, and every release branch there is or was (a deleted one still has its tags).
const allTags = computed(() => [...new Map((lines.value || []).flatMap((l) => l.versions).map((v) => [v.name, v])).values()]);
const knownBranches = computed(() => [...new Set([
  ...(lines.value || []).filter((l) => !l.main).map((l) => l.branch),
  ...allTags.value.map((v) => v.branch).filter(Boolean)
])]);
const fromRelease = computed(() => (versionLine.value ? versionLine.value.branch : null));
const branchExamples = computed(() => {
  const n = fromRelease.value ? branchNumbers(fromRelease.value) : null;
  return n ? `${n[0]}.${n[1] + 1}, ${n[0]}.${n[1]}.${n[2] + 1} or ${n[0] + 1}.0` : '1.2 or 1.2.3';
});
const branchProblem = computed(() => {
  if (mode.value !== 'line') return null;
  const typed = number.value.trim();
  const n = numbersOf(typed, 2);
  if (!n) return typed ? `“${typed}” is not a version: write it like 1.2 or 1.2.3.` : 'Give it a version number.';
  const same = knownBranches.value.find((b) => compare(branchNumbers(b), n) === 0);
  if (same) return (lines.value || []).some((l) => l.branch === same) ? `${same} exists already.` : `${same} was tagged before and deleted; start a fix from one of its tags instead.`;
  if (fromRelease.value && compare(n, branchNumbers(fromRelease.value)) <= 0) return `A release branch created from ${fromRelease.value} is numbered after ${ownNumber(fromRelease.value)}.`;
  if (!fromRelease.value) {
    const newest = knownBranches.value.map(branchNumbers).sort(compare).pop();
    if (newest && compare(n, newest) <= 0) return `A release branch created from ${app.info.mainBranch} is numbered after ${newest.join('.').replace(/\.0$/, '')}, the newest release branch.`;
  }
  return null;
});
const tagLimit = computed(() => {
  const line = versionLine.value;
  if (!line) return '';
  const own = branchNumbers(line.branch);
  const nextMinor = [own[0], own[1] + 1, 0];
  const newer = line.newerBranch ? branchNumbers(line.newerBranch) : null;
  return (newer && compare(newer, nextMinor) < 0 ? newer : nextMinor).join('.');
});
const tagProblem = computed(() => {
  if (mode.value !== 'version' || !versionLine.value) return null;
  const typed = tagNumber.value.trim().replace(/^v/, '');
  const n = numbersOf(typed, 3);
  if (!n) return typed ? `“${typed}” is not a version: write it like 1.0.2.` : 'Give it a version.';
  const name = `v${n.join('.')}`;
  const line = versionLine.value;
  if (allTags.value.some((v) => v.name === name)) return `${name} exists already.`;
  const own = branchNumbers(line.branch);
  if (compare(n, own) < 0) return `Tags on ${line.branch} start at v${own.join('.')}.`;
  // Below the next newer release branch, and within the branch's own minor: release/1.0 holds 1.0.x.
  const nextMinor = [own[0], own[1] + 1, 0];
  const newerFirst = line.newerBranch && compare(branchNumbers(line.newerBranch), nextMinor) < 0;
  const limit = newerFirst ? branchNumbers(line.newerBranch) : nextMinor;
  if (compare(n, limit) >= 0) return `Tags on ${line.branch} are numbered below v${limit.join('.')}${newerFirst ? `, where ${line.newerBranch} starts` : '; a newer minor needs its own release branch'}.`;
  const last = line.versions[0] ? numbersOf(line.versions[0].name.slice(1), 3) : null;
  if (last && compare(n, last) <= 0) return `It must come after ${line.versions[0].name}, the newest tag on ${line.branch}.`;
  return null;
});
// main's own commits, newest first: its first parents from the tip, as far as the history reaches.
const mainChain = computed(() => {
  const bySha = new Map((history.value?.commits || []).map((c) => [c.sha, c]));
  const chain = [];
  for (let c = bySha.get(mainLine.value?.tip); c && chain.length < bySha.size; c = bySha.get(c.parents?.[0])) chain.push(c.sha);
  return chain;
});
// Why the next release branch cannot start at the picked commit — each starts after the one before — or null.
const lineBlocked = computed(() => {
  const main = mainLine.value;
  const i = at.value ? mainChain.value.indexOf(at.value.sha) : -1;
  if (i < 0 || !main?.opensAfter) return null;
  const fork = mainChain.value.indexOf(main.opensAfter);
  if (fork < 0 || i < fork) return null;
  const next = main.opensLine;
  const before = main.opensAfterLine;
  return i === fork
    ? `${next} cannot start from this commit: ${before} started here, and each release branch starts after the one before. Pick a newer commit of ${before} or of ${main.branch}.`
    : `${next} cannot start from this commit: it is older than where ${before} started (${main.opensAfter.slice(0, 7)}). Pick a newer commit of ${before} or of ${main.branch}.`;
});
// Merging a stalled release branch into newer branches: { line, result, answers, stale, busy }.
const carry = ref(null);
const mergedInto = (r) => r.hops.filter((h) => !h.already).map((h) => h.into).join(', then ');
const carryText = computed(() => {
  const r = carry.value?.result;
  if (!r) return '';
  const where = mergedInto(r);
  if (r.status === 'CLEAN') return `No conflicts, and every test passes. Merging moves ${where} together.`;
  if (r.status === 'CONFLICTS') return `${r.open} ${r.open === 1 ? 'conflict needs' : 'conflicts need'} a decision before it can be merged into ${where}.`;
  if (r.status === 'TESTS_FAILED') return 'No conflicts, but tests fail after the merge, so nothing is merged yet. Fix the failing cases on the release branch, then merge again.';
  if (r.status === 'LANDED') return `Merged into ${where}.`;
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
      app.say(`Merged ${c.line} into ${mergedInto(r)}. Every test passed at every step.`, 'clean');
      carry.value = null;
      await load();
    } else if (r.status === 'NOTHING_TO_LAND') {
      app.say(r.message, 'clean');
      carry.value = null;
      await load();
    }
  } catch (error) {
    app.say(`Not merged: ${error.message}`, 'needs');
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
  return [...newer.map((l) => l.branch), app.info.mainBranch].join(', then ');
};

const ghost = computed(() => {
  if (!at.value) return [];
  if (mode.value === 'line' && !lineBlocked.value && !branchProblem.value) return [{ lane: `release/${number.value.trim()}`, from: at.value.lane, at: at.value.sha, open: true }];
  return [];
});
const ghostText = computed(() => {
  if (!ghost.value.length) return '';
  return `release/${number.value.trim()} starts here`;
});

async function load() {
  try {
    [lines.value, history.value] = await Promise.all([api.lines(), api.history(42).catch(() => null)]);
  } catch (error) {
    loadError.value = `The branches could not be read: ${error.message}`;
  }
}

function pickCommit(c) {
  if (at.value?.sha === c.sha) {
    at.value = null;
    return;
  }
  at.value = c;
  notes.value = '';
  actionError.value = null;
  // Suggested names, free to change: the next release branch from where it was picked, the next tag on it.
  number.value = (versionLine.value ? versionLine.value.nextBranch : mainLine.value?.opensLine)?.replace(/^release\//, '') || '';
  tagNumber.value = versionLine.value?.nextVersion?.replace(/^v/, '') || '';
  // A deploy-only instance makes no branches and no tags: a picked commit is only looked at.
  mode.value = !app.author ? null : onMain.value ? 'line' : versionLine.value ? 'version' : null;
}
function pickTip(line) {
  if (!line || !history.value) return;
  const tip = history.value.commits.find((c) => c.sha === line.tip);
  if (tip) pickCommit({ ...tip, lane: line.branch });
}

// The tests at the picked commit, as soon as tagging it is on the table.
watch([at, mode], async () => {
  report.value = null;
  testError.value = null;
  blocked.value = null;
  if (!at.value || mode.value !== 'version' || !versionLine.value) return;
  const tagged = versionLine.value.versions.find((v) => v.commit === at.value.sha);
  if (tagged) {
    blocked.value = `This commit is tagged ${tagged.name} already. Tag a newer commit of ${versionLine.value.branch} once fixes are merged there.`;
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
    const made = await api.release(line.branch, notes.value.trim() || null, false, at.value.sha, `v${tagNumber.value.trim().replace(/^v/, '')}`);
    app.say(`Tagged ${made.commit.slice(0, 7)} as ${made.version}. Every test passed; it can be deployed now.`, 'clean');
    at.value = null;
    await load();
  } catch (error) {
    if (error.body?.code === 'TESTS_FAILED') report.value = error.body.details;
    else actionError.value = `Not tagged: ${error.message}.`;
    app.say(`Not tagged: ${error.message}`, 'needs');
    if (error.body?.code === 'VERSION_TAKEN') await load();
  } finally {
    busy.value = false;
  }
}

async function openLine() {
  busy.value = true;
  try {
    const opened = await api.openLine(at.value.sha, number.value.trim() || null, at.value.lane);
    app.say(`Created ${opened.line} at ${opened.commit.slice(0, 7)}. Its first tag will be ${opened.nextVersion}.`, 'clean');
    at.value = null;
    await load();
  } catch (error) {
    actionError.value = `Not created: ${error.message}.`;
    app.say(`No branch was created: ${error.message}`, 'needs');
  } finally {
    busy.value = false;
  }
}

const tabClass = (active) => [
  'tab -mb-px inline-flex h-[38px] items-center gap-1.5 border-b-2 px-3.5 whitespace-nowrap',
  active ? 'active border-ink font-bold text-ink' : 'border-transparent font-medium text-ink-2 hover:text-ink'
];

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

useAssistant(() => ({
  now: !at.value
    ? `Nothing is picked in the graph yet: tags and release branches are made at a picked commit — click a commit, or a branch’s label for its newest commit.${(lines.value || [])
      .filter((l) => !l.main && l.unreleased)
      .map((l) => ` ${l.branch} has ${l.unreleased} ${l.unreleased === 1 ? 'commit' : 'commits'} not tagged yet: tagging its newest commit${l.nextVersion ? ` as ${l.nextVersion}` : ''} releases ${l.unreleased === 1 ? 'it' : 'them'}.`).join('')}`
    : mode.value === 'version'
      ? (blocked.value ? `The picked commit cannot be tagged: ${blocked.value}` : `The picked commit ${at.value.sha.slice(0, 7)} on ${at.value.lane} is open on the Tag tab, to tag as v${tagNumber.value.trim().replace(/^v/, '')}.`)
      : mode.value === 'line'
        ? (lineBlocked.value ? `No release branch can be created at the picked commit: ${lineBlocked.value}` : `The picked commit ${at.value.sha.slice(0, 7)} on ${at.value.lane} is open on the Release branch tab, to create release/${number.value.trim()}.`)
        : 'The picked commit is on a work space, not on main or a release branch: merge it first.',
  branches: !lines.value ? (loadError.value || 'still being read') : lines.value.map((l) => `${l.branch}: ${l.main
    ? `main — where new work is merged; never tagged${l.opensLine ? `; a release branch created from it would be ${l.opensLine}` : ''}`
    : `${l.versions.length ? `tags ${l.versions.slice(0, 4).map((v) => `${v.name}${v.verified ? '' : ' (made by hand, not verified)'}`).join(', ')}` : 'no tags yet'}${l.unreleased ? `; ${l.unreleased} ${l.unreleased === 1 ? 'commit' : 'commits'} ${l.versions.length ? 'since its newest tag' : 'not tagged yet'}` : ''}${l.nextVersion ? `; its next tag would be ${l.nextVersion}` : ''}${l.nextBranch ? `; a release branch created from it would be ${l.nextBranch}` : ''}`}${l.missingFrom?.length ? `; its newest commits are NOT merged into ${l.missingFrom.join(', ')}` : ''}`),
  pickedCommit: at.value
    ? `${at.value.sha.slice(0, 7)} on ${at.value.lane} by ${at.value.author} ${whenText(at.value.when)}: “${plainMessage(at.value.message.split('\n')[0])}”`
    : 'none — nothing is picked in the graph yet',
  tabsAtThePickedCommit: !at.value ? null : versionLine.value ? 'Tag, Release branch' : onMain.value ? 'Release branch' : 'none (a work space’s commit: merge it first)',
  tabOpen: mode.value === 'version' ? 'Tag' : mode.value === 'line' ? 'Release branch' : null,
  tagAs: mode.value === 'version' ? `v${tagNumber.value.trim().replace(/^v/, '')}${tagProblem.value ? ` — refused: ${tagProblem.value}` : ''}` : null,
  whyItCannotBeTagged: mode.value === 'version' ? blocked.value : null,
  testsAtThePickedCommit: mode.value === 'version' ? testsText(report.value, testing.value, testError.value) : null,
  createBranch: mode.value === 'line' ? (lineBlocked.value ? `not possible here: ${lineBlocked.value}` : `release/${number.value.trim()}${branchProblem.value ? ` — refused: ${branchProblem.value}` : ''}`) : null,
  numbersABranchFromHereCouldHave: mode.value === 'line' ? branchExamples.value : null,
  deployedOnThisEnvironment: deployments.value.map((d) => `${d.name}: ${(d.history || []).slice(0, 2).map((h) => (h.release ? `tag ${h.release}` : h.workspace ? `draft of ${h.workspace}` : 'stopped')).join(', then before it ') || 'nothing yet'}`),
  // What the actions are checked against: each branch's newest commit, and the names it would get.
  branchTips: Object.fromEntries((lines.value || []).map((l) => [l.branch, {
    tip: l.tip,
    tipMessage: plainMessage((l.tipMessage || '').split('\n')[0]),
    nextVersion: l.main ? null : l.nextVersion || null,
    nextBranch: (l.main ? l.opensLine : l.nextBranch) || null,
    tags: l.versions.map((v) => v.name)
  }]))
}), {
  async tag(p) {
    const line = pickNewest(p.branch, p.commit);
    mode.value = 'version';
    tagNumber.value = p.version.replace(/^v/, '');
    notes.value = p.notes || '';
    await make();
    if (at.value) throw new Error(actionError.value || 'it was not tagged — the bar below says why');
    return `${line.branch} is tagged ${p.version}`;
  },
  async create_branch(p) {
    pickNewest(p.from, p.commit);
    mode.value = 'line';
    number.value = p.number;
    await openLine();
    if (at.value) throw new Error(actionError.value || 'no branch was created — the bar below says why');
    return `release/${p.number} is created`;
  }
});

// A branch's newest commit, picked as a click on its label picks it — if it is still the one proposed.
function pickNewest(branch, commit) {
  const line = (lines.value || []).find((l) => l.branch === branch);
  if (!line) throw new Error(`there is no ${branch} any more`);
  if (line.tip !== commit) throw new Error(`${branch} has a newer commit since this was proposed: nothing was done. Ask again.`);
  if (at.value?.sha !== line.tip) pickTip(line);
  if (at.value?.sha !== line.tip) throw new Error(`the newest commit of ${branch} is not in the graph`);
  return line;
}

useBar(() => {
  // Deploy only: the one thing to do with a picked commit here is open it.
  if (!app.author) {
    return at.value
      ? { secondary: [], primary: { label: 'Open this commit', run: () => router.push(`/commit/${at.value.sha}`) } }
      : { secondary: [], hint: 'This instance only deploys. Pick a commit to look at it; deploy a tag from its application’s page.' };
  }
  if (carry.value) {
    const c = carry.value;
    const secondary = [{ label: 'Cancel', quiet: true, run: () => { carry.value = null; } }];
    if (c.result?.status === 'CLEAN' && !c.stale) return { secondary, primary: { label: `Merge ${c.line} into newer branches`, run: () => runCarry(true), busy: c.busy } };
    return { secondary, primary: { label: 'Check again', run: () => runCarry(false), busy: c.busy, disabled: !c.result } };
  }
  // Release branches are created and tags made only from a commit picked in the graph.
  if (!at.value) return { secondary: [], hint: 'Pick a commit in the graph: on a release branch, to tag it or create a release branch from it; on main, to create a release branch.' };
  if (mode.value === 'line' && lineBlocked.value) return { secondary: [] };
  if (mode.value === 'line') {
    return { secondary: [], primary: { label: `Create branch release/${number.value.trim() || '…'} here`, run: openLine, busy: busy.value, disabled: !!branchProblem.value || !app.author } };
  }
  if (mode.value === 'version' && blocked.value) return { secondary: [] };
  if (mode.value === 'version' && versionLine.value) {
    return {
      secondary: [],
      hint: testing.value ? 'Running every domain’s tests at this commit…' : report.value && !report.value.passed ? 'Tests fail here, so it cannot be tagged.' : null,
      primary: { label: `Tag as v${tagNumber.value.trim().replace(/^v/, '') || '…'}`, run: make, busy: busy.value, disabled: !report.value?.passed || testing.value || !!tagProblem.value || !app.author }
    };
  }
  return { secondary: [] };
});
</script>

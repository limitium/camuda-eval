<template>
  <div class="page-body wide">
    <h1>Land it</h1>
    <p class="lede">Landing brings your saved version into {{ lineName(target, app.info.mainBranch) }} and carries it forward, so no newer release can be missing it. Every step is combined and tested first; it happens all at once or not at all.</p>

    <p v-if="space.changes.length" class="notice notice-needs mt-7">There are changes that are not in a saved version. Only saved versions land — <router-link :to="spacePath(ws, '/version')" class="font-semibold">save one first</router-link>.</p>
    <p v-else-if="error" class="notice notice-needs mt-7">{{ error }}</p>
    <p v-else-if="!result" class="loading mt-7">Combining your version with every line it lands in, and running every test…</p>

    <template v-else>
      <!-- What happens and the network side by side: both columns start on the same line. -->
      <div class="mt-8 grid items-start gap-10" :class="result.hops.length ? 'grid-cols-[minmax(0,1fr)_minmax(0,1fr)] max-[1200px]:grid-cols-1' : ''">
      <div class="min-w-0">
      <!-- The ride as a strip map: your work space, then each line it lands in or is carried into as a
           station, joined by the route it is carried along, with the state of that step under it. -->
      <ol v-if="result.hops.length" class="chain m-0 flex list-none flex-wrap items-start gap-y-4 p-0 max-md:flex-col" aria-label="Where it lands, in order">
        <li class="grid flex-none content-start gap-1">
          <span class="flex h-8 items-center gap-2">
            <span class="flex w-8 flex-none justify-center" aria-hidden="true">
              <svg width="14" height="14" viewBox="0 0 14 14"><circle cx="7" cy="7" r="5" fill="var(--plane)" :stroke="routeColour(target, app.info.mainBranch)" stroke-width="2.5" /></svg>
            </span>
            <span class="font-semibold whitespace-nowrap">{{ space.info?.name || 'Your work space' }}</span>
          </span>
          <span class="pl-10 text-xs text-ink-3">your saved version</span>
        </li>
        <li
          v-for="(hop, i) in result.hops"
          :key="hop.into"
          class="flex min-w-0 items-start max-md:flex-col"
          :class="stepClass(hop)"
        >
          <!-- the route into this station, in the colour of the line it is carried into -->
          <span
            class="mx-3 mt-[14px] h-1 w-16 flex-none rounded-full max-md:mx-0 max-md:my-1 max-md:ml-[14px] max-md:h-6 max-md:w-1"
            :class="hop.provisional ? 'opacity-40' : ''"
            :style="{ background: routeColour(hop.into, app.info.mainBranch) }"
            aria-hidden="true"
          ></span>
          <span class="grid min-w-0 content-start gap-1">
            <span class="flex h-8 items-center gap-2">
              <span class="inline-flex flex-none rounded-full" :class="hop.already ? 'opacity-50' : ''">
                <LineBullet :line="hop.into" :main-branch="app.info.mainBranch" size="lg" />
              </span>
              <span class="font-bold whitespace-nowrap" :class="hop.already ? 'text-ink-3' : 'text-ink'">{{ lineName(hop.into, app.info.mainBranch) }}</span>
            </span>
            <span class="state pl-10" :class="STATE[stepClass(hop)]">{{ stepWord(hop, i) }}</span>
            <span v-if="stepDetail(hop, i)" class="pl-10 text-xs text-ink-3">{{ stepDetail(hop, i) }}</span>
          </span>
        </li>
      </ol>

      <p v-if="result.status === 'NOTHING_TO_LAND'" class="notice mt-7 first:mt-0">{{ result.message }}.</p>
      <template v-else-if="result.status === 'LANDED'">
        <p class="notice notice-clean mt-7">Landed in {{ landedLines }}. Every test passed at every step.</p>
        <div v-if="!removed" class="mt-3.5 max-w-[70ch] border-y border-rule py-3.5">
          <p><strong class="font-bold">Remove the work space {{ space.info?.name }}?</strong> Everything in it is in {{ landedLines }} now. Keep it only to go on working in it.</p>
        </div>
        <p v-else class="meta mt-3.5">The work space is removed. <router-link to="/releases" class="font-semibold text-ink">Make a release</router-link> when it should ship.</p>
      </template>
      <p v-else-if="result.status === 'MOVED'" class="notice notice-needs mt-7">{{ result.message }}</p>
      <p v-else-if="result.status === 'CLEAN'" class="notice notice-clean mt-7">Nothing disagrees and every test passes{{ steps > 1 ? ' at every step' : '' }}. Landing moves {{ landedLines }}{{ steps > 1 ? ' together' : '' }}.</p>

      <!-- Failing: one notice — the problem in a sentence, the failing rows, and the way back. -->
      <div v-if="result.status === 'TESTS_FAILED'" class="notice notice-needs mt-7 grid gap-3">
        <template v-if="ownFailing.length && ownFailing.length === failingDomains.length">
          <p class="font-semibold">Your saved version fails its own tests in {{ ownFailing.join(', ') }} — combining did not cause it, and it would fail the same way anywhere.</p>
          <TestReport v-for="hop in failing" :key="hop.into" plain class="border-t border-[oklch(85%_0.06_25)] pt-3" :report="hop.tests" :rev="`${lineName(hop.into, app.info.mainBranch)} after combining`"
                      :link-to="(d) => spacePath(ws, `/table/${d}`)" :notes="failureNotes" />
          <p class="text-sm">Open {{ ownFailing[0] }}, fix the marked rows, save a version, and come back.</p>
        </template>
        <template v-else>
          <p class="font-semibold">It combines cleanly, but tests fail after combining, so it cannot land.</p>
          <TestReport v-for="hop in failing" :key="hop.into" plain class="border-t border-[oklch(85%_0.06_25)] pt-3" :report="hop.tests" :rev="`${lineName(hop.into, app.info.mainBranch)} after combining`"
                      :link-to="(d) => spacePath(ws, `/table/${d}`)" :notes="failureNotes" />
          <p class="text-sm">Fix it in your work space — the failing cases are above — save a version, and come back.</p>
        </template>
      </div>

      <p v-if="result.status === 'CONFLICTS'" class="mt-7">
        <strong class="font-bold">{{ conflicts.length }} {{ conflicts.length === 1 ? 'thing needs' : 'things need' }} a decision</strong>
        <span class="meta"> — {{ answered }} answered. Your answers are kept if a line moves meanwhile; only new questions will need you.</span>
      </p>
      </div>

      <!-- Where it goes: the work space, and each line it lands in or is carried into, as dashed steps. -->
      <div v-if="result.hops.length" ref="graphSide" class="sticky grid min-w-0 gap-2.5 max-[1200px]:static" :style="{ top: `${stickTop}px` }">
        <BranchGraph
          v-if="history"
          class="graph"
          :caption="['LANDED', 'NOTHING_TO_LAND'].includes(result.status) ? 'Where it landed' : 'Where it will land'"
          :history="history"
          :focus="ws"
          :ghost="['LANDED', 'NOTHING_TO_LAND'].includes(result.status) ? [] : ghost"
          :ghost-text="ghostText"
          :main-branch="app.info.mainBranch"
        />
        <p v-else class="loading">Reading the branches…</p>
      </div>
      </div>

      <!-- The decisions take the full width: each one is a side-by-side of rules. -->
      <Resolver
        v-if="result.status === 'CONFLICTS'"
        class="mt-8"
        :result="result"
        :resolutions="answers"
        :main-branch="app.info.mainBranch"
        :space-name="`your ${space.info?.name || 'space'}`"
        @choose="choose"
      />
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import BranchGraph from '../components/BranchGraph.vue';
import LineBullet from '../components/LineBullet.vue';
import Resolver from '../components/Resolver.vue';
import TestReport from '../components/TestReport.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { routeColour } from '../lib/routes';
import { space } from '../lib/space';
import { useStickyFit } from '../lib/stickyFit';
import { app, lineName, spaceIdFromRoute, spacePath } from '../store';

const route = useRoute();
const router = useRouter();
const ws = spaceIdFromRoute(route.params);
const result = ref(null);
const error = ref(null);
const busy = ref(false);
const stale = ref(false);
const STORE_KEY = `land-answers:${ws}`;
const history = ref(null);
const graphSide = ref(null);
const stickTop = useStickyFit(graphSide);
const loadHistory = () => api.history(42).then((h) => { history.value = h; }).catch(() => {});

// The chain, step by step: into the target line, then carried into each newer line and main.
const ghost = computed(() => {
  const steps = [];
  let from = ws;
  for (const h of result.value?.hops || []) {
    if (!h.already) steps.push({ lane: h.into, from });
    from = h.into;
  }
  return steps;
});
const ghostText = computed(() => {
  const hops = result.value?.hops || [];
  if (!hops.length || ['LANDED', 'NOTHING_TO_LAND'].includes(result.value.status)) return '';
  const names = hops.filter((h) => !h.already).map((h) => lineName(h.into, app.info.mainBranch));
  if (!names.length) return '';
  return names.length === 1 ? `Lands in ${names[0]}` : `Lands in ${names[0]}, then carried into ${names.slice(1).join(' and ')}`;
});
const removed = ref(false);
const removing = ref(false);

// Landed: everything in the work space is in the lines now, so it is usually done with.
async function removeSpace() {
  removing.value = true;
  try {
    await api.deleteSpace(ws);
    removed.value = true;
    app.say(`Removed the work space ${space.info?.name || ''}; its work lives in the lines.`, 'plain');
  } catch (e) {
    app.say(`The work space was not removed: ${e.message}`, 'needs');
  } finally {
    removing.value = false;
  }
}

// Answers survive a reload: deciding fifteen conflicts is work.
const answers = reactive((() => {
  try {
    return JSON.parse(sessionStorage.getItem(STORE_KEY) || '{}');
  } catch {
    return {};
  }
})());
function remember() {
  try {
    sessionStorage.setItem(STORE_KEY, JSON.stringify(answers));
  } catch {
    // a private window: answers live as long as the page
  }
}

const target = computed(() => space.info?.targetLine || app.info.mainBranch);
const conflicts = computed(() => (result.value?.hops || []).flatMap((h) => h.conflicts));
const answered = computed(() => conflicts.value.filter((c) => answers[c.id]).length);
const failing = computed(() => (result.value?.hops || []).filter((h) => h.tests && !h.tests.passed));
// Which failures the work space brings with it, and which only appear once combined.
const ownReport = ref(null);
const failingDomains = computed(() => [...new Set(failing.value.flatMap((h) => h.tests.domains.filter((d) => d.failures.length || d.error).map((d) => d.domain)))]);
const ownFailing = computed(() => (ownReport.value?.domains || []).filter((d) => (d.failures.length || d.error) && failingDomains.value.includes(d.domain)).map((d) => d.domain));
const failureNotes = computed(() => Object.fromEntries(failingDomains.value.map((d) => [d, ownFailing.value.includes(d)
  ? 'Fails in your saved version too: fix it in your work space.'
  : 'Passes in your work space; fails only once combined — something on the line changed what it depends on.'])));
const steps = computed(() => (result.value?.hops || []).filter((h) => !h.already).length);
const landedLines = computed(() => (result.value?.hops || []).filter((h) => !h.already).map((h) => lineName(h.into, app.info.mainBranch)).join(', then '));

function stepClass(hop) {
  if (hop.already) return 'done';
  if (hop.conflicts.length) return 'needs';
  if (hop.tests && !hop.tests.passed) return 'needs';
  return hop.provisional ? 'waiting' : 'ready';
}
// The state word under each station; the detail of stepText goes beneath it.
const STATE = { done: 'state-quiet', needs: 'state-needs', waiting: 'state-quiet', ready: 'state-clean' };
function stepWord(hop, i) {
  const k = stepClass(hop);
  if (k === 'done') return 'already has it';
  if (k === 'needs') return hop.conflicts.length ? 'needs you' : 'failing';
  if (k === 'waiting') return 'waiting';
  if (result.value?.status === 'LANDED') return i === 0 ? 'landed' : 'carried here';
  return 'ready';
}
const stepDetail = (hop, i) => (stepClass(hop) === 'done' || result.value?.status === 'LANDED' ? '' : stepText(hop, i));
function stepText(hop, i) {
  if (hop.already) return 'already has it';
  if (hop.conflicts.length) return `${hop.conflicts.length} to decide`;
  if (hop.provisional) return 'after the step before is decided';
  if (hop.tests && !hop.tests.passed) return `${hop.tests.failed} ${hop.tests.failed === 1 ? 'test fails' : 'tests fail'}`;
  if (result.value?.status === 'LANDED') return i === 0 ? 'landed' : 'carried here';
  const changed = hop.domains.filter((d) => d.change !== 'unchanged').length;
  return `${i === 0 ? 'lands here' : 'carried here'} · ${changed} ${changed === 1 ? 'domain' : 'domains'} · ${hop.tests ? `${hop.tests.total} tests pass` : ''}`;
}

function choose(id, key) {
  answers[id] = key;
  stale.value = true;
  remember();
}

async function compute(apply = false) {
  busy.value = true;
  error.value = null;
  try {
    const r = await api.land(ws, { resolutions: { ...answers }, tips: apply ? result.value?.tips : null, apply });
    result.value = r;
    stale.value = false;
    if (r.status === 'TESTS_FAILED' && space.info?.head) api.commitTests(space.info.head).then((t) => { ownReport.value = t; }).catch(() => {});
    if (r.status === 'LANDED') {
      try {
        sessionStorage.removeItem(STORE_KEY);
      } catch {
        // nothing kept
      }
      app.say(`Landed in ${landedLines.value}.`, 'clean');
      space.open(ws);
      loadHistory();
    } else if (r.status === 'MOVED') {
      app.say(r.message, 'needs');
      await compute(false);
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  loadHistory();
  await space.open(ws);
  if (!space.changes.length) compute(false);
});

useBar(() => {
  const secondary = [{ label: 'Back to the work space', quiet: true, run: () => router.push(spacePath(ws)) }];
  const r = result.value;
  if (!r || space.changes.length) return { secondary };
  if (r.status === 'LANDED' && !removed.value) {
    return {
      secondary: [{ label: 'Keep the work space', quiet: true, run: () => router.push(spacePath(ws)) }],
      primary: { label: `Remove ${space.info?.name || 'the work space'}`, run: removeSpace, busy: removing.value }
    };
  }
  if (r.status === 'LANDED') return { secondary: [], primary: { label: 'Make a release', run: () => router.push('/releases') } };
  if (r.status === 'CONFLICTS') {
    const left = conflicts.value.length - answered.value;
    return {
      secondary,
      hint: left ? `${left} still to decide.` : null,
      primary: { label: 'Check with these answers', run: () => compute(false), busy: busy.value, disabled: left > 0 }
    };
  }
  if (r.status === 'TESTS_FAILED' && failingDomains.value.length) {
    const d = failingDomains.value[0];
    return { secondary: [...secondary, { label: 'Check again', run: () => compute(false), disabled: busy.value }], primary: { label: `Open ${d} to fix it`, run: () => router.push(spacePath(ws, `/table/${d}`)) } };
  }
  if (r.status === 'CLEAN') {
    return { secondary, primary: { label: stale.value ? 'Check again' : `Land in ${r.hops.filter((h) => !h.already).length} ${r.hops.filter((h) => !h.already).length === 1 ? 'line' : 'lines'}`, run: () => compute(!stale.value), busy: busy.value } };
  }
  return { secondary, primary: { label: 'Check again', run: () => compute(false), busy: busy.value } };
});
</script>

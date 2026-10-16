<template>
  <div class="page-body wide">
    <h1>Work spaces</h1>
    <p class="lede">Rules change in work spaces. A work space is one branch — the one place it is saved — so it can be continued on any instance; each copy shows whether it is saved here.</p>
    <SyncLine class="mt-3" @changed="load" />
    <p v-if="loadError" class="notice notice-needs mt-7">The work spaces could not be read: {{ loadError }}</p>
    <p v-else-if="!loaded" class="loading mt-7">Reading the work spaces…</p>

    <!-- The list and the network side by side, one surface: both columns start on the same line. -->
    <div class="mt-9 grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)] items-start gap-10 max-[1200px]:grid-cols-1">
    <div class="min-w-0">

    <LinePanel v-if="selectedLine" class="mb-9" :line="selectedLine" :main-branch="app.info.mainBranch" @clear="clear" @closed="onLineClosed" />

    <section v-if="mine.length" class="mt-9 first:mt-0">
      <h2 class="mb-2.5 text-sm font-bold text-ink-2">Continue</h2>
      <div class="rows">
        <router-link
          v-for="s in mine"
          :key="s.id"
          :to="spacePath(s.id)"
          class="grid grid-cols-[minmax(120px,1fr)_minmax(0,1.4fr)_minmax(0,1.4fr)_5rem] items-center gap-4 rounded-sm px-2 py-3 no-underline hover:bg-hover max-[760px]:grid-cols-[1fr_auto]"
          :class="rowTone(s)"
          @mouseenter="focused = s.id"
          @focus="focused = s.id"
        >
          <span class="flex min-w-0 items-center gap-2 font-semibold"><LineBullet :line="lineOf(s)" :main-branch="app.info.mainBranch" size="sm" :title="lineName(lineOf(s), app.info.mainBranch)" /><span class="truncate">{{ s.name }}</span></span>
          <span class="truncate text-ink-2 max-[760px]:col-span-full max-[760px]:row-start-2 max-[760px]:whitespace-normal">{{ purposeOf(s, app.info.mainBranch) }}</span>
          <span :class="`state state-${savedStateOf(s).tone}`">{{ savedStateOf(s).text }}</span>
          <span class="text-right text-xs whitespace-nowrap text-ink-3 max-[760px]:hidden">{{ whenText(s.lastModifiedAt) }}</span>
        </router-link>
      </div>
    </section>

    <section v-if="others.length" class="mt-9 first:mt-0">
      <h2 class="mb-2.5 text-sm font-bold text-ink-2">Colleagues’ work spaces here</h2>
      <div class="rows">
        <router-link
          v-for="s in others"
          :key="s.id"
          :to="spacePath(s.id)"
          class="grid grid-cols-[minmax(120px,1fr)_minmax(0,1.4fr)_minmax(0,1.4fr)_5rem] items-center gap-4 rounded-sm px-2 py-3 no-underline hover:bg-hover max-[760px]:grid-cols-[1fr_auto]"
          :class="rowTone(s)"
          @mouseenter="focused = s.id"
          @focus="focused = s.id"
        >
          <span class="flex min-w-0 items-center gap-2"><LineBullet :line="lineOf(s)" :main-branch="app.info.mainBranch" size="sm" :title="lineName(lineOf(s), app.info.mainBranch)" /><span class="min-w-0 truncate"><span class="font-semibold">{{ s.name }}</span> <span class="text-sm text-ink-2">· {{ s.owner }}</span></span></span>
          <span class="truncate text-ink-2 max-[760px]:col-span-full max-[760px]:row-start-2 max-[760px]:whitespace-normal">{{ purposeOf(s, app.info.mainBranch) }}</span>
          <span :class="`state state-${savedStateOf(s).tone === 'needs' ? 'quiet' : savedStateOf(s).tone}`">{{ savedStateOf(s).text }}</span>
          <span class="text-right text-xs whitespace-nowrap text-ink-3 max-[760px]:hidden">{{ whenText(s.lastModifiedAt) }}</span>
        </router-link>
      </div>
    </section>

    <section v-if="elsewhere.length" class="mt-9 first:mt-0">
      <h2 class="mb-1 text-sm font-bold text-ink-2">Worked on elsewhere</h2>
      <p class="mb-2.5 text-sm text-ink-2">Continuing one here starts from its last saved version. Unsaved changes on other instances stay there until saved.</p>
      <div class="rows">
        <div
          v-for="s in elsewhere"
          :key="s.id"
          class="grid grid-cols-[minmax(120px,1fr)_minmax(0,1.2fr)_minmax(0,1fr)_auto] items-center gap-4 rounded-sm px-2 py-3 max-[760px]:grid-cols-[1fr_auto]"
          :class="rowTone(s)"
          @mouseenter="focused = s.id"
        >
          <span class="flex min-w-0 items-center gap-2"><LineBullet :line="lineOf(s)" :main-branch="app.info.mainBranch" size="sm" :title="lineName(lineOf(s), app.info.mainBranch)" /><span class="min-w-0 truncate"><span class="font-semibold">{{ s.name }}</span> <span class="text-sm text-ink-2">· {{ s.owner }}</span></span></span>
          <span class="truncate text-ink-2 max-[760px]:col-span-full max-[760px]:row-start-2 max-[760px]:whitespace-normal">{{ purposeOf(s, app.info.mainBranch) }}</span>
          <span class="text-sm text-ink-2">saved{{ s.savedOn ? ` on ${s.savedOn}` : '' }} <span class="text-xs text-ink-3">{{ whenText(s.lastModifiedAt) }}</span></span>
          <button type="button" class="btn btn-small" :aria-busy="opening === s.id" :disabled="opening === s.id" @click="continueHere(s)">{{ opening === s.id ? 'Opening…' : 'Continue here' }}</button>
        </div>
      </div>
    </section>

    <p v-if="loaded && !mine.length && !others.length && !elsewhere.length" class="text-sm text-ink-2">No work spaces yet.</p>
    </div>

    <!-- Where every work space started: forked from its line, and what happened since. -->
    <div ref="graphSide" class="sticky grid min-w-0 gap-2.5 max-[1200px]:static" :style="{ top: `${stickTop}px` }">
    <BranchGraph
      v-if="history"
      caption="Where they started"
      :history="history"
      :focus="selected || pickedCommit?.lane || focused"
      :picked="selected"
      :pickable="history.lanes.map((l) => l.id)"
      commit-pickable
      :picked-commit="pickedCommit?.sha || null"
      :main-branch="app.info.mainBranch"
      @pick="(id) => { pickedCommit = null; selected = selected === id ? null : id; }"
      @pick-commit="(c) => { selected = null; pickedCommit = pickedCommit?.sha === c.sha ? null : c; }"
    />
    <p v-else-if="loaded && !history" class="loading">Reading the branches…</p>
    <p v-if="history && !pickedCommit && !chosen && !selectedLine" class="text-xs text-ink-2">Pick a branch by its label to continue it or start from its newest commit — or pick one commit to start a work space from exactly there.</p>
    </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import BranchGraph from '../components/BranchGraph.vue';
import LineBullet from '../components/LineBullet.vue';
import LinePanel from '../components/LinePanel.vue';
import SyncLine from '../components/SyncLine.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { useStickyFit } from '../lib/stickyFit';
import { app, branchName, lineName, plainMessage, purposeOf, savedStateOf, spacePath, whenText } from '../store';

/* Work spaces: continue one (here, or one worked on elsewhere), or start a new one. */
const router = useRouter();
const spaces = ref([]);
const elsewhere = ref([]);
const loadError = ref(null);
const loaded = ref(false);
const opening = ref(null);
const history = ref(null);
const focused = ref(null);
const graphSide = ref(null);
const stickTop = useStickyFit(graphSide);
// A work space runs in the colour of the line it lands in.
const lineOf = (s) => s.targetLine || app.info.mainBranch;
// The row picked in the graph is selected; the one under the pointer lights its branch there.
const rowTone = (s) => (selected.value === s.id ? 'bg-select ring-1 ring-ink ring-inset' : focused.value === s.id ? 'bg-hover' : '');
// The work space picked in the graph: what "Continue" acts on.
const selected = ref(null);
// Or one commit picked in the graph: a work space can start from exactly there.
const pickedCommit = ref(null);
const clear = () => { selected.value = null; pickedCommit.value = null; };
const startFromCommit = (c) => `/work/new?from=${encodeURIComponent(c.sha)}&on=${encodeURIComponent(c.lane)}`;
// A commit on a work space opens that work space (continued here if it was worked on elsewhere);
// a commit on a line opens the version itself, to look at and try.
function openFor(c) {
  const here = spaces.value.find((s) => s.id === c.lane);
  if (here) return { label: `Open ${here.name}`, quiet: true, run: () => router.push(spacePath(here.id)) };
  const away = elsewhere.value.find((s) => s.id === c.lane);
  if (away) return { label: `Open ${away.name} here`, quiet: true, run: () => continueHere(away) };
  return { label: 'Open this version', quiet: true, run: () => router.push(`/commit/${c.sha}`) };
}
function onLineClosed() {
  clear();
  load();
}
const selectedLine = computed(() => (selected.value && !spaceIds.value.includes(selected.value) ? selected.value : null));
const chosen = computed(() => {
  const here = spaces.value.find((s) => s.id === selected.value);
  if (here) return { ...here, here: true };
  const away = elsewhere.value.find((s) => s.id === selected.value);
  return away ? { ...away, here: false } : null;
});

const spaceIds = computed(() => [...spaces.value, ...elsewhere.value].map((s) => s.id));

const mine = computed(() => spaces.value.filter((s) => s.owner === app.info.user));
const others = computed(() => spaces.value.filter((s) => s.owner !== app.info.user));

async function load() {
  try {
    [spaces.value, elsewhere.value, history.value] = await Promise.all([api.spaces(), api.spacesElsewhere(), api.history(42).catch(() => null)]);
  } catch (e) {
    loadError.value = e.message;
  } finally {
    loaded.value = true;
  }
}

async function continueHere(s) {
  opening.value = s.id;
  try {
    await api.openSpace(s.id);
    app.say(`${s.name} continues here, from its version saved${s.savedOn ? ` on ${s.savedOn}` : ''}.`, 'clean');
    router.push(spacePath(s.id));
  } catch (e) {
    app.say(`${s.name} could not be continued here: ${e.message}`, 'needs');
  } finally {
    opening.value = null;
  }
}

onMounted(load);

useBar(() => {
  if (pickedCommit.value) {
    const c = pickedCommit.value;
    return {
      secondary: [openFor(c)],
      primary: { label: 'Start a work space from this commit', run: () => router.push(startFromCommit(c)) }
    };
  }
  if (selectedLine.value) {
    return {
      secondary: [],
      primary: { label: `Start a work space from ${branchName(selectedLine.value, app.info.mainBranch)}`, run: () => router.push(`/work/new?from=${encodeURIComponent(selectedLine.value)}`) }
    };
  }
  if (chosen.value) {
    const c = chosen.value;
    return {
      secondary: [{ label: 'Start a new work space', quiet: true, run: () => router.push('/work/new') }],
      primary: c.here
        ? { label: `Continue ${c.name}`, run: () => router.push(spacePath(c.id)) }
        : { label: `Continue ${c.name} here`, run: () => continueHere(c) }
    };
  }
  const latest = mine.value[0];
  return {
    secondary: latest ? [{ label: `Continue ${latest.name}`, quiet: true, run: () => router.push(spacePath(latest.id)) }] : [],
    primary: { label: 'Start a new work space', run: () => router.push('/work/new') }
  };
});
</script>

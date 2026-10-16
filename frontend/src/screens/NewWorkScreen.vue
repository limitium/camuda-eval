<template>
  <div class="page-body wide">
    <h1>Start a new work space</h1>
    <p class="lede">Name it and pick, in the graph, what it builds on: a branch by its label (its newest commit, fetched just now from every instance) or any single commit.</p>

    <p v-if="error" class="notice notice-needs mt-7">{{ error }}</p>
    <p v-else-if="!bases" class="loading mt-7">Fetching the newest of every branch…</p>

    <!-- The choices and the network side by side: both columns start on the same line. -->
    <div v-else class="mt-7 grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)] items-start gap-10 max-[1200px]:grid-cols-1">
      <div class="min-w-0">
        <div class="max-w-[520px]">
          <label class="label" for="space-name">Name</label>
          <input id="space-name" v-model="name" class="field" placeholder="for example: release 1.1 fix 1" autocomplete="off" />
          <p class="mt-1 text-xs text-ink-2">Becomes the branch <span class="mono text-ink">{{ branch }}</span></p>
        </div>

        <!-- What it builds on, picked in the graph: one hairline row, not a card. -->
        <div class="rows mt-7" role="status">
          <div v-if="atCommit" class="grid grid-cols-[5.5rem_minmax(0,1fr)] items-baseline gap-x-4 gap-y-0.5 px-1 py-3">
            <span class="text-sm font-bold text-ink-2">Builds on</span>
            <span class="flex min-w-0 items-center gap-2">
              <LineBullet :line="landsIn" :main-branch="app.info.mainBranch" size="sm" :title="lineName(landsIn, app.info.mainBranch)" />
              <span class="min-w-0 font-semibold">“{{ plainMessage(atCommit.message.split('\n')[0], app.info.mainBranch) }}”</span>
            </span>
            <span class="col-start-2 text-sm text-ink-2">one commit on {{ branchName(atCommit.lane, app.info.mainBranch) }}, not its newest · lands in {{ lineName(landsIn, app.info.mainBranch) }}</span>
            <span class="col-start-2 text-xs text-ink-3">{{ atCommit.author }}, {{ whenText(atCommit.when) }} · <span class="mono">{{ atCommit.sha.slice(0, 7) }}</span></span>
          </div>
          <div v-else-if="picked" class="grid grid-cols-[5.5rem_minmax(0,1fr)] items-baseline gap-x-4 gap-y-0.5 px-1 py-3">
            <span class="text-sm font-bold text-ink-2">Builds on</span>
            <span class="flex min-w-0 items-center gap-2">
              <LineBullet :line="pickedLine" :main-branch="app.info.mainBranch" size="sm" :title="lineName(pickedLine, app.info.mainBranch)" />
              <span class="min-w-0 font-semibold">{{ capital(branchName(picked.ref, app.info.mainBranch)) }}</span>
            </span>
            <span class="col-start-2 text-sm text-ink-2">{{ landsText(picked) }}</span>
            <span class="col-start-2 text-xs text-ink-3">newest: “{{ plainMessage(picked.message, app.info.mainBranch) }}” — {{ picked.by }}{{ picked.savedOn ? ` on ${picked.savedOn}` : '' }}, {{ whenText(picked.when) }}</span>
          </div>
          <div v-else class="grid grid-cols-[5.5rem_minmax(0,1fr)] items-baseline gap-x-4 px-1 py-3">
            <span class="text-sm font-bold text-ink-2">Builds on</span>
            <span class="text-sm text-ink-2">Pick the branch it builds on — its label or any of its commits.</span>
          </div>
        </div>
      </div>

      <div ref="graphSide" class="sticky grid min-w-0 gap-2.5 max-[1200px]:static" :style="{ top: `${stickTop}px` }">
        <BranchGraph
          v-if="history"
          :history="history"
          :picked="on ? null : from"
          :pickable="bases.map((b) => b.ref)"
          commit-pickable
          :picked-commit="on ? from : null"
          :focus="on || null"
          :ghost="from ? [{ lane: branch, from: on || from, at: on ? from : null, open: true }] : []"
          :ghost-text="ghostText"
          :main-branch="app.info.mainBranch"
          @pick="pickLane"
          @pick-commit="pickCommit"
        />
        <p v-else class="loading">Reading the branches…</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import BranchGraph from '../components/BranchGraph.vue';
import LineBullet from '../components/LineBullet.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { useStickyFit } from '../lib/stickyFit';
import { app, branchName, branchSlug, lineName, plainMessage, spacePath, whenText } from '../store';

/* One screen: choose what the work space builds on (each at its newest commit, fetched first) and name it. */
const route = useRoute();
const router = useRouter();
const bases = ref(null);
const error = ref(null);
const from = ref(String(route.query.from || ''));
const name = ref(String(route.query.name || ''));
const busy = ref(false);
// Set when one commit is picked: the branch it is on, which decides where the work space lands.
const on = ref(String(route.query.on || ''));
const history = ref(null);
const graphSide = ref(null);
const stickTop = useStickyFit(graphSide);

const capital = (s) => (/^(main|master|release\/|feature\/|hotfix\/)/.test(s) ? s : s.charAt(0).toUpperCase() + s.slice(1));
const picked = computed(() => (bases.value || []).find((b) => b.ref === from.value) || null);
const atCommit = computed(() => (on.value && history.value?.commits.find((c) => c.sha === from.value)) || null);
// Like the branch it was picked on: a space's commit lands where that space lands, a line's on that line.
const landsIn = computed(() => (bases.value || []).find((b) => b.ref === on.value)?.targetLine || (on.value.startsWith('release/') ? on.value : app.info.mainBranch));
// The line a picked branch runs on: a line itself, or the line a work space lands in.
const pickedLine = computed(() => (picked.value?.kind === 'SPACE' ? picked.value.targetLine || app.info.mainBranch : picked.value?.ref || app.info.mainBranch));
function pickLane(ref) {
  on.value = '';
  from.value = ref;
}
// The newest commit of a branch is that branch; any other commit is started from exactly.
function pickCommit(c) {
  if ((c.tips || []).includes(c.lane)) return pickLane(c.lane);
  on.value = c.lane;
  from.value = c.sha;
}
const ghostText = computed(() => {
  if (!from.value) return '';
  const who = name.value.trim() ? `“${name.value.trim()}”` : 'Your new work space';
  return on.value
    ? `${who} starts here, from the picked commit on ${branchName(on.value, app.info.mainBranch)}`
    : `${who} starts here, from the newest commit of ${branchName(from.value, app.info.mainBranch)}`;
});
// Any wording becomes a branch name: lower case, words joined by dashes, nothing git would refuse.
const slug = computed(() => branchSlug(name.value));
const valid = computed(() => slug.value.length > 0);
// A fix to a release stays a fix: building on a hotfix work space makes another hotfix.
const kind = computed(() => ((on.value || from.value).startsWith('hotfix/') ? 'hotfix' : 'feature'));
const branch = computed(() => `${kind.value}/${app.info.user}/${slug.value || '…'}`);
const landsText = (b) => (b.kind === 'SPACE' ? `someone’s work in progress, landing in ${lineName(b.targetLine, app.info.mainBranch)}` : b.kind === 'MAIN' ? 'new work, released in the next release' : 'a fix to what this release runs, carried forward to main');

onMounted(async () => {
  try {
    [bases.value, history.value] = await Promise.all([api.bases(), api.history(42)]);
    if (!from.value && bases.value.length) from.value = bases.value[0].ref;
  } catch (e) {
    error.value = `The branches could not be fetched: ${e.message}`;
  }
});

async function create() {
  busy.value = true;
  try {
    const created = await api.createSpace(slug.value, from.value, on.value || null);
    app.say(`Started the work space ${created.name} on ${on.value ? `commit ${from.value.slice(0, 7)} of ${branchName(on.value, app.info.mainBranch)}` : branchName(from.value, app.info.mainBranch)}.`, 'clean');
    router.push(spacePath(created.id));
  } catch (e) {
    app.say(`No work space was started: ${e.message}`, 'needs');
  } finally {
    busy.value = false;
  }
}

useBar(() => ({
  secondary: [{ label: 'Back', quiet: true, run: () => router.push('/work') }],
  hint: !from.value ? 'Pick what it builds on.' : !valid.value ? 'Give it a name.' : null,
  primary: { label: 'Start the work space', run: create, busy: busy.value, disabled: !from.value || !valid.value }
}));
</script>

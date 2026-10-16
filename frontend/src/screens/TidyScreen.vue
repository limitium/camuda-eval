<template>
  <div class="page-body wide">
    <h1>Clean up</h1>
    <p class="lede">Clears away abandoned or old work spaces and releases nobody needs, without losing anyone’s work. Work that never landed is archived and can be brought back at any time.</p>

    <p v-if="loadError" class="notice notice-needs mt-7">{{ loadError }}</p>
    <p v-else-if="!plan" class="loading mt-7">Fetching, then looking through work spaces and releases…</p>

    <!-- The list and the network side by side, one surface: both columns start on the same line. -->
    <div v-else class="mt-10 grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)] items-start gap-10 max-[1200px]:grid-cols-1">
    <div class="min-w-0">
      <p v-if="!actionable.length && !blocked.length" class="notice mt-7 first:mt-0">Nothing to clean up. {{ keptText }}</p>

      <section v-for="group in groups" :key="group.key" class="mt-10 first:mt-0">
        <header class="mb-3 grid max-w-[72ch] gap-1">
          <h2 class="text-lg">{{ group.title }}</h2>
          <p class="text-sm text-ink-2">{{ group.note }}</p>
        </header>
        <div class="rows">
          <label
            v-for="item in group.items"
            :key="item.id"
            class="grid min-h-[60px] cursor-pointer grid-cols-[20px_minmax(0,1.4fr)_minmax(0,1fr)_7rem] items-center gap-3.5 rounded-sm px-2 py-3 hover:bg-hover max-[760px]:grid-cols-[20px_1fr_auto]"
            :class="focused === item.id ? 'bg-hover' : ''"
            @mouseenter="focused = item.id"
            @mouseleave="focused = null"
          >
            <input v-model="chosen[item.id]" type="checkbox" class="m-0 size-4 accent-ink" />
            <span class="grid min-w-0 gap-0.5">
              <span class="flex min-w-0 items-center gap-2 font-semibold" :class="chosen[item.id] ? '' : 'text-ink-3'">
                <LineBullet :line="lineOf(item)" :main-branch="app.info.mainBranch" size="sm" :title="lineName(lineOf(item), app.info.mainBranch)" />
                <span class="truncate">{{ title(item) }}</span>
              </span>
              <span class="text-sm" :class="chosen[item.id] ? 'text-ink-2' : 'text-ink-3'">{{ why(item) }}</span>
            </span>
            <span class="truncate text-sm text-ink-2 max-[760px]:hidden">{{ item.lastMessage ? `“${plainMessage(item.lastMessage, app.info.mainBranch)}”` : '' }}</span>
            <span class="text-right text-xs whitespace-nowrap text-ink-3">{{ whenText(item.lastActivity) }}</span>
          </label>
        </div>
      </section>

      <section v-if="blocked.length" class="mt-10 first:mt-0">
        <header class="mb-3 grid max-w-[72ch] gap-1">
          <h2 class="text-lg">Left alone until someone decides</h2>
          <p class="text-sm text-ink-2">These look old, but tidying them could lose something only a person can judge.</p>
        </header>
        <div class="rows">
          <div
            v-for="item in blocked"
            :key="item.id"
            class="grid min-h-[60px] grid-cols-[20px_minmax(0,1.4fr)_minmax(0,1fr)_7rem] items-center gap-3.5 rounded-sm px-2 py-3 max-[760px]:grid-cols-[20px_1fr_auto]"
            :class="focused === item.id ? 'bg-hover' : ''"
            @mouseenter="focused = item.id"
            @mouseleave="focused = null"
          >
            <span class="state state-needs" aria-hidden="true"></span>
            <span class="grid min-w-0 gap-0.5">
              <span class="flex min-w-0 items-center gap-2 font-semibold">
                <LineBullet :line="lineOf(item)" :main-branch="app.info.mainBranch" size="sm" :title="lineName(lineOf(item), app.info.mainBranch)" />
                <span class="truncate">{{ title(item) }}</span>
              </span>
              <span class="text-sm text-needs-ink">{{ why(item) }}</span>
            </span>
            <span class="truncate text-sm text-ink-2 max-[760px]:hidden">{{ item.lastMessage ? `“${plainMessage(item.lastMessage, app.info.mainBranch)}”` : '' }}</span>
            <span class="text-right text-xs whitespace-nowrap text-ink-3">{{ whenText(item.lastActivity) }}</span>
          </div>
        </div>
      </section>

      <p v-if="actionable.length || blocked.length" class="mt-7 max-w-[72ch] text-sm text-ink-2">{{ keptText }}</p>

      <section v-if="archived.length" class="mt-10 first:mt-0">
        <header class="mb-3 grid max-w-[72ch] gap-1">
          <h2 class="text-lg">Archived spaces</h2>
          <p class="text-sm text-ink-2">Kept exactly as they were left. Restoring makes the work space again, with all its versions.</p>
        </header>
        <div class="rows">
          <div v-for="a in archived" :key="a.ref" class="grid min-h-[60px] grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_7rem] items-center gap-3.5 px-2 py-3 max-[760px]:grid-cols-[1fr_auto]">
            <span class="grid min-w-0 gap-0.5">
              <span class="flex min-w-0 items-center gap-2 font-semibold">
                <LineBullet :line="a.targetLine || app.info.mainBranch" :main-branch="app.info.mainBranch" size="sm" />
                <span class="truncate">{{ a.owner }}’s {{ a.name }}</span>
              </span>
              <span class="text-sm text-ink-2">Archived by {{ a.archivedBy }} <span class="text-xs text-ink-3">{{ whenText(a.archivedAt) }}</span> · lands in {{ lineName(a.targetLine, app.info.mainBranch) }}</span>
            </span>
            <span class="truncate text-sm text-ink-2 max-[760px]:hidden">“{{ plainMessage(a.lastMessage, app.info.mainBranch) }}”</span>
            <button type="button" class="btn btn-small justify-self-end" :aria-busy="restoring === a.ref" :disabled="restoring === a.ref" @click="restore(a)">Restore</button>
          </div>
        </div>
      </section>
    </div>

    <!-- What goes: the chosen branches struck through, in the same graph as everywhere else. -->
    <div ref="graphSide" class="sticky grid min-w-0 gap-2 max-[1200px]:static" :style="{ top: `${stickTop}px` }">
      <BranchGraph
        v-if="history"
        caption="What is cleaned up"
        :history="history"
        :marked="selected.map((i) => i.id)"
        mark-text="Cleaned up"
        :focus="focused"
        :pickable="actionable.map((i) => i.id)"
        :main-branch="app.info.mainBranch"
        @pick="(id) => (chosen[id] = !chosen[id])"
      />
      <p v-else class="loading">Reading the branches…</p>
      <p v-if="history" class="text-xs text-ink-2">Struck through: what the chosen items clear away. Pick a branch to choose or unchoose it.</p>
    </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import BranchGraph from '../components/BranchGraph.vue';
import LineBullet from '../components/LineBullet.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { useStickyFit } from '../lib/stickyFit';
import { app, lineName, plainMessage, spacePath, whenText } from '../store';

const router = useRouter();
const plan = ref(null);
const archived = ref([]);
const loadError = ref(null);
const chosen = reactive({});
const busy = ref(false);
const restoring = ref(null);
const history = ref(null);
const focused = ref(null);
const graphSide = ref(null);
const stickTop = useStickyFit(graphSide);
// The line an item runs on: a release line itself, or the line a work space lands in.
const lineOf = (item) => (item.kind === 'LINE' ? item.id : item.detail?.[0] || app.info.mainBranch);

const byAction = (action) => (plan.value?.items || []).filter((i) => i.action === action);
const actionable = computed(() => (plan.value?.items || []).filter((i) => i.action !== 'BLOCKED'));
const blocked = computed(() => byAction('BLOCKED'));
const selected = computed(() => actionable.value.filter((i) => chosen[i.id]));

const groups = computed(() => [
  {
    key: 'remove',
    title: 'Spaces with nothing left in them',
    note: 'Everything in these is already in the release or main they were for. Removing them loses nothing.',
    items: byAction('REMOVE')
  },
  {
    key: 'archive',
    title: `Spaces untouched for over ${plan.value?.staleAfterDays} days`,
    note: 'They hold work that never landed. It is archived, not deleted, and its owner can restore it below.',
    items: byAction('ARCHIVE')
  },
  {
    key: 'close',
    title: 'Releases that can be closed',
    note: 'Every change on them was released as a version. The versions stay: they can still be deployed, and fixing one reopens its release.',
    items: byAction('CLOSE')
  }
].filter((g) => g.items.length));

const keptText = computed(() => {
  if (!plan.value) return '';
  const spaces = plan.value.spacesInUse;
  const lines = plan.value.linesKept.map((l) => l.slice(8));
  const parts = [];
  if (spaces) parts.push(`${spaces} ${spaces === 1 ? 'space is' : 'spaces are'} in use`);
  if (lines.length) parts.push(`the newest ${lines.length} releases (${lines.join(', ')}) always stay open`);
  parts.push('main is never touched');
  const text = parts.join('; ');
  return `Always left alone: ${text.charAt(0).toLowerCase() + text.slice(1)}.`;
});

const owned = (item) => (item.owner === app.info.user ? 'Your' : `${item.owner}’s`);
function title(item) {
  if (item.kind === 'LINE') return `Release ${item.name}`;
  return `${owned(item)} ${item.name}`;
}
function why(item) {
  switch (item.reason) {
    case 'LANDED':
      return `Landed in ${lineName(item.detail[0], app.info.mainBranch)} — everything in it lives there now`;
    case 'NOTHING_UNIQUE':
      return `Nothing in it that isn’t already in ${lineName(item.detail[0], app.info.mainBranch)}`;
    case 'STALE_UNLANDED':
      return `${item.detail[1]} saved ${item.detail[1] === '1' ? 'version' : 'versions'} never landed in ${lineName(item.detail[0], app.info.mainBranch)}`;
    case 'UNSAVED_EDITS':
      return `Has edits that were never saved as a version — ${item.owner === app.info.user ? 'save or discard them first' : `ask ${item.owner} to save or discard them`}`;
    case 'FULLY_RELEASED':
      return `Last released as ${item.detail.join(' and ')}`;
    case 'FULLY_RELEASED_RECENT':
      return `One of the newest releases, kept open unless you choose it. Everything on it is released (${item.detail.join(', ')}); a fix to it opens it again`;
    case 'UNRELEASED_CHANGES':
      return 'Has changes after its last version — release them as a version first, or they can’t be released from this line again';
    case 'OPEN_SPACE_LANDS_HERE':
      return `${item.detail.map((id) => { const [, owner, name] = id.split('/'); return `${owner}’s ${name}`; }).join(', ')} will land here — it stays open until that lands or is tidied`;
    default:
      return item.reason;
  }
}

function adopt(data, keepChoices) {
  const previous = { ...chosen };
  plan.value = data.plan;
  archived.value = data.archived || archived.value;
  Object.keys(chosen).forEach((k) => delete chosen[k]);
  for (const item of actionable.value) {
    // Everything offered is safe (removed losing nothing, or archived and restorable), so it starts chosen.
    chosen[item.id] = keepChoices && item.id in previous ? previous[item.id] : item.reason !== 'FULLY_RELEASED_RECENT';
  }
}

async function load() {
  api.history(42).then((h) => { history.value = h; }).catch(() => {});
  try {
    adopt(await api.cleanupPlan(), false);
  } catch (error) {
    loadError.value = error.message;
  }
}

async function tidy() {
  if (!selected.value.length || busy.value) return;
  busy.value = true;
  const items = Object.fromEntries(selected.value.map((i) => [i.id, i.head]));
  const archivedCount = selected.value.filter((i) => i.action === 'ARCHIVE').length;
  try {
    const result = await api.cleanupApply(items);
    adopt(result, true);
    api.history(42).then((h) => { history.value = h; }).catch(() => {});
    const n = result.done.length;
    app.say(`Tidied ${n} ${n === 1 ? 'item' : 'items'}.${archivedCount ? ` ${archivedCount} ${archivedCount === 1 ? 'space was' : 'spaces were'} archived and can be restored.` : ''}`, 'clean');
  } catch (error) {
    if (error.status === 409 && error.body?.plan) {
      adopt({ plan: error.body.plan }, true);
      app.say('Something changed while you were looking, so nothing was tidied. The list is up to date now — check it and try again.', 'needs');
    } else {
      app.say(`Nothing was tidied: ${error.message}`, 'needs');
    }
  } finally {
    busy.value = false;
  }
}

async function restore(a) {
  restoring.value = a.ref;
  try {
    const space = await api.cleanupRestore(a.ref);
    app.say(`Restored ${a.owner}’s ${a.name}, with all its versions.`, 'clean');
    archived.value = archived.value.filter((x) => x.ref !== a.ref);
    router.push(spacePath(space.id));
  } catch (error) {
    app.say(`${a.name} was not restored: ${error.message}`, 'needs');
  } finally {
    restoring.value = null;
  }
}

onMounted(load);

useBar(() => {
  const n = selected.value.length;
  return {
    hint: plan.value && !actionable.value.length ? null : n ? null : 'Choose what to tidy.',
    secondary: [{ label: 'Back', quiet: true, run: () => router.push('/') }],
    primary: actionable.value.length
      ? { label: n ? `Tidy up ${n} ${n === 1 ? 'item' : 'items'}` : 'Tidy up', run: tidy, busy: busy.value, disabled: !n }
      : null
  };
});
</script>

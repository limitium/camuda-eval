<template>
  <div class="page-body" :class="{ wide: spaceId && !space.missing }">
    <template v-if="space.missing">
      <h1>This work space does not exist</h1>
      <p class="lede">There is no space called {{ spaceId }}. It may have been deleted, or the link is wrong. Nothing else was opened in its place.</p>
    </template>

    <template v-else>
      <header class="flex items-end justify-between gap-6">
        <div>
          <h1>{{ title }}</h1>
          <p class="lede">{{ intro }}</p>
          <SavedState v-if="spaceId" :space-id="spaceId" />
        </div>
      </header>

      <!-- The domains and the network side by side: both columns start on the same line. -->
      <div class="mt-8 grid items-start gap-10" :class="spaceId ? 'grid-cols-[minmax(0,1fr)_minmax(0,1fr)] max-[1200px]:grid-cols-1' : ''">
      <div class="min-w-0">
        <div class="rows">
          <div
            v-for="t in space.tables"
            :key="t"
            class="group/row grid min-h-[60px] grid-cols-[minmax(0,1fr)_auto_auto_auto] items-center gap-5 rounded-sm px-2 hover:bg-hover max-[760px]:grid-cols-[1fr_auto] max-[760px]:gap-y-1 max-[760px]:pb-2"
          >
            <router-link :to="spacePath(spaceId, `/table/${t}`)" class="group/open grid min-w-0 gap-0.5 py-3 no-underline max-[760px]:col-start-1 max-[760px]:row-start-1">
              <span class="text-lg font-semibold group-hover/open:underline">{{ t }}</span>
              <span class="truncate text-sm text-ink-2">{{ decisionsOf(t) }}</span>
            </router-link>
            <span v-if="problems[t]?.hasErrors || verdicts[t]" class="max-[760px]:col-start-1">
              <span v-if="problems[t]?.hasErrors" class="state state-needs">Tests don’t match the tables</span>
              <span v-else-if="verdicts[t] && !verdicts[t].passed" class="state state-needs">{{ verdicts[t].failed }} failing</span>
              <span v-else-if="verdicts[t]?.passed" class="state state-clean">Tests pass</span>
            </span>
            <span v-if="space.changeOf(t)" class="max-[760px]:col-start-1">
              <span v-if="space.changeOf(t) === 'new'" class="state state-edit">New</span>
              <span v-else-if="space.changeOf(t) === 'changed'" class="state state-edit">Changed</span>
            </span>
            <span class="flex min-w-9 items-center justify-end gap-2 max-[760px]:col-start-2 max-[760px]:row-start-1">
              <button
                v-if="confirming !== t"
                type="button"
                class="btn btn-quiet btn-small opacity-0 group-hover/row:opacity-100 focus-visible:opacity-100 [@media(hover:none)]:opacity-100"
                :aria-label="`Remove ${t}`"
                @click="confirming = t"
              >
                <Trash2 :size="16" :stroke-width="1.75" aria-hidden="true" />
              </button>
              <template v-else>
                <span class="text-sm text-ink-2">Remove {{ t }} and its tests?</span>
                <button type="button" class="btn btn-small btn-danger" @click="remove(t)">Remove</button>
                <button type="button" class="btn btn-small btn-quiet" @click="confirming = null">Keep</button>
              </template>
            </span>
          </div>
          <p v-if="!space.tables.length && space.loading" class="loading px-1 py-5">Reading the domains…</p>
          <p v-if="!space.tables.length && !space.loading" class="px-1 py-5 text-sm text-ink-2">No domains in this work space yet.</p>
        </div>

        <form v-if="adding" class="mt-5 max-w-[560px]" @submit.prevent="create">
          <label class="label" for="new-table">Name of the new domain</label>
          <div class="flex gap-2">
            <input id="new-table" ref="addField" v-model="newName" class="field" placeholder="for example: pricing" autocomplete="off" />
            <button type="submit" class="btn" :disabled="!validName">Add</button>
            <button type="button" class="btn btn-quiet" @click="adding = false">Cancel</button>
          </div>
          <p class="mt-2 text-sm text-ink-2">A domain holds its decision tables and their tests. Its name is the key the running app reads it by, so it cannot change later without changing the app.</p>
        </form>
        <button v-else type="button" class="btn btn-quiet -ml-2 mt-3" @click="startAdding">
          <Plus :size="16" :stroke-width="1.75" aria-hidden="true" /> Add a domain
        </button>

      <details v-if="space.info" class="group/mech mt-12 text-sm text-ink-2">
        <summary class="inline-flex w-max cursor-pointer list-none items-center gap-1 font-semibold [&::-webkit-details-marker]:hidden">
          <ChevronRight :size="16" :stroke-width="1.75" class="transition-[rotate] duration-150 ease-out group-open/mech:rotate-90" aria-hidden="true" />Version details
        </summary>
        <dl class="mt-3.5 mb-4 grid grid-cols-[max-content_1fr] gap-x-5 gap-y-1.5">
          <dt class="text-ink-3">Branch</dt><dd class="m-0 font-mono text-[0.8125rem] text-ink">{{ space.info.id }}</dd>
          <dt class="text-ink-3">Built on</dt><dd class="m-0 font-mono text-[0.8125rem] text-ink">{{ space.info.baseline }}</dd>
          <dt class="text-ink-3">Lands in</dt>
          <dd class="m-0 flex items-center gap-2 text-ink">
            <LineBullet v-if="space.info.targetLine" :line="space.info.targetLine" :main-branch="app.info.mainBranch" size="sm" />
            <span class="font-mono text-[0.8125rem]">{{ space.info.targetLine }}</span>
          </dd>
          <dt class="text-ink-3">Last version</dt><dd class="m-0 text-ink"><span class="mono">{{ space.info.head?.slice(0, 7) }}</span> “{{ space.info.lastMessage }}” — {{ space.info.lastModifiedBy }}, <span class="text-xs text-ink-3">{{ whenText(space.info.lastModifiedAt) }}</span></dd>
          <dt class="text-ink-3">Saved versions not landed</dt><dd class="m-0 text-ink">{{ space.info.ahead }}</dd>
        </dl>
        <button v-if="space.info.owner === app.info.user" type="button" class="btn btn-small btn-danger" @click="deleteSpace">Delete this work space</button>
      </details>
      </div>

      <!-- Where this work space sits: forked from its line, and what has happened on both since. The one
           graph on the screen; while changes can be brought in, it is also where they are picked. -->
      <div v-if="spaceId && space.info" ref="graphSide" class="sticky grid min-w-0 gap-6 max-[1200px]:static" :style="{ top: `${stickTop}px` }">
        <BranchGraph
          v-if="history"
          caption="Where this work space is"
          :history="history"
          :focus="spaceId"
          :picked="bringing ? bringFrom || null : null"
          :pickable="bringing ? bringPickable : []"
          :ghost="bringing && bringFrom ? [{ lane: spaceId, from: bringFrom }] : []"
          :ghost-text="bringing && bringFrom ? `Brings ${branchName(bringFrom, app.info.mainBranch)} into this work space` : ''"
          :main-branch="app.info.mainBranch"
          @pick="(ref) => (bringFrom = ref)"
        />
        <p v-else class="loading">Reading the branches…</p>

        <BringIn
          v-if="bringing"
          v-model:from="bringFrom"
          :space-id="spaceId"
          :target="space.info.targetLine"
          :main-branch="app.info.mainBranch"
          @pickable="(p) => (bringPickable = p)"
        />
      </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import BringIn from '../components/BringIn.vue';
import BranchGraph from '../components/BranchGraph.vue';
import LineBullet from '../components/LineBullet.vue';
import SavedState from '../components/SavedState.vue';
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ChevronRight, Plus, Trash2 } from 'lucide-vue-next';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { DmnDocument } from '../lib/dmnModel';
import { space } from '../lib/space';
import { useStickyFit } from '../lib/stickyFit';
import { app, branchName, spaceIdFromRoute, spacePath, whenText } from '../store';

const route = useRoute();
const router = useRouter();
const spaceId = spaceIdFromRoute(route.params);
const history = ref(null);
const decisionNames = reactive({});
// The version of each file as listed: removing is refused if it changed since (someone saved).
const seenVersions = {};
const problems = reactive({});
const confirming = ref(null);
const adding = ref(false);
const newName = ref('');
const addField = ref(null);
const graphSide = ref(null);
const stickTop = useStickyFit(graphSide);
// Bringing in changes: picked in the one graph, with BringIn's controls beneath it.
const bringing = computed(() => Boolean(spaceId && space.info && !space.changes.length));
const bringFrom = ref('');
const bringPickable = ref([]);

const verdicts = computed(() => app.verdictsFor(spaceId));
const validName = computed(() => /^[a-z0-9][a-z0-9_-]*$/i.test(newName.value.trim()) && !space.tables.includes(newName.value.trim()));

const title = computed(() => {
  if (!spaceId) return 'Shared tables';
  return space.info?.owner && space.info.owner !== app.info.user ? `${space.info.owner}’s ${route.params.name}` : route.params.name;
});

const intro = computed(() => {
  const n = space.changedTables.length;
  switch (space.current) {
    case 'edit':
      return spaceId ? 'Open a domain to change its tables’ rules or their test cases.' : 'Open a domain to change its tables’ rules or their test cases. Saving writes straight into the shared domains.';
    case 'test':
      return `${n === 1 ? 'One domain has' : `${n} domains have`} changes. Run the tests before saving a version.`;
    case 'version':
      return 'Tests pass. Save a version so colleagues can see exactly what you changed.';
    case 'try':
      return `Your version is saved. Try it in ${app.info.env} against the running app before landing it.`;
    default:
      return 'Your version is saved. Land it when you are ready.';
  }
});

async function load() {
  await space.open(spaceId);
  for (const t of space.tables) {
    api.readDmn(spaceId, t).then(({ content, etag }) => {
      seenVersions[t] = etag;
      try {
        decisionNames[t] = new DmnDocument(content).read().map((d) => d.name);
      } catch {
        decisionNames[t] = null;
      }
    }).catch(() => {});
    api.testProblems(spaceId, t).then((p) => { problems[t] = p; });
  }
}

const decisionsOf = (t) => {
  const names = decisionNames[t];
  if (names === null) return 'Not a readable domain';
  return names ? names.join(', ') : '';
};

onMounted(() => {
  load();
  if (spaceId) api.history(42).then((h) => { history.value = h; }).catch(() => {});
});

async function startAdding() {
  adding.value = true;
  newName.value = '';
  await nextTick();
  addField.value?.focus();
}

async function create() {
  if (!validName.value) return;
  const name = newName.value.trim();
  try {
    await api.createFile(spaceId, name, DmnDocument.blank(name));
    app.forgetVerdict(spaceId, name);
    app.say(`Added ${name} with one starter rule and a test case outline.`, 'clean');
    router.push(spacePath(spaceId, `/table/${name}`));
  } catch (error) {
    app.say(`${name} was not added: ${error.message}`, 'needs');
  }
}

async function remove(t) {
  confirming.value = null;
  try {
    await api.deleteFile(spaceId, t, seenVersions[t] ?? null);
    app.forgetVerdict(spaceId, t);
    app.say(spaceId ? `Removed ${t}. It is gone from this work space; the last saved version still has it until you save a new one.` : `Removed ${t} and its tests.`, 'plain');
    await load();
  } catch (error) {
    if (error.status === 412) {
      app.say(`${t} was not removed: someone changed it after this list was shown. Look at it first, then remove it if you still mean to.`, 'needs');
      await load();
      return;
    }
    app.say(`${t} was not removed: ${error.message}`, 'needs');
  }
}

async function deleteSpace() {
  try {
    await api.deleteSpace(spaceId);
    app.say(`Deleted the work space ${route.params.name}. Versions already landed are unaffected.`, 'plain');
    router.push('/');
  } catch (error) {
    app.say(`The work space was not deleted: ${error.message}`, 'needs');
  }
}

useBar(() => {
  const base = spacePath(spaceId);
  switch (space.current) {
    case 'test':
      return { primary: { label: 'Run the tests', run: () => router.push(`${base}/test`) } };
    case 'version':
      return { primary: { label: 'Review and save version', run: () => router.push(`${base}/version`) } };
    case 'try':
      return { primary: { label: `Try in ${app.info.env}`, run: () => router.push(`${base}/try`) } };
    case 'land':
      return { primary: { label: 'Land', run: () => router.push(`${base}/land`) } };
    default:
      if (!space.tables.length && !space.loading) return { primary: { label: 'Add a domain', run: startAdding } };
      return {};
  }
});
</script>

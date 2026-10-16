<template>
  <div class="page-body">
    <h1>Applications on {{ app.info.env }}</h1>
    <p class="lede">The applications this instance deploys rules to, and what each runs now — read from its topic. Open one to look at and try what runs, start a work space from it, or deploy.</p>
    <SyncLine class="mt-3" @changed="load" />

    <p v-if="error" class="notice notice-needs mt-7">{{ error }}</p>
    <p v-else-if="!apps" class="loading mt-7">Reading every application’s topic…</p>
    <div v-else-if="!apps.length" class="notice mt-7">
      <p><strong>This instance controls no applications yet.</strong> They come from its environment, one block per application:</p>
      <pre class="mono mt-2.5 whitespace-pre-wrap text-sm">dmn.deploy.applications.emea-street.bootstrap-servers=…
dmn.deploy.applications.emea-street.topic=…
dmn.deploy.applications.emea-street.files=pricing,fees</pre>
    </div>

    <div v-else class="rows mt-7">
      <router-link
        v-for="d in apps"
        :key="d.name"
        :to="`/applications/${d.name}`"
        class="app group grid min-h-16 grid-cols-[minmax(140px,0.9fr)_minmax(0,1.5fr)_minmax(0,1fr)_16px] items-center gap-x-5 gap-y-1 px-1 py-3 no-underline hover:bg-hover max-md:grid-cols-[1fr_16px]"
        :title="healthText(d)"
      >
        <span class="min-w-0">
          <span class="block truncate text-xl font-semibold leading-tight">{{ d.name }}</span>
          <span class="block truncate text-xs text-ink-3">reads {{ d.files.map((f) => (shared[f] > 1 ? `${f} (shared)` : f)).join(', ') }}</span>
        </span>
        <span class="flex flex-wrap items-center gap-x-2.5 gap-y-1 max-md:col-start-1">
          <template v-if="!d.ok"><span class="state state-needs">problem</span><span class="meta">topic cannot be read</span></template>
          <template v-else-if="!d.version"><span class="state state-quiet">nothing deployed</span></template>
          <template v-else-if="d.version.release">
            <LineBullet v-if="lineOf(d.version)" :line="lineOf(d.version)" :main-branch="app.info.mainBranch" size="sm" :title="lineName(lineOf(d.version), app.info.mainBranch)" />
            <span class="tag">{{ d.version.release }}</span>
            <span class="state" :class="health(d) === 'ok' ? 'state-clean' : 'state-needs'">{{ health(d) === 'ok' ? 'running' : 'problem' }}</span>
          </template>
          <template v-else>
            <LineBullet v-if="lineOf(d.version)" :line="lineOf(d.version)" :main-branch="app.info.mainBranch" size="sm" :title="lineName(lineOf(d.version), app.info.mainBranch)" />
            <span class="font-semibold">{{ branchName(d.version.workspace, app.info.mainBranch) }}</span>
            <span class="state" :class="health(d) === 'ok' ? 'state-live' : 'state-needs'">{{ health(d) === 'ok' ? 'draft live' : 'problem' }}</span>
          </template>
        </span>
        <span class="min-w-0 text-right max-md:col-start-1 max-md:text-left">
          <span class="block truncate font-mono text-xs text-ink-3">{{ d.server?.where }}</span>
          <span class="block truncate text-xs text-ink-3">{{ d.version ? `${d.version.publishedBy}, ${whenText(d.version.publishedAt)}` : '' }}</span>
        </span>
        <ChevronRight class="text-ink-3 group-hover:text-ink max-md:col-start-2 max-md:row-start-1" :size="16" :stroke-width="1.75" aria-hidden="true" />
      </router-link>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ChevronRight } from 'lucide-vue-next';
import LineBullet from '../components/LineBullet.vue';
import SyncLine from '../components/SyncLine.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { app, branchName, lineName, whenText } from '../store';

/* The home: every application this instance controls, and what it runs now. */
const router = useRouter();
const apps = ref(null);
const error = ref(null);

// A rule domain several applications read is shared: changing it reaches all of them.
const shared = computed(() => {
  const count = {};
  for (const d of apps.value || []) for (const f of d.files) count[f] = (count[f] || 0) + 1;
  return count;
});

const health = (d) => (!d.ok ? 'down' : d.problems?.length || !d.inStep ? 'warn' : 'ok');
const healthText = (d) => (!d.ok ? d.error : d.problems?.length ? d.problems.join('; ') : d.inStep ? 'in step' : 'its files run different versions — redeploy');

// The line a version runs on: release/M.m for a release vM.m.p; a draft runs on the line it lands in.
function lineOf(v) {
  if (!v) return null;
  if (v.line) return v.line;
  const tag = /^v(\d+)\.(\d+)/.exec(v.release || v.baseline || '');
  if (tag && (v.release || /^hotfix\//.test(v.workspace || ''))) return `release/${tag[1]}.${tag[2]}`;
  if (/^release\/|^main$/.test(v.baseline || '') || v.baseline === app.info.mainBranch) return v.baseline;
  return v.workspace ? app.info.mainBranch : null;
}

async function load() {
  try {
    apps.value = (await api.applications()).applications;
  } catch (e) {
    error.value = `The applications could not be read: ${e.message}`;
  }
}

onMounted(load);

useBar(() => (app.author
  ? { primary: { label: 'Go to work spaces', run: () => router.push('/work') } }
  : {}));
</script>

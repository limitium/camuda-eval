<template>
  <div class="page-body">
    <p v-if="loadError" class="notice notice-needs">{{ loadError }}</p>
    <p v-else-if="!info" class="loading">Opening {{ sha.slice(0, 12) }}…</p>

    <template v-else>
      <h1 class="text-xl">Version <span class="mono">{{ info.shortSha }}</span></h1>
      <p class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <span v-for="v in info.versions" :key="v" class="inline-flex items-center gap-1.5">
          <LineBullet v-if="lineOfTag(v)" :line="lineOfTag(v)" :main-branch="app.info.mainBranch" size="sm" :title="lineName(lineOfTag(v), app.info.mainBranch)" />
          <span class="tag">{{ v }}</span>
        </span>
        <span v-for="t in info.tips" :key="t" class="meta inline-flex items-center gap-1.5">
          <LineBullet v-if="isLine(t)" :line="t" :main-branch="app.info.mainBranch" size="sm" />
          newest on {{ tipName(t) }}
        </span>
        <span v-if="runningIn.length" class="state state-live">runs in {{ runningIn.join(', ') }}</span>
      </p>
      <p class="mt-3.5 max-w-[70ch] text-lg">“{{ plainMessage(info.message.split('\n')[0], app.info.mainBranch) }}”</p>
      <p class="meta mt-1">{{ info.author }} · <span class="text-xs text-ink-3">{{ whenText(info.when) }}</span> · <span class="mono text-ink-3">{{ info.sha }}</span></p>

      <section class="mt-9 grid gap-2">
        <h2 class="text-sm font-bold text-ink-2">Domains</h2>
        <p v-if="!info.domains.length" class="meta">This version holds no domain.</p>
        <div v-else class="rows max-w-[760px]">
          <router-link v-for="d in info.domains" :key="d" :to="`/commit/${info.sha}/${d}`" class="group flex min-h-12 items-center justify-between gap-3 px-1 py-3 no-underline hover:bg-hover">
            <span class="text-lg font-semibold">{{ d }}</span>
            <span class="inline-flex items-center gap-0.5 text-sm text-ink-2 group-hover:text-ink">Look and try <ChevronRight :size="16" :stroke-width="1.75" aria-hidden="true" /></span>
          </router-link>
        </div>
      </section>

      <TestReport v-if="report || testing || testError" class="mt-7 max-w-[760px]" :report="report" :busy="testing" :error="testError" :rev="info.versions[0] || info.shortSha" />
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ChevronRight } from 'lucide-vue-next';
import LineBullet from '../components/LineBullet.vue';
import TestReport from '../components/TestReport.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { app, lineName, plainMessage, spacePath, whenText } from '../store';

/*
 * Any commit, opened: what it is, what names it, and its domains — each to look at, change without
 * saving, and test exactly as it was. What an environment runs opens here too: a deploy names its commit.
 */
const route = useRoute();
const router = useRouter();
const sha = String(route.params.sha);
const info = ref(null);
const loadError = ref(null);
const report = ref(null);
const testing = ref(false);
const testError = ref(null);
const runningIn = ref([]);
const tipName = (branch) => (branch.split('/').length === 3 ? `${branch.split('/')[1]}’s space ${branch.split('/')[2]}` : lineName(branch, app.info.mainBranch));

const isLine = (branch) => branch === app.info.mainBranch || /^release\/\d+\.\d+$/.test(branch);
// release/M.m for a version vM.m.p.
const lineOfTag = (tag) => {
  const m = /^v(\d+)\.(\d+)/.exec(tag || '');
  return m ? `release/${m[1]}.${m[2]}` : null;
};

onMounted(async () => {
  try {
    info.value = await api.commit(sha);
  } catch (error) {
    loadError.value = error.status === 404 ? `There is no version ${sha} here. If it was made on another instance, it arrives with the next exchange.` : error.message;
    return;
  }
  api.applications().then((r) => {
    runningIn.value = r.applications.filter((d) => d.version?.commit === info.value.sha).map((d) => d.name);
  }).catch(() => {});
});

async function runTests() {
  testing.value = true;
  testError.value = null;
  try {
    report.value = await api.commitTests(info.value.sha);
  } catch (error) {
    testError.value = error.message;
  } finally {
    testing.value = false;
  }
}

async function startSpace() {
  try {
    const stamp = new Date().toISOString().slice(5, 16).replace(/[-T:]/g, '');
    const created = await api.createSpace(`from-${info.value.shortSha}-${stamp}`, info.value.versions[0] || info.value.sha);
    app.say(`Started ${created.name} on ${info.value.versions[0] || info.value.shortSha}.`, 'clean');
    router.push(spacePath(created.id));
  } catch (error) {
    app.say(`No space was started: ${error.message}`, 'needs');
  }
}

useBar(() => {
  const secondary = [{ label: 'Back', quiet: true, run: () => router.back() }];
  if (info.value && app.author) secondary.push({ label: info.value.versions.length ? `Start a fix to ${info.value.versions[0]}` : 'Start a work space from here', run: startSpace });
  return {
    secondary,
    primary: info.value?.domains.length ? { label: report.value ? 'Run every test again' : 'Run every domain’s tests', run: runTests, busy: testing.value } : null
  };
});
</script>

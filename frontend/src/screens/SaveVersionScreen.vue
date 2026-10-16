<template>
  <div class="page-body">
    <h1>Save a version</h1>
    <SavedState :space-id="ws" />
    <p class="lede">A version is what colleagues see, what can be tried in an environment, and what lands. This is exactly what changes since the last one.</p>

    <p v-if="space.loading && !space.changes.length" class="loading mt-7">Reading what changed…</p>
    <p v-if="!space.loading && !space.changes.length" class="notice mt-7">Nothing has changed since the last saved version{{ space.info?.lastMessage ? ` (“${space.info.lastMessage}”)` : '' }}.</p>

    <p v-if="untested.length" class="notice notice-needs mt-7">
      {{ untested.join(', ') }} {{ untested.length === 1 ? 'has' : 'have' }} not passed {{ untested.length === 1 ? 'its' : 'their' }} tests since the last change. You can still save; landing will refuse it until they pass.
      <router-link :to="spacePath(ws, '/test')" class="ml-1 font-semibold">Run the tests</router-link>
    </p>

    <section v-for="t in space.changedTables" :key="t" class="mt-10 grid gap-3.5">
      <header class="flex items-baseline gap-3.5 border-b border-rule pb-2">
        <h2 class="text-lg">{{ t }}</h2>
        <span class="state state-edit">{{ { new: 'New domain', removed: 'Removed', changed: 'Changed' }[space.changeOf(t)] }}</span>
      </header>
      <div v-if="!reviews[t]" class="loading text-sm">Comparing…</div>
      <template v-else>
        <p v-if="reviews[t].error" class="notice notice-needs">{{ reviews[t].error }}</p>
        <RuleDiff v-if="reviews[t].diff" :diff="reviews[t].diff" />
        <p v-if="reviews[t].newRules !== undefined" class="meta">{{ reviews[t].newRules }} {{ reviews[t].newRules === 1 ? 'rule' : 'rules' }} in {{ reviews[t].newTables.join(', ') }}.</p>
        <p v-if="reviews[t].tests" class="meta mt-0.5">Test cases: {{ reviews[t].tests }}</p>
      </template>
    </section>

    <div v-if="space.changes.length" class="mt-12 max-w-[720px]">
      <label class="label" for="version-message">What does this version change, and why?</label>
      <textarea id="version-message" v-model="message" class="field" rows="3" placeholder="for example: raise the EU discount above 500 to 12%, agreed with finance"></textarea>
      <p class="meta mt-2">Colleagues read this when their change meets yours, so say why, not just what.</p>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import RuleDiff from '../components/RuleDiff.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { DmnDocument } from '../lib/dmnModel';
import { space } from '../lib/space';
import SavedState from '../components/SavedState.vue';
import { caseKey, parseSpec } from '../lib/testSpec';
import { app, spaceIdFromRoute, spacePath } from '../store';

const route = useRoute();
const router = useRouter();
const ws = spaceIdFromRoute(route.params);
const reviews = reactive({});
const message = ref('');
const saving = ref(false);

const untested = computed(() => space.changedTables
  .filter((t) => space.changeOf(t) !== 'removed')
  .filter((t) => !app.verdictsFor(ws)[t]?.passed));

async function review(t) {
  const out = {};
  try {
    const change = space.changeOf(t);
    const [savedDmn, savedTests] = await Promise.all([
      api.savedContent(ws, `dmn/${t}.dmn`),
      api.savedContent(ws, `yaml/${t}.yaml`)
    ]);
    if (change === 'removed') {
      out.diff = null;
    } else {
      const [now, nowTests] = await Promise.all([api.readDmn(ws, t), api.readTests(ws, t)]);
      if (savedDmn === null) {
        const decisions = new DmnDocument(now.content).read();
        out.newRules = decisions.reduce((n, d) => n + d.rules.length, 0);
        out.newTables = decisions.map((d) => d.name);
      } else {
        out.diff = await api.diff(savedDmn, now.content, 'last version', 'now');
      }
      out.tests = describeTests(savedTests, nowTests.content);
    }
  } catch (error) {
    out.error = `Could not compare ${t}: ${error.message}`;
  }
  reviews[t] = out;
}

function describeTests(before, after) {
  const keys = (text) => {
    const map = new Map();
    try {
      for (const b of parseSpec(text || '')) b.rows.forEach((row) => map.set(caseKey(b, row), JSON.stringify(row)));
    } catch {
      return null;
    }
    return map;
  };
  const a = keys(before);
  const b = keys(after);
  if (!b) return 'the test file cannot be read';
  if (!a) return `${b.size} cases`;
  const added = [...b.keys()].filter((k) => !a.has(k)).length;
  const removed = [...a.keys()].filter((k) => !b.has(k)).length;
  const changed = [...b.keys()].filter((k) => a.has(k) && a.get(k) !== b.get(k)).length;
  const parts = [added && `${added} added`, changed && `${changed} changed`, removed && `${removed} removed`].filter(Boolean);
  return parts.length ? parts.join(', ') : 'unchanged';
}

onMounted(async () => {
  await space.open(ws);
  space.changedTables.forEach(review);
});

async function saveVersion() {
  if (!message.value.trim() || saving.value) return;
  saving.value = true;
  try {
    const greenBefore = space.testedOk;
    const result = await api.saveVersion(ws, message.value.trim());
    // Every changed table was green: the version saved from exactly those files is green too.
    if (greenBefore) space.seedSaved(result.commit, true);
    if (result.notSent) {
      app.say(`Saved version ${result.shortCommit}: “${message.value.trim()}”. Colleagues on ${app.info.env} can see it; other instances cannot yet — ${result.notSent}.`, 'needs');
    } else {
      app.say(`Saved version ${result.shortCommit}: “${message.value.trim()}”. Colleagues can see it now, on every instance.`, 'clean');
    }
    message.value = '';
    await space.refresh();
    router.push(spacePath(ws, space.tryAvailable ? '/try' : '/land'));
  } catch (error) {
    if (error.body?.code === 'BEHIND') {
      // Every git action starts from the newest saved version: bring it in first, then save.
      app.say(`Not saved yet: ${error.message}.`, 'needs');
      await space.open(ws);
      router.push(spacePath(ws, '/refresh'));
    } else {
      app.say(`The version was not saved: ${error.message}. Your changes are untouched.`, 'needs');
    }
  } finally {
    saving.value = false;
  }
}

useBar(() => ({
  secondary: [{ label: 'Back to the work space', quiet: true, run: () => router.push(spacePath(ws)) }],
  primary: space.changes.length
    ? { label: 'Save version', run: saveVersion, busy: saving.value, disabled: !message.value.trim() }
    : null
}));
</script>

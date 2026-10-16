<template>
  <div class="page-body" :class="{ wide: result?.status === 'CONFLICTS' }">
    <h1 class="flex flex-wrap items-center gap-3">
      <LineBullet v-if="!refreshing && isLine" :line="from" :main-branch="app.info.mainBranch" size="lg" />
      <span>{{ refreshing ? 'Refresh onto the newest saved version' : `Bring ${sourceName} in` }}</span>
    </h1>
    <p v-if="refreshing" class="lede">Someone saved this work space after you started changing it here. Your unsaved changes are combined with their saved version: what only one changed is kept, anything both changed is asked. Afterwards your changes are still unsaved.</p>
    <p v-else class="lede">{{ capital(sourceName) }} is combined with this work space’s last saved version. What only one changed is taken; anything both changed is asked. The work space moves only when you say so.</p>

    <p v-if="space.changes.length && !refreshing" class="notice notice-needs mt-7">This work space has unsaved changes. Save a version or undo them first, so nothing of yours is mixed in unnoticed.</p>
    <p v-else-if="error" class="notice notice-needs mt-7">{{ error }}</p>
    <p v-else-if="!result" class="loading mt-7">Combining {{ sourceName }} with this work space…</p>

    <template v-else>
      <p v-if="result.status === 'NOTHING_TO_PULL'" class="notice mt-7">This work space already has everything in {{ sourceName }}.</p>
      <p v-else-if="result.status === 'PULLED'" class="notice notice-clean mt-7">{{ capital(sourceName) }} is in this work space now, as a saved version.</p>
      <p v-else-if="result.status === 'REFRESHED' || result.status === 'UP_TO_DATE'" class="notice notice-clean mt-7">This work space is at its newest saved version{{ refreshing && result.status === 'REFRESHED' ? ', with your changes still there, unsaved' : '' }}.</p>
      <p v-else-if="result.status === 'MOVED'" class="notice notice-needs mt-7">{{ result.message }}</p>
      <template v-else-if="result.status === 'CLEAN'">
        <p class="notice notice-clean mt-7">Nothing disagrees. {{ refreshing ? 'Your changes and the saved version combine cleanly.' : changedText }}</p>
        <TestReport v-if="hop?.tests" class="mt-3.5" :report="hop.tests" rev="the combined version" />
        <p v-if="hop?.tests && !hop.tests.passed" class="meta mt-3.5">Failing tests do not stop bringing changes in — fix them in the work space before landing.</p>
      </template>

      <template v-if="result.status === 'CONFLICTS'">
        <p class="mt-7">
          <strong class="font-bold">{{ conflicts.length }} {{ conflicts.length === 1 ? 'thing needs' : 'things need' }} a decision</strong>
          <span class="meta"> — {{ answered }} answered.</span>
        </p>
        <Resolver
          class="mt-3.5"
          :result="result"
          :resolutions="answers"
          :main-branch="app.info.mainBranch"
          :space-name="refreshing ? 'your unsaved changes' : 'this work space'"
          :ours-all="refreshing ? 'Keep all of mine' : 'Keep this work space’s'"
          :theirs-all="refreshing ? 'Take all of the saved version' : `Take all of ${sourceName}`"
          :theirs-name="refreshing ? 'the saved version' : sourceName"
          @choose="choose"
        />
      </template>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import LineBullet from '../components/LineBullet.vue';
import Resolver from '../components/Resolver.vue';
import TestReport from '../components/TestReport.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { space } from '../lib/space';
import { app, branchName, spaceIdFromRoute, spacePath } from '../store';

/*
 * Bringing a line, a version or another space (any instance) into this work space: the same combining
 * and the same answers as landing, and only this work space moves.
 */
const route = useRoute();
const router = useRouter();
const ws = spaceIdFromRoute(route.params);
const from = String(route.query.from || '');
const refreshing = !!route.meta.refresh;
const result = ref(null);
const error = ref(null);
const busy = ref(false);
const stale = ref(false);
const answers = reactive({});

const capital = (s) => (/^(main|master|release\/|feature\/|hotfix\/)/.test(s) ? s : s.charAt(0).toUpperCase() + s.slice(1));
const isLine = computed(() => from === app.info.mainBranch || /^release\//.test(from));
const sourceName = computed(() => branchName(from, app.info.mainBranch));
const hop = computed(() => result.value?.hops?.[0] || null);
const conflicts = computed(() => hop.value?.conflicts || []);
const answered = computed(() => conflicts.value.filter((c) => answers[c.id]).length);
const changedText = computed(() => {
  const changed = (hop.value?.domains || []).filter((d) => d.change !== 'unchanged').map((d) => d.name);
  return changed.length ? `It changes ${changed.join(', ')}.` : '';
});

function choose(id, key) {
  answers[id] = key;
  stale.value = true;
}

async function compute(apply = false) {
  busy.value = true;
  error.value = null;
  try {
    const r = refreshing
      ? await api.refresh(ws, { resolutions: { ...answers }, apply })
      : await api.pull(ws, from, { resolutions: { ...answers }, head: apply ? hop.value?.ours : null, apply });
    result.value = r;
    stale.value = false;
    if (r.status === 'PULLED' || r.status === 'REFRESHED') {
      app.say(`${capital(sourceName.value)} is in this work space now.`, 'clean');
      space.open(ws);
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  await space.open(ws);
  if (refreshing || !space.changes.length) compute(false);
});

useBar(() => {
  const secondary = [{ label: 'Back to the work space', quiet: true, run: () => router.push(spacePath(ws)) }];
  const r = result.value;
  if (!r || (space.changes.length && !refreshing)) return { secondary };
  if (['PULLED', 'NOTHING_TO_PULL', 'REFRESHED', 'UP_TO_DATE'].includes(r.status)) return { secondary: [], primary: { label: 'Back to the work space', run: () => router.push(spacePath(ws)) } };
  if (r.status === 'CONFLICTS') {
    const left = conflicts.value.length - answered.value;
    return { secondary, hint: left ? `${left} still to decide.` : null, primary: { label: 'Check with these answers', run: () => compute(false), busy: busy.value, disabled: left > 0 } };
  }
  if (r.status === 'CLEAN') return { secondary, primary: { label: stale.value ? 'Check again' : refreshing ? 'Refresh' : `Bring ${sourceName.value} in`, run: () => compute(!stale.value), busy: busy.value } };
  return { secondary, primary: { label: 'Check again', run: () => compute(false), busy: busy.value } };
});
</script>

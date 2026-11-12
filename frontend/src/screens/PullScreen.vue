<template>
  <div class="page-body" :class="{ wide: result?.status === 'CONFLICTS' }">
    <h1 class="flex flex-wrap items-center gap-3">
      <LineBullet v-if="!refreshing && isLine" :line="from" :main-branch="app.info.mainBranch" size="lg" />
      <span>{{ refreshing ? 'Pull the newest commit' : `Merge from ${from}` }}</span>
    </h1>
    <p v-if="refreshing" class="lede">Someone committed to {{ ws }} after you started changing it here. Pulling merges your uncommitted changes over their commit: what only one side changed is kept; anything both changed is a conflict you decide. Afterwards your changes are still uncommitted.</p>
    <p v-else class="lede">{{ from }} is merged with this work space’s last commit. What only one side changed is taken; anything both changed is a conflict you decide. The work space moves only when you say so.</p>

    <p v-if="space.changes.length && !refreshing" class="notice notice-needs mt-7">This work space has uncommitted changes. Commit or undo them first, so nothing of yours is mixed in unnoticed.</p>
    <p v-else-if="error" class="notice notice-needs mt-7">{{ error }}</p>
    <p v-else-if="!result" class="loading mt-7">{{ refreshing ? 'Merging your uncommitted changes over the newest commit…' : `Merging ${from} into this work space…` }}</p>

    <template v-else>
      <p v-if="result.status === 'NOTHING_TO_PULL'" class="notice mt-7">This work space already has everything in {{ from }}.</p>
      <p v-else-if="result.status === 'PULLED'" class="notice notice-clean mt-7">Merged {{ from }} into this work space, as a merge commit.</p>
      <p v-else-if="result.status === 'REFRESHED' || result.status === 'UP_TO_DATE'" class="notice notice-clean mt-7">This work space is at its newest commit{{ refreshing && result.status === 'REFRESHED' ? ', with your changes still there, uncommitted' : '' }}.</p>
      <p v-else-if="result.status === 'MOVED'" class="notice notice-needs mt-7">{{ result.message }}</p>
      <template v-else-if="result.status === 'CLEAN'">
        <p class="notice notice-clean mt-7">No conflicts. {{ refreshing ? 'Your changes merge cleanly over the newest commit.' : changedText }}</p>
        <TestReport v-if="hop?.tests" class="mt-3.5" :report="hop.tests" rev="the merge" />
        <p v-if="hop?.tests && !hop.tests.passed" class="meta mt-3.5">Failing tests do not stop this merge — fix them in the work space before you merge it onward.</p>
      </template>

      <template v-if="result.status === 'CONFLICTS'">
        <p class="mt-7">
          <strong class="font-bold">{{ conflicts.length }} {{ conflicts.length === 1 ? 'conflict needs' : 'conflicts need' }} a decision</strong>
          <span class="meta"> — {{ answered }} answered.</span>
        </p>
        <Resolver
          class="mt-3.5"
          :result="result"
          :resolutions="answers"
          :main-branch="app.info.mainBranch"
          :space-name="refreshing ? 'your uncommitted changes' : 'this work space'"
          :ours-all="refreshing ? 'Keep all of mine' : 'Keep this work space’s'"
          :theirs-all="refreshing ? 'Take all of the newest commit' : `Take all of ${from}`"
          :theirs-name="refreshing ? 'the newest commit' : from"
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
import { app, spaceIdFromRoute, spacePath } from '../store';

/*
 * Merging main, a release branch, a tag or another work space (any instance) into this work space —
 * or pulling its own newest commit over uncommitted changes: the same merge and the same answers as
 * merging onward, and only this work space moves.
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

const isLine = computed(() => from === app.info.mainBranch || /^release\//.test(from));
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
      app.say(refreshing ? `Pulled the newest commit of ${ws}.` : `Merged ${from} into ${ws}.`, 'clean');
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
  if (r.status === 'CLEAN') return { secondary, primary: { label: stale.value ? 'Check again' : refreshing ? 'Pull' : `Merge from ${from}`, run: () => compute(!stale.value), busy: busy.value } };
  return { secondary, primary: { label: 'Check again', run: () => compute(false), busy: busy.value } };
});
</script>

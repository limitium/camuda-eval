<template>
  <!-- The picked line: like a picked row, selected — not a card. -->
  <div class="flex flex-wrap items-center gap-x-3 gap-y-1.5 rounded bg-select px-3 py-2.5 ring-1 ring-ink" role="status">
    <span class="flex items-center gap-2 text-lg font-bold"><LineBullet :line="line" :main-branch="mainBranch" />{{ title }}</span>
    <span class="text-sm text-ink-2">{{ line === mainBranch ? 'new work lands here; the next release is cut from it' : 'where released versions get fixes; each fix is carried forward to main' }}</span>

    <p v-if="!info" class="loading m-0 basis-full text-sm">Reading the line…</p>
    <template v-else>
      <p class="m-0 flex basis-full flex-wrap items-center gap-1 text-sm text-ink-2">
        <template v-if="info.versions.length">Versions: <span v-for="v in info.versions.slice(0, 6)" :key="v.name" class="tag mono">{{ v.name }}</span></template>
        <template v-else>No version released from it yet.</template>
        <template v-if="info.unreleased"> · {{ info.unreleased }} {{ info.unreleased === 1 ? 'change' : 'changes' }} not released yet</template>
      </p>
      <p v-if="line !== mainBranch" class="m-0 basis-full text-sm text-ink-2">{{ closeText }}</p>
    </template>

    <div class="flex basis-full flex-wrap gap-1.5 pt-1">
      <router-link :to="`/work/new?from=${encodeURIComponent(line)}`" class="btn btn-small">Start a work space from it</router-link>
      <router-link v-if="info?.unreleased && info.nextVersion" to="/releases" class="btn btn-quiet btn-small">Tag {{ info.nextVersion }}</router-link>
      <button v-if="closable" type="button" class="btn btn-quiet btn-small" :aria-busy="closing" :disabled="closing" @click="close">{{ closing ? 'Closing…' : `Close ${title}` }}</button>
      <button type="button" class="btn btn-quiet btn-small" @click="$emit('clear')">Clear</button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import LineBullet from './LineBullet.vue';
import { api } from '../lib/api';
import { app, branchName } from '../store';

/* One line picked in the graph: its versions, what is unreleased, and what can be done with it — start from it, release it, close it. */
const props = defineProps({
  line: { type: String, required: true },
  mainBranch: { type: String, default: 'main' }
});
const emit = defineEmits(['clear', 'closed']);

const info = ref(null);
const item = ref(undefined); // the clean-up verdict for this line; null when it is not offered
const closing = ref(false);

const title = computed(() => {
  const name = branchName(props.line, props.mainBranch);
  return name.charAt(0).toUpperCase() + name.slice(1);
});
const closable = computed(() => item.value?.action === 'CLOSE');
const closeText = computed(() => {
  if (item.value === undefined) return 'Checking whether it can be closed…';
  if (closable.value) return 'Everything on it is released, so it can be closed. Its versions stay deployable, and a fix to one of them opens it again.';
  if (item.value?.reason === 'OPEN_SPACE_LANDS_HERE') return `It stays open: ${item.value.detail.map((id) => branchName(id, props.mainBranch)).join(', ')} will land in it.`;
  if (info.value?.unreleased) return 'It stays open until its newest changes are released as a version.';
  return 'It stays open: a work space will land in it.';
});

async function load() {
  info.value = null;
  item.value = undefined;
  try {
    const lines = await api.lines();
    info.value = lines.find((l) => l.branch === props.line) || { versions: [], unreleased: 0 };
  } catch {
    info.value = { versions: [], unreleased: 0 };
  }
  if (props.line === props.mainBranch) return;
  try {
    const plan = (await api.cleanupPlan()).plan;
    item.value = plan.items.find((i) => i.id === props.line) || null;
  } catch {
    item.value = null;
  }
}

async function close() {
  closing.value = true;
  try {
    await api.cleanupApply({ [props.line]: item.value.head });
    app.say(`Closed ${title.value}. Its versions are still there to deploy; fixing one opens it again.`, 'clean');
    emit('closed');
  } catch (e) {
    app.say(`${title.value} was not closed: ${e.message}`, 'needs');
    load();
  } finally {
    closing.value = false;
  }
}

watch(() => props.line, load, { immediate: true });
</script>

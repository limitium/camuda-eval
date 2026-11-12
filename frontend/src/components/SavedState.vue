<template>
  <div
    v-if="saved"
    class="mt-3 flex max-w-[80ch] flex-wrap items-center gap-x-4 gap-y-2"
    :class="tone === 'needs' ? 'notice notice-needs' : tone === 'live' ? 'notice notice-live' : ''"
    role="status"
  >
    <template v-if="state === 'BEHIND' || state === 'BEHIND_UNSAVED'">
      <p class="flex-[1_1_40ch]">
        <strong>Newer changes to this work space were committed</strong> by {{ saved.savedBy }}{{ saved.savedOn ? ` on ${saved.savedOn}` : '' }} {{ whenText(saved.savedAt) }} — “{{ saved.savedMessage }}”.
        <template v-if="state === 'BEHIND_UNSAVED'">Your uncommitted changes here are merged over them when you pull, and stay uncommitted.</template>
        <template v-else>Pull to continue from them.</template>
      </p>
      <button type="button" class="btn btn-small" :aria-busy="busy" :disabled="busy" @click="refresh">{{ busy ? 'Pulling…' : 'Pull' }}</button>
    </template>
    <p v-else class="flex-[1_1_40ch] text-sm text-ink-2">
      <span :class="state === 'UNSAVED' ? 'state state-edit' : 'state state-clean'">{{ state === 'UNSAVED' ? 'Uncommitted changes here.' : 'Committed.' }}</span>
      Last committed by {{ saved.savedBy }}{{ saved.savedOn ? ` on ${saved.savedOn}` : '' }} {{ whenText(saved.savedAt) }}. A work space’s commits are shared by every instance.
    </p>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../lib/api';
import { space } from '../lib/space';
import { app, spacePath, whenText } from '../store';

/*
 * Where this checkout stands against the work space's commits (its branch): committed, uncommitted
 * changes here, or behind a commit made elsewhere — with the way back in step.
 */
const props = defineProps({ spaceId: { type: String, required: true } });
const router = useRouter();
const busy = ref(false);
const saved = computed(() => space.info?.saved || null);
const state = computed(() => saved.value?.state);
const tone = computed(() => (state.value === 'BEHIND_UNSAVED' ? 'needs' : state.value === 'BEHIND' ? 'live' : 'quiet'));

async function refresh() {
  if (state.value === 'BEHIND_UNSAVED') {
    router.push(spacePath(props.spaceId, '/refresh'));
    return;
  }
  busy.value = true;
  try {
    const r = await api.refresh(props.spaceId, { apply: true });
    if (r.status === 'REFRESHED' || r.status === 'UP_TO_DATE') app.say('This work space is at its newest commit.', 'clean');
    else app.say(r.message || 'Look again.', 'needs');
    await space.open(props.spaceId);
  } catch (error) {
    app.say(`Not pulled: ${error.message}`, 'needs');
  } finally {
    busy.value = false;
  }
}
</script>

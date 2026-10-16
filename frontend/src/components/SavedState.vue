<template>
  <div
    v-if="saved"
    class="mt-3 flex max-w-[80ch] flex-wrap items-center gap-x-4 gap-y-2"
    :class="tone === 'needs' ? 'notice notice-needs' : tone === 'live' ? 'notice notice-live' : ''"
    role="status"
  >
    <template v-if="state === 'BEHIND' || state === 'BEHIND_UNSAVED'">
      <p class="flex-[1_1_40ch]">
        <strong>A newer version of this work space was saved</strong> by {{ saved.savedBy }}{{ saved.savedOn ? ` on ${saved.savedOn}` : '' }} {{ whenText(saved.savedAt) }} — “{{ saved.savedMessage }}”.
        <template v-if="state === 'BEHIND_UNSAVED'">Your unsaved changes here are merged over it when you refresh, and stay unsaved.</template>
        <template v-else>Refresh to continue from it.</template>
      </p>
      <button type="button" class="btn btn-small" :aria-busy="busy" :disabled="busy" @click="refresh">{{ busy ? 'Refreshing…' : 'Refresh' }}</button>
    </template>
    <p v-else class="flex-[1_1_40ch] text-sm text-ink-2">
      <span :class="state === 'UNSAVED' ? 'state state-edit' : 'state state-clean'">{{ state === 'UNSAVED' ? 'Unsaved changes here.' : 'Saved.' }}</span>
      Last saved by {{ saved.savedBy }}{{ saved.savedOn ? ` on ${saved.savedOn}` : '' }} {{ whenText(saved.savedAt) }}. A work space has one saved version, shared by every instance.
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
 * Where this checkout stands against the work space's one saved version (its branch): saved, unsaved
 * changes here, or behind a version saved elsewhere — with the way back in step.
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
    if (r.status === 'REFRESHED' || r.status === 'UP_TO_DATE') app.say('This work space is at its newest saved version.', 'clean');
    else app.say(r.message || 'Look again.', 'needs');
    await space.open(props.spaceId);
  } catch (error) {
    app.say(`Not refreshed: ${error.message}`, 'needs');
  } finally {
    busy.value = false;
  }
}
</script>

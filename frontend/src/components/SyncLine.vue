<template>
  <div
    v-if="status?.enabled"
    class="flex flex-wrap items-center gap-x-2.5 gap-y-1.5 text-sm"
    :class="trouble ? 'max-w-[80ch] rounded border border-[oklch(85%_0.06_25)] bg-needs-tint px-3 py-2 text-ink' : 'text-ink-2'"
    role="status"
  >
    <RefreshCw :size="14" :stroke-width="1.75" aria-hidden="true" class="flex-none" :class="{ 'animate-turn': busy }" />
    <span v-if="!last">Not yet in touch with the other instances.</span>
    <span v-else-if="!last.ok">The other instances could not be reached {{ whenText(last.at) }}: {{ last.error }}. Work here is kept and sent when they can.</span>
    <span v-else-if="trouble">{{ troubleText }}</span>
    <span v-else>In step with the other instances · {{ whenText(last.at) }}{{ last.takenIn.length ? ` · took in ${last.takenIn.length} from them` : '' }}</span>
    <button type="button" class="btn btn-quiet btn-small" :aria-busy="busy" :disabled="busy" @click="now">Check now</button>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { RefreshCw } from 'lucide-vue-next';
import { api } from '../lib/api';
import { app, whenText } from '../store';

/*
 * How this instance stands with the others. They meet only in the remote repository, so this says
 * when it last exchanged versions with it, and names anything that could not be exchanged.
 */
const emit = defineEmits(['changed']);
const status = ref(null);
const busy = ref(false);
const last = computed(() => status.value?.last || null);
const notSent = computed(() => Object.entries(status.value?.notSent || {}));
const trouble = computed(() => !!last.value && (!last.value.ok || notSent.value.length > 0 || Object.keys(last.value.diverged || {}).length > 0));
const troubleText = computed(() => {
  const diverged = Object.keys(last.value?.diverged || {});
  if (diverged.length) return `${diverged.join(', ')}: saved here and on another instance too. Both versions are kept; they need joining before either lands.`;
  return notSent.value.map(([branch, why]) => `${branch} has not reached the other instances: ${why}`).join(' · ');
});

async function load() {
  try {
    status.value = await api.sync();
  } catch {
    status.value = null;
  }
}

async function now() {
  busy.value = true;
  try {
    status.value = await api.syncNow();
    if (status.value.last?.takenIn?.length) emit('changed');
    else if (status.value.last?.ok) app.say('Checked: nothing new from the other instances.');
  } catch (error) {
    app.say(`Could not check: ${error.message}`, 'needs');
  } finally {
    busy.value = false;
  }
}

let timer = null;
onMounted(() => {
  load();
  timer = setInterval(load, 30000);
});
onBeforeUnmount(() => clearInterval(timer));
</script>

<template>
  <section class="grid gap-2" aria-label="Bring in changes">
    <h2 class="text-sm font-bold text-ink-2">Bring in changes</h2>
    <p class="max-w-[72ch] text-sm text-ink-2">Take what moved on a line, or a colleague’s work space from any instance, into this one. Pick it in the graph; anything both changed is asked, as when landing.</p>
    <p v-if="!loaded" class="loading text-sm">Reading the lines and work spaces…</p>
    <div class="flex flex-wrap items-center gap-2">
      <select v-if="others.length" v-model="otherPick" class="field max-w-[460px]" aria-label="Another work space">
        <option value="">or another work space…</option>
        <option v-for="s in others" :key="s.id" :value="s.id">{{ branchName(s.id, mainBranch) }}{{ s.savedOn ? ` (saved on ${s.savedOn})` : '' }}{{ s.lastMessage ? ` — “${s.lastMessage}”` : '' }}</option>
      </select>
      <router-link v-if="from" class="btn" :to="spacePath(spaceId, `/pull?from=${encodeURIComponent(from)}`)"><LineBullet v-if="from === mainBranch || from.startsWith('release/')" :line="from" :main-branch="mainBranch" size="sm" />Bring {{ branchName(from, mainBranch) }} in</router-link>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import LineBullet from './LineBullet.vue';
import { api } from '../lib/api';
import { branchName, spacePath } from '../store';

/*
 * The controls for choosing what to bring into a work space. The pick itself happens in the screen's
 * one graph: this says which branches can be picked ('pickable') and holds what was picked (v-model:from).
 */
const props = defineProps({
  spaceId: { type: String, required: true },
  target: { type: String, default: 'main' },
  mainBranch: { type: String, default: 'main' },
  from: { type: String, default: '' }
});
const emit = defineEmits(['update:from', 'pickable']);
const lines = ref([]);
const others = ref([]);
const loaded = ref(false);
const otherPick = ref('');
watch(otherPick, (v) => { if (v) emit('update:from', v); });

const pickable = computed(() => [props.mainBranch, ...lines.value, ...others.value.map((s) => s.id)]);
watch(pickable, (p) => emit('pickable', p), { immediate: true });

onMounted(async () => {
  try {
    const [l, mine, elsewhere] = await Promise.all([api.lines(), api.spaces(), api.spacesElsewhere()]);
    lines.value = l.filter((x) => !x.main).map((x) => x.branch);
    others.value = [...mine, ...elsewhere].filter((s) => s.id !== props.spaceId);
  } catch {
    // nothing more to pick than the main line
  } finally {
    loaded.value = true;
  }
});
</script>

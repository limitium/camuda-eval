<template>
  <Teleport to="body">
    <div class="backdrop fixed inset-0 right-[min(360px,100vw)] z-40" @mousedown.self="$emit('close')"></div>
    <form
      ref="panel"
      class="editor fixed top-0 right-0 bottom-0 z-[41] flex w-[min(360px,100vw)] flex-col gap-1 overflow-auto border-l border-rule-strong bg-ground px-[18px] py-4 text-left text-md font-normal shadow-[-12px_0_32px_-16px_oklch(20%_0.02_260/0.25)]"
      @submit.prevent="save"
      @keydown.esc.stop="$emit('close')"
    >
      <header class="mb-2 flex items-center justify-between gap-2">
        <h3 class="truncate">{{ side === 'in' ? 'Condition' : 'Result' }}: {{ column?.label || (side === 'in' ? column?.expression : column?.name) || 'new' }}</h3>
        <button type="button" class="btn btn-quiet btn-small close" aria-label="Close" @click="$emit('close')"><X :size="16" :stroke-width="1.75" /></button>
      </header>
      <label class="label mt-0 mb-0.5" :for="ids.label">Heading</label>
      <input :id="ids.label" ref="first" v-model="draft.label" class="field h-8" placeholder="shown above the column" />

      <label class="label mt-2 mb-0.5" :for="ids.key">{{ side === 'in' ? 'Checks the value' : 'Result name' }}</label>
      <input :id="ids.key" v-model="draft.key" class="field mono h-8" :placeholder="side === 'in' ? 'e.g. amount' : 'e.g. discount'" spellcheck="false" autocomplete="off" />
      <div v-if="side === 'in' && sources.length" class="mt-1 flex flex-wrap gap-1.5">
        <p class="w-full text-xs text-ink-2">or use another table’s result:</p>
        <button
          v-for="s in sources"
          :key="`${s.from}|${s.name}`"
          type="button"
          class="source btn-option font-normal"
          :class="{ 'picked on': draft.key === s.name }"
          @click="pick(s)"
        ><span class="mono">{{ s.name }}</span> <span class="text-ink-2">from {{ s.fromName }}</span></button>
      </div>
      <p class="hint text-xs text-ink-2">{{ side === 'in'
        ? (pickedSource ? `Connected to ${pickedSource.fromName}: this condition checks its result.` : 'Tests supply this value under this name. A name another table gives connects to that table.')
        : 'Tests expect it, and other tables read it, under this name.' }}</p>

      <label class="label mt-2 mb-0.5" :for="ids.type">Kind of value</label>
      <select :id="ids.type" v-model="draft.typeRef" class="field h-8">
        <option v-for="t in TYPES" :key="t" :value="t">{{ t }}</option>
      </select>

      <div class="mt-auto flex items-center gap-1.5 pt-4">
        <button v-if="canRemove" type="button" class="btn btn-small btn-danger" @click="$emit('remove')">Remove column</button>
        <span class="flex-1"></span>
        <button type="button" class="btn btn-small btn-quiet" @click="$emit('close')">Cancel</button>
        <button type="submit" class="btn btn-small" :disabled="!draft.key.trim()">Apply</button>
      </div>
    </form>
  </Teleport>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { X } from 'lucide-vue-next';
import { TYPES } from '../lib/dmnModel';

/*
 * Editing one column's heading, the value it checks or the result it gives — always in the same place,
 * a panel docked on the right, so it is never cut off and never lands somewhere unexpected; the column
 * being edited stays marked in the grid.
 */
const props = defineProps({
  side: { type: String, required: true },
  column: { type: Object, default: null },
  canRemove: { type: Boolean, default: false },
  // Results of other tables that this condition could check: [{ name, typeRef, from, fromName }]
  sources: { type: Array, default: () => [] }
});
const emit = defineEmits(['save', 'remove', 'close']);

const uid = Math.random().toString(36).slice(2, 7);
const ids = { label: `lbl-${uid}`, key: `key-${uid}`, type: `typ-${uid}` };
const first = ref(null);
const panel = ref(null);
const draft = reactive({
  label: props.column?.label || '',
  key: (props.side === 'in' ? props.column?.expression : props.column?.name) || '',
  typeRef: props.column?.typeRef || 'string'
});
const pickedSource = computed(() => props.sources.find((s) => s.name === draft.key.trim()) || null);

function pick(s) {
  draft.key = s.name;
  if (s.typeRef) draft.typeRef = s.typeRef;
  if (!draft.label) draft.label = s.label || s.name;
}

// While the panel is open the page makes room for it, so it never sits on the column being edited.
onMounted(() => {
  document.body.classList.add('column-panel-open');
  first.value?.focus();
});
onBeforeUnmount(() => document.body.classList.remove('column-panel-open'));

function save() {
  if (!draft.key.trim()) return;
  const key = draft.key.trim();
  emit('save', props.side === 'in'
    ? { label: draft.label.trim(), expression: key, typeRef: draft.typeRef }
    : { label: draft.label.trim(), name: key, typeRef: draft.typeRef });
}
</script>

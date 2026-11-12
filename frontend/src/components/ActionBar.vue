<template>
  <footer class="relative flex min-h-[var(--bar)] items-center justify-between gap-4 border-t border-rule bg-ground px-[var(--gutter)] py-2.5 max-md:flex-col max-md:items-stretch max-md:gap-2">
    <div class="relative min-w-0 flex-1" aria-live="polite">
      <span v-if="bar.hint && last?.tone !== 'needs' && !fresh" class="flex px-1.5 py-1 text-ink-2">{{ bar.hint }}</span>
      <button
        v-else-if="last"
        type="button"
        class="-ml-1.5 flex max-w-full items-center gap-2.5 rounded px-1.5 py-1 text-left hover:bg-hover"
        :aria-expanded="open"
        @click="open = !open"
      >
        <span class="size-2 flex-none rounded-full" :class="dotOf(last.tone)" aria-hidden="true"></span>
        <span class="truncate" :class="last.tone === 'needs' ? 'font-semibold text-needs-ink' : ''">{{ last.text }}</span>
        <span class="flex-none text-xs text-ink-3">{{ time(last.at) }}</span>
      </button>
      <span v-else class="flex px-1.5 py-1 text-ink-3">Nothing has happened yet in this session.</span>
      <ol
        v-if="open && app.transcript.length > 1"
        class="absolute bottom-[calc(100%+14px)] -left-1.5 m-0 max-h-[50vh] w-[min(640px,90vw)] list-none overflow-auto rounded border border-rule-strong bg-ground py-1.5 shadow-[0_8px_24px_-8px_oklch(20%_0.02_260/0.18)]"
        aria-label="Earlier"
      >
        <li v-for="(entry, i) in app.transcript.slice(1)" :key="i" class="flex items-center gap-2.5 px-3 py-1.5 text-sm">
          <span class="size-2 flex-none rounded-full" :class="dotOf(entry.tone)" aria-hidden="true"></span>
          <span :class="entry.tone === 'needs' ? 'text-needs-ink' : ''">{{ entry.text }}</span>
          <span class="ml-auto flex-none text-xs text-ink-3">{{ time(entry.at) }}</span>
        </li>
      </ol>
    </div>
    <div class="flex flex-none items-center gap-2 max-md:flex-wrap max-md:justify-end">
      <button
        v-for="(action, i) in bar.secondary"
        :key="i"
        type="button"
        class="btn"
        :class="{ 'btn-danger': action.danger, 'btn-quiet': action.quiet }"
        :disabled="action.disabled || bar.running === action"
        :aria-busy="bar.running === action ? 'true' : undefined"
        @click="bar.press(action)"
      >{{ action.label }}</button>
      <button
        v-if="bar.primary"
        type="button"
        class="btn btn-next"
        :disabled="bar.primary.disabled || bar.primary.busy || bar.running === bar.primary"
        :aria-busy="bar.primary.busy || bar.running === bar.primary ? 'true' : undefined"
        @click="bar.press(bar.primary)"
      >
        {{ bar.primary.label }}
      </button>
    </div>
  </footer>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { bar } from '../lib/bar';
import { app } from '../store';

const open = ref(false);
const last = computed(() => app.last());
// What just happened stays in view for a while even when the screen's next state has a hint: a tag
// or a deploy confirmed only until the next render is not confirmed at all.
const FRESH_MS = 8000;
const fresh = ref(false);
let freshTimer = null;
watch(last, (entry) => {
  clearTimeout(freshTimer);
  const age = entry ? Date.now() - entry.at : Infinity;
  fresh.value = age < FRESH_MS;
  if (fresh.value) freshTimer = setTimeout(() => { fresh.value = false; }, FRESH_MS - age);
}, { immediate: true });
onBeforeUnmount(() => clearTimeout(freshTimer));
const time = (at) => at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
const dotOf = (tone) => ({ clean: 'bg-clean', needs: 'bg-needs', live: 'bg-live' }[tone] || 'bg-rule-strong');

</script>

<template>
  <div class="pointer-events-none fixed inset-x-0 top-0 z-[100] h-[3px] overflow-hidden transition-opacity duration-150" :class="show ? 'opacity-100' : 'opacity-0'" aria-hidden="true">
    <span class="working-run absolute inset-y-0 w-[30%] bg-route-1"></span>
  </div>
  <div
    v-if="progress.label"
    class="pointer-events-none fixed top-[calc(var(--top)+12px)] left-1/2 z-[90] flex max-w-[min(520px,calc(100vw-32px))] -translate-x-1/2 items-center gap-3 rounded border border-rule-strong bg-ground px-4.5 py-3 font-semibold shadow-[0_12px_40px_-16px_oklch(20%_0.02_260/0.35)]"
    role="status"
    aria-live="polite"
  >
    <span class="size-5 flex-none animate-turn rounded-full border-2 border-rule-strong border-t-ink" aria-hidden="true"></span>
    <p>{{ progress.label }}</p>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue';
import { progress } from '../lib/progress';

/*
 * The top progress bar while anything is in flight, and while something is being changed a status
 * floating over the page saying what — never a wall: the page stays scrollable and readable, and the
 * button that started it is busy, so nothing is started twice.
 */
const show = ref(false);
let timer = null;
watch(() => progress.busy, (busy) => {
  clearTimeout(timer);
  if (busy) timer = setTimeout(() => { show.value = true; }, 120);
  else show.value = false;
});
</script>

<style scoped>
.working-run { animation: slide 1.1s var(--ease-out) infinite; }
@keyframes slide { from { left: -30%; } to { left: 100%; } }
</style>

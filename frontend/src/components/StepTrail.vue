<template>
  <!-- The journey as a ride along the space's own route: each step a station, the next one an interchange ring. -->
  <nav class="flex h-[var(--trail)] items-center justify-between gap-6 overflow-x-auto border-b border-rule bg-ground px-[var(--gutter)] max-md:pr-10 max-md:[mask-image:linear-gradient(to_right,black_80%,transparent)]" aria-label="Steps">
    <ol class="m-0 flex flex-none list-none items-center p-0" :style="{ '--route': colour }">
      <li v-for="(step, i) in space.steps" :key="step.key" class="flex items-center">
        <span
          v-if="i > 0"
          class="mx-0.5 h-1 w-7 flex-none rounded-full"
          :class="space.steps[i - 1].done ? 'bg-[var(--route)]' : 'bg-rule-strong'"
          aria-hidden="true"
        ></span>
        <router-link
          :to="base + step.to"
          :aria-current="step.key === route.meta.step ? 'step' : undefined"
          class="group relative flex h-10 items-center gap-2 rounded px-2 whitespace-nowrap no-underline hover:bg-hover"
        >
          <span
            class="grid flex-none place-items-center rounded-full text-[11px] font-extrabold"
            :class="markClass(step)"
            aria-hidden="true"
          >
            <Check v-if="step.done" :size="12" :stroke-width="3" />
            <X v-else-if="step.failing" :size="12" :stroke-width="3" />
            <span v-else-if="step.checking" class="size-2.5 animate-turn rounded-full border-2 border-rule-strong border-t-ink" aria-label="checking"></span>
            <template v-else>{{ i + 1 }}</template>
          </span>
          <span
            class="text-md"
            :class="[
              step.failing ? 'font-bold text-needs-ink' : step.current ? 'font-bold text-ink' : step.done ? 'font-semibold text-ink' : 'text-ink-2',
              step.key === route.meta.step ? 'underline decoration-2 underline-offset-[6px]' : ''
            ]"
          >{{ step.failing ? `${step.label}: fails` : step.label }}</span>
          <span v-if="step.current" class="sr-only">(next step)</span>
        </router-link>
      </li>
    </ol>
    <p v-if="space.info" class="hidden flex-none items-center gap-2 text-sm text-ink-2 md:flex">
      <LineBullet :line="space.info.targetLine" :main-branch="app.info.mainBranch" size="sm" />
      {{ purposeOf(space.info, app.info.mainBranch) }}
    </p>
  </nav>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { Check, X } from 'lucide-vue-next';
import LineBullet from './LineBullet.vue';
import { space } from '../lib/space';
import { routeColour } from '../lib/routes';
import { app, purposeOf, spaceIdFromRoute, spacePath } from '../store';

const route = useRoute();
const base = computed(() => spacePath(spaceIdFromRoute(route.params)));
const colour = computed(() => routeColour(space.info?.targetLine, app.info.mainBranch));

// Stations: done is filled in the route's colour, failing red, the next step a bold interchange ring.
function markClass(step) {
  if (step.done) return 'size-5 bg-[var(--route)] text-white';
  if (step.failing) return 'size-5 bg-needs text-white';
  if (step.current) return 'size-6 border-[5px] border-ink bg-ground text-transparent';
  return 'size-5 border-2 border-rule-strong bg-ground text-ink-3';
}
</script>

<template>
  <!-- The signage band: where you are, as a station sign would say it. -->
  <header class="flex h-[var(--top)] items-center justify-between gap-4 bg-sign px-[var(--gutter)] text-sm text-white">
    <nav class="flex min-w-0 items-center gap-1 overflow-x-auto [scrollbar-width:none] max-md:pr-6 max-md:[mask-image:linear-gradient(to_right,black_82%,transparent)]" aria-label="Where you are">
      <router-link to="/" class="mr-3 flex flex-none items-center gap-2 text-[15px] font-extrabold tracking-[-0.01em] no-underline max-md:mr-1" aria-label="Rules">
        <svg width="22" height="22" viewBox="0 0 22 22" aria-hidden="true" class="flex-none">
          <path d="M3 17 L9 11 L19 11" fill="none" stroke="oklch(85% 0.09 160)" stroke-width="3" stroke-linecap="round" />
          <path d="M3 5 L19 5" fill="none" stroke="white" stroke-width="3" stroke-linecap="round" />
          <circle cx="9" cy="11" r="2.6" fill="var(--color-brand)" stroke="white" stroke-width="1.6" />
        </svg>
        <span class="max-md:hidden">Rules</span>
      </router-link>
      <router-link
        v-for="z in zones"
        :key="z.key"
        :to="z.to"
        class="flex-none whitespace-nowrap rounded-sm px-2.5 py-1.5 font-semibold no-underline transition-colors max-md:px-1.5"
        :class="zone === z.key ? 'bg-white text-brand' : 'text-white/85 hover:bg-white/15 hover:text-white'"
      >{{ z.label }}</router-link>
      <template v-if="spaceLabel">
        <span class="mx-2 h-4 w-px flex-none bg-white/35 max-md:hidden" aria-hidden="true"></span>
        <router-link :to="spaceHome" class="flex min-w-0 items-center gap-2 no-underline hover:underline max-md:hidden">
          <LineBullet v-if="space.info?.targetLine" :line="space.info.targetLine" :main-branch="app.info.mainBranch" size="sm" />
          <span class="truncate font-semibold">{{ spaceLabel }}</span>
        </router-link>
      </template>
      <template v-if="route.params.file">
        <ChevronRight :size="14" class="flex-none text-white/60" aria-hidden="true" />
        <span class="truncate font-mono text-[12px]">{{ route.params.file }}</span>
      </template>
    </nav>
    <div class="flex flex-none items-center gap-4">
      <span class="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 font-bold" :class="app.author ? 'bg-white/15 text-white' : 'bg-needs text-white'" :title="envTitle">
        <span class="size-2 rounded-full" :class="app.author ? 'bg-live' : 'bg-white'" aria-hidden="true"></span>{{ app.info.env }}<template v-if="!app.author"> · deploy only</template>
      </span>
      <span v-if="app.info.user" class="text-white/85 max-md:hidden">{{ app.info.user }}</span>
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { ChevronRight } from 'lucide-vue-next';
import LineBullet from './LineBullet.vue';
import { space } from '../lib/space';
import { app, spaceIdFromRoute, spacePath } from '../store';

const route = useRoute();
const spaceId = computed(() => spaceIdFromRoute(route.params));
const spaceLabel = computed(() => {
  if (spaceId.value) {
    const owner = route.params.owner;
    return owner === app.info.user ? route.params.name : `${owner}’s ${route.params.name}`;
  }
  return route.meta.flat ? 'Shared tables' : '';
});
const spaceHome = computed(() => spacePath(spaceId.value));
// The few places everything happens: what the applications run, work spaces, releases, cleaning up.
const zones = computed(() => [
  { key: 'applications', label: 'Applications', to: '/' },
  ...(app.author ? [{ key: 'work', label: 'Work spaces', to: '/work' }] : []),
  { key: 'releases', label: 'Releases', to: '/releases' },
  ...(app.author ? [{ key: 'tidy', label: 'Clean up', to: '/tidy' }] : [])
]);
const zone = computed(() => route.meta.zone || (spaceId.value ? 'work' : 'applications'));
const envTitle = computed(() => `This instance serves the ${app.info.env} environment and sees only it.`);
</script>

<template>
  <!-- The picked branch: like a picked row, selected — not a card. -->
  <div class="flex flex-wrap items-center gap-x-3 gap-y-1.5 rounded bg-select px-3 py-2.5 ring-1 ring-ink" role="status">
    <span class="flex items-center gap-2 text-lg font-bold"><LineBullet :line="line" :main-branch="mainBranch" />{{ line }}</span>
    <span class="text-sm text-ink-2">{{ line === mainBranch ? 'new work is merged here; the next release branch is created from it' : `where tagged releases get fixes; each fix is merged into newer branches and ${mainBranch}` }}</span>
    <button type="button" class="-my-1 -mr-1 ml-auto grid size-7 flex-none place-items-center self-start rounded text-ink-2 hover:bg-hover hover:text-ink" aria-label="Close" title="Close" @click="$emit('clear')"><X :size="16" :stroke-width="1.75" /></button>

    <p class="m-0 basis-full text-sm text-ink-2">{{ line === mainBranch ? `A work space started from it merges back into ${mainBranch}.` : `A work space started from it merges into ${line}, then into every newer release branch and ${mainBranch} in the same step.` }}</p>

    <div class="flex basis-full flex-wrap gap-1.5 pt-1">
      <router-link :to="`/work/new?from=${encodeURIComponent(line)}`" class="btn btn-small">Start a work space from it</router-link>
    </div>
  </div>
</template>

<script setup>
import { X } from 'lucide-vue-next';
import LineBullet from './LineBullet.vue';

/*
 * One branch (main or a release branch) picked in the graph on the work spaces screen: what starting
 * from it means, and starting. Only work-space actions here — tagging is on Releases, deleting a
 * branch on Clean up.
 */
defineProps({
  line: { type: String, required: true },
  mainBranch: { type: String, default: 'main' }
});
defineEmits(['clear']);
</script>

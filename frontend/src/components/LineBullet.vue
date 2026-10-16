<template>
  <!-- A line's bullet, like a metro line's: its colour and its short name, set in a disc. -->
  <span
    class="inline-flex flex-none items-center justify-center rounded-full font-sans font-extrabold leading-none text-white"
    :class="size === 'sm' ? 'h-[18px] min-w-[18px] px-[3px] text-[10px]' : size === 'lg' ? 'h-8 min-w-8 px-1.5 text-[13px]' : 'h-[22px] min-w-[22px] px-1 text-[11px]'"
    :style="{ background: routeColour(line, mainBranch) }"
    :title="title"
    aria-hidden="true"
  >{{ bulletText(line, mainBranch) }}</span>
</template>

<script setup>
import { bulletText, routeColour } from '../lib/routes';

defineProps({
  line: { type: String, required: true },
  mainBranch: { type: String, default: 'main' },
  size: { type: String, default: 'md' }, // sm | md | lg
  title: { type: String, default: undefined }
});
</script>

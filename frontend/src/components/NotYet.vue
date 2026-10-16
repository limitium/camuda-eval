<template>
  <div class="not-yet mt-8 grid max-w-[70ch] gap-2.5 border-y border-rule py-5">
    <h2 class="text-lg">{{ title }}</h2>
    <p class="text-ink"><slot /></p>
    <p class="meta">This instance does not have it yet — nothing was changed, and nothing is pretending to work.</p>
  </div>
</template>

<script setup>
defineProps({ title: { type: String, required: true } });
</script>

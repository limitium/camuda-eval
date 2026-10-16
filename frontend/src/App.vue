<template>
  <div class="flex h-dvh flex-col">
    <TopLine class="flex-none" />
    <StepTrail v-if="route.meta.space && app.ready" class="flex-none" />
    <main id="stage" class="stage min-h-0 flex-auto overflow-auto bg-ground">
      <div v-if="app.failed" class="page-body">
        <h1>This instance is not answering</h1>
        <p class="lede">The rules service could not be reached: {{ app.failed }}. Nothing has been changed. Reload the page once it is back.</p>
      </div>
      <router-view v-else-if="app.ready" :key="route.fullPath.split('?')[0]" />
      <div v-else class="page-body"><p class="loading">Connecting to the rules service…</p></div>
    </main>
    <ActionBar class="flex-none" />
    <Working />
  </div>
</template>

<script setup>
import { useRoute } from 'vue-router';
import TopLine from './components/TopLine.vue';
import StepTrail from './components/StepTrail.vue';
import ActionBar from './components/ActionBar.vue';
import Working from './components/Working.vue';
import { app } from './store';

const route = useRoute();
app.load();
</script>

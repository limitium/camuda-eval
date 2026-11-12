<template>
  <div class="flex h-dvh flex-col">
    <TopLine class="flex-none" />
    <!-- The page, and beside it the assistant when it is open: the page gives up that width. -->
    <div class="flex min-h-0 flex-auto">
      <div class="flex min-w-0 flex-auto flex-col">
        <StepTrail v-if="route.meta.space && app.ready" class="flex-none" />
        <!-- A container: its screens lay out by the width they have, which the docked assistant narrows. -->
        <main id="stage" class="stage @container min-h-0 flex-auto overflow-auto bg-ground">
          <div v-if="app.failed" class="page-body">
            <h1>This instance is not answering</h1>
            <p class="lede">The rules service could not be reached: {{ app.failed }}. Nothing has been changed. Reload the page once it is back.</p>
          </div>
          <router-view v-else-if="app.ready" :key="route.fullPath.split('?')[0]" />
          <div v-else class="page-body"><p class="loading">Connecting to the rules service…</p></div>
        </main>
        <ActionBar class="flex-none" />
      </div>
      <AssistantPanel v-if="app.info.assistant && assistant.open" />
    </div>
    <Working />
  </div>
</template>

<script setup>
import { useRoute } from 'vue-router';
import TopLine from './components/TopLine.vue';
import StepTrail from './components/StepTrail.vue';
import ActionBar from './components/ActionBar.vue';
import Working from './components/Working.vue';
import AssistantPanel from './components/AssistantPanel.vue';
import { assistant } from './lib/assistant';
import { app } from './store';

const route = useRoute();
app.load();
</script>

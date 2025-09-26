import { createRouter, createWebHistory } from 'vue-router';
import ListPage from './views/ListPage.vue';
import DmnEditorPage from './views/DmnEditorPage.vue';
import TestEditorPage from './views/TestEditorPage.vue';
import RunPage from './views/RunPage.vue';

const routes = [
  { path: '/', component: ListPage },
  { path: '/dmn/:name', component: DmnEditorPage, props: true },
  { path: '/test/:name', component: TestEditorPage, props: true },
  { path: '/run/:name', component: RunPage, props: true }
];

export default createRouter({
  history: createWebHistory(),
  routes
});



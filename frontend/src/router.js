import { createRouter, createWebHistory } from 'vue-router';
import { app } from './store';
import ApplicationsScreen from './screens/ApplicationsScreen.vue';
import ApplicationScreen from './screens/ApplicationScreen.vue';
import WorkScreen from './screens/WorkScreen.vue';
import NewWorkScreen from './screens/NewWorkScreen.vue';
import SpaceScreen from './screens/SpaceScreen.vue';
import TableScreen from './screens/TableScreen.vue';
import TestScreen from './screens/TestScreen.vue';
import SaveVersionScreen from './screens/SaveVersionScreen.vue';
import TryScreen from './screens/TryScreen.vue';
import LandScreen from './screens/LandScreen.vue';
import TidyScreen from './screens/TidyScreen.vue';
import RunningTableScreen from './screens/RunningTableScreen.vue';
import CommitScreen from './screens/CommitScreen.vue';
import ReleasesScreen from './screens/ReleasesScreen.vue';
import PullScreen from './screens/PullScreen.vue';

const SPACE = '/space/:kind/:owner/:name';
// The same screens serve the shared folder when this instance keeps no version history.
const FLAT = '/tables';

const spaceRoutes = (prefix, flat) => [
  { path: prefix, component: SpaceScreen, meta: { space: true, step: 'edit', flat } },
  { path: `${prefix}/table/:file`, component: TableScreen, meta: { space: true, step: 'edit', flat } },
  { path: `${prefix}/test`, component: TestScreen, meta: { space: true, step: 'test', flat } }
];

const routes = [
  // Zones: what the applications run, work spaces, releases, clean up.
  { path: '/', component: ApplicationsScreen, meta: { zone: 'applications' } },
  { path: '/applications/:name', component: ApplicationScreen, meta: { zone: 'applications' } },
  { path: '/domains/:name', redirect: (to) => `/applications/${to.params.name}` },
  { path: '/work', component: WorkScreen, meta: { zone: 'work' } },
  { path: '/work/new', component: NewWorkScreen, meta: { zone: 'work' } },
  { path: '/new', redirect: '/work/new' },
  ...spaceRoutes(SPACE, false),
  { path: `${SPACE}/version`, component: SaveVersionScreen, meta: { space: true, step: 'version' } },
  { path: `${SPACE}/try`, component: TryScreen, meta: { space: true, step: 'try' } },
  { path: `${SPACE}/land`, component: LandScreen, meta: { space: true, step: 'land' } },
  { path: `${SPACE}/pull`, component: PullScreen, meta: { space: true, step: 'edit' } },
  { path: `${SPACE}/refresh`, component: PullScreen, meta: { space: true, step: 'edit', refresh: true } },
  ...spaceRoutes(FLAT, true),
  { path: '/running', redirect: '/' },
  { path: '/running/:name', redirect: '/' },
  // Any commit, opened; what runs opens here too, since a deploy names its commit.
  // A commit belongs to no zone of its own: it stays under whichever one it was opened from.
  { path: '/commit/:sha', component: CommitScreen, meta: { zone: 'inherit' } },
  { path: '/commit/:sha/:domain', component: RunningTableScreen, meta: { zone: 'inherit' } },
  { path: '/tidy', component: TidyScreen, meta: { zone: 'tidy' } },
  { path: '/releases', component: ReleasesScreen, meta: { zone: 'releases' } },
  { path: '/deploy', redirect: '/' },
  { path: '/:rest(.*)*', redirect: '/' }
];

const router = createRouter({
  history: createWebHistory(window.BASE_URL || '/'),
  routes
});

router.beforeEach(async (to, from) => {
  // A deploy-only instance has no work spaces and nothing to clean up: those pages do not exist there.
  if (to.meta.space || ['work', 'tidy'].includes(to.meta.zone)) {
    await app.load();
    if (app.ready && !app.author) return '/';
  }
  if (to.meta.zone !== 'inherit') return;
  const came = from.meta?.zone && from.meta.zone !== 'inherit' ? from.meta.zone : from.meta?.space ? 'work' : null;
  // Opened directly (a link, a reload): a commit is a version, so it sits with the releases.
  to.meta.zone = came || 'releases';
});

export default router;

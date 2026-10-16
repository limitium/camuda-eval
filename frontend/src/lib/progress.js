import { reactive } from 'vue';

/*
 * What is happening on the server right now, for every screen at once:
 * - any request: the thin bar along the top;
 * - the button that started it: busy (spinner, cannot be pressed again) until it is done;
 * - an action that changes something (save, land, release, deploy, publish, bring in, refresh,
 *   clean up…): a modal saying what is happening, so nothing else is pressed meanwhile.
 */
export const progress = reactive({
  inflight: 0,
  working: [], // [{ id, label }]
  get busy() {
    return this.inflight > 0;
  },
  get label() {
    return this.working.length ? this.working[this.working.length - 1].label : null;
  }
});

let lastClick = { button: null, at: 0 };
if (typeof document !== 'undefined') {
  document.addEventListener('click', (e) => {
    const button = e.target.closest?.('button, a.btn');
    if (button) lastClick = { button, at: Date.now() };
  }, true);
}

const held = new Map(); // button -> count of its requests in flight
let seq = 0;

// What a changing request is doing, in words. Checks (dry runs) and reads get no modal.
function describe(config) {
  const method = (config.method || 'get').toLowerCase();
  if (method === 'get') return null;
  const url = config.url || '';
  let body = config.data;
  if (typeof body === 'string') {
    try { body = JSON.parse(body); } catch { body = {}; }
  }
  body = body || {};
  if (/\/commit$/.test(url) && url.includes('/api/workspaces/')) return 'Saving the version and sharing it…';
  if (url.includes('/api/lifecycle/land/')) return body.apply ? 'Landing — combining, testing and moving every line…' : null;
  if (url.includes('/api/lifecycle/pull/')) return body.apply ? 'Bringing the changes in…' : null;
  if (url.includes('/api/lifecycle/refresh/')) return body.apply ? 'Refreshing onto the newest saved version…' : null;
  if (url.includes('/api/lifecycle/release')) return body.check ? null : 'Making the release — running every test first…';
  if (url.includes('/api/lifecycle/lines/open')) return 'Opening the release line…';
  if (url.includes('/api/lifecycle/carry')) return body.apply ? 'Carrying the line forward — combining and testing every step…' : null;
  if (/\/api\/applications\/[^/]+\/deploy$/.test(url)) return 'Deploying — running every test, then publishing…';
  if (/\/api\/applications\/[^/]+\/draft$/.test(url)) return 'Publishing the draft — running every test first…';
  if (/\/redeploy$/.test(url)) return 'Redeploying…';
  if (/\/api\/applications\/[^/]+\/restore$/.test(url)) return 'Putting the last release back…';
  if (url.includes('/api/cleanup/apply')) return 'Cleaning up…';
  if (url.includes('/api/cleanup/restore')) return 'Bringing the work space back…';
  if (/\/api\/workspaces$/.test(url) && method === 'post') return 'Starting the work space — fetching first…';
  if (/\/open$/.test(url)) return 'Continuing the work space here…';
  if (method === 'delete' && url.includes('/api/workspaces/')) return 'Removing the work space…';
  return null;
}

export function requestStarted(config) {
  const id = ++seq;
  config.__progress = { id };
  progress.inflight++;
  const recent = lastClick.button && Date.now() - lastClick.at < 500 && lastClick.button.isConnected ? lastClick.button : null;
  if (recent) {
    config.__progress.button = recent;
    held.set(recent, (held.get(recent) || 0) + 1);
    recent.setAttribute('aria-busy', 'true');
    if (recent.tagName === 'BUTTON') recent.disabled = true;
  }
  const label = describe(config);
  if (label) {
    // Shown only when it takes a moment: a quick action is not interrupted by a flash.
    config.__progress.timer = setTimeout(() => progress.working.push({ id, label }), 250);
  }
  return config;
}

export function requestEnded(config) {
  const p = config?.__progress;
  if (!p) return;
  progress.inflight = Math.max(0, progress.inflight - 1);
  clearTimeout(p.timer);
  const i = progress.working.findIndex((w) => w.id === p.id);
  if (i >= 0) progress.working.splice(i, 1);
  if (p.button) {
    const left = (held.get(p.button) || 1) - 1;
    if (left <= 0) {
      held.delete(p.button);
      p.button.removeAttribute('aria-busy');
      if (p.button.tagName === 'BUTTON' && !p.button.hasAttribute('data-keep-disabled')) p.button.disabled = false;
    } else {
      held.set(p.button, left);
    }
  }
}

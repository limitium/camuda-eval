import { reactive } from 'vue';

/*
 * What is happening on the server right now, for every screen at once:
 * - any request: the thin bar along the top;
 * - the button that started it: busy (spinner, cannot be pressed again) until it is done;
 * - an action that changes something (save, commit, merge, tag, deploy, publish, pull,
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
    // A button whose request is still running cannot be pressed again.
    if (button?.hasAttribute('data-busy')) {
      e.preventDefault();
      e.stopImmediatePropagation();
      return;
    }
    if (button) lastClick = { button, at: Date.now() };
  }, true);
}

const held = new Map(); // button -> count of its requests in flight
let seq = 0;

// The branch a request is about, from its path: /api/lifecycle/refresh/feature/alice/x -> feature/alice/x.
const branchOf = (url, prefix) => decodeURIComponent(url.slice(url.indexOf(prefix) + prefix.length).split('?')[0]);

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
  if (/\/commit$/.test(url) && url.includes('/api/workspaces/')) return 'Committing and sharing it…';
  if (url.includes('/api/lifecycle/land/')) return body.apply ? 'Merging — testing every merge and moving every branch…' : null;
  if (url.includes('/api/lifecycle/pull/')) return body.apply ? `Merging ${body.from || 'the other branch'} into this work space…` : null;
  if (url.includes('/api/lifecycle/refresh/')) return body.apply ? `Pulling the newest commit of ${branchOf(url, '/api/lifecycle/refresh/')}…` : null;
  if (url.includes('/api/lifecycle/release')) return body.check ? null : 'Tagging the release — running every test first…';
  if (url.includes('/api/lifecycle/lines/open')) return 'Creating the release branch…';
  if (url.includes('/api/lifecycle/carry')) return body.apply ? `Merging ${body.line || 'the release branch'} into newer branches — testing every merge…` : null;
  if (/\/api\/applications\/[^/]+\/deploy$/.test(url)) return 'Deploying — running every test, then publishing…';
  if (/\/api\/applications\/[^/]+\/draft$/.test(url)) return 'Publishing the draft — running every test first…';
  if (/\/redeploy$/.test(url)) return 'Redeploying…';
  if (/\/api\/applications\/[^/]+\/restore$/.test(url)) return 'Putting the last release back…';
  if (url.includes('/api/cleanup/apply')) return 'Cleaning up…';
  if (url.includes('/api/cleanup/restore')) return 'Restoring the work space…';
  if (/\/api\/workspaces$/.test(url) && method === 'post') return 'Starting the work space — fetching first…';
  if (/\/open$/.test(url)) return 'Continuing the work space here…';
  if (method === 'delete' && url.includes('/api/workspaces/')) return 'Removing the work space…';
  return null;
}

function begin(label) {
  const id = ++seq;
  const p = { id };
  progress.inflight++;
  const recent = lastClick.button && Date.now() - lastClick.at < 500 && lastClick.button.isConnected ? lastClick.button : null;
  if (recent) {
    p.button = recent;
    if (!held.has(recent)) {
      // Marked with attributes of its own: disabled and aria-busy belong to the screen, and the screen
      // writes them only when its own value changes, so whatever was written here would stick — a
      // button the screen disabled meanwhile (Deploy, once no tag is chosen) would come back pressable.
      recent.setAttribute('data-busy', '');
      if (!recent.hasAttribute('aria-disabled')) recent.setAttribute('aria-disabled', 'true');
    }
    held.set(recent, (held.get(recent) || 0) + 1);
  }
  if (label) {
    // Shown only when it takes a moment: a quick action is not interrupted by a flash.
    p.timer = setTimeout(() => progress.working.push({ id, label }), 250);
  }
  return p;
}

export function requestStarted(config) {
  config.__progress = begin(describe(config));
  return config;
}

export function requestEnded(config) {
  end(config?.__progress);
}

/*
 * One piece of work made of several requests — a test run and the checks for its result, or every
 * table's run in turn — shown as one: the bar and the button stay busy from start to end, instead of
 * flashing off between requests. Returns the function that ends it.
 */
export function activity() {
  const p = begin(null);
  return () => end(p);
}

function end(p) {
  if (!p || p.ended) return;
  p.ended = true;
  progress.inflight = Math.max(0, progress.inflight - 1);
  clearTimeout(p.timer);
  const i = progress.working.findIndex((w) => w.id === p.id);
  if (i >= 0) progress.working.splice(i, 1);
  if (p.button) {
    const left = (held.get(p.button) || 1) - 1;
    if (left <= 0) {
      held.delete(p.button);
      p.button.removeAttribute('data-busy');
      p.button.removeAttribute('aria-disabled');
    } else {
      held.set(p.button, left);
    }
  }
}

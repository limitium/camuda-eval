import { reactive } from 'vue';
import { api } from './lib/api';

/*
 * App-wide state: what this instance is, who is asking, and the transcript — the plain-language line
 * of what just happened that the bottom bar prints. There are no toasts: a toast that renders late can
 * be read as the result of the wrong action, and on a refused save that is the one misreading that
 * must never happen.
 */
let loading = null;

export const app = reactive({
  ready: false,
  failed: null,
  info: { user: null, env: 'dev', role: 'author', gitEnabled: false, mainBranch: 'main', allowDraftPublish: false },
  transcript: [], // [{ text, tone: 'plain'|'clean'|'needs'|'live', at }]
  // Test verdicts this session, per space, dropped whenever something in that work space is saved.
  verdicts: {}, // { [spaceKey]: { [file]: { passed, failed, total, tests, error } } }

  get author() {
    return this.info.role !== 'deployer';
  },

  // Loaded once and shared: the app and the route guard both wait on the same request.
  load() {
    if (!loading) {
      loading = (async () => {
        try {
          this.info = { ...this.info, ...(await api.app()) };
          this.ready = true;
        } catch (error) {
          this.failed = error.message;
        }
      })();
    }
    return loading;
  },

  say(text, tone = 'plain') {
    this.transcript.unshift({ text, tone, at: new Date() });
    this.transcript.length = Math.min(this.transcript.length, 20);
  },

  last() {
    return this.transcript[0] || null;
  },

  spaceKey(ws) {
    return ws || '(shared folder)';
  },

  recordVerdict(ws, file, verdict) {
    const key = this.spaceKey(ws);
    this.verdicts[key] = { ...(this.verdicts[key] || {}), [file]: verdict };
  },

  forgetVerdict(ws, file) {
    const key = this.spaceKey(ws);
    if (this.verdicts[key]) delete this.verdicts[key][file];
  },

  verdictsFor(ws) {
    return this.verdicts[this.spaceKey(ws)] || {};
  }
});

/** The branch name is the id; the route carries it as three segments. */
export function spaceIdFromRoute(params) {
  return params.kind && params.owner && params.name ? `${params.kind}/${params.owner}/${params.name}` : null;
}

export function spacePath(id, rest = '') {
  return id ? `/space/${id}${rest}` : `/tables${rest}`;
}

export function whenText(iso) {
  if (!iso) return '';
  const seconds = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 45) return 'just now';
  if (seconds < 3600) return `${Math.max(1, Math.round(seconds / 60))} min ago`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)} h ago`;
  const days = Math.round(seconds / 86400);
  if (days <= 1) return 'yesterday';
  if (days < 14) return `${days} days ago`;
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

/**
 * A work space's state on this instance, against its one saved version (the branch): saved, unsaved
 * changes here, or behind a version saved elsewhere. Returns { key, text, tone }.
 */
export function savedStateOf(space) {
  const s = space?.saved;
  if (!s) return space?.dirty ? { key: 'UNSAVED', text: 'Unsaved changes here', tone: 'needs' } : { key: 'SAVED', text: 'Saved', tone: 'quiet' };
  const where = `${s.savedBy || 'someone'}${s.savedOn ? ` on ${s.savedOn}` : ''}`;
  switch (s.state) {
    case 'BEHIND': return { key: s.state, text: `Newer version saved by ${where} — refresh`, tone: 'live' };
    case 'BEHIND_UNSAVED': return { key: s.state, text: `Unsaved changes here, and a newer version saved by ${where}`, tone: 'needs' };
    case 'UNSAVED': return { key: s.state, text: 'Unsaved changes here', tone: 'needs' };
    default: return { key: 'SAVED', text: `Saved${s.savedOn ? ` (last on ${s.savedOn})` : ''}`, tone: 'quiet' };
  }
}

/** What a work space is for, said the way an analyst would say it. */
export function purposeOf(space, mainBranch = 'main') {
  if (!space) return '';
  if (space.kind === 'hotfix' || space.kind === 'HOTFIX') {
    return `Fixing ${space.baseline}, lands in ${lineName(space.targetLine, mainBranch)}`;
  }
  // Started from one commit: name it short, like a version, not by its whole hash.
  const base = /^[0-9a-f]{40}$/.test(space.baseline || '') ? `commit ${space.baseline.slice(0, 7)}` : lineName(space.baseline, mainBranch);
  if (space.baseline === space.targetLine) return `Building on ${base}`;
  return `Building on ${base}, lands in ${lineName(space.targetLine, mainBranch)}`;
}

/** Any wording as a branch name: lower case, words joined by dashes, nothing git would refuse. */
export function branchSlug(text) {
  return String(text || '').trim().toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '-').replace(/\.{2,}/g, '.').replace(/-{2,}/g, '-')
    .replace(/^[-.]+|[-.]+$/g, '').replace(/\.lock$/, '');
}

/** Any branch in words: a line, or a work space ("bob’s pricing"). */
export function branchName(ref, mainBranch = 'main') {
  const space = String(ref || '').match(/^(feature|hotfix)\/([\w.-]+)\/([\w.-]+)$/);
  if (space) return `${space[2]}’s ${space[3]}`;
  return lineName(ref, mainBranch);
}

export function lineName(ref, mainBranch = 'main') {
  if (!ref) return '';
  if (ref === mainBranch) return mainBranch;
  if (ref.startsWith('release/')) return `release ${ref.slice('release/'.length)}`;
  return ref;
}

/**
 * A commit message in the analyst's words: landings and carries are said plainly, anything else is
 * left as its author wrote it.
 */
export function plainMessage(message, mainBranch = 'main') {
  const text = String(message || '');
  let m = text.match(/^Land (feature|hotfix)\/([\w.-]+)\/([\w.-]+)(?: into (\S+))?/);
  if (m) return `${m[2]}’s ${m[1] === 'hotfix' ? 'fix' : 'change'} “${m[3]}” landed in ${lineName(m[4] || mainBranch, mainBranch)}`;
  m = text.match(/^Bring (\S+) into (feature|hotfix)\/([\w.-]+)\/([\w.-]+)/);
  if (m) return `${branchName(m[1], mainBranch)} brought into ${m[3]}’s “${m[4]}”`;
  m = text.match(/^Carry (\S+) forward into (\S+)/);
  if (m) {
    const from = lineName(m[1], mainBranch);
    return `${from.charAt(0).toUpperCase() + from.slice(1)} carried forward into ${lineName(m[2], mainBranch)}`;
  }
  return text;
}

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
 * A work space's state on this instance, against its commits (the branch): committed, uncommitted
 * changes here, or behind a commit made elsewhere. Returns { key, text, tone }.
 */
export function savedStateOf(space) {
  const s = space?.saved;
  if (!s) return space?.dirty ? { key: 'UNSAVED', text: 'Uncommitted changes here', tone: 'needs' } : { key: 'SAVED', text: 'Committed', tone: 'quiet' };
  const where = `${s.savedBy || 'someone'}${s.savedOn ? ` on ${s.savedOn}` : ''}`;
  switch (s.state) {
    case 'BEHIND': return { key: s.state, text: `Newer commit by ${where} — pull`, tone: 'live' };
    case 'BEHIND_UNSAVED': return { key: s.state, text: `Uncommitted changes here, and a newer commit by ${where}`, tone: 'needs' };
    case 'UNSAVED': return { key: s.state, text: 'Uncommitted changes here', tone: 'needs' };
    default: return { key: 'SAVED', text: `Committed${s.savedOn ? ` (last on ${s.savedOn})` : ''}`, tone: 'quiet' };
  }
}

/** What a work space is for, said the way an analyst would say it. */
export function purposeOf(space, mainBranch = 'main') {
  if (!space) return '';
  if (space.kind === 'hotfix' || space.kind === 'HOTFIX') {
    return `Fixing ${space.baseline}, merges into ${lineName(space.targetLine, mainBranch)}`;
  }
  // Started from one commit: name it short, like git does, not by its whole hash.
  const base = /^[0-9a-f]{40}$/.test(space.baseline || '') ? `commit ${space.baseline.slice(0, 7)}` : lineName(space.baseline, mainBranch);
  if (space.baseline === space.targetLine) return `Building on ${base}`;
  return `Building on ${base}, merges into ${lineName(space.targetLine, mainBranch)}`;
}

/** Any wording as a branch name: lower case, words joined by dashes, nothing git would refuse. */
export function branchSlug(text) {
  return String(text || '').trim().toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '-').replace(/\.{2,}/g, '.').replace(/-{2,}/g, '-')
    .replace(/^[-.]+|[-.]+$/g, '').replace(/\.lock$/, '');
}

/** Any branch by its git name: main, release/1.0, feature/alice/pricing, hotfix/bob/x. */
export function branchName(ref) {
  return String(ref || '');
}

/**
 * The branch a work space started from a picked commit merges into. A commit that is the newest of
 * several branches (a release branch created at main's newest commit) starts on the last of them — the
 * newest release branch; otherwise on the branch it was picked on. Null when that is not main or a
 * release branch.
 */
export function startLineOf(c, mainBranch = 'main') {
  const number = (b) => [...b.slice('release/'.length).split('.').map(Number), 0];
  const releases = (c?.tips || []).filter((t) => /^release\/\d+\.\d+(\.\d+)?$/.test(t))
    .sort((a, b) => number(a)[0] - number(b)[0] || number(a)[1] - number(b)[1] || number(a)[2] - number(b)[2]);
  if (releases.length) return releases[releases.length - 1];
  return c?.lane === mainBranch || /^release\//.test(c?.lane || '') ? c.lane : null;
}

/** main or a release branch by its git name (release/1.0); kept apart from branchName for its callers. */
export function lineName(ref) {
  return String(ref || '');
}

/**
 * A commit message as git has it. Only the older wordings of a merge are normalised, so history reads
 * one way: "Land X into Y", "Carry X forward into Y" and "Bring X into Y" were all merges, and say
 * "Merge X into Y" now, like every newer one. Anything else is left as its author wrote it.
 */
export function plainMessage(message) {
  return String(message || '')
    .replace(/^Land (?=(?:feature|hotfix)\/|\S+ into \S)/, 'Merge ')
    .replace(/^Carry (\S+) forward into (\S+)/, 'Merge $1 into $2')
    .replace(/^Bring (\S+) into (\S+)/, 'Merge $1 into $2');
}

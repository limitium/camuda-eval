import { reactive } from 'vue';
import { api } from './api';
import { app } from '../store';

/*
 * The open space: which tables it holds, what changed since its last saved version, and therefore
 * which step of the journey it is on. The step is derived, never stored, so the trail cannot drift
 * from what is actually true of the files.
 */
// Per commit, kept for the session: reopening a work space shows the known answer at once.
const savedVerdicts = new Map((() => {
  try {
    return JSON.parse(sessionStorage.getItem('saved-verdicts') || '[]');
  } catch {
    return [];
  }
})());
function rememberVerdict(head, passed) {
  savedVerdicts.set(head, passed);
  try {
    sessionStorage.setItem('saved-verdicts', JSON.stringify([...savedVerdicts].slice(-300)));
  } catch {
    // a private window: remembered for this page only
  }
}

export const space = reactive({
  id: null,
  info: null,
  files: [], // [{ name, modified }]
  changes: [], // [{ path, change }]
  loading: false,
  missing: false,
  // The saved version's own tests: the evidence for "Test" once nothing unsaved is left to test.
  savedVerdict: null, // { head, passed }

  async open(id) {
    if (this.id !== id) {
      this.id = id;
      this.info = null;
      this.files = [];
      this.changes = [];
      this.missing = false;
    }
    await this.refresh();
  },

  async refresh() {
    this.loading = true;
    try {
      if (this.id) {
        const all = await api.spaces();
        this.info = all.find((s) => s.id === this.id) || null;
        this.missing = !this.info;
        if (this.missing) return;
        const [files, changes] = await Promise.all([api.files(this.id), api.changes(this.id)]);
        this.files = files;
        this.changes = changes;
        this.checkSaved();
      } else {
        this.info = null;
        this.files = await api.files(null);
        this.changes = [];
      }
    } finally {
      this.loading = false;
    }
  },

  /** Records the saved version's tests as known (just saved from files whose tests all passed). */
  seedSaved(head, passed) {
    rememberVerdict(head, passed);
  },

  /** Runs the saved version's tests once per version (a commit never changes, so neither does the answer). */
  checkSaved() {
    const head = this.info?.head;
    if (!head || this.changes.length || this.savedVerdict?.head === head) return;
    this.savedVerdict = null;
    const cached = savedVerdicts.get(head);
    if (cached !== undefined) {
      this.savedVerdict = { head, passed: cached };
      return;
    }
    api.commitTests(head)
      .then((r) => {
        rememberVerdict(head, r.passed && r.total > 0);
        if (this.info?.head === head) this.savedVerdict = { head, passed: r.passed && r.total > 0 };
      })
      .catch(() => {});
  },

  get tables() {
    return this.files.map((f) => f.name.replace(/\.dmn$/i, '')).sort();
  },

  /** How each table stands against the last saved version. */
  changeOf(table) {
    const hits = this.changes.filter((c) => c.path === `dmn/${table}.dmn` || c.path === `yaml/${table}.yaml`);
    if (!hits.length) return null;
    if (hits.some((c) => c.path.startsWith('dmn/') && c.change === 'ADDED')) return 'new';
    if (hits.some((c) => c.path.startsWith('dmn/') && c.change === 'REMOVED')) return 'removed';
    return 'changed';
  },

  get changedTables() {
    const names = new Set(this.changes.map((c) => c.path.replace(/^(dmn|yaml)\//, '').replace(/\.(dmn|yaml)$/, '')));
    return [...names].sort();
  },

  get tryAvailable() {
    return !!this.id && app.info.allowDraftPublish;
  },

  get steps() {
    const env = app.info.env;
    const all = [
      { key: 'edit', label: 'Edit', to: '' },
      { key: 'test', label: 'Test', to: '/test' }
    ];
    if (this.id) {
      all.push({ key: 'version', label: 'Save version', to: '/version' });
      if (this.tryAvailable) all.push({ key: 'try', label: `Try in ${env}`, to: '/try' });
      all.push({ key: 'land', label: 'Land', to: '/land' });
    }
    const current = this.current;
    const changed = this.changes.length > 0;
    const landed = !changed && !!this.info?.saved?.landed;
    const versioned = !changed && ((this.info?.ahead || 0) > 0 || landed);
    // A check mark is a claim; each one needs its own evidence, never just its place in the order.
    const done = {
      edit: changed || versioned,
      // Unsaved changes: tested here. Nothing unsaved: the saved version's own tests are the evidence.
      test: changed ? this.testedOk : versioned && this.savedVerdict?.head === this.info?.head && this.savedVerdict.passed,
      version: versioned,
      try: false,
      land: landed
    };
    // The saved version's tests are being checked: say so, rather than showing it as not done.
    const checking = { test: !changed && versioned && !landed && this.savedVerdict?.head !== this.info?.head };
    // ...and when they fail, say that: it is why nothing after it can happen.
    const failing = { test: this.savedFails };
    return all.map((s) => ({ ...s, current: s.key === current && !landed, done: !!done[s.key] && (s.key !== current || landed), checking: !!checking[s.key], failing: !!failing[s.key] }));
  },

  /** Nothing unsaved, and the saved version's own tests fail. */
  get savedFails() {
    return !this.changes.length && !this.info?.saved?.landed && (this.info?.ahead || 0) > 0
      && this.savedVerdict?.head === this.info?.head && this.savedVerdict.passed === false;
  },

  get testedOk() {
    const verdicts = app.verdictsFor(this.id);
    const tables = this.id ? this.changedTables.filter((t) => this.changeOf(t) !== 'removed') : this.tables;
    return tables.length > 0 && tables.every((t) => verdicts[t]?.passed);
  },

  get current() {
    const changed = this.changes.length > 0;
    if (!this.id) return this.testedOk ? 'test' : 'edit';
    if (!changed && this.info?.saved?.landed) return 'land';
    const versioned = !changed && (this.info?.ahead || 0) > 0;
    if (!changed && !versioned) return 'edit';
    if (this.savedFails) return 'test';
    if (changed && !this.testedOk) return 'test';
    if (changed) return 'version';
    return this.tryAvailable ? 'try' : 'land';
  }
});

import { markRaw, onBeforeUnmount, reactive } from 'vue';
import { api } from './api';
import { bar } from './bar';
import { space } from './space';
import { app, purposeOf, spacePath } from '../store';

/*
 * The assistant panel: open or not, the conversation, and what the open screen tells it.
 *
 * A screen describes itself with useAssistant(() => facts, actions), the way it declares its next
 * action with useBar. The facts are read when a question is asked, so they are what the screen shows
 * at that moment. The actions are how the screen does what the assistant may propose there — deploy a
 * tag, commit, add a rule — through the same code as its own buttons; every screen can also have one
 * of its bottom-bar buttons pressed, or a place opened. Nothing runs until the person confirms the
 * card, and only while the screen it was proposed on is open.
 *
 * The instructions the model works under are the server's, not the screen's: a screen sends facts.
 */
const KEPT = 40;

function restore() {
  try {
    return JSON.parse(sessionStorage.getItem('assistant') || 'null') || {};
  } catch {
    return {};
  }
}

const saved = restore();

export const assistant = reactive({
  open: !!saved.open,
  // [{ role, text, turn, proposal, refused, review, status: 'running'|'done'|'failed', statusText, error, page, where, at, seconds }]
  messages: Array.isArray(saved.messages) ? saved.messages : [],
  busy: false,
  screen: null, // { facts: () => object, actions: { [type]: (proposal) => Promise }, owner }

  toggle() {
    this.open = !this.open;
    keep();
  },

  close() {
    this.open = false;
    keep();
  },

  clear() {
    this.messages = [];
    keep();
  },

  /** Asks about the screen at `route`; the answer is appended to the conversation. */
  async ask(text, route, task = null) {
    const question = String(text || '').trim();
    if (!question || this.busy) return;
    const page = pageOf(route);
    this.messages.push({ role: 'user', text: question, page, at: Date.now() });
    keep();
    this.busy = true;
    try {
      const { context, xml } = this.contextFor(route);
      const history = this.messages
        .filter((m) => !m.error)
        .map((m) => ({ role: m.role, content: m.role === 'assistant' ? m.turn || m.text : m.text }));
      const reply = await api.assistant(page, context, history, xml, task);
      this.messages.push({
        role: 'assistant',
        text: reply.answer || '',
        turn: reply.turn || null,
        proposal: reply.proposal || null,
        refused: reply.refused || null,
        review: reply.review || null,
        page,
        where: route.fullPath.split('?')[0],
        at: Date.now(),
        seconds: reply.seconds
      });
    } catch (error) {
      this.messages.push({ role: 'assistant', error: true, text: error.status === 503 ? error.message : `No answer: ${error.message}`, page, at: Date.now() });
    } finally {
      this.busy = false;
      keep();
    }
  },

  /** The work space's changes, reviewed by the app and summed up. */
  review(route) {
    this.open = true;
    return this.ask('Review my changes', route, 'review');
  },

  /** A commit message for what changed, keeping the person's own words; null when there is nothing to commit. */
  async commitMessage(route, draft) {
    const { context } = this.contextFor(route);
    const reply = await api.assistant(pageOf(route), context, [{ role: 'user', content: 'Write the commit message for my changes' }], null, 'commit_message', draft || '');
    return reply.message || null;
  },

  /** A proposal can be confirmed while the screen it was made on is open, once. */
  canApply(message, route) {
    const p = message.proposal;
    if (!p || ['running', 'done'].includes(message.status) || message.where !== route.fullPath.split('?')[0]) return false;
    return p.type === 'press' || p.type === 'go' || !!this.screen?.actions?.[p.type];
  },

  /** Runs a confirmed proposal: the screen's own code, a bottom-bar button, or opening a place. */
  async apply(message, route, router, variant = null) {
    if (!this.canApply(message, route)) return;
    const p = message.proposal;
    message.status = 'running';
    message.statusText = null;
    try {
      if (p.type === 'press') {
        const button = bar.pressable.find((b) => b.label === p.button);
        if (!button) throw new Error(`${p.button} cannot be pressed now`);
        await bar.press(button);
      } else if (p.type === 'go') {
        const to = placesOf(route, this.screen)[p.place];
        if (!to) throw new Error(`${p.place} cannot be opened from here`);
        await router.push(to);
      } else {
        const outcome = await this.screen.actions[variant || p.type](p);
        if (typeof outcome === 'string') message.statusText = outcome;
      }
      message.status = 'done';
    } catch (error) {
      message.status = 'failed';
      message.statusText = error.message;
    }
    keep();
  },

  /** Everything the model is told about the screen: where it is, the work space, its buttons and places, and its own facts. */
  contextFor(route) {
    const context = { screen: route.fullPath.split('?')[0] };
    if (route.meta?.space && space.info) {
      const s = space.info;
      context.workSpace = {
        branch: s.id,
        startedBy: s.owner,
        purpose: purposeOf(s, app.info.mainBranch),
        buildsOn: s.baseline,
        mergesInto: s.targetLine,
        steps: space.steps.map((st) => `${st.label}${st.done ? ' (done)' : st.failing ? ' (failing)' : st.current ? ' (the next step)' : ''}`),
        domains: space.tables.map((t) => (space.changeOf(t) ? `${t} (${space.changeOf(t)} since the last commit)` : t)),
        lastCommit: s.lastMessage || null
      };
    }
    let xml = null;
    if (this.screen) {
      const own = this.screen.facts() || {};
      xml = own.xml || null;
      delete own.xml;
      delete own.places;
      Object.assign(context, own);
    }
    context.buttons = bar.pressable.map((b) => b.label);
    context.places = Object.keys(placesOf(route, this.screen));
    return { context, xml };
  }
});

function keep() {
  try {
    sessionStorage.setItem('assistant', JSON.stringify({ open: assistant.open, messages: assistant.messages.slice(-KEPT) }));
  } catch {
    // a private window: the conversation lasts as long as the page
  }
}

/** Where the assistant may take the person from here: the zones, the work space's steps, and the screen's own links. */
function placesOf(route, screen) {
  const places = { Applications: '/', Releases: '/releases' };
  if (app.author) {
    places['Work spaces'] = '/work';
    places['Clean up'] = '/tidy';
  }
  if (route.meta?.space && space.id) {
    places['the work space'] = spacePath(space.id);
    for (const step of space.steps) places[`step: ${step.label}`] = spacePath(space.id, step.to);
  }
  try {
    Object.assign(places, screen?.facts()?.places || {});
  } catch {
    // a screen still loading lists no places of its own
  }
  return places;
}

/** A screen's facts, and the actions the assistant may propose there, as the screen does them. */
export function useAssistant(facts, actions = null) {
  const owner = {};
  assistant.screen = markRaw({ facts, actions, owner });
  onBeforeUnmount(() => {
    if (assistant.screen?.owner === owner) assistant.screen = null;
  });
}

/** A test run at a tag or a commit, said in one line. */
export function testsText(report, running, error) {
  if (running) return 'running now';
  if (error) return `could not run: ${error}`;
  if (!report) return null;
  if (report.passed) return `all ${report.total} test cases pass`;
  const failing = (report.domains || []).filter((d) => d.error || d.failures?.length)
    .map((d) => (d.error ? `${d.domain}: cannot run (${d.error})` : `${d.domain}: ${d.failures.length} of ${d.total} fail`));
  return `${report.failed} failing — ${failing.join('; ')}`;
}

/** The screen a route is, by the names the server's guides use. */
export function pageOf(route) {
  const path = route.path || '';
  const step = route.meta?.step;
  if (route.meta?.space) {
    if (route.params.file) return 'table';
    if (step === 'test') return 'test';
    if (step === 'version') return 'commit';
    if (step === 'try') return 'try';
    if (step === 'land') return 'merge';
    if (/\/(pull|refresh)$/.test(path)) return 'pull';
    return 'space';
  }
  if (path === '/') return 'applications';
  if (path.startsWith('/applications/')) return 'application';
  if (path === '/work/new') return 'new-work';
  if (path === '/work') return 'work';
  if (path === '/releases') return 'releases';
  if (path === '/tidy') return 'tidy';
  if (/^\/commit\/[^/]+\/[^/]+/.test(path)) return 'running-table';
  if (path.startsWith('/commit/')) return 'commit-view';
  return null;
}

/** What to ask first, per screen: the questions the screen exists to answer. */
export const STARTERS = {
  applications: ['What does each application run now?', 'How do I deploy a new version?'],
  application: ['How do I deploy a tag here?', 'Deploy the newest tag', 'What runs here now, and who deployed it?'],
  work: ['How do I start a new work space?', 'Which work space should I continue?'],
  'new-work': ['What should my work space build on?', 'What is the difference between feature and hotfix?'],
  space: ['Review my changes', 'What is the next step?', 'Where do I add a rule?'],
  table: ['Explain this table', 'Add a rule for …', 'Write a test case for rule 1', 'Review my changes'],
  test: ['Run the tests', 'Why does a test fail?', 'Review my changes'],
  commit: ['Review my changes', 'Write the commit message', 'Where does this commit go?'],
  try: ['What does publishing do?', 'Publish to …', 'How do I undo it afterwards?'],
  merge: ['Review my changes', 'Where will this be merged?', 'How do I answer a conflict?'],
  pull: ['What does pulling do to my changes?'],
  releases: ['Should I make a tag or a release branch?', 'Tag the release branch', 'Create the next release branch'],
  'commit-view': ['What is in this commit?', 'How do I fix this version?'],
  'running-table': ['Explain this table', 'How do I turn my changes into a fix?'],
  tidy: ['What will be removed?', 'Can I get an archived work space back?']
};

/** The screen's name in the panel's header. */
export const PAGE_NAMES = {
  applications: 'Applications',
  application: 'Deploying to an application',
  work: 'Work spaces',
  'new-work': 'Starting a work space',
  space: 'A work space',
  table: 'Tables and rules',
  test: 'Testing',
  commit: 'Committing changes',
  try: 'Trying in an application',
  merge: 'Merging',
  pull: 'Pulling',
  releases: 'Releases: tags and branches',
  'commit-view': 'A commit',
  'running-table': 'A domain as it runs',
  tidy: 'Clean up'
};

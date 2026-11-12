<template>
  <div class="page-body">
    <h1>Commit your changes</h1>
    <SavedState :space-id="ws" />
    <p class="lede">A commit is what colleagues see, what can be tried in an environment, and what is merged. This is exactly what changed since the last one.</p>

    <p v-if="space.loading && !space.changes.length" class="loading mt-7">Reading what changed…</p>
    <p v-if="!space.loading && !space.changes.length" class="notice mt-7">Nothing has changed since the last commit{{ space.info?.lastMessage ? ` (“${space.info.lastMessage}”)` : '' }}.</p>

    <p v-if="untested.length" class="notice notice-needs mt-7">
      {{ untested.join(', ') }} {{ untested.length === 1 ? 'has' : 'have' }} not passed {{ untested.length === 1 ? 'its' : 'their' }} tests since the last change. You can still commit; merging will refuse it until they pass.
      <router-link :to="spacePath(ws, '/test')" class="ml-1 font-semibold">Run the tests</router-link>
    </p>

    <section v-for="t in space.changedTables" :key="t" class="mt-10 grid gap-3.5">
      <header class="flex items-baseline gap-3.5 border-b border-rule pb-2">
        <h2 class="text-lg">{{ t }}</h2>
        <span class="state state-edit">{{ { new: 'New domain', removed: 'Removed', changed: 'Changed' }[space.changeOf(t)] }}</span>
      </header>
      <div v-if="!reviews[t]" class="loading text-sm">Comparing…</div>
      <template v-else>
        <p v-if="reviews[t].error" class="notice notice-needs">{{ reviews[t].error }}</p>
        <RuleDiff v-if="reviews[t].diff" :diff="reviews[t].diff" />
        <p v-if="reviews[t].newRules !== undefined" class="meta">{{ reviews[t].newRules }} {{ reviews[t].newRules === 1 ? 'rule' : 'rules' }} in {{ reviews[t].newTables.join(', ') }}.</p>
        <div v-if="reviews[t].tests" class="mt-0.5 grid gap-1.5">
          <p class="meta">Test cases: {{ reviews[t].tests.text }}</p>
          <!-- Which cases, and in a changed one which values: what a reviewer needs to see, not a count. -->
          <ul v-if="reviews[t].tests.cases.length" class="m-0 grid list-none gap-1 p-0 text-sm">
            <li v-for="(c, i) in reviews[t].tests.cases.slice(0, 12)" :key="i" class="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
              <span class="text-ink-2">{{ c.table }}</span>
              <strong class="font-semibold">{{ c.name }}</strong>
              <span v-if="c.kind !== 'changed'" :class="c.kind === 'added' ? 'state state-edit' : 'meta line-through'">{{ c.kind }}</span>
              <span v-for="(d, j) in c.cells" :key="j" class="inline-flex flex-wrap items-baseline gap-x-1.5">
                <span class="meta">{{ d.column }}</span>
                <s class="font-mono text-[0.8125rem] text-ink-2">{{ d.before || '–' }}</s>
                <span class="text-ink-3" aria-label="becomes">→</span>
                <span class="rounded-sm bg-edit-tint px-1 font-mono text-[0.8125rem] font-semibold text-edit-ink">{{ d.after || '–' }}</span>
              </span>
            </li>
          </ul>
          <p v-if="reviews[t].tests.cases.length > 12" class="meta">and {{ reviews[t].tests.cases.length - 12 }} more.</p>
        </div>
      </template>
    </section>

    <div v-if="space.changes.length" class="mt-12 max-w-[720px]">
      <div class="flex flex-wrap items-end justify-between gap-2">
        <label class="label mb-0" for="version-message">What does this commit change, and why?</label>
        <!-- The assistant writes it from what changed; what is already written is kept as the why. -->
        <button v-if="app.info.assistant" type="button" class="btn btn-quiet btn-small" :disabled="writing" :aria-busy="writing ? 'true' : undefined" @click="writeMessage">
          <Sparkles aria-hidden="true" /> {{ writing ? 'Writing…' : message.trim() ? 'Improve it' : 'Write it for me' }}
        </button>
      </div>
      <textarea id="version-message" ref="messageBox" v-model="message" class="field mt-1.5" :rows="message.split('\n').length > 3 ? 8 : 3" placeholder="for example: raise the EU discount above 500 to 12%, agreed with finance"></textarea>
      <p class="meta mt-2">Colleagues read this when their change meets yours, so say why, not just what.</p>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { Sparkles } from 'lucide-vue-next';
import { useRoute, useRouter } from 'vue-router';
import RuleDiff from '../components/RuleDiff.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { assistant, useAssistant } from '../lib/assistant';
import { DmnDocument } from '../lib/dmnModel';
import { space } from '../lib/space';
import SavedState from '../components/SavedState.vue';
import { SCENARIO, caseKey, expectationName, isExpectation, parseSpec } from '../lib/testSpec';
import { app, spaceIdFromRoute, spacePath } from '../store';

const route = useRoute();
const router = useRouter();
const ws = spaceIdFromRoute(route.params);
const reviews = reactive({});
const message = ref('');
const messageBox = ref(null);
const saving = ref(false);
const writing = ref(false);

// A message from what changed, the first line written by the assistant; the person's words kept.
async function writeMessage() {
  writing.value = true;
  try {
    const written = await assistant.commitMessage(route, message.value.trim());
    if (written) {
      message.value = written;
      app.say('The assistant wrote the commit message from what changed. Read it, change it as you like, then commit.');
      nextTick(() => messageBox.value?.focus());
    } else {
      app.say('There is nothing to write a message for: nothing has changed since the last commit.', 'needs');
    }
  } catch (e) {
    app.say(`No message was written: ${e.message}`, 'needs');
  } finally {
    writing.value = false;
  }
}

const untested = computed(() => space.changedTables
  .filter((t) => space.changeOf(t) !== 'removed')
  .filter((t) => !app.verdictsFor(ws)[t]?.passed));

async function review(t) {
  const out = {};
  try {
    const change = space.changeOf(t);
    const [savedDmn, savedTests] = await Promise.all([
      api.savedContent(ws, `dmn/${t}.dmn`),
      api.savedContent(ws, `yaml/${t}.yaml`)
    ]);
    if (change === 'removed') {
      out.diff = null;
    } else {
      const [now, nowTests] = await Promise.all([api.readDmn(ws, t), api.readTests(ws, t)]);
      if (savedDmn === null) {
        const decisions = new DmnDocument(now.content).read();
        out.newRules = decisions.reduce((n, d) => n + d.rules.length, 0);
        out.newTables = decisions.map((d) => d.name);
      } else {
        out.diff = await api.diff(savedDmn, now.content, 'last commit', 'now');
      }
      const names = Object.fromEntries(new DmnDocument(now.content).read().map((d) => [d.id, d.name]));
      out.tests = describeTests(savedTests, nowTests.content, names);
    }
  } catch (error) {
    out.error = `Could not compare ${t}: ${error.message}`;
  }
  reviews[t] = out;
}

// The cases added, changed and removed, each by its scenario; a changed one with the values it changed.
// A case is known by its inputs, so one whose inputs changed is one removed and one added.
function describeTests(before, after, names = {}) {
  const cases = (text) => {
    const map = new Map();
    try {
      for (const b of parseSpec(text || '')) b.rows.forEach((row) => map.set(caseKey(b, row), { block: b, row }));
    } catch {
      return null;
    }
    return map;
  };
  const a = cases(before);
  const b = cases(after);
  if (!b) return { text: 'the test file cannot be read', cases: [] };
  if (!a) return { text: `${b.size} cases`, cases: [] };
  const value = (c, column) => String(c.row[c.block.columns.indexOf(column)] ?? '').trim();
  const describe = (c, kind, cells = []) => ({
    kind,
    table: names[c.block.decision] || c.block.decision,
    name: value(c, SCENARIO) || 'a case with no name',
    cells
  });
  const added = [...b.keys()].filter((k) => !a.has(k)).map((k) => describe(b.get(k), 'added'));
  const removed = [...a.keys()].filter((k) => !b.has(k)).map((k) => describe(a.get(k), 'removed'));
  const changed = [...b.keys()].filter((k) => a.has(k) && JSON.stringify(a.get(k).row) !== JSON.stringify(b.get(k).row)).map((k) => {
    const was = a.get(k);
    const now = b.get(k);
    const cells = now.block.columns
      .filter((column) => column !== SCENARIO && value(was, column) !== value(now, column))
      .map((column) => ({ column: isExpectation(column) ? `expects ${expectationName(column)}` : column, before: value(was, column), after: value(now, column) }));
    // Only its name changed: say so, by the old name.
    if (!cells.length) cells.push({ column: 'renamed from', before: value(was, SCENARIO), after: value(now, SCENARIO) });
    return describe(now, 'changed', cells);
  });
  const parts = [added.length && `${added.length} added`, changed.length && `${changed.length} changed`, removed.length && `${removed.length} removed`].filter(Boolean);
  return { text: parts.length ? parts.join(', ') : 'unchanged', cases: [...changed, ...added, ...removed] };
}

onMounted(async () => {
  await space.open(ws);
  space.changedTables.forEach(review);
});

async function saveVersion() {
  if (!message.value.trim() || saving.value) return;
  saving.value = true;
  try {
    const greenBefore = space.testedOk;
    const result = await api.saveVersion(ws, message.value.trim());
    // Every changed table was green: the commit made from exactly those files is green too.
    if (greenBefore) space.seedSaved(result.commit, true);
    if (result.notSent) {
      app.say(`Committed ${result.shortCommit}: “${message.value.trim()}”. Colleagues on ${app.info.env} can see it; other instances cannot yet — ${result.notSent}.`, 'needs');
    } else {
      app.say(`Committed ${result.shortCommit}: “${message.value.trim()}”. Colleagues can see it now, on every instance.`, 'clean');
    }
    message.value = '';
    await space.refresh();
    router.push(spacePath(ws, space.tryAvailable ? '/try' : '/land'));
  } catch (error) {
    if (error.body?.code === 'BEHIND') {
      // Every git action starts from the newest commit: pull it first, then commit.
      app.say(`Not committed yet: ${error.message}.`, 'needs');
      await space.open(ws);
      router.push(spacePath(ws, '/refresh'));
    } else {
      app.say(`Nothing was committed: ${error.message}. Your changes are untouched.`, 'needs');
    }
  } finally {
    saving.value = false;
  }
}

// What changed, said per domain the way the review below shows it: rules added, changed, removed; cases.
function reviewText(t) {
  const r = reviews[t];
  if (!r) return 'still being compared';
  if (r.error) return r.error;
  const parts = [];
  if (r.newRules !== undefined) parts.push(`new, ${r.newRules} rules in ${r.newTables.join(', ')}`);
  for (const d of r.diff?.decisions || []) {
    if (d.change === 'UNCHANGED') continue;
    const count = {};
    for (const rule of d.rules || []) if (rule.change !== 'UNCHANGED') count[rule.change.toLowerCase()] = (count[rule.change.toLowerCase()] || 0) + 1;
    const rules = Object.entries(count).map(([k, n]) => `${n} ${k}`).join(', ');
    const policy = d.leftHitPolicy && d.rightHitPolicy && d.leftHitPolicy !== d.rightHitPolicy ? `, hit policy ${d.leftHitPolicy} → ${d.rightHitPolicy}` : '';
    parts.push(`${d.rightName || d.leftName} ${d.change.toLowerCase()}${rules ? ` (rules: ${rules})` : ''}${policy}`);
  }
  if (r.tests) {
    const which = r.tests.cases.slice(0, 8).map((c) => `“${c.name}” ${c.kind}${c.cells.length ? ` (${c.cells.map((d) => `${d.column} ${d.before || '–'} → ${d.after || '–'}`).join(', ')})` : ''}`);
    parts.push(`test cases: ${r.tests.text}${which.length ? ` — ${which.join('; ')}` : ''}`);
  }
  return parts.join('; ') || 'no rule changes';
}

useAssistant(() => ({
  now: !space.changes.length
    ? `Nothing has changed since the last commit${space.info?.lastMessage ? ` (“${space.info.lastMessage}”)` : ''}, so there is nothing to commit here. The next step is ${space.steps.find((st) => st.current)?.label || 'Merge'}.`
    : `${space.changedTables.join(', ')} changed since the last commit and can be committed now${message.value.trim() ? ', with the message in the box' : ' — it needs a message'}. ${untested.value.length
      ? `${untested.value.join(', ')} has not passed its tests since the last change — that does not stop the commit; only merging is refused until the tests pass, so running them first is advised.`
      : 'Its tests pass.'} The commit is made on this work space’s own branch, ${ws}${space.info?.targetLine ? `, not on ${space.info.targetLine}; Merge later takes it into ${space.info.targetLine}` : ''}.`,
  commitIsMadeOnBranch: ws,
  afterwardsMergeTakesItInto: space.info?.targetLine || null,
  changedSinceTheLastCommit: space.changes.length
    ? space.changedTables.map((t) => `${t} (${{ new: 'new domain', removed: 'removed', changed: 'changed' }[space.changeOf(t)]}): ${space.changeOf(t) === 'removed' ? 'removed' : reviewText(t)}`)
    : 'nothing',
  notPassedTheirTestsSinceTheLastChange: untested.value,
  messageWritten: message.value.trim() ? `“${message.value.trim()}”` : 'not yet',
  hasChanges: space.changes.length > 0,
  messageInBox: message.value.trim()
}), {
  async commit(p) {
    message.value = p.message;
    await saveVersion();
    if (message.value) throw new Error('nothing was committed — the bar below says why');
    return 'committed';
  },
  fill_message(p) {
    message.value = p.message;
    nextTick(() => messageBox.value?.focus());
    return 'it is in the message box';
  }
});

useBar(() => ({
  secondary: [{ label: 'Back to the work space', quiet: true, run: () => router.push(spacePath(ws)) }],
  primary: space.changes.length
    ? { label: 'Commit changes', run: saveVersion, busy: saving.value, disabled: !message.value.trim() }
    : null
}));
</script>

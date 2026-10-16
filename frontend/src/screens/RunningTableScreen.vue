<template>
  <div class="page-body wide screen pt-6">
    <p v-if="loadError" class="notice notice-needs">{{ loadError }}</p>
    <p v-else-if="!entry" class="loading">Reading {{ name }} {{ asWhat }}…</p>

    <template v-else>
      <header class="mb-5 grid gap-3.5">
        <div>
          <h1 class="text-xl">{{ name }} <span class="font-normal text-ink-2">{{ asWhat }}</span></h1>
          <p v-if="atCommit" class="mt-1.5 flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
            <span v-for="v in entry.versions" :key="v" class="tag">{{ v }}</span>
            <span>“{{ plainMessage(entry.message.split('\n')[0], app.info.mainBranch) }}”</span>
            <span class="meta">{{ entry.author }} · <span class="text-xs text-ink-3">{{ whenText(entry.when) }}</span> ·</span>
            <router-link :to="`/commit/${entry.sha}`" class="mono text-sm">{{ entry.shortSha }}</router-link>
          </p>
          <p v-if="atCommit && entry.domains.length > 1" class="mt-1 flex flex-wrap gap-x-2.5 gap-y-1 text-sm">
            <span class="meta">Other domains here:</span>
            <router-link v-for="d in entry.domains.filter((x) => x !== name)" :key="d" :to="`/commit/${entry.sha}/${d}`">{{ d }}</router-link>
          </p>
          <p v-else-if="!atCommit" class="mt-1.5 flex flex-wrap items-center gap-x-2.5 gap-y-1">
            <template v-if="entry.release">
              <span class="inline-flex items-center gap-1.5 font-semibold"><LineBullet :line="entry.line" :main-branch="app.info.mainBranch" size="sm" />Release {{ entry.line.slice(8) }}</span>
              <span class="tag">{{ entry.release }}</span>
            </template>
            <template v-else>
              <span class="state state-live">Draft</span>
              <span>{{ entry.owner === app.info.user ? 'your' : `${entry.owner}’s` }} {{ entry.workspace.split('/').slice(2).join('/') }}</span>
              <span class="meta inline-flex items-center gap-1.5">built on {{ builtOn }} · lands in <LineBullet :line="entry.line" :main-branch="app.info.mainBranch" size="sm" />{{ lineName(entry.line, app.info.mainBranch) }}</span>
            </template>
            <span class="meta">· {{ entry.release ? 'deployed' : 'published' }} by {{ entry.publishedBy }} <span class="text-xs text-ink-3">{{ whenText(entry.publishedAt) }}</span> ·</span>
            <router-link :to="`/commit/${entry.commit}`" class="mono text-sm">{{ entry.commit.slice(0, 7) }}</router-link>
          </p>
        </div>
        <p
          class="max-w-[90ch] rounded border px-3.5 py-2.5 text-sm"
          :class="edits.count || testsEdited ? 'edited border-edit bg-edit-tint text-ink' : 'border-dashed border-rule-strong text-ink-2'"
        >
          <template v-if="edits.count || testsEdited">
            <span class="state state-edit">{{ changeText }}</span>
            Only here, for trying. Nothing is saved{{ atCommit ? '; the version is unchanged' : `, and ${app.info.env} still runs the original` }}.
          </template>
          <template v-else-if="locked">This instance only deploys: look at the tables, run the tests and try values. Nothing here can be changed.</template>
          <template v-else>Change anything to try it: nothing here is saved or shipped.</template>
        </p>
      </header>

      <div class="mb-5 flex items-end gap-0.5 overflow-x-auto border-b border-rule" role="tablist" aria-label="Tables in this domain">
        <button
          v-for="d in decisions"
          :key="d.id"
          type="button"
          role="tab"
          :aria-selected="tab === d.id"
          :class="tabClass(tab === d.id, { edited: tabEdited(d) })"
          @click="tab = d.id"
        ><span v-if="tabEdited(d)" class="size-1.5 rounded-full bg-edit" aria-hidden="true"></span>{{ d.name }}<span v-if="tabEdited(d)" class="sr-only"> (changed)</span></button>
        <button type="button" role="tab" :aria-selected="tab === 'relations'" :class="tabClass(tab === 'relations')" @click="tab = 'relations'">
          <Workflow :size="16" :stroke-width="1.75" aria-hidden="true" /> How they connect
        </button>
      </div>

      <template v-if="tab === 'relations'">
        <RelationsView :decisions="decisions" :relations="relations" :readonly="locked" @open="(id) => (tab = id)" @use-result="useResult" @disconnect="disconnect" @rename-result="renameResult" />
        <EvaluatePanel
        v-if="decisions.some((d) => d.tabular)"
        class="overview-try mt-9"
        :decisions="decisions"
        :decision="overviewEnd"
        :ends="decisions.filter((d) => d.tabular).map((d) => d.id)"
        :start-at="overviewEnd.id"
        default-scope="whole"
        :xml="() => doc.serialize()"
        :blocks="blocks"
        :edited="edits.count > 0"
        @answer="(a) => (answer = a)"
      />
      <TestSets
        :readonly="locked"
        class="overview-sets mt-9"
        :decisions="decisions"
        :blocks="blocks"
        :saved-blocks="shippedBlocks"
        :results="verdict?.tests || null"
        :problems="[]"
        @edit-tests="editTests"
      />
      </template>

      <section v-else-if="decision" class="decision grid gap-6">
        <p v-if="!decision.tabular" class="notice">{{ decision.name }} is a single expression, not a table.</p>
        <template v-else>
          <RuleGrid
            :decision="decision"
            :readonly="locked"
            :cell-states="edits.cells"
            :column-states="edits.columns"
            :row-states="rowMarks"
            @cell="(r, side, c, v) => edit(() => doc.setCell(decision.id, r, side, c, v))"
            @description="(r, v) => edit(() => doc.setDescription(decision.id, r, v))"
            @add-rule="(after) => edit(() => doc.addRule(decision.id, after))"
            @remove-rule="(r) => edit(() => doc.removeRule(decision.id, r))"
            @move-rule="(r, delta) => edit(() => doc.moveRule(decision.id, r, delta))"
            @column="(side, i, v) => edit(() => (side === 'in' ? doc.updateInput(decision.id, i, v) : doc.updateOutput(decision.id, i, v)))"
            @add-column="(side) => edit(() => (side === 'in' ? doc.addInput(decision.id, { expression: 'input', typeRef: 'string' }) : doc.addOutput(decision.id, { name: 'result', typeRef: 'string' })))"
            @remove-column="(side, i) => edit(() => doc.removeColumn(decision.id, side, i))"
          />

          <EvaluatePanel
            :decisions="decisions"
            :decision="decision"
            :xml="() => doc.serialize()"
            :blocks="blocks"
            :edited="edits.count > 0"
            @answer="(a) => (answer = a)"
          />

          <TestSets
        :readonly="locked"
            v-if="decision.tabular"
            class="tests"
            :decisions="decisions"
            :blocks="blocks"
            :saved-blocks="shippedBlocks"
            :results="verdict?.tests || null"
            :problems="[]"
            :ends="[decision.id]"
            @edit-tests="editTests"
          />
        </template>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, markRaw, onMounted, ref, shallowRef } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Workflow } from 'lucide-vue-next';
import EvaluatePanel from '../components/EvaluatePanel.vue';
import TestSets from '../components/TestSets.vue';
import RelationsView from '../components/RelationsView.vue';
import RuleGrid from '../components/RuleGrid.vue';
import LineBullet from '../components/LineBullet.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { DmnDocument, chainEnds, chainNoteFor, chainOutputsOf, editStates, relationsOf } from '../lib/dmnModel';
import { blockFor, parseSpec, serializeSpec, testEdits } from '../lib/testSpec';
import { expectationColumn } from '../lib/testSpec';
import { app, lineName, plainMessage, spacePath, whenText } from '../store';

/*
 * A domain exactly as it runs (/running/:name) or exactly as it was at any commit
 * (/commit/:sha/:domain), to look at and try. Edits here are a scratch copy: coloured as
 * they are made, evaluated and tested on the spot, never saved — until someone chooses to start a
 * space from them.
 */
// A deploy-only instance (production) changes nothing, not even a scratch copy to try.
const locked = computed(() => !app.author);
const route = useRoute();
const router = useRouter();
const name = route.params.name || route.params.domain;
const sha = route.params.sha || null;
const atCommit = !!sha;
const asWhat = atCommit ? `at ${String(sha).slice(0, 7)}` : `as it runs in ${app.info.env}`;

const entry = ref(null);
const loadError = ref(null);
const original = ref({ dmn: '', yaml: '' });
const doc = shallowRef(null);
const decisions = ref([]);
const blocks = ref([]);
const tab = ref(null);
const answer = ref(null);
const verdict = ref(null);
const running = ref(false);
const starting = ref(false);

const shipped = computed(() => {
  try {
    return original.value.dmn ? new DmnDocument(original.value.dmn).read() : [];
  } catch {
    return [];
  }
});
const edits = computed(() => editStates(shipped.value, decisions.value));
const testsEdited = computed(() => serializeSpec(blocks.value) !== serializeSpec(parseSpec(original.value.yaml)));
const changeText = computed(() => {
  const n = edits.value.count;
  const parts = [];
  if (n) parts.push(`${n} ${n === 1 ? 'change' : 'changes'} to the rules`);
  if (testsEdited.value) parts.push('test cases changed');
  return parts.join(', ');
});
const decision = computed(() => decisions.value.find((d) => d.id === tab.value) || null);
const block = computed(() => {
  const d = decision.value;
  return d ? blocks.value.find((b) => b.decision === d.id) || blocks.value.find((b) => b.decision === d.name) || null : null;
});
const testChanges = computed(() => {
  const d = decision.value;
  const saved = d ? parseSpec(original.value.yaml).find((b) => b.decision === d.id || b.decision === d.name) : null;
  return testEdits(saved, block.value);
});
const builtOn = computed(() => {
  const b = entry.value?.baseline;
  if (atCommit) return '';
  if (!b) return 'an unknown starting point';
  return /^v\d/.test(b) ? b : lineName(b, app.info.mainBranch);
});
const rowMarks = computed(() => {
  const marks = { ...edits.value.rows };
  const add = (id, mark) => { marks[id] = marks[id] ? `${marks[id]} ${mark}` : mark; };
  if (answer.value?.overlap?.length) answer.value.overlap.forEach((id) => add(id, 'overlap'));
  else (answer.value?.matched || []).forEach((m) => add(m.rule, 'matched'));
  return marks;
});
const chainNote = (d) => chainNoteFor(decisions.value, d.id, blocks.value);
const tabEdited = (d) => !!edits.value.decisions[d.id]
  || d.rules.some((r) => edits.value.rows[r.id] === 'new' || Object.keys(edits.value.cells).some((k) => k.startsWith(`${r.id}|`)))
  || [...d.inputs, ...d.outputs].some((c) => edits.value.columns[c.id]);

function reread() {
  decisions.value = doc.value.read();
  if (tab.value !== 'relations' && !decisions.value.some((d) => d.id === tab.value)) tab.value = 'relations';
}

const relations = computed(() => relationsOf(decisions.value));
const ends = computed(() => chainEnds(decisions.value));
const overviewEnd = computed(() => ends.value[0] || decisions.value.filter((d) => d.tabular).slice(-1)[0] || decisions.value[0]);
const shippedBlocks = computed(() => parseSpec(original.value.yaml));
const nameOf = (id) => decisions.value.find((d) => d.id === id)?.name || id;

function edit(change, note) {
  try {
    change();
  } catch (error) {
    app.say(error.message, 'needs');
    return;
  }
  verdict.value = null;
  const { added, removed } = doc.value.syncRequirements();
  reread();
  if (note) app.say(note);
  added.forEach((a) => app.say(`${nameOf(a.to)} now uses ${nameOf(a.from)}’s result.`));
  removed.forEach((r) => app.say(`${nameOf(r.to)} no longer uses ${nameOf(r.from)}.`));
}

// Renaming a result also renames what its tests expect, so nothing silently stops checking it.
function renameResult(decisionId, oldName, newName, quiet = false) {
  const d = decisions.value.find((x) => x.id === decisionId);
  const index = d.outputs.findIndex((o) => o.name === oldName);
  if (index < 0) return;
  edit(() => doc.value.updateOutput(decisionId, index, { name: newName }), quiet ? null : `${d.name} now gives ${newName} instead of ${oldName}.`);
  editTests(() => {
    for (const b of blocks.value) {
      if (b.decision !== decisionId && b.decision !== d.name) continue;
      const c = b.columns.indexOf(expectationColumn(oldName));
      if (c >= 0) b.columns[c] = expectationColumn(newName);
    }
  });
}

function useResult(from, output, typeRef, to) {
  // Another table already gives this name, and names decide connections: give this one its own first.
  const source = decisions.value.find((d) => d.id === from);
  // Only a later table giving the name is renamed; the first one is what tables checking it already use.
  const first = decisions.value.find((d) => d.tabular && d.outputs.some((o) => o.name === output));
  const clash = first && first.id !== from;
  if (clash) {
    const fresh = doc.value.uniqueResultName(`${output}${source.name.replace(/[^A-Za-z0-9]/g, '')}`);
    renameResult(from, output, fresh, true);
    app.say(`${source.name}’s ${output} is now called ${fresh}, because another table also gives ${output}.`);
    output = fresh;
  }
  const target = decisions.value.find((d) => d.id === to);
  if (target.inputs.some((i) => i.expression.trim() === output)) return app.say(`${target.name} already checks ${output}.`);
  edit(() => doc.value.addInput(to, { label: output, expression: output, typeRef: typeRef || 'string' }),
    `${target.name} now checks ${output} from ${nameOf(from)} — here only, for trying.`);
}

function disconnect(to, via) {
  const target = decisions.value.find((d) => d.id === to);
  const index = target.inputs.findIndex((i) => i.expression.trim() === via);
  if (index >= 0) edit(() => doc.value.removeColumn(to, 'in', index), `${target.name} no longer checks ${via} — here only, for trying.`);
}

function editTests(change) {
  change();
  verdict.value = null;
}

function createBlock() {
  const d = decision.value;
  editTests(() => blocks.value.push(blockFor({ decision: d.id, suppliedVariables: d.inputs.map((i) => i.expression), outputs: d.outputs.map((o) => o.name) })));
}

function reset() {
  doc.value = markRaw(new DmnDocument(original.value.dmn));
  blocks.value = parseSpec(original.value.yaml);
  answer.value = null;
  verdict.value = null;
  reread();
  app.say(`Back to ${name} exactly ${asWhat}.`);
}

async function load() {
  try {
    let table;
    if (atCommit) {
      const [domain, info] = await Promise.all([api.commitDomain(sha, name), api.commit(sha)]);
      table = domain;
      entry.value = info;
    } else {
      throw new Error('open a domain from a version: /commit/<sha>/<file>');
    }
    original.value = { dmn: table.dmn || '', yaml: table.yaml || '' };
    doc.value = markRaw(new DmnDocument(original.value.dmn));
    blocks.value = parseSpec(original.value.yaml);
    tab.value = 'relations';
    reread();
  } catch (error) {
    loadError.value = error.status === 404 ? (atCommit ? error.message : `${name} does not run in ${app.info.env}.`) : error.message;
  }
}

async function runTests() {
  running.value = true;
  try {
    const status = await api.runTests(name, doc.value.serialize(), serializeSpec(blocks.value));
    const tests = status.tests || [];
    const failed = tests.filter((t) => !t.passed).length;
    verdict.value = { passed: status.result === 'passed', tests, failed };
    const what = edits.value.count || testsEdited.value ? 'with your changes' : asWhat;
    if (status.error && !tests.length) app.say(`The tests could not run: ${String(status.error).split('\n')[0]}`, 'needs');
    else if (failed) app.say(`${failed} of ${tests.length} test cases fail for ${name} ${what}.`, 'needs');
    else app.say(`All ${tests.length} test cases pass for ${name} ${what}.`, 'clean');
  } catch (error) {
    app.say(`The tests did not run: ${error.message}`, 'needs');
  } finally {
    running.value = false;
  }
}

/*
 * Turning a try into work: a work space built on exactly what runs — a fix to the release, or new work on
 * the draft's version — holding these changes, unsaved, ready to be tested and saved properly.
 */
async function startSpace() {
  starting.value = true;
  try {
    const stamp = new Date().toISOString().slice(5, 16).replace(/[-T:]/g, '');
    const baseline = atCommit ? (entry.value.versions[0] || entry.value.sha) : (entry.value.release || entry.value.commit);
    const created = await api.createSpace(`${name}-${stamp}`, baseline);
    const [dmn, tests] = await Promise.all([api.readDmn(created.id, name), api.readTests(created.id, name)]);
    await api.writeDmn(created.id, name, doc.value.serialize(), dmn.etag);
    if (testsEdited.value) await api.writeTests(created.id, name, serializeSpec(blocks.value), tests.etag);
    app.say(`Started ${created.name} on ${baseline.length === 40 ? baseline.slice(0, 7) : baseline}, with your changes in it. Test them and save a version when ready.`, 'clean');
    router.push(spacePath(created.id, `/table/${name}`));
  } catch (error) {
    app.say(`No space was started: ${error.message}. Your changes are still here.`, 'needs');
  } finally {
    starting.value = false;
  }
}

onMounted(load);

useBar(() => {
  if (!entry.value) return { secondary: [{ label: 'Back', quiet: true, run: () => router.push('/') }] };
  const changed = edits.value.count > 0 || testsEdited.value;
  const secondary = [{ label: 'Back', quiet: true, run: () => router.push('/') }];
  if (changed) {
    secondary.push({ label: atCommit ? 'Undo my changes' : 'Put back what runs', run: reset });
    const fixOf = atCommit ? entry.value.versions[0] : entry.value.release;
    if (app.author) secondary.push({ label: fixOf ? `Start a fix to ${fixOf} with these changes` : 'Start a work space with these changes', run: startSpace, disabled: starting.value });
  }
  return {
    secondary,
    primary: { label: verdict.value ? 'Run the tests again' : 'Run the tests', run: runTests, busy: running.value }
  };
});

const tabClass = (active, extra = {}) => [
  'tab -mb-px inline-flex h-[38px] items-center gap-1.5 border-b-2 px-3.5 whitespace-nowrap',
  active ? 'active border-ink font-bold text-ink' : 'border-transparent font-medium text-ink-2 hover:text-ink',
  extra
];
</script>

<template>
  <div class="page-body wide screen pt-5">
    <div v-if="loadError" class="notice notice-needs">
      <p><strong>{{ file }} could not be opened.</strong> {{ loadError }}</p>
    </div>

    <!-- Someone saved first: the three-way merge. There is no overwrite anywhere. -->
    <section v-else-if="merge" class="merge">
      <header class="max-w-[80ch]">
        <h1 class="text-xl">{{ merge.kind === 'dmn' ? `${file} was saved by someone else while you worked` : `The test cases for ${file} were saved by someone else while you worked` }}</h1>
        <p class="lede">Nothing was overwritten, and your edits are all still here. Everything you changed in different places is combined. Where you both changed the same thing, pick which one stays.</p>
      </header>
      <div class="mt-7 grid grid-cols-[minmax(300px,380px)_minmax(0,1fr)] items-start gap-8 max-[900px]:grid-cols-1">
        <ConflictList
          class="merge-list sticky top-0 max-[900px]:static"
          :conflicts="merge.conflicts"
          :resolutions="merge.resolutions"
          :focused="merge.focused"
          @choose="(id, key) => (merge.resolutions[id] = key)"
          @focus="(id) => (merge.focused = id)"
        />
        <div v-if="merge.kind === 'dmn' && focusedDecision" class="merge-table grid min-w-0 gap-3">
          <h3>{{ focusedDecision.name }} <span class="meta font-normal">— your version, the contested cells marked</span></h3>
          <RuleGrid
            :decision="focusedDecision"
            readonly
            :cell-states="mergeCellStates"
            :focus-rule="focusedConflict?.ruleId"
          />
        </div>
        <div v-else class="merge-table meta grid min-w-0 gap-3">
          <p>Each case is shown in the list with both versions of what it expects.</p>
        </div>
      </div>
    </section>

    <p v-else-if="!decisions.length && !loaded" class="loading">Opening {{ file }}…</p>
    <template v-else-if="decisions.length || loaded">
      <div class="mb-5 flex items-end gap-0.5 overflow-x-auto border-b border-rule" role="tablist" aria-label="Tables in this domain">
        <button
          v-for="d in decisions"
          :key="d.id"
          type="button"
          role="tab"
          :aria-selected="tab === d.id"
          :class="tabClass(tab === d.id, { flagged: tabFlagged(d), edited: tabEdited(d) })"
          @click="tab = d.id"
        >
          <span v-if="tabEdited(d)" class="size-1.5 rounded-full bg-edit" aria-hidden="true"></span>{{ d.name }}<span v-if="tabFlagged(d)" class="size-1.5 rounded-full bg-needs" aria-hidden="true"></span>
          <span v-if="tabEdited(d) || tabFlagged(d)" class="sr-only">{{ [tabEdited(d) && 'changed', tabFlagged(d) && 'has problems'].filter(Boolean).join(', ') }}</span>
        </button>
        <button type="button" role="tab" :aria-selected="tab === 'relations'" :class="tabClass(tab === 'relations')" @click="tab = 'relations'">
          <Workflow :size="16" :stroke-width="1.75" aria-hidden="true" /> How they connect
        </button>
        <button type="button" :class="[tabClass(false), 'add ml-auto !text-ink-3 hover:!text-ink']" @click="addDecision"><Plus :size="16" :stroke-width="1.75" aria-hidden="true" /> Table</button>
      </div>

      <template v-if="tab === 'relations'">
      <RelationsView
        :decisions="decisions"
        :relations="relations"
        @open="(id) => (tab = id)"
        @use-result="useResult"
        @disconnect="disconnect"
        @rename-result="renameResult"
      />
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
        :edited="dmnDirty"
        @answer="(a) => (answer = a)"
      />
      <TestSets
        class="overview-sets mt-9"
        :decisions="decisions"
        :blocks="blocks"
        :saved-blocks="savedBlocks"
        :results="verdict?.tests || null"
        :problems="problems"
        @edit-tests="editTests"
      />
      </template>

      <section v-else-if="decision" class="decision grid gap-4">
        <header class="flex flex-wrap items-center gap-x-7 gap-y-3">
          <input
            class="dname -ml-[7px] w-auto min-w-0 rounded border border-transparent bg-transparent px-1.5 py-0.5 text-xl font-bold tracking-[-0.012em] [field-sizing:content] hover:border-rule focus:border-ink focus:bg-ground focus:outline-none"
            :value="decision.name"
            aria-label="Table name"
            spellcheck="false"
            @change="edit(() => doc.renameDecision(decision.id, $event.target.value.trim() || decision.name))"
          />
          <div v-if="decision.tabular" class="flex items-center gap-2">
            <label class="meta" :for="`hp-${decision.id}`">When several rules match</label>
            <select :id="`hp-${decision.id}`" class="field h-8 w-auto" :value="decision.hitPolicy" @change="edit(() => doc.setHitPolicy(decision.id, $event.target.value, decision.aggregation))">
              <option v-for="p in HIT_POLICIES" :key="p.value" :value="p.value">{{ p.label }}</option>
            </select>
            <select v-if="decision.hitPolicy === 'COLLECT'" class="field h-8 w-auto" :value="decision.aggregation" aria-label="How collected results are combined" @change="edit(() => doc.setHitPolicy(decision.id, 'COLLECT', $event.target.value))">
              <option v-for="a in AGGREGATIONS" :key="a.value" :value="a.value">{{ a.label }}</option>
            </select>
          </div>
          <div class="ml-auto flex items-center gap-2.5 max-[900px]:ml-0">
            <span v-if="decision.requires.length" class="meta">Uses {{ decision.requires.map(nameOf).join(', ') }}</span>
            <template v-if="confirmRemove === decision.id">
              <span class="meta">Remove {{ decision.name }}?</span>
              <button type="button" class="btn btn-small btn-danger" @click="removeDecision(decision.id)">Remove</button>
              <button type="button" class="btn btn-small btn-quiet" @click="confirmRemove = null">Keep</button>
            </template>
            <button v-else-if="decisions.length > 1" type="button" class="btn btn-small btn-quiet" @click="confirmRemove = decision.id">Remove table</button>
          </div>
        </header>

        <p v-if="edits.count || testChanges.count" class="edited-line state state-edit -mt-1">{{ changeLine }} — shown in blue</p>

        <p v-if="!decision.tabular" class="notice">{{ decision.name }} is a single expression, not a table, so it is not edited here. Its result is still shown in how the tables connect.</p>

        <RuleGrid
          v-else
          :decision="decision"
          :fed-by="fedBy"
          :feeds="feeds"
          :cell-states="edits.cells"
          :column-states="edits.columns"
          :row-states="rowMarks"
          :sources="sourcesFor(decision.id)"
          @cell="(r, side, c, v) => edit(() => doc.setCell(decision.id, r, side, c, v))"
          @description="(r, v) => edit(() => doc.setDescription(decision.id, r, v))"
          @add-rule="(after) => edit(() => doc.addRule(decision.id, after))"
          @remove-rule="(r) => edit(() => doc.removeRule(decision.id, r), `Removed rule ${r + 1} from ${decision.name}.`)"
          @move-rule="(r, delta) => edit(() => doc.moveRule(decision.id, r, delta))"
          @column="(side, i, v) => changeColumn(decision, side, i, v)"
          @add-column="(side) => edit(() => (side === 'in' ? doc.addInput(decision.id, { expression: nextName('input'), typeRef: 'string' }) : doc.addOutput(decision.id, { name: nextName('result'), typeRef: 'string' })))"
          @remove-column="(side, i) => edit(() => doc.removeColumn(decision.id, side, i), 'Removed the column. Its cells in every rule went with it.')"
        />

        <EvaluatePanel
          v-if="decision.tabular"
          :decisions="decisions"
          :decision="decision"
          :xml="() => doc.serialize()"
          :blocks="blocks"
          :edited="dmnDirty"
          @answer="(a) => (answer = a)"
        />

        <TestSets
          v-if="decision.tabular"
          class="tests mt-7"
          :decisions="decisions"
          :blocks="blocks"
          :saved-blocks="savedBlocks"
          :results="verdict?.tests || null"
          :problems="problems"
          :ends="[decision.id]"
          @edit-tests="editTests"
        />
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, markRaw, onBeforeUnmount, onMounted, reactive, ref, shallowRef } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Plus, Workflow } from 'lucide-vue-next';
import ConflictList from '../components/ConflictList.vue';
import EvaluatePanel from '../components/EvaluatePanel.vue';
import TestSets from '../components/TestSets.vue';
import RelationsView from '../components/RelationsView.vue';
import RuleGrid from '../components/RuleGrid.vue';
import { api } from '../lib/api';
import { useBar } from '../lib/bar';
import { AGGREGATIONS, DmnDocument, HIT_POLICIES, chainEnds, chainNoteFor, chainOutputsOf, editStates, relationsOf } from '../lib/dmnModel';
import { space } from '../lib/space';
import { blockFor, isExpectation, mergeSpecs, parseSpec, serializeSpec, SCENARIO, testEdits } from '../lib/testSpec';
import { expectationColumn } from '../lib/testSpec';
import { app, spaceIdFromRoute, spacePath } from '../store';

// Unsaved edits survive leaving the screen: a draft is never thrown away by navigation.
const drafts = window.__camundaEvalDrafts || (window.__camundaEvalDrafts = new Map());

const route = useRoute();
const router = useRouter();
const ws = spaceIdFromRoute(route.params);
const file = route.params.file;
const draftKey = `${ws || ''}|${file}`;

const doc = shallowRef(null);
const decisions = ref([]);
const blocks = ref([]);
const base = reactive({ dmn: { content: '', etag: null }, tests: { content: '', etag: null } });
const dmnDirty = ref(false);
const testsDirty = ref(false);
const loaded = ref(false);
const loadError = ref(null);
const tab = ref(null);
const hints = ref([]);
const problems = ref([]);
const saving = ref(false);
const running = ref(false);
const merge = ref(null);
const confirmRemove = ref(null);

const dirty = computed(() => dmnDirty.value || testsDirty.value);
const answer = ref(null);

// Everything changed since the last save, in blue: compared rule by rule against what is stored.
const savedDecisions = computed(() => {
  try {
    return base.dmn.content ? new DmnDocument(base.dmn.content).read() : [];
  } catch {
    return [];
  }
});
const edits = computed(() => editStates(savedDecisions.value, decisions.value));
const rowMarks = computed(() => {
  const marks = { ...edits.value.rows };
  const add = (id, mark) => { marks[id] = marks[id] ? `${marks[id]} ${mark}` : mark; };
  if (answer.value?.overlap?.length) answer.value.overlap.forEach((id) => add(id, 'overlap'));
  else (answer.value?.matched || []).forEach((m) => add(m.rule, 'matched'));
  return marks;
});
const decision = computed(() => decisions.value.find((d) => d.id === tab.value) || null);
const relations = computed(() => relationsOf(decisions.value));
const verdict = computed(() => app.verdictsFor(ws)[file] || null);
// Results of the file's other tables, offered when choosing what a condition checks. Tables that use
// this one are left out: checking their result would make a loop.
const sourcesFor = (id) => {
  const usesMe = (d, seen = new Set()) => !seen.has(d.id) && (seen.add(d.id), d.requires.includes(id) || d.requires.some((r) => {
    const next = decisions.value.find((x) => x.id === r);
    return next && usesMe(next, seen);
  }));
  return decisions.value
    .filter((d) => d.id !== id && d.tabular && !usesMe(d))
    .flatMap((d) => d.outputs.map((o) => ({ name: o.name, typeRef: o.typeRef, label: o.label, from: d.id, fromName: d.name })));
};
const changeLine = computed(() => {
  const parts = [];
  const n = edits.value.count;
  const t = testChanges.value.count;
  if (n) parts.push(`${n} ${n === 1 ? 'change' : 'changes'} to the rules${edits.value.removedRules ? ` (${edits.value.removedRules} removed)` : ''}`);
  if (t) parts.push(`${t} ${t === 1 ? 'change' : 'changes'} to the test cases`);
  return `${parts.join(' and ')} not saved`;
});
const chainNote = (d) => chainNoteFor(decisions.value, d.id, blocks.value);
const tabEdited = (d) => !!edits.value.decisions[d.id]
  || d.rules.some((r) => edits.value.rows[r.id] === 'new' || Object.keys(edits.value.cells).some((k) => k.startsWith(`${r.id}|`)))
  || [...d.inputs, ...d.outputs].some((c) => edits.value.columns[c.id]);
const nameOf = (id) => decisions.value.find((d) => d.id === id)?.name || id;

const block = computed(() => {
  const d = decision.value;
  if (!d) return null;
  return blocks.value.find((b) => b.decision === d.id) || blocks.value.find((b) => b.decision === d.name) || null;
});
// Test cases changed since they were stored, in blue like the rules.
const savedBlocks = computed(() => {
  try {
    return parseSpec(base.tests.content);
  } catch {
    return [];
  }
});
const testChanges = computed(() => {
  const d = decision.value;
  const saved = d ? savedBlocks.value.find((b) => b.decision === d.id) || savedBlocks.value.find((b) => b.decision === d.name) : null;
  return testEdits(saved, block.value);
});
const hintFor = (id) => hints.value.find((h) => h.decision === id) || null;
const problemsFor = (id) => {
  const name = nameOf(id);
  return problems.value.filter((p) => p.decision === id || p.decision === name);
};

// Which table produces the value an input checks, and which tables read a result.
const fedBy = computed(() => {
  const d = decision.value;
  const map = {};
  if (!d) return map;
  for (const l of relations.value.links) if (l.to === d.id) for (const v of l.via) map[v] = nameOf(l.from);
  return map;
});
const feeds = computed(() => {
  const d = decision.value;
  const map = {};
  if (!d) return map;
  for (const l of relations.value.links) {
    if (l.from !== d.id) continue;
    for (const v of l.via) map[v] = map[v] ? `${map[v]}, ${nameOf(l.to)}` : nameOf(l.to);
  }
  return map;
});

function reread() {
  decisions.value = doc.value.read();
  if (!decisions.value.some((d) => d.id === tab.value) && tab.value !== 'relations') {
    tab.value = decisions.value[0]?.id || null;
  }
}

function edit(change, note) {
  try {
    change();
  } catch (error) {
    app.say(error.message, 'needs');
    return;
  }
  dmnDirty.value = true;
  app.forgetVerdict(ws, file); // what was tested is no longer what is here
  const synced = doc.value.syncRequirements();
  reread();
  if (note) app.say(note);
  sayConnections(synced);
}

// Connections follow names, so say when an edit made or broke one.
function sayConnections({ added, removed }) {
  for (const a of added) app.say(`${nameOf(a.to)} now uses ${nameOf(a.from)}’s result.`);
  for (const r of removed) app.say(`${nameOf(r.to)} no longer uses ${nameOf(r.from)}: it checks none of its results.`);
}

const ends = computed(() => chainEnds(decisions.value));
const overviewEnd = computed(() => ends.value[0] || decisions.value.filter((d) => d.tabular).slice(-1)[0] || decisions.value[0]);

// Disconnecting is removing the condition that checks the other table's result.
function disconnect(to, via) {
  const target = decisions.value.find((d) => d.id === to);
  const index = target.inputs.findIndex((i) => i.expression.trim() === via);
  if (index < 0) return;
  edit(() => doc.value.removeColumn(to, 'in', index), `${target.name} no longer checks ${via}.`);
}

// A result picked in the overview, dropped on a table: that table gets a condition checking it.
// Renaming a result also renames what its tests expect, so nothing silently stops checking it.
/*
 * Changing a column: when what a condition checks, or what a result is called, is renamed, the test
 * cases follow — the column supplying that value, or expecting that result — so the tests keep
 * testing the same thing instead of silently no longer matching the table.
 */
function changeColumn(d, side, i, v) {
  if (side === 'out') {
    const before = d.outputs[i]?.name;
    edit(() => doc.value.updateOutput(d.id, i, v));
    if (before && v.name && v.name.trim() !== before) renameExpectations(d, before, v.name.trim());
    return;
  }
  const before = d.inputs[i]?.expression?.trim();
  edit(() => doc.value.updateInput(d.id, i, v));
  const after = v.expression?.trim();
  if (!before || !after || before === after) return;
  // Another table may still check the old name: then only this table's own test sets follow.
  const stillChecked = decisions.value.some((x) => x.id !== d.id && x.inputs.some((inp) => inp.expression.trim() === before));
  let renamed = 0;
  editTests(() => {
    for (const b of blocks.value) {
      if (stillChecked && b.decision !== d.id && b.decision !== d.name) continue;
      const c = b.columns.indexOf(before);
      if (c >= 0 && !b.columns.includes(after)) {
        b.columns[c] = after;
        renamed++;
      }
    }
  });
  if (renamed) app.say(`The test cases now supply ${after} instead of ${before}.`);
}

function renameExpectations(d, oldName, newName) {
  editTests(() => {
    for (const b of blocks.value) {
      if (b.decision !== d.id && b.decision !== d.name) continue;
      const c = b.columns.indexOf(expectationColumn(oldName));
      if (c >= 0) b.columns[c] = expectationColumn(newName);
    }
  });
}

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
  if (target.inputs.some((i) => i.expression.trim() === output)) {
    app.say(`${target.name} already checks ${output}.`);
    return;
  }
  edit(() => doc.value.addInput(to, { label: output, expression: output, typeRef: typeRef || 'string' }),
    `${target.name} now has a condition checking ${output} from ${nameOf(from)}. Every rule accepts any value until you say otherwise.`);
}

function editTests(change) {
  change();
  testsDirty.value = true;
  app.forgetVerdict(ws, file); // what was tested is no longer what is here
}

function nextName(prefix) {
  const used = new Set(decisions.value.flatMap((d) => [...d.inputs.map((i) => i.expression), ...d.outputs.map((o) => o.name)]));
  let n = 1;
  while (used.has(`${prefix}${n}`)) n++;
  return `${prefix}${n}`;
}

function addDecision() {
  let id;
  edit(() => { id = doc.value.addDecision(`Table ${decisions.value.length + 1}`); }, 'Added a table to this domain. Name it, then give it conditions and results.');
  if (id) tab.value = id;
}

function removeDecision(id) {
  const name = nameOf(id);
  confirmRemove.value = null;
  edit(() => doc.value.removeDecision(id), `Removed ${name}. Tables that used its results no longer do.`);
  blocks.value = blocks.value.filter((b) => b.decision !== id && b.decision !== name);
  testsDirty.value = true;
}

function createBlock() {
  const d = decision.value;
  const hint = hintFor(d.id) || { decision: d.id, suppliedVariables: d.inputs.map((i) => i.expression), outputs: d.outputs.map((o) => o.name) };
  editTests(() => blocks.value.push(blockFor({ ...hint, decision: d.id })));
}

function addTestColumn(column) {
  const b = block.value;
  if (!b || b.columns.includes(column)) return;
  editTests(() => {
    // Values supplied go before the expectations, so every row reads "given …, expects …".
    const firstExpectation = b.columns.findIndex((c) => isExpectation(c));
    const at = isExpectation(column) || firstExpectation < 0 ? b.columns.length : firstExpectation;
    b.columns.splice(at, 0, column);
    b.rows.forEach((row) => row.splice(at, 0, ''));
  });
}

// --- loading ---------------------------------------------------------------------------------------

async function load() {
  try {
    if (ws) space.open(ws);
    const saved = drafts.get(draftKey);
    const [dmn, tests] = await Promise.all([api.readDmn(ws, file), api.readTests(ws, file)]);
    if (saved) {
      // Restore unsaved work against the versions it was built on; a save will reconcile if they moved.
      doc.value = markRaw(new DmnDocument(saved.xml));
      blocks.value = parseSpec(saved.tests);
      Object.assign(base, saved.base);
      dmnDirty.value = saved.dmnDirty;
      testsDirty.value = saved.testsDirty;
      drafts.delete(draftKey);
      app.say(`Your unsaved edits to ${file} are back where you left them.`);
    } else {
      doc.value = markRaw(new DmnDocument(dmn.content));
      base.dmn = dmn;
      base.tests = tests;
      blocks.value = parseSpec(tests.content);
    }
    // A file may arrive with half-connections (a table checking another's result without using it):
    // put them in step now, visibly, as an unsaved change.
    const synced = doc.value.syncRequirements();
    if (synced.added.length || synced.removed.length) dmnDirty.value = true;
    // The overview of how the file's tables connect is where a file opens.
    tab.value = 'relations';
    reread();
    sayConnections(synced);
    loaded.value = true;
    refreshChecks();
  } catch (error) {
    loadError.value = error.status === 404 ? 'There is no such table in this work space.' : error.message;
  }
}

async function refreshChecks() {
  const [h, p] = await Promise.all([api.testHints(ws, file), api.testProblems(ws, file)]);
  hints.value = h;
  problems.value = p.problems;
}

// --- saving, with the version check and the merge when it fails ------------------------------------

async function save() {
  if (saving.value || !dirty.value) return;
  saving.value = true;
  const done = [];
  // What is being saved, exactly as it would be tested: a green result for this content stays green.
  const savingContent = doc.value.serialize() + '\u0000' + serializeSpec(blocks.value);
  try {
    if (dmnDirty.value) {
      const xml = doc.value.serialize();
      try {
        const { etag } = await api.writeDmn(ws, file, xml, base.dmn.etag);
        base.dmn = { content: xml, etag };
        dmnDirty.value = false;
        done.push('the table');
      } catch (error) {
        if (error.status === 412) return await beginDmnMerge(xml);
        throw error;
      }
    }
    if (testsDirty.value) {
      const text = serializeSpec(blocks.value);
      try {
        const stored = await api.writeTests(ws, file, text, base.tests.etag);
        base.tests = { content: stored.content, etag: stored.etag };
        blocks.value = parseSpec(stored.content);
        testsDirty.value = false;
        done.push('its test cases');
      } catch (error) {
        if (error.status === 412) return await beginTestsMerge(text);
        throw error;
      }
    }
    // A green result stays green when what was saved is exactly what was tested.
    if (verdict.value?.content !== savingContent) app.forgetVerdict(ws, file);
    app.say(`Saved ${done.join(' and ')} for ${file}.${ws ? ' Only you can see this until you save a version.' : ''}`, 'clean');
    refreshChecks();
    if (ws) space.refresh();
  } catch (error) {
    app.say(`${file} was not saved: ${error.message}. Your edits are still here.`, 'needs');
  } finally {
    saving.value = false;
  }
}

async function beginDmnMerge(oursXml) {
  const theirs = await api.readDmn(ws, file);
  const result = await api.mergePreview(base.dmn.content, oursXml, theirs.content, {});
  if (result.clean) {
    doc.value = markRaw(new DmnDocument(result.mergedXml));
    base.dmn = theirs;
    reread();
    app.say(`Someone else saved ${file} while you worked. You changed different things, so both are kept — save once more to store the combination.`, 'live');
    return;
  }
  merge.value = { kind: 'dmn', conflicts: result.conflicts, resolutions: {}, focused: result.conflicts[0]?.id || null, theirs, ours: oursXml };
  app.say(`Someone else saved ${file} while you worked. Nothing was overwritten; ${result.conflicts.length} ${result.conflicts.length === 1 ? 'place needs' : 'places need'} your answer.`, 'needs');
}

async function beginTestsMerge(oursText) {
  const theirs = await api.readTests(ws, file);
  const m = mergeSpecs(base.tests.content, oursText, theirs.content);
  if (!m.conflicts.length) {
    blocks.value = parseSpec(m.build({}));
    base.tests = theirs;
    app.say(`Someone else saved the test cases for ${file}. You changed different cases, so both are kept — save once more to store them.`, 'live');
    return;
  }
  merge.value = { kind: 'tests', conflicts: m.conflicts, resolutions: {}, focused: m.conflicts[0].id, theirs, merger: m };
  app.say(`Someone else saved the test cases for ${file}. Nothing was overwritten; ${m.conflicts.length} ${m.conflicts.length === 1 ? 'case needs' : 'cases need'} your answer.`, 'needs');
}

async function finishMerge() {
  const m = merge.value;
  if (m.kind === 'tests') {
    blocks.value = parseSpec(m.merger.build(m.resolutions));
    base.tests = m.theirs;
    testsDirty.value = true;
    merge.value = null;
    return save();
  }
  const result = await api.mergePreview(base.dmn.content, m.ours, m.theirs.content, m.resolutions);
  if (!result.clean) {
    // Kinds whose answers the server cannot apply yet come back unresolved; say which, keep everything.
    const open = result.conflicts.filter((c) => m.resolutions[c.id]);
    app.say(open.length
      ? `${open.length} of these answers can’t be combined automatically yet (${[...new Set(open.map((c) => c.kind.toLowerCase().replace(/_/g, ' ')))].join(', ')}). Nothing was saved; ask the colleague and re-apply one side by hand.`
      : 'New conflicts appeared — someone saved again. Answer the new ones.', 'needs');
    merge.value = { ...m, conflicts: result.conflicts, resolutions: Object.fromEntries(Object.entries(m.resolutions).filter(([id]) => result.conflicts.some((c) => c.id === id))) };
    return;
  }
  doc.value = markRaw(new DmnDocument(result.mergedXml));
  base.dmn = m.theirs;
  dmnDirty.value = true;
  merge.value = null;
  reread();
  return save();
}

const focusedConflict = computed(() => merge.value?.conflicts.find((c) => c.id === merge.value.focused) || merge.value?.conflicts[0] || null);
const focusedDecision = computed(() => {
  if (!merge.value || merge.value.kind !== 'dmn') return null;
  const ours = new DmnDocument(merge.value.ours).read();
  const id = focusedConflict.value?.decisionId;
  return ours.find((d) => d.id === id) || ours[0] || null;
});
const mergeCellStates = computed(() => {
  const states = {};
  for (const c of merge.value?.conflicts || []) {
    if (c.ruleId && c.columnId) states[`${c.ruleId}|${c.columnId}`] = merge.value.resolutions[c.id] ? 'resolved' : 'contested';
  }
  return states;
});

// --- tests -----------------------------------------------------------------------------------------

async function runTests() {
  if (running.value) return;
  running.value = true;
  app.say(`Running the tests for ${file}…`);
  try {
    const testedXml = doc.value.serialize();
    const testedSpec = serializeSpec(blocks.value);
    const status = await api.runTests(file, testedXml, testedSpec);
    const tests = status.tests || [];
    const failed = tests.filter((t) => !t.passed).length;
    const passed = status.result === 'passed' && !status.error;
    // The verdict belongs to exactly what was tested: kept across a save of the same content.
    app.recordVerdict(ws, file, { passed, failed, total: tests.length, tests, error: status.error || null, content: testedXml + '\u0000' + testedSpec });
    if (status.error && !tests.length) {
      app.say(`The tests for ${file} could not run: ${String(status.error).split('\n')[0]}`, 'needs');
    } else if (passed) {
      app.say(`All ${tests.length} test ${tests.length === 1 ? 'case passes' : 'cases pass'} for ${file}.`, 'clean');
    } else {
      app.say(`${failed} of ${tests.length} test cases fail for ${file}. The failing rows are marked.`, 'needs');
    }
  } catch (error) {
    app.say(`The tests for ${file} did not run: ${error.message}`, 'needs');
  } finally {
    running.value = false;
  }
}

// --- the bar ---------------------------------------------------------------------------------------

useBar(() => {
  const back = { label: ws ? 'Back to the work space' : 'All tables', quiet: true, run: () => router.push(spacePath(ws)) };
  if (loadError.value) return { secondary: [back] };
  if (merge.value) {
    const open = merge.value.conflicts.filter((c) => !merge.value.resolutions[c.id]).length;
    return {
      primary: { label: open ? `${open} still to answer` : 'Combine and save', run: finishMerge, disabled: open > 0 }
    };
  }
  if (dirty.value) {
    // Tests run on what is on screen, so they can be run before anything is saved.
    return {
      secondary: [back, { label: running.value ? 'Running the tests…' : verdict.value ? 'Run the tests again' : 'Run the tests', run: runTests, disabled: running.value }],
      primary: { label: 'Save', run: save, busy: saving.value }
    };
  }
  // A table untouched since the last version is not the next thing to do; the work space's step is.
  const untouched = ws && !space.changeOf(file) && !verdict.value;
  if (untouched && ['try', 'land'].includes(space.current)) {
    const step = space.steps.find((s) => s.key === space.current);
    return {
      secondary: [back, { label: 'Run the tests', run: runTests, disabled: running.value }],
      primary: { label: step.label, run: () => router.push(spacePath(ws, step.to)) }
    };
  }
  if (!verdict.value || !verdict.value.passed) {
    return { secondary: [back], primary: { label: verdict.value ? 'Run the tests again' : 'Run the tests', run: runTests, busy: running.value } };
  }
  if (ws && space.current === 'version') {
    return { secondary: [back], primary: { label: 'Review and save version', run: () => router.push(spacePath(ws, '/version')) } };
  }
  if (ws && space.current === 'test') {
    return { secondary: [back], primary: { label: 'Test the other changed tables', run: () => router.push(spacePath(ws, '/test')) } };
  }
  return { secondary: [{ ...back, quiet: false }] };
});

function onKey(event) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    save();
  }
}

function onUnload(event) {
  if (dirty.value) {
    event.preventDefault();
    event.returnValue = '';
  }
}

onMounted(() => {
  load();
  window.addEventListener('keydown', onKey);
  window.addEventListener('beforeunload', onUnload);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey);
  window.removeEventListener('beforeunload', onUnload);
  if (dirty.value && doc.value) {
    drafts.set(draftKey, {
      xml: doc.value.serialize(),
      tests: serializeSpec(blocks.value),
      base: JSON.parse(JSON.stringify(base)),
      dmnDirty: dmnDirty.value,
      testsDirty: testsDirty.value
    });
    app.say(`Your edits to ${file} are not saved yet. They are kept here until you come back and save.`, 'live');
  }
});
// --- the tabs --------------------------------------------------------------------------------------
const tabFlagged = (d) => problemsFor(d.id).some((p) => p.severity === 'ERROR');
const tabClass = (active, extra = {}) => [
  'tab -mb-px inline-flex h-[38px] items-center gap-1.5 border-b-2 px-3.5 whitespace-nowrap',
  active ? 'active border-ink font-bold text-ink' : 'border-transparent font-medium text-ink-2 hover:text-ink',
  extra
];
</script>

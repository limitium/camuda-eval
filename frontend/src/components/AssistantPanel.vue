<template>
  <!-- The assistant: docked beside the page on a wide screen; on a narrower one a drawer over the page
       (docked, it would leave the page too narrow to use), stopping above the bar so the next action
       stays in reach; the whole width on a phone. -->
  <aside
    id="assistant"
    class="flex w-[min(400px,100vw)] flex-none flex-col border-l border-rule-strong bg-ground max-lg:fixed max-lg:top-[var(--top)] max-lg:right-0 max-lg:bottom-[var(--bar)] max-lg:z-40 max-lg:border-b max-lg:shadow-[-16px_0_40px_-20px_oklch(20%_0.02_260/0.4)] max-md:inset-x-0 max-md:bottom-0 max-md:w-auto max-md:border-x-0 max-md:border-b-0 max-md:shadow-none"
    aria-labelledby="assistant-title"
  >
    <header class="flex flex-none items-start gap-2 border-b border-rule px-4 pt-3 pb-2.5">
      <div class="min-w-0 flex-1">
        <h2 id="assistant-title" class="text-lg">Assistant</h2>
        <p class="m-0 truncate text-sm text-ink-2">{{ pageName ? `Helping with: ${pageName}` : 'Helping with this screen' }}</p>
      </div>
      <button v-if="assistant.messages.length" type="button" class="btn btn-quiet btn-small" :disabled="assistant.busy" @click="assistant.clear()">New conversation</button>
      <button type="button" class="-mr-1.5 grid size-8 flex-none place-items-center rounded text-ink-2 hover:bg-hover hover:text-ink" aria-label="Close the assistant" title="Close" @click="assistant.close()"><X :size="18" :stroke-width="1.75" /></button>
    </header>

    <div ref="scroller" class="min-h-0 flex-1 overflow-auto px-4 py-4">
      <div v-if="!assistant.messages.length" class="grid gap-3">
        <p class="m-0 text-sm text-ink-2">Ask about this screen, or ask for something to be done here — {{ page === 'table' ? 'a rule, a test case, a changed cell' : 'a deploy, a tag, a commit, a button pressed' }}. It reads what the screen shows, and does nothing until you confirm its card.</p>
        <div v-if="starters.length" class="flex flex-wrap gap-1.5">
          <button v-for="q in starters" :key="q" type="button" class="btn-option h-auto min-h-7 py-1 text-left whitespace-normal" @click="startWith(q)">{{ q }}</button>
        </div>
      </div>

      <ol class="m-0 grid list-none gap-5 p-0" aria-live="polite">
        <li v-for="(m, i) in assistant.messages" :key="i" class="grid gap-1.5">
          <!-- The question: the person's own words, on the platform tint. -->
          <p v-if="m.role === 'user'" class="m-0 ml-8 rounded bg-platform px-3 py-2 whitespace-pre-wrap">{{ m.text }}</p>

          <p v-else-if="m.error" class="notice notice-needs m-0 text-sm">{{ m.text }}</p>

          <template v-else>
            <div class="answer grid gap-2 leading-relaxed">
              <template v-for="(b, j) in blocks(m.text)" :key="j">
                <p v-if="b.type === 'p'" class="m-0"><template v-for="(line, k) in b.lines" :key="k"><br v-if="k" /><Inline :parts="line" /></template></p>
                <component :is="b.type" v-else class="m-0 grid gap-1 pl-5" :class="b.type === 'ol' ? 'list-decimal' : 'list-disc'" :start="b.type === 'ol' ? b.start : undefined">
                  <li v-for="(item, k) in b.items" :key="k" class="pl-0.5"><Inline :parts="item" /></li>
                </component>
              </template>
            </div>

            <!-- One rule, as a row of its table. -->
            <section v-if="m.proposal?.type === 'add_rule'" class="grid gap-2 rounded border border-[oklch(84%_0.05_262)] bg-edit-tint px-3 py-2.5" :aria-label="`Proposed rule for ${m.proposal.tableName}`">
              <p class="m-0 text-sm font-semibold">New rule {{ m.proposal.position }} in {{ m.proposal.tableName }}</p>
              <!-- The rule as it reads: what it checks, then what it gives — one line per column, so nothing is cut at this width. -->
              <dl class="m-0 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 rounded-sm border border-rule bg-ground px-2.5 py-2 text-sm">
                <template v-for="(v, k) in m.proposal.inputs" :key="`i${k}`">
                  <dt class="text-ink-2"><span v-if="k === 0" class="mr-1.5 text-xs font-bold tracking-wide text-ink-3 uppercase">When</span>{{ m.proposal.columns?.inputs?.[k] }}</dt>
                  <dd class="m-0 font-mono text-[0.8125rem] break-words" :class="v === '-' ? 'text-ink-3' : ''">{{ v === '-' ? '- (any)' : v }}</dd>
                </template>
                <template v-for="(v, k) in m.proposal.outputs" :key="`o${k}`">
                  <dt class="text-ink-2" :class="k === 0 ? 'mt-1 border-t border-rule pt-1.5' : ''"><span v-if="k === 0" class="mr-1.5 text-xs font-bold tracking-wide text-ink-3 uppercase">Then</span>{{ m.proposal.columns?.outputs?.[k] }}</dt>
                  <dd class="m-0 font-mono text-[0.8125rem] break-words" :class="k === 0 ? 'mt-1 border-t border-rule pt-1.5' : ''">{{ v || '(empty)' }}</dd>
                </template>
              </dl>
              <p v-if="m.proposal.description" class="m-0 text-sm text-ink-2">{{ m.proposal.description }}</p>
              <p class="m-0 text-xs text-ink-3">Every cell was checked by the engine.</p>
              <Applying :message="m" :route="route" :label="`Add to ${m.proposal.tableName}`" done="Added, shown in blue — not saved until you press Save" />
            </section>

            <!-- One test case, with what the table gives for it today. -->
            <section v-else-if="m.proposal?.type === 'add_test'" class="grid gap-1.5 rounded border border-[oklch(84%_0.05_262)] bg-edit-tint px-3 py-2.5 text-sm" :aria-label="`Proposed test case for ${m.proposal.tableName}`">
              <p class="m-0 font-semibold">New test case for {{ m.proposal.tableName }}: “{{ m.proposal.scenario }}”</p>
              <p class="m-0">Supplies <template v-for="(v, k, n) in m.proposal.given" :key="k"><template v-if="n">, </template>{{ k }} <code class="var">{{ shown(v) }}</code></template></p>
              <p class="m-0">Expects <template v-for="(v, k, n) in m.proposal.expect" :key="k"><template v-if="n">, </template>{{ k }} <code class="var">{{ shown(v) }}</code></template></p>
              <p v-if="m.proposal.today" class="m-0" :class="m.proposal.today.agrees ? 'text-clean-ink' : 'text-live-ink'">
                <template v-if="m.proposal.today.agrees">The table gives that today: the case passes.</template>
                <template v-else-if="m.proposal.today.noMatch">Today no rule matches these values: the case fails until a rule does.</template>
                <template v-else-if="m.proposal.today.error">Today the table stops with: {{ m.proposal.today.error }}</template>
                <template v-else>Today the table gives <template v-for="(r, n) in m.proposal.today.results" :key="n"><template v-if="n">; </template><template v-for="(v, k, x) in r" :key="k"><template v-if="x">, </template>{{ k }} <code class="var">{{ shown(v) }}</code></template></template>: the case fails until the rules change.</template>
              </p>
              <Applying :message="m" :route="route" label="Add the test case" done="Added, shown in blue — not saved until you press Save" />
            </section>

            <!-- One cell: before and after. -->
            <section v-else-if="m.proposal?.type === 'change_cell'" class="grid gap-1.5 rounded border border-[oklch(84%_0.05_262)] bg-edit-tint px-3 py-2.5 text-sm" :aria-label="`Proposed change to rule ${m.proposal.rule} of ${m.proposal.tableName}`">
              <p class="m-0 font-semibold">Rule {{ m.proposal.rule }} of {{ m.proposal.tableName }}: {{ m.proposal.column }}</p>
              <p class="m-0 flex flex-wrap items-baseline gap-x-2 font-mono text-[0.8125rem]"><s class="text-ink-3 decoration-needs">{{ m.proposal.before || '(empty)' }}</s><span class="font-sans text-ink-3" aria-hidden="true">→</span><span class="sr-only">becomes</span><span class="font-semibold">{{ m.proposal.value || '(empty)' }}</span></p>
              <p class="m-0 text-xs text-ink-3">Checked by the engine.</p>
              <Applying :message="m" :route="route" label="Change the cell" done="Changed, shown in blue — not saved until you press Save" />
            </section>

            <!-- One rule out, shown as it reads so it is clear which. -->
            <section v-else-if="m.proposal?.type === 'remove_rule'" class="grid gap-1.5 rounded border border-[oklch(85%_0.06_25)] bg-needs-tint px-3 py-2.5 text-sm" :aria-label="`Proposed removal of rule ${m.proposal.rule} of ${m.proposal.tableName}`">
              <p class="m-0 font-semibold">Remove rule {{ m.proposal.rule }} of {{ m.proposal.tableName }}</p>
              <p class="m-0 font-mono text-[0.8125rem] break-words">{{ ruleText(m.proposal) }}</p>
              <p v-if="m.proposal.description" class="m-0 text-ink-2">{{ m.proposal.description }}</p>
              <Applying :message="m" :route="route" label="Remove the rule" done="Removed — not saved until you press Save" />
            </section>

            <!-- Any other action: what it does, in the server's words, and the button that does it. -->
            <section v-else-if="m.proposal?.card" class="grid gap-1.5 rounded border px-3 py-2.5 text-sm" :class="m.proposal.card.loud ? 'border-[oklch(86%_0.08_80)] bg-live-tint' : 'border-rule-strong bg-platform'" :aria-label="m.proposal.card.title">
              <p class="m-0 font-semibold">{{ m.proposal.card.title }}</p>
              <pre v-if="m.proposal.type === 'commit'" class="m-0 max-h-48 overflow-auto rounded-sm border border-rule bg-ground px-2.5 py-2 font-mono text-xs whitespace-pre-wrap">{{ m.proposal.message }}</pre>
              <p v-for="(line, k) in m.proposal.card.lines" :key="k" class="m-0 text-ink-2">{{ line }}</p>
              <Applying :message="m" :route="route" :label="m.proposal.card.confirm" :loud="m.proposal.card.loud" :also="m.proposal.type === 'commit' ? { label: 'Only put it in the message box', variant: 'fill_message' } : null" />
            </section>

            <!-- The review: the app's findings, as they are, beside the summary above. -->
            <section v-if="m.review" class="grid gap-2 rounded border border-rule-strong px-3 py-2.5 text-sm" aria-label="Review">
              <p class="m-0 font-semibold">Review of {{ m.review.scopeText }}</p>
              <p v-if="m.review.testsTotal" class="m-0" :class="m.review.testsPassed === m.review.testsTotal ? 'text-clean-ink' : 'text-needs-ink'">Tests: {{ m.review.testsPassed }} of {{ m.review.testsTotal }} cases pass</p>
              <ul v-if="m.review.findings.length" class="m-0 grid list-none gap-1 p-0">
                <li v-for="(f, k) in m.review.findings" :key="k" class="flex gap-2">
                  <span class="state flex-none" :class="f.severity === 'problem' ? 'state-needs' : 'state-live'">{{ f.severity === 'problem' ? 'fix' : 'look' }}</span>
                  <span class="min-w-0"><Inline :parts="inline(f.text)" /></span>
                </li>
              </ul>
              <p v-else class="m-0 text-clean-ink">Nothing found: every changed rule can apply, and no test case fails.</p>
              <details v-if="m.review.changes.length" class="text-ink-2">
                <summary class="cursor-pointer">What changed ({{ m.review.changes.length }})</summary>
                <ul class="m-0 mt-1 grid gap-0.5 pl-5"><li v-for="(c, k) in m.review.changes" :key="k" class="break-words">{{ c }}</li></ul>
              </details>
            </section>

            <div v-if="m.refused?.length" class="notice notice-live m-0 text-sm">
              <p class="m-0 font-semibold">Nothing to confirm: the app refused what was proposed.</p>
              <ul class="m-0 mt-1 grid gap-0.5 pl-5"><li v-for="(r, k) in m.refused" :key="k"><Inline :parts="inline(r)" /></li></ul>
              <p class="m-0 mt-1">Ask again with the exact values or names, or do it on the screen yourself{{ m.page === 'table' ? ' (DMN syntax shows how cells are written)' : '' }}.</p>
            </div>
          </template>
        </li>
        <li v-if="assistant.busy" class="loading text-sm">Thinking… {{ elapsed }} s</li>
      </ol>
    </div>

    <form class="flex-none border-t border-rule px-4 pt-3 pb-3.5" @submit.prevent="send">
      <label for="assistant-question" class="sr-only">Your question</label>
      <textarea
        id="assistant-question"
        ref="input"
        v-model="draft"
        class="field !min-h-[64px] text-md"
        rows="2"
        :placeholder="page === 'table' ? 'Ask about these rules, or ask for a rule, a test case, a change…' : 'Ask about this screen, or ask for something to be done…'"
        @keydown.enter.exact.prevent="send"
      ></textarea>
      <div class="mt-2 flex items-center justify-between gap-3">
        <span class="text-xs text-ink-3">Enter sends · Shift+Enter: new line</span>
        <button type="submit" class="btn btn-small" :disabled="!draft.trim() || assistant.busy">Send</button>
      </div>
    </form>
  </aside>
</template>

<script setup>
import { computed, defineComponent, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { X } from 'lucide-vue-next';
import { assistant, PAGE_NAMES, pageOf, STARTERS } from '../lib/assistant';
import { blocks, inline } from '../lib/richText';
import { toCellText } from '../lib/testSpec';

/*
 * The conversation with the assistant about the open screen. Answers are rendered from plain text —
 * bold, code and lists — never as HTML. A proposal is shown as what it does (a rule as a row of its
 * table, a test case with what the table gives today, a cell before and after, any other action in
 * the server's words) and is done only when the person presses its button, only while its screen is
 * open, through the same code as the screen's own buttons. A table edit lands unsaved, in blue.
 */
const route = useRoute();
const router = useRouter();
const draft = ref('');
const input = ref(null);
const scroller = ref(null);
const page = computed(() => pageOf(route));
const pageName = computed(() => PAGE_NAMES[page.value] || null);
const starters = computed(() => STARTERS[page.value] || []);
const shown = (v) => toCellText(v);

// Text, **bold** and `code` parts of a line.
const Inline = defineComponent({
  props: { parts: { type: Array, required: true } },
  setup(props) {
    return () => props.parts.map((p) => (p.t === 'b' ? h('strong', p.v) : p.t === 'code' ? h('code', { class: 'var' }, p.v) : p.v));
  }
});

// The button that does what was proposed — busy while it runs, then what came of it — or why not from here.
const Applying = defineComponent({
  props: { message: Object, route: Object, label: String, done: String, loud: Boolean, also: Object },
  setup(props) {
    return () => {
      const m = props.message;
      if (m.status === 'done') return h('p', { class: `m-0 ${props.done ? 'state state-edit' : 'state state-clean'}` }, props.done || `Done${m.statusText ? `: ${m.statusText}` : ''}`);
      const failed = m.status === 'failed' ? h('p', { class: 'm-0 font-semibold text-needs-ink' }, `Not done: ${m.statusText || 'it was refused'}`) : null;
      if (m.status === 'running' || assistant.canApply(m, props.route)) {
        const running = m.status === 'running';
        const buttons = [h('button', {
          type: 'button',
          class: `btn btn-small ${props.loud ? 'border-ink' : ''}`,
          disabled: running,
          'aria-busy': running ? 'true' : undefined,
          onClick: () => assistant.apply(m, props.route, router)
        }, props.label)];
        if (props.also) {
          buttons.push(h('button', { type: 'button', class: 'btn btn-quiet btn-small', disabled: running, onClick: () => assistant.apply(m, props.route, router, props.also.variant) }, props.also.label));
        }
        return h('div', { class: 'grid gap-1.5' }, [failed, h('div', { class: 'flex flex-wrap items-center gap-2' }, buttons)]);
      }
      return h('div', { class: 'grid gap-1' }, [failed, h('p', { class: 'm-0 text-xs text-ink-2' }, 'It can be done on the screen it was proposed on.')]);
    };
  }
});

// A rule as it reads: "when country "GB", market "LSE" → feeCode "UK-STAMP", …".
function ruleText(p) {
  const when = (p.inputs || []).map((v, k) => (v && v !== '-' ? `${p.columns?.inputs?.[k]} ${v}` : null)).filter(Boolean);
  const gives = (p.outputs || []).map((v, k) => `${p.columns?.outputs?.[k]} ${v || '(nothing)'}`);
  return `when ${when.length ? when.join(', ') : 'anything'} → ${gives.join(', ')}`;
}

function send() {
  const q = draft.value.trim();
  if (!q || assistant.busy) return;
  draft.value = '';
  assistant.ask(q, route);
}

// A starter that ends in "…" needs the person's own words: it is put in the box, not sent.
function startWith(q) {
  if (q.endsWith('…')) {
    draft.value = q.slice(0, -1);
    nextTick(() => input.value?.focus());
    return;
  }
  draft.value = q;
  send();
}

// Seconds since the question, while waiting: a local model takes a while, and the panel says so.
const elapsed = ref(0);
let timer = null;
watch(() => assistant.busy, (busy) => {
  clearInterval(timer);
  elapsed.value = 0;
  if (busy) timer = setInterval(() => elapsed.value++, 1000);
});

watch(() => [assistant.messages.length, assistant.busy], () => nextTick(() => {
  if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight;
}));

onMounted(() => {
  input.value?.focus();
  if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight;
});
onBeforeUnmount(() => clearInterval(timer));
</script>

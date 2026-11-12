<template>
  <!-- A modal dialog: the backdrop, Esc, keeping focus inside and handing it back are the browser's. -->
  <dialog
    ref="dialog"
    class="m-auto h-[min(880px,calc(100dvh-32px))] max-h-none w-[min(1040px,calc(100vw-32px))] max-w-none overflow-hidden rounded border border-rule-strong bg-ground p-0 text-left text-md text-ink shadow-[0_24px_64px_-24px_oklch(20%_0.02_260/0.45)] backdrop:bg-[oklch(20%_0.02_260/0.4)]"
    aria-labelledby="syntax-title"
    @close="$emit('close')"
    @click="(e) => e.target === dialog && dialog.close()"
  >
    <!-- A fixed height, so the window does not jump about while the filter narrows what it shows. -->
    <div class="flex h-full flex-col">
      <header class="flex flex-none items-start gap-3 px-4 pt-4 pb-3 sm:px-5">
        <div class="min-w-0 flex-1">
          <h2 id="syntax-title" class="text-xl">DMN syntax</h2>
          <p class="meta mt-1 mb-0">Cells are written in FEEL. <strong class="font-semibold text-ink">Text always goes in double quotes</strong>, like <code class="var">"EU"</code>; a word without quotes is the name of an input, like <code class="var">amount</code>.</p>
          <p v-if="names.length" class="meta mt-1 mb-0">Inputs of this table: <template v-for="(n, i) in names" :key="n"><span class="var">{{ n }}</span><template v-if="i < names.length - 1">, </template></template></p>
        </div>
        <button type="button" class="-mt-1 -mr-2 grid size-8 flex-none place-items-center rounded text-ink-2 hover:bg-hover hover:text-ink" aria-label="Close" title="Close" @click="dialog.close()"><X :size="18" :stroke-width="1.75" /></button>
      </header>

      <div class="flex-none border-b border-rule px-4 sm:px-5">
        <div class="relative">
          <Search :size="16" :stroke-width="1.75" class="pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2 text-ink-3" aria-hidden="true" />
          <input
            ref="filter"
            v-model="query"
            type="search"
            class="field pl-8"
            aria-label="Filter the examples"
            placeholder="Filter: round, starts with, date…"
            autocomplete="off"
            spellcheck="false"
            @keydown.esc="clearFilter"
          />
        </div>
        <div v-if="!searching" class="mt-1 flex items-end overflow-x-auto overflow-y-hidden sm:gap-0.5" role="tablist" aria-label="Parts of the sheet">
          <button
            v-for="t in TABS"
            :id="`syntax-tab-${t.id}`"
            :key="t.id"
            type="button"
            role="tab"
            :aria-selected="tab === t.id"
            aria-controls="syntax-body"
            :class="tabClass(tab === t.id)"
            @click="tab = t.id"
          >{{ t.label }}</button>
        </div>
        <p v-else class="meta m-0 flex h-[39px] items-center">{{ found ? `${found} ${found === 1 ? 'entry matches' : 'entries match'}, from every part of the sheet` : 'Nothing matches' }}</p>
      </div>
      <p class="sr-only" aria-live="polite">{{ searching ? (found ? `${found} matching` : 'Nothing matches') : '' }}</p>

      <div
        id="syntax-body"
        ref="body"
        class="min-h-0 overflow-auto px-4 pb-6 sm:px-5"
        :role="searching ? undefined : 'tabpanel'"
        :aria-labelledby="searching ? undefined : `syntax-tab-${tab}`"
      >
        <p v-if="!searching" class="meta mt-4 mb-0 max-w-[80ch]">{{ TABS.find((t) => t.id === tab).intro }}</p>

        <div v-if="cellSections.length" class="gap-x-10 md:columns-2">
          <section v-for="s in cellSections" :key="s.key" class="break-inside-avoid pt-5">
            <h3 class="mb-1.5 text-sm font-bold">{{ s.heading }}</h3>
            <p v-if="s.note" class="meta mt-0 mb-1.5"><template v-for="(part, i) in pieces(s.note)" :key="i"><code v-if="i % 2" class="var">{{ part }}</code><template v-else>{{ part }}</template></template></p>
            <dl class="rows m-0">
              <div v-for="e in s.entries" :key="e.code" class="flex flex-wrap items-baseline gap-x-2.5 gap-y-0.5 py-1.5">
                <dt class="max-w-full"><code v-if="e.code" class="var select-all [overflow-wrap:anywhere]">{{ e.code }}</code><span v-else class="text-sm text-ink-3 italic">empty</span></dt>
                <dd class="m-0 min-w-[12ch] flex-1 text-sm text-ink-2"><template v-for="(part, i) in pieces(e.means)" :key="i"><code v-if="i % 2" class="var">{{ part }}</code><template v-else>{{ part }}</template></template></dd>
              </div>
            </dl>
          </section>
        </div>

        <section v-if="policies.length" class="max-w-[80ch] pt-5">
          <h3 class="mb-1.5 text-sm font-bold">When several rules match</h3>
          <dl class="rows m-0">
            <div v-for="p in policies" :key="p.dmn" class="py-1.5">
              <dt class="flex flex-wrap items-baseline gap-x-2 text-sm"><span class="font-semibold" :class="p.refused ? 'text-ink-3 line-through decoration-needs' : 'text-ink'">{{ p.label }}</span><span class="mono text-xs text-ink-3">{{ p.dmn }}</span></dt>
              <dd class="m-0 text-sm text-ink-2">{{ p.means }}</dd>
            </div>
          </dl>
        </section>

        <section v-for="g in refusedGroups" :key="g.where" class="pt-5">
          <h3 class="mb-1.5 text-sm font-bold">{{ g.heading }}</h3>
          <dl class="m-0 grid gap-x-10 border-t border-rule md:grid-cols-2">
            <div v-for="r in g.entries" :key="r.code" class="border-b border-rule py-1.5">
              <dt class="flex flex-wrap items-baseline gap-x-1.5 text-sm"><code class="var line-through decoration-needs [overflow-wrap:anywhere]">{{ r.code }}</code><span class="text-ink-3" aria-hidden="true">→</span><span class="sr-only">write instead</span><span v-if="r.prose" class="font-semibold">{{ r.instead }}</span><code v-else class="var select-all [overflow-wrap:anywhere]">{{ r.instead }}</code></dt>
              <dd class="m-0 text-sm text-ink-2"><template v-for="(part, i) in pieces(r.why)" :key="i"><code v-if="i % 2" class="var">{{ part }}</code><template v-else>{{ part }}</template></template></dd>
            </div>
          </dl>
        </section>

        <p v-if="searching && !found" class="meta mt-5 mb-0">Nothing on the sheet matches “{{ query.trim() }}”. Try one word, like round, date or text.</p>

        <section v-if="!searching" class="mt-6">
          <h3 class="mb-1.5 text-sm font-bold">Test cases are values</h3>
          <p class="meta m-0">In the test grid write <span class="var">EU</span> and <span class="var">100</span>, without quotes: what an input is given, not a condition.</p>
        </section>
      </div>
    </div>
  </dialog>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { Search, X } from 'lucide-vue-next';
import { CONDITION_SECTIONS, REFUSED, REFUSED_GROUPS, RESULT_SECTIONS, WHEN_SEVERAL_MATCH } from '../lib/dmnSyntax';

/*
 * The cheatsheet for writing cells, in a modal window over the table. It is long, so it is split into
 * tabs by kind of cell, and the filter looks through every tab at once: someone hunting for "round"
 * should not have to guess whether rounding lives under conditions or results. Examples are selectable
 * in one click (to copy); the table's own input names are listed, since cells can use them. Closed by
 * the close icon, Esc, or a click outside it.
 */
const props = defineProps({
  decision: { type: Object, default: null }
});
defineEmits(['close']);

const TABS = [
  { id: 'condition', label: 'Conditions', intro: 'A condition cell says which values of its column the rule applies to.' },
  { id: 'result', label: 'Results', intro: 'A result cell says what the rule gives when it matches.' },
  { id: 'several', label: 'Several match', intro: 'Set in the table header: what happens when more than one rule matches.' },
  { id: 'refused', label: 'Refused', intro: 'Written like this, the engine refuses the cell or quietly gets it wrong. Write the code after the arrow instead.' }
];

const dialog = ref(null);
const filter = ref(null);
const body = ref(null);
const tab = ref('condition');
const query = ref('');
const names = computed(() => (props.decision?.inputs || []).map((i) => i.expression).filter((e) => /^[A-Za-z_][\w.]*$/.test(e || '')));

// Every word typed must appear somewhere in the entry, its explanation, or its section's title or note.
const words = computed(() => query.value.toLowerCase().split(/\s+/).filter(Boolean));
const searching = computed(() => words.value.length > 0);
const hits = (...texts) => {
  const text = texts.join(' ').toLowerCase();
  return words.value.every((w) => text.includes(w));
};
const shown = (id) => searching.value || tab.value === id;

const cellSections = computed(() => [
  ...CONDITION_SECTIONS.map((s) => ({ ...s, tab: 'condition', key: `condition-${s.id}`, heading: searching.value ? `Conditions · ${s.title}` : s.title })),
  ...RESULT_SECTIONS.map((s) => ({ ...s, tab: 'result', key: `result-${s.id}`, heading: searching.value ? `Results · ${s.title}` : s.title }))
]
  .filter((s) => shown(s.tab))
  .map((s) => ({ ...s, entries: s.entries.filter((e) => !searching.value || hits(e.code, e.means, s.title, s.note || '')) }))
  .filter((s) => s.entries.length));
const policies = computed(() => (shown('several') ? WHEN_SEVERAL_MATCH.filter((p) => !searching.value || hits(p.label, p.dmn, p.means, 'several match hit policy')) : []));
const refusedGroups = computed(() => (shown('refused')
  ? REFUSED_GROUPS
    .map((g) => ({ ...g, heading: searching.value ? `Refused · ${g.title}` : g.title, entries: REFUSED.filter((r) => r.where === g.where && (!searching.value || hits(r.code, r.instead, r.why, g.title, 'refused'))) }))
    .filter((g) => g.entries.length)
  : []));
const found = computed(() => cellSections.value.reduce((n, s) => n + s.entries.length, 0) + policies.value.length
  + refusedGroups.value.reduce((n, g) => n + g.entries.length, 0));

// A new tab or a new filter starts reading from the top.
watch([tab, query], () => { if (body.value) body.value.scrollTop = 0; });

// Esc first empties the filter; on an empty filter it closes the sheet, as anywhere else in it.
function clearFilter(event) {
  if (!query.value) return;
  event.preventDefault();
  event.stopPropagation();
  query.value = '';
}

// The table screen's tab look, a little tighter so all four fit a phone. The underline sits on the
// row's rule rather than over it: pulled down over it, it would make the scrolling row scroll up and down.
const tabClass = (active) => [
  'tab inline-flex h-[38px] items-center border-b-2 px-2.5 text-sm whitespace-nowrap sm:px-3.5 sm:text-md',
  active ? 'border-ink font-bold text-ink' : 'border-transparent font-medium text-ink-2 hover:text-ink'
];
// Text, code, text, code…: the explanations mark code with backticks.
const pieces = (text) => text.split('`');

onMounted(() => {
  dialog.value.showModal();
  // With a mouse, typing straight away filters; on a touch screen that would only pop up the keyboard.
  if (window.matchMedia('(pointer: fine)').matches) filter.value.focus();
});
</script>

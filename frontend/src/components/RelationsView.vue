<template>
  <div class="relations grid gap-5" @keydown.esc="armed = null">
    <p class="min-h-[2.9em] max-w-[82ch] text-ink-2">
      <template v-if="armed">
        <strong class="font-bold text-ink">Now click the table that should use {{ armed.output }}</strong> from {{ nameOf(armed.from) }} — it gets a condition checking it.
        <button type="button" class="btn btn-small btn-quiet" @click="armed = null">Cancel</button>
      </template>
      <template v-else>How the tables in this domain feed each other, left to right: a table that checks a value another one gives uses that table’s result.<template v-if="!readonly"> To connect two, click a result, then the table that should use it.</template> Click a table’s name to open it.</template>
    </p>

    <!-- The tables as boxes, left to right in the order data flows: what each checks (a port on its
         left edge) and what it gives (a port on its right edge); a connection runs from a result's
         port to the value it feeds. -->
    <div class="overflow-x-auto border-y border-rule bg-platform">
      <div class="relative" :style="{ width: `${size.width}px`, minWidth: '100%', height: `${size.height}px` }">
        <svg :width="size.width" :height="size.height" class="pointer-events-none absolute inset-0 z-10 overflow-visible" aria-hidden="true">
          <g v-for="e in edges" :key="e.key">
            <path :d="e.d" fill="none" stroke="var(--color-platform)" :stroke-width="e.width + 4" stroke-linecap="round" />
            <path :d="e.d" fill="none" :stroke="e.colour" :stroke-width="e.width" :stroke-dasharray="e.dash" stroke-linecap="round" />
          </g>
          <!-- ports: drawn over the box edges and the link ends -->
          <g v-for="n in nodes" :key="`p-${n.id}`">
            <circle v-for="(i, k) in n.inputs" :key="`ip-${i}`" :cx="n.x" :cy="n.inY(k)" r="4.5"
                    fill="var(--color-ground)" :stroke="inputColour(n.id, i)" :stroke-width="linkedInputs.has(`${n.id}|${i}`) ? 3 : 1.75" />
            <circle v-for="(o, k) in n.outputs" :key="`op-${o.name}`" :cx="n.x + BOX_W" :cy="n.outY(k)" r="4.5"
                    :fill="n.colour" stroke="var(--color-ground)" stroke-width="1.5" />
          </g>
        </svg>

        <div
          v-for="n in nodes"
          :key="n.id"
          class="node absolute overflow-hidden rounded border bg-ground transition-[box-shadow,border-color] duration-100 ease-out"
          :class="[
            { target: armed && armed.from !== n.id, source: armed?.from === n.id },
            armed && armed.from !== n.id ? 'cursor-copy border-rule-strong hover:border-ink hover:ring-1 hover:ring-ink' : armed?.from === n.id ? 'border-ink ring-1 ring-ink' : 'border-rule-strong'
          ]"
          :style="{ left: `${n.x}px`, top: `${n.y}px`, width: `${BOX_W}px`, height: `${n.height}px` }"
          @click="armed && armed.from !== n.id && use(n.id)"
        >
          <!-- the table: its colour, its name (opens it) -->
          <div class="flex items-center gap-2 border-b border-rule px-3" :style="{ height: `${HEAD}px` }">
            <span class="size-3 flex-none rounded-full" :style="{ background: n.colour }" aria-hidden="true"></span>
            <button
              type="button"
              class="min-w-0 truncate border-0 bg-transparent p-0 text-left text-md font-bold"
              :class="armed && armed.from !== n.id ? 'underline decoration-dashed decoration-1 underline-offset-4' : 'hover:underline'"
              :title="armed ? (armed.from !== n.id ? `Use ${armed.output} in ${n.name}` : n.name) : `Open ${n.name}`"
              @click.stop="armed ? armed.from !== n.id && use(n.id) : $emit('open', n.id)"
            >{{ n.name }}</button>
          </div>
          <!-- what it checks: a port on the left for each -->
          <p class="px-3 text-xs font-semibold text-ink-3" :style="{ height: `${LABEL}px`, lineHeight: `${LABEL}px` }">checks</p>
          <ul class="m-0 list-none p-0" :aria-label="`${n.name} checks`" :class="armed?.from === n.id ? 'opacity-50' : ''">
            <li
              v-for="i in n.inputs"
              :key="`i${i}`"
              class="flex items-center gap-1.5 px-3 whitespace-nowrap"
              :class="linkedInputs.has(`${n.id}|${i}`) ? 'linked' : ''"
              :style="{ height: `${ROW}px` }"
              :title="fromOf(n.id, i) ? `${i}, from ${fromOf(n.id, i)}` : i"
            >
              <span class="truncate font-mono text-xs" :class="linkedInputs.has(`${n.id}|${i}`) ? 'font-semibold text-ink' : 'text-ink-2'">{{ i }}</span>
              <span v-if="fromOf(n.id, i)" class="min-w-0 truncate text-xs text-ink-3">from {{ fromOf(n.id, i) }}</span>
            </li>
            <li v-if="!n.inputs.length" class="flex items-center px-3 text-xs text-ink-3" :style="{ height: `${ROW}px` }">nothing</li>
          </ul>
          <!-- what it gives: a port on the right for each; pick one to use it in another table -->
          <p class="border-t border-rule px-3 text-right text-xs font-semibold text-ink-3" :style="{ height: `${LABEL}px`, lineHeight: `${LABEL}px` }">gives</p>
          <ul class="m-0 list-none p-0" :aria-label="`${n.name} gives`">
            <li v-for="o in n.outputs" :key="`o${o.name}`" class="flex justify-end px-2" :style="{ height: `${ROW}px` }">
              <button
                type="button"
                class="max-w-full truncate rounded-sm border-0 px-1.5 font-mono text-xs whitespace-nowrap transition-[background-color] duration-100"
                :class="armed?.from === n.id && armed.output === o.name ? 'armed bg-select font-semibold text-ink ring-1 ring-ink' : armed?.from === n.id ? 'bg-transparent text-ink-3 hover:bg-hover' : 'bg-transparent font-semibold text-ink hover:bg-hover'"
                :style="{ height: `${ROW - 4}px`, marginTop: '2px' }"
                :aria-pressed="armed?.from === n.id && armed.output === o.name"
                :title="readonly ? o.name : `Use ${o.name} in another table`"
                :disabled="readonly"
                @click.stop="!readonly && arm(n.id, o)"
              >{{ o.name }}</button>
            </li>
          </ul>
          <p v-for="b in n.broken" :key="`b${b.from}`" class="state state-needs !flex border-t border-rule px-3 !text-xs" :style="{ height: `${PROBLEM}px` }" :title="b.text"><span class="min-w-0 truncate">{{ b.text }}</span></p>
        </div>
      </div>
    </div>

    <section v-for="c in (readonly ? [] : relations.clashes)" :key="c.name" class="grid max-w-[900px] gap-2 rounded border border-[oklch(85%_0.06_25)] bg-needs-tint px-3.5 py-3">
      <p class="state state-needs">
        {{ c.ids.map(nameOf).join(', ').replace(/, ([^,]*)$/, ' and $1') }} {{ c.ids.length === 2 ? 'both' : 'all' }} give <span class="mono">{{ c.name }}</span>. A table checking {{ c.name }} can only use one of them — {{ nameOf(c.ids[0]) }}’s. Give the others their own names:
      </p>
      <form v-for="id in c.ids.slice(1)" :key="id" class="flex flex-wrap items-center gap-2.5" @submit.prevent="rename(id, c.name)">
        <label class="label !m-0 min-w-[120px]" :for="`rn-${id}`">{{ nameOf(id) }} gives</label>
        <input :id="`rn-${id}`" v-model="renames[id]" class="field mono !h-8 !w-[220px]" spellcheck="false" autocomplete="off" />
        <button type="submit" class="btn btn-small" :disabled="!validName(renames[id])">Rename</button>
      </form>
    </section>

    <section class="grid max-w-[900px] gap-3">
      <h3>Connections</h3>
      <div class="rows">
        <template v-for="l in relations.links.filter((x) => x.via.length)" :key="`${l.from}>${l.to}`">
          <div
            v-for="v in l.via"
            :key="v"
            class="grid min-h-12 grid-cols-[minmax(200px,auto)_1fr_auto] items-center gap-4 px-1 py-2 max-md:grid-cols-1 max-md:gap-1"
            :class="confirming === `${l.to}|${v}` ? 'bg-needs-tint' : ''"
          >
            <span class="inline-flex items-center gap-2"><strong class="font-semibold">{{ nameOf(l.from) }}</strong><ArrowRight class="flex-none text-ink-3" :size="16" :stroke-width="1.75" aria-label="feeds" /><strong class="font-semibold">{{ nameOf(l.to) }}</strong></span>
            <span v-if="confirming !== `${l.to}|${v}`" class="meta">{{ nameOf(l.to) }} checks <span class="mono">{{ v }}</span>, which {{ nameOf(l.from) }} gives</span>
            <span v-else class="state state-needs">Removes {{ nameOf(l.to) }}’s condition checking {{ v }}{{ entriesText(l.to, v) }}.</span>
            <span v-if="!readonly" class="flex gap-1.5">
              <button v-if="confirming !== `${l.to}|${v}`" type="button" class="btn btn-small btn-quiet" @click="confirming = `${l.to}|${v}`">Disconnect</button>
              <template v-else>
                <button type="button" class="btn btn-small btn-danger" @click="disconnect(l.to, v)">Disconnect</button>
                <button type="button" class="btn btn-small btn-quiet" @click="confirming = null">Keep</button>
              </template>
            </span>
          </div>
        </template>
        <p v-if="!relations.links.some((x) => x.via.length)" class="meta px-1 py-3">
          {{ decisions.length > 1 ? 'No table in this domain uses another’s results yet.' : 'This domain has one table, so there is nothing to connect.' }}
        </p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { ArrowRight } from 'lucide-vue-next';

/*
 * The tables of one file and how their results feed each other. There is one rule, and it is the
 * name: a table that checks a value another table gives is connected to it (the editor keeps the
 * file's records in step), so the picture never shows a half-connection to reconcile by hand.
 */
const props = defineProps({
  decisions: { type: Array, required: true },
  relations: { type: Object, required: true },
  // Look only: no result can be connected, disconnected or renamed.
  readonly: { type: Boolean, default: false }
});
const emit = defineEmits(['open', 'use-result', 'disconnect', 'rename-result']);



// Disconnecting removes the condition that reads the other table's result; say what goes with it.
const confirming = ref(null);
function disconnect(to, via) {
  confirming.value = null;
  emit('disconnect', to, via);
}
const entriesText = (to, via) => {
  const d = props.decisions.find((x) => x.id === to);
  const i = d ? d.inputs.findIndex((x) => x.expression.trim() === via) : -1;
  const n = i < 0 ? 0 : d.rules.filter((r) => r.inputs[i] && r.inputs[i].trim() !== '-' && r.inputs[i].trim() !== '').length;
  return n ? ` and what ${n} ${n === 1 ? 'rule says' : 'rules say'} about it` : ' (no rule uses it yet)';
};

// Box geometry: a header with the name, then "checks" and one row per value it checks (port on the
// left edge), then "gives" and one row per result (port on the right edge).
const BOX_W = 240;
const GAP_X = 132; // between columns: room for the links to bend
const GAP_Y = 28;
const HEAD = 36;
const LABEL = 20;
const ROW = 26;
const PROBLEM = 24;
const LEFT = 40;

// A result picked to be used elsewhere: { from, output, typeRef }.
const armed = ref(null);

const nameOf = (id) => props.decisions.find((d) => d.id === id)?.name || id;
const resultsOf = (d) => (d.tabular ? d.outputs.map((o) => ({ name: o.name, typeRef: o.typeRef })) : [{ name: d.variable || d.name, typeRef: '' }]).filter((o) => o.name);

function arm(from, output) {
  armed.value = armed.value?.from === from && armed.value.output === output.name ? null : { from, output: output.name, typeRef: output.typeRef };
}

function use(to) {
  const a = armed.value;
  armed.value = null;
  emit('use-result', a.from, a.output, a.typeRef, to);
}

const fromOf = (to, input) => {
  const link = props.relations.links.find((l) => l.to === to && l.via.includes(input));
  return link ? nameOf(link.from) : null;
};

// Each table runs its own route colour, in file order; its results leave in that colour.
const colourOfIndex = (i) => `var(--color-route-${(i % 6) + 1})`;

// A table that should use another but reads none of its results (or the other is gone): said in
// words under its station, and drawn as a dashed red route.
const brokenOf = (id) => props.relations.links
  .filter((l) => l.to === id && (l.missing || !l.via.length))
  .map((l) => ({
    from: l.from,
    missing: l.missing,
    text: l.missing ? `Uses ${l.from}, which is not in this file` : `Uses ${nameOf(l.from)} but checks none of its results`
  }));

// Which way each link that skips a column goes round (decided with the layout, used by around()).
const sides = new Map();
const nodes = computed(() => {
  const columns = new Map();
  for (const d of props.decisions) {
    const level = props.relations.depth.get(d.id) || 0;
    if (!columns.has(level)) columns.set(level, []);
    columns.get(level).push(d);
  }
  // Order each column so links cross as little as possible: a table sits near the tables it reads
  // from (a pass left to right), and the first column near the tables it feeds (a pass back).
  const ordered = [...columns].sort((a, b) => a[0] - b[0]).map(([, list]) => list);
  const rank = new Map();
  const place = () => ordered.forEach((list) => list.forEach((d, i) => rank.set(d.id, i)));
  place();
  const sourcesOf = (id) => props.relations.links.filter((l) => l.to === id && l.via.length).map((l) => l.from);
  const targetsOf = (id) => props.relations.links.filter((l) => l.from === id && l.via.length).map((l) => l.to);
  const centre = (ids, own) => (ids.length ? ids.reduce((n, x) => n + (rank.get(x) ?? 0), 0) / ids.length : own);
  for (let pass = 0; pass < 2; pass++) {
    for (let c = 1; c < ordered.length; c++) {
      ordered[c].sort((a, b) => centre(sourcesOf(a.id), rank.get(a.id)) - centre(sourcesOf(b.id), rank.get(b.id)));
      place();
    }
    for (let c = ordered.length - 2; c >= 0; c--) {
      ordered[c].sort((a, b) => centre(targetsOf(a.id), rank.get(a.id)) - centre(targetsOf(b.id), rank.get(b.id)));
      place();
    }
  }
  const out = [];
  ordered.forEach((list, col) => {
    let y = 24 + 12 * skipCount.value; // room above for links that pass over a column
    for (const d of list) {
      const inputs = d.inputs.map((i) => i.expression.trim()).filter(Boolean);
      const outputs = resultsOf(d);
      const broken = brokenOf(d.id);
      const inRows = Math.max(inputs.length, 1);
      const outTop = HEAD + LABEL + inRows * ROW + LABEL;
      const height = outTop + outputs.length * ROW + broken.length * PROBLEM + 6;
      const top = y;
      out.push({
        id: d.id, name: d.name, inputs, outputs, broken, col,
        colour: colourOfIndex(props.decisions.indexOf(d)),
        x: LEFT + col * (BOX_W + GAP_X), y: top, height,
        inY: (k) => top + HEAD + LABEL + k * ROW + ROW / 2,
        outY: (k) => top + outTop + k * ROW + ROW / 2
      });
      y += height + GAP_Y;
    }
  });
  // In the picture, a table's linked values are listed in the order their links arrive (top to
  // bottom), after the values nothing feeds, so links never cross at its edge. A link from the
  // next column arrives at its result's height; one that passes over a column arrives from above
  // the boxes, one passing under from below (decided as in around()).
  const byId = new Map(out.map((n) => [n.id, n]));
  sides.clear();
  for (const n of out) {
    const arrival = (input) => {
      const l = props.relations.links.find((x) => x.to === n.id && x.via.includes(input));
      const a = l && byId.get(l.from);
      if (!a) return null;
      const yOut = a.outY(Math.max(0, a.outputs.findIndex((o) => o.name === input)));
      if (n.col - a.col <= 1) return yOut;
      const between = out.filter((m) => m.col > a.col && m.col < n.col);
      const top = Math.min(...between.map((m) => m.y));
      const bottom = Math.max(...between.map((m) => m.y + m.height));
      // Over or under: the side that crosses fewer of the links leaving the same column, then the nearer.
      const leaving = props.relations.links.flatMap((x) => {
        const s0 = byId.get(x.from);
        const t0 = byId.get(x.to);
        return s0 && t0 && s0.col === a.col && t0.col === a.col + 1 ? x.via.map((v) => s0.outY(Math.max(0, s0.outputs.findIndex((o) => o.name === v)))) : [];
      });
      const overCross = leaving.filter((y) => y < yOut).length;
      const underCross = leaving.filter((y) => y > yOut).length;
      const over = overCross !== underCross ? overCross < underCross : yOut - top < bottom - yOut;
      sides.set(`${l.from}>${n.id}|${input}`, over ? 'over' : 'under');
      return over ? top - 1000 : bottom + 1000;
    };
    const keyed = n.inputs.map((i, k) => ({ i, k, at: arrival(i) }));
    keyed.sort((p, q) => (p.at === null) !== (q.at === null) ? (p.at === null ? -1 : 1) : p.at === null ? p.k - q.k : p.at - q.at || p.k - q.k);
    n.inputs = keyed.map((x) => x.i);
  }
  return out;
});

const skipCount = computed(() => props.relations.links.filter((l) => {
  const depth = props.relations.depth;
  return l.via.length && (depth.get(l.to) || 0) - (depth.get(l.from) || 0) > 1;
}).reduce((n, l) => n + l.via.length, 0));
const size = computed(() => ({
  width: Math.max(...nodes.value.map((n) => n.x + BOX_W + LEFT), 320),
  height: Math.max(...nodes.value.map((n) => n.y + n.height + 24), 120) + 12 * skipCount.value
}));

const linkedInputs = computed(() => {
  const set = new Set();
  for (const l of props.relations.links) for (const v of l.via) set.add(`${l.to}|${v}`);
  return set;
});
const colourById = computed(() => new Map(nodes.value.map((n) => [n.id, n.colour])));
// A checked value fed by another table takes that table's colour; one supplied from outside stays grey.
const inputColour = (to, input) => {
  const link = props.relations.links.find((l) => l.to === to && l.via.includes(input));
  return link ? colourById.value.get(link.from) || 'var(--color-ink)' : 'var(--color-rule-strong)';
};

// A link: a smooth curve from a result's port (right edge) to the value it feeds (left edge). One
// that cannot run forwards (a loop, or two tables in one column) swings out right and back in.
function link(x1, y1, x2, y2) {
  const dx = x2 > x1 ? Math.max(36, (x2 - x1) / 2) : 90;
  return `M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}`;
}

// A link that skips a column never crosses the boxes in it: it bends into the gap after its table,
// runs over or under every box of the columns it passes, and bends into the gap before the table it
// feeds. It takes the side that crosses fewer of the other links in those two gaps (then the shorter
// way), and each such link gets its own lane so two never run on top of each other.
function around(x1, y1, x2, y2, fromCol, toCol, k, side) {
  const between = nodes.value.filter((n) => n.col > fromCol && n.col < toCol);
  const top = Math.min(...between.map((n) => n.y));
  const bottom = Math.max(...between.map((n) => n.y + n.height));
  const overY = top - 14 - k * 10;
  const underY = bottom + 16 + k * 10;
  const over = side === 'over';
  const laneY = over ? overY : underY;
  const g1 = x1 + GAP_X / 2;
  const g2 = x2 - GAP_X / 2;
  const q = GAP_X / 4;
  return `M${x1},${y1} C${x1 + q},${y1} ${g1 - q},${laneY} ${g1},${laneY} H${g2} C${g2 + q},${laneY} ${x2 - q},${y2} ${x2},${y2}`;
}

const edges = computed(() => {
  const byId = new Map(nodes.value.map((n) => [n.id, n]));
  const list = [];
  let skips = 0;
  for (const l of props.relations.links) {
    const b = byId.get(l.to);
    if (!b) continue;
    const a = byId.get(l.from);
    if (l.missing || !a) {
      // Nothing to come from: a dashed red stub into the table's header.
      list.push({ key: `m-${l.from}>${l.to}`, d: `M${b.x - 30},${b.y + HEAD / 2} H${b.x}`, width: 2.5, dash: '6 5', colour: 'var(--color-needs)' });
      continue;
    }
    if (!l.via.length) {
      list.push({ key: `b-${l.from}>${l.to}`, d: link(a.x + BOX_W, a.y + HEAD / 2, b.x, b.y + HEAD / 2), width: 2.5, dash: '6 5', colour: 'var(--color-needs)' });
      continue;
    }
    for (const v of l.via) {
      const j = Math.max(0, a.outputs.findIndex((o) => o.name === v));
      const i = Math.max(0, b.inputs.indexOf(v));
      const d = b.col - a.col > 1
        ? around(a.x + BOX_W, a.outY(j), b.x, b.inY(i), a.col, b.col, skips++, sides.get(`${l.from}>${l.to}|${v}`))
        : link(a.x + BOX_W, a.outY(j), b.x, b.inY(i));
      list.push({ key: `l-${l.from}>${l.to}|${v}`, d, width: 2.5, dash: undefined, colour: a.colour });
    }
  }
  for (const s of props.relations.suggestions || []) {
    const a = byId.get(s.from);
    const b = byId.get(s.to);
    if (!a || !b) continue;
    const j = Math.max(0, a.outputs.findIndex((o) => o.name === s.via));
    const i = Math.max(0, b.inputs.indexOf(s.via));
    list.push({ key: `s-${s.from}>${s.to}|${s.via}`, d: link(a.x + BOX_W, a.outY(j), b.x, b.inY(i)), width: 2, dash: '2 6', colour: 'var(--color-ink-3)' });
  }
  return list;
});

// A name several tables give: suggest a name of each table's own, editable before renaming.
const renames = reactive({});
const camel = (s) => String(s).replace(/[^A-Za-z0-9]+/g, ' ').trim().split(' ')
  .map((w, i) => (i ? w.charAt(0).toUpperCase() + w.slice(1) : w.charAt(0).toLowerCase() + w.slice(1))).join('');
watch(() => props.relations.clashes, (clashes) => {
  for (const c of clashes || []) for (const id of c.ids.slice(1)) if (!renames[id]) renames[id] = camel(nameOf(id)) || `${c.name}2`;
}, { immediate: true });
const allResults = computed(() => new Set(props.decisions.flatMap((d) => resultsOf(d).map((o) => o.name))));
const validName = (n) => !!n && /^[A-Za-z_][A-Za-z0-9_]*$/.test(n) && !allResults.value.has(n);
function rename(id, oldName) {
  const next = renames[id];
  if (!validName(next)) return;
  delete renames[id];
  emit('rename-result', id, oldName, next);
}
</script>

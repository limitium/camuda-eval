<template>
  <figure ref="figure" class="relative m-0 grid min-w-0 gap-3" :aria-label="caption">
    <figcaption v-if="caption" class="text-sm font-bold text-ink-2">{{ caption }}</figcaption>

    <!-- The key: line bullets for main and the release lines, then the work spaces as branch services. -->
    <div class="flex flex-wrap items-center gap-1.5">
      <component
        :is="pickable.includes(l.id) ? 'button' : 'span'"
        v-for="l in lanes"
        :key="l.id"
        :type="pickable.includes(l.id) ? 'button' : undefined"
        :class="[
          pickable.includes(l.id) ? 'btn-option font-semibold' : 'ref h-6',
          pickable.includes(l.id) && (picked === l.id || focus === l.id) ? 'on' : '',
          !pickable.includes(l.id) && (picked === l.id || focus === l.id) ? 'ring-1 ring-ink' : '',
          marked.includes(l.id) ? '!border-needs text-needs-ink line-through' : '',
          dimmed(l.id) && !marked.includes(l.id) ? 'opacity-55' : ''
        ]"
        :style="pickable.includes(l.id) ? undefined : { '--ref': colour(l) }"
        :aria-pressed="pickable.includes(l.id) ? picked === l.id : undefined"
        :title="marked.includes(l.id) ? markText : undefined"
        @click="pickable.includes(l.id) && $emit('pick', l.id)"
      >
        <LineBullet v-if="isRoute(l)" :line="l.id" :main-branch="mainBranch" size="sm" />
        <span v-else class="h-[3px] w-3.5 flex-none rounded-full" :style="{ background: colour(l) }" aria-hidden="true"></span>
        {{ laneName(l) }}
      </component>
    </div>

    <div class="grid grid-cols-[auto_minmax(0,1fr)] items-start overflow-x-auto border-y border-rule bg-ground py-1 pr-2" :style="{ '--row': `${ROW}px` }">
      <svg :width="width" :height="height" class="block" aria-hidden="true">
        <!-- the routes: main and the lines heavy, work spaces as thinner branch services -->
        <line v-for="l in lanes" :key="`rail-${l.id}`" :x1="x(l.id)" :x2="x(l.id)" :y1="railTop(l.id)" :y2="railBottom(l.id)"
              :stroke="colour(l)" :stroke-width="laneWidth(l)" stroke-linecap="round" :opacity="dimmed(l.id) ? 0.22 : isRoute(l) ? 1 : 0.8" />
        <path v-for="e in edges" :key="e.key" :d="e.d" fill="none" :stroke="e.colour" :stroke-width="e.width" stroke-linecap="round" stroke-linejoin="round" :opacity="e.dim ? 0.22 : 0.95" />
        <!-- lines opened with nothing on them yet: a short spur off the station they start at -->
        <path v-for="b in stubs" :key="`stub-${b.lane}`" :d="`M${x(b.from)},${b.y} L${x(b.lane)},${b.y}`" fill="none" :stroke="colourOf(b.lane)" stroke-width="4" stroke-linecap="round" />
        <circle v-for="b in stubs" :key="`stubdot-${b.lane}`" :cx="x(b.lane)" :cy="b.y" r="5" fill="var(--color-ground)" :stroke="colourOf(b.lane)" stroke-width="3" />
        <!-- where it goes next: a dashed route, travelling -->
        <path v-for="g in ghostEdges" :key="g.key" :d="g.d" fill="none" :stroke="ghostColour" stroke-width="3" stroke-dasharray="6 5" stroke-linecap="round" class="ghost-run" />
        <circle v-for="g in ghostDots" :key="`gd-${g.lane}`" :cx="x(g.lane)" :cy="g.y" r="7" fill="var(--color-ground)" :stroke="ghostColour" stroke-width="3" stroke-dasharray="4 3" />
        <!-- each row's whole width in the drawing answers hover, like its text does -->
        <rect v-for="(c, i) in rows" :key="`hit-${c.sha}`" x="0" :y="y(i) - ROW / 2" :width="width" :height="ROW" fill="transparent"
              @mouseenter="hover(c, i, $event)" @mouseleave="unhover" />
        <!-- stations: a white tick on a route, an interchange ring where work joins, a dot on a branch service -->
        <circle v-if="pickedRow >= 0" :cx="x(rows[pickedRow].lane)" :cy="y(pickedRow)" r="11" fill="none" stroke="var(--color-ink)" stroke-width="2.5" />
        <template v-for="(c, i) in rows" :key="c.sha">
          <!-- a station on a route: the tick a metro map draws; versions and joins get the full ring -->
          <path v-if="isTick(c)" :d="`M${x(c.lane)},${y(i)} h8`" :stroke="colourOf(c.lane)" stroke-width="3.5" stroke-linecap="round" :opacity="dimmed(c.lane) ? 0.35 : 1" />
          <circle v-else :cx="x(c.lane)" :cy="y(i)" :r="stationR(c)"
                  :fill="stationFill(c)" :stroke="stationStroke(c)" :stroke-width="c.parents.length > 1 || c.versions?.length ? 3 : 2.5"
                  :opacity="dimmed(c.lane) ? 0.35 : 1" />
        </template>
      </svg>

      <ol class="m-0 min-w-0 list-none p-0">
        <li v-if="ghost.length" class="flex items-start px-2 pt-2" :style="{ height: `${ROW * ghost.length}px` }">
          <span class="min-w-0 truncate text-md font-bold" :title="ghostText">{{ ghostText }}</span>
        </li>
        <li
          v-for="(c, i) in rows"
          :key="c.sha"
          class="flex h-[var(--row)] items-center gap-2 overflow-hidden rounded px-2 whitespace-nowrap"
          :class="[
            dimmed(c.lane) ? 'opacity-45' : '',
            commitPickable || pickable.includes(c.lane) ? 'cursor-pointer hover:bg-hover' : '',
            hovered?.sha === c.sha ? 'bg-hover' : '',
            pickedCommit === c.sha ? '!bg-select font-bold' : '',
            isRouteId(c.lane) ? (c.parents.length > 1 || c.versions?.length ? 'font-bold text-ink' : 'font-semibold text-ink') : 'text-sm text-ink-2'
          ]"
          @click="clickRow(c)" @mouseenter="hover(c, i, $event)" @mouseleave="unhover"
        >
          <span class="min-w-0 truncate max-md:min-w-[9rem]" :class="marked.includes(c.lane) ? 'line-through decoration-needs' : ''">{{ plainMessage(c.message, mainBranch) }}</span>
          <span v-for="v in c.versions" :key="v.ref || v.name" class="tag flex-none max-md:hidden">{{ v.ref || v.name }}</span>
          <template v-for="t in tipsOf(c)" :key="t">
            <LineBullet v-if="isRouteId(t)" :line="t" :main-branch="mainBranch" size="sm" :title="laneName({ id: t })" />
            <span v-else class="ref flex-none max-md:hidden" :style="{ '--ref': colourOf(t) }">{{ shortLane(t) }}</span>
          </template>
          <span class="ml-auto flex-none pl-3 text-xs text-ink-3 max-md:hidden">{{ c.author }} · {{ whenText(c.when) }}</span>
        </li>
      </ol>
    </div>
    <!-- The commit under the pointer: its whole message, its line, and what it changed. -->
    <div
      v-if="hovered"
      class="absolute inset-x-6 z-20 ml-auto grid max-w-[560px] gap-1.5 rounded border border-rule-strong bg-ground px-3.5 py-3 text-sm shadow-[0_10px_30px_-14px_oklch(20%_0.02_260/0.35)]"
      :style="{ top: `${cardTop}px` }"
      role="tooltip"
      @mouseenter="keep"
      @mouseleave="unhover"
    >
      <p class="flex flex-wrap items-center gap-1.5 font-bold">
        <LineBullet v-if="isRouteId(hovered.lane)" :line="hovered.lane" :main-branch="mainBranch" size="sm" />
        <span v-else class="h-[3px] w-3.5 rounded-full" :style="{ background: colourOf(hovered.lane) }" aria-hidden="true"></span>
        <span>{{ laneName({ id: hovered.lane, kind: known.get(hovered.lane)?.kind }) }}</span>
        <span v-for="v in hovered.versions" :key="v.ref || v.name" class="tag">{{ v.ref || v.name }}</span>
      </p>
      <p class="text-md whitespace-pre-wrap">{{ detail?.message || hovered.message }}</p>
      <p class="text-xs text-ink-2">{{ hovered.author }}{{ detail?.savedOn ? ` on ${detail.savedOn}` : '' }} · {{ whenText(hovered.when) }} · <span class="mono">{{ hovered.sha.slice(0, 7) }}</span></p>
      <p v-if="!detail" class="loading border-t border-rule pt-1.5">Reading what it changed…</p>
      <ul v-else-if="detail.changes.length" class="m-0 grid list-none gap-0.5 border-t border-rule p-0 pt-1.5">
        <li v-for="d in detail.changes" :key="d.domain"><strong>{{ d.domain }}</strong> <span class="text-ink-2">{{ changeText(d) }}</span></li>
      </ul>
      <p v-else class="border-t border-rule pt-1.5 text-ink-2">No rule or test changes{{ hovered.parents.length > 1 ? ' beyond what it brought together' : '' }}.</p>
    </div>
    <button v-if="hiddenCount" type="button" class="btn btn-quiet btn-small justify-self-start" @click="shown += 40">Show {{ Math.min(40, hiddenCount) }} older {{ Math.min(40, hiddenCount) === 1 ? 'commit' : 'commits' }} ({{ hiddenCount }} not shown)</button>
  </figure>
</template>

<script setup>
import { computed, ref } from 'vue';
import LineBullet from './LineBullet.vue';
import { api } from '../lib/api';
import { routeColour } from '../lib/routes';
import { branchName, plainMessage, whenText } from '../store';

/*
 * Lines and spaces as a git graph, newest at the top: one rail per lane, a dot per commit, an edge
 * to each parent — so a fork, a landing and a carry forward are visible as what they are.
 *
 * Three uses: picking where to start (pickable lanes), seeing one space among its lines (focus), and
 * seeing where something goes next (ghost: dashed steps above the newest commits, e.g. a landing's
 * chain or a new work space forking off the picked lane).
 */
const props = defineProps({
  history: { type: Object, required: true }, // GET /api/workspaces/history
  focus: { type: String, default: null },
  // Lanes to set apart without picking them, e.g. what Clean up would remove.
  marked: { type: Array, default: () => [] },
  markText: { type: String, default: '' },
  picked: { type: String, default: null },
  pickable: { type: Array, default: () => [] },
  // Dashed steps above the graph: [{ lane, from, open }] — from is the lane whose newest commit it
  // leaves; open marks a branch with no commits yet, drawn as an open end running off the top.
  ghost: { type: Array, default: () => [] },
  ghostText: { type: String, default: '' },
  caption: { type: String, default: '' },
  mainBranch: { type: String, default: 'main' },
  limit: { type: Number, default: 40 },
  // Commits, not just branches, can be picked (e.g. to start from one), and the one picked.
  commitPickable: { type: Boolean, default: false },
  pickedCommit: { type: String, default: null }
});
const emit = defineEmits(['pick', 'pick-commit']);
function clickRow(c) {
  if (props.commitPickable) emit('pick-commit', c);
  else if (props.pickable.includes(c.lane)) emit('pick', c.lane);
}
// More history on request; never cut off inside a box.
const shown = ref(props.limit);

const ROW = 36;
const LEFT = 14;

const known = computed(() => new Map(props.history.lanes.map((l) => [l.id, l])));
// The same lanes in the same order on every screen, so a branch keeps its column and colour wherever
// it is drawn: main, the newest release lines (and any older one a work space lands in), every work
// space — then a branch that does not exist yet (a new work space) in a column of its own.
const lanes = computed(() => {
  const all = props.history.lanes;
  // A branch with nothing in the window (closed or landed long ago) would only be an empty column,
  // unless this screen is about it.
  const inWindow = props.history.commits.slice(0, shown.value);
  const drawn = new Set(inWindow.flatMap((c) => [c.lane, ...(c.tips || []).filter((t) => t.startsWith('release/'))]));
  const about = new Set([props.focus, props.picked, ...props.marked, ...props.ghost.flatMap((g) => [g.lane, g.from])]);
  const wanted = (id) => drawn.has(id) || about.has(id);
  const lines = all.filter((l) => l.kind === 'LINE' && wanted(l.id)).map((l) => l.id)
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  const spaces = all.filter((l) => l.kind === 'SPACE' && wanted(l.id)).map((l) => l.id);
  const ids = [props.mainBranch, ...lines, ...spaces];
  props.ghost.forEach((g) => { if (!ids.includes(g.lane)) ids.push(g.lane); });
  return ids.map((id) => known.value.get(id) || { id, kind: 'NEW' });
});
const column = computed(() => new Map(lanes.value.map((l, i) => [l.id, i])));
// Narrower columns when there are many lanes, so the messages keep their room.
const narrow = ref(typeof window !== 'undefined' && window.matchMedia('(max-width: 760px)').matches);
if (typeof window !== 'undefined') window.matchMedia('(max-width: 760px)').addEventListener?.('change', (e) => { narrow.value = e.matches; });
const COL = computed(() => (narrow.value ? (lanes.value.length > 8 ? 9 : 14) : lanes.value.length > 8 ? 16 : lanes.value.length > 5 ? 20 : 26));

const all = computed(() => props.history.commits.filter((c) => column.value.has(c.lane)));
const rows = computed(() => {
  // The picked commit is always drawn, however old.
  const at = props.pickedCommit ? all.value.findIndex((c) => c.sha === props.pickedCommit) : -1;
  return all.value.slice(0, Math.max(shown.value, at + 1));
});
const pickedRow = computed(() => (props.pickedCommit ? rows.value.findIndex((c) => c.sha === props.pickedCommit) : -1));
const hiddenCount = computed(() => all.value.length - rows.value.length);
const rowOf = computed(() => new Map(rows.value.map((c, i) => [c.sha, i])));
const laneOfSha = computed(() => new Map(props.history.commits.map((c) => [c.sha, c.lane])));

const top = computed(() => props.ghost.length * ROW);
const x = (lane) => LEFT + (column.value.get(lane) ?? 0) * COL.value;
const y = (i) => top.value + i * ROW + ROW / 2;
const width = computed(() => LEFT * 2 + Math.max(0, lanes.value.length - 1) * COL.value);
const height = computed(() => top.value + rows.value.length * ROW + 8);

// Routes: main and the release lines, each in its own colour. A work space is a branch service of
// the line it lands in, so it runs in that line's colour, thinner.
const isRouteId = (id) => id === props.mainBranch || /^release\//.test(id || '');
const isRoute = (l) => l.kind === 'MAIN' || l.kind === 'LINE' || isRouteId(l.id);
function colour(l) {
  if (isRoute(l)) return routeColour(l.id, props.mainBranch);
  if (l.kind === 'NEW') {
    const g = props.ghost.find((s) => s.lane === l.id);
    return colourOf(g?.from || props.mainBranch);
  }
  return routeColour(l.target || known.value.get(l.id)?.target || props.mainBranch, props.mainBranch);
}
const colourOf = (lane) => colour(lanes.value.find((l) => l.id === lane) || known.value.get(lane) || { id: lane, kind: 'SPACE' });
const ghostColour = computed(() => (props.ghost.length ? colourOf(props.ghost[props.ghost.length - 1].lane) : 'var(--color-ink)'));

// The route a picked branch rides to main: a work space, the line it lands in, every newer line, main.
// Picking lights that whole route and dims the rest.
function routeOf(id) {
  if (!id) return [];
  const lane = known.value.get(id);
  const target = lane?.kind === 'SPACE' ? (lane.target || props.mainBranch) : id;
  const route = [id, target];
  if (/^release\//.test(target)) {
    lanes.value.filter((l) => isRoute(l) && l.id.localeCompare(target, undefined, { numeric: true }) > 0).forEach((l) => route.push(l.id));
  }
  route.push(props.mainBranch);
  return route;
}
const emphasised = computed(() => new Set([
  ...routeOf(props.focus), ...routeOf(props.picked), ...props.marked,
  ...props.ghost.map((g) => g.lane), ...props.ghost.map((g) => g.from)
].filter(Boolean)));
const dimmed = (lane) => emphasised.value.size > 0 && !emphasised.value.has(lane);
const laneWidth = (l) => (isRoute(l) ? (emphasised.value.has(l.id) ? 5 : 4) : emphasised.value.has(l.id) ? 3 : 2);
// Plain commits on a route are ticks; a join (interchange) or a version (a named station) is a ring.
const isTick = (c) => isRouteId(c.lane) && c.parents.length < 2 && !c.versions?.length;
const stationR = (c) => (c.parents.length > 1 || c.versions?.length ? 6 : isRouteId(c.lane) ? 4.5 : 3.5);
const stationFill = (c) => (c.parents.length > 1 || c.versions?.length || isRouteId(c.lane) ? 'var(--color-ground)' : colourOf(c.lane));
const stationStroke = (c) => (c.parents.length > 1 ? 'var(--color-ink)' : colourOf(c.lane));

// A rail runs from the lane's newest drawn commit (or the ghost above it) down to its oldest, or the bottom.
function railTop(lane) {
  const g = props.ghost.findIndex((s) => s.lane === lane && !s.open);
  if (g >= 0) return (props.ghost.length - 1 - g) * ROW + ROW / 2;
  const first = rows.value.findIndex((c) => c.lane === lane);
  return first < 0 ? height.value : y(first);
}
function railBottom(lane) {
  let last = -1;
  rows.value.forEach((c, i) => { if (c.lane === lane) last = i; });
  if (last < 0) return railTop(lane); // nothing on it yet (a new branch): no rail
  const c = rows.value[last];
  // A lane continues down past its oldest drawn commit when its parent is on the same lane (older history).
  const continues = c.parents.some((p) => laneOfSha.value.get(p) === lane && !rowOf.value.has(p));
  return continues ? height.value : y(last);
}

// Transit geometry: straight runs joined by short 45° elbows, never swoops and never long diagonals
// across other routes. A join (top, at the joining station) runs level along that station's row,
// then turns 45° down into the branch's own column.
const ELBOW = 14;
function curve(x1, y1, x2, y2) {
  if (x1 === x2) return `M${x1},${y1} L${x2},${y2}`;
  const s = Math.sign(x2 - x1);
  const d = Math.min(ELBOW, Math.abs(x2 - x1), Math.max(0, y2 - y1));
  return `M${x1},${y1} L${x2 - s * d},${y1} L${x2},${y1 + d} L${x2},${y2}`;
}

// A fork: down the branch's own column, a 45° elbow, then level along the row of the station it
// left from — so every branch keeps its own column instead of piling up on its parent's.
function fork(x1, y1, x2, y2) {
  if (x1 === x2) return `M${x1},${y1} L${x2},${y2}`;
  const s = Math.sign(x2 - x1);
  const d = Math.min(ELBOW, Math.abs(x2 - x1), Math.max(0, y2 - y1));
  return `M${x1},${y1} L${x1},${y2 - d} L${x1 + s * d},${y2} L${x2},${y2}`;
}

const edges = computed(() => {
  const list = [];
  rows.value.forEach((c, i) => {
    c.parents.forEach((p, n) => {
      const pr = rowOf.value.get(p);
      const pl = laneOfSha.value.get(p);
      if (pl === c.lane && n === 0) return; // the rail draws it
      if (!column.value.has(pl)) return;
      const y2 = pr === undefined ? height.value : y(pr);
      // A fork: a lane's first commit coming off another lane. A merge: a second parent joining in.
      list.push({
        key: `${c.sha}-${p}`,
        d: n === 0 ? fork(x(c.lane), y(i), x(pl), y2) : curve(x(c.lane), y(i), x(pl), y2),
        colour: n === 0 ? colourOf(c.lane) : colourOf(pl),
        width: (isRouteId(c.lane) && isRouteId(pl) ? 3.5 : 2) + (emphasised.value.has(c.lane) || emphasised.value.has(pl) ? 0.5 : 0),
        dim: dimmed(c.lane) && dimmed(pl)
      });
    });
  });
  return list;
});

// A line with no commits of its own yet (just opened from main): a short branch off the commit it
// starts at, ending in a hollow dot in its own column — so a stabilising line is visible at once.
const stubs = computed(() => lanes.value
  .filter((l) => l.kind === 'LINE' && !rows.value.some((c) => c.lane === l.id))
  .map((l) => {
    const i = rows.value.findIndex((c) => (c.tips || []).includes(l.id));
    return i < 0 ? null : { lane: l.id, from: rows.value[i].lane, y: y(i) };
  })
  .filter(Boolean));

// Ghost steps, drawn from the top down in order: step 0 is lowest (nearest the commits).
// Every step ends in a dot on its row, beside its text — a new branch too, so nothing runs off the top.
const ghostDots = computed(() => props.ghost.map((g, i) => ({ lane: g.lane, open: g.open, y: (props.ghost.length - 1 - i) * ROW + ROW / 2 })));
const ghostEdges = computed(() => props.ghost.map((g, i) => {
  const yTo = (props.ghost.length - 1 - i) * ROW + ROW / 2;
  let yFrom;
  if (i === 0) {
    // From one commit (g.at), or from the newest commit of the lane.
    const r = g.at ? rows.value.findIndex((c) => c.sha === g.at) : rows.value.findIndex((c) => c.lane === g.from);
    yFrom = r < 0 ? height.value : y(r);
  } else {
    yFrom = (props.ghost.length - i) * ROW + ROW / 2;
  }
  // An open end: from the fork up past the top edge — a branch that starts here and is still to be written.
  // From one older commit: down its own column, bending into that commit only at the end, so it
  // does not run along the branch's newer commits as if it held them.
  if (g.open && g.at) return { key: `g-${i}`, d: fork(x(g.lane), yTo, x(g.from), yFrom) };
  return { key: `g-${i}`, d: curve(x(g.lane), yTo, x(g.from), yFrom) };
}));

// Hover: the commit's full message, branch and changes, read once per commit.
const details = new Map();
const hovered = ref(null);
const detail = ref(null);
const cardTop = ref(0);
let leaveTimer = null;
const figure = ref(null);
function hover(c, i, event) {
  clearTimeout(leaveTimer);
  hovered.value = c;
  // Just under the hovered row, wherever the legend above it wrapped to.
  const row = event?.currentTarget?.getBoundingClientRect?.();
  const box = figure.value?.getBoundingClientRect?.();
  cardTop.value = row && box ? row.bottom - box.top + 2 : y(i) + ROW;
  detail.value = details.get(c.sha) || null;
  if (!detail.value) {
    api.commit(c.sha).then((d) => {
      details.set(c.sha, d);
      if (hovered.value?.sha === c.sha) detail.value = d;
    }).catch(() => {});
  }
}
function unhover() {
  leaveTimer = setTimeout(() => { hovered.value = null; }, 150);
}
function keep() {
  clearTimeout(leaveTimer);
}
function changeText(d) {
  if (d.kind === 'ADDED') return 'added';
  if (d.kind === 'REMOVED') return 'removed';
  const parts = [];
  if (d.rulesAdded) parts.push(`${d.rulesAdded} ${d.rulesAdded === 1 ? 'rule' : 'rules'} added`);
  if (d.rulesChanged) parts.push(`${d.rulesChanged} ${d.rulesChanged === 1 ? 'rule' : 'rules'} changed`);
  if (d.rulesRemoved) parts.push(`${d.rulesRemoved} ${d.rulesRemoved === 1 ? 'rule' : 'rules'} removed`);
  if (d.columnsChanged) parts.push(`${d.columnsChanged} ${d.columnsChanged === 1 ? 'column' : 'columns'} changed`);
  if (d.tablesAdded) parts.push(`${d.tablesAdded} ${d.tablesAdded === 1 ? 'table' : 'tables'} added`);
  if (d.tablesRemoved) parts.push(`${d.tablesRemoved} ${d.tablesRemoved === 1 ? 'table' : 'tables'} removed`);
  if (d.hitPolicyChanged) parts.push('hit policy changed');
  if (d.testsChanged) {
    const delta = d.casesAfter - d.casesBefore;
    parts.push(delta > 0 ? `${delta} test ${delta === 1 ? 'case' : 'cases'} added` : delta < 0 ? `${-delta} test ${delta === -1 ? 'case' : 'cases'} removed` : 'test cases changed');
  }
  if (!parts.length && d.tableChanged) parts.push('table changed (wording or layout)');
  return parts.join(', ');
}

const tipsOf = (c) => (c.tips || []).filter((t) => column.value.has(t));
const shortLane = (id) => (id === props.mainBranch ? 'main' : id.startsWith('release/') ? id.slice(8) : id.split('/').slice(1).join('/'));
const laneName = (l) => {
  if (l.id === props.mainBranch) return 'main';
  if (l.kind === 'NEW' && l.id.startsWith('release/')) return `Release ${l.id.slice(8)} (new)`;
  if (l.kind === 'NEW' || l.id.endsWith('/…')) return l.id.endsWith('/…') ? 'new work space' : l.id;
  const name = branchName(l.id, props.mainBranch);
  return name.charAt(0).toUpperCase() + name.slice(1);
};
</script>

<style scoped>
.ghost-run { animation: run 1.1s linear infinite; }
@keyframes run { to { stroke-dashoffset: -22; } }
</style>

# Converting a component to Tailwind in the Transit world

The frontend (Vue 3, `frontend/src`) moved from hand-written CSS to **Tailwind CSS v4** and a new
visual world, **Transit**. The theme, base and shared classes live in `frontend/src/styles/app.css`
— read it first. Already converted (use as reference, do not edit): `App.vue`,
`components/TopLine.vue`, `StepTrail.vue`, `ActionBar.vue`, `Working.vue`, `BranchGraph.vue`,
`LineBullet.vue`, `lib/routes.js`.

## The world

Releases are a transit network. main and every release line are routes with their own colour and
**bullet** (a coloured disc with "M" or "1.3" — `components/LineBullet.vue`); versions are stations
(`.tag` — a small black plate with the version in mono); a work space is a branch service of the
line it lands in, drawn thinner in that line's colour. White ground, near-black ink, the Overpass
grotesk (already the body font); Overpass Mono only for rule cells, values, identifiers, shas.
Strong type hierarchy: headings 700, labels semibold, metadata small and grey. Hairline rows, no
cards, no shadows at rest (only on transient overlays: menus, popovers, the column panel).

## Tokens (Tailwind utilities generated from `@theme`)

- Ground and ink: `bg-ground` (white), `bg-platform` (very light grey: grid headers, rule-number
  column, disabled), `text-ink`, `text-ink-2`, `text-ink-3`, `border-rule`, `border-rule-strong`,
  `bg-hover`, `bg-select`, `bg-sign` (the black signage band).
- Routes (line identity only): `route-main`, `route-1` … `route-6`. In code use
  `routeColour(branch, mainBranch)` from `lib/routes.js` (returns a CSS colour) and
  `<LineBullet :line="branch" :main-branch="app.info.mainBranch" size="sm|md|lg" />`.
- States (always a word, colour reinforces): `live` / `live-ink` / `live-tint` (amber, live in an
  environment), `needs` / `needs-ink` / `needs-tint` (red, needs you / failing / conflict),
  `clean` / `clean-ink` / `clean-tint` (green, done / passing / resolved), `edit` / `edit-ink` /
  `edit-tint` (blue, changed here and not saved). E.g. `bg-needs-tint text-needs-ink`.
- Type: `text-xs` (0.75), `text-sm` (0.8125), `text-md` (0.875 = body), `text-lg`, `text-xl`,
  `text-2xl`; `font-sans`, `font-mono`. Radius: `rounded-sm` (3px), `rounded` (4px). Ease:
  `ease-out` is the house curve.
- Layout vars still available in CSS: `var(--top)`, `var(--trail)`, `var(--bar)`, `var(--gutter)`
  (use as `px-[var(--gutter)]` etc.).
- Legacy short names (`var(--ink)`, `var(--rule)`, `var(--page)`, `var(--needs-tint)` …) still
  resolve, for SVG attributes and inline styles only; prefer utilities everywhere else.

## Shared classes (keep using them; defined in app.css)

`page-body` (+ `wide`), `lede`, `btn`, `btn-quiet`, `btn-danger`, `btn-small`, `btn-next` (only
the ActionBar draws the next action — never put `btn-next` in screen content), `field`, `label`,
`meta`, `mono`, `state` + `state-live|needs|clean|edit|quiet`, `rows` (hairline list), `notice` +
`notice-needs|live|clean`, `tag` (version plate), `loading` (spinner + text), `sr-only`.

## How to convert a file

1. Move every rule of the `<style scoped>` block onto the elements as Tailwind utilities, then
   delete the block. Keep a small `<style scoped>` only for what utilities cannot express cleanly
   (keyframes, `:deep()`, complex sibling/`:has` selectors, sticky table cell layering) — and say
   why in one comment.
2. Keep every behaviour: script logic, props, emits, events, `v-if`s, refs, aria attributes,
   copy. Class names other code relies on (e.g. `.stage`, `.graph-rows .row` used by tests, any
   class queried in script with `querySelector`) must stay — grep before removing a class.
3. Apply the world while you convert — this is a redesign, not a pixel copy:
   - Wherever a line or branch is named (main, "Release 1.2", a work space's target line), show
     its `LineBullet` beside the name. Version names use `.tag`.
   - Headings 700; section labels `text-sm font-bold text-ink-2`; list rows `py-3` with hairlines;
     names `font-semibold`; metadata `text-sm text-ink-2`; timestamps `text-xs text-ink-3`.
   - Grids (rule/test/diff): header row `bg-platform`, `text-xs font-bold` labels, mono cells
     `font-mono text-[0.8125rem]`, hairline cell borders, rule numbers in `text-ink-3`; edited =
     `bg-edit-tint` + edit-ink, contested = `bg-needs-tint`, resolved = `bg-clean-tint`.
   - Selection/picked: `bg-select` and an ink ring (`ring-1 ring-ink`), never an ink fill.
   - No cards, no resting shadows, no coloured left borders thicker than 1px, no gradients, no
     emoji/unicode icons (use lucide-vue-next, 16px, stroke 1.75), no uppercase kickers above
     headings.
   - Keep it dense and scannable for a desk monitor; don't shrink type below `text-xs`.
4. Responsive: keep the existing breakpoints' intent with `max-md:` / `md:` / `max-[1200px]:`.
5. Run `cd frontend && npx vite build` — it must succeed. Do not start or restart servers, do not
   touch files outside your list, do not commit.

Report: files converted, any style kept in `<style scoped>` and why, any class kept for script use.

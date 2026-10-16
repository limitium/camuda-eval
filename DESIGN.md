---
name: camunda-eval
description: Where analysts change decision tables, prove them with tests, and land them; releases drawn as a transit network with one next action.
colors:
  ground: "oklch(100% 0 0)"
  platform: "oklch(97.2% 0.003 250)"
  ink: "oklch(19% 0.01 260)"
  ink-2: "oklch(41% 0.012 260)"
  ink-3: "oklch(54% 0.01 260)"
  rule: "oklch(91% 0.004 260)"
  rule-strong: "oklch(81% 0.006 260)"
  hover: "oklch(96.5% 0.004 250)"
  select: "oklch(95% 0.032 160)"
  sign: "#016c47"
  brand: "#016c47"
  brand-hover: "oklch(39% 0.09 161)"
  brand-tint: "oklch(95.5% 0.03 160)"
  route-main: "#016c47"
  route-1: "oklch(47% 0.13 222)"
  route-2: "oklch(70% 0.17 55)"
  route-3: "oklch(47% 0.15 305)"
  route-4: "oklch(64% 0.12 205)"
  route-5: "oklch(56% 0.21 355)"
  route-6: "oklch(50% 0.08 60)"
  live: "oklch(74% 0.15 72)"
  live-ink: "oklch(47% 0.11 60)"
  live-tint: "oklch(96.5% 0.04 85)"
  needs: "oklch(55% 0.2 27)"
  needs-ink: "oklch(49% 0.18 27)"
  needs-tint: "oklch(96.5% 0.025 25)"
  edit: "oklch(55% 0.17 262)"
  edit-ink: "oklch(45% 0.16 262)"
  edit-tint: "oklch(95.5% 0.03 262)"
  clean: "oklch(62% 0.17 138)"
  clean-ink: "oklch(46% 0.13 138)"
  clean-tint: "oklch(96.5% 0.04 135)"
typography:
  display:
    fontFamily: "Overpass Variable, Overpass, system-ui, sans-serif"
    fontSize: "1.875rem"
    fontWeight: 700
    lineHeight: 1.1
    letterSpacing: "-0.022em"
  headline:
    fontFamily: "Overpass Variable, Overpass, system-ui, sans-serif"
    fontSize: "1.25rem"
    fontWeight: 700
    lineHeight: 1.25
    letterSpacing: "-0.012em"
  title:
    fontFamily: "Overpass Variable, Overpass, system-ui, sans-serif"
    fontSize: "1.0625rem"
    fontWeight: 700
    lineHeight: 1.4
    letterSpacing: "-0.012em"
  lede:
    fontFamily: "Overpass Variable, Overpass, system-ui, sans-serif"
    fontSize: "1.0625rem"
    fontWeight: 400
    lineHeight: 1.4
  body:
    fontFamily: "Overpass Variable, Overpass, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.5
    fontFeature: "\"tnum\" 1"
  label:
    fontFamily: "Overpass Variable, Overpass, system-ui, sans-serif"
    fontSize: "0.8125rem"
    fontWeight: 600
    lineHeight: 1.45
  caption:
    fontFamily: "Overpass Variable, Overpass, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1.4
  bullet:
    fontFamily: "Overpass Variable, Overpass, system-ui, sans-serif"
    fontSize: "11px"
    fontWeight: 800
    lineHeight: 1
  mono:
    fontFamily: "Overpass Mono Variable, Overpass Mono, ui-monospace, Menlo, monospace"
    fontSize: "0.92em"
    fontWeight: 400
rounded:
  sm: "3px"
  md: "4px"
  full: "9999px"
spacing:
  gutter: "clamp(16px, 2.4vw, 36px)"
  top-band: "48px"
  step-trail: "56px"
  action-bar: "64px"
  graph-row: "36px"
  page-max: "1120px"
components:
  button:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.md}"
    padding: "0 14px"
    height: "34px"
  button-hover:
    backgroundColor: "{colors.hover}"
  button-next:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ground}"
    rounded: "{rounded.md}"
    padding: "0 20px"
    height: "40px"
  button-next-hover:
    backgroundColor: "{colors.ink-2}"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.ink-2}"
    rounded: "{rounded.md}"
    padding: "0 8px"
    height: "34px"
  button-danger:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.needs-ink}"
    rounded: "{rounded.md}"
    height: "34px"
  button-small:
    padding: "0 10px"
    height: "28px"
  field:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: "0 10px"
    height: "36px"
  version-tag:
    backgroundColor: "{colors.platform}"
    textColor: "{colors.ink}"
    typography: "{typography.mono}"
    rounded: "999px"
    padding: "0 8px"
  line-bullet:
    backgroundColor: "{colors.route-main}"
    textColor: "{colors.ground}"
    typography: "{typography.bullet}"
    rounded: "{rounded.full}"
    size: "22px"
  line-bullet-sm:
    size: "18px"
  line-bullet-lg:
    size: "32px"
  top-line:
    backgroundColor: "{colors.sign}"
    textColor: "{colors.ground}"
    typography: "{typography.label}"
    height: "48px"
  top-line-zone-active:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "6px 10px"
  action-bar:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.ink-2}"
    height: "64px"
  notice-needs:
    backgroundColor: "{colors.needs-tint}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "14px 16px"
---

# Design System: camunda-eval

## Overview

**Creative North Star: "The Transit Line Diagram"**

Releases are a transit network. main and every release line are routes, each with one colour and one bullet wherever it appears; versions are stations with name plates; a work space is a branch service that runs in the colour of the line it lands in and rejoins it; landing is a ride from the space through each interchange to main. The interface reads like station signage: a brand-green (#016c47) signage band on top, white ground, near-black road-sign type, and the network diagram drawn with solid route strokes, ticks and interchange rings.

Density is a working tool's: rows between hairlines, no cards, no shadows at rest. Colour is spent on two things only, route identity and state words, and the two vocabularies never share a hue. Every screen ends in a white bottom band carrying the session transcript on the left and exactly one brand-green next action on the right, the same green as the signage band.

The world refuses the sidebar-and-cards dashboard and the grey git-client commit graph.

**Key Characteristics:**
- Brand-green signage band (48px) on top: wayfinding zones, the current space with its line bullet, environment and person on the right.
- Route palette owns identity: main is the brand green "M", release lines cycle through six route colours by line number.
- Line bullets and station rings are the atoms; solid 4px route strokes, 2px branch services, interchange rings.
- Hairline rows are the only container; white ground, platform grey only for disabled and inset surfaces.
- One filled brand-green control per screen: the next action.

## Colors

A white-and-ink palette with one brand colour, #016c47, owning the signage band and the next action; all other chroma belongs to route identity and to the four state words.

### Primary
- **Brand Green** (brand / brand-hover / brand-tint, #016c47): the top signage band, the single next-action button (hover deepens), the selection wash (select is a brand tint), the progress bar, the caret. White text on it is 6.5:1.
- **Ink** (ink): body type, focus outlines. main's route (route-main) is the brand green.

### Secondary (route palette: line identity)
- **Petrol Blue** (route-1): a release line; also the caret colour and the brand mark's branch stroke. Held at hue 222, clear of edit-blue.
- **Signal Orange** (route-2), **Line Purple** (route-3), **Cyan** (route-4, lighter than the petrol line and clear of the brand green), **Metro Magenta** (route-5), **Bronze** (route-6): release lines.
- A line's colour follows from its number (`(major * 7 + minor) % 6 + 1`), so release 1.3 is the same colour on every screen. A work space draws in its target line's colour; branches with no line fall back to ink-3.

### Tertiary (state words)
- **Live Amber** (live / live-ink / live-tint): what runs now in an environment; the env indicator in the signage band.
- **Needs Red** (needs / needs-ink / needs-tint): failing, conflicting, blocked; failure notices and flagged cells.
- **Edit Blue** (edit / edit-ink / edit-tint): new or edited cells and rows not yet saved.
- **Clean Green** (clean / clean-ink / clean-tint): passing, saved, merged clean. A yellower, brighter green (hue 138) than the brand (160), so "passed" never reads as "press here".
- Each state has three steps: the dot (base), the word (ink, legible on white), the wash (tint, for notices and cell backgrounds).

### Neutral
- **Ground White** (ground): the page plane, the action bar, fields, popovers.
- **Platform Grey** (platform): disabled buttons, inset strips, quiet surfaces.
- **Ink 2 / Ink 3** (ink-2, ink-3): secondary text and meta; placeholders and timestamps.
- **Hairline** (rule): row separators and band edges. **Strong Hairline** (rule-strong): control borders, unfinished trail segments, scrollbar thumb.
- **Hover Wash** (hover) and **Select Wash** (select): row and control hover; the picked row or cell.

### Named Rules
**The Two Vocabularies Rule.** Route colours identify lines; amber, red, green and edit-blue are state words. No route colour may sit on the state hues, and a state is never signalled by a route colour.

**The Word First Rule.** A state is always a word; the 7px dot and the colour reinforce it, never replace it.

**The One Green Button Rule.** The only filled brand-green control on a screen is the next action in the action bar.

## Typography

**Display Font:** Overpass (with system-ui)
**Body Font:** Overpass (with system-ui)
**Label/Mono Font:** Overpass Mono, for rule cells, values, identifiers, hosts and version name plates

**Character:** Overpass is a road-sign grotesk; heavy weights carry the signage, the bullets set in extrabold. Numerals are tabular everywhere.

### Hierarchy
- **Display** (700, 1.875rem, 1.1, -0.022em): the one screen title, sentence case, balanced wrap.
- **Headline** (700, 1.25rem, 1.25): section titles within a screen ("Connections", "Test sets").
- **Title** (700, 1.0625rem, 1.4): row names and sub-sections.
- **Lede** (400, 1.0625rem, 1.4, ink-2, max 66ch): the explanation under a screen title.
- **Body** (400, 0.875rem, 1.5): default text, buttons are body size at 600.
- **Label** (600, 0.8125rem, 1.45, ink-2): field labels, state words, meta lines, signage zones.
- **Caption** (400, 0.75rem): timestamps and counts.
- **Bullet** (800, 10/11/13px, 1): the short line name inside a bullet.

### Named Rules
**The Mono Is Data Rule.** Overpass Mono appears only for what the analyst could type or copy: cell values, column names, identifiers, hosts, version names. Never for prose or headings.

**The Sentence Case Rule.** Headings, buttons and zones are sentence case; nothing is set uppercase.

## Layout

Three fixed bands frame every screen: the signage band (48px), the step trail inside a work space (56px), and the action bar at the bottom (64px). Horizontal padding is one fluid gutter (clamp 16px to 36px). Content sits in a 1120px column (top 40px, bottom 56px); wide screens (table editing, landing) drop the max width. Landing and network screens put the list or the task on the left and the network diagram on the right; the diagram is the same component on every page, one station row per 36px.

On mobile the signage band scrolls horizontally with a fade mask, the person and space label drop, the step trail scrolls under a mask, and the action bar stacks its transcript above right-aligned, wrapping actions. A docked column panel (360px) takes its width from the stage rather than covering it.

## Elevation & Depth

Flat at rest. Depth is conveyed by bands (green top, white bottom with a hairline), hairline rows and washes. Shadows exist only on layers that float above the page while open: the transcript list, graph hover detail, menus, the working toast.

### Shadow Vocabulary
- **Float** (`box-shadow: 0 8px 24px -8px oklch(20% 0.02 260 / 0.18)`): the transcript drawer rising from the action bar.
- **Float Strong** (`box-shadow: 0 10px 30px -14px oklch(20% 0.02 260 / 0.35)`): graph hover detail, menus.
- **Cell Mark** (`box-shadow: inset 0 -2px 0 var(--color-edit)` or `var(--color-needs)`): an underline stroke marking an edited or flagged grid cell or column; a mark, not elevation.

### Named Rules
**The Flat Platform Rule.** Nothing on the page plane carries a shadow; only open overlays do.

## Shapes

Two shapes that never swap: **an action is a rectangle** (4px: buttons, choice buttons, fields), **a label is a pill** (version tags, branch refs, line chips that are not clickable) with a soft fill and no border, never black. 3px on signage zone plates. Circles are reserved for the network: line bullets, station rings, state dots, step marks. Route strokes are round-capped and round-joined, 4px for main and release lines (5px when lit), 2px for branch services (3px when lit); routes turn on short 45-degree elbows (14px), never curves.

## Components

### Buttons
Plain signage plates: white with a strong hairline, ink text, the brand-green one reserved.
- **Shape:** gently squared (4px), 34px tall, 14px padding, 600 weight.
- **Next action:** brand-green fill (#016c47), white 700 text, 40px tall, 20px padding; hover deepens to brand-hover. One per screen, in the action bar.
- **Hover / Focus:** border goes to ink and the hover wash appears (120ms, ease-out); focus is a 2px ink outline offset 2px.
- **Quiet:** borderless ink-2 text, hover wash. **Danger:** needs-ink text on a pale red border, red border and wash on hover. **Small:** 28px, 13px text.
- **Busy:** a spinning ring before the label (700ms turn); the button stays its size.

### Labels: version tags and branch refs
- **Version tag (`.tag`):** a pill on the platform grey, ink mono 600 text, a 6% ink inset hairline; links underline on hover. Never black.
- **Branch ref (`.ref`):** a pill tinted 14% with its line's colour (`--ref`), ink 12px 600 text; the network's key uses refs for lines that are only shown.

### Choice buttons (`.btn-option`)
Picking one option among several — a line in the network's key, a run shortcut, a result to use — is an action, so a 28px rectangle: white, strong hairline, ink-2 600 text; hover goes to ink; picked takes the selection wash, an ink border and a 1px ink ring (never an ink fill). Routes lead with their bullet, branch services with a short stroke in their line's colour; a line that would be lost is struck through in red.

**The Two Shapes Rule.** Rectangles act, pills inform. Nothing that is only information is black or outlined like a button, and nothing clickable is shaped like a label.

### Cards / Containers
There are no cards. Lists are rows between hairlines; notices are the only boxed element: 4px radius, hairline border, 14px by 16px padding, tinted by state (needs, live, clean), max 70ch.

### Inputs / Fields
- **Style:** 36px, white, strong hairline, 4px radius, petrol-blue caret.
- **Focus:** border goes to ink with a 1px ink ring; no glow.
- **Labels:** 13px 600 ink-2 above the field.

### Navigation
- **Signage band (TopLine):** brand green, white text; zones as 600 text at 85% white, the current zone a white plate with brand-green text; the open space follows a divider with its small line bullet; env shown as a white word with a live-amber dot on a 15% white pill (a red pill "deploy only" when not an author).
- **Step trail:** a ride along the space's route: steps are stations joined by 4px segments in the route colour once done, rule-strong before; done steps filled in route colour with a check, failing filled red with an X and "fails", the next step a bold 5px ink interchange ring; the current page underlined 2px.

### Line Bullet (signature)
A route's identity as a metro bullet: a filled disc in the route colour, white extrabold short name ("M" for main, "1.3" for a release). Sizes 18 / 22 / 32px. Used wherever a line is named: lists, signage, trail, chips, landing chains.

### Branch Graph (signature)
The transit network. main is a solid brand-green rail on the left (work spaces landing in main run as thin green branch services); release lines are coloured rails; work spaces are thin branch services in their target line's colour. Plain commits on a route are 8px ticks; joins and versions are interchange rings (6px radius, white fill); version tags sit beside the station. Picking a station or line lights its route through every interchange to main (stroke thickens) and dims the rest to 22%. A pending landing draws as a dashed run (6/5 dash) animated along the route.

### How they connect (tables of one domain)
Tables are entities, so this diagram is the one place boxes are right: each table a white box (strong hairline, 4px) with a header (its colour dot, name in bold, opens the table), then "checks" with a port on the left edge per value, then "gives" with a coloured port on the right edge per result (click one, then the table that should use it). Columns run left to right in the order data flows, on a platform-grey field. A link is a smooth 2.5px curve in the source table's colour from result port to input port; a link that skips a column drops below the boxes in between and runs in its own lane, never through a box. Broken links are dashed red, suggestions dotted grey.

### Action Bar
A white bottom band over a hairline: the last transcript entry with its state dot on the left (opens the earlier entries upward), secondary buttons then the single next action on the right.

## Do's and Don'ts

### Do:
- **Do** name every line with its bullet and its route colour, derived from `routeColour()`; never pick a line colour by hand.
- **Do** keep the next action as the only brand-green filled button, in the action bar.
- **Do** say every state as a word, with the 7px dot in the state colour.
- **Do** separate list items with hairline rows (rule) on the white ground.
- **Do** draw routes with solid round-capped strokes and 45-degree elbows; mark versions with interchange rings and grey mono version tags.
- **Do** keep mono for values, identifiers and versions only.

### Don't:
- **Don't** wrap lists or sections in cards or give the page plane shadows; only open overlays float.
- **Don't** use amber, red, green or edit-blue as a line colour, or a route colour as a state.
- **Don't** put a second brand-green filled control on a screen, or use the brand green for a state.
- **Don't** draw the network as a grey git-client graph or curve its routes.
- **Don't** set headings, buttons or labels in uppercase.

---
version: 1
slug: "frontend-src-app-vue"
primary_target: "frontend/src/App.vue"
related_targets: ["frontend/src"]
---

# Surface: camunda-eval app (whole frontend)

Mode: Operate. Audience: business rules analysts, desk, large monitor, all day, no technical
background. Constraints: tens of files, a couple of tables each, under a hundred rules; must
visualise and manage output→input relations between decisions and the release lines a change
travels along; no generic SaaS dashboard (sidebar + cards + KPI tiles), no heavy enterprise
(grey forms, tiny type, tabs on tabs). Light theme. One next action per screen. Styling in
Tailwind CSS v4 (tokens in @theme).

User answers (2026-10-14): fix first — graph pages feel bolted on; hard to scan. Keep — one next
action per screen; light theme. Colour is free to identify lines, not only state.

## Flow map

- Applications: what each application runs, its deployments, look and try a version.
- Work spaces: continue one, or start one from any branch or commit, picked on the network.
- Space: step trail (Edit · Test · Save version · Try in dev · Land), its tables.
- Table: rule grid, test grid, relations, try it.
- Land: the chain into its line and forward to main; conflicts beside the table.
- Releases: open a release line from main to stabilise; tag a commit on a line as a version.
- Clean up: what goes, struck through on the network.

## Direction contract

THESIS: Releases are a transit network. main and every release line are routes with their own
colour and bullet; versions are stations; a work space is a branch service that rejoins its line;
landing is a ride from the space through each interchange to main. Refuses the sidebar-and-cards
dashboard and the grey git-client graph.

OWN-WORLD: White ground, near-black type in Overpass (road-sign grotesk) with Overpass Mono for
rule cells. Route palette owns identity: main black, release lines in metro blue, orange, purple,
teal, magenta, cycling; red, green, amber and edit-blue stay reserved for state words. Line
bullets (coloured circle with "1.3", black "M") and station ticks are the atoms; solid 4px route
strokes, interchange rings, no cards, no shadows at rest, hairline rows.

STORY: The analyst sees which line everything sits on, where their work joins it, and rides the
one next action along the route.

FIRST VIEWPORT: A black signage band on top (zones as wayfinding, env and person right). Lists on
the left as station rows with line bullets; the network diagram on the right, same component on
every page; a white bottom band with the transcript left and the single black next action right.

FORM: Transit Line Diagram, my top-ranked candidate, chosen by the user over the roll (seed
1c13d54c, assigned Analysis Ledger). Signature interaction: picking a station or line lights its
route through every interchange to main and dims the rest; a landing chain draws itself along the
route.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

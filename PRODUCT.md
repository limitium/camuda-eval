# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

**Business rules analysts** are the primary users. They own the decision logic — pricing, eligibility,
routing tables — but not the plumbing underneath it. Their job: change a decision table, prove the
change with test cases, see it running in an environment, and get it released without breaking what
colleagues are doing in the same tables.

Several analysts work at once, sometimes in the same file. Each is identified by the proxy in front of
the app; identity is trustworthy and is shown as real attribution.

Developers appear occasionally (setting up environments, the consuming app), but the product is not
designed around them. Git mechanics are translated into the analyst's task; shas, refs and branch
names stay available on demand, never in the way.

## Product Purpose

camunda-eval is where DMN decision tables are authored, tested, versioned, released and shipped.

Before it, a decision table was a single mutable file with no history, no versions, and no reliable
way to reach the running application — content reached production by hand. Success is an analyst
taking a change from idea to a running environment, with tests, review of exactly what changes, and
a clean release, without ever losing a colleague's work and without learning git.

## Positioning

The documents are **structured, not text**, so the product reasons about rules, cells and columns
instead of lines. That is the mechanism nothing else offers:

- A diff shows *which rule changed and which cell*, and knows a threshold edit is one changed rule,
  not a removed row and an added one.
- A merge conflict is a single contested cell, one of a **closed set of thirteen kinds**, each with a
  fixed set of answers — resolving is choosing, never hand-editing XML.
- Every conflict names the colleague's change behind it, with their message.
- Tests are written as a table that lines up with the decision table it exercises, and a change to
  the table that breaks its tests is reported as an error with the rename identified.

## Operating Context

- **Working spaces**: each analyst works in their own space, started from a baseline they choose by
  navigating release lines and the versions running in each environment. Anyone may open anyone's.
- **The flow**: edit → save (a draft) → save a version with a message (visible to everyone) →
  publish to this environment to try it against the running app → land it.
- **Landing is all or nothing**: it merges into the target release line and carries the change forward
  through every newer line to main, gated by tests at every step. Conflicts from every step arrive at
  once; nothing lands until all are resolved.
- **Editing together is non-destructive**: a save built on a version someone else has since replaced is
  refused and there is no overwrite anywhere; the way forward is the three-way merge.
- **Environments** (dev, stg, prod) each run their own instance of the app against an isolated Kafka
  broker; each instance sees only its own environment. Prod has no authoring at all — only choosing a
  released version, reviewing what changes, and deploying it.
- **Shipping**: one compacted, single-partition topic per environment, one key per DMN file, keys fixed
  in the consuming app's build. Publishing a draft therefore replaces the shared rules in that
  environment until a release is restored — allowed only where configured (dev), never silently.
- **What is shipped here** is read back from the topic and compared with version history: which
  release or whose draft each table runs, and where the two disagree.

## Capabilities and Constraints

- Scale: **tens of DMN files, a couple of decision tables each, most tables under a hundred rules.**
  Screens can show whole tables; virtualisation is not a requirement.
- Test specs: one block per decision, a `scenario` column, input variables, `=> output` expectations.
  Input columns are the variables a test supplies, not the table's declared inputs; the editor must
  offer the names.
- Conflict taxonomy (fixed): cell; rule delete vs modify; rule added twice; rule order; column delete vs
  modify; column deleted while in use; column added twice; column type; hit policy; decision delete vs
  modify; test expectation; test delete vs modify; test added twice. Three tiers: ignored (layout, ids,
  formatting), cosmetic (names, labels, descriptions — bulk-resolvable), semantic.
- Existing: a visual DMN modeller (dmn-js) and a text editor (Monaco) are part of the app today.
- Built backend: working spaces (list, create, delete, save a version), content scoped to a working
  space with version checks, semantic diff and three-way merge preview, test hints and test problems.
- Designed, not yet built: release lines and versions, the landing chain, deploy/publish/restore,
  and the shipped-state read-back.
- UI library: **free to replace** — no commitment to ant-design-vue.

## Brand Commitments

No logo, palette or voice committed beyond the interface commitment below. The product name is `camunda-eval`; no logo, palette or voice has been committed.

## Evidence on Hand

Real decision tables and test specs exist only as fixtures and scratch data in the repository
(`evaluator-test/src/test/resources`, pricing examples used during development). No customers,
screenshots of production use, or metrics exist; none may be invented.

## Brand Commitments (interface)

**The cleanest interface, focused on the current single action**, usable by someone with no technical
background. Pinned by the user over every generated direction. Each screen serves one step of the
rule's journey and offers one obvious next action; everything else is out of the way. Git, XML and
Kafka vocabulary never appear on the surface.

## Product Principles

0. **One screen, one next action.** The app knows where the analyst is in the journey and shows only
   that step. Nothing else competes for attention.
1. **Never lose someone's work.** No overwrite path, anywhere. Refusal comes with the way forward.
2. **Tables, not text.** Every change, diff and conflict is shown as rules and cells in the shape of
   the table itself.
3. **Say what happened, and whose it was.** Conflicts, drafts and shipped state carry the person and
   the message behind them. No silent fallbacks, no guessed defaults.
4. **The analyst's task first, the mechanism on demand.** Version, land, publish — with the git truth
   one step away for whoever needs it.
5. **Destructive is loud.** Publishing a draft replaces what an environment runs; the product says so
   before it happens and offers the way back after.

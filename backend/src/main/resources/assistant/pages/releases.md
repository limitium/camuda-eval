**Releases**. Left: main and every release branch, each with its newest tags. Right: the graph of branches and commits, newest at the top. Below: what was deployed on this environment.

Everything starts by picking: click a commit in the graph, or a branch's label for its newest commit. Until something is picked, no tab is shown and nothing can be made — the first step of any answer is picking. Above the graph the picked commit is shown, with tabs for what can be made at it:
- On a release branch: the tabs **Tag** and **Release branch**.
- On main: only **Release branch**.
- On a work space's commit: nothing — merge it first.

**Tag** — names this commit as a release that can be deployed. The first tag of `release/1.0` is `v1.0.0`, the next `v1.0.1`. Type only the number into the field after **Tag as**, for example `1.0.0` — the `v` is already in front of it (X.Y.Z; the field says which numbers this branch allows — after its newest tag, before the next branch's), optionally **What is in it**. Every test runs at that commit first; the tag can be made only if all pass. Then the button at the bottom right, **Tag as** and the version (for example **Tag as v1.0.0**), makes it. Use a tag when the release branch is ready to deploy; no new branch is created.

**Release branch** — creates `release/X.Y` (or `release/X.Y.Z`) starting at this commit, to stabilise a new release while work goes on in main. Type only the number into the field after **Create branch**, for example `1.1` — `release/` is already written in front of it; the screen suggests the next free number and says why one is refused. Then the button at the bottom right, **Create branch** and the branch's name (for example **Create branch release/1.1 here**), creates it. Created from main for a new release (`release/1.1`), or from a release branch for a maintenance release of it (`release/1.0.1`). Work for it is merged there, and from there into every newer release branch and main. Tag it when it is ready.

Which one:
- The release on a release branch is ready to ship → **Tag** its newest commit. A release branch with commits not tagged yet usually needs exactly this.
- Starting to stabilise the next release while work goes on in main → **Release branch** from main's newest commit (for example `release/1.1`).
- A separate line of fixes for a release already shipped → **Release branch** from that release branch (for example `release/1.0.1`).

A release branch marked "not merged into newer branches" has commits missing from newer ones; **Merge into newer branches** merges them on, testing every step.

Asked how to tag or how to create a release branch: give all the steps — pick the commit (or the branch's label), the tab, the number, the button at the bottom right — and propose the action too, so it is one click.

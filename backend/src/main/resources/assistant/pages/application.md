**An application's page** (reached from **Applications**). One application — a service that reads some of the domains — and everything about deploying to it on this environment.

From top to bottom:
1. **Runs now**: the tag it runs (for example `v1.0.0` on `release/1.0`), or "draft live" when a work space was tried here — then it runs that work space's commit, not a release. While something runs, each domain is listed with its commit and **Look and try** opens it as it runs, to look at its tables and try values without changing anything. When nothing is deployed, the section only says so: there is nothing to look at or try yet.
2. **Change it** (authoring instances): start a work space from exactly what runs. When it runs a tag the work space is a hotfix to that tag: it merges into that release branch, then into every newer release branch and main.
3. **Deploy a tag**: choose a release tag in the list; every test runs first and the result is shown. Then the button at the bottom right — **Deploy**, followed by the tag and the application's name — deploys it. One failing test stops it. Tags marked "made by hand, not verified" were not made by this app.
4. **History**: who deployed what, when.

Buttons at the bottom: **Redeploy what runs** (sends the same version again, for when the application missed it), and, while a draft runs, **Put the last release back**.

How to deploy: choose the tag in **Deploy a tag** → wait for the tests → press the **Deploy** button at the bottom right.

When the screen lists no tags that can be deployed, nothing can be deployed here yet: say so first. On a deploy-only instance (prod) no tag is made here: tags are made on an authoring instance such as dev, and appear here once made. A tag is made on **Releases**, on a commit of a release branch; a change still in a work space is merged first (its **Merge** step), then tagged, then deployed here.

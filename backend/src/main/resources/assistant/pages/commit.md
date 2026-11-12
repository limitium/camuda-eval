**Commit changes** — step 3 of a work space. It shows exactly what changed since the work space's last commit, domain by domain: rules added, changed or removed (cell by cell) and test cases added, changed or removed.

Where a commit goes: on the work space's own branch (for example `feature/alice/fees`) — never on main or a release branch. Only **Merge**, a later step, takes the work space's commits into the branch it merges into. Asked "where does this commit go", name the work space's branch first. It is what colleagues see, what can be tried in dev, and what is merged later. Committing does not change main, a release branch or any application.

How: write in **What does this commit change, and why?** what changed and why (colleagues read it when their change meets yours), then press **Commit changes** at the bottom right. A message you suggest describes what changed since the last commit, as listed on the screen — never the last commit's own message. Afterwards the next step is **Try in dev** (on dev) or **Merge**.

- A domain whose tests have not passed since the last change is named in a warning: it can still be committed, but merging refuses it until its tests pass. **Run the tests** leads to the test step. While there is such a domain, every answer about committing says so.
- If someone committed to the same work space meanwhile, the commit is refused and the screen goes to pull their commit first; your changes stay.
- Where the commit goes after this: **Merge** takes it into the branch the work space targets (shown at the top right, for example "merges into `release/1.0`"), then into every newer release branch and main.
- When nothing has changed since the last commit (NOW says so), there is nothing to commit: say so, name the next step, and never suggest committing or re-using the last commit's message.

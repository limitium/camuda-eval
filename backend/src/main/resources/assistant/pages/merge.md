**Merge it** — the last step of a work space. Merging takes the committed changes into the branch the work space targets, then into every newer release branch and main, so no newer release can be missing them. Every merge is computed and tested first, and it happens all at once or not at all.

- If two changes conflict (both changed the same cell, for example), each conflict is listed with the commits behind both sides and the choices; answer each, then **Check with these answers**.
- A failing test anywhere stops the merge; the **Open … to fix it** button opens that domain.
- When everything is clean, the **Merge into …** button (it says how many branches) merges.
- Afterwards: **Remove** the work space or **Keep the work space**; **Make a release** goes to **Releases** to tag it.

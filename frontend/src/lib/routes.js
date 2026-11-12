/*
 * Route identity: main and every release line keep one colour and one bullet wherever they appear
 * (the network, lists, breadcrumbs). A line's colour follows from its number, so release 1.3 is the
 * same colour on every screen and every instance. A work space runs in the colour of the line it
 * merges into — a branch service of that route.
 */
const ROUTES = 6;

export function lineNumber(branch) {
  const m = /^release\/(\d+)\.(\d+)(?:\.(\d+))?$/.exec(branch || '');
  return m ? { major: Number(m[1]), minor: Number(m[2]), patch: Number(m[3] || 0) } : null;
}

/** The CSS colour of a line (or of the line a work space merges into). */
export function routeColour(branch, mainBranch = 'main') {
  if (!branch || branch === mainBranch) return 'var(--color-route-main)';
  const n = lineNumber(branch);
  if (!n) return 'var(--color-ink-3)';
  return `var(--color-route-${((n.major * 7 + n.minor + n.patch * 3) % ROUTES) + 1})`;
}

/** The short name on a line's bullet: "M" for main, "1.3" for release/1.3, "1.3.1" for release/1.3.1. */
export function bulletText(branch, mainBranch = 'main') {
  if (branch === mainBranch) return 'M';
  const n = lineNumber(branch);
  return n ? `${n.major}.${n.minor}${n.patch ? `.${n.patch}` : ''}` : '';
}

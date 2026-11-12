// Every screen, at three widths, checked for what breaks a layout: the page scrolling sideways, text
// cut off with no ellipsis, a control covered by something else, a control with no name, a button whose
// label wraps. Screenshots of each go to out/layout/<width>/ for a person to look at as well.
//
// Runs after lifecycle.mjs, on what it left: a work space merged into main, release/1.0, v1.0.0.
import { launch } from './chrome.mjs';
import { execFileSync } from 'child_process';
import { mkdirSync, writeFileSync } from 'fs';
import { join } from 'path';

const DEV = process.env.DEV_URL || 'http://localhost:7180';
const PROD = process.env.PROD_URL || 'http://localhost:7181';
const PROJECT = process.env.E2E_PROJECT || 'camunda-e2e';
const OUT = join(process.env.OUT || new URL('./out', import.meta.url).pathname, 'layout');
const WIDTHS = (process.env.WIDTHS || '1440,1024,768').split(',').map(Number);
const ONLY = process.env.ONLY ? new RegExp(process.env.ONLY) : null;
const SPACE = 'feature/alice/e2e-uk-sdrt';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

let sha;
try {
  sha = execFileSync('docker', ['run', '--rm', '-v', `${PROJECT}_rules-git:/git`, 'alpine/git:2.45.2', '-C', '/git/rules.git', 'rev-parse', 'v1.0.0^{commit}'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
} catch {
  console.log('layout: there is no v1.0.0 in the remote — run lifecycle.mjs first; it leaves what these screens show');
  process.exit(1);
}
const tab = (name) => `//main//button[@role="tab" and normalize-space(.)="${name}"]`;
const HISTORY = '//main//button[@aria-pressed and contains(., "History")]';
const ASSISTANT = '//header//button[contains(., "Assistant")]';

// [name, base, path, clicks before looking]
const SCREENS = [
  ['dev-applications', DEV, '/'],
  ['dev-application', DEV, '/applications/emea-street'],
  ['dev-work-spaces', DEV, '/work'],
  ['dev-new-work-space', DEV, '/work/new'],
  ['dev-space', DEV, `/space/${SPACE}`],
  ['dev-table', DEV, `/space/${SPACE}/table/fee`, [tab('Fee Code')]],
  ['dev-table-history', DEV, `/space/${SPACE}/table/fee`, [tab('Fee Code'), '//main//input[@aria-label="Rule 4, result Fee Code"]', HISTORY]],
  ['dev-table-connect', DEV, `/space/${SPACE}/table/fee`, [tab('How they connect')]],
  ['dev-table-assistant', DEV, `/space/${SPACE}/table/fee`, [tab('Fee Code'), ASSISTANT]],
  ['dev-test', DEV, `/space/${SPACE}/test`],
  ['dev-commit', DEV, `/space/${SPACE}/version`],
  ['dev-try', DEV, `/space/${SPACE}/try`],
  ['dev-merge', DEV, `/space/${SPACE}/land`],
  ['dev-releases', DEV, '/releases'],
  ['dev-releases-tag', DEV, '/releases', ['//main//figure//button[@aria-pressed and contains(normalize-space(.), "release/1.0")]', tab('Tag')]],
  ['dev-clean-up', DEV, '/tidy'],
  ['dev-commit-view', DEV, `/commit/${sha}`],
  ['dev-commit-table', DEV, `/commit/${sha}/fee`, [tab('Fee Code'), HISTORY]],
  ['prod-applications', PROD, '/'],
  ['prod-application', PROD, '/applications/emea-street'],
  ['prod-releases', PROD, '/releases'],
  ['prod-commit-table', PROD, `/commit/${sha}/fee`, [tab('Fee Code'), HISTORY]]
].filter(([name]) => !ONLY || ONLY.test(name));

// Run in the page: what is wrong with the layout in the part of the page now on screen.
function audit() {
  const issues = [];
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const label = (el) => {
    const name = el.getAttribute('aria-label') || el.getAttribute('title') || el.innerText || el.value || el.getAttribute('placeholder') || '';
    return `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''} “${String(name).trim().replace(/\s+/g, ' ').slice(0, 50)}”`;
  };
  // The part of an element actually on screen: cut by every ancestor that clips (a scrolled grid, the
  // page under the bars) and by the window. Null when none of it is.
  const shownRect = (el) => {
    const r = el.getBoundingClientRect();
    let box = { left: Math.max(0, r.left), top: Math.max(0, r.top), right: Math.min(vw, r.right), bottom: Math.min(vh, r.bottom) };
    for (let e = el.parentElement; e && e !== document.documentElement; e = e.parentElement) {
      const s = getComputedStyle(e);
      if (s.overflowX !== 'visible' || s.overflowY !== 'visible') {
        const c = e.getBoundingClientRect();
        box = { left: Math.max(box.left, c.left), top: Math.max(box.top, c.top), right: Math.min(box.right, c.right), bottom: Math.min(box.bottom, c.bottom) };
      }
    }
    return box.right - box.left >= 2 && box.bottom - box.top >= 2 ? box : null;
  };
  const visible = (el) => {
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    if (el.checkVisibility && !el.checkVisibility({ opacityProperty: true, visibilityProperty: true, contentVisibilityAuto: true })) return false;
    for (let e = el; e; e = e.parentElement) if (Number(getComputedStyle(e).opacity) < 0.05) return false;
    return !!shownRect(el);
  };
  // How many lines an element's text runs over.
  const lines = (el) => {
    const bottoms = [];
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      if (!n.textContent.trim()) continue;
      const range = document.createRange();
      range.selectNodeContents(n);
      for (const r of range.getClientRects()) if (r.width > 1) bottoms.push(r.bottom);
    }
    // Text on one line can sit a few pixels apart (a smaller font beside a larger one): a new line is
    // one that starts well below the last.
    bottoms.sort((a, b) => a - b);
    return bottoms.reduce((n, b, i) => (i === 0 || b - bottoms[i - 1] > 8 ? n + 1 : n), 0);
  };
  // Inside something that scrolls or clips sideways on purpose (a wide table, the graph).
  const scrollsX = (el) => {
    for (let e = el.parentElement; e && e !== document.body; e = e.parentElement) {
      const s = getComputedStyle(e).overflowX;
      if (s === 'auto' || s === 'scroll' || s === 'hidden' || s === 'clip') return e;
    }
    return null;
  };

  // 1. The page itself never scrolls sideways.
  const stage = document.querySelector('#stage');
  if (document.documentElement.scrollWidth > vw + 1) issues.push({ kind: 'page-overflow', what: `the page is ${document.documentElement.scrollWidth}px wide in a ${vw}px window` });
  if (stage && stage.scrollWidth > stage.clientWidth + 1) {
    const wide = [...stage.querySelectorAll('*')].filter((e) => e.getBoundingClientRect().right > stage.getBoundingClientRect().right + 1 && !scrollsX(e)?.closest('#stage *') && visible(e)).slice(0, 3).map(label);
    issues.push({ kind: 'page-overflow', what: `the page scrolls sideways: ${stage.scrollWidth}px in ${stage.clientWidth}px`, detail: wide.join(', ') });
  }

  // 2. Text cut off with no ellipsis to say so.
  for (const el of document.querySelectorAll('#stage *, footer *, header *, aside *')) {
    // The cheap tests first: most elements do not clip, and visible() walks every ancestor.
    if (el.scrollWidth <= el.clientWidth + 1) continue;
    const s = getComputedStyle(el);
    if ((s.overflowX !== 'hidden' && s.overflowX !== 'clip') || s.textOverflow === 'ellipsis') continue;
    if (![...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()) || !visible(el)) continue;
    issues.push({ kind: 'cut-text', what: `${label(el)} is cut off (${el.scrollWidth}px of text in ${el.clientWidth}px), no ellipsis` });
  }
  for (const el of document.querySelectorAll('input:not([type=checkbox]):not([type=radio]), select')) {
    if (!visible(el) || el.tagName === 'SELECT') continue;
    if (el.scrollWidth > el.clientWidth + 2 && document.activeElement !== el && getComputedStyle(el).textOverflow !== 'ellipsis' && !el.title) {
      issues.push({ kind: 'cut-value', what: `${label(el)}: its value is cut off, with no ellipsis or hover text` });
    }
  }

  // 3. Every control has a name, and nothing covers it.
  const controls = [...document.querySelectorAll('button, a[href], input, select, textarea, [role="button"], [role="tab"]')].filter(visible);
  for (const el of controls) {
    const name = (el.getAttribute('aria-label') || el.getAttribute('aria-labelledby') || el.getAttribute('title') || el.innerText || '').trim()
      || (el.id && document.querySelector(`label[for="${el.id}"]`)?.innerText.trim()) || el.closest('label')?.innerText.trim() || (el.tagName === 'INPUT' && el.placeholder);
    if (!name) issues.push({ kind: 'no-name', what: `${label(el)} has no accessible name`, detail: el.outerHTML.slice(0, 160) });
    // Whatever is on top at the middle of what is shown of it should be it. Sticky headings, the rule
    // numbers and the row tools sit over a scrolled grid on purpose.
    const box = shownRect(el);
    const top = document.elementFromPoint((box.left + box.right) / 2, (box.top + box.bottom) / 2);
    // On a narrow screen the assistant is a drawer over the page: what it covers, it covers on purpose.
    const drawer = top?.closest('#assistant');
    if (top && top !== el && !el.contains(top) && !top.contains(el) && !(top.tagName === 'LABEL' && top.control === el)
        && !top.closest('[role="tooltip"], .working, thead, th.num, .tools, .tools-head')
        && !(drawer && !drawer.contains(el) && getComputedStyle(drawer).position === 'fixed')) {
      issues.push({ kind: 'covered', what: `${label(el)} is covered by ${label(top)}` });
    }
    // 4. An action's label on one line.
    if (el.classList.contains('btn') && el.innerText.trim() && lines(el) > 1) {
      issues.push({ kind: 'wrapped-button', what: `${label(el)} wraps onto ${lines(el)} lines` });
    }
  }
  return issues;
}

const browser = await launch();
const page = await browser.newPage();
// Each screen as it first opens: the assistant closed (it remembers being open for the session).
await page.evaluateOnNewDocument(() => { try { sessionStorage.removeItem('assistant'); } catch {} });
const errors = [];
page.on('pageerror', (e) => errors.push(`page error: ${e.message}`));
page.on('response', (r) => r.status() >= 400 && !r.url().endsWith('favicon.ico') && errors.push(`${r.status()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`));
page.on('dialog', (d) => d.dismiss());

const report = [];
for (const width of WIDTHS) {
  mkdirSync(join(OUT, String(width)), { recursive: true });
  await page.setViewport({ width, height: 900 });
  for (const [name, base, path, clicks = []] of SCREENS) {
    const issues = [];
    try {
      await page.goto(base + path, { waitUntil: 'networkidle0', timeout: 90000 });
      for (const x of clicks) {
        const el = await page.waitForSelector(`xpath/${x}`, { timeout: 30000, visible: true });
        await el.click();
        await wait(600);
      }
      await page.waitForFunction(() => !document.querySelector('.loading'), { timeout: 120000, polling: 300 }).catch(() => {});
      await wait(700);
      // Down the page a screen at a time, so what is below the fold is checked too.
      const height = await page.evaluate(() => { const s = document.querySelector('#stage'); return s ? s.scrollHeight - s.clientHeight : 0; });
      const steps = Math.min(4, 1 + Math.ceil(height / 700));
      for (let i = 0; i < steps; i++) {
        await page.evaluate((top) => document.querySelector('#stage')?.scrollTo(0, top), i * 700);
        await wait(250);
        for (const issue of await page.evaluate(audit)) {
          if (!issues.some((x) => x.what === issue.what)) issues.push(issue);
        }
        if (i < 2) await page.screenshot({ path: join(OUT, String(width), `${name}${i ? `-${i + 1}` : ''}.png`) });
      }
    } catch (e) {
      issues.push({ kind: 'broke', what: e.message.split('\n')[0] });
      await page.screenshot({ path: join(OUT, String(width), `${name}-broke.png`) }).catch(() => {});
    }
    report.push({ width, name, issues });
    console.log(`${issues.length ? 'FAIL' : 'pass'}  ${width}  ${name}${issues.map((x) => `\n      ${x.kind}: ${x.what}${x.detail ? ` — ${x.detail}` : ''}`).join('')}`);
  }
}
await browser.close();

writeFileSync(join(OUT, 'report.json'), JSON.stringify({ report, errors }, null, 2));
const bad = report.filter((r) => r.issues.length);
if (errors.length) console.log(`\nrequests and page errors:\n  ${[...new Set(errors)].join('\n  ')}`);
console.log(`\n${report.length - bad.length} of ${report.length} screens clean${errors.length ? `; ${errors.length} errors` : ''}`);
process.exit(bad.length || errors.length ? 1 : 0);

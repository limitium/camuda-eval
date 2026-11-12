// The whole life of a rule change, through the UI, on a fresh dev + prod stack (see run.sh).
//
// dev, as alice: start a work space, change a result and add a rule, see a test fail and fix it, save,
// follow the cell in History, commit, publish a draft to try it, merge into main, create release/1.0,
// tag v1.0.0, deploy it. prod, as carol: deploy v1.0.0, open it and follow the same cell.
//
// Each step is checked where its result lands — the remote repository, the brokers, the API — not only
// on screen. Any page error, console error or failed request fails the run.
import { launch } from './chrome.mjs';
import { execFileSync } from 'child_process';
import { mkdirSync, writeFileSync } from 'fs';
import { join } from 'path';

const DEV = process.env.DEV_URL || 'http://localhost:7180';
const PROD = process.env.PROD_URL || 'http://localhost:7181';
const PROJECT = process.env.E2E_PROJECT || 'camunda-e2e';
const OUT = join(process.env.OUT || new URL('./out', import.meta.url).pathname, 'lifecycle');
mkdirSync(OUT, { recursive: true });

const SPACE = 'feature/alice/e2e-uk-sdrt';
const MESSAGE = 'Fee Code: UK trades pay SDRT; German XETRA trades get their own code';
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// --- reporting ---------------------------------------------------------------------------------------
const results = [];
let step = '';
function check(name, pass, detail = '') {
  results.push({ step, name, pass: !!pass, detail: pass ? '' : String(detail) });
  console.log(`${pass ? 'pass' : 'FAIL'}  ${name}${pass || !detail ? '' : `\n      ${String(detail).replace(/\s+/g, ' ').slice(0, 600)}`}`);
  return !!pass;
}
function begin(name) {
  step = name;
  console.log(`\n— ${name}`);
}

// --- the outside world: the remote repository and the brokers ---------------------------------------
function remoteGit(...args) {
  return execFileSync('docker', ['run', '--rm', '-v', `${PROJECT}_rules-git:/git`, 'alpine/git:2.45.2', '-C', '/git/rules.git', ...args], { encoding: 'utf8' }).trim();
}
function topic(tier, name) {
  // A console consumer is a JVM of its own: on a busy machine it can start slower than it waits, so
  // an empty read is tried once more before it counts.
  const read = () => {
    try {
      return execFileSync('docker', ['exec', `${PROJECT}-kafka-${tier}-1`, '/opt/kafka/bin/kafka-console-consumer.sh',
        '--bootstrap-server', 'localhost:9092', '--topic', name, '--from-beginning', '--timeout-ms', '20000'],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim().split('\n').filter(Boolean);
    } catch (e) {
      return (e.stdout || '').trim().split('\n').filter(Boolean);
    }
  };
  const first = read();
  return first.length ? first : read();
}
const api = async (base, path) => {
  const r = await fetch(base + path);
  if (!r.ok) throw new Error(`${r.status} ${path}: ${await r.text()}`);
  return r.json();
};

// --- the browser ------------------------------------------------------------------------------------
const browser = await launch({ defaultViewport: { width: 1440, height: 900 } });
const problems = [];
const requests = [];
async function open(label) {
  const page = await browser.newPage();
  page.on('pageerror', (e) => problems.push(`${label} ${step}: page error: ${e.message}`));
  page.on('console', (m) => m.type() === 'error' && !/Failed to load resource/.test(m.text()) && problems.push(`${label} ${step}: console: ${m.text()}`));
  page.on('response', (r) => r.status() >= 400 && !r.url().endsWith('favicon.ico') && problems.push(`${label} ${step}: ${r.status()} ${r.request().method()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`));
  page.on('dialog', (d) => { problems.push(`${label} ${step}: unexpected dialog: ${d.message()}`); d.dismiss(); });
  page.on('request', (r) => requests.push(r.url()));
  return page;
}
let shots = 0;
const shot = (page, name) => page.screenshot({ path: join(OUT, `${String(++shots).padStart(2, '0')}-${name}.png`) });

async function go(page, base, path, ready) {
  await page.bringToFront();   // a page in the background renders no frames: clicks and screenshots would hang
  await page.goto(base + path, { waitUntil: 'networkidle0', timeout: 90000 });
  if (ready) await page.waitForSelector(ready, { timeout: 60000 });
  await settle(page);
}
// Nothing loading, nothing busy.
async function settle(page, timeout = 120000) {
  await page.waitForFunction(() => !document.querySelector('.loading, [aria-busy="true"], [data-busy]'), { timeout, polling: 250 }).catch(() => {});
  await wait(250);
}
const stageText = (page) => page.$eval('#stage', (s) => s.innerText);
const footerText = (page) => page.$eval('footer', (f) => f.innerText);
async function waitText(page, pattern, timeout = 60000, where = '#stage') {
  const source = pattern.source;
  const flags = pattern.flags;
  await page.waitForFunction((sel, s, f) => new RegExp(s, f).test(document.querySelector(sel)?.innerText || ''), { timeout, polling: 300 }, where, source, flags);
}
// A button of the bar at the bottom, by its label (or the start of it).
async function press(page, label, { timeout = 30000 } = {}) {
  const handle = await page.waitForFunction((l) => [...document.querySelectorAll('footer button')]
    .find((b) => b.innerText.trim().startsWith(l) && !b.disabled), { timeout, polling: 250 }, label);
  await handle.asElement().click();
  await wait(300);
  await settle(page);
}
async function click(page, xpath, timeout = 30000) {
  const el = await page.waitForSelector(`xpath/${xpath}`, { timeout, visible: true });
  await el.click();
  await wait(250);
}
// Types into a field, replacing what is there; leaves it with Tab so the edit is taken.
async function fill(page, selector, value) {
  const el = await page.waitForSelector(selector, { timeout: 30000 });
  await el.click();
  await el.evaluate((e) => e.select());   // Cmd+A does nothing in headless Chrome on a Mac
  await page.keyboard.press('Backspace');
  await page.keyboard.type(value);
  await page.keyboard.press('Tab');
  await wait(200);
}
const value = (page, selector) => page.$eval(selector, (e) => e.value);
// The panel once it shows what was last asked for: it reads after a short pause, and dims while it does.
async function historyText(page) {
  await wait(400);
  await page.waitForFunction(() => document.querySelector('aside.history') && !document.querySelector('aside.history .loading, aside.history [aria-busy="true"]'), { timeout: 60000, polling: 250 });
  return page.$eval('aside.history', (a) => a.innerText);
}
async function historyLevel(page, label) {
  await click(page, `//aside[contains(@class,"history")]//button[@aria-pressed and normalize-space(.)="${label}"]`);
  await page.waitForFunction(() => !document.querySelector('aside.history .loading, aside.history [aria-busy="true"]'), { timeout: 60000, polling: 250 });
  await wait(300);
}

const dev = await open('dev');
let prod = null;
try {
  // ---------------------------------------------------------------------------------------------------
  begin('dev: what the applications run');
  await go(dev, DEV, '/', 'footer');
  await waitText(dev, /emea-street/);
  const home = await stageText(dev);
  check('both applications are listed, nothing deployed yet', /emea-street/.test(home) && /amer-street/.test(home), home);
  await shot(dev, 'dev-applications');

  // ---------------------------------------------------------------------------------------------------
  begin('dev: start a work space');
  await go(dev, DEV, '/work/new', '#space-name');
  await waitText(dev, /Builds on[\s\S]*main/);
  await fill(dev, '#space-name', 'e2e uk sdrt');
  check('the name becomes the branch', (await stageText(dev)).includes(SPACE), await stageText(dev));
  await shot(dev, 'dev-new-work-space');
  await press(dev, 'Start the work space');
  await dev.waitForFunction((p) => location.pathname === `/space/${p}`, { timeout: 30000 }, SPACE);
  const spaces = await api(DEV, '/api/workspaces');
  check('the work space exists, on main', spaces.some((s) => s.id === SPACE), JSON.stringify(spaces.map((s) => s.id)));
  await settle(dev);
  await shot(dev, 'dev-space');

  // ---------------------------------------------------------------------------------------------------
  begin('dev: change a result and add a rule');
  await click(dev, `//main//a[contains(@href,"/table/fee")]`);
  await click(dev, '//main//button[@role="tab" and normalize-space(.)="Fee Code"]', 60000);
  await dev.waitForSelector('input[aria-label="Rule 4, result Fee Code"]', { timeout: 60000 });
  await settle(dev);
  check('rule 4 gives UK-STAMP before the change', (await value(dev, 'input[aria-label="Rule 4, result Fee Code"]')) === '"UK-STAMP"');
  await fill(dev, 'input[aria-label="Rule 4, result Fee Code"]', '"UK-SDRT"');
  const insert = await dev.waitForSelector('button[aria-label="Insert a rule after rule 4"]');
  await insert.click();
  await dev.waitForSelector('input[aria-label="Rule 7, result Fee Code"]', { timeout: 10000 });
  const newRule = { Country: '"DE"', Market: '"XETRA"', Amount: '-', 'result Fee Code': '"DE-XETRA"', 'result Fee Calculator': '"BPS-FLAT"' };
  for (const [column, v] of Object.entries(newRule)) await fill(dev, `input[aria-label="Rule 5, ${column}"]`, v);
  const changed = await dev.$eval('.edited-line', (e) => e.innerText).catch(() => '');
  check('the screen counts the unsaved changes, in blue', /2 changes/.test(changed), changed);
  check('rule 5 is the new one, the old rule 5 is rule 6 now', (await value(dev, 'input[aria-label="Rule 6, result Fee Code"]')) === '"EU-STD"');
  await shot(dev, 'dev-table-edited');

  // ---------------------------------------------------------------------------------------------------
  begin('dev: a test fails, and is fixed');
  await press(dev, 'Run the tests');
  await dev.waitForFunction(() => !/Running the tests/.test(document.querySelector('footer').innerText), { timeout: 120000, polling: 300 });
  await settle(dev);
  const set = await dev.waitForSelector('xpath/.//section[@aria-label="Test sets"]//button[contains(@class,"set-row") and contains(., "Fee Code alone")]');
  const setRow = await set.evaluate((b) => b.innerText);
  check('the set shows it fails, before it is opened', /fail/i.test(setRow), setRow);
  if ((await set.evaluate((b) => b.getAttribute('aria-expanded'))) !== 'true') await set.click();
  await wait(500);
  await set.evaluate((b) => b.scrollIntoView({ block: 'start' }));
  const failing = await dev.$eval('section[aria-label="Test sets"]', (s) => s.innerText);
  check('the failing case says what it expected and what it got', /feeCode: expected UK-STAMP, Fee Code gave UK-SDRT/.test(failing), failing.slice(0, 1500));
  await shot(dev, 'dev-test-fails');
  const expectation = await dev.evaluate(() => {
    const input = [...document.querySelectorAll('input[aria-label^="Case "]')].find((i) => i.value.trim() === 'UK-STAMP');
    return input?.getAttribute('aria-label');
  });
  check('the failing case’s expectation is a field in the test cases', !!expectation, expectation);
  if (expectation) await fill(dev, `input[aria-label="${expectation}"]`, 'UK-SDRT');
  await press(dev, 'Run the tests');   // a changed case drops the last run: it is "Run the tests" again
  await dev.waitForFunction(() => !/Running the tests/.test(document.querySelector('footer').innerText), { timeout: 120000, polling: 300 });
  await settle(dev);
  const passing = await dev.$eval('section[aria-label="Test sets"] button.set-row', (b) => b.innerText);
  check('run again, every case passes', /pass/i.test(passing) && !/fail/i.test(passing), passing);
  await shot(dev, 'dev-test-passes');
  await press(dev, 'Save');
  await dev.waitForFunction(() => !document.querySelector('.edited-line'), { timeout: 30000, polling: 250 });
  const saved = (await api(DEV, `/api/workspaces/${SPACE}/changes`)).files || [];
  check('saved: the rules and the tests are the work space’s changes', saved.some((f) => /fee\.dmn$/.test(f.path || f)) && saved.some((f) => /fee\.yaml$/.test(f.path || f)), JSON.stringify(saved));

  // ---------------------------------------------------------------------------------------------------
  begin('dev: History, on request only');
  const historyReads = () => requests.filter((u) => u.includes('/api/history')).length;
  // A cell picked, then History opened: the opening is the asking, for that cell.
  await dev.click('input[aria-label="Rule 4, result Fee Code"]');
  check('closed, the history reads nothing when a cell is picked', historyReads() === 0, historyReads());
  await click(dev, '//main//button[@aria-pressed and contains(., "History")]');
  await dev.waitForSelector('aside.history');
  let h = await historyText(dev);
  check('opened, it shows the picked cell: saved, not committed, UK-STAMP → UK-SDRT first', /rule 4, Fee Code/i.test(h) && /Not committed[\s\S]*"UK-STAMP"\s*→\s*"UK-SDRT"/.test(h), h);
  check('then where it came from: the first commit, with UK-STAMP', /initialise rule repository[\s\S]*Rule added with\s*"UK-STAMP"/.test(h), h);
  await shot(dev, 'dev-history-cell');
  // Clicking about the table, to edit it, reads nothing and leaves the panel as it is.
  const before = historyReads();
  for (const cell of ['Rule 2, Market', 'Rule 5, Country', 'Rule 1, result Fee Calculator']) {
    await dev.click(`input[aria-label="${cell}"]`);
    await wait(300);
  }
  await wait(600);
  h = await historyText(dev);
  check('clicking three other cells reads no history and keeps showing rule 4', historyReads() === before && /rule 4, Fee Code/i.test(h), `${historyReads() - before} reads | ${h.slice(0, 120)}`);
  check('the last cell picked is offered, not shown', /Show the history of rule 1, Fee Calculator/.test(h), h.slice(0, 300));
  await shot(dev, 'dev-history-offer');
  await dev.click('input[aria-label="Rule 5, Country"]');
  await historyLevel(dev, 'Rule');
  h = await historyText(dev);
  check('Rule pressed, with rule 5 picked: the new rule’s history, added, not committed yet', /Not committed/.test(h) && /Rule 5 added/.test(h) && !/initialise/.test(h), h);
  await dev.click('input[aria-label="Rule 4, result Fee Code"]');
  await historyLevel(dev, 'Column');
  h = await historyText(dev);
  check('Column pressed: both its new values, then every rule’s first value', /Rule 4\s*"UK-STAMP"\s*→\s*"UK-SDRT"/.test(h) && /Rule 5\s*added\s*"DE-XETRA"/.test(h) && /initialise/.test(h), h);
  await shot(dev, 'dev-history-column');

  // ---------------------------------------------------------------------------------------------------
  begin('dev: review and commit');
  await press(dev, 'Review and commit changes');
  await dev.waitForSelector('#version-message', { timeout: 30000 });
  await settle(dev);
  const review = await stageText(dev);
  check('the tests passed before the save count: no warning that they have not', !/has not passed its tests/.test(review), review.slice(0, 600));
  check('the review shows both rule changes', /UK-STAMP/.test(review) && /UK-SDRT/.test(review) && /DE-XETRA/.test(review), review.slice(0, 2000));
  check('rules pushed down by the new one are not listed as moved', !/Moved/.test(review), review.slice(0, 2000));
  check('the review names the changed test case and its new expectation', /London pays stamp duty[\s\S]{0,40}expects feeCode\s*UK-STAMP\s*→\s*UK-SDRT/.test(review), review.slice(0, 2000));
  await fill(dev, '#version-message', MESSAGE);
  await shot(dev, 'dev-commit');
  await press(dev, 'Commit changes');
  await dev.waitForFunction((p) => !location.pathname.endsWith('/version') || /committed/i.test(document.querySelector('footer').innerText), { timeout: 60000, polling: 300 }, SPACE);
  const left = (await api(DEV, `/api/workspaces/${SPACE}/changes`)).files || [];
  check('nothing is left uncommitted', left.length === 0, JSON.stringify(left));
  let remote = '';
  try { remote = remoteGit('log', '-1', '--format=%an|%s', SPACE); } catch (e) { remote = e.message; }
  check('the commit is on the work space’s branch in the remote, by alice', remote.startsWith('alice|') && remote.includes('UK trades pay SDRT'), remote);
  await settle(dev);
  await shot(dev, 'dev-committed');

  begin('dev: History after the commit');
  await go(dev, DEV, `/space/${SPACE}/table/fee`, 'footer');
  await click(dev, '//main//button[@role="tab" and normalize-space(.)="Fee Code"]', 60000);
  await dev.click('input[aria-label="Rule 4, result Fee Code"]');
  await click(dev, '//main//button[@aria-pressed and contains(., "History")]');
  await dev.waitForFunction((m) => (document.querySelector('aside.history')?.innerText || '').includes(m.slice(0, 20)), { timeout: 30000, polling: 300 }, MESSAGE);
  h = await historyText(dev);
  check('the cell’s newest change is the commit, by alice, nothing uncommitted', h.includes('UK trades pay SDRT') && /alice/.test(h) && !/Not committed/.test(h), h);

  // ---------------------------------------------------------------------------------------------------
  begin('dev: publish a draft to try it');
  await go(dev, DEV, `/space/${SPACE}/try`, 'footer');
  await waitText(dev, /pass/i, 120000);
  await dev.click('main input[type="radio"][value="emea-street"]');
  await dev.click('main input[type="checkbox"]');
  await wait(300);
  check('picked and agreed, the bar offers to publish to emea-street', /Publish to emea-street/.test(await footerText(dev)) && await dev.$eval('main input[type="checkbox"]', (b) => b.checked), await footerText(dev));
  await shot(dev, 'dev-try');
  await press(dev, 'Publish to emea-street', { timeout: 120000 });
  await waitText(dev, /emea-street/, 60000);
  const draftTopic = topic('dev', 'dmn-emea-street-dev');
  check('the draft is on emea-street’s dev topic, naming the work space', draftTopic.some((m) => m.includes(SPACE)), draftTopic.map((m) => m.slice(0, 200)).join(' | '));
  await shot(dev, 'dev-try-published');

  // ---------------------------------------------------------------------------------------------------
  begin('dev: merge into main');
  await go(dev, DEV, `/space/${SPACE}/land`, 'footer');
  await dev.waitForFunction(() => /Merge into \d/.test(document.querySelector('footer').innerText), { timeout: 120000, polling: 300 });
  await shot(dev, 'dev-merge-check');
  await press(dev, 'Merge into', { timeout: 10000 });
  await dev.waitForFunction(() => /Remove /.test(document.querySelector('footer').innerText), { timeout: 120000, polling: 300 });
  const onMain = await api(DEV, '/api/history?domain=fee&rev=main&decision=FeeCode&rule=FeeCode_r4&column=FeeCode_out_feeCode');
  check('on main, the cell’s newest change is alice’s commit, not the merge', onMain.entries[0]?.message?.startsWith(MESSAGE) && onMain.entries[0]?.author === 'alice' && !onMain.entries[0]?.merge, JSON.stringify(onMain.entries[0]));
  check('the remote’s main has it', remoteGit('log', '-3', '--format=%s', 'main').includes('UK trades pay SDRT'));
  await shot(dev, 'dev-merged');
  await press(dev, 'Keep the work space');
  await dev.waitForFunction((p) => location.pathname === `/space/${p}`, { timeout: 30000 }, SPACE);
  await settle(dev);
  const kept = await stageText(dev);
  check('kept, the work space says it is merged and offers no merge', /Merged into main/.test(kept) && !/Merge them when you are ready/.test(kept) && !/^Merge$/m.test(await footerText(dev)), `${kept.slice(0, 300)} | ${await footerText(dev)}`);

  // ---------------------------------------------------------------------------------------------------
  begin('dev: create release/1.0 and tag v1.0.0');
  await go(dev, DEV, '/releases', 'footer');
  await click(dev, '//main//figure//button[@aria-pressed and substring(normalize-space(.), string-length(normalize-space(.)) - 3) = "main"]');
  await dev.waitForSelector('#branch-number');
  if (!(await value(dev, '#branch-number'))) await fill(dev, '#branch-number', '1.0');
  check('a release branch from main is offered as release/1.0', /release\/1\.0 here/.test(await footerText(dev)), await footerText(dev));
  await shot(dev, 'dev-release-branch');
  await press(dev, 'Create branch release/');
  await dev.waitForFunction(() => /release\/1\.0/.test(document.querySelector('#stage h2 ~ *, #stage')?.innerText || ''), { timeout: 30000 });
  const lines = await api(DEV, '/api/lifecycle/lines');
  check('release/1.0 exists', lines.some((l) => l.branch === 'release/1.0'), JSON.stringify(lines.map((l) => l.branch)));
  check('and is in the remote', remoteGit('branch', '--list', 'release/1.0').includes('release/1.0'));

  await go(dev, DEV, '/releases', 'footer');
  await click(dev, '//main//figure//button[@aria-pressed and contains(normalize-space(.), "release/1.0")]');
  const tagTab = await dev.$('xpath/.//main//button[@role="tab" and normalize-space(.)="Tag"]');
  if (tagTab) await tagTab.click();
  await dev.waitForSelector('#tag-number');
  if (!(await value(dev, '#tag-number'))) await fill(dev, '#tag-number', '1.0.0');
  await dev.waitForFunction(() => /Tag as v1\.0\.0/.test(document.querySelector('footer').innerText) && !document.querySelector('footer button.btn-next')?.disabled, { timeout: 120000, polling: 300 });
  await shot(dev, 'dev-tag');
  await press(dev, 'Tag as v1.0.0');
  await dev.waitForFunction(() => /v1\.0\.0/.test(document.querySelector('#stage').innerText) && /tests green/.test(document.querySelector('#stage').innerText), { timeout: 60000, polling: 300 });
  check('the bar confirms the tag', /Tagged \w+ as v1\.0\.0/.test(await footerText(dev)), await footerText(dev));
  const tagged = (await api(DEV, '/api/lifecycle/lines')).find((l) => l.branch === 'release/1.0');
  check('v1.0.0 is a tag on release/1.0, tests green', tagged?.versions?.some((v) => v.name === 'v1.0.0' && v.verified), JSON.stringify(tagged?.versions));
  check('and is in the remote', remoteGit('tag', '--list', 'v1.0.0') === 'v1.0.0');
  await shot(dev, 'dev-tagged');

  // ---------------------------------------------------------------------------------------------------
  begin('dev: deploy v1.0.0');
  await go(dev, DEV, '/applications/emea-street', 'select[aria-label="Tag to deploy"]');
  await dev.select('select[aria-label="Tag to deploy"]', 'v1.0.0');
  await dev.waitForFunction(() => /Deploy v1\.0\.0 to emea-street/.test(document.querySelector('footer').innerText) && !document.querySelector('footer button.btn-next')?.disabled, { timeout: 120000, polling: 300 });
  await shot(dev, 'dev-deploy');
  await press(dev, 'Deploy v1.0.0 to emea-street');
  await waitText(dev, /v1\.0\.0/, 30000);
  // Read at once: the confirmation gives way to the next hint after a few seconds, by design.
  check('the bar confirms the deploy', /emea-street runs v1\.0\.0 now/.test(await footerText(dev)), await footerText(dev));
  const devTopic = topic('dev', 'dmn-emea-street-dev');
  check('emea-street’s dev topic has v1.0.0 as its newest message', /v1\.0\.0/.test(devTopic[devTopic.length - 1] || ''), (devTopic[devTopic.length - 1] || '').slice(0, 300));
  check('with no tag chosen any more, Deploy waits', await dev.$eval('footer button.btn-next', (b) => b.disabled && /Deploy a tag/.test(b.innerText)).catch(() => false), await footerText(dev));
  const devApp = await api(DEV, '/api/applications/emea-street');
  check('emea-street runs v1.0.0 on dev', devApp.version?.release === 'v1.0.0', JSON.stringify(devApp.version));
  await shot(dev, 'dev-deployed');

  // ---------------------------------------------------------------------------------------------------
  begin('prod: deploy only');
  prod = await open('prod');
  await go(prod, PROD, '/work', 'footer');
  check('there are no work spaces on prod: /work goes to the applications', new URL(prod.url()).pathname === '/', prod.url());
  await go(prod, PROD, '/applications/emea-street', 'select[aria-label="Tag to deploy"]');
  const offered = await prod.$$eval('select[aria-label="Tag to deploy"] option', (o) => o.map((x) => x.value));
  check('prod offers v1.0.0, made on dev', offered.includes('v1.0.0'), JSON.stringify(offered));
  await prod.select('select[aria-label="Tag to deploy"]', 'v1.0.0');
  await prod.waitForFunction(() => /Deploy v1\.0\.0 to emea-street/.test(document.querySelector('footer').innerText) && !document.querySelector('footer button.btn-next')?.disabled, { timeout: 120000, polling: 300 });
  await shot(prod, 'prod-deploy');
  await press(prod, 'Deploy v1.0.0 to emea-street');
  await waitText(prod, /v1\.0\.0/, 30000);
  const prodTopic = topic('prod', 'dmn-emea-street-prod');
  check('emea-street’s prod topic has v1.0.0', /v1\.0\.0/.test(prodTopic[prodTopic.length - 1] || ''), (prodTopic[prodTopic.length - 1] || '').slice(0, 300));
  check('and nothing reached amer-street', topic('prod', 'dmn-amer-street-prod').length === 0);
  await shot(prod, 'prod-deployed');

  begin('prod: the release, opened, and its history');
  const sha = remoteGit('rev-parse', 'v1.0.0^{commit}');
  await go(prod, PROD, `/commit/${sha}/fee`, 'footer');
  await click(prod, '//main//button[@role="tab" and normalize-space(.)="Fee Code"]', 60000);
  check('read only, with the history closed, cells are not pickers', !(await prod.$('main span[role="button"][aria-label*="pick it for the history"]')));
  await click(prod, '//main//button[@aria-pressed and contains(., "History")]');
  const cell = await prod.waitForSelector(`xpath/.//main//span[@role="button" and contains(@aria-label, "Rule 4") and contains(@aria-label, "Fee Code") and not(contains(@aria-label, "Calculator"))]`, { timeout: 30000 });
  await cell.click();
  await click(prod, '//aside[contains(@class,"history")]//button[contains(., "Show the history of rule 4, Fee Code")]');
  await prod.waitForFunction(() => /UK trades pay SDRT/.test(document.querySelector('aside.history')?.innerText || ''), { timeout: 30000, polling: 300 });
  h = await historyText(prod);
  check('read only on prod, the cell’s history is the same: alice’s commit, then the first', /alice/.test(h) && /"UK-STAMP"\s*→\s*"UK-SDRT"/.test(h) && /initialise rule repository/.test(h) && !/Not committed/.test(h), h);
  await shot(prod, 'prod-history');
  const refused = await fetch(`${PROD}/api/workspaces`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: 'x', baseline: 'main' }) });
  check('prod refuses to start a work space', refused.status === 403, refused.status);
} catch (e) {
  check(`no step broke off (${e.message.split('\n')[0]})`, false, e.stack);
  await shot(dev, 'broke-dev').catch(() => {});
  if (prod) await shot(prod, 'broke-prod').catch(() => {});
}

begin('the whole run');
const expected = (p) => /403 POST \/api\/workspaces$/.test(p);
const unexpected = problems.filter((p) => !expected(p));
check('no page errors, console errors or failed requests', unexpected.length === 0, unexpected.join('\n'));
await browser.close();

const failed = results.filter((r) => !r.pass);
writeFileSync(join(OUT, 'results.json'), JSON.stringify({ results, problems }, null, 2));
console.log(`\n${results.length - failed.length} of ${results.length} checks pass${failed.length ? `; failing: ${failed.map((f) => `${f.step}: ${f.name}`).join('; ')}` : ''}`);
process.exit(failed.length ? 1 : 0);

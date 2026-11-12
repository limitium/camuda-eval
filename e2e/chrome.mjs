// Headless Chrome for the e2e scripts: the installed one (CHROME to choose another), started here and
// connected to. Started by puppeteer, it can hang before it is up: a pipe nobody reads fills and Chrome
// blocks on writing to it. Here stdout is not piped at all and stderr is read to the end. The start is
// tried three times, as it can take minutes under Rosetta.
import puppeteer from 'puppeteer-core';
import { spawn } from 'child_process';
import { mkdtempSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';

const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

function start() {
  const child = spawn(CHROME, [
    '--headless=new', '--remote-debugging-port=0', `--user-data-dir=${mkdtempSync(join(tmpdir(), 'e2e-'))}`,
    '--no-first-run', '--no-default-browser-check',
    // a tab in the background keeps running its timers and drawing: the scripts use more than one
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows',
    'about:blank'
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  const endpoint = new Promise((resolve, reject) => {
    let seen = '';
    const timer = setTimeout(() => reject(new Error('no DevTools endpoint after 120 s')), 120000);
    child.stderr.on('data', (chunk) => {
      seen = (seen + chunk).slice(-4000);
      const m = /DevTools listening on (ws:\/\/\S+)/.exec(seen);
      if (m) { clearTimeout(timer); resolve(m[1]); }
    });
    child.on('exit', (code) => { clearTimeout(timer); reject(new Error(`Chrome exited (${code})`)); });
  });
  return { child, endpoint };
}

export async function launch({ defaultViewport = null } = {}) {
  let last;
  for (let attempt = 1; attempt <= 3; attempt++) {
    const { child, endpoint } = start();
    try {
      const browser = await puppeteer.connect({ browserWSEndpoint: await endpoint, defaultViewport, protocolTimeout: 240000 });
      const close = browser.close.bind(browser);
      browser.close = async () => { await close().catch(() => {}); child.kill(); };
      return browser;
    } catch (e) {
      last = e;
      child.kill();
      console.log(`Chrome did not start (attempt ${attempt} of 3): ${e.message.split('\n')[0]}`);
    }
  }
  throw last;
}

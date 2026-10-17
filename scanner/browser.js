'use strict';
// Optional headless-browser rendering via the Chrome DevTools Protocol (CDP), with zero npm
// dependencies — it drives an installed Chrome/Edge over a WebSocket. Used so JavaScript-rendered
// apps (Nuxt, React, etc.) are scanned as a browser actually sees them. Still passive: it only
// navigates (GET) to same-origin pages and reads what rendered.

const { spawn } = require('child_process');
const os = require('os');
const fs = require('fs');
const path = require('path');

function findBrowser() {
  const candidates = process.platform === 'win32'
    ? [
        'C:/Program Files/Google/Chrome/Application/chrome.exe',
        'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
        'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
        'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
      ]
    : process.platform === 'darwin'
      ? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
         '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge']
      : ['/usr/bin/google-chrome', '/usr/bin/chromium-browser', '/usr/bin/chromium', '/usr/bin/microsoft-edge'];
  return candidates.find((p) => { try { return fs.existsSync(p); } catch (_) { return false; } }) || null;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Minimal CDP client over a single flat WebSocket connection.
class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.listeners = new Map();
    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch (_) { return; }
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
      } else if (msg.method) {
        (this.listeners.get(msg.method) || []).forEach((cb) => cb(msg.params, msg.sessionId));
      }
    });
  }
  send(method, params = {}, sessionId) {
    const id = ++this.id;
    const payload = { id, method, params };
    if (sessionId) payload.sessionId = sessionId;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify(payload));
      setTimeout(() => {
        if (this.pending.has(id)) { this.pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }
      }, 30000);
    });
  }
  on(method, cb) {
    if (!this.listeners.has(method)) this.listeners.set(method, []);
    this.listeners.get(method).push(cb);
  }
}

function openWs(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.addEventListener('open', () => resolve(ws), { once: true });
    ws.addEventListener('error', (e) => reject(new Error('WebSocket error: ' + (e.message || 'failed'))), { once: true });
  });
}

function parseCookieHeader(str, origin) {
  return str.split(';').map((p) => p.trim()).filter(Boolean).map((p) => {
    const eq = p.indexOf('=');
    return { name: p.slice(0, eq), value: p.slice(eq + 1), url: origin };
  });
}

// Launches a browser, returns a fetcher with page(url) and close().
async function createBrowserFetcher({ origin, cookieHeader, settleMs = 1200 } = {}) {
  const bin = findBrowser();
  if (!bin) {
    throw new Error('No Chrome or Edge found for --browser mode. Install Google Chrome, or run without --browser.');
  }
  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'confix-cdp-'));
  const child = spawn(bin, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--remote-debugging-port=0', `--user-data-dir=${userDataDir}`, 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });

  const wsUrl = await new Promise((resolve, reject) => {
    let buf = '';
    const t = setTimeout(() => reject(new Error('Browser did not expose a DevTools endpoint in time.')), 15000);
    child.stderr.on('data', (d) => {
      buf += d.toString();
      const m = buf.match(/ws:\/\/[^\s]+\/devtools\/browser\/[a-f0-9-]+/);
      if (m) { clearTimeout(t); resolve(m[0]); }
    });
    child.on('exit', (c) => { clearTimeout(t); reject(new Error('Browser exited early (code ' + c + ').')); });
  });

  const browserWs = await openWs(wsUrl);
  const cdp = new Cdp(browserWs);

  // One reusable page target.
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
  await cdp.send('Page.enable', {}, sessionId);
  await cdp.send('Network.enable', {}, sessionId);
  await cdp.send('Runtime.enable', {}, sessionId);

  if (cookieHeader && origin) {
    try { await cdp.send('Network.setCookies', { cookies: parseCookieHeader(cookieHeader, origin) }, sessionId); } catch (_) {}
  }

  // Track main-document responses for status + headers.
  let docResponses = {};
  cdp.on('Network.responseReceived', (p) => {
    if (p.type === 'Document') docResponses[p.response.url] = p.response;
  }, sessionId);

  async function page(url) {
    docResponses = {};
    await cdp.send('Page.navigate', { url }, sessionId);
    await Promise.race([
      new Promise((res) => cdp.on('Page.loadEventFired', () => res(), sessionId)),
      sleep(20000),
    ]);
    await sleep(settleMs); // let late XHR/render settle

    const html = (await cdp.send('Runtime.evaluate',
      { expression: 'document.documentElement.outerHTML', returnByValue: true }, sessionId)).result.value || '';

    const all = await cdp.send('Network.getAllCookies', {}, sessionId);
    let host;
    try { host = new URL(url).hostname; } catch (_) { host = ''; }
    const cookies = (all.cookies || [])
      .filter((c) => host.endsWith(c.domain.replace(/^\./, '')))
      .map((c) => ({ name: c.name, httpOnly: !!c.httpOnly, secure: !!c.secure, sameSite: (c.sameSite || '').toLowerCase() }));

    const doc = docResponses[url] || Object.values(docResponses)[0] || { status: 0, headers: {} };
    const headers = {};
    for (const [k, v] of Object.entries(doc.headers || {})) headers[k.toLowerCase()] = v;

    return { status: doc.status || 200, headers, html, cookies };
  }

  async function close() {
    try { browserWs.close(); } catch (_) {}
    try { child.kill(); } catch (_) {}
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (_) {}
  }

  return { page, close };
}

module.exports = { createBrowserFetcher, findBrowser };

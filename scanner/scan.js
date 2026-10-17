#!/usr/bin/env node
'use strict';
// ConfixHub PassiveVuln Scanner — passive, same-origin crawl.
//
// Passive means: GET only, following links the pages themselves expose, reading the responses any
// visitor already receives. It never submits forms, logs in, injects payloads, brute-forces, or
// touches anything cross-origin. Findings are informational, not confirmed exploitable issues.
//
// AUTHORIZED USE ONLY. Scan sites you own or have written permission to test.

const { finding, parseSetCookies, pageLinks, runPageChecks } = require('./checks');
const { buildHtmlReport } = require('./report');
const fs = require('fs');
const path = require('path');

function parseArgs(argv) {
  const args = { maxPages: 50, delayMs: 400, out: null, cookieEnv: null, sensitiveProbe: true, authorized: false };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    if (a === '--target' || a === '-t') args.target = next();
    else if (a === '--max-pages') args.maxPages = parseInt(next(), 10);
    else if (a === '--delay') args.delayMs = parseInt(next(), 10);
    else if (a === '--out' || a === '-o') args.out = next();
    else if (a === '--cookie-env') args.cookieEnv = next();
    else if (a === '--no-sensitive-probe') args.sensitiveProbe = false;
    else if (a === '--browser') args.browser = true;
    else if (a === '--i-am-authorized') args.authorized = true;
    else if (a === '--help' || a === '-h') args.help = true;
    else { console.error(`Unknown option: ${a}`); process.exit(2); }
  }
  return args;
}

const HELP = `ConfixHub PassiveVuln Scanner

Usage:
  node scan.js --target https://app.example.com --i-am-authorized [options]

Required:
  --target, -t <url>       Start URL. Only same-origin pages are visited.
  --i-am-authorized        Confirm you own or are permitted to test this target.

Options:
  --max-pages <n>          Max pages to crawl (default 50).
  --delay <ms>             Delay between requests, be polite (default 400).
  --cookie-env <VAR>       Name of an env var holding a Cookie header, to scan your
                           own authenticated app. The value is never printed or saved.
  --no-sensitive-probe     Skip the source-map / security.txt GET probes.
  --browser                Render pages in headless Chrome/Edge (for JS-heavy apps like Nuxt/React).
                           Uses an installed browser via DevTools; no npm install needed.
  --out, -o <path>         Write the HTML report here (default scanner/reports/<host>-<ts>.html).
  --help, -h               This help.

Passive only: GET requests, same-origin links, no login/forms/injection. Authorized use only.`;

const SENSITIVE_PROBES = [
  // Exposed source maps leak original source. Probed only for scripts the page actually loads.
  { suffix: '.map', score: 5.5, title: (u) => `Source map returned HTTP 200: ${u}`,
    detail: 'Production source maps reveal original source code and comments. Stop serving .map files publicly.' },
];

async function fetchPage(url, headers) {
  const res = await fetch(url, { headers, redirect: 'follow' });
  const ct = res.headers.get('content-type') || '';
  const html = ct.includes('text/html') ? await res.text() : '';
  const hdrs = {};
  res.headers.forEach((v, k) => { hdrs[k] = v; });
  const setCookie = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
  return { status: res.status, headers: hdrs, html, setCookie, finalUrl: res.url || url };
}

async function headOk(url, headers) {
  try {
    const res = await fetch(url, { headers, redirect: 'manual' });
    return res.status;
  } catch (_) {
    return 0;
  }
}

function sameOrigin(u, origin) {
  try { return new URL(u).origin === origin; } catch (_) { return false; }
}

async function main() {
  const args = parseArgs(process.argv);
  if (args.help || !args.target) { console.log(HELP); process.exit(args.help ? 0 : 2); }

  let start;
  try { start = new URL(args.target); } catch (_) {
    console.error('Invalid --target URL.'); process.exit(2);
  }
  if (!/^https?:$/.test(start.protocol)) { console.error('Target must be http(s).'); process.exit(2); }

  if (!args.authorized) {
    console.error('\nRefusing to scan without authorization.');
    console.error('Re-run with --i-am-authorized only for a site you own or are permitted to test.\n');
    process.exit(3);
  }

  const origin = start.origin;
  const cookieHeader = args.cookieEnv ? process.env[args.cookieEnv] : null;
  const baseHeaders = { 'User-Agent': 'ConfixHub-PassiveVuln/1.0 (+passive; authorized-use-only)' };
  if (cookieHeader) baseHeaders['Cookie'] = cookieHeader;

  // Page fetcher: headless browser (renders JS) or plain GET. Source-map/security.txt probes below
  // stay on plain GET either way — they don't need a browser.
  let browserFetcher = null;
  if (args.browser) {
    const { createBrowserFetcher } = require('./browser');
    try {
      browserFetcher = await createBrowserFetcher({ origin, cookieHeader });
      console.log('Renderer  : headless browser (DevTools)\n');
    } catch (e) {
      console.error(`Browser mode unavailable: ${e.message}\n`);
      process.exit(4);
    }
  }
  const getPage = async (url) => {
    if (browserFetcher) {
      const r = await browserFetcher.page(url);
      return { url, origin, status: r.status, headers: r.headers, html: r.html, cookies: r.cookies };
    }
    const r = await fetchPage(url, baseHeaders);
    return { url, origin, status: r.status, headers: r.headers, html: r.html, cookies: parseSetCookies(r.setCookie) };
  };

  const queue = [start.href];
  const seen = new Set();
  const findings = [];
  const pages = [];
  const startedAt = new Date();

  console.log(`\nConfixHub PassiveVuln Scanner`);
  console.log(`Target     : ${origin}`);
  console.log(`Mode       : same-origin link crawl (passive, GET only)`);
  console.log(`Max pages  : ${args.maxPages}\n`);

  const probedMaps = new Set();

  while (queue.length && seen.size < args.maxPages) {
    const url = queue.shift();
    if (seen.has(url) || !sameOrigin(url, origin)) continue;
    seen.add(url);

    let page;
    try {
      page = await getPage(url);
    } catch (e) {
      console.log(`  ✗ ${url}  (${e.message})`);
      continue;
    }
    pages.push({ url, status: page.status });
    console.log(`  ${page.status}  ${url}`);

    findings.push(...runPageChecks(page));

    // Discover more same-origin links
    if (page.html) {
      for (const href of pageLinks(page.html)) {
        try {
          const abs = new URL(href, url).href.split('#')[0];
          if (sameOrigin(abs, origin) && !seen.has(abs) && !queue.includes(abs)) queue.push(abs);
        } catch (_) {}
      }

      // Sensitive-file probe: only for the .js files this page already loads
      if (args.sensitiveProbe) {
        const { scriptSources } = require('./checks');
        for (const s of scriptSources(page.html)) {
          let jsUrl;
          try { jsUrl = new URL(s.src, url).href; } catch (_) { continue; }
          if (!sameOrigin(jsUrl, origin) || !/\.js(\?|$)/.test(jsUrl)) continue;
          const mapUrl = jsUrl.split('?')[0] + '.map';
          if (probedMaps.has(mapUrl)) continue;
          probedMaps.add(mapUrl);
          const st = await headOk(mapUrl, baseHeaders);
          if (st === 200) {
            findings.push(finding(5.5, `Source map returned HTTP 200: ${new URL(mapUrl).pathname}`, url,
              'Production source maps reveal original source code. Stop serving .map files publicly.'));
          }
          await sleep(args.delayMs);
        }
      }
    }

    await sleep(args.delayMs);
  }

  // security.txt (once per origin)
  if (args.sensitiveProbe) {
    let found = false;
    for (const p of ['/.well-known/security.txt', '/security.txt']) {
      if ((await headOk(origin + p, baseHeaders)) === 200) { found = true; break; }
    }
    if (!found) {
      findings.push(finding(0.0, 'security.txt was not found at the standard paths checked.', origin,
        'Publishing /.well-known/security.txt gives researchers a way to report issues. Optional but recommended.'));
    }
  }

  if (browserFetcher) await browserFetcher.close();

  const report = {
    tool: 'ConfixHub PassiveVuln Scanner',
    version: '1.0',
    target: start.href,
    origin,
    scanType: 'Site Crawl',
    crawlerMode: args.browser ? 'Same-origin crawl (headless browser render)' : 'Same-origin link crawl',
    maxPages: args.maxPages,
    pagesScanned: pages.length,
    sensitiveProbe: args.sensitiveProbe,
    startedAt: startedAt.toISOString(),
    durationMs: Date.now() - startedAt.getTime(),
    findings,
  };

  const outPath = args.out || defaultOut(origin, startedAt);
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, buildHtmlReport(report));
  fs.writeFileSync(outPath.replace(/\.html$/, '.json'), JSON.stringify(report, null, 2));

  printSummary(report, outPath);
}

function defaultOut(origin, when) {
  const host = origin.replace(/^https?:\/\//, '').replace(/[^\w.-]/g, '_');
  const ts = when.toISOString().replace(/[:.]/g, '-').slice(0, 19);
  return path.join(__dirname, 'reports', `${host}-${ts}.html`);
}

function printSummary(r, outPath) {
  const by = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 };
  r.findings.forEach((f) => { by[f.severity]++; });
  console.log(`\nDone. ${r.findings.length} findings across ${r.pagesScanned} pages in ${(r.durationMs / 1000).toFixed(1)}s`);
  console.log(`  CRITICAL ${by.CRITICAL}  HIGH ${by.HIGH}  MEDIUM ${by.MEDIUM}  LOW ${by.LOW}  INFO ${by.INFO}`);
  console.log(`\nReport: ${outPath}\n`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

main().catch((e) => { console.error(e); process.exit(1); });

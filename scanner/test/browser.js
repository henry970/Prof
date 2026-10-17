'use strict';
// Headless-browser smoke test: serves a JS-rendered page whose only link to /settings is injected
// by client script after load, then scans with --browser and asserts the render path works (the
// injected link is followed) and the cookie/SRI/source-map checks fire. Skips cleanly if no browser
// is installed. Run with: npm run test:browser

const http = require('http');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const { findBrowser } = require('../browser');

if (!findBrowser()) {
  console.log('• no Chrome/Edge found — skipping browser smoke test');
  process.exit(0);
}

const PAGE = `<!doctype html><html><head>
<script src="https://cdn.example.com/lib.js"></script>
<script src="/_nuxt/app.js"></script>
</head><body><div id="app"></div>
<script>
  document.getElementById('app').innerHTML = '<a href="/settings">settings</a>';
  fetch('/api/v1/users');
</script></body></html>`;

const server = http.createServer((req, res) => {
  if (req.url === '/_nuxt/app.js') { res.setHeader('content-type', 'application/javascript'); return res.end('console.log(1)'); }
  if (req.url === '/_nuxt/app.js.map') { res.setHeader('content-type', 'application/json'); return res.end('{"version":3}'); }
  if (req.url.startsWith('/.well-known') || req.url === '/security.txt') { res.statusCode = 404; return res.end('nope'); }
  res.setHeader('content-type', 'text/html');
  res.setHeader('set-cookie', ['sid=abc; Path=/', 'theme=dark; Path=/']);
  res.setHeader('access-control-allow-origin', '*');
  res.end(PAGE);
});

server.listen(0, () => {
  const port = server.address().port;
  const out = path.join(__dirname, 'tmp-browser-report.html');
  const child = spawn('node', [path.join(__dirname, '..', 'scan.js'),
    '-t', `http://127.0.0.1:${port}/dashboard`, '--i-am-authorized', '--browser',
    '--max-pages', '3', '--delay', '0', '--out', out], { stdio: 'inherit' });

  child.on('exit', (code) => {
    try {
      assert.strictEqual(code, 0, 'scan should exit 0');
      const report = JSON.parse(fs.readFileSync(out.replace(/\.html$/, '.json'), 'utf8'));
      const titles = report.findings.map((f) => f.title).join('\n');
      assert.ok(report.crawlerMode.includes('headless'), 'report should note headless render');
      assert.strictEqual(report.pagesScanned, 2, 'should follow the JS-injected /settings link');
      assert.ok(/missing HttpOnly/.test(titles), 'HttpOnly check should fire');
      assert.ok(/Source map returned HTTP 200/.test(titles), 'source map check should fire');
      assert.ok(/SRI hash/.test(titles), 'SRI check should fire');
      console.log('\n✓ browser smoke test passed — rendered DOM scanned, JS-injected link followed');
      fs.rmSync(out); fs.rmSync(out.replace(/\.html$/, '.json'));
      server.close();
    } catch (e) {
      console.error('\n✗ browser smoke test failed'); console.error(e.message || e);
      server.close(); process.exit(1);
    }
  });
});

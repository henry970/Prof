'use strict';
// Smoke test: serve a deliberately weak page on localhost, scan it, assert the checks fire.
// No external network. Run with: npm test

const http = require('http');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const PAGE = `<!doctype html><html><head>
<script src="https://cdn.example.com/lib.js"></script>
<script src="/_nuxt/app.js"></script>
</head><body>
<a href="/dashboard">dash</a><a href="/settings">settings</a>
<script>fetch('/api/v1/users')</script>
</body></html>`;

const server = http.createServer((req, res) => {
  if (req.url === '/_nuxt/app.js') { res.setHeader('content-type', 'application/javascript'); return res.end('console.log(1)'); }
  if (req.url === '/_nuxt/app.js.map') { res.setHeader('content-type', 'application/json'); return res.end('{"version":3}'); }
  if (req.url.startsWith('/.well-known') || req.url === '/security.txt') { res.statusCode = 404; return res.end('nope'); }
  res.setHeader('content-type', 'text/html');
  res.setHeader('set-cookie', ['sid=abc; Path=/', 'theme=dark; Path=/']); // no HttpOnly, no SameSite
  res.setHeader('access-control-allow-origin', '*');
  res.end(PAGE);
});

server.listen(0, async () => {
  const port = server.address().port;
  const target = `http://127.0.0.1:${port}/dashboard`;
  const out = path.join(__dirname, 'tmp-report.html');
  try {
    // spawn (async) so this process's event loop keeps serving the child's requests
    await new Promise((resolve, reject) => {
      const child = spawn('node', [path.join(__dirname, '..', 'scan.js'),
        '--target', target, '--i-am-authorized', '--max-pages', '5', '--delay', '0', '--out', out],
        { stdio: 'inherit' });
      child.on('exit', (code) => (code === 0 ? resolve() : reject(new Error('scan exited ' + code))));
      child.on('error', reject);
    });

    const report = JSON.parse(fs.readFileSync(out.replace(/\.html$/, '.json'), 'utf8'));
    const titles = report.findings.map((f) => f.title).join('\n');

    assert.ok(/Source map returned HTTP 200/.test(titles), 'should flag exposed source map');
    assert.ok(/missing HttpOnly/.test(titles), 'should flag HttpOnly');
    assert.ok(/SameSite/.test(titles), 'should flag SameSite');
    assert.ok(/SRI hash/.test(titles), 'should flag missing SRI');
    assert.ok(/Access-Control-Allow-Origin/.test(titles), 'should flag CORS *');
    assert.ok(/Cache-Control/.test(titles), 'should flag sensitive page cache-control');
    assert.ok(/security\.txt was not found/.test(titles), 'should note missing security.txt');

    // Passive discipline: it must not have requested any cross-origin host
    assert.ok(!report.findings.some((f) => /cdn\.example\.com\/lib\.js\.map/.test(f.title)),
      'must not probe cross-origin source maps');

    console.log('\n✓ smoke test passed — all expected checks fired');
    fs.rmSync(out); fs.rmSync(out.replace(/\.html$/, '.json'));
    server.close();
  } catch (e) {
    console.error('\n✗ smoke test failed'); console.error(e.message || e);
    server.close(); process.exit(1);
  }
});

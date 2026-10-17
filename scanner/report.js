'use strict';
// Builds the HTML report in the ConfixHub house style (brand green #17704b).

const SEV_META = {
  CRITICAL: { color: '#a54432', band: '9.0 - 10', means: 'Severe and likely exploitable. Treat as top priority.' },
  HIGH: { color: '#c0392b', band: '7.0 - 8.9', means: 'Directly exploitable or a meaningful weakening of security. Fix promptly.' },
  MEDIUM: { color: '#b8860b', band: '4.0 - 6.9', means: 'Could contribute to a bigger issue if combined with other weaknesses. Plan a fix soon.' },
  LOW: { color: '#17704b', band: '0.1 - 3.9', means: 'Minor issue, unlikely to be exploitable alone. Fix during normal upkeep.' },
  INFO: { color: '#6b7280', band: '0', means: 'No direct risk on its own; useful context for a fuller assessment.' },
};
const ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];

function esc(v) {
  return String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function buildHtmlReport(r) {
  const counts = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 };
  r.findings.forEach((f) => { counts[f.severity]++; });
  const total = r.findings.length || 1;

  const overview = ORDER.map((s) => {
    const n = counts[s];
    const pctv = Math.round((n / total) * 100);
    return `<div class="ov" style="border-top:4px solid ${SEV_META[s].color}">
      <div class="ov-n">${n}</div><div class="ov-l">${s}</div><div class="ov-p">${pctv}%</div></div>`;
  }).join('');

  const legend = ORDER.map((s) => `<tr>
    <td><span class="pill" style="background:${SEV_META[s].color}">${s}</span></td>
    <td>${esc(SEV_META[s].means)}</td><td class="band">${SEV_META[s].band}</td></tr>`).join('');

  const sorted = r.findings.slice().sort((a, b) => b.score - a.score);
  const rows = sorted.map((f) => `<tr>
    <td class="sc" style="color:${SEV_META[f.severity].color}">${f.score.toFixed(1)}</td>
    <td><span class="pill sm" style="background:${SEV_META[f.severity].color}">${f.severity}</span></td>
    <td class="fnd"><div class="fnd-t">${esc(f.title)}</div>
      ${f.detail ? `<div class="fnd-d">${esc(f.detail)}</div>` : ''}
      <div class="fnd-u">${esc(f.url)}</div></td></tr>`).join('');

  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ConfixHub PassiveVuln Report — ${esc(r.origin)}</title>
<style>
  :root{--green:#17704b;--green2:#14734e;--cream:#fbfaf7;--ink:#17231f;--muted:#6b7280;--line:#e5e7eb}
  *{box-sizing:border-box;margin:0;padding:0}
  body{font-family:-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;color:var(--ink);background:#eef1ee;padding:24px;line-height:1.5}
  .sheet{max-width:960px;margin:0 auto;background:#fff;box-shadow:0 2px 20px rgba(23,35,31,.1);border-radius:12px;overflow:hidden}
  header{background:var(--green);color:var(--cream);padding:28px 32px}
  header h1{font-size:22px;font-weight:800;letter-spacing:.2px}
  header .sub{opacity:.85;font-size:13px;margin-top:4px}
  .body{padding:28px 32px}
  h2{font-size:15px;color:var(--green2);margin:26px 0 10px;border-bottom:2px solid var(--line);padding-bottom:6px}
  .ov-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:10px}
  .ov{background:var(--cream);border:1px solid var(--line);border-radius:10px;padding:14px;text-align:center}
  .ov-n{font-size:28px;font-weight:800}.ov-l{font-size:11px;color:var(--muted);letter-spacing:.5px}.ov-p{font-size:12px;font-weight:700;margin-top:2px}
  table{width:100%;border-collapse:collapse;font-size:13px}
  th{text-align:left;background:var(--green);color:#fff;padding:8px 10px;font-weight:600}
  td{padding:8px 10px;border-bottom:1px solid var(--line);vertical-align:top}
  .pill{color:#fff;font-size:10.5px;font-weight:700;padding:3px 9px;border-radius:999px;white-space:nowrap}
  .pill.sm{font-size:10px;padding:2px 7px}
  .band{color:var(--muted);white-space:nowrap}
  .sc{font-weight:800;white-space:nowrap}
  .fnd-t{font-weight:600}.fnd-d{color:#374151;font-size:12px;margin:3px 0}.fnd-u{color:var(--muted);font-size:11.5px;word-break:break-all}
  .kv{display:grid;grid-template-columns:180px 1fr;gap:6px 16px;font-size:13px}
  .kv dt{color:var(--muted)}.kv dd{font-weight:600;word-break:break-all}
  .note{background:#fff8e6;border:1px solid #f1d98a;border-radius:8px;padding:12px 14px;font-size:12.5px;color:#6b5a1e;margin-top:22px}
  footer{padding:16px 32px;background:var(--cream);color:var(--muted);font-size:11.5px;text-align:center;border-top:1px solid var(--line)}
  @media (max-width:640px){.ov-grid{grid-template-columns:repeat(2,1fr)}.kv{grid-template-columns:1fr}}
  @media print{body{background:#fff;padding:0}.sheet{box-shadow:none}}
</style></head>
<body><div class="sheet">
  <header>
    <h1>ConfixHub PassiveVuln Scanner</h1>
    <div class="sub">Passive security report · ${esc(r.origin)}</div>
  </header>
  <div class="body">
    <h2>Severity overview</h2>
    <div class="ov-grid">${overview}</div>

    <h2>What the severities mean</h2>
    <table><thead><tr><th>Severity</th><th>What it means</th><th>Indicative score</th></tr></thead>
      <tbody>${legend}</tbody></table>

    <h2>Project data</h2>
    <dl class="kv">
      <dt>Target URL</dt><dd>${esc(r.target)}</dd>
      <dt>Scan type</dt><dd>${esc(r.scanType)}</dd>
      <dt>Crawler mode</dt><dd>${esc(r.crawlerMode)}</dd>
      <dt>Max pages</dt><dd>${esc(r.maxPages)}</dd>
      <dt>Pages scanned</dt><dd>${esc(r.pagesScanned)}</dd>
      <dt>Sensitive-file probe</dt><dd>${r.sensitiveProbe ? 'Enabled' : 'Disabled'}</dd>
      <dt>Scan started</dt><dd>${new Date(r.startedAt).toLocaleString()}</dd>
      <dt>Scan duration</dt><dd>${(r.durationMs / 1000).toFixed(0)}s</dd>
    </dl>

    <h2>Findings (${r.findings.length})</h2>
    <table><thead><tr><th>Score</th><th>Severity</th><th>Finding</th></tr></thead>
      <tbody>${rows || '<tr><td colspan="3">No findings.</td></tr>'}</tbody></table>

    <div class="note">Findings are informational, not confirmed exploitable vulnerabilities. Verify each one manually.
      This scan is passive (GET only, same-origin). For authorized testing use only.</div>
  </div>
  <footer>ConfixHub PassiveVuln Scanner v${esc(r.version)} · generated ${new Date().toLocaleString()}</footer>
</div></body></html>`;
}

module.exports = { buildHtmlReport, SEV_META };

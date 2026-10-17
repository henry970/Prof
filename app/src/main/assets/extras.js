/* Android-only report sections: network, API timings, crashes, security checkup. perf.js is generated from the
   desktop CLI, so these are added here, after it renders. */
(function () {
  'use strict';
  if (!window.PerfReport) return;

  var C = { good: '#16a34a', warn: '#d97706', poor: '#dc2626', muted: '#64748b' };

  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function bytes(n) {
    if (n == null) return '–';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1024 / 1024).toFixed(2) + ' MB';
  }

  function pct(sorted, p) {
    if (!sorted.length) return null;
    return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
  }

  // /v1/users/123/orders/9f8e... -> /v1/users/:id/orders/:id so repeated calls group together
  function endpoint(path) {
    return path
      .split('/')
      .map(function (seg) {
        return /^\d+$/.test(seg) || /^[0-9a-f-]{16,}$/i.test(seg) ? ':id' : seg;
      })
      .join('/');
  }

  function apiRows(its) {
    var groups = {};
    its.forEach(function (it) {
      (it.api || []).forEach(function (c) {
        var key = c.method + ' ' + endpoint(c.path);
        (groups[key] = groups[key] || []).push(c);
      });
    });
    return Object.keys(groups)
      .map(function (key) {
        var calls = groups[key];
        var ms = calls.map(function (c) { return c.ms; }).sort(function (a, b) { return a - b; });
        return {
          key: key,
          count: calls.length,
          p50: pct(ms, 50),
          p90: pct(ms, 90),
          errors: calls.filter(function (c) { return c.status >= 400; }).length
        };
      })
      .sort(function (a, b) { return b.p90 - a.p90; });
  }

  function msColor(ms) {
    return ms == null ? C.muted : ms <= 300 ? C.good : ms <= 1000 ? C.warn : C.poor;
  }

  function networkPage(r) {
    var its = (r.iterations || []).filter(function (it) { return !it.error; });
    var net = r.network || {};

    // Data used
    var withNet = its.filter(function (it) { return it.network; });
    var rx = 0, tx = 0;
    withNet.forEach(function (it) { rx += it.network.rxBytes || 0; tx += it.network.txBytes || 0; });
    var dataHtml = withNet.length
      ? '<table class="tbl"><thead><tr><th>Downloaded</th><th>Uploaded</th><th>Average per run</th></tr></thead><tbody><tr>' +
        '<td>' + bytes(rx) + '</td><td>' + bytes(tx) + '</td><td>' + bytes(Math.round((rx + tx) / withNet.length)) + '</td></tr></tbody></table>'
      : '<p class="cap">Data usage wasn\'t available on this phone.</p>';

    // Servers
    var servers = net.servers || [];
    var serverHtml = servers.length
      ? '<table class="tbl"><thead><tr><th>Server</th><th>Port</th><th>Seen in runs</th><th>Connect time</th></tr></thead><tbody>' +
        servers.map(function (s) {
          return '<tr><td>' + esc(s.host || s.ip) + (s.host ? '<br><span class="cap">' + esc(s.ip) + '</span>' : '') + '</td>' +
            '<td>' + esc(s.port) + '</td><td>' + esc(s.runs) + ' of ' + its.length + '</td>' +
            '<td style="color:' + msColor(s.connectMs) + ';font-weight:700">' + (s.connectMs == null ? 'no answer' : s.connectMs + ' ms') + '</td></tr>';
        }).join('') +
        '</tbody></table>' +
        (net.serversTotal > servers.length ? '<p class="cap">Showing the ' + servers.length + ' most used of ' + net.serversTotal + ' servers.</p>' : '')
      : '<p class="cap">No open connections were seen. The app may not use the network on this journey, or its connections were too short to catch.</p>';

    // API calls
    var api = apiRows(its);
    var apiHtml = api.length
      ? '<table class="tbl"><thead><tr><th>Endpoint</th><th>Calls</th><th>Median</th><th>p90</th><th>Errors</th></tr></thead><tbody>' +
        api.map(function (a) {
          return '<tr><td>' + esc(a.key) + '</td><td>' + a.count + '</td>' +
            '<td style="color:' + msColor(a.p50) + ';font-weight:700">' + a.p50 + ' ms</td>' +
            '<td style="color:' + msColor(a.p90) + ';font-weight:700">' + a.p90 + ' ms</td>' +
            '<td' + (a.errors ? ' class="err"' : '') + '>' + a.errors + '</td></tr>';
        }).join('') +
        '</tbody></table><p class="cap">Green ≤ 300 ms, amber ≤ 1 s, red slower. Errors are HTTP 4xx/5xx responses.</p>'
      : '<p class="cap">No API timings were logged. HTTPS traffic is encrypted, so PerfPilot can\'t read another app\'s requests. ' +
        'If you build the app, log each call under the tag <b>' + esc(net.apiTag || 'PerfPilotApi') + '</b> as ' +
        '<b>GET /v1/items 200 184ms</b> (method, path, status, time) and they will appear here.</p>';

    // Per run
    var runRows = (r.iterations || []).map(function (it) {
      if (it.error) return '<tr><td>' + it.index + '</td><td colspan="5" class="err">' + esc(it.error) + '</td></tr>';
      var n = it.network || {};
      var crashes = (it.crashes || []).length;
      return '<tr><td>' + it.index + '</td><td>' + bytes(n.rxBytes) + '</td><td>' + bytes(n.txBytes) + '</td>' +
        '<td>' + (n.servers != null ? n.servers : '–') + '</td><td>' + (it.api || []).length + '</td>' +
        '<td' + (crashes ? ' class="err"' : '') + '>' + crashes + '</td></tr>';
    }).join('');

    return '<section class="page">' +
      '<div class="phead"><span>PerfPilot <b>Mobile</b></span><span>Network &amp; stability · ' + esc(r.app.package) + '</span></div>' +
      '<h2>Crashes &amp; freezes</h2>' + crashHtml(its) +
      '<h2>Data used</h2>' + dataHtml +
      '<h2>Servers the app connected to</h2>' +
      '<p class="cap">Connect time is how long this phone takes to reach each server (lower is better). Names come from reverse DNS and may show the hosting provider rather than the app\'s domain.</p>' +
      serverHtml +
      '<h2>API response times</h2>' + apiHtml +
      '<h2>Per run</h2><table class="tbl"><thead><tr><th>Run</th><th>Downloaded</th><th>Uploaded</th><th>Servers</th><th>API calls</th><th>Crashes</th></tr></thead><tbody>' + runRows + '</tbody></table>' +
      '<div class="pfoot"></div></section>';
  }

  function allCrashes(its) {
    var out = [];
    its.forEach(function (it) {
      (it.crashes || []).forEach(function (c) { out.push({ run: it.index, type: c.type, detail: c.detail }); });
    });
    return out;
  }

  function crashHtml(its) {
    var list = allCrashes(its);
    if (!list.length) return '<p class="cap" style="color:' + C.good + ';font-weight:700">No crashes or "app not responding" events during the test.</p>';
    return list.map(function (c) {
      return '<div class="finding" style="border-left-color:' + C.poor + '"><div class="f-title">Run ' + c.run + ': ' +
        (c.type === 'anr' ? 'App not responding (froze)' : 'App crashed') + '</div><div class="f-detail">' + esc(c.detail) + '</div></div>';
    }).join('');
  }

  var SEV = {
    high: { label: 'High', color: C.poor, order: 0 },
    medium: { label: 'Medium', color: C.warn, order: 1 },
    low: { label: 'Low', color: '#2563eb', order: 2 },
    info: { label: 'Info', color: C.muted, order: 3 }
  };

  function leakHtml(leaks) {
    if (!leaks.length) return '<p class="cap" style="color:' + C.good + ';font-weight:700">Nothing sensitive-looking was found in the app’s log lines during the test.</p>';
    return '<table class="tbl"><thead><tr><th>What</th><th>Log lines</th><th>Example (masked)</th></tr></thead><tbody>' +
      leaks.map(function (l) {
        return '<tr><td' + (l.severity === 'high' ? ' class="err"' : '') + '>' + esc(l.type) + '</td><td>' + esc(l.count) + '</td><td>' + esc(l.sample) + '</td></tr>';
      }).join('') +
      '</tbody></table><p class="cap">Pattern matches can include false positives; check the app’s code before treating one as a leak.</p>';
  }

  function securityPage(r) {
    var sec = r.security;
    if (!sec) return '';
    var findings = (sec.findings || []).slice().sort(function (a, b) {
      return (SEV[a.severity] || SEV.info).order - (SEV[b.severity] || SEV.info).order;
    });
    var counts = { high: 0, medium: 0, low: 0, info: 0 };
    findings.forEach(function (f) { counts[f.severity] = (counts[f.severity] || 0) + 1; });

    var tiles = ['high', 'medium', 'low', 'info'].map(function (k) {
      return '<div class="card"><div class="card-label">' + SEV[k].label + '</div>' +
        '<div class="card-val" style="color:' + (counts[k] ? SEV[k].color : C.muted) + '">' + counts[k] + '</div></div>';
    }).join('');

    var list = findings.length
      ? findings.map(function (f) {
          var s = SEV[f.severity] || SEV.info;
          return '<div class="finding" style="border-left-color:' + s.color + '"><div class="f-title">' +
            '<span style="color:' + s.color + '">' + s.label + '</span> · ' + esc(f.title) + '</div>' +
            '<div class="f-detail">' + esc(f.detail) + '</div></div>';
        }).join('')
      : '<p class="cap" style="color:' + C.good + ';font-weight:700">No configuration issues found.</p>';

    var perms = sec.permissions || [];
    var permHtml = perms.length
      ? '<table class="tbl"><thead><tr><th>Permission granted</th><th>System name</th></tr></thead><tbody>' +
        perms.map(function (p) {
          return '<tr><td' + (p.sensitive ? ' style="color:' + C.warn + ';font-weight:700"' : '') + '>' + esc(p.label) + '</td>' +
            '<td class="cap">' + esc(p.name.replace('android.permission.', '')) + '</td></tr>';
        }).join('') + '</tbody></table>'
      : '<p class="cap">No sensitive permissions are granted.</p>';

    var ex = sec.exported || {};
    function n(a) { return a ? a.length : 0; }
    var yes = function (v) { return v ? '<span class="err">Yes</span>' : 'No'; };

    return '<section class="page">' +
      '<div class="phead"><span>PerfPilot <b>Mobile</b></span><span>Security checkup · ' + esc(r.app.package) + '</span></div>' +
      (sec.error ? '<p class="cap err">' + esc(sec.error) + '</p>' : '') +
      '<p class="cap">A read-only look at how the app is configured on this phone, plus the connections seen during the test. ' +
      'It doesn’t attack or probe the app or its servers, so it can’t find bugs in the app’s code. Use it as a checklist, not a verdict.</p>' +
      '<div class="cards" style="grid-template-columns:repeat(4,1fr)">' + tiles + '</div>' +
      '<h2>Findings</h2>' + list +
      '<h2>Configuration</h2><table class="tbl kv"><tbody>' +
      '<tr><td>Debuggable</td><td>' + yes(sec.debuggable) + '</td></tr>' +
      '<tr><td>Plain HTTP allowed</td><td>' + yes(sec.cleartextAllowed) + '</td></tr>' +
      '<tr><td>HTTP seen in test</td><td>' + ((sec.cleartextServers || []).length ? '<span class="err">' + esc(sec.cleartextServers.join(', ')) + '</span>' : 'None') + '</td></tr>' +
      '<tr><td>Backup allowed</td><td>' + (sec.allowBackup ? 'Yes' : 'No') + '</td></tr>' +
      '<tr><td>Target / min API</td><td>' + esc(sec.targetSdk) + ' / ' + esc(sec.minSdk) + '</td></tr>' +
      '<tr><td>Open to other apps</td><td>' + n(ex.activities) + ' screens · ' + n(ex.services) + ' services · ' + n(ex.receivers) + ' receivers · ' + n(ex.providers) + ' providers</td></tr>' +
      '<tr><td>Screenshots blocked</td><td>' + (sec.screenshotsBlocked == null ? '–' : sec.screenshotsBlocked ? 'Yes' : 'No') + '</td></tr>' +
      '<tr><td>Installed from</td><td>' + esc(sec.installer || 'unknown') + '</td></tr>' +
      '<tr><td>Signed by</td><td>' + esc(sec.signer || '–') + (sec.signatureScheme ? ' · scheme v' + esc(sec.signatureScheme) : '') +
        (sec.signerSha256 ? '<br><span class="cap" style="word-break:break-all">SHA-256 ' + esc(sec.signerSha256) + '</span>' : '') + '</td></tr>' +
      '<tr><td>Powerful roles</td><td>' + ([sec.accessibilityService ? 'Accessibility service' : '', sec.deviceAdmin ? 'Device admin' : ''].filter(Boolean).join(', ') || 'None') + '</td></tr>' +
      '<tr><td>Analytics / ads SDKs</td><td>' + esc((sec.sdks || []).join(', ') || 'None found') + '</td></tr>' +
      '</tbody></table>' +
      '<h2>Sensitive data in the app’s logs</h2>' + leakHtml(sec.logLeaks || []) +
      '<h2>Sensitive permissions granted</h2>' + permHtml +
      '<div class="pfoot"></div></section>';
  }

  var render = window.PerfReport.render;
  window.PerfReport.render = function (raw) {
    var r = render(raw);
    try {
      document.body.insertAdjacentHTML('beforeend', networkPage(r));
      document.body.insertAdjacentHTML('beforeend', securityPage(r));
      // Put crashes at the top of the findings too: they matter more than any speed number.
      var crashes = allCrashes((r.iterations || []).filter(function (it) { return !it.error; }));
      var high = ((r.security && r.security.findings) || []).filter(function (f) { return f.severity === 'high'; });
      if (high.length) {
        var hs = document.querySelectorAll('h2');
        for (var j = 0; j < hs.length; j++) {
          if (hs[j].textContent === 'Findings') {
            hs[j].insertAdjacentHTML('afterend',
              '<div class="finding" style="border-left-color:' + C.poor + '"><div class="f-title">' + high.length +
              ' high-severity security issue' + (high.length === 1 ? '' : 's') + '</div><div class="f-detail">' +
              esc(high.map(function (f) { return f.title; }).join(' · ')) + '. See the Security checkup page.</div></div>');
            break;
          }
        }
      }
      if (crashes.length) {
        var heads = document.querySelectorAll('h2');
        for (var i = 0; i < heads.length; i++) {
          if (heads[i].textContent === 'Findings') {
            heads[i].insertAdjacentHTML('afterend',
              '<div class="finding" style="border-left-color:' + C.poor + '"><div class="f-title">The app crashed or froze ' +
              crashes.length + ' time' + (crashes.length === 1 ? '' : 's') + ' during the test</div>' +
              '<div class="f-detail">See "Crashes &amp; freezes" on the Network &amp; stability page.</div></div>');
            break;
          }
        }
      }
    } catch (e) {
      document.body.insertAdjacentHTML('beforeend', '<p class="cap">Extra report sections failed: ' + esc(e.message) + '</p>');
    }
    return r;
  };
})();

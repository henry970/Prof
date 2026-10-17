'use strict';
// Passive checks. Each takes what a normal browser GET already returned (status, headers, HTML,
// cookies) and reports observations. Nothing here sends crafted input, logs in, or tries to exploit
// anything — it only reads responses the server hands out to any visitor.

const SEV = {
  INFO: { min: 0, label: 'INFO' },
  LOW: { min: 0.1, label: 'LOW' },
  MEDIUM: { min: 4.0, label: 'MEDIUM' },
  HIGH: { min: 7.0, label: 'HIGH' },
  CRITICAL: { min: 9.0, label: 'CRITICAL' },
};

function severityFor(score) {
  if (score >= 9.0) return 'CRITICAL';
  if (score >= 7.0) return 'HIGH';
  if (score >= 4.0) return 'MEDIUM';
  if (score >= 0.1) return 'LOW';
  return 'INFO';
}

function finding(score, title, url, detail) {
  return { score, severity: severityFor(score), title, url, detail: detail || '' };
}

// --- HTML helpers (regex-based; good enough for passive link/script discovery) ---

function scriptSources(html) {
  const out = [];
  const re = /<script\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>/gi;
  let m;
  while ((m = re.exec(html))) {
    const integrity = /\bintegrity\s*=\s*["'][^"']+["']/i.test(m[0]);
    out.push({ src: m[1], integrity });
  }
  return out;
}

function pageLinks(html) {
  const out = [];
  const re = /\bhref\s*=\s*["']([^"'#]+)["']/gi;
  let m;
  while ((m = re.exec(html))) out.push(m[1]);
  return out;
}

// API-ish URLs mentioned in markup: helps map the attack surface, no requests are made to them.
function apiLikeRefs(html, origin) {
  const found = new Set();
  const re = /["'`](\/(?:api|v\d+|graphql|rest|rpc)\/[^"'`\s?]*)/gi;
  let m;
  while ((m = re.exec(html))) found.add(m[1].split('?')[0]);
  const abs = new RegExp('["\'`](https?://[^"\'`\\s]*/(?:api|v\\d+|graphql)/[^"\'`\\s?]*)', 'gi');
  while ((m = abs.exec(html))) {
    try {
      if (new URL(m[1]).origin === origin) found.add(new URL(m[1]).pathname);
    } catch (_) {}
  }
  return [...found];
}

// --- Cookie parsing (from Set-Cookie on responses we already received) ---

function parseSetCookies(setCookieList) {
  return (setCookieList || []).map((line) => {
    const parts = line.split(';').map((p) => p.trim());
    const [name] = parts[0].split('=');
    const attrs = parts.slice(1).map((p) => p.toLowerCase());
    const sameSite = (parts.slice(1).find((p) => /^samesite=/i.test(p)) || '').split('=')[1] || '';
    return {
      name,
      httpOnly: attrs.includes('httponly'),
      secure: attrs.includes('secure'),
      sameSite: sameSite.toLowerCase(),
    };
  });
}

// --- The checks ---

const SENSITIVE_PATH = /\b(dashboard|settings|account|profile|billing|payment|payout|transfer|admin|security|officer|personal|company|document|api-key|webhook)/i;

function runPageChecks(page) {
  const { url, origin, status, headers, html, cookies } = page;
  const out = [];
  if (status >= 400 || !html) return out;

  // Cross-origin scripts without SRI
  const xoNoSri = scriptSources(html).filter((s) => {
    try {
      return new URL(s.src, url).origin !== origin && !s.integrity;
    } catch (_) {
      return false;
    }
  });
  if (xoNoSri.length) {
    out.push(finding(2.5, `${xoNoSri.length} cross-origin script(s) loaded without an SRI hash.`, url,
      'Without an integrity hash a compromised third-party host could serve altered JavaScript. Add Subresource Integrity (integrity=) to these tags.'));
  }

  // Third-party script origins (info)
  const thirdParties = new Set();
  scriptSources(html).forEach((s) => {
    try {
      const o = new URL(s.src, url).origin;
      if (o !== origin) thirdParties.add(o);
    } catch (_) {}
  });
  if (thirdParties.size) {
    out.push(finding(0.0, `${thirdParties.size} third-party script origin(s) are loaded; review whether each is trusted.`, url,
      [...thirdParties].join(', ')));
  }

  // Cookies: HttpOnly
  const noHttpOnly = cookies.filter((c) => !c.httpOnly);
  if (noHttpOnly.length) {
    out.push(finding(2.5, `${noHttpOnly.length} cookie(s) missing HttpOnly (readable by JavaScript).`, url,
      `Cookies without HttpOnly can be read by injected scripts (XSS). Affected: ${noHttpOnly.map((c) => c.name).join(', ')}.`));
  }

  // Cookies: SameSite
  const weakSameSite = cookies.filter((c) => c.sameSite !== 'strict' && c.sameSite !== 'lax');
  if (weakSameSite.length) {
    out.push(finding(2.5, `${weakSameSite.length} cookie(s) without a restrictive SameSite attribute.`, url,
      `A missing or None SameSite lets the cookie ride cross-site requests (CSRF). Affected: ${weakSameSite.map((c) => c.name).join(', ')}.`));
  }

  // Cache-Control on sensitive pages
  const cache = (headers['cache-control'] || '').toLowerCase();
  if (SENSITIVE_PATH.test(url) && !/no-store|private|no-cache/.test(cache)) {
    out.push(finding(2.5, 'Potentially sensitive page lacks Cache-Control private or no-store.', url,
      'Shared caches or the browser may retain this page. Send Cache-Control: no-store (or private) on authenticated pages.'));
  }

  // CORS wildcard (info — only a risk with credentials, which this tool does not test)
  const acao = headers['access-control-allow-origin'];
  if (acao === '*') {
    out.push(finding(0.0, "Access-Control-Allow-Origin is set to '*' (any origin can read cross-origin responses).", url,
      'Fine for public assets; review that no authenticated or private response carries this header with credentials allowed.'));
  }

  // API-like endpoints referenced in markup
  const apis = apiLikeRefs(html, origin);
  if (apis.length) {
    out.push(finding(0.0, `${apis.length} same-origin API-like endpoint(s) were observed in page URLs, markup, or resource timing.`, url,
      apis.slice(0, 10).join(', ')));
  }

  // Authenticated context hint
  if (cookies.some((c) => /sess|auth|token|sid|jwt/i.test(c.name))) {
    out.push(finding(0.0, 'The scan may be running in an authenticated browser context.', url,
      'Session-like cookies were present. Findings reflect the signed-in view.'));
  }

  return out;
}

module.exports = {
  SEV,
  severityFor,
  finding,
  scriptSources,
  pageLinks,
  parseSetCookies,
  runPageChecks,
  SENSITIVE_PATH,
};

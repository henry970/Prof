# ConfixHub PassiveVuln Scanner

A **passive**, zero-dependency web security scanner. It crawls same-origin links from a start URL,
reads the responses any visitor already receives, and reports configuration weaknesses. It produces
an HTML + JSON report in the ConfixHub house style.

> **Authorized use only.** Scan sites you own or have written permission to test. Findings are
> informational, not confirmed exploitable vulnerabilities — verify each one manually.

## What "passive" means here

The scanner only sends plain `GET` requests and follows links the pages themselves expose. It does
**not** log in by itself, submit forms, inject payloads, fuzz, brute-force, or touch any cross-origin
host. It cannot and does not attempt exploitation. Because of that it finds *misconfigurations*, not
code bugs.

## Requirements

Node.js 18 or newer (uses the built-in `fetch`). No `npm install` needed.

## Usage

```bash
node scan.js --target https://app.example.com --i-am-authorized
```

The `--i-am-authorized` flag is required; without it the scanner refuses to run.

### Options

| Option | Meaning |
| --- | --- |
| `--target`, `-t <url>` | Start URL. Only same-origin pages are visited. |
| `--i-am-authorized` | Confirm you own or may test the target. Required. |
| `--max-pages <n>` | Max pages to crawl (default 50). |
| `--delay <ms>` | Delay between requests (default 400). Be polite. |
| `--cookie-env <VAR>` | Name of an env var holding a `Cookie` header, to scan your own signed-in app. The value is never printed or saved. |
| `--no-sensitive-probe` | Skip the source-map / `security.txt` `GET` probes. |
| `--browser` | Render pages in headless Chrome/Edge (see below). |
| `--out`, `-o <path>` | Where to write the report (default `reports/<host>-<timestamp>.html`). |

### Browser mode (for JavaScript-rendered apps)

A Nuxt/React/Vue app renders most of its content with JavaScript, so a plain `GET` sees an almost
empty page. Add `--browser` to load each page in a real headless browser and scan what actually
renders — including links and scripts added after load:

```bash
node scan.js -t https://app.example.com --i-am-authorized --browser
```

This drives an installed **Google Chrome or Microsoft Edge** over the DevTools Protocol. No
`npm install` is needed. It is still passive — it only navigates (GET) to same-origin pages and reads
the rendered DOM and cookies. If no browser is found it tells you and exits.

### Scanning your own authenticated app

Put a session cookie in an environment variable (so it never appears in your shell history or the
report), then point the scanner at it:

```bash
# PowerShell
$env:FOREN_COOKIE = "session=...; csrf=..."
node scan.js -t https://app.example.com/dashboard --i-am-authorized --cookie-env FOREN_COOKIE
```

Use a disposable test account where possible.

## What it checks

| Check | Severity | Why it matters |
| --- | --- | --- |
| Source map served (`.js.map` → 200) | Medium (5.5) | Production source maps expose original source code. Probed only for scripts the page actually loads. |
| Cross-origin script without SRI | Low (2.5) | A compromised CDN could serve altered JavaScript. |
| Cookie missing `HttpOnly` | Low (2.5) | Readable by injected scripts (XSS). |
| Cookie without restrictive `SameSite` | Low (2.5) | Can ride cross-site requests (CSRF). |
| Sensitive page lacks `Cache-Control` private/no-store | Low (2.5) | Caches may retain authenticated pages. |
| `Access-Control-Allow-Origin: *` | Info | Fine for public assets; review for credentialed responses. |
| Same-origin API-like endpoints referenced | Info | Maps the attack surface. No requests are sent to them. |
| Third-party script origins loaded | Info | Review that each is trusted. |
| Authenticated context detected | Info | Session-like cookies present. |
| `security.txt` missing | Info | Optional, recommended way for researchers to reach you. |

## Severity bands

| Severity | Score | Meaning |
| --- | --- | --- |
| CRITICAL | 9.0–10 | Severe and likely exploitable. Top priority. |
| HIGH | 7.0–8.9 | Directly exploitable or a meaningful weakening. Fix promptly. |
| MEDIUM | 4.0–6.9 | Could compound with other weaknesses. Fix soon. |
| LOW | 0.1–3.9 | Minor, unlikely exploitable alone. Fix during upkeep. |
| INFO | 0 | Context, no direct risk. |

## Test

```bash
npm test
```

Spins up a deliberately weak page on localhost, scans it, and asserts every check fires (and that no
cross-origin host is probed).

## Output

Two files per scan in `reports/` (git-ignored): a styled `.html` report and a `.json` with the raw
findings for further processing.

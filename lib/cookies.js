'use strict';
// One cookie jar for everything the desktop app does with a site: page fetches, length probes,
// the video relay (proxy) and the headless browser. A session cookie a site sets while its page
// is read is then sent when its video is streamed — the site sees one consistent visitor.
// Consent / "are you 18?" cookies (cookieFor) are added for sites that haven't set their own.

const jar = new Map(); // domain -> Map(name -> { value, expires })

/** Cookies that pre-accept a site's consent or "are you 18?" page. */
function cookieFor(url) {
  let host = '';
  try { host = new URL(url).hostname; } catch { /* bad url */ }
  if (/(^|\.)pornhub\.com$/.test(host)) return 'accessAgeDisclaimerPH=1; accessAgeDisclaimerUK=1; age_verified=1; platform=pc';
  if (/(^|\.)spankbang\.com$/.test(host)) return 'age_pass=1';
  if (/(^|\.)xhamster\.com$/.test(host)) return 'x_content_preference_index=straight';
  return 'CONSENT=YES+1; SOCS=CAI';
}

function set(domain, name, value, expires) {
  domain = domain.replace(/^\./, '').toLowerCase();
  if (!jar.has(domain)) jar.set(domain, new Map());
  if (expires != null && expires <= Date.now()) jar.get(domain).delete(name);
  else jar.get(domain).set(name, { value, expires });
}

/** Remember the Set-Cookie headers of a response to `url`. */
function storeFrom(url, res) {
  let host;
  try { host = new URL(url).hostname; } catch { return; }
  const list = res && res.headers && typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
  for (const line of list) {
    const [pair, ...attrs] = line.split(';');
    const eq = pair.indexOf('=');
    if (eq <= 0) continue;
    const name = pair.slice(0, eq).trim();
    const value = pair.slice(eq + 1).trim();
    let domain = host;
    let expires = null;
    for (const a of attrs) {
      const [k, v = ''] = a.split('=').map((x) => x.trim());
      if (/^domain$/i.test(k) && v) domain = v;
      else if (/^max-age$/i.test(k)) expires = Date.now() + (+v || 0) * 1000;
      else if (/^expires$/i.test(k) && expires == null) { const t = Date.parse(v); if (!Number.isNaN(t)) expires = t; }
    }
    // A site may only set cookies for itself or a parent domain of itself.
    const d = domain.replace(/^\./, '').toLowerCase();
    if (host === d || host.endsWith('.' + d)) set(d, name, value, expires);
  }
}

/** "Cookie:" header for a request to `url`: stored cookies plus consent defaults. */
function header(url) {
  let host;
  try { host = new URL(url).hostname.toLowerCase(); } catch { return ''; }
  const out = new Map();
  for (const [domain, cookies] of jar) {
    if (host !== domain && !host.endsWith('.' + domain)) continue;
    for (const [name, c] of cookies) if (c.expires == null || c.expires > Date.now()) out.set(name, c.value);
  }
  for (const kv of cookieFor(url).split('; ')) { const [k, v] = kv.split('='); if (!out.has(k)) out.set(k, v); }
  return [...out].map(([k, v]) => `${k}=${v}`).join('; ');
}

/** Cookies a headless-browser page ended up with (Playwright format), into the jar. */
function importBrowser(list) {
  for (const c of list || []) if (c && c.name && c.domain) set(c.domain, c.name, c.value, c.expires > 0 ? c.expires * 1000 : null);
}

/** Jar contents in Playwright format, to start a headless-browser page with. */
function exportBrowser() {
  const out = [];
  for (const [domain, cookies] of jar) for (const [name, c] of cookies) out.push({ name, value: c.value, domain: '.' + domain, path: '/' });
  return out;
}

module.exports = { cookieFor, storeFrom, header, importBrowser, exportBrowser, _jar: jar };

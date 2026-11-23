'use strict';
// Web search by reading public search-result pages (no API keys).
// Each engine returns "hits": { url, title, snippet, engine, rank, media? }.

const { UA, decodeEntities, stripTags, domainOf, parseDuration } = require('./util');

const FETCH_TIMEOUT = 9000;

const MAX_HTML = 4 * 1024 * 1024;

/** Read a response body as text, but never more than `limit` bytes (endless/huge pages). */
async function readCapped(res, limit = MAX_HTML) {
  if (!res.body) return '';
  const reader = res.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (total < limit) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      total += value.byteLength;
    }
  } finally {
    reader.cancel().catch(() => {});
  }
  return Buffer.concat(chunks.map((c) => Buffer.from(c))).subarray(0, limit).toString('utf8');
}

async function fetchPlain(url) {
  try {
    const res = await fetch(url, {
      headers: {
        'User-Agent': UA,
        'Accept': 'text/html,application/xhtml+xml',
        'Accept-Language': 'en-US,en;q=0.9',
        // Skip cookie-consent interstitials (YouTube / Google properties).
        'Cookie': 'CONSENT=YES+1; SOCS=CAI',
      },
      redirect: 'follow',
      signal: AbortSignal.timeout(FETCH_TIMEOUT),
    });
    // Note: DuckDuckGo answers bots with "202 Accepted" + a challenge page, so only 200 counts.
    if (res.status === 200) return await readCapped(res);
    res.body && res.body.cancel().catch(() => {});
  } catch { /* blocked / timed out */ }
  return '';
}

/**
 * Fetch a search page and parse it. If the plain request was blocked, challenged or
 * yields no results (JS-only page), load it in headless Chromium and parse again.
 */
async function fetchHits(url, browserFetch, parse) {
  let hits = [];
  const html = await fetchPlain(url);
  if (html) { try { hits = parse(html); } catch { hits = []; } }
  if (!hits.length && browserFetch) {
    try {
      const rendered = await browserFetch(url);
      if (rendered) hits = parse(rendered);
    } catch { /* give up on this engine */ }
  }
  return hits;
}

const ENGINE_DOMAINS = /(^|\.)(duckduckgo\.com|bing\.com|bing\.net|microsoft\.com|live\.com|msn\.cn|google\.[a-z.]+|gstatic\.com|googleapis\.com|w3\.org|schema\.org|ytimg\.com)$/i;
const JUNK_PATH = /\.(js|css|png|jpe?g|gif|svg|ico|webp|woff2?|pdf|xml|json)(\?|$)/i;

function cleanHitUrl(raw) {
  let url = decodeEntities(raw).trim();
  if (url.startsWith('//')) url = 'https:' + url;
  try {
    const u = new URL(url);
    // DuckDuckGo redirect: //duckduckgo.com/l/?uddg=<target>
    if (/duckduckgo\.com$/.test(u.hostname) && u.pathname === '/l/') {
      const t = u.searchParams.get('uddg');
      if (t) return cleanHitUrl(t);
    }
    // Google redirect: google.<tld>/url?q=<target> (or &url=)
    if (/(^|\.)google\.[a-z.]+$/.test(u.hostname) && u.pathname === '/url') {
      const t = u.searchParams.get('q') || u.searchParams.get('url');
      return t && /^https?:\/\//.test(t) ? cleanHitUrl(t) : null;
    }
    // Bing redirect: bing.com/ck/a?...&u=a1<base64url>
    if (/bing\.com$/.test(u.hostname) && u.pathname.startsWith('/ck/')) {
      const enc = u.searchParams.get('u');
      if (enc && enc.startsWith('a1')) {
        const decoded = Buffer.from(enc.slice(2).replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');
        if (/^https?:\/\//.test(decoded)) return cleanHitUrl(decoded);
      }
      return null;
    }
    if (!/^https?:$/.test(u.protocol)) return null;
    if (ENGINE_DOMAINS.test(u.hostname) || JUNK_PATH.test(u.pathname)) return null;
    if (/youtube\.com$/.test(u.hostname) && /^\/(results|feed)/.test(u.pathname)) return null;
    u.hash = '';
    return u.toString();
  } catch {
    return null;
  }
}

// ---------- DuckDuckGo (HTML version) ----------
async function duckduckgo(query, page, browserFetch) {
  const url = `https://html.duckduckgo.com/html/?q=${encodeURIComponent(query + ' video')}` + (page > 0 ? `&s=${page * 30}&dc=${page * 30 + 1}` : '');
  return fetchHits(url, browserFetch, (html) => {
  const hits = [];
  const blocks = html.split(/<div[^>]+class="[^"]*\bresult\b[^"]*"/i).slice(1);
  for (const b of blocks) {
    if (/result--ad/.test(b.slice(0, 200))) continue;
    const a = b.match(/<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/i)
      || b.match(/<a[^>]+href="([^"]+)"[^>]+class="[^"]*result__a[^"]*"[^>]*>([\s\S]*?)<\/a>/i);
    if (!a) continue;
    const target = cleanHitUrl(a[1]);
    if (!target) continue;
    const sn = b.match(/class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)<\/a>/i);
    hits.push({ url: target, title: stripTags(a[2]), snippet: sn ? stripTags(sn[1]) : '' });
  }
  return hits;
  });
}

// ---------- Bing web results ----------
async function bing(query, page, browserFetch) {
  const url = `https://www.bing.com/search?q=${encodeURIComponent(query + ' video')}&count=30&first=${page * 30 + 1}`;
  return fetchHits(url, browserFetch, (html) => {
  const hits = [];
  const blocks = html.split(/<li[^>]+class="b_algo"/i).slice(1);
  for (const b of blocks) {
    const a = b.match(/<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/i);
    if (!a) continue;
    const target = cleanHitUrl(a[1]);
    if (!target) continue;
    const sn = b.match(/<p[^>]*>([\s\S]*?)<\/p>/i);
    hits.push({ url: target, title: stripTags(a[2]), snippet: sn ? stripTags(sn[1]) : '' });
  }
  return hits;
  });
}

// ---------- Bing Videos (indexes video pages from all over the web) ----------
async function bingVideos(query, page, browserFetch) {
  const url = `https://www.bing.com/videos/search?q=${encodeURIComponent(query)}&first=${page * 35 + 1}`;
  return fetchHits(url, browserFetch, (raw) => {
  const html = decodeEntities(raw).replace(/\\u002f/gi, '/').replace(/\\\//g, '/');
  const hits = [];
  const seen = new Set();
  // Video tiles carry JSON like {"murl":"<page url>","vt":"<title>","du":"4:13",...}
  const re = /"murl"\s*:\s*"([^"]+)"/g;
  let m;
  while ((m = re.exec(html))) {
    const target = cleanHitUrl(m[1]);
    if (!target || seen.has(target)) continue;
    seen.add(target);
    const ctx = html.slice(Math.max(0, m.index - 1500), m.index + 1500);
    const vt = ctx.match(/"vt"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"/);
    const du = ctx.match(/"du"\s*:\s*"([\d:]+)"/);
    hits.push({ url: target, title: vt ? stripTags(vt[1]) : '', snippet: '', duration: du ? clockToSecs(du[1]) : null });
  }
  return hits;
  });
}

// ---------- YouTube results page ----------
async function youtube(query, page, browserFetch) {
  if (page > 0) return []; // continuation needs their internal API; other engines cover deeper pages
  const url = `https://www.youtube.com/results?search_query=${encodeURIComponent(query)}`;
  return fetchHits(url, browserFetch, (html) => {
  const hits = [];
  const seen = new Set();
  for (const chunk of html.split('"videoRenderer":{').slice(1)) {
    const id = (chunk.match(/^"videoId":"([\w-]{11})"/) || [])[1];
    if (!id || seen.has(id)) continue;
    seen.add(id);
    const body = chunk.slice(0, 6000);
    const title = (body.match(/"title":\{"runs":\[\{"text":"([^"\\]*(?:\\.[^"\\]*)*)"/) || [])[1] || '';
    const len = (body.match(/"lengthText":\{[\s\S]{0,300}?"simpleText":"([\d:]+)"/) || [])[1];
    const desc = (body.match(/"(?:descriptionSnippet|detailedMetadataSnippets)"[\s\S]{0,200}?"text":"([^"\\]*(?:\\.[^"\\]*)*)"/) || [])[1] || '';
    const channel = (body.match(/"ownerText":\{"runs":\[\{"text":"([^"\\]*(?:\\.[^"\\]*)*)"/) || [])[1] || '';
    const pageUrl = `https://www.youtube.com/watch?v=${id}`;
    hits.push({
      url: pageUrl,
      title: unescapeJson(title),
      snippet: unescapeJson(desc),
      duration: len ? clockToSecs(len) : null,
      // Already know everything needed to play it — no inspection required.
      media: {
        kind: 'embed', provider: 'youtube', id, src: pageUrl,
        title: unescapeJson(title), description: unescapeJson(desc),
        thumbnail: `https://i.ytimg.com/vi/${id}/hqdefault.jpg`,
        duration: len ? clockToSecs(len) : null, siteName: channel ? `YouTube · ${unescapeJson(channel)}` : 'YouTube',
        via: 'search-page',
      },
    });
  }
  return hits;
  });
}

function unescapeJson(s) {
  try { return JSON.parse(`"${s}"`); } catch { return s; }
}

function clockToSecs(t) {
  const parts = String(t).split(':').map(Number);
  if (parts.some(isNaN)) return parseDuration(t);
  return parts.reduce((acc, n) => acc * 60 + n, 0) || null;
}

// ---------- Google (Videos tab: video pages from all over the web) ----------
// Google only serves results to browsers that run JavaScript, so a plain request gets
// an "enable JavaScript" page, parses to nothing, and fetchHits retries in the browser.
const GOOGLE_OWN = /(^|\.)(google\.[a-z.]+|gstatic\.com|googleusercontent\.com|googleadservices\.com|googlesyndication\.com|doubleclick\.net|blogger\.com\/(?:comment))$/i;

function parseGoogle(html) {
  // Bot check / captcha page: nothing to read.
  if (/\/sorry\/index|unusual traffic from your computer|id="captcha-form"/i.test(html)) return [];
  const hits = [];
  const seen = new Set();
  // Read results by structure, not class names (which Google changes constantly):
  // every link that leaves Google and carries a heading or readable label.
  const re = /<a\b([^>]{0,3000})>([\s\S]{0,6000}?)<\/a>/gi;
  let m;
  while ((m = re.exec(html)) && hits.length < 40) {
    const hrefM = m[1].match(/\bhref\s*=\s*"([^"]+)"/i);
    if (!hrefM) continue;
    let href = decodeEntities(hrefM[1]);
    if (href.startsWith('/url?')) href = 'https://www.google.com' + href;
    if (!/^https?:\/\//i.test(href)) continue;
    let host;
    try { host = new URL(href).hostname; } catch { continue; }
    const target = GOOGLE_OWN.test(host) && !href.includes('/url?') ? null : cleanHitUrl(href);
    if (!target || seen.has(target)) continue;
    const inner = m[2];
    const heading = inner.match(/<h3\b[^>]*>([\s\S]*?)<\/h3>/i) || inner.match(/role="heading"[^>]*>([\s\S]{0,600}?)<\/div>/i);
    const aria = m[1].match(/\baria-label\s*=\s*"([^"]{5,300})"/i);
    let title = heading ? stripTags(heading[1]) : aria ? decodeEntities(aria[1]) : '';
    if (!title) { const t = stripTags(inner); if (t.length >= 12 && t.length <= 200) title = t; }
    if (!title || title.length < 5) continue;
    const dur = stripTags(inner).match(/\b(\d{1,2}:\d{2}(?::\d{2})?)\b/);
    seen.add(target);
    hits.push({ url: target, title, snippet: '', duration: dur ? clockToSecs(dur[1]) : null });
  }
  return hits;
}

async function google(query, page, browserFetch) {
  const url = `https://www.google.com/search?q=${encodeURIComponent(query)}&tbm=vid&hl=en&num=20` + (page > 0 ? `&start=${page * 20}` : '');
  return fetchHits(url, browserFetch, parseGoogle);
}

const ENGINES = { duckduckgo, bing, bingVideos, youtube, google };

/**
 * Query every engine in parallel and merge the hits round-robin so no single
 * engine (or site) dominates the inspection order.
 */
async function searchWeb(query, { page = 0, browserFetch, engines = Object.keys(ENGINES), onEngine } = {}) {
  const lists = await Promise.all(engines.map(async (name) => {
    const t0 = Date.now();
    try {
      const hits = (await ENGINES[name](query, page, browserFetch)).map((h, i) => ({ ...h, engine: name, rank: i }));
      onEngine && onEngine(name, hits.length, Date.now() - t0);
      return hits;
    } catch (e) {
      onEngine && onEngine(name, 0, Date.now() - t0, e);
      return [];
    }
  }));
  const merged = [];
  const seen = new Set();
  const max = Math.max(0, ...lists.map((l) => l.length));
  for (let i = 0; i < max; i++) {
    for (const list of lists) {
      const h = list[i];
      if (!h) continue;
      const key = normalizeKey(h.url);
      if (seen.has(key)) continue;
      seen.add(key);
      merged.push(h);
    }
  }
  return merged;
}

function normalizeKey(url) {
  try {
    const u = new URL(url);
    const host = u.hostname.replace(/^(www|m)\./, '');
    if (/youtube\.com$/.test(host) && u.searchParams.get('v')) return 'yt:' + u.searchParams.get('v');
    return host + u.pathname.replace(/\/$/, '') + (u.search || '');
  } catch { return url; }
}

module.exports = { readCapped, parseGoogle, searchWeb, cleanHitUrl, normalizeKey, ENGINES, domainOf };

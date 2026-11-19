'use strict';
// Web search by reading public search-result pages (no API keys).
// Each engine returns "hits": { url, title, snippet, engine, rank, media? }.

const { UA, decodeEntities, stripTags, domainOf, parseDuration } = require('./util');

const FETCH_TIMEOUT = 9000;

async function fetchHtml(url, browserFetch) {
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
    if (res.ok) {
      const html = await res.text();
      if (html.length > 2000) return html;
    }
  } catch { /* fall through to the real browser */ }
  // Blocked, captcha'd or JS-only: load it in headless Chromium instead.
  if (browserFetch) {
    try { return await browserFetch(url); } catch { /* give up on this engine */ }
  }
  return '';
}

const ENGINE_DOMAINS = /(^|\.)(duckduckgo\.com|bing\.com|bing\.net|microsoft\.com|live\.com|msn\.cn|google\.[a-z.]+|gstatic\.com|googleapis\.com|w3\.org|schema\.org|ytimg\.com|youtube\.com\/(results|feed))$/i;
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
    u.hash = '';
    return u.toString();
  } catch {
    return null;
  }
}

// ---------- DuckDuckGo (HTML version) ----------
async function duckduckgo(query, page, browserFetch) {
  const url = `https://html.duckduckgo.com/html/?q=${encodeURIComponent(query + ' video')}` + (page > 0 ? `&s=${page * 30}&dc=${page * 30 + 1}` : '');
  const html = await fetchHtml(url, browserFetch);
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
}

// ---------- Bing web results ----------
async function bing(query, page, browserFetch) {
  const url = `https://www.bing.com/search?q=${encodeURIComponent(query + ' video')}&count=30&first=${page * 30 + 1}`;
  const html = await fetchHtml(url, browserFetch);
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
}

// ---------- Bing Videos (indexes video pages from all over the web) ----------
async function bingVideos(query, page, browserFetch) {
  const url = `https://www.bing.com/videos/search?q=${encodeURIComponent(query)}&first=${page * 35 + 1}`;
  const raw = await fetchHtml(url, browserFetch);
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
    const vt = ctx.match(/"vt"\s*:\s*"((?:[^"\\]|\\.)*)"/);
    const du = ctx.match(/"du"\s*:\s*"([\d:]+)"/);
    hits.push({ url: target, title: vt ? stripTags(vt[1]) : '', snippet: '', duration: du ? clockToSecs(du[1]) : null });
  }
  return hits;
}

// ---------- YouTube results page ----------
async function youtube(query, page, browserFetch) {
  if (page > 0) return []; // continuation needs their internal API; other engines cover deeper pages
  const url = `https://www.youtube.com/results?search_query=${encodeURIComponent(query)}`;
  const html = await fetchHtml(url, browserFetch);
  const hits = [];
  const seen = new Set();
  for (const chunk of html.split('"videoRenderer":{').slice(1)) {
    const id = (chunk.match(/^"videoId":"([\w-]{11})"/) || [])[1];
    if (!id || seen.has(id)) continue;
    seen.add(id);
    const body = chunk.slice(0, 6000);
    const title = (body.match(/"title":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"/) || [])[1] || '';
    const len = (body.match(/"lengthText":\{[\s\S]{0,300}?"simpleText":"([\d:]+)"/) || [])[1];
    const desc = (body.match(/"(?:descriptionSnippet|detailedMetadataSnippets)"[\s\S]{0,200}?"text":"((?:[^"\\]|\\.)*)"/) || [])[1] || '';
    const channel = (body.match(/"ownerText":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"/) || [])[1] || '';
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
}

function unescapeJson(s) {
  try { return JSON.parse(`"${s}"`); } catch { return s; }
}

function clockToSecs(t) {
  const parts = String(t).split(':').map(Number);
  if (parts.some(isNaN)) return parseDuration(t);
  return parts.reduce((acc, n) => acc * 60 + n, 0) || null;
}

const ENGINES = { duckduckgo, bing, bingVideos, youtube };

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

module.exports = { searchWeb, cleanHitUrl, normalizeKey, ENGINES, domainOf };

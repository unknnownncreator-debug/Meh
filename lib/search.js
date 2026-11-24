'use strict';
// Web search by reading public search-result pages (no API keys).
// Each engine returns "hits": { url, title, snippet, engine, rank, media? }.

const { UA, decodeEntities, stripTags, domainOf, parseDuration, isBlockedSite, isAdultSite, depictsMinors, AGE_NEGATIVE_TAGS, unwantedContent, UNWANTED_NEGATIVE_TAGS } = require('./util');

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

// ---------- RedGIFs (opt-in, 18+) ----------
// RedGIFs' website is a JavaScript app that loads everything from its data service with a
// guest token every visitor gets automatically (no account or key; documented by RedGIFs at
// github.com/Redgifs/api/wiki/Temporary-tokens). The token is tied to the caller's address and
// browser identity, so it is fetched and used by the same client. Results are the clips'
// watch pages, which go through normal page inspection.
let redgifsToken = null; // { token, at }

async function getRedgifsToken(fresh) {
  if (!fresh && redgifsToken && Date.now() - redgifsToken.at < 12 * 3600 * 1000) return redgifsToken.token;
  try {
    const res = await fetch('https://api.redgifs.com/v2/auth/temporary', { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(8000) });
    if (!res.ok) return null;
    const { token } = await res.json();
    redgifsToken = token ? { token, at: Date.now() } : null;
    return token || null;
  } catch { return null; }
}

function parseRedgifs(data) {
  const hits = [];
  for (const g of (data && data.gifs) || []) {
    if (!g || !/^[a-z0-9]+$/i.test(g.id || '') || !(g.urls && g.urls.hd)) continue;
    if (g.type === 2) continue; // 2 = still image
    const tags = Array.isArray(g.tags) ? g.tags.filter((t) => typeof t === 'string') : [];
    hits.push({
      url: `https://www.redgifs.com/watch/${g.id.toLowerCase()}`,
      title: stripTags(g.description || '').slice(0, 200) || tags.slice(0, 6).join(' '),
      snippet: tags.join(', '),
      duration: g.duration > 0 ? g.duration : null,
    });
  }
  return hits;
}

async function redgifs(query, page) {
  if (depictsMinors(query)) return []; // never search adult sites for that
  const url = `https://api.redgifs.com/v2/gifs/search?search_text=${encodeURIComponent(query)}&order=trending&count=40&page=${page + 1}`;
  for (let attempt = 0; attempt < 2; attempt++) {
    const token = await getRedgifsToken(attempt > 0); // second try: the token expired or was refused
    if (!token) return [];
    try {
      const res = await fetch(url, { headers: { 'User-Agent': UA, 'Authorization': `Bearer ${token}` }, signal: AbortSignal.timeout(9000) });
      if (res.status === 401 || res.status === 403) continue;
      if (!res.ok) return [];
      return parseRedgifs(await res.json()).filter((h) => !depictsMinors(`${h.title} ${h.snippet}`));
    } catch { return []; }
  }
  return [];
}

// ---------- rule34 sites (opt-in, 18+) ----------
// Every search excludes content depicting minors: such queries aren't sent at all, booru
// searches carry minus-tags for it, and results whose tags/titles mark it are dropped.

/** rule34video.com: video-only site; search page lists each video's page, title and length. */
function parseRule34video(html) {
  const hits = [];
  for (const b of html.split(/<div[^>]+class="item thumb\b/i).slice(1)) {
    const a = b.match(/href="(https:\/\/rule34video\.com\/video\/\d+\/[^"]+\/)"/i);
    if (!a) continue;
    const title = stripTags((b.match(/class="thumb_title"[^>]*>([\s\S]*?)<\/div>/i) || [])[1] || '') ||
      decodeEntities((b.match(/<img[^>]+alt="([^"]*)"/i) || [])[1] || '');
    const time = (b.match(/class="time"[^>]*>\s*([\d:]+)\s*</i) || [])[1];
    hits.push({ url: a[1], title, snippet: '', duration: time ? clockToSecs(time) : null });
  }
  return hits;
}

async function rule34video(query, page, browserFetch) {
  if (page > 0 || depictsMinors(query)) return [];
  const slug = query.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  if (!slug) return [];
  return (await fetchHits(`https://rule34video.com/search/${slug}/`, browserFetch, parseRule34video))
    .filter((h) => !depictsMinors(`${h.title} ${h.url}`));
}

/** Words of a free-text query as booru tags ("big sword" → "big sword"), plus the minors minus-tags. */
function booruTags(query, extra) {
  const words = query.toLowerCase().split(/\s+/).map((w) => w.replace(/[^a-z0-9_'.:-]/g, '')).filter(Boolean).slice(0, 5);
  return [...extra, ...words, ...AGE_NEGATIVE_TAGS.map((t) => `-${t}`), ...UNWANTED_NEGATIVE_TAGS.map((t) => `-${t}`)].join(' ');
}

/** rule34.paheal.net: each result says its file type and tags, so non-videos are skipped up front. */
function parsePaheal(html) {
  const hits = [];
  for (const m of html.matchAll(/<div[^>]+class=['"][^'"]*shm-thumb[^'"]*['"][^>]*>[\s\S]{0,3000}?<\/div>/gi)) {
    const div = m[0];
    const mime = (div.match(/data-mime=['"]([^'"]+)/i) || [])[1] || '';
    if (!/^video\//i.test(mime)) continue;
    const tags = decodeEntities((div.match(/data-tags=['"]([^'"]*)/i) || [])[1] || '');
    if (depictsMinors(tags)) continue;
    const href = (div.match(/href=['"](\/post\/view\/\d+)/i) || [])[1];
    if (!href) continue;
    hits.push({ url: `https://rule34.paheal.net${href}`, title: tags.replace(/_/g, ' ').slice(0, 200), snippet: tags, duration: null });
  }
  return hits;
}

async function paheal(query, page, browserFetch) {
  if (depictsMinors(query)) return [];
  const tags = booruTags(query, ['animated']).replace(/ /g, '%20');
  return fetchHits(`https://rule34.paheal.net/post/list/${tags}/${page + 1}`, browserFetch, parsePaheal);
}

/** rule34.xxx: behind Cloudflare, so its pages are read through the hidden browser. */
function parseRule34xxx(html) {
  const hits = [];
  for (const m of html.matchAll(/<span[^>]+class=["']thumb["'][^>]*>([\s\S]{0,2000}?)<\/span>/gi)) {
    const id = (m[1].match(/[?&;]id=(\d+)/) || [])[1];
    if (!id) continue;
    const tags = decodeEntities((m[1].match(/\b(?:title|alt)=["']([^"']*)["']/i) || [])[1] || '').trim();
    if (depictsMinors(tags)) continue;
    hits.push({ url: `https://rule34.xxx/index.php?page=post&s=view&id=${id}`, title: tags.replace(/\b(score|rating):\S+/g, '').replace(/_/g, ' ').trim().slice(0, 200), snippet: tags, duration: null });
  }
  return hits;
}

async function rule34xxx(query, page, browserFetch) {
  if (depictsMinors(query)) return [];
  const tags = encodeURIComponent(booruTags(query, ['video'])).replace(/%20/g, '+');
  return fetchHits(`https://rule34.xxx/index.php?page=post&s=list&tags=${tags}&pid=${page * 42}`, browserFetch, parseRule34xxx);
}

const ENGINES = { google, bing, bingVideos, duckduckgo, redgifs, rule34video, paheal, rule34xxx };
/** Adult engines, used only while the "Adult sites (18+)" switch is on. */
const ADULT_ENGINES = ['redgifs', 'rule34video', 'paheal', 'rule34xxx'];
/** Engines used unless the caller opts into more (adult engines are opt-in). */
const DEFAULT_ENGINES = ['google', 'bing', 'bingVideos', 'duckduckgo'];

/**
 * Query every engine in parallel and merge the hits round-robin so no single
 * engine (or site) dominates the inspection order.
 */
async function searchWeb(query, { page = 0, browserFetch, engines = DEFAULT_ENGINES, onEngine, includeRedgifs = engines.some((e) => ADULT_ENGINES.includes(e)) } = {}) {
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
      if (isBlockedSite(h.url)) continue; // excluded sites (YouTube, TikTok)
      if (!includeRedgifs && isAdultSite(h.url)) continue; // adult sites switched off
      if (isAdultSite(h.url) && depictsMinors(`${h.title} ${h.snippet} ${h.url}`)) continue; // always excluded
      if (unwantedContent(`${h.title} ${h.snippet} ${h.url}`, isAdultSite(h.url))) continue; // music videos / male focus
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

module.exports = { readCapped, parseGoogle, parseRedgifs, parseRule34video, parsePaheal, parseRule34xxx, DEFAULT_ENGINES, ADULT_ENGINES, searchWeb, cleanHitUrl, normalizeKey, ENGINES, domainOf };

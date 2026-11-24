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

/** Cookies that pre-accept a site's consent or "are you 18?" page. */
function cookieFor(url) {
  let host = '';
  try { host = new URL(url).hostname; } catch { /* bad url */ }
  if (/(^|\.)pornhub\.com$/.test(host)) return 'accessAgeDisclaimerPH=1; accessAgeDisclaimerUK=1; age_verified=1; platform=pc';
  if (/(^|\.)spankbang\.com$/.test(host)) return 'age_pass=1';
  if (/(^|\.)xhamster\.com$/.test(host)) return 'x_content_preference_index=straight';
  return 'CONSENT=YES+1; SOCS=CAI';
}

async function fetchPlain(url) {
  try {
    const res = await fetch(url, {
      headers: {
        'User-Agent': UA,
        'Accept': 'text/html,application/xhtml+xml',
        'Accept-Language': 'en-US,en;q=0.9',
        // Pre-accepted consent / age-confirmation pages, per site.
        'Cookie': cookieFor(url),
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

function clockToSecs(t) {
  const parts = String(t).split(':').map(Number);
  if (parts.some(isNaN)) return parseDuration(t);
  return parts.reduce((acc, n) => acc * 60 + n, 0) || null;
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

// ---------- adult video sites (opt-in, 18+) ----------
// Each site's search page is read for links in that site's video-page address format; titles
// come from the link (title attribute, image alt, text) or else the address's slug. The video
// pages themselves go through normal page inspection (which also reads video links that pages
// keep in their scripts, and sniffs the network in the hidden browser).
const plus = (q) => encodeURIComponent(q.trim()).replace(/%20/g, '+');
const dashes = (q) => q.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const SITES = {
  xvideos: { base: 'https://www.xvideos.com', search: (q, p) => `https://www.xvideos.com/?k=${plus(q)}${p ? `&p=${p}` : ''}`, video: /^\/video[.\d][\w.]*\/[^/?#]+\/?$/i },
  xnxx: { base: 'https://www.xnxx.com', search: (q, p) => `https://www.xnxx.com/search/${plus(q)}${p ? `/${p}` : ''}`, video: /^\/video-[a-z0-9]+\/[^/?#]+\/?$/i },
  pornhub: { base: 'https://www.pornhub.com', search: (q, p) => `https://www.pornhub.com/video/search?search=${plus(q)}&page=${p + 1}`, video: /^\/view_video\.php\?viewkey=[\w]+$/i },
  xhamster: { base: 'https://xhamster.com', search: (q, p) => `https://xhamster.com/search/${encodeURIComponent(q.trim())}?page=${p + 1}`, video: /^\/videos\/[\w-]+-[a-z0-9]+\/?$/i },
  eporner: { base: 'https://www.eporner.com', search: (q, p) => `https://www.eporner.com/search/${dashes(q)}/${p + 1}/`, video: /^\/(?:video-[\w]+|hd-porn\/[\w]+)\/[^/?#]+\/?$/i },
  spankbang: { base: 'https://spankbang.com', search: (q, p) => `https://spankbang.com/s/${plus(q)}/${p + 1}/`, video: /^\/[a-z0-9]+\/video\/[^/?#]+\/?$/i },
  rule34us: { base: 'https://rule34.us', search: (q, p) => `https://rule34.us/index.php?r=posts/index&q=${plus(booruTags(q, ['video']))}&page=${p}`, video: /^\/?index\.php\?r=posts\/view&id=\d+$/i },
};

/** Video-page links on a site's search page, in page order, with the best title seen for each. */
function parseSiteLinks(site, html) {
  const cfg = SITES[site];
  const host = new URL(cfg.base).hostname;
  const byUrl = new Map();
  for (const m of html.matchAll(/<a\b([^>]{0,2000})>([\s\S]{0,3000}?)<\/a>/gi)) {
    const href = (m[1].match(/\bhref\s*=\s*["']([^"']+)["']/i) || [])[1];
    if (!href) continue;
    let u;
    try { u = new URL(decodeEntities(href), cfg.base + '/'); } catch { continue; }
    if (u.hostname.replace(/^(www|m)\./, '') !== host.replace(/^www\./, '')) continue;
    const rel = u.pathname + u.search;
    if (!cfg.video.test(rel)) continue;
    const url = cfg.base + rel;
    const title = decodeEntities((m[1].match(/\btitle\s*=\s*["']([^"']{3,300})["']/i) || [])[1] || '') ||
      decodeEntities((m[2].match(/<img[^>]+alt\s*=\s*["']([^"']{3,300})["']/i) || [])[1] || '') || stripTags(m[2]).slice(0, 200);
    const prev = byUrl.get(url);
    if (!prev) byUrl.set(url, { url, title: /\w{3}/.test(title) ? title : '', snippet: '', duration: null });
    else if (title.length > prev.title.length && /\w{3}/.test(title)) prev.title = title;
  }
  const hits = [...byUrl.values()];
  for (const h of hits) {
    // No usable title on the search page: words from the address (".../big-red-dragon").
    if (!h.title) h.title = decodeURIComponent(new URL(h.url).pathname.split('/').filter(Boolean).pop() || '').replace(/[-_]+/g, ' ').replace(/\.\w+$/, '');
  }
  return hits.filter((h) => !depictsMinors(`${h.title} ${h.url}`)).slice(0, 40);
}

const siteEngine = (site) => async (query, page, browserFetch) => {
  if (depictsMinors(query)) return []; // never search adult sites for that
  return fetchHits(SITES[site].search(query, page), browserFetch, (html) => parseSiteLinks(site, html));
};

const ENGINES = { duckduckgo, redgifs, rule34video, paheal, rule34xxx };
for (const site of Object.keys(SITES)) ENGINES[site] = siteEngine(site);
/** Adult engines, used only while the "Adult sites (18+)" switch is on. */
const ADULT_ENGINES = ['xvideos', 'xnxx', 'pornhub', 'xhamster', 'eporner', 'spankbang', 'redgifs', 'rule34video', 'paheal', 'rule34xxx', 'rule34us'];
/** Engines used unless the caller opts into more (adult engines are opt-in). */
const DEFAULT_ENGINES = ['duckduckgo'];

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

module.exports = { cookieFor, readCapped, parseSiteLinks, SITES, parseRedgifs, parseRule34video, parsePaheal, parseRule34xxx, DEFAULT_ENGINES, ADULT_ENGINES, searchWeb, cleanHitUrl, normalizeKey, ENGINES, domainOf };

'use strict';
// Page inspection: find the playable video(s) on any web page.
//   Stage 1 – fetch the raw HTML and read what the page declares (og:video, JSON-LD
//             VideoObject, <video>/<source> tags, known player iframes). Fast, no browser.
//   Stage 2 – if nothing playable was declared, open the page in headless Chromium,
//             press play, and sniff the network for video files / HLS playlists,
//             then read the live DOM of every frame.

const { chromium } = require('playwright');
const {
  UA, isAdUrl, SEGMENT_EXT, mediaKindFromUrl, embedFromUrl, domainOf,
  decodeEntities, stripTags, parseDuration,
} = require('./util');

const PAGE_TIMEOUT = 15000;
const SNIFF_WINDOW = 4500;
const MIN_DURATION = 20; // seconds; shorter clips are almost always ads/teasers
const CACHE_TTL = 60 * 60 * 1000;

let browserPromise = null;
function getBrowser() {
  if (!browserPromise) {
    browserPromise = chromium.launch({
      headless: true,
      args: ['--disable-blink-features=AutomationControlled', '--autoplay-policy=no-user-gesture-required', '--mute-audio'],
    }).catch((e) => { browserPromise = null; throw e; });
  }
  return browserPromise;
}

async function newContext() {
  const browser = await getBrowser();
  const ctx = await browser.newContext({
    userAgent: UA,
    viewport: { width: 1280, height: 800 },
    locale: 'en-US',
    javaScriptEnabled: true,
    ignoreHTTPSErrors: true,
  });
  // Skip images/fonts and ad networks: faster loads, no ad videos sniffed.
  await ctx.route('**/*', (route) => {
    const req = route.request();
    const type = req.resourceType();
    if (type === 'image' || type === 'font' || isAdUrl(req.url())) return route.abort();
    return route.continue();
  });
  return ctx;
}

/** Load a URL in the real browser and return its HTML (used when a search page blocks plain fetch). */
async function browserFetch(url) {
  const ctx = await newContext();
  try {
    const page = await ctx.newPage();
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT });
    await page.waitForTimeout(1500);
    return await page.content();
  } finally {
    await ctx.close().catch(() => {});
  }
}

// ---------------------------------------------------------------- static parsing

function attr(tag, name) {
  const m = tag.match(new RegExp(`\\b${name}\\s*=\\s*("([^"]*)"|'([^']*)'|([^\\s>]+))`, 'i'));
  return m ? decodeEntities(m[2] ?? m[3] ?? m[4] ?? '') : null;
}

function absolute(url, base) {
  if (!url) return null;
  try { return new URL(url, base).toString(); } catch { return null; }
}

/** Parse raw HTML into the same shape the live-DOM probe returns. */
function parseStaticHtml(html, baseUrl) {
  const out = { url: baseUrl, title: '', metas: {}, ld: [], videos: [], iframes: [], text: '' };
  const t = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  out.title = t ? stripTags(t[1]) : '';
  for (const tag of html.match(/<meta\b[^>]*>/gi) || []) {
    const key = (attr(tag, 'property') || attr(tag, 'name') || attr(tag, 'itemprop') || '').toLowerCase();
    const val = attr(tag, 'content');
    if (key && val && !(key in out.metas)) out.metas[key] = val;
  }
  for (const m of html.matchAll(/<script[^>]+type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi)) out.ld.push(m[1]);
  for (const m of html.matchAll(/<video\b([^>]*)>([\s\S]*?)<\/video>/gi)) {
    const sources = [...m[2].matchAll(/<source\b[^>]*>/gi)].map((s) => ({ src: absolute(attr(s[0], 'src'), baseUrl), type: attr(s[0], 'type') || '' }));
    out.videos.push({ src: absolute(attr(m[1], 'src'), baseUrl), sources, poster: absolute(attr(m[1], 'poster'), baseUrl), duration: null, w: null, h: null });
  }
  for (const m of html.matchAll(/<iframe\b[^>]*>/gi)) {
    const src = absolute(attr(m[0], 'src') || attr(m[0], 'data-src'), baseUrl);
    if (src) out.iframes.push(src);
  }
  const body = html.match(/<body[\s\S]*$/i);
  out.text = stripTags((body ? body[0] : html).replace(/<(script|style|noscript)[\s\S]*?<\/\1>/gi, ' ')).slice(0, 3000);
  return out;
}

// Runs inside each frame of the live page.
function domProbe() {
  const out = { url: location.href, title: document.title, metas: {}, ld: [], videos: [], iframes: [], text: '' };
  document.querySelectorAll('meta[property],meta[name],meta[itemprop]').forEach((m) => {
    const k = (m.getAttribute('property') || m.getAttribute('name') || m.getAttribute('itemprop') || '').toLowerCase();
    const v = m.getAttribute('content');
    if (k && v && !(k in out.metas)) out.metas[k] = v;
  });
  document.querySelectorAll('script[type="application/ld+json"]').forEach((s) => out.ld.push(s.textContent));
  document.querySelectorAll('video').forEach((v) => {
    const r = v.getBoundingClientRect();
    out.videos.push({
      src: v.currentSrc || v.src || null,
      sources: [...v.querySelectorAll('source')].map((s) => ({ src: s.src, type: s.type || '' })),
      poster: v.poster || null,
      duration: Number.isFinite(v.duration) ? v.duration : null,
      w: r.width, h: r.height,
    });
  });
  document.querySelectorAll('iframe').forEach((f) => f.src && out.iframes.push(f.src));
  out.text = (document.body ? document.body.innerText : '').slice(0, 3000);
  return out;
}

async function pressPlay(page) {
  for (const frame of page.frames()) {
    await frame.evaluate(() => {
      document.querySelectorAll('video').forEach((v) => { v.muted = true; const p = v.play(); if (p) p.catch(() => {}); });
    }).catch(() => {});
  }
  const selectors = [
    '.vjs-big-play-button', '.jw-icon-display', '.ytp-large-play-button', '.plyr__control--overlaid',
    'button[aria-label*="play" i]', '[class*="play-button" i]', '[class*="PlayButton"]', '[data-testid*="play" i]',
  ];
  for (const sel of selectors) {
    const el = page.locator(sel).first();
    if (await el.isVisible({ timeout: 200 }).catch(() => false)) {
      await el.click({ timeout: 800, noWaitAfter: true }).catch(() => {});
      break;
    }
  }
}

// ---------------------------------------------------------------- candidate extraction

function flattenLd(node, acc = []) {
  if (!node || typeof node !== 'object') return acc;
  if (Array.isArray(node)) { node.forEach((n) => flattenLd(n, acc)); return acc; }
  const type = [].concat(node['@type'] || []).join(',');
  if (/VideoObject|Clip|Movie|Episode/i.test(type) && (node.contentUrl || node.embedUrl || node.url)) acc.push(node);
  for (const k of Object.keys(node)) if (k !== '@context' && typeof node[k] === 'object') flattenLd(node[k], acc);
  return acc;
}

function firstString(v) {
  if (v == null) return null;
  if (Array.isArray(v)) return firstString(v[0]);
  if (typeof v === 'object') return firstString(v.url || v.contentUrl || v['@id']);
  return String(v);
}

function candidateFromUrl(src, base, extra) {
  const url = absolute(src, base);
  if (!url || /^(blob|data):/i.test(url) || isAdUrl(url)) return null;
  const embed = embedFromUrl(url);
  if (embed) return { kind: 'embed', ...embed, src: url, ...extra };
  const kind = mediaKindFromUrl(url, extra && extra.type);
  if (kind === 'file' || kind === 'hls') return { kind, src: url, ...extra };
  return null;
}

/** Turn one probe (static HTML or a live frame) into media candidates with metadata. */
function candidatesFromProbe(p) {
  const out = [];
  const base = p.url;
  // JSON-LD VideoObjects (the most trustworthy: the site describing its own video)
  for (const block of p.ld) {
    let data;
    try { data = JSON.parse(block.trim()); } catch { continue; }
    for (const v of flattenLd(data)) {
      const meta = {
        title: stripTags(v.name || v.headline || ''), description: stripTags(v.description || '').slice(0, 400),
        thumbnail: absolute(firstString(v.thumbnailUrl || v.thumbnail || v.image), base),
        duration: parseDuration(v.duration), origin: 'jsonld', referer: base,
      };
      const c = candidateFromUrl(firstString(v.contentUrl), base, { ...meta, type: v.encodingFormat || '' })
        || candidateFromUrl(firstString(v.embedUrl), base, meta);
      if (c) out.push(c);
    }
  }
  // Open Graph / Twitter card video
  const m = p.metas;
  for (const key of ['og:video:secure_url', 'og:video:url', 'og:video', 'twitter:player:stream', 'twitter:player']) {
    if (!m[key]) continue;
    const c = candidateFromUrl(m[key], base, {
      type: m['og:video:type'] || m['twitter:player:stream:content_type'] || '',
      duration: parseDuration(m['video:duration'] || m['og:video:duration'] || m.duration),
      origin: 'og', referer: base,
    });
    if (c) out.push(c);
  }
  // <video> elements
  for (const v of p.videos) {
    const tooSmall = v.w != null && v.w > 0 && (v.w < 200 || v.h < 110);
    for (const s of [{ src: v.src, type: '' }, ...v.sources]) {
      const c = candidateFromUrl(s.src, base, { type: s.type, duration: v.duration, thumbnail: absolute(v.poster, base), origin: 'dom', referer: base, tooSmall });
      if (c) out.push(c);
    }
  }
  // Known player iframes (YouTube / Vimeo / Dailymotion embedded in articles)
  for (const src of p.iframes) {
    const e = embedFromUrl(src);
    if (e) out.push({ kind: 'embed', ...e, src, origin: 'iframe', referer: base });
  }
  return out;
}

function pageMeta(p, hit) {
  const m = p.metas;
  return {
    title: m['og:title'] || m['twitter:title'] || p.title || hit.title || '',
    description: (m['og:description'] || m.description || m['twitter:description'] || hit.snippet || '').slice(0, 400),
    thumbnail: absolute(m['og:image'] || m['twitter:image'] || m.thumbnailurl, p.url),
    siteName: m['og:site_name'] || domainOf(p.url),
    text: p.text || '',
  };
}

function scoreCandidate(c) {
  if (c.duration != null && c.duration < MIN_DURATION) return -1;
  if (c.tooSmall) return -1;
  let s = { jsonld: 50, og: 45, network: 35, dom: 40, iframe: 30, page: 60 }[c.origin] || 20;
  if (c.kind === 'hls') s += 4;
  if (c.kind === 'file' && /webm|mp4/i.test(c.src)) s += 2;
  if (c.size && c.size > 2e6) s += 6;
  if (c.size && c.size < 3e5 && c.kind === 'file') s -= 25;
  if (c.duration && c.duration >= 60) s += 5;
  return s;
}

/** Pick the best distinct media on a page and attach page-level metadata. */
function finalize(hit, probes, cands) {
  const main = probes[0];
  const meta = pageMeta(main, hit);
  const byKey = new Map();
  for (const c of cands) {
    const key = c.kind === 'embed' ? `${c.provider}:${c.id}` : c.src.replace(/[?#].*$/, '');
    const score = scoreCandidate(c);
    if (score < 0) continue;
    const prev = byKey.get(key);
    // Merge: keep best score but fill missing metadata from any duplicate.
    const merged = prev ? { ...c, ...prev, title: prev.title || c.title, thumbnail: prev.thumbnail || c.thumbnail, duration: prev.duration || c.duration, score: Math.max(prev.score, score) } : { ...c, score };
    byKey.set(key, merged);
  }
  const sorted = [...byKey.values()].sort((a, b) => b.score - a.score);
  // Several distinct JSON-LD videos = a page that lists many videos; otherwise one per page.
  const multi = sorted.filter((c) => c.origin === 'jsonld' && c.title);
  const chosen = multi.length > 1 ? multi.slice(0, 3) : sorted.slice(0, 1);
  return chosen.map((c) => ({
    kind: c.kind, src: c.src, provider: c.provider, id: c.id,
    referer: c.referer || hit.url,
    title: c.title || meta.title, description: c.description || meta.description,
    thumbnail: c.thumbnail || meta.thumbnail, duration: c.duration || hit.duration || null,
    siteName: meta.siteName, pageUrl: hit.url, pageTitle: meta.title, pageText: meta.text,
    via: c.origin,
  }));
}

// ---------------------------------------------------------------- main entry

const cache = new Map();

async function inspectPage(hit, { signal } = {}) {
  const cached = cache.get(hit.url);
  if (cached && Date.now() - cached.t < CACHE_TTL) return cached.result;
  const result = await inspectUncached(hit, signal);
  cache.set(hit.url, { t: Date.now(), result });
  if (cache.size > 2000) cache.delete(cache.keys().next().value);
  return result;
}

async function inspectUncached(hit, signal) {
  // Stage 1: static HTML
  let staticProbe = null;
  try {
    const res = await fetch(hit.url, {
      headers: { 'User-Agent': UA, 'Accept': 'text/html,*/*', 'Accept-Language': 'en-US,en;q=0.9', 'Cookie': 'CONSENT=YES+1; SOCS=CAI' },
      redirect: 'follow', signal: AbortSignal.timeout(8000),
    });
    const ct = res.headers.get('content-type') || '';
    const kind = mediaKindFromUrl(res.url, ct);
    if (res.ok && (kind === 'file' || kind === 'hls')) {
      // The search hit *is* a media file.
      res.body && res.body.cancel().catch(() => {});
      return [{ kind, src: res.url, referer: hit.url, title: hit.title || decodeURIComponent(res.url.split('/').pop()), description: hit.snippet || '', siteName: domainOf(res.url), pageUrl: hit.url, pageTitle: hit.title, pageText: '', duration: hit.duration || null, via: 'direct' }];
    }
    if (res.ok && /html/i.test(ct)) {
      staticProbe = parseStaticHtml((await res.text()).slice(0, 3_000_000), res.url);
    }
  } catch { /* try the browser */ }

  const pageEmbed = embedFromUrl(hit.url);
  if (staticProbe) {
    const cands = candidatesFromProbe(staticProbe);
    if (pageEmbed) cands.unshift({ kind: 'embed', ...pageEmbed, src: hit.url, origin: 'page', referer: hit.url });
    const best = finalize(hit, [staticProbe], cands);
    // The page already declares playable media in its HTML — no browser needed.
    if (best.length) return best;
  } else if (pageEmbed) {
    return finalize(hit, [{ url: hit.url, title: hit.title, metas: {}, ld: [], videos: [], iframes: [], text: '' }],
      [{ kind: 'embed', ...pageEmbed, src: hit.url, origin: 'page', referer: hit.url, title: hit.title }]);
  }
  if (signal && signal.aborted) return [];

  // Stage 2: live browser + network sniffing
  return inspectInBrowser(hit, staticProbe, signal);
}

async function inspectInBrowser(hit, staticProbe, signal) {
  const ctx = await newContext();
  const sniffed = new Map();
  try {
    const page = await ctx.newPage();
    const onMedia = (url, ct, size, referer) => {
      if (!/^https?:/i.test(url) || isAdUrl(url) || SEGMENT_EXT.test(url.split('?')[0])) return;
      const kind = mediaKindFromUrl(url, ct);
      if (kind !== 'file' && kind !== 'hls') return;
      const key = url.replace(/[?#].*$/, '');
      const prev = sniffed.get(key);
      sniffed.set(key, { kind, src: url, size: Math.max(size || 0, (prev && prev.size) || 0), origin: 'network', referer: referer || hit.url });
    };
    page.on('request', (req) => {
      if (req.resourceType() === 'media' || mediaKindFromUrl(req.url())) onMedia(req.url(), '', 0, req.headers().referer);
    });
    page.on('response', (res) => {
      const h = res.headers();
      const ct = h['content-type'] || '';
      if (/^video\/|mpegurl|dash\+xml/i.test(ct) || mediaKindFromUrl(res.url())) {
        const range = (h['content-range'] || '').match(/\/(\d+)$/);
        onMedia(res.url(), ct, range ? +range[1] : +(h['content-length'] || 0), res.request().headers().referer);
      }
    });

    await page.goto(hit.url, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT });
    await page.waitForTimeout(1200);
    await page.mouse.wheel(0, 600).catch(() => {});
    await pressPlay(page);
    // Give players a moment to request their media; stop early once something shows up.
    const until = Date.now() + SNIFF_WINDOW;
    while (Date.now() < until && !(signal && signal.aborted)) {
      if (sniffed.size && Date.now() > until - SNIFF_WINDOW / 2) break;
      await page.waitForTimeout(400);
    }

    const probes = [];
    for (const frame of page.frames()) {
      const p = await frame.evaluate(domProbe).catch(() => null);
      if (p) probes.push(p);
    }
    if (!probes.length) probes.push(staticProbe || { url: hit.url, title: hit.title, metas: {}, ld: [], videos: [], iframes: [], text: '' });
    // Live DOM durations tell us which sniffed file is the long (real) video.
    const durations = new Map();
    for (const p of probes) for (const v of p.videos) {
      for (const s of [v.src, ...v.sources.map((x) => x.src)]) if (s && v.duration) durations.set(s.replace(/[?#].*$/, ''), v.duration);
    }
    const cands = probes.flatMap(candidatesFromProbe);
    for (const [key, c] of sniffed) cands.push({ ...c, duration: durations.get(key) || null });
    return finalize(hit, probes, cands);
  } catch {
    return staticProbe ? finalize(hit, [staticProbe], candidatesFromProbe(staticProbe)) : [];
  } finally {
    await ctx.close().catch(() => {});
  }
}

async function shutdown() {
  if (browserPromise) {
    const b = await browserPromise.catch(() => null);
    browserPromise = null;
    if (b) await b.close().catch(() => {});
  }
}

module.exports = { inspectPage, browserFetch, parseStaticHtml, candidatesFromProbe, shutdown };

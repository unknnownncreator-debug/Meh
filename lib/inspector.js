'use strict';
// Page inspection: find the playable video(s) on any web page.
//   Stage 1 – fetch the raw HTML and read what the page declares (og:video, JSON-LD
//             VideoObject, <video>/<source> tags, known player iframes). Fast, no browser.
//   Stage 2 – if nothing playable was declared, open the page in headless Chromium,
//             press play, and sniff the network for video files / HLS playlists,
//             then read the live DOM of every frame.

const { chromium } = require('playwright');
const { readCapped } = require('./search');
const { probeDuration } = require('./media');
const { countBlocked } = require('./adblock');
const {
  UA, isAdUrl, isBlockedVideo, withSound, SEGMENT_EXT, mediaKindFromUrl, embedFromUrl, domainOf,
  decodeEntities, stripTags, parseDuration,
} = require('./util');

const PAGE_TIMEOUT = 15000;
const HARD_TIMEOUT = 30000; // whole browser inspection of one page, no matter what the page does
const STEP_TIMEOUT = 3000;  // any single evaluate/click (a page stuck in a JS loop never answers)
const SNIFF_WINDOW = 6500;
// Shortest video we accept (seconds): only skips blank placeholders and one-frame
// decorations. Ads are handled by the ad blocker, ad-container detection, request order
// and the page's own declared length — not by a length floor, so short real clips play.
const MIN_DURATION = 5;
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

/** Resolve to `fallback` if `p` hasn't settled within `ms`. */
function within(p, ms, fallback = null) {
  let t;
  return Promise.race([
    Promise.resolve(p).catch(() => fallback),
    new Promise((r) => { t = setTimeout(() => r(fallback), ms); }),
  ]).finally(() => clearTimeout(t));
}

/**
 * @param onMedia optional (url, referer) callback: media requests with a recognisable
 *   file/HLS URL are reported and then aborted, so sniffing never downloads whole videos.
 */
async function newContext(onMedia) {
  const browser = await getBrowser();
  const ctx = await browser.newContext({
    userAgent: UA,
    viewport: { width: 1280, height: 800 },
    locale: 'en-US',
    javaScriptEnabled: true,
    ignoreHTTPSErrors: true,
  });
  // Pre-accept Google's cookie consent so EU users don't land on a consent wall.
  await ctx.addCookies(['.google.com', '.youtube.com'].flatMap((domain) => [
    { name: 'SOCS', value: 'CAI', domain, path: '/' },
    { name: 'CONSENT', value: 'YES+1', domain, path: '/' },
  ])).catch(() => {});
  // Skip images/fonts and ad networks: faster loads, no ad videos sniffed.
  await ctx.route('**/*', (route) => {
    const req = route.request();
    const type = req.resourceType();
    const url = req.url();
    // Never block the page being inspected itself — only what it loads (its URL may
    // legitimately contain words like "preroll" or "ads").
    const isPage = req.isNavigationRequest() && req.frame() === req.frame().page().mainFrame();
    if (!isPage && isAdUrl(url)) { countBlocked(); return route.abort().catch(() => {}); }
    if (type === 'image' || type === 'font') return route.abort().catch(() => {});
    if (onMedia && /^https?:/i.test(url)) {
      if (SEGMENT_EXT.test(url.split('?')[0])) return route.abort().catch(() => {});
      const kind = mediaKindFromUrl(url);
      if (kind === 'file' || kind === 'hls') {
        onMedia(url, req.headers().referer);
        return route.abort().catch(() => {});
      }
    }
    return route.continue().catch(() => {});
  });
  return ctx;
}

/** Load a URL in the real browser and return its HTML (used when a search page blocks plain fetch). */
// Cloudflare-style "checking your browser" pages clear themselves after a few seconds.
const CHALLENGE = /cf-chl|challenge-platform|<title>\s*Just a moment|Checking your browser|DDoS protection by/i;

/** Wait (up to ~15 s) for a bot-check interstitial to finish and the real page to load. */
async function waitOutChallenge(page) {
  for (let i = 0; i < 30; i++) {
    const html = await within(page.content(), STEP_TIMEOUT, '');
    if (!CHALLENGE.test(html.slice(0, 20000))) return;
    await page.waitForTimeout(500);
  }
}

async function browserFetch(url) {
  const ctx = await newContext();
  try {
    const page = await ctx.newPage();
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT });
    await page.waitForTimeout(1500);
    await waitOutChallenge(page);
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

/**
 * Linear-time tag scanner. Regexes like /<video[^>]*>([\s\S]*?)<\/video>/ go quadratic on
 * pages with many unclosed tags (each match rescans to the end), so instead: bounded
 * attribute matching, and the next closing tag is found once and reused.
 */
function scanTags(html, lower, tag, maxInner = 0) {
  const out = [];
  const open = new RegExp(`<${tag}\\b([^>]{0,4000})>`, 'gi');
  const closeTag = `</${tag}`;
  let close = -2; // -2 = not looked up yet, -1 = no more closing tags anywhere
  let m;
  while ((m = open.exec(html)) && out.length < 2000) {
    let inner = '';
    if (maxInner) {
      const from = open.lastIndex;
      if (close !== -1 && close < from) close = lower.indexOf(closeTag, from);
      if (close >= 0 && close - from <= maxInner) inner = html.slice(from, close);
    }
    out.push({ attrs: m[1], tag: m[0], inner, index: m.index });
  }
  return out;
}

// Elements whose class/id marks them as an ad slot (same idea as the live-page check).
const AD_BOX_ATTR = /\b(?:class|id)\s*=\s*["'][^"']*(?:ad-container|ima-|preroll|advert|ad-slot|adslot|ad-player|adplayer|\bvast\b|vpaid|sponsor)[^"']*["']|\bdata-ad(?:-slot)?\b|\bid\s*=\s*["']google_ads/i;

/**
 * Is position `idx` (a <video> tag) inside a still-open ad-slot element? Looks back a short
 * way for the nearest ad-marked opener and checks it hasn't been closed again before idx.
 */
function insideAdBox(lower, idx) {
  const from = Math.max(0, idx - 4000);
  const before = lower.slice(from, idx);
  const re = /<(div|section|aside|figure|span|ins)\b([^>]{0,800})>/g;
  let m;
  let last = null;
  while ((m = re.exec(before))) if (AD_BOX_ATTR.test(m[2])) last = { tag: m[1], at: m.index + m[0].length };
  if (!last) return false;
  // Walk the same-name tags after it in order: once the ad element's own close tag is
  // reached (nesting depth back to 0), the video is outside it.
  const after = before.slice(last.at);
  const step = new RegExp(`<(/?)${last.tag}\\b`, 'g');
  let depth = 1;
  while ((m = step.exec(after))) {
    depth += m[1] ? -1 : 1;
    if (depth === 0) return false;
  }
  return true;
}

/** Remove <script>/<style>/<noscript> blocks in one pass. */
function stripBlocks(html, lower) {
  const parts = [];
  const re = /<(script|style|noscript)\b/gi;
  const nextClose = {};
  let pos = 0;
  let m;
  while ((m = re.exec(html))) {
    if (m.index < pos) continue;
    const name = m[1].toLowerCase();
    parts.push(html.slice(pos, m.index));
    let c = nextClose[name];
    if (c === undefined || (c !== -1 && c < m.index)) c = nextClose[name] = lower.indexOf(`</${name}`, m.index);
    if (c === -1) { pos = html.length; break; }
    pos = c + name.length + 2;
    re.lastIndex = pos;
  }
  parts.push(html.slice(pos));
  return parts.join(' ');
}

/** Parse raw HTML into the same shape the live-DOM probe returns. */
function parseStaticHtml(html, baseUrl) {
  html = String(html).slice(0, 3_000_000);
  const lower = html.toLowerCase();
  const out = { url: baseUrl, title: '', metas: {}, ld: [], videos: [], iframes: [], text: '' };
  const ti = lower.indexOf('<title');
  if (ti >= 0) {
    const start = lower.indexOf('>', ti) + 1;
    const end = lower.indexOf('</title', start);
    if (start > 0 && end > start && end - start < 5000) out.title = stripTags(html.slice(start, end));
  }
  for (const { tag } of scanTags(html, lower, 'meta')) {
    const key = (attr(tag, 'property') || attr(tag, 'name') || attr(tag, 'itemprop') || '').toLowerCase();
    const val = attr(tag, 'content');
    if (key && val && !(key in out.metas)) out.metas[key] = val;
  }
  for (const { attrs, inner } of scanTags(html, lower, 'script', 2_000_000)) {
    if (/type\s*=\s*["']?application\/ld\+json/i.test(attrs) && inner && out.ld.length < 30) out.ld.push(inner);
  }
  for (const { attrs, inner, index } of scanTags(html, lower, 'video', 20000).slice(0, 300)) {
    const sources = scanTags(inner, inner.toLowerCase(), 'source').map((t) => ({ src: absolute(attr(t.tag, 'src'), baseUrl), type: attr(t.tag, 'type') || '' }));
    out.videos.push({ src: absolute(attr(attrs, 'src'), baseUrl), sources, poster: absolute(attr(attrs, 'poster'), baseUrl), duration: null, w: num(attr(attrs, 'width')), h: num(attr(attrs, 'height')), ad: insideAdBox(lower, index) });
  }
  for (const { tag } of scanTags(html, lower, 'iframe').slice(0, 300)) {
    const src = absolute(attr(tag, 'src') || attr(tag, 'data-src'), baseUrl);
    if (src) out.iframes.push(src);
  }
  const bodyAt = lower.indexOf('<body');
  const body = bodyAt >= 0 ? html.slice(bodyAt) : html;
  out.text = stripTags(stripBlocks(body.slice(0, 600_000), body.slice(0, 600_000).toLowerCase())).slice(0, 3000);
  return out;
}

/** Pixel size from a width/height attribute; percentages and junk → null (unknown). */
function num(v) {
  if (!v || !/^\s*\d+(\.\d+)?(px)?\s*$/i.test(v)) return null;
  const n = parseFloat(v);
  return n > 0 ? n : null;
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
  // Ad players (IMA, VAST/VPAID plugins, ad slots) put their video inside containers like these.
  const AD_BOX = '[class*="ad-container" i],[id*="ad-container" i],[class*="ima-" i],[id*="ima-" i],[class*="preroll" i],' +
    '[id*="preroll" i],[class*="advert" i],[id*="advert" i],[class*="ad-player" i],[class*="adplayer" i],[class*="ad-slot" i],' +
    '[class*="adslot" i],[class*="vast" i],[class*="vpaid" i],[data-ad],[data-ad-slot],[id^="google_ads" i],[class*="sponsor" i]';
  document.querySelectorAll('video').forEach((v) => {
    const r = v.getBoundingClientRect();
    out.videos.push({
      ad: !!v.closest(AD_BOX),
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
    await within(frame.evaluate(() => {
      document.querySelectorAll('video').forEach((v) => { v.muted = true; const p = v.play(); if (p) p.catch(() => {}); });
    }), STEP_TIMEOUT);
  }
  const selectors = [
    '.vjs-big-play-button', '.jw-icon-display', '.ytp-large-play-button', '.plyr__control--overlaid',
    'button[aria-label*="play" i]', '[class*="play-button" i]', '[class*="PlayButton"]', '[data-testid*="play" i]',
  ];
  for (const sel of selectors) {
    const el = page.locator(sel).first();
    if (await within(el.isVisible(), STEP_TIMEOUT, false)) {
      await within(el.click({ timeout: 800, noWaitAfter: true }), STEP_TIMEOUT);
      break;
    }
  }
}

// ---------------------------------------------------------------- candidate extraction

function flattenLd(node, acc = [], depth = 0) {
  // Depth/size limits: hostile or broken pages can nest data thousands of levels deep.
  if (!node || typeof node !== 'object' || depth > 40 || acc.length > 50) return acc;
  if (Array.isArray(node)) { node.slice(0, 500).forEach((n) => flattenLd(n, acc, depth + 1)); return acc; }
  const type = [].concat(node['@type'] || []).join(',');
  if (/VideoObject|Clip|Movie|Episode/i.test(type) && (node.contentUrl || node.embedUrl || node.url)) acc.push(node);
  for (const k of Object.keys(node).slice(0, 200)) if (k !== '@context' && typeof node[k] === 'object') flattenLd(node[k], acc, depth + 1);
  return acc;
}

function firstString(v) {
  if (v == null) return null;
  if (Array.isArray(v)) return firstString(v[0]);
  if (typeof v === 'object') return firstString(v.url || v.contentUrl || v['@id']);
  return String(v);
}

function candidateFromUrl(src, base, extra) {
  const url = withSound(absolute(src, base) || '') || null;
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
  for (const block of p.ld.slice(0, 30)) {
    let data;
    if (block.length > 2_000_000) continue;
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
    // Each type label describes only its own address (twitter:player is a web page, not a file).
    const type = key.startsWith('og:') ? m['og:video:type'] : key === 'twitter:player:stream' ? m['twitter:player:stream:content_type'] : '';
    const c = candidateFromUrl(m[key], base, {
      type: type || '',
      duration: parseDuration(m['video:duration'] || m['og:video:duration'] || m.duration),
      origin: 'og', referer: base,
    });
    if (c) out.push(c);
  }
  // <video> elements
  for (const v of p.videos) {
    // Only sizes we actually know count: a missing height is unknown, not zero.
    const tooSmall = (v.w != null && v.w > 0 && v.w < 200) || (v.h != null && v.h > 0 && v.h < 110);
    for (const s of [{ src: v.src, type: '' }, ...v.sources]) {
      const c = candidateFromUrl(s.src, base, { type: s.type, duration: v.duration, thumbnail: absolute(v.poster, base), origin: p.live ? 'live' : 'dom', referer: base, tooSmall, ad: !!v.ad });
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
    // Keywords / tag metadata count as page text (relevance, and the adult-site safety check).
    keywords: [m.keywords, m['article:tag'], m['video:tag']].filter(Boolean).join(' ').slice(0, 1000),
    text: [m.keywords, m['article:tag'], m['video:tag'], p.text].filter(Boolean).join(' ').slice(0, 4000),
  };
}

/** Minimum length for a candidate: declared media is trusted further than media found in the page. */
function minDuration() {
  return MIN_DURATION;
}

function scoreCandidate(c) {
  if (c.duration != null && c.duration < minDuration(c)) return -1;
  if (c.tooSmall || c.ad) return -1;
  let s = { jsonld: 50, og: 45, network: 35, live: 40, dom: 40, iframe: 30, page: 60 }[c.origin] || 20;
  // Players load the ad first and the real video after it: later network requests win ties.
  if (c.origin === 'network' && c.seq) s += Math.min(c.seq, 4);
  if (c.kind === 'hls') s += 4;
  if (c.kind === 'file' && /webm|mp4/i.test(c.src)) s += 2;
  if (c.size && c.size > 2e6) s += 6;
  if (c.size && c.size < 3e5 && c.kind === 'file') s -= 25;
  if (c.duration && c.duration >= 60) s += 5;
  return s;
}

/**
 * Pick the best distinct media on a page and attach page-level metadata. Candidates whose
 * length is unknown get it read from the file itself (probeDuration), so a short pre-roll ad
 * can't pass for the real video.
 */
async function finalize(hit, probes, cands, { probe = true } = {}) {
  const main = probes[0];
  const meta = pageMeta(main, hit);
  const byKey = new Map();
  for (const c of cands) {
    if (isBlockedVideo(c)) continue; // e.g. a YouTube/TikTok player embedded in an article
    const key = c.kind === 'embed' ? `${c.provider}:${c.id}` : c.src.replace(/[?#].*$/, '');
    const prev = byKey.get(key);
    // Merge duplicates, keeping the most trustworthy origin and any metadata either has.
    const merged = prev
      ? { ...(scoreCandidate(c) > scoreCandidate(prev) ? c : prev), title: prev.title || c.title, thumbnail: prev.thumbnail || c.thumbnail,
        duration: prev.duration || c.duration, ad: !!(prev.ad || c.ad), seq: Math.max(prev.seq || 0, c.seq || 0) }
      : { ...c };
    byKey.set(key, merged);
  }
  // How long the page itself says its video is (JSON-LD / og:video:duration).
  const declared = Math.max(0, ...[...byKey.values()].filter((c) => (c.origin === 'jsonld' || c.origin === 'og') && c.duration).map((c) => c.duration),
    parseDuration(main.metas['og:video:duration'] || main.metas['video:duration']) || 0);

  const ranked = [...byKey.values()].map((c) => ({ c, score: scoreCandidate(c) })).filter((x) => x.score >= 0)
    .sort((a, b) => b.score - a.score).map((x) => x.c);
  let probes_ = 0;
  const acceptable = async (c) => {
    if (c.kind === 'embed') return true;
    if (c.duration == null && probe && probes_ < 6) { probes_++; c.duration = await within(probeDuration(c), 7000); }
    if (c.duration != null && c.duration < minDuration(c)) return false;          // too short: ad / teaser
    if (declared >= 60 && c.duration != null && c.duration < declared * 0.5) return false; // page says its video is much longer
    return true;
  };
  // Several distinct JSON-LD videos = a page that lists many videos; otherwise one per page.
  const multi = ranked.filter((c) => c.origin === 'jsonld' && c.title);
  const chosen = [];
  for (const c of (multi.length > 1 ? multi : ranked).slice(0, 8)) {
    if (await acceptable(c)) chosen.push(c);
    if (chosen.length >= (multi.length > 1 ? 3 : 1)) break;
  }
  return chosen.map((c) => ({
    kind: c.kind, src: c.src, provider: c.provider, id: c.id,
    referer: c.referer || hit.url,
    title: c.title || meta.title, description: c.description || meta.description,
    thumbnail: c.thumbnail || meta.thumbnail, duration: c.duration || hit.duration || null,
    siteName: meta.siteName, pageUrl: hit.url, pageTitle: meta.title, pageText: meta.text, keywords: meta.keywords,
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
      staticProbe = parseStaticHtml(await readCapped(res, 3_000_000), res.url);
    }
  } catch { /* try the browser */ }

  const pageEmbed = embedFromUrl(hit.url);
  if (staticProbe) {
    const cands = candidatesFromProbe(staticProbe);
    if (pageEmbed) cands.unshift({ kind: 'embed', ...pageEmbed, src: hit.url, origin: 'page', referer: hit.url });
    const best = await finalize(hit, [staticProbe], cands);
    // The page already declares playable media in its HTML — no browser needed.
    if (best.length) return best;
  } else if (pageEmbed) {
    return await finalize(hit, [{ url: hit.url, title: hit.title, metas: {}, ld: [], videos: [], iframes: [], text: '' }],
      [{ kind: 'embed', ...pageEmbed, src: hit.url, origin: 'page', referer: hit.url, title: hit.title }]);
  }
  if (signal && signal.aborted) return [];

  // Stage 2: live browser + network sniffing, with a hard ceiling on time spent.
  const fallback = staticProbe ? await finalize(hit, [staticProbe], candidatesFromProbe(staticProbe), { probe: false }) : [];
  return within(inspectInBrowser(hit, staticProbe, signal), HARD_TIMEOUT + 8000, fallback);
}

async function inspectInBrowser(hit, staticProbe, signal) {
  const sniffed = new Map();
  const probes = [];
  const onMedia = (url, ct, size, referer) => {
    if (!/^https?:/i.test(url) || isAdUrl(url) || SEGMENT_EXT.test(url.split('?')[0])) return;
    const kind = mediaKindFromUrl(url, ct);
    if (kind !== 'file' && kind !== 'hls') return;
    const key = url.replace(/[?#].*$/, '');
    const prev = sniffed.get(key);
    sniffed.set(key, { kind, src: url, size: Math.max(size || 0, (prev && prev.size) || 0), origin: 'network',
      referer: referer || (prev && prev.referer) || hit.url, seq: prev ? prev.seq : sniffed.size + 1 });
  };
  // Media requests with a recognisable URL are recorded and aborted (no full downloads).
  const ctx = await newContext((url, referer) => onMedia(url, '', 0, referer));
  try {
    // Everything that touches the page is time-boxed: a page can hang, loop forever in
    // JS, or never finish loading, and none of that may stall the search.
    await within((async () => {
      const page = await ctx.newPage();
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
      page.on('dialog', (d) => d.dismiss().catch(() => {}));
      page.on('popup', (p) => p.close().catch(() => {}));

      await page.goto(hit.url, { waitUntil: 'domcontentloaded', timeout: PAGE_TIMEOUT }).catch(() => {});
      if (signal && signal.aborted) return;
      await page.waitForTimeout(1200);
      await waitOutChallenge(page);
      await within(page.mouse.wheel(0, 600), STEP_TIMEOUT);
      await pressPlay(page);
      // Keep watching after the first video request: that is usually a pre-roll ad, and
      // the real video is only requested once the (blocked) ad fails. Stop early only
      // once a second video has shown up.
      const start = Date.now();
      let pressedAgain = false;
      while (Date.now() - start < SNIFF_WINDOW && !(signal && signal.aborted)) {
        if (sniffed.size >= 2 && Date.now() - start > 3000) break;
        if (!pressedAgain && Date.now() - start > 2500) { pressedAgain = true; await pressPlay(page); }
        await page.waitForTimeout(400);
      }
      for (const frame of page.frames().slice(0, 20)) {
        if (frame !== page.mainFrame() && isAdUrl(frame.url())) continue; // ad iframes
        const p = await within(frame.evaluate(domProbe), STEP_TIMEOUT);
        if (p) probes.push({ ...p, live: true });
      }
    })(), HARD_TIMEOUT);
  } finally {
    await within(ctx.close(), 5000);
  }

  if (!probes.length) probes.push(staticProbe || { url: hit.url, title: hit.title, metas: {}, ld: [], videos: [], iframes: [], text: '' });
  // Live DOM durations tell us which sniffed file is the long (real) video.
  const durations = new Map();
  for (const p of probes) for (const v of p.videos) {
    for (const src of [v.src, ...v.sources.map((x) => x.src)]) if (src && v.duration) durations.set(src.replace(/[?#].*$/, ''), v.duration);
  }
  let cands = [];
  try { cands = probes.flatMap(candidatesFromProbe); } catch { /* malformed page data */ }
  for (const [key, c] of sniffed) cands.push({ ...c, duration: durations.get(key) || null });
  return finalize(hit, probes, cands);
}

async function shutdown() {
  if (browserPromise) {
    const b = await browserPromise.catch(() => null);
    browserPromise = null;
    if (b) await b.close().catch(() => {});
  }
}

module.exports = { inspectPage, browserFetch, parseStaticHtml, candidatesFromProbe, shutdown };

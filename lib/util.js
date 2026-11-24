'use strict';

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36';

// Hosts/paths that serve ads, trackers or analytics — never real content.
const AD_PATTERNS = [
  /doubleclick\.net/i, /googlesyndication\.com/i, /googleadservices\.com/i, /imasdk\.googleapis\.com/i,
  /2mdn\.net/i, /adnxs\.com/i, /springserve/i, /teads\./i, /taboola/i, /outbrain/i, /spotx/i,
  /adsrvr\.org/i, /amazon-adsystem/i, /moatads/i, /innovid/i, /serving-sys\.com/i, /pubmatic/i,
  /rubiconproject/i, /criteo/i, /adform/i, /smartadserver/i, /connatix/i, /primis\.tech/i,
  /aniview/i, /vidazoo/i, /exco\.com/i, /jwpltx\.com/i, /\/ads?\//i, /[?&/._-]vast[?&/._-]/i,
  /preroll/i, /pre-roll/i, /\/adserver/i, /adsystem/i,
  // more ad servers / ad-video CDNs / ad formats
  /googleads\./i, /adsafeprotected/i, /doubleverify/i, /tremorhub/i, /unrulymedia/i, /sascdn\.com/i, /advertising\.com/i,
  /adcolony/i, /vpaid/i, /\/adtags?\//i, /ad-?delivery/i, /adserv/i, /yieldmo/i, /lijit\.com/i, /openx\.net/i, /indexww\.com/i,
  /3lift\.com/i, /sharethrough/i, /gumgum/i, /\/creatives?\//i, /\/vast\//i, /admanager/i, /smartclip/i, /stickyadstv/i,
  /adition\.com/i, /videoadex/i, /ads-?video/i, /video-?ads?\b/i, /[?&]ad_?type=/i,
];

const adblock = require('./adblock');

/** Ad / tracker request: on the ad-server list (~43k domains), or matching a known ad pattern. */
function isAdUrl(url) {
  return adblock.isListedUrl(url) || AD_PATTERNS.some((re) => re.test(url));
}

const MEDIA_EXT = /\.(mp4|m4v|webm|ogv|ogg|mov|m3u8|mpd)(\?|#|$)/i;
const SEGMENT_EXT = /\.(ts|m4s|aac|m4a|mp3|vtt|key)(\?|#|$)/i;

function mediaKindFromUrl(url, contentType = '') {
  const ct = contentType.toLowerCase();
  // A trailing slash is allowed: KVS-based video sites write file links as ".../name.mp4/?token".
  if (/mpegurl/.test(ct) || /\.m3u8\/?(\?|#|$)/i.test(url)) return 'hls';
  if (/dash\+xml/.test(ct) || /\.mpd(\?|#|$)/i.test(url)) return 'dash';
  if (/^video\//.test(ct) || /\.(mp4|m4v|webm|ogv|mov)\/?(\?|#|$)/i.test(url)) return 'file';
  return null;
}

// Pages we can embed with a real player instead of sniffing their (DRM/MSE) streams.
function embedFromUrl(raw) {
  let u;
  try { u = new URL(raw); } catch { return null; }
  const host = u.hostname.replace(/^www\.|^m\./, '');
  if (host === 'youtube.com' || host === 'youtube-nocookie.com') {
    let id = null;
    if (u.pathname === '/watch') id = u.searchParams.get('v');
    else {
      const m = u.pathname.match(/^\/(?:embed|shorts|v|live)\/([\w-]{11})/);
      if (m) id = m[1];
    }
    if (id && /^[\w-]{11}$/.test(id)) return { provider: 'youtube', id };
  }
  if (host === 'youtu.be') {
    const id = u.pathname.slice(1, 12);
    if (/^[\w-]{11}$/.test(id)) return { provider: 'youtube', id };
  }
  if (host === 'vimeo.com' || host === 'player.vimeo.com') {
    const m = u.pathname.match(/(?:^|\/)(?:video\/)?(\d{5,})(?:\/|$)/);
    if (m) return { provider: 'vimeo', id: m[1] };
  }
  if (host === 'dailymotion.com' || host === 'dai.ly' || host === 'geo.dailymotion.com') {
    const m = u.pathname.match(/\/(?:embed\/)?video\/([a-z0-9]+)/i) || (host === 'dai.ly' && u.pathname.match(/\/([a-z0-9]+)/i));
    const id = (m && m[1]) || u.searchParams.get('video');
    if (id) return { provider: 'dailymotion', id };
  }
  return null;
}

// Sites excluded entirely — their pages, short links, embeds, video servers and thumbnails.
const BLOCKED_SITES = [
  // YouTube
  'youtube.com', 'youtu.be', 'youtube-nocookie.com', 'googlevideo.com', 'ytimg.com',
  // TikTok
  'tiktok.com', 'tiktokv.com', 'tiktokcdn.com', 'tiktokcdn-us.com', 'tiktokcdn-eu.com', 'tiktokw.us',
  'ttwstatic.com', 'ibytedtos.com', 'byteoversea.com', 'muscdn.com',
  // music videos
  'vevo.com',
];
const BLOCKED_HOST = new RegExp(`(^|\\.)(${BLOCKED_SITES.map((d) => d.replace(/[.-]/g, '\\$&')).join('|')})$`, 'i');
function isBlockedSite(url) {
  try { return BLOCKED_HOST.test(new URL(url).hostname); } catch { return false; }
}
/** A found video we refuse to play (YouTube, TikTok). */
function isBlockedVideo(v) {
  return v.provider === 'youtube' || isBlockedSite(v.src || '') || isBlockedSite(v.pageUrl || '');
}

// RedGIFs (opt-in, 18+). Its pages declare the *silent* copy of each video ("Name-silent.mp4");
// the same name without the suffix is the copy with sound.
function withSound(url) {
  return String(url).replace(/^(https?:\/\/media\.redgifs\.com\/[A-Za-z0-9]+)-silent\.mp4/i, '$1.mp4');
}

// ---------------------------------------------------------------- adult sites (opt-in, 18+)
// RedGIFs and the rule34 sites. They are only searched while the "Adult sites (18+)" switch is on,
// and their videos are hidden while it's off.
const ADULT_HOST = /(^|\.)(redgifs\.com|rule34video\.com|rule34\.xxx|paheal\.net|paheal-cdn\.net)$/i;
function isAdultSite(url) {
  try { return ADULT_HOST.test(new URL(url).hostname); } catch { return false; }
}

// Content depicting minors is ALWAYS excluded from adult sites — not a setting. Tags/words that
// mark it (also as part of underscore/dash compounds like "young_link" or "aged-down").
const AGE_TERMS = new Set(['loli', 'lolis', 'lolicon', 'shota', 'shotas', 'shotacon', 'toddlercon', 'child', 'children',
  'childs', 'kid', 'kids', 'toddler', 'toddlers', 'infant', 'infants', 'baby', 'babies', 'underage', 'preteen', 'preteens',
  'teen', 'teens', 'teenage', 'teenager', 'teenagers', 'young', 'younger', 'youngest', 'cub', 'cubs', 'minor', 'minors',
  'kindergarten', 'elementary', 'schoolgirl', 'schoolboy', 'agedown', 'ageplay', 'pedo', 'pedophile', 'cp']);
const AGE_PHRASES = /\b(middle[\s_-]?school|grade[\s_-]?school|primary[\s_-]?school|aged?[\s_-]?down|age[\s_-]?regression|little[\s_-]?(girl|boy|sister|brother))\b/i;
/** True if the text (tags, title, query…) marks content as depicting minors. */
function depictsMinors(text) {
  const t = String(text || '').toLowerCase();
  if (AGE_PHRASES.test(t)) return true;
  return t.split(/[^a-z0-9]+/).some((w) => AGE_TERMS.has(w));
}
/** Same terms as "minus" tags for booru-style searches (rule34.xxx, paheal). */
const AGE_NEGATIVE_TAGS = ['loli', 'lolicon', 'shota', 'shotacon', 'toddlercon', 'child', 'young', 'younger', 'underage', 'cub', 'teen'];

/**
 * Final gate for a found video: drop it if it's from an adult site while those are switched
 * off, or if anything about it (title, description, page text, tags) marks it as depicting minors.
 */
function dropAdult(item, hit, includeAdult) {
  const adult = isAdultSite(item.pageUrl || '') || isAdultSite(item.src || '');
  if (!adult) return false;
  if (!includeAdult) return true;
  return depictsMinors([item.title, item.description, item.pageTitle, item.pageText, hit && hit.title, hit && hit.snippet, item.pageUrl].join(' '));
}

// ---------------------------------------------------------------- unwanted content (not a setting)
// Music videos — including HMVs, PMVs and AMVs — everywhere; male-focus content on adult sites.
// Matched against titles, descriptions and tags (words, and underscore/dash compounds).
const MUSIC_WORDS = new Set(['hmv', 'pmv', 'amv', 'hmvs', 'pmvs', 'amvs', 'musicvideo', 'musicvideos']);
const MUSIC_PHRASES = /\b(music[\s_-]?videos?|official[\s_-]?(music[\s_-]?)?(video|mv|audio|clip)|lyrics?[\s_-]?videos?|visuali[sz]er|official[\s_-]?lyric)\b/i;
const MALE_WORDS = new Set(['yaoi', 'gay', 'bara', 'malefocus', 'maleonly', 'solomale', 'malesolo', 'allmale']);
const MALE_PHRASES = /\b(male[\s_-]?focus|males?[\s_-]?only|solo[\s_-]?male|male[\s_-]?solo|all[\s_-]?males?|gay[\s_-]?(sex|porn|couple)|male[\s_-]?masturbation|man[\s_-]?on[\s_-]?man)\b/i;
/** Why this text is unwanted ('music video' / 'male focus'), or null. Male focus only counts for adult sites. */
function unwantedContent(text, adult) {
  const t = String(text || '').toLowerCase();
  const words = t.split(/[^a-z0-9]+/);
  if (MUSIC_PHRASES.test(t) || words.some((w) => MUSIC_WORDS.has(w))) return 'music video';
  if (adult && (MALE_PHRASES.test(t) || words.some((w) => MALE_WORDS.has(w)))) return 'male focus';
  return null;
}
/** Extra booru minus-tags for unwanted content (rule34.xxx, paheal); the rest is filtered after. */
const UNWANTED_NEGATIVE_TAGS = ['pmv', 'hmv', 'male_focus', 'yaoi'];

function domainOf(url) {
  try { return new URL(url).hostname.replace(/^www\./, ''); } catch { return ''; }
}

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", '#39': "'", '#x27': "'", '#x2F': '/', nbsp: ' ' };
function decodeEntities(s) {
  return String(s)
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&([a-z0-9#]+);/gi, (m, n) => (n in ENTITIES ? ENTITIES[n] : m));
}

function stripTags(s) {
  return decodeEntities(String(s).replace(/<[^<>]{0,2000}>/g, ' ')).replace(/\s+/g, ' ').trim();
}

// ISO-8601 duration (PT1H2M3S) or plain seconds → seconds.
function parseDuration(d) {
  if (d == null) return null;
  if (typeof d === 'number') return d > 0 ? d : null;
  const s = String(d).trim();
  if (/^\d+(\.\d+)?$/.test(s)) return +s;
  const m = s.match(/^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/i);
  if (!m) return null;
  const secs = (+m[1] || 0) * 86400 + (+m[2] || 0) * 3600 + (+m[3] || 0) * 60 + (+m[4] || 0);
  return secs > 0 ? secs : null;
}

// Hostnames that resolve to this machine or a private network. Blocked in the proxy
// unless MEH_ALLOW_PRIVATE=1 (used by the local test suite).
function isPrivateHost(hostname) {
  const h = hostname.replace(/^\[|\]$/g, '').toLowerCase();
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h === '::1' || h === '0.0.0.0') return true;
  const m = h.match(/^(\d+)\.(\d+)\.(\d+)\.(\d+)$/);
  if (m) {
    const [a, b] = [+m[1], +m[2]];
    return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
  }
  return /^(fc|fd|fe80)/.test(h);
}

module.exports = {
  UA, isAdUrl, BLOCKED_SITES, isBlockedSite, isBlockedVideo, withSound,
  isAdultSite, depictsMinors, dropAdult, AGE_NEGATIVE_TAGS, unwantedContent, UNWANTED_NEGATIVE_TAGS, MEDIA_EXT, SEGMENT_EXT, mediaKindFromUrl, embedFromUrl, domainOf,
  decodeEntities, stripTags, parseDuration, isPrivateHost,
};

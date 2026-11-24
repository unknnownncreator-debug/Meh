'use strict';
// Relevance scoring: how well a found video matches what was typed.

const STOP = new Set('a an and are as at be by for from how i in is it of on or that the this to was what when where who why will with video videos watch'.split(' '));

function tokens(s) {
  return String(s || '').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '')
    .split(/[^a-z0-9]+/).filter((t) => t && !STOP.has(t));
}

// Crude stemmer so "volcanoes" matches "volcano", "erupting" matches "eruption"-ish.
function stem(t) {
  return t.length > 4 ? t.replace(/(ing|ed|es|s|ly)$/, '') : t;
}

function fieldScore(qStems, text) {
  const toks = tokens(text).map(stem);
  if (!toks.length) return 0;
  const set = new Set(toks);
  let hit = 0;
  for (const q of qStems) if (set.has(q) || toks.some((t) => t.length > 3 && q.length > 3 && (t.startsWith(q) || q.startsWith(t)))) hit++;
  return hit / qStems.length; // 0..1 coverage
}

/**
 * Score 0..100. Weighted coverage of query words in the video title, page title,
 * description, and surrounding page text, plus bonuses for exact phrase matches and
 * the original search-engine rank.
 */
function scoreItem(query, item, hit = {}) {
  const qStems = [...new Set(tokens(query).map(stem))];
  if (!qStems.length) return 50;
  const phrase = String(query).toLowerCase().trim();
  const title = item.title || '';
  let s = 0;
  s += 40 * fieldScore(qStems, title);
  s += 15 * fieldScore(qStems, item.pageTitle);
  s += 15 * fieldScore(qStems, item.description);
  s += 10 * fieldScore(qStems, item.pageText);
  s += 5 * fieldScore(qStems, decodeURIComponent(String(item.pageUrl || '')).replace(/[-_/]/g, ' '));
  if (phrase.length > 3 && title.toLowerCase().includes(phrase)) s += 8;
  // Engines already did relevance work; reward their top results a little.
  if (Number.isFinite(hit.rank)) s += Math.max(0, 7 - hit.rank * 0.35);
  // Very short = probably a teaser; very long = probably a livestream dump.
  if (item.duration && item.duration < 45) s -= 6;
  if (item.duration && item.duration > 3 * 3600) s -= 4;
  return Math.max(0, Math.min(100, Math.round(s * 10) / 10));
}

/**
 * Strict matching: does this video really match what was typed?
 *  - every query word must appear in the video's title, its page's title, its description, its
 *    keywords/tags, or its page address — NOT the rest of the page's text (that's where unrelated
 *    matches come from). On adult sites the page text is their tag list, so it counts there.
 *  - searches of 4+ words may miss one word;
 *  - "quoted phrases" must appear exactly.
 * `extra` adds text that counts (e.g. an adult site's tag list).
 */
function parseQuery(query) {
  const phrases = [...String(query).matchAll(/"([^"]+)"/g)].map((m) => norm(m[1])).filter(Boolean);
  const words = [...new Set(tokens(String(query).replace(/"/g, ' ')).map(stem))];
  return { words, phrases };
}

function norm(s) {
  return ' ' + String(s || '').toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, ' ').trim() + ' ';
}

function matchFields(item, hit = {}, extra = '') {
  let slug = '';
  try { slug = decodeURIComponent(new URL(item.pageUrl || '').pathname); } catch { /* no page */ }
  return [item.title, item.pageTitle, item.description, item.keywords, hit.title, hit.snippet, slug, extra].filter(Boolean).join(' \n ');
}

function matchesQuery(query, item, hit = {}, extra = '') {
  const { words, phrases } = parseQuery(query);
  if (!words.length && !phrases.length) return true;
  const text = matchFields(item, hit, extra);
  const toks = tokens(text).map(stem);
  const set = new Set(toks);
  // Word forms count ("volcano" ~ "volcanoes", "erupt" ~ "eruption"), different words don't
  // ("dragon" ≠ "dragonball"): a shared start with at most 3 extra letters.
  const close = (a, b) => a.length > 3 && b.length > 3 && Math.abs(a.length - b.length) <= 3 && (a.startsWith(b) || b.startsWith(a));
  const has = (q) => set.has(q) || toks.some((t) => close(t, q));
  const missing = words.filter((q) => !has(q)).length;
  if (missing > (words.length >= 4 ? 1 : 0)) return false;
  const n = norm(text);
  return phrases.every((p) => n.includes(p));
}

module.exports = { scoreItem, tokens, matchesQuery, parseQuery };

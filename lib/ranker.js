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

module.exports = { scoreItem, tokens };

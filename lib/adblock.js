'use strict';
// Ad blocker: ~43,000 ad-server domains (EasyList + core video-ad networks), from
// data/adblock-domains.txt (refresh with `node tools/update-adblock.js`).
// A host is blocked if it or any parent domain is listed (ads.example.com ← example.com).

const fs = require('fs');
const path = require('path');

let domains = null;
let blocked = 0;

function load() {
  if (domains) return domains;
  try {
    const text = fs.readFileSync(path.join(__dirname, '..', 'data', 'adblock-domains.txt'), 'utf8');
    domains = new Set(text.split('\n').map((l) => l.trim().toLowerCase()).filter((l) => l && !l.startsWith('#')));
  } catch {
    domains = new Set(); // list missing: the pattern-based filter still works
  }
  return domains;
}

function isListedHost(hostname) {
  const set = load();
  const parts = String(hostname).toLowerCase().replace(/\.$/, '').split('.');
  for (let i = 0; i < parts.length - 1; i++) if (set.has(parts.slice(i).join('.'))) return true;
  return false;
}

function isListedUrl(url) {
  try { return isListedHost(new URL(url).hostname); } catch { return false; }
}

module.exports = { isListedHost, isListedUrl, size: () => load().size, countBlocked: () => { blocked++; }, blockedCount: () => blocked };

#!/usr/bin/env node
'use strict';
// Refresh data/adblock-domains.txt from EasyList's ad-server list.
//   node tools/update-adblock.js            (downloads the latest list)
//   node tools/update-adblock.js FILE       (uses a local copy)
// Keeps only whole-domain rules ("||example.com^"), skipping exceptions, path rules and
// rules limited to particular sites, so it never blocks more than the list intends.
// EasyList is dual-licensed GPLv3 / CC BY-SA 3.0 (https://easylist.to).

const fs = require('fs');
const path = require('path');

const SOURCE = 'https://raw.githubusercontent.com/easylist/easylist/master/easylist/easylist_adservers.txt';
const OUT = path.join(__dirname, '..', 'data', 'adblock-domains.txt');
// Hosts the app itself relies on for real video. Never block them, whatever a list says.
const NEVER = /(^|\.)(vimeo\.com|vimeocdn\.com|dailymotion\.com|dmcdn\.net|dmxleo\.com|jwplayer\.com|jwpcdn\.com|jwplatform\.com|brightcove\.(com|net)|brightcovecdn\.com|kaltura\.com|cloudfront\.net|akamaihd\.net|akamaized\.net|fastly\.net|googleapis\.com|gstatic\.com|google\.com|bing\.com|duckduckgo\.com|jsdelivr\.net|cloudflare\.com|amazonaws\.com|archive\.org)$/i;

// Core video-ad and ad-tech infrastructure. EasyList blocks most of these with more detailed
// rules in its main list rather than in the ad-server list, so they're added explicitly.
const EXTRA = [
  // Google ads / IMA (most video pre-rolls run through these)
  'doubleclick.net', 'googlesyndication.com', 'googleadservices.com', 'googletagservices.com', 'imasdk.googleapis.com',
  '2mdn.net', 'adservice.google.com', 'pagead2.googlesyndication.com', 'securepubads.g.doubleclick.net',
  // video ad servers / SSPs
  'springserve.com', 'spotx.tv', 'spotxchange.com', 'freewheel.tv', 'fwmrm.net', 'innovid.com', 'tremorhub.com', 'teads.tv',
  'unrulymedia.com', 'connatix.com', 'primis.tech', 'aniview.com', 'vidazoo.com', 'ex.co', 'smartclip.net', 'stickyadstv.com',
  'videoadex.com', 'serving-sys.com', 'sizmek.com', 'adform.net', 'smartadserver.com', 'sascdn.com',
  // programmatic / verification / retargeting
  'adsrvr.org', 'amazon-adsystem.com', 'moatads.com', 'doubleverify.com', 'adsafeprotected.com', 'pubmatic.com',
  'rubiconproject.com', 'openx.net', 'criteo.com', 'criteo.net', 'taboola.com', 'outbrain.com', 'adnxs.com', 'yieldmo.com',
  'sharethrough.com', 'gumgum.com', '3lift.com', 'indexww.com', 'casalemedia.com', 'contextweb.com', 'lijit.com', 'sovrn.com',
  'media.net', 'adition.com', 'bidswitch.net', 'mathtag.com', 'adroll.com', 'advertising.com', 'yieldlab.net', 'turn.com',
  'scorecardresearch.com', 'quantserve.com', 'imrworldwide.com',
  // in-app / mobile ad SDKs that also show up in web players
  'adcolony.com', 'applovin.com', 'vungle.com', 'mopub.com', 'unityads.unity3d.com',
];

function parse(text) {
  const out = new Set();
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    const m = line.match(/^\|\|([a-z0-9.-]+\.[a-z]{2,})\^(\$(.*))?$/i);
    if (!m) continue;                                  // comments, exceptions (@@), path rules
    const opts = (m[3] || '').toLowerCase();
    if (/domain=|~|redirect|csp|removeparam/.test(opts)) continue; // conditional rules
    const host = m[1].toLowerCase().replace(/^\.+/, '');
    if (NEVER.test(host)) continue;
    out.add(host);
  }
  for (const d of EXTRA) out.add(d);
  return [...out].sort();
}

(async () => {
  const text = process.argv[2] ? fs.readFileSync(process.argv[2], 'utf8') : await (await fetch(SOURCE)).text();
  const domains = parse(text);
  if (domains.length < 1000) throw new Error(`only ${domains.length} domains parsed — refusing to overwrite`);
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, `# Ad-server domains for meh's ad blocker. ${domains.length} domains.\n` +
    `# Source: EasyList ad servers (${SOURCE}), ${new Date().toISOString().slice(0, 10)}.\n` +
    `# EasyList is dual-licensed GPLv3 / CC BY-SA 3.0 — https://easylist.to\n` +
    `# Regenerate with: node tools/update-adblock.js\n` + domains.join('\n') + '\n');
  console.log(`wrote ${domains.length} domains to ${path.relative(process.cwd(), OUT)}`);
})().catch((e) => { console.error(e.message); process.exit(1); });

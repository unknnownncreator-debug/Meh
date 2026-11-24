'use strict';
// Streams remote video through this server so sites that check Referer / block
// cross-origin players still play. HLS playlists are rewritten so every segment,
// key and sub-playlist also flows through the proxy.

const { Readable } = require('stream');
const { UA, isPrivateHost } = require('./util');
const { readCapped } = require('./search');
const cookies = require('./cookies');

const ALLOW_PRIVATE = process.env.MEH_ALLOW_PRIVATE === '1';
const PASS_HEADERS = ['content-type', 'content-length', 'content-range', 'accept-ranges', 'last-modified', 'etag'];

function proxyUrl(target, referer) {
  return `/proxy?u=${encodeURIComponent(target)}` + (referer ? `&r=${encodeURIComponent(referer)}` : '');
}

function rewritePlaylist(text, baseUrl, referer) {
  const abs = (u) => { try { return new URL(u, baseUrl).toString(); } catch { return u; } };
  return text.split(/\r?\n/).map((line) => {
    const trimmed = line.trim();
    if (!trimmed) return line;
    if (trimmed.startsWith('#')) {
      // URI="..." inside tags (EXT-X-KEY, EXT-X-MEDIA, EXT-X-MAP, ...)
      return line.replace(/URI="([^"]+)"/g, (_, u) => `URI="${proxyUrl(abs(u), referer)}"`);
    }
    return proxyUrl(abs(trimmed), referer);
  }).join('\n');
}

async function handleProxy(req, res, query) {
  const target = query.get('u');
  const referer = query.get('r') || '';
  let url;
  try { url = new URL(target); } catch { res.writeHead(400).end('bad url'); return; }
  if (!/^https?:$/.test(url.protocol) || (!ALLOW_PRIVATE && isPrivateHost(url.hostname))) {
    res.writeHead(403).end('forbidden'); return;
  }

  const headers = { 'User-Agent': UA, 'Accept': '*/*', 'Accept-Language': 'en-US,en;q=0.9' };
  if (referer) {
    headers.Referer = referer;
    try { headers.Origin = new URL(referer).origin; } catch { /* ignore */ }
  }
  if (req.headers.range) headers.Range = req.headers.range;
  // Same visitor as the page: session cookies from the shared jar.
  const cookie = cookies.header(url.toString());
  if (cookie) headers.Cookie = cookie;

  const ac = new AbortController();
  req.on('close', () => ac.abort());
  // Give up if the upstream doesn't even start answering (the body itself may stream for hours).
  const headerTimer = setTimeout(() => ac.abort(), +process.env.MEH_PROXY_TIMEOUT || 15000);
  let upstream;
  try {
    upstream = await fetch(url, { headers, redirect: 'follow', signal: ac.signal });
  } catch (e) {
    if (!res.headersSent) res.writeHead(504).end('upstream error');
    return;
  } finally {
    clearTimeout(headerTimer);
  }
  cookies.storeFrom(url.toString(), upstream);

  const ct = upstream.headers.get('content-type') || '';
  const isPlaylist = /mpegurl/i.test(ct) || /\.m3u8(\?|$)/i.test(upstream.url);
  if (isPlaylist && upstream.ok) {
    const text = await readCapped(upstream, 5 * 1024 * 1024).catch(() => '');
    if (text.trimStart().startsWith('#EXTM3U')) {
      const body = rewritePlaylist(text, upstream.url, referer);
      res.writeHead(200, { 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-cache' });
      res.end(body);
      return;
    }
    res.writeHead(upstream.status, { 'Content-Type': ct || 'text/plain' }).end(text);
    return;
  }

  const out = { 'Cache-Control': 'no-cache' };
  for (const h of PASS_HEADERS) {
    const v = upstream.headers.get(h);
    if (v) out[h] = v;
  }
  // fetch() transparently decompresses, so the upstream length may be wrong.
  if (upstream.headers.get('content-encoding')) delete out['content-length'];
  res.writeHead(upstream.status, out);
  if (!upstream.body) { res.end(); return; }
  Readable.fromWeb(upstream.body).on('error', () => res.destroy()).pipe(res);
}

module.exports = { handleProxy, rewritePlaylist, proxyUrl };

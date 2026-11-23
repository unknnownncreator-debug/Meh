'use strict';
// Meh — a video search engine. Searches the web, inspects result pages for video,
// ranks what it finds by relevance and streams the results to the player live.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { searchWeb, DEFAULT_ENGINES } = require('./lib/search');
const { inspectPage, browserFetch, shutdown } = require('./lib/inspector');
const { scoreItem } = require('./lib/ranker');
const { handleProxy, proxyUrl } = require('./lib/proxy');
const { isBlockedVideo, isRedgifs } = require('./lib/util');
const { blockedCount } = require('./lib/adblock');

const PORT = +process.env.PORT || 3000;
const HOST = process.env.HOST || '127.0.0.1';
const CONCURRENCY = +process.env.MEH_CONCURRENCY || 5;
const MAX_PAGES = +process.env.MEH_MAX_PAGES || 40;
// Testing hook: when MEH_TEST_PAGES is set, skip web search and inspect these URLs
// instead (comma-separated). In that mode a request may also pass its own ?testpages=.
function testPages(query) {
  if (process.env.MEH_TEST_PAGES === undefined) return null;
  const list = (query.get('testpages') || process.env.MEH_TEST_PAGES).split(',').filter(Boolean);
  return list.length ? list : null;
}

const PUBLIC = path.join(__dirname, 'public');
const STATIC = {
  '/vendor/hls.min.js': require.resolve('hls.js/dist/hls.min.js'),
};
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };

function serveStatic(req, res, pathname) {
  let file = STATIC[pathname];
  if (!file) {
    const rel = pathname === '/' ? '/index.html' : pathname;
    file = path.join(PUBLIC, path.normalize(rel).replace(/^(\.\.[/\\])+/, ''));
    if (!file.startsWith(PUBLIC)) { res.writeHead(403).end(); return; }
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      // Single-page app: unknown paths get the app shell.
      if (!path.extname(pathname)) return serveStatic(req, res, '/');
      res.writeHead(404).end('not found'); return;
    }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(data);
  });
}

async function handleSearch(req, res, query) {
  const q = (query.get('q') || '').trim().slice(0, 200);
  const includeRedgifs = query.get('redgifs') === '1';
  const page = Math.max(0, Math.min(10, +query.get('page') || 0));
  if (!q) { res.writeHead(400).end('missing q'); return; }

  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive', 'X-Accel-Buffering': 'no' });
  const ac = new AbortController();
  req.on('close', () => ac.abort());
  const send = (event, data) => { if (!ac.signal.aborted) res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`); };
  const heartbeat = setInterval(() => send('ping', {}), 15000);

  const stats = { engines: {}, pagesTotal: 0, pagesDone: 0, found: 0 };
  const adsBefore = blockedCount();
  const status = (message) => send('status', { message, ...stats });
  const seenMedia = new Set();

  const emit = (item, hit) => {
    if (isBlockedVideo(item)) return; // excluded sites (YouTube, TikTok)
    if (!includeRedgifs && (isRedgifs(item.pageUrl || '') || isRedgifs(item.src))) return; // RedGIFs switched off
    const key = item.kind === 'embed' ? `${item.provider}:${item.id}` : item.src.replace(/[?#].*$/, '');
    if (seenMedia.has(key)) return;
    seenMedia.add(key);
    stats.found++;
    const { pageText, ...rest } = item;
    send('item', {
      ...rest,
      key,
      score: scoreItem(q, item, hit),
      playUrl: item.kind === 'embed' ? null : proxyUrl(item.src, item.referer),
    });
  };

  try {
    status('Searching the web…');
    const TEST_PAGES = testPages(query);
    const hits = TEST_PAGES
      ? TEST_PAGES.map((url, rank) => ({ url, title: '', snippet: '', engine: 'test', rank }))
      : await searchWeb(q, {
        page, browserFetch, includeRedgifs,
        engines: includeRedgifs ? [...DEFAULT_ENGINES, 'redgifs'] : DEFAULT_ENGINES,
        onEngine: (name, n, ms) => { stats.engines[name] = n; status(`${name}: ${n} results (${ms} ms)`); },
      });
    const queue = hits.slice(0, MAX_PAGES);
    stats.pagesTotal = queue.length;
    status(queue.length ? `Inspecting ${queue.length} pages for video…` : 'No search results. The search engines may be blocking requests — try again in a moment.');

    const toInspect = queue;

    let next = 0;
    const worker = async () => {
      while (next < toInspect.length && !ac.signal.aborted) {
        const hit = toInspect[next++];
        try {
          const items = await inspectPage(hit, { signal: ac.signal });
          for (const it of items) emit(it, hit);
        } catch { /* page failed — skip */ }
        stats.pagesDone++;
        status(`Inspected ${stats.pagesDone}/${stats.pagesTotal} pages · ${stats.found} videos found`);
      }
    };
    await Promise.all(Array.from({ length: CONCURRENCY }, worker));
    stats.adsBlocked = blockedCount() - adsBefore;
    send('done', { ...stats, page, hasMore: !TEST_PAGES && hits.length > 0 && page < 10 });
  } catch (e) {
    send('fail', { message: String(e && e.message || e) });
  } finally {
    clearInterval(heartbeat);
    res.end();
  }
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://localhost');
  try {
    if (u.pathname === '/api/search') return await handleSearch(req, res, u.searchParams);
    if (u.pathname === '/proxy') return await handleProxy(req, res, u.searchParams);
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405).end(); return; }
    serveStatic(req, res, u.pathname);
  } catch (e) {
    if (!res.headersSent) res.writeHead(500).end('server error');
    else res.destroy();
  }
});

if (require.main === module) {
  server.listen(PORT, HOST, () => console.log(`Meh is running → http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}`));
  const stop = async () => { await shutdown(); process.exit(0); };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

module.exports = { server };

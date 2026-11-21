'use strict';
// Stress test: a hostile local "web" (hanging servers, endless pages, JS infinite loops,
// redirect loops, popups, giant/deeply-nested data, binary junk) plus fuzzing, concurrent
// searches, client disconnects and proxy abuse. Everything must finish, in bounded time
// and memory, and still find the real videos.
// Run: npm run stress   (needs ffmpeg for test media)

const assert = require('assert/strict');
const http = require('http');
const { startFixtures } = require('./fixtures');

let passed = 0;
async function step(name, fn) {
  process.stdout.write(`• ${name} … `);
  const t0 = Date.now();
  await fn();
  passed++;
  console.log(`ok (${((Date.now() - t0) / 1000).toFixed(1)}s)`);
}

async function readSse(url, { abortAfterMs } = {}) {
  const ac = new AbortController();
  if (abortAfterMs) setTimeout(() => ac.abort(), abortAfterMs);
  const events = [];
  try {
    const res = await fetch(url, { signal: ac.signal });
    let buf = '';
    const dec = new TextDecoder();
    for await (const chunk of res.body) {
      buf += dec.decode(chunk, { stream: true });
      let i;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, i); buf = buf.slice(i + 2);
        const ev = (block.match(/^event: (.*)$/m) || [])[1];
        const data = (block.match(/^data: (.*)$/m) || [])[1];
        if (ev) events.push({ event: ev, data: JSON.parse(data) });
      }
    }
  } catch (e) { if (!abortAfterMs) throw e; }
  return events;
}

// ------------------------------------------------------------------ the hostile web
const served = { bigmedia: 0, requests: 0 };
function hostile() {
  const page = (body, head = '') => `<!doctype html><html><head><title>volcano test</title>${head}</head><body>${body}</body></html>`;
  const routes = {
    '/hang': () => {},                                   // never answers
    '/drip': (req, res) => {                              // 1 byte every 200 ms, forever
      res.writeHead(200, { 'Content-Type': 'text/html' });
      const t = setInterval(() => res.write('<'), 200);
      req.on('close', () => clearInterval(t));
    },
    '/endless': (req, res) => {                           // endless HTML as fast as possible
      res.writeHead(200, { 'Content-Type': 'text/html' });
      const chunk = Buffer.alloc(64 * 1024, '<p>volcano lava ');
      let open = true;
      req.on('close', () => { open = false; });
      (function pump() { while (open && res.write(chunk)); if (open) res.once('drain', pump); })();
    },
    '/huge-with-video': (req, res) => {                   // 25 MB page, video declared up front
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.write(`<html><head><title>Huge volcano page</title><meta property="og:video" content="${F}/media/eruption.webm"></head><body>`);
      res.end('<div>lava</div>'.repeat(25 * 1024 * 1024 / 15));
    },
    '/redirect-loop': (req, res) => { res.writeHead(302, { Location: '/redirect-loop' }); res.end(); },
    '/error500': (req, res) => { res.writeHead(500); res.end('boom'); },
    '/binary': (req, res) => { res.writeHead(200, { 'Content-Type': 'text/html' }); res.end(require('crypto').randomBytes(500_000)); },
    '/js-loop': () => page('<script>while (true) {}</script>'),
    // Starts an HLS stream, then locks up: what was sniffed before the lock must survive.
    '/js-loop-after-media': () => page(`<script>
      fetch('/media/stream/index.m3u8?late=1');
      setTimeout(function(){ while (true) {} }, 400);</script>`),
    '/popups': () => page(`<script>
      alert('hi'); confirm('sure?'); window.open('/popups'); window.open('/js-loop');
      setTimeout(function(){ prompt('x'); }, 100);
      window.onbeforeunload = function(){ return 'stay'; };
      </script><video src="/media/formation.webm" width="640" height="360"></video>`),
    '/navigates-away': () => page(`<script>setTimeout(function(){ location.href = '/redirect-loop'; }, 300);</script>`),
    '/many-videos': () => page(Array.from({ length: 300 }, (_, i) =>
      `<video src="/media/clip-${i}.webm" width="${i === 150 ? 800 : 100}" height="${i === 150 ? 450 : 60}"></video>`).join('')),
    '/deep-json': () => page('', `<script type="application/ld+json">${'['.repeat(200000)}${']'.repeat(200000)}</script>
      <script type="application/ld+json">${'{"a":'.repeat(5000)}{"@type":"VideoObject","name":"deep","contentUrl":"/media/deep.webm"}${'}'.repeat(5000)}</script>`),
    '/broken-json': () => page('', `<script type="application/ld+json">{"@type":"VideoObject", "contentUrl": </script>
      <meta property="og:video" content="javascript:alert(1)"><meta property="og:video:url" content="data:video/mp4;base64,AAAA">`),
    // A real-looking page whose <video> is an endless "file": sniffing must not download it.
    '/big-media-page': () => page('<video autoplay muted width="640" height="360" src="/bigmedia.mp4"></video>'),
    // Same, but the player is created by script, so only the live browser can find it.
    '/big-media-js': () => page(`<div id="p"></div><script>
      var v = document.createElement('video'); v.width = 640; v.height = 360; v.muted = true; v.autoplay = true;
      v.src = '/bigmedia-js.mp4'; document.getElementById('p').appendChild(v);</script>`),
    '/bigmedia.mp4': (req, res) => {
      res.writeHead(200, { 'Content-Type': 'video/mp4' });
      let open = true;
      req.on('close', () => { open = false; });
      const chunk = Buffer.alloc(256 * 1024);
      (function pump() { while (open) { served.bigmedia += chunk.length; if (!res.write(chunk)) return res.once('drain', pump); } })();
    },
    '/bigmedia-js.mp4': (req, res) => routes['/bigmedia.mp4'](req, res),
    '/good': () => page('<h1>Volcano eruption</h1><video controls width="640" height="360" src="/media/good-eruption.webm"></video>',
      '<meta property="og:title" content="Volcano eruption up close">'),
  };
  let F;
  const server = http.createServer((req, res) => {
    served.requests++;
    const path = new URL(req.url, 'http://x').pathname;
    const r = routes[path];
    if (!r) { proxyToFixtures(req, res); return; }
    const out = r(req, res);
    if (typeof out === 'string') { res.writeHead(200, { 'Content-Type': 'text/html' }); res.end(out); }
  });
  server.keepAliveTimeout = 1000;
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => {
    F = `http://127.0.0.1:${server.address().port}`;
    resolve({ server, F, paths: Object.keys(routes).filter((p) => !p.endsWith('.mp4')) });
  }));
}
let fixturesBase;
function proxyToFixtures(req, res) {
  const up = http.request(fixturesBase + req.url, { headers: req.headers }, (r) => { res.writeHead(r.statusCode, r.headers); r.pipe(res); });
  up.on('error', () => { res.writeHead(502); res.end(); });
  up.end();
}

// ------------------------------------------------------------------ fuzz helpers
function rand(n) { return Math.floor(Math.random() * n); }
const PIECES = ['<video', '<video src="/a.mp4">', '</video>', '<source src="x.m3u8" type="application/x-mpegURL">', '<meta property="og:video" content="',
  '"', "'", '>', '<', '<script type="application/ld+json">', '</script>', '{"@type":"VideoObject","contentUrl":', '[', ']', '{', '}', '&amp;', '&#x1F30B;',
  '<iframe src="https://www.youtube.com/embed/', 'dQw4w9WgXcQ', '"></iframe>', '<title>', '</title>', 'volcano', '\u0000', '￿', 'é', '🌋', ' ', '\n',
  '<body>', '<!--', '-->', 'PT1H', 'https://', 'blob:', '<style>', '</style>', '"videoRenderer":{"videoId":"', '"murl":"', '\\', '\\"'];
function junk(len) { let s = ''; while (s.length < len) s += PIECES[rand(PIECES.length)]; return s; }

(async () => {
  const fixtures = await startFixtures();
  fixturesBase = `http://127.0.0.1:${fixtures.address().port}`;
  const { server: hostileServer, F, paths } = await hostile();
  process.env.MEH_ALLOW_PRIVATE = '1';
  process.env.MEH_PROXY_TIMEOUT = '3000';
  process.env.MEH_TEST_PAGES = paths.map((p) => F + p).join(',');
  process.env.MEH_MAX_PAGES = '100';

  const { server } = require('../server');
  const inspector = require('../lib/inspector');
  const { parseStaticHtml, candidatesFromProbe } = inspector;
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const M = `http://127.0.0.1:${server.address().port}`;
  const rss0 = process.memoryUsage().rss;

  // ---------------------------------------------------------------- parser fuzzing / pathological input
  await step('fuzz: 3,000 random junk pages through every parser — no crashes', async () => {
    const { ENGINES } = require('../lib/search');
    const { scoreItem } = require('../lib/ranker');
    const realFetch = global.fetch;
    let current = '';
    global.fetch = async () => new Response(current, { status: 200, headers: { 'content-type': 'text/html' } });
    try {
      for (let i = 0; i < 3000; i++) {
        current = junk(200 + rand(6000));
        const p = parseStaticHtml(current, 'https://fuzz.test/p');
        candidatesFromProbe(p);
        scoreItem(junk(30), { title: junk(50), pageTitle: junk(20), description: current.slice(0, 300) });
        if (i % 25 === 0) for (const e of Object.values(ENGINES)) await e('volcano', 0);
      }
    } finally { global.fetch = realFetch; }
  });

  await step('pathological HTML parses quickly (unclosed tags, 3 MB)', async () => {
    const cases = {
      'unclosed <video> ×20k': '<video '.repeat(20000) + 'x'.repeat(2_000_000),
      'unclosed <script> ×20k': '<script>'.repeat(20000) + 'x'.repeat(2_000_000),
      'unclosed <meta ×50k': '<meta property="og:video" content="'.repeat(50000),
      '3 MB of attributes': '<div ' + 'a="b" '.repeat(500000) + '>',
    };
    for (const [name, html] of Object.entries(cases)) {
      const t0 = Date.now();
      candidatesFromProbe(parseStaticHtml(html, 'https://x.test/'));
      const ms = Date.now() - t0;
      assert.ok(ms < 4000, `${name} took ${ms} ms`);
    }
  });

  // ---------------------------------------------------------------- the hostile search
  let items;
  await step(`hostile web: ${paths.length} nasty pages in one search finish in bounded time`, async () => {
    const t0 = Date.now();
    const events = await readSse(`${M}/api/search?q=volcano`);
    const secs = (Date.now() - t0) / 1000;
    assert.ok(events.find((e) => e.event === 'done'), 'search completed');
    items = events.filter((e) => e.event === 'item').map((e) => e.data);
    const by = Object.fromEntries(items.map((i) => [new URL(i.pageUrl).pathname, i]));
    console.log(`\n    ${items.length} videos from ${paths.length} pages in ${secs.toFixed(1)}s`);
    for (const i of items) console.log(`      ${new URL(i.pageUrl).pathname.padEnd(22)} ${i.kind.padEnd(5)} via ${i.via.padEnd(7)} ${i.src.replace(F, '').replace(fixturesBase, '').slice(0, 50)}`);
    process.stdout.write('    ');
    assert.ok(secs < 75, `took ${secs}s`);
    assert.ok(by['/good'], 'normal page still found');
    assert.ok(by['/huge-with-video'], 'video declared at the top of a 25 MB page');
    assert.ok(by['/popups'], 'page spamming alerts/popups still inspected');
    assert.equal(by['/js-loop-after-media'] && by['/js-loop-after-media'].kind, 'hls', 'media sniffed before the page locked up');
    assert.ok(by['/big-media-js'] && ['dom', 'network'].includes(by['/big-media-js'].via), 'script-created player found by the live browser');
    assert.match(by['/many-videos'].src, /clip-150\.webm/, 'picks the big real player out of 300 tiny ones');
    for (const bad of ['/hang', '/drip', '/endless', '/redirect-loop', '/error500', '/binary', '/js-loop', '/navigates-away', '/broken-json']) {
      assert.ok(!by[bad], `${bad} should yield nothing`);
    }
  });

  await step('sniffing never downloads whole videos', async () => {
    const mb = served.bigmedia / 1e6;
    assert.ok(mb < 20, `endless video page pulled ${mb.toFixed(1)} MB`);
  });

  await step('memory stays bounded after the hostile run', async () => {
    global.gc && global.gc();
    const grew = (process.memoryUsage().rss - rss0) / 1e6;
    console.log(`\n    RSS grew ${grew.toFixed(0)} MB`); process.stdout.write('    ');
    assert.ok(grew < 600, `RSS grew ${grew} MB`);
  });

  // ---------------------------------------------------------------- concurrency
  await step('8 searches at once on fresh pages all complete', async () => {
    const pages = ['static-video', 'jsonld', 'js-player', 'embed', 'ad-only', 'no-video'];
    process.env.MEH_TEST_PAGES = '';
    const results = await Promise.all(Array.from({ length: 8 }, (_, n) => {
      // Unique query strings → unique URLs → no inspection cache hits.
      const urls = pages.map((p) => `${fixturesBase}/${p}.html?run=${n}`).join(',');
      return readSse(`${M}/api/search?q=volcano&testpages=${encodeURIComponent(urls)}`);
    }));
    for (const ev of results) {
      assert.ok(ev.find((e) => e.event === 'done'), 'completed');
      assert.equal(ev.filter((e) => e.event === 'item').length, 4, 'all 4 videos found');
    }
  });

  await step('closing the page mid-search stops the work', async () => {
    const urls = Array.from({ length: 30 }, (_, i) => `${F}/js-loop?abort=${i}`).join(',');
    const before = served.requests;
    await readSse(`${M}/api/search?q=volcano&testpages=${encodeURIComponent(urls)}`, { abortAfterMs: 1500 });
    await new Promise((r) => setTimeout(r, 4000));
    const mid = served.requests;
    await new Promise((r) => setTimeout(r, 6000));
    const after = served.requests;
    console.log(`\n    requests: ${mid - before} before abort settled, ${after - mid} after`); process.stdout.write('    ');
    assert.ok(after - mid <= 6, `work kept going after disconnect (${after - mid} more page requests)`);
    assert.ok(mid - before < 30 * 2, 'did not inspect all 30 pages');
  });

  // ---------------------------------------------------------------- proxy abuse
  await step('proxy: 60 concurrent range requests, aborted clients, dead upstreams', async () => {
    const file = items.find((i) => /eruption\.webm/.test(i.src));
    const reqs = Array.from({ length: 60 }, async (_, i) => {
      const ac = new AbortController();
      if (i % 3 === 0) setTimeout(() => ac.abort(), 20); // a third of clients vanish mid-stream
      try {
        const r = await fetch(M + file.playUrl, { headers: { Range: `bytes=${i * 100}-${i * 100 + 999}` }, signal: ac.signal });
        const b = await r.arrayBuffer();
        return [r.status, b.byteLength];
      } catch { return ['aborted', 0]; }
    });
    const out = await Promise.all(reqs);
    for (const [st, len] of out) if (st !== 'aborted') { assert.equal(st, 206); assert.equal(len, 1000); }

    const t0 = Date.now();
    const hung = await fetch(`${M}/proxy?u=${encodeURIComponent(F + '/hang')}`);
    assert.equal(hung.status, 504, 'hung upstream → 504');
    assert.ok(Date.now() - t0 < 6000);
    const bad = await Promise.all(['not a url', 'file:///etc/passwd', 'ftp://x/y', 'javascript:alert(1)'].map((u) => fetch(`${M}/proxy?u=${encodeURIComponent(u)}`).then((r) => r.status)));
    assert.ok(bad.every((s) => s === 400 || s === 403), `bad urls → ${bad}`);
    const missing = await fetch(`${M}/proxy`);
    assert.equal(missing.status, 400);
  });

  await step('weird queries: empty, huge, unicode, injection', async () => {
    assert.equal((await fetch(`${M}/api/search?q=`)).status, 400);
    for (const q of ['🌋'.repeat(500), '"><script>alert(1)</script>', 'a'.repeat(10000), '%00\u0000\r\n']) {
      const r = await fetch(`${M}/api/search?q=${encodeURIComponent(q)}&testpages=${encodeURIComponent(fixturesBase + '/no-video.html')}`);
      assert.equal(r.status, 200);
      await r.text();
    }
  });

  console.log(`\n${passed} stress checks passed`);
  await inspector.shutdown();
  server.close(); fixtures.close(); hostileServer.close();
  process.exit(0);
})().catch((e) => { console.log('FAILED'); console.error(e); process.exit(1); });

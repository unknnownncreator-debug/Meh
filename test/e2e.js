'use strict';
// End-to-end test: fixture site → page inspection → ranking → proxy → real browser playback.
// Run: npm test   (needs ffmpeg for generating test media)

const assert = require('assert/strict');
const path = require('path');
const fs = require('fs');
const { startFixtures } = require('./fixtures');

let passed = 0;
async function step(name, fn) {
  process.stdout.write(`• ${name} … `);
  await fn();
  passed++;
  console.log('ok');
}

function readSse(url) {
  return new Promise(async (resolve, reject) => {
    const res = await fetch(url);
    const events = [];
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
    resolve(events);
  });
}

(async () => {
  const fixtures = await startFixtures();
  const F = `http://127.0.0.1:${fixtures.address().port}`;
  const pages = ['static-video', 'jsonld', 'js-player', 'embed', 'ad-only', 'no-video'].map((p) => `${F}/${p}.html`);
  process.env.MEH_ALLOW_PRIVATE = '1';
  process.env.MEH_TEST_PAGES = pages.join(',');

  const { server } = require('../server');
  const { shutdown } = require('../lib/inspector');
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const M = `http://127.0.0.1:${server.address().port}`;

  // ---------------------------------------------------------------- unit checks
  const { embedFromUrl, parseDuration, isAdUrl } = require('../lib/util');
  const { cleanHitUrl, ENGINES } = require('../lib/search');
  const { rewritePlaylist } = require('../lib/proxy');
  const { scoreItem } = require('../lib/ranker');

  await step('recognises embeddable players', () => {
    assert.deepEqual(embedFromUrl('https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3'), { provider: 'youtube', id: 'dQw4w9WgXcQ' });
    assert.deepEqual(embedFromUrl('https://youtu.be/dQw4w9WgXcQ'), { provider: 'youtube', id: 'dQw4w9WgXcQ' });
    assert.deepEqual(embedFromUrl('https://vimeo.com/76979871'), { provider: 'vimeo', id: '76979871' });
    assert.deepEqual(embedFromUrl('https://www.dailymotion.com/video/x8abc12'), { provider: 'dailymotion', id: 'x8abc12' });
    assert.equal(embedFromUrl('https://example.com/watch?v=x'), null);
  });

  await step('parses durations and spots ad URLs', () => {
    assert.equal(parseDuration('PT1H2M3S'), 3723);
    assert.equal(parseDuration('95'), 95);
    assert.ok(isAdUrl('https://pubads.g.doubleclick.net/x.mp4'));
    assert.ok(isAdUrl('https://cdn.site.com/ads/preroll.mp4'));
    assert.ok(!isAdUrl('https://cdn.site.com/videos/volcano.mp4'));
  });

  await step('decodes search-engine redirect links', () => {
    assert.equal(cleanHitUrl('//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fv%3Fa%3D1&amp;rut=abc'), 'https://example.com/v?a=1');
    const b64 = Buffer.from('https://news.example.org/story').toString('base64').replace(/=+$/, '');
    assert.equal(cleanHitUrl(`https://www.bing.com/ck/a?!&&p=xyz&u=a1${b64}&ntb=1`), 'https://news.example.org/story');
    assert.equal(cleanHitUrl('https://www.bing.com/images/search?q=x'), null);
  });

  await step('parses search result pages (DuckDuckGo, Bing, Bing Videos, YouTube)', async () => {
    const realFetch = global.fetch;
    const pad = '<!--' + 'x'.repeat(2500) + '-->';
    const html = {
      'html.duckduckgo.com': `${pad}<div class="result results_links"><h2><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fa.com%2Fvolcano">Volcano <b>video</b></a></h2><a class="result__snippet" href="#">Lava!</a></div>`,
      'www.bing.com/search': `${pad}<li class="b_algo"><h2><a href="https://b.org/eruption" h="ID=1">Eruption</a></h2><p>Big one</p></li>`,
      'www.bing.com/videos': `${pad}<div class="vrhdata" vrhm='{&quot;du&quot;:&quot;4:13&quot;,&quot;murl&quot;:&quot;https://c.net/clip/9&quot;,&quot;vt&quot;:&quot;Volcano clip&quot;}'></div>`,
      'www.youtube.com': `${pad}var ytInitialData = {"videoRenderer":{"videoId":"abcdefghijk","thumbnail":{},"title":{"runs":[{"text":"Volcano \\u0026 lava"}]},"lengthText":{"accessibility":{},"simpleText":"12:05"}}};`,
    };
    global.fetch = async (url) => {
      const key = Object.keys(html).find((k) => String(url).includes(k));
      return new Response(key ? html[key] : '', { status: key ? 200 : 404, headers: { 'content-type': 'text/html' } });
    };
    try {
      const ddg = await ENGINES.duckduckgo('volcano', 0);
      assert.deepEqual([ddg[0].url, ddg[0].title, ddg[0].snippet], ['https://a.com/volcano', 'Volcano video', 'Lava!']);
      const bing = await ENGINES.bing('volcano', 0);
      assert.equal(bing[0].url, 'https://b.org/eruption');
      const bv = await ENGINES.bingVideos('volcano', 0);
      assert.deepEqual([bv[0].url, bv[0].title, bv[0].duration], ['https://c.net/clip/9', 'Volcano clip', 253]);
      const yt = await ENGINES.youtube('volcano', 0);
      assert.equal(yt[0].media.id, 'abcdefghijk');
      assert.equal(yt[0].title, 'Volcano & lava');
      assert.equal(yt[0].duration, 725);
    } finally {
      global.fetch = realFetch;
    }
  });

  await step('parses REAL saved search pages (Bing, Bing Videos, YouTube, DuckDuckGo bot check)', async () => {
    const zlib = require('zlib');
    const real = (n) => zlib.gunzipSync(fs.readFileSync(path.join(__dirname, 'fixtures', 'real', n + '.html.gz'))).toString('utf8');
    const realFetch = global.fetch;
    const map = { 'html.duckduckgo.com': ['ddg', 202], 'www.bing.com/search': ['bing', 200], 'www.bing.com/videos': ['bingv', 200], 'www.youtube.com': ['yt', 200] };
    global.fetch = async (url) => {
      const [name, status] = map[Object.keys(map).find((k) => String(url).includes(k))];
      return new Response(real(name), { status, headers: { 'content-type': 'text/html' } });
    };
    try {
      const bing = await ENGINES.bing('volcano', 0);
      assert.ok(bing.length >= 8 && bing.every((h) => /^https:\/\//.test(h.url) && h.title), `bing ${bing.length}`);
      const bv = await ENGINES.bingVideos('volcano eruption', 0);
      assert.ok(bv.length >= 8, `bing videos ${bv.length}`);
      const yt = await ENGINES.youtube('volcano', 0);
      assert.ok(yt.length >= 15 && yt.every((h) => /^[\w-]{11}$/.test(h.media.id) && h.title), `youtube ${yt.length}`);
      assert.ok(yt.filter((h) => h.duration).length >= 10, 'youtube durations');
      // DuckDuckGo's bot check (202) must fall back to the real browser, not silently give 0.
      let browserCalls = 0;
      const ddg = await ENGINES.duckduckgo('volcano', 0, async () => { browserCalls++; return ''; });
      assert.equal(ddg.length, 0);
      assert.equal(browserCalls, 1, 'fell back to the browser');
      const parity = { bing: bing.length, bingVideos: bv.length, youtube: yt.length, youtubeFirst: yt[0].media.id, bingFirst: bing[0].url };
      fs.writeFileSync(path.join(__dirname, 'fixtures', 'real', 'expected.json'), JSON.stringify(parity, null, 2));
    } finally {
      global.fetch = realFetch;
    }
  });

  await step('parses Google video results (modern + legacy markup, bot check, browser retry)', async () => {
    const { parseGoogle } = require('../lib/search');
    const modern = `<html><body>
      <a href="https://www.google.com/search?q=volcano&tbm=isch">Images</a>
      <a href="https://accounts.google.com/ServiceLogin">Sign in</a>
      <a href="/search?q=volcano+lava&tbm=vid">volcano lava</a>
      <a href="https://support.google.com/websearch">Help with search results</a>
      <div class="g"><a href="https://www.youtube.com/watch?v=abcdefghijk" jsname="UWckNb" data-ved="x"><h3 class="LC20lb">Volcano eruption in 4K</h3><cite>www.youtube.com</cite></a><span>12:34</span></div>
      <div class="g"><a href="https://www.nationalgeographic.com/video/volcano-101" data-ved="y"><div role="heading" aria-level="3">Volcanoes 101 &amp; how they erupt</div><div>Nat Geo · 3:05</div></a></div>
      <div class="g"><a href="https://vimeo.com/76979871" aria-label="Lava flow timelapse, from Vimeo"><img src="x.jpg"></a></div>
      <div class="g"><a href="https://www.youtube.com/watch?v=abcdefghijk"><h3>Volcano eruption in 4K</h3></a></div>
    </body></html>`;
    const hits = parseGoogle(modern);
    assert.deepEqual(hits.map((h) => h.url), ['https://www.youtube.com/watch?v=abcdefghijk', 'https://www.nationalgeographic.com/video/volcano-101', 'https://vimeo.com/76979871']);
    assert.deepEqual(hits.map((h) => h.title), ['Volcano eruption in 4K', 'Volcanoes 101 & how they erupt', 'Lava flow timelapse, from Vimeo']);
    assert.equal(hits[1].duration, 185);

    const legacy = '<div><a href="/url?q=https://www.bbc.co.uk/news/av/volcano&amp;sa=U&amp;ved=2ah"><h3><div>Volcano erupts in Iceland - BBC News</div></h3></a>' +
      '<a href="/url?q=https://maps.google.com/x&amp;sa=U"><h3>Maps result</h3></a></div>';
    assert.deepEqual(parseGoogle(legacy).map((h) => h.url), ['https://www.bbc.co.uk/news/av/volcano']);

    const zlib = require('zlib');
    const sorry = zlib.gunzipSync(fs.readFileSync(path.join(__dirname, 'fixtures', 'real', 'google-sorry.html.gz'))).toString('utf8');
    assert.equal(parseGoogle(sorry).length, 0, 'real Google bot-check page');
    assert.equal(parseGoogle('<html><form id="captcha-form"></form><a href="https://x.com/v"><h3>Something long enough</h3></a></html>').length, 0);

    // Plain request gets the "enable JavaScript" page → must retry in the browser.
    const realFetch = global.fetch;
    global.fetch = async () => new Response('<html><noscript>Please click <a href="/search?q=v&emsg=SG_REL">here</a> if you are not redirected. enablejs</noscript>' + 'x'.repeat(3000) + '</html>', { status: 200, headers: { 'content-type': 'text/html' } });
    try {
      let calls = 0;
      const got = await ENGINES.google('volcano', 0, async (url) => { calls++; assert.match(url, /google\.com\/search\?q=volcano&tbm=vid/); return modern; });
      assert.equal(calls, 1, 'retried in the browser');
      assert.equal(got.length, 3);
    } finally {
      global.fetch = realFetch;
    }
  });

  await step('rewrites HLS playlists through the proxy', () => {
    const out = rewritePlaylist('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\nseg0.ts\nhttps://cdn.x/seg1.ts', 'https://h.com/v/index.m3u8', 'https://h.com/page');
    assert.match(out, /URI="\/proxy\?u=https%3A%2F%2Fh\.com%2Fv%2Fkey\.bin&r=/);
    assert.match(out, /\n\/proxy\?u=https%3A%2F%2Fh\.com%2Fv%2Fseg0\.ts/);
    assert.match(out, /\n\/proxy\?u=https%3A%2F%2Fcdn\.x%2Fseg1\.ts/);
  });

  await step('ranks relevant videos higher', () => {
    const a = scoreItem('volcano eruption', { title: 'Huge volcano eruption filmed', pageTitle: '', description: '' });
    const b = scoreItem('volcano eruption', { title: 'Cooking pasta at home', pageTitle: '', description: '' });
    assert.ok(a > b + 30, `${a} vs ${b}`);
  });

  // ---------------------------------------------------------------- search stream + inspection
  let items;
  await step('inspects pages and streams found videos', async () => {
    const t0 = Date.now();
    const events = await readSse(`${M}/api/search?q=volcano%20eruption`);
    items = events.filter((e) => e.event === 'item').map((e) => e.data);
    const done = events.find((e) => e.event === 'done');
    assert.ok(done, 'search finished');
    const byPage = Object.fromEntries(items.map((i) => [i.pageUrl.split('/').pop(), i]));
    console.log(`\n    ${items.length} videos from ${done.data.pagesTotal} pages in ${Date.now() - t0} ms`);
    for (const i of [...items].sort((a, b) => b.score - a.score)) console.log(`      ${String(i.score).padStart(5)}  ${i.kind.padEnd(5)} via ${i.via.padEnd(7)} ${i.title}`);
    process.stdout.write('    ');

    assert.equal(byPage['static-video.html'].kind, 'file', 'plain <video> tag');
    assert.match(byPage['static-video.html'].src, /eruption\.webm$/);
    assert.equal(byPage['jsonld.html'].title, 'How volcanoes form', 'JSON-LD VideoObject');
    assert.equal(byPage['jsonld.html'].duration, 30);
    assert.equal(byPage['js-player.html'].kind, 'hls', 'HLS found by network sniffing');
    assert.equal(byPage['js-player.html'].via, 'network');
    assert.equal(byPage['embed.html'].provider, 'youtube', 'embedded YouTube iframe');
    assert.ok(!byPage['ad-only.html'], 'ad-only page yields nothing');
    assert.ok(!byPage['no-video.html'], 'text page yields nothing');
    const top = [...items].sort((a, b) => b.score - a.score)[0];
    assert.match(top.title, /eruption/i, 'best match ranked first');
  });

  await step('proxy streams media with range requests', async () => {
    const file = items.find((i) => i.kind === 'file');
    const r = await fetch(M + file.playUrl, { headers: { Range: 'bytes=0-99' } });
    assert.equal(r.status, 206);
    assert.equal((await r.arrayBuffer()).byteLength, 100);
    const hls = items.find((i) => i.kind === 'hls');
    const pl = await (await fetch(M + hls.playUrl)).text();
    assert.match(pl, /^#EXTM3U/);
    assert.match(pl, /\/proxy\?u=.*index0\.ts/);
  });

  await step('proxy refuses private addresses unless allowed', async () => {
    delete require.cache[require.resolve('../lib/proxy')];
    process.env.MEH_ALLOW_PRIVATE = '0';
    const { handleProxy } = require('../lib/proxy');
    process.env.MEH_ALLOW_PRIVATE = '1';
    let status;
    const res = { writeHead(s) { status = s; return this; }, end() {} };
    await handleProxy({ headers: {}, on() {} }, res, new URLSearchParams({ u: 'http://127.0.0.1:22/' }));
    assert.equal(status, 403);
  });

  // ---------------------------------------------------------------- real browser
  await step('browser: searches, autoplays, skips to next', async () => {
    const { chromium } = require('playwright');
    const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
    const page = await browser.newPage({ viewport: { width: 1360, height: 860 } });
    const shots = process.env.MEH_SHOTS;
    await page.goto(M);
    if (shots) await page.screenshot({ path: path.join(shots, 'home.png') });
    await page.fill('#q', 'volcano eruption');
    await page.press('#q', 'Enter');

    // First video should start on its own.
    await page.waitForFunction(() => { const v = document.querySelector('#video'); return !v.hidden && v.currentTime > 1.5; }, null, { timeout: 30000 });
    const first = await page.textContent('#nowTitle');
    assert.match(first, /eruption/i);
    await page.waitForFunction(() => document.querySelector('#statusDot').classList.contains('done'), null, { timeout: 30000 });
    const queued = await page.$$eval('#queue li', (l) => l.length);
    assert.ok(queued >= 3, `queue shows ${queued}`);
    if (shots) await page.screenshot({ path: path.join(shots, 'playing.png') });

    // Jump to the end: autoplay should advance to the next video.
    await page.evaluate(() => { const v = document.querySelector('#video'); v.currentTime = v.duration - 0.5; });
    await page.waitForFunction((t) => document.querySelector('#nowTitle').textContent !== t, first, { timeout: 10000 });
    const second = await page.textContent('#nowTitle');
    // Press N to skip again.
    await page.keyboard.press('n');
    await page.waitForFunction((t) => document.querySelector('#nowTitle').textContent !== t, second, { timeout: 10000 });
    // P goes back.
    await page.keyboard.press('p');
    await page.waitForFunction((t) => document.querySelector('#nowTitle').textContent === t, second, { timeout: 10000 });

    // Phone width: no horizontal scroll.
    await page.setViewportSize({ width: 390, height: 844 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert.ok(overflow <= 0, `horizontal overflow ${overflow}px`);
    if (shots) await page.screenshot({ path: path.join(shots, 'phone.png') });
    await browser.close();
  });

  console.log(`\n${passed} checks passed`);
  await shutdown();
  server.close();
  fixtures.close();
  process.exit(0);
})().catch(async (e) => {
  console.log('FAILED');
  console.error(e);
  process.exit(1);
});

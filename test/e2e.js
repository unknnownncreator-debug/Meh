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
  const pages = ['static-video', 'jsonld', 'js-player', 'embed', 'ad-only', 'no-video', 'youtube-only', 'tiktok-only', 'mixed',
    'preroll-player', 'preroll-only', 'ima-container', 'declared-long', 'moov-at-end'].map((p) => `${F}/${p}.html`);
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

  await step('ad blocker: ~43k listed ad domains, subdomains included, no false positives', () => {
    const adblock = require('../lib/adblock');
    assert.ok(adblock.size() > 40000, `list has ${adblock.size()} domains`);
    for (const h of ['securepubads.g.doubleclick.net', 'imasdk.googleapis.com', 'ib.adnxs.com', 'cdn.teads.tv', 'mssl.fwmrm.net', 'c.amazon-adsystem.com'])
      assert.ok(adblock.isListedHost(h), h);
    for (const h of ['www.cnn.com', 'player.vimeo.com', 'www.dailymotion.com', 's1.dmcdn.net', 'cdn.jwplayer.com', 'd1.cloudfront.net',
      'vod-progressive.akamaized.net', 'www.googleapis.com', 'en.wikipedia.org', 'archive.org', 'www.nasa.gov'])
      assert.ok(!adblock.isListedHost(h), h);
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

  await step('parses search result pages (DuckDuckGo, Bing, Bing Videos)', async () => {
    const realFetch = global.fetch;
    const pad = '<!--' + 'x'.repeat(2500) + '-->';
    const html = {
      'html.duckduckgo.com': `${pad}<div class="result results_links"><h2><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fa.com%2Fvolcano">Volcano <b>video</b></a></h2><a class="result__snippet" href="#">Lava!</a></div>`,
      'www.bing.com/search': `${pad}<li class="b_algo"><h2><a href="https://b.org/eruption" h="ID=1">Eruption</a></h2><p>Big one</p></li>`,
      'www.bing.com/videos': `${pad}<div class="vrhdata" vrhm='{&quot;du&quot;:&quot;4:13&quot;,&quot;murl&quot;:&quot;https://c.net/clip/9&quot;,&quot;vt&quot;:&quot;Volcano clip&quot;}'></div>`,
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
      assert.equal(ENGINES.youtube, undefined, 'YouTube search engine removed');
    } finally {
      global.fetch = realFetch;
    }
  });

  await step('parses REAL saved search pages (Bing, Bing Videos, DuckDuckGo bot check)', async () => {
    const zlib = require('zlib');
    const real = (n) => zlib.gunzipSync(fs.readFileSync(path.join(__dirname, 'fixtures', 'real', n + '.html.gz'))).toString('utf8');
    const realFetch = global.fetch;
    const map = { 'html.duckduckgo.com': ['ddg', 202], 'www.bing.com/search': ['bing', 200], 'www.bing.com/videos': ['bingv', 200] };
    global.fetch = async (url) => {
      const [name, status] = map[Object.keys(map).find((k) => String(url).includes(k))];
      return new Response(real(name), { status, headers: { 'content-type': 'text/html' } });
    };
    try {
      const bing = await ENGINES.bing('volcano', 0);
      assert.ok(bing.length >= 8 && bing.every((h) => /^https:\/\//.test(h.url) && h.title), `bing ${bing.length}`);
      const bv = await ENGINES.bingVideos('volcano eruption', 0);
      assert.ok(bv.length >= 8, `bing videos ${bv.length}`);
      // DuckDuckGo's bot check (202) must fall back to the real browser, not silently give 0.
      let browserCalls = 0;
      const ddg = await ENGINES.duckduckgo('volcano', 0, async () => { browserCalls++; return ''; });
      assert.equal(ddg.length, 0);
      assert.equal(browserCalls, 1, 'fell back to the browser');
      const parity = { bing: bing.length, bingVideos: bv.length, bingFirst: bing[0].url, bingVideosFirst: bv[0].url };
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

  await step('YouTube and TikTok results are dropped from every engine', async () => {
    const { searchWeb } = require('../lib/search');
    const realFetch = global.fetch;
    const page = '<!--' + 'x'.repeat(2500) + '-->' +
      '<li class="b_algo"><h2><a href="https://www.youtube.com/watch?v=abcdefghijk">YT</a></h2></li>' +
      '<li class="b_algo"><h2><a href="https://youtu.be/abcdefghijk">YT short</a></h2></li>' +
      '<li class="b_algo"><h2><a href="https://m.youtube.com/watch?v=zzzzzzzzzzz">YT mobile</a></h2></li>' +
      '<li class="b_algo"><h2><a href="https://www.tiktok.com/@nasa/video/7234567890123456789">TikTok</a></h2></li>' +
      '<li class="b_algo"><h2><a href="https://vm.tiktok.com/ZMabc123/">TikTok short link</a></h2></li>' +
      '<li class="b_algo"><h2><a href="https://news.example.org/volcano-video">News</a></h2></li>';
    global.fetch = async () => new Response(page, { status: 200, headers: { 'content-type': 'text/html' } });
    try {
      const hits = await searchWeb('volcano', { engines: ['bing'] });
      assert.deepEqual(hits.map((h) => h.url), ['https://news.example.org/volcano-video']);
    } finally {
      global.fetch = realFetch;
    }
  });

  await step('RedGIFs: sound version, search engine with guest token, opt-in switch', async () => {
    const { withSound } = require('../lib/util');
    const { parseRedgifs, searchWeb, ENGINES } = require('../lib/search');
    const insp = require('../lib/inspector');
    assert.equal(withSound('https://media.redgifs.com/AbleGiddyCanary-silent.mp4'), 'https://media.redgifs.com/AbleGiddyCanary.mp4');
    assert.equal(withSound('https://media.redgifs.com/AbleGiddyCanary-mobile.mp4'), 'https://media.redgifs.com/AbleGiddyCanary-mobile.mp4');
    assert.equal(withSound('https://cdn.example.org/clip-silent.mp4'), 'https://cdn.example.org/clip-silent.mp4', 'only RedGIFs files');

    // A watch page shaped like RedGIFs' real ones: it declares the silent copy, plus its player page.
    const watch = `<html><head><title>Clip</title><meta property="og:type" content="video/other">
      <meta property="og:video" content="https://media.redgifs.com/AbleGiddyCanary-silent.mp4"><meta property="og:video:type" content="video/mp4">
      <meta property="og:video:iframe" content="https://www.redgifs.com/ifr/ablegiddycanary"><meta name="twitter:player" content="https://www.redgifs.com/ifr/ablegiddycanary">
      <script type="application/ld+json">{"@type":"SocialMediaPosting","video":{"@type":"VideoObject","contentUrl":"https://media.redgifs.com/AbleGiddyCanary-silent.mp4"}}</script></head></html>`;
    const cands = insp.candidatesFromProbe(insp.parseStaticHtml(watch, 'https://www.redgifs.com/watch/ablegiddycanary'));
    assert.ok(cands.length && cands.every((c) => c.src === 'https://media.redgifs.com/AbleGiddyCanary.mp4'), JSON.stringify(cands.map((c) => c.src)));

    const data = { gifs: [
      { id: 'AbleGiddyCanary', type: 1, duration: 33.6, description: 'Volcano clip', tags: ['Nature'], urls: { hd: 'https://media.redgifs.com/AbleGiddyCanary.mp4' } },
      { id: 'StillPicture', type: 2, duration: 0, urls: { hd: 'https://media.redgifs.com/StillPicture.jpg' } },
      { id: 'bad id!', type: 1, urls: { hd: 'x' } },
      { id: 'NoTitle', type: 1, duration: 12, tags: ['Lava', 'Night'], urls: { hd: 'https://media.redgifs.com/NoTitle.mp4' } },
    ] };
    assert.deepEqual(parseRedgifs(data).map((h) => [h.url, h.title, h.duration]), [
      ['https://www.redgifs.com/watch/ablegiddycanary', 'Volcano clip', 33.6],
      ['https://www.redgifs.com/watch/notitle', 'Lava Night', 12],
    ]);

    // Guest token: fetched once, reused, and renewed once when refused.
    const realFetch = global.fetch;
    const calls = [];
    let tokenNo = 0;
    global.fetch = async (url, opts = {}) => {
      url = String(url); calls.push(url.replace(/\?.*/, ''));
      if (url.endsWith('/v2/auth/temporary')) return Response.json({ token: `t${++tokenNo}` });
      if (url.includes('/v2/gifs/search')) {
        if (opts.headers.Authorization === 'Bearer t1' && calls.filter((c) => c.endsWith('/search')).length === 2) return new Response('', { status: 401 });
        return Response.json(data);
      }
      return new Response('', { status: 404 });
    };
    try {
      assert.equal((await ENGINES.redgifs('volcano', 0)).length, 2);
      assert.equal((await ENGINES.redgifs('volcano', 0)).length, 2, 'refused token renewed');
      assert.equal(calls.filter((c) => c.endsWith('/temporary')).length, 2, 'token fetched once, renewed once');
      // Switched off: not searched, and RedGIFs links from other engines are dropped.
      const page = '<!--' + 'x'.repeat(2500) + '--><li class="b_algo"><h2><a href="https://www.redgifs.com/watch/x">r</a></h2></li><li class="b_algo"><h2><a href="https://news.example.org/v">n</a></h2></li>';
      global.fetch = async () => new Response(page, { status: 200, headers: { 'content-type': 'text/html' } });
      assert.deepEqual((await searchWeb('volcano', { engines: ['bing'] })).map((h) => h.url), ['https://news.example.org/v']);
      assert.equal((await searchWeb('volcano', { engines: ['bing'], includeRedgifs: true })).length, 2);
    } finally {
      global.fetch = realFetch;
    }
  });

  await step('adult sites: rule34 search parsers, opt-in, and minors ALWAYS excluded', async () => {
    const { depictsMinors, dropAdult } = require('../lib/util');
    const { parseRule34video, parsePaheal, parseRule34xxx, ENGINES, searchWeb } = require('../lib/search');
    // The exclusion terms, including compounds; ordinary words that merely contain them pass.
    for (const t of ['loli', 'young_link', 'Shota boy', 'aged-down', 'middle school', 'toddlercon', 'little_sister', 'teen'])
      assert.ok(depictsMinors(t), t);
    for (const t of ['overwatch', 'mercy', 'volcano eruption', 'kidnapped', 'skid', 'babylon', 'minority report', 'cuba'])
      assert.ok(!depictsMinors(t), t);

    // Result pages shaped like the real sites'.
    const rv = '<div class="item thumb video_1"><a class="th js-open-popup" href="https://rule34video.com/video/101/dragon-fight/"><img alt="Dragon fight"><div class="time">13:14</div></a><div class="thumb_title">Dragon fight</div></div>' +
      '<div class="item thumb video_2"><a class="th" href="https://rule34video.com/video/102/young-link-x/"><div class="time">2:00</div><div class="thumb_title">Young Link x</div></a></div>';
    assert.deepEqual(parseRule34video(rv).map((h) => [h.url, h.title, h.duration]), [
      ['https://rule34video.com/video/101/dragon-fight/', 'Dragon fight', 794], ['https://rule34video.com/video/102/young-link-x/', 'Young Link x', 120]]);
    const ph = "<div class='shm-thumb thumb' data-mime='video/mp4' data-tags='dragon animated sound' data-post-id='1'><a class='shm-thumb-link' href='/post/view/1'></a></div>" +
      "<div class='shm-thumb thumb' data-mime='image/png' data-tags='dragon' data-post-id='2'><a href='/post/view/2'></a></div>" +
      "<div class='shm-thumb thumb' data-mime='video/webm' data-tags='dragon loli animated' data-post-id='3'><a href='/post/view/3'></a></div>";
    assert.deepEqual(parsePaheal(ph).map((h) => h.url), ['https://rule34.paheal.net/post/view/1'], 'videos only, minors-tagged dropped');
    const xx = '<span id="s7" class="thumb"><a id="p7" href="index.php?page=post&amp;s=view&amp;id=7"><img alt="dragon animated video" title=" dragon animated video score:9 rating:explicit"></a></span>' +
      '<span id="s8" class="thumb"><a id="p8" href="index.php?page=post&amp;s=view&amp;id=8"><img title="dragon shota video"></a></span>';
    assert.deepEqual(parseRule34xxx(xx).map((h) => [h.url, h.title]), [['https://rule34.xxx/index.php?page=post&s=view&id=7', 'dragon animated video']]);

    // Queries: minus-tags on booru searches; queries asking for minors never reach adult sites.
    const realFetch = global.fetch;
    const asked = [];
    global.fetch = async (url) => { asked.push(String(url)); return new Response('', { status: 404 }); };
    try {
      await ENGINES.paheal('dragon fight', 0); await ENGINES.rule34xxx('dragon', 1); await ENGINES.rule34video('dragon fight', 0);
      assert.match(asked[0], /^https:\/\/rule34\.paheal\.net\/post\/list\/animated%20dragon%20fight%20-loli%20-lolicon.*\/1$/);
      assert.match(asked[1], /^https:\/\/rule34\.xxx\/index\.php\?page=post&s=list&tags=video\+dragon\+-loli\+.*&pid=42$/);
      assert.equal(asked[2], 'https://rule34video.com/search/dragon-fight/');
      asked.length = 0;
      for (const e of ['redgifs', 'rule34video', 'paheal', 'rule34xxx']) assert.deepEqual(await ENGINES[e]('loli dragon', 0), []);
      assert.equal(asked.length, 0, 'nothing sent to adult sites for such a query');
      // Adult links from general engines: hidden when switched off; minors-marked ones always.
      const page = '<!--' + 'x'.repeat(2500) + '--><li class="b_algo"><h2><a href="https://rule34video.com/video/5/dragon/">Dragon</a></h2></li>' +
        '<li class="b_algo"><h2><a href="https://rule34video.com/video/6/loli-x/">x</a></h2></li><li class="b_algo"><h2><a href="https://news.example.org/v">n</a></h2></li>';
      global.fetch = async () => new Response(page, { status: 200, headers: { 'content-type': 'text/html' } });
      assert.deepEqual((await searchWeb('dragon', { engines: ['bing'] })).map((h) => h.url), ['https://news.example.org/v']);
      assert.deepEqual((await searchWeb('dragon', { engines: ['bing'], includeRedgifs: true })).map((h) => h.url), ['https://rule34video.com/video/5/dragon/', 'https://news.example.org/v']);
    } finally {
      global.fetch = realFetch;
    }
    // Final gate on found videos (page text and tags are checked too).
    const v = { src: 'https://r34i.paheal-cdn.net/a.mp4', pageUrl: 'https://rule34.paheal.net/post/view/1', title: 'Dragon', description: '', pageTitle: '', pageText: 'Tags: dragon animated' };
    assert.equal(dropAdult(v, {}, true), false);
    assert.equal(dropAdult(v, {}, false), true, 'switched off');
    assert.equal(dropAdult({ ...v, pageText: 'Tags: dragon shotacon animated' }, {}, true), true, 'minors tag on the page');
    assert.equal(dropAdult({ ...v, src: 'https://cdn.example.org/a.mp4', pageUrl: 'https://news.example.org/a' }, {}, false), false, 'not an adult site');
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
    assert.ok(done.data.adsBlocked >= 2, `ad blocker stopped the page's ad scripts (${done.data.adsBlocked})`);
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
    assert.equal(byPage['embed.html'].provider, 'vimeo', 'embedded Vimeo iframe');
    // Pre-roll ads must never stand in for the real video.
    assert.match(byPage['preroll-player.html'].src, /\/f1\.mp4$/, 'player loads ad then video → the video');
    assert.match(byPage['preroll-only.html'].src, /\/s2\.mp4$/, 'short clips are allowed now (no 45 s rule): a page\'s only clip is used');
    assert.match(byPage['ima-container.html'].src, /\/f3\.mp4$/, 'video in an ad container is skipped for the real one');
    assert.match(byPage['declared-long.html'].src, /\/f4\.mp4$/, 'page declares 5 min → its 60 s clip is rejected');
    assert.match(byPage['moov-at-end.html'].src, /\/n5\.mp4$/, 'unknown length is not rejected');
    assert.ok(!items.some((i) => /\/(s1|s3|m4)\.mp4$/.test(i.src)), 'no ad clip where the real video was available');
    assert.ok(!byPage['youtube-only.html'], 'YouTube-only page yields nothing');
    assert.ok(!byPage['tiktok-only.html'], 'TikTok-only page yields nothing');
    assert.match(byPage['mixed.html'].src, /report\.webm$/, 'article plays its own video, not the YouTube embed');
    assert.ok(items.every((i) => i.provider !== 'youtube' && !/youtube|youtu\.be|googlevideo|tiktok/.test(i.src + i.pageUrl)), 'no YouTube or TikTok anywhere');
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

    // Minimum-length slider: hides shorter videos from the queue at once, and brings them back.
    const titles = () => page.$$eval('#queue .qi-title', (l) => l.map((e) => e.textContent));
    const before = await titles();
    assert.ok(before.some((t) => /How volcanoes form/.test(t)), 'the 30 s video is queued');
    await page.$eval('#minLength', (el) => { el.value = 6; el.dispatchEvent(new Event('input')); }); // 1 min
    assert.equal(await page.textContent('#minLengthLabel'), 'At least 1 min');
    const after = await titles();
    assert.ok(!after.some((t) => /How volcanoes form/.test(t)), 'videos under 1 min hidden');
    assert.ok(after.some((t) => /eruption/i.test(t)), 'longer videos stay');
    await page.$eval('#minLength', (el) => { el.value = 0; el.dispatchEvent(new Event('input')); });
    assert.equal((await titles()).length, before.length, 'all back at "Any length"');
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

    // Short clips play (no 45 s rule): a 25 s clip whose length is only known once loaded.
    process.env.MEH_TEST_PAGES = `${F}/short-unknown.html`;
    await page.fill('#q', 'volcano teaser short clip');
    await page.press('#q', 'Enter');
    await page.waitForFunction(() => { const v = document.querySelector('#video'); return !v.hidden && v.currentTime > 1.5; }, null, { timeout: 30000 });
    process.env.MEH_TEST_PAGES = pages.join(',');

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

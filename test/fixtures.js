'use strict';
// A tiny local "website" whose pages hide video in all the ways real sites do.
// Media is generated with ffmpeg on first run.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const MEDIA = path.join(__dirname, 'fixtures', 'media2');

function ensureMedia() {
  fs.mkdirSync(path.join(MEDIA, 'stream'), { recursive: true });
  const ff = (args) => execFileSync('ffmpeg', ['-y', '-loglevel', 'error', ...args]);
  const mk = (name, color, secs) => {
    const out = path.join(MEDIA, name);
    if (!fs.existsSync(out)) {
      ff(['-f', 'lavfi', '-i', `testsrc2=size=640x360:rate=24:duration=${secs}`, '-f', 'lavfi', '-i', `sine=frequency=440:duration=${secs}`,
        '-vf', `drawbox=c=${color}@0.5:t=fill`, '-c:v', 'libvpx-vp9', '-b:v', '300k', '-deadline', 'realtime', '-cpu-used', '8',
        '-c:a', 'libopus', '-shortest', out]);
    }
  };
  // Real content is at least a minute long (shorter "found" videos are treated as ads).
  mk('eruption.webm', 'red', 60);
  mk('formation.webm', 'blue', 60);
  mk('preroll.webm', 'yellow', 30);
  if (!fs.existsSync(path.join(MEDIA, 'stream', 'index.m3u8'))) {
    ff(['-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=24:duration=60', '-c:v', 'libx264', '-preset', 'ultrafast',
      '-f', 'hls', '-hls_time', '6', '-hls_playlist_type', 'vod', path.join(MEDIA, 'stream', 'index.m3u8')]);
  }
  // MP4 clips (VP9, so headless Chromium can play them) for the pre-roll ad tests.
  // "faststart" puts the length header at the front, where the app reads it.
  const mp4 = (name, secs, faststart) => {
    const out = path.join(MEDIA, name);
    if (!fs.existsSync(out)) {
      ff(['-f', 'lavfi', '-i', `testsrc2=size=160x90:rate=10:duration=${secs}`, '-c:v', 'libvpx-vp9', '-b:v', '60k',
        '-deadline', 'realtime', '-cpu-used', '8', ...(faststart ? ['-movflags', '+faststart'] : []), out]);
    }
  };
  mp4('short.mp4', 25, true);         // a typical pre-roll ad
  mp4('mid.mp4', 60, true);
  mp4('long.mp4', 200, true);         // the real video
  mp4('long-nofast.mp4', 200, false); // length header at the end: can't be read up front
  mp4('short-nofast.mp4', 25, false);
}

const PAGES = {
  '/static-video.html': `<!doctype html><html><head><title>Volcano eruption caught on camera</title>
    <meta property="og:title" content="Volcano eruption caught on camera">
    <meta property="og:description" content="Lava fountains during a major volcano eruption.">
    </head><body><h1>Volcano eruption</h1><video controls width="640" src="/media/eruption.webm"></video></body></html>`,

  '/jsonld.html': `<!doctype html><html><head><title>Science Hub</title>
    <script type="application/ld+json">${JSON.stringify({
    '@context': 'https://schema.org', '@type': 'VideoObject', name: 'How volcanoes form',
    description: 'Plate tectonics and magma explained.', duration: 'PT30S',
    contentUrl: '/media/formation.webm', thumbnailUrl: '/thumb.jpg',
  })}</script></head><body><p>Article about how volcanoes form.</p></body></html>`,

  // Nothing in the HTML: a script "player" loads an HLS stream after the page runs.
  '/js-player.html': `<!doctype html><html><head><title>Live volcano cam stream</title></head>
    <script async src="https://securepubads.g.doubleclick.net/tag/js/gpt.js"></script>
    <script async src="https://imasdk.googleapis.com/js/sdkloader/ima3.js"></script>
    <body><div id="player"></div><script>
      setTimeout(function () { fetch('/media/stream/index.m3u8').then(function (r) { return r.text(); }); }, 300);
    </script></body></html>`,

  '/embed.html': `<!doctype html><html><head><title>Top 10 volcano facts</title></head>
    <body><p>Volcano facts</p><iframe width="560" height="315" src="https://player.vimeo.com/video/76979871"></iframe></body></html>`,

  // YouTube is excluded: a page whose only video is YouTube yields nothing…
  '/youtube-only.html': `<!doctype html><html><head><title>Volcano on YouTube</title>
    <meta property="og:video:url" content="https://www.youtube.com/embed/dQw4w9WgXcQ"></head>
    <body><iframe src="https://www.youtube.com/embed/dQw4w9WgXcQ"></iframe></body></html>`,

  // TikTok is excluded too: its embed and its video server.
  '/tiktok-only.html': `<!doctype html><html><head><title>Volcano TikTok</title>
    <meta property="og:video" content="https://v16-webapp.tiktokcdn.com/video/volcano.mp4"></head>
    <body><iframe src="https://www.tiktok.com/embed/v2/7234567890123456789"></iframe>
    <video width="640" height="360" src="https://v16-webapp.tiktokcdn.com/video/volcano.mp4"></video></body></html>`,

  // …and an article with both a YouTube embed and its own video plays its own video.
  '/mixed.html': `<!doctype html><html><head><title>Volcano report</title></head><body>
    <iframe src="https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"></iframe>
    <video controls width="640" height="360" src="/media/report.webm"></video></body></html>`,

  '/ad-only.html': `<!doctype html><html><head><title>Volcano news</title></head>
    <body><video autoplay muted src="/ads/preroll.webm"></video><p>No real video here.</p></body></html>`,

  // ---- pre-roll ads (clip names are neutral: these ads are NOT on any ad-server list) ----
  // A player that requests its ad first, and the real video once the ad fails/ends.
  '/preroll-player.html': `<!doctype html><html><head><title>Volcano documentary</title></head><body>
    <div class="player"><video id="v" width="640" height="360" muted autoplay></video></div><script>
      var v = document.getElementById('v'), switched = false;
      function content() { if (!switched) { switched = true; v.src = '/media/clips/f1.mp4'; v.play().catch(function(){}); } }
      v.onerror = content; setTimeout(content, 1500);
      v.src = '/media/clips/s1.mp4'; v.play().catch(function(){});
    </script></body></html>`,

  // A page whose only video request is the ad: must yield nothing.
  '/preroll-only.html': `<!doctype html><html><head><title>Volcano clip with ad</title></head><body>
    <video width="640" height="360" muted autoplay src="/media/clips/s2.mp4"></video></body></html>`,

  // IMA-style: the ad plays in an ad container, the real video is set up by script.
  '/ima-container.html': `<!doctype html><html><head><title>Volcano news report</title></head><body>
    <div class="ima-ad-container"><video width="640" height="360" src="/media/clips/s3.mp4"></video></div>
    <div class="player"><video id="c" width="640" height="360"></video></div>
    <script>setTimeout(function(){ document.getElementById('c').src = '/media/clips/f3.mp4'; }, 800);</script></body></html>`,

  // The page says its video is 5 minutes; a 60 s clip it loads first can't be it.
  '/declared-long.html': `<!doctype html><html><head><title>Volcano full lecture</title>
    <script type="application/ld+json">{"@type":"VideoObject","name":"Volcano full lecture","duration":"PT5M"}</script></head><body>
    <video id="v" width="640" height="360" muted></video><script>
      var v = document.getElementById('v'), n = 0;
      function next(){ v.src = ['/media/clips/m4.mp4', '/media/clips/f4.mp4'][Math.min(n++, 1)]; v.play().catch(function(){}); }
      v.onerror = next; next();
    </script></body></html>`,

  // Length header at the end of the file: length unknown up front, so it must NOT be rejected.
  '/moov-at-end.html': `<!doctype html><html><head><title>Volcano timelapse</title></head><body>
    <video width="640" height="360" src="/media/clips/n5.mp4"></video></body></html>`,

  // A 25 s clip whose length can only be learned by loading it: the player must skip it.
  '/short-unknown.html': `<!doctype html><html><head><title>Volcano teaser</title></head><body>
    <video width="640" height="360" src="/media/clips/q6.mp4"></video></body></html>`,

  // A music video (must be dropped) and a page that only mentions the search words in its body.
  '/music-video.html': `<!doctype html><html><head><title>Volcano (Official Music Video)</title></head><body>
    <video width="640" height="360" src="/media/clips/l9.mp4"></video></body></html>`,
  '/body-mention.html': `<!doctype html><html><head><title>Cooking pasta at home</title></head><body>
    <p>Unrelated aside: a volcano eruption once delayed my flight.</p><video width="640" height="360" src="/media/clips/l8.mp4"></video></body></html>`,

  '/no-video.html': `<!doctype html><html><head><title>Volcano text article</title></head><body><p>Just text about a volcano.</p></body></html>`,
};

function startFixtures(port = 0) {
  ensureMedia();
  const server = http.createServer((req, res) => {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (PAGES[p]) { res.writeHead(200, { 'Content-Type': 'text/html' }); res.end(PAGES[p]); return; }
    let file = null;
    if (p.startsWith('/media/clips/')) {
      // Many URLs, few files: s* = 25 s ad, m* = 60 s, f*/l* = 200 s, n* = 200 s (header at end), q* = 25 s (header at end)
      const pick = { s: 'short.mp4', m: 'mid.mp4', f: 'long.mp4', l: 'long.mp4', n: 'long-nofast.mp4', q: 'short-nofast.mp4' }[path.basename(p)[0]];
      if (pick) file = path.join(MEDIA, pick);
    } else if (p.startsWith('/media/')) file = path.join(MEDIA, p.slice(7));
    if (p === '/ads/preroll.webm') file = path.join(MEDIA, 'preroll.webm');
    if (!file || !file.startsWith(MEDIA) || !fs.existsSync(file)) { res.writeHead(404).end(); return; }
    const type = file.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : file.endsWith('.ts') ? 'video/mp2t' : file.endsWith('.mp4') ? 'video/mp4' : 'video/webm';
    const size = fs.statSync(file).size;
    const range = req.headers.range && req.headers.range.match(/bytes=(\d+)-(\d*)/);
    if (range) {
      const start = +range[1], end = range[2] ? +range[2] : size - 1;
      res.writeHead(206, { 'Content-Type': type, 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': end - start + 1, 'Accept-Ranges': 'bytes' });
      fs.createReadStream(file, { start, end }).pipe(res);
    } else {
      res.writeHead(200, { 'Content-Type': type, 'Content-Length': size, 'Accept-Ranges': 'bytes' });
      fs.createReadStream(file).pipe(res);
    }
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}

module.exports = { startFixtures, PAGES };

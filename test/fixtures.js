'use strict';
// A tiny local "website" whose pages hide video in all the ways real sites do.
// Media is generated with ffmpeg on first run.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const MEDIA = path.join(__dirname, 'fixtures', 'media');

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
  mk('eruption.webm', 'red', 30);
  mk('formation.webm', 'blue', 30);
  mk('preroll.webm', 'yellow', 30);
  if (!fs.existsSync(path.join(MEDIA, 'stream', 'index.m3u8'))) {
    ff(['-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=24:duration=24', '-c:v', 'libx264', '-preset', 'ultrafast',
      '-f', 'hls', '-hls_time', '4', '-hls_playlist_type', 'vod', path.join(MEDIA, 'stream', 'index.m3u8')]);
  }
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
    <body><div id="player"></div><script>
      setTimeout(function () { fetch('/media/stream/index.m3u8').then(function (r) { return r.text(); }); }, 300);
    </script></body></html>`,

  '/embed.html': `<!doctype html><html><head><title>Top 10 volcano facts</title></head>
    <body><p>Volcano facts</p><iframe width="560" height="315" src="https://www.youtube.com/embed/dQw4w9WgXcQ"></iframe></body></html>`,

  '/ad-only.html': `<!doctype html><html><head><title>Volcano news</title></head>
    <body><video autoplay muted src="/ads/preroll.webm"></video><p>No real video here.</p></body></html>`,

  '/no-video.html': `<!doctype html><html><head><title>Volcano text article</title></head><body><p>Just text about a volcano.</p></body></html>`,
};

function startFixtures(port = 0) {
  ensureMedia();
  const server = http.createServer((req, res) => {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (PAGES[p]) { res.writeHead(200, { 'Content-Type': 'text/html' }); res.end(PAGES[p]); return; }
    let file = null;
    if (p.startsWith('/media/')) file = path.join(MEDIA, p.slice(7));
    if (p === '/ads/preroll.webm') file = path.join(MEDIA, 'preroll.webm');
    if (!file || !file.startsWith(MEDIA) || !fs.existsSync(file)) { res.writeHead(404).end(); return; }
    const type = file.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : file.endsWith('.ts') ? 'video/mp2t' : 'video/webm';
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

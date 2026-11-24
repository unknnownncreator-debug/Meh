'use strict';
// Read a video's real length without downloading it: the first 64 KB of an MP4 (its
// "mvhd" header) or WebM (its "Duration" element), or the segment list of an HLS
// playlist. Used to tell a 15–30 s pre-roll ad from the real video behind it.

const { UA } = require('./util');
const cookies = require('./cookies');

const HEAD_BYTES = 64 * 1024;

/** MP4/MOV: duration from the movie header box (only if it sits near the start — "faststart" files). */
function mp4Duration(buf) {
  for (let i = buf.indexOf('mvhd'); i >= 4 && i + 32 <= buf.length; i = buf.indexOf('mvhd', i + 4)) {
    const version = buf[i + 4];
    let timescale, duration;
    if (version === 0) {
      timescale = buf.readUInt32BE(i + 16);
      duration = buf.readUInt32BE(i + 20);
    } else if (version === 1 && i + 36 <= buf.length) {
      timescale = buf.readUInt32BE(i + 24);
      duration = Number(buf.readBigUInt64BE(i + 28));
    } else continue;
    if (timescale > 0 && duration > 0 && duration !== 0xffffffff) return duration / timescale;
  }
  return null;
}

/** WebM/Matroska: Segment Info → Duration (0x4489) × TimecodeScale (0x2AD7B1, default 1 ms). */
function webmDuration(buf) {
  if (!(buf[0] === 0x1a && buf[1] === 0x45 && buf[2] === 0xdf && buf[3] === 0xa3)) return null;
  let scale = 1e6;
  const ts = buf.indexOf(Buffer.from([0x2a, 0xd7, 0xb1]));
  if (ts >= 0 && ts + 4 < buf.length) {
    const len = buf[ts + 3] & 0x0f; // size byte 0x8N → N bytes
    if ((buf[ts + 3] & 0xf0) === 0x80 && len > 0 && len <= 6 && ts + 4 + len <= buf.length) scale = buf.readUIntBE(ts + 4, len);
  }
  const d = buf.indexOf(Buffer.from([0x44, 0x89]));
  if (d < 0 || d + 3 > buf.length) return null;
  const size = buf[d + 2];
  let units = null;
  if (size === 0x84 && d + 7 <= buf.length) units = buf.readFloatBE(d + 3);
  else if (size === 0x88 && d + 11 <= buf.length) units = buf.readDoubleBE(d + 3);
  if (!units || !Number.isFinite(units) || units <= 0) return null;
  return (units * scale) / 1e9;
}

/** HLS: sum of #EXTINF segment lengths. Live streams (no ENDLIST) count as long. */
function hlsDuration(text) {
  if (!/#EXTM3U/.test(text)) return null;
  let total = 0;
  for (const m of text.matchAll(/#EXTINF:\s*([\d.]+)/g)) total += parseFloat(m[1]);
  if (!/#EXT-X-ENDLIST/.test(text) && total > 0) return Infinity; // live
  return total > 0 ? total : null;
}

async function get(url, referer, range) {
  const headers = { 'User-Agent': UA, 'Accept': '*/*', 'Cookie': cookies.header(url) };
  if (referer) headers.Referer = referer;
  if (range) headers.Range = range;
  const res = await fetch(url, { headers, redirect: 'follow', signal: AbortSignal.timeout(6000) });
  cookies.storeFrom(url, res);
  if (!res.ok || !res.body) { res.body && res.body.cancel().catch(() => {}); return null; }
  const reader = res.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (total < HEAD_BYTES) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(Buffer.from(value));
      total += value.byteLength;
    }
  } finally { reader.cancel().catch(() => {}); }
  return { buf: Buffer.concat(chunks).subarray(0, HEAD_BYTES), url: res.url };
}

/** Length in seconds of a {kind, src, referer} media candidate, or null if it can't be told. */
async function probeDuration(c) {
  try {
    if (c.kind === 'hls') {
      const first = await get(c.src, c.referer);
      if (!first) return null;
      let text = first.buf.toString('utf8');
      if (/#EXT-X-STREAM-INF/.test(text)) { // master playlist → read the first variant
        const variant = text.split(/\r?\n/).find((l) => l.trim() && !l.startsWith('#'));
        if (!variant) return null;
        const v = await get(new URL(variant.trim(), first.url).toString(), c.referer);
        if (!v) return null;
        text = v.buf.toString('utf8');
      }
      return hlsDuration(text);
    }
    if (c.kind === 'file') {
      const r = await get(c.src, c.referer, `bytes=0-${HEAD_BYTES - 1}`);
      return r ? (mp4Duration(r.buf) ?? webmDuration(r.buf)) : null;
    }
  } catch { /* unreachable / blocked */ }
  return null;
}

module.exports = { probeDuration, mp4Duration, webmDuration, hlsDuration };

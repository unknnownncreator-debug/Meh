'use strict';
/* meh — front end: live search stream → ranked queue → continuous autoplay. */

const $ = (id) => document.getElementById(id);
const els = {
  form: $('search'), q: $('q'), stage: document.querySelector('.stage'),
  video: $('video'), embed: $('embed'), overlay: $('overlay'), overlayText: $('overlayText'),
  title: $('nowTitle'), source: $('nowSource'), kind: $('nowKind'), score: $('nowScore'), desc: $('nowDesc'),
  prev: $('prev'), next: $('next'), unmute: $('unmute'), autoplay: $('autoplay'), skipWatched: $('skipWatched'), redgifs: $('redgifs'), minLength: $('minLength'), minLengthLabel: $('minLengthLabel'),
  status: $('status'), dot: $('statusDot'), queue: $('queue'), queueCount: $('queueCount'),
};

// ---------------------------------------------------------------- persistence (best effort)
const store = {
  get(k, d) { try { const v = localStorage.getItem('meh:' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('meh:' + k, JSON.stringify(v)); } catch { /* private mode etc. */ } },
};
const watched = new Set(store.get('watched', []));
function markWatched(key) {
  watched.delete(key); watched.add(key);
  const arr = [...watched].slice(-3000);
  store.set('watched', arr);
}
els.autoplay.checked = store.get('autoplay', true);
els.skipWatched.checked = store.get('skipWatched', true);
els.autoplay.addEventListener('change', () => store.set('autoplay', els.autoplay.checked));
els.skipWatched.addEventListener('change', () => { store.set('skipWatched', els.skipWatched.checked); renderQueue(); });
// RedGIFs is opt-in content (18+): searched only while this is on, and hidden from the queue when off.
els.redgifs.checked = store.get('redgifs', true);
els.redgifs.addEventListener('change', () => { store.set('redgifs', els.redgifs.checked); renderQueue(); });
const isRedgifsItem = (it) => /(^|\.)redgifs\.com$/i.test(it.site || '');

// Minimum length slider: uneven steps (seconds) so short settings are as easy to hit as long ones.
const MIN_STEPS = [0, 5, 10, 15, 30, 45, 60, 90, 120, 180, 300, 600, 900, 1200];
const shortLen = (s) => (s < 60 ? `${s} s` : s % 60 === 30 ? `${Math.floor(s / 60)}½ min` : `${s / 60} min`);
let minSeconds = 0;
function setMinLength(i) {
  i = Math.max(0, Math.min(MIN_STEPS.length - 1, +i || 0));
  minSeconds = MIN_STEPS[i];
  els.minLength.value = i;
  els.minLengthLabel.textContent = minSeconds ? `At least ${shortLen(minSeconds)}` : 'Any length';
}
setMinLength(store.get('minLengthIdx', 0));
// Filters the queue right away; the playing video is never interrupted.
els.minLength.addEventListener('input', () => { setMinLength(els.minLength.value); store.set('minLengthIdx', +els.minLength.value); renderQueue(); });
els.minLength.addEventListener('change', () => maybeLoadMore());
/** Unknown lengths pass (checked again once the video loads). */
const longEnough = (d) => !minSeconds || !d || d >= minSeconds;

// ---------------------------------------------------------------- state
let S = null; // current search session

function newSession(q) {
  if (S) S.sources.forEach((es) => es.close());
  S = {
    q, items: new Map(), failed: new Set(), played: new Set(), history: [], current: null,
    page: 0, loading: false, done: false, hasMore: false, sources: [], startTimer: null,
  };
}

// Ranked queue: highest relevance first, gently spreading sites out so one domain
// can't take over, watched videos last (or hidden).
function upcoming() {
  const pool = [...S.items.values()].filter((it) => it.key !== S.current && !S.played.has(it.key) && !S.failed.has(it.key) && (els.redgifs.checked || !isRedgifsItem(it)) && longEnough(it.duration));
  const fresh = pool.filter((it) => !watched.has(it.key));
  const old = els.skipWatched.checked ? [] : pool.filter((it) => watched.has(it.key));
  const order = (list) => {
    const remaining = [...list].sort((a, b) => b.score - a.score);
    const out = [];
    const perSite = new Map();
    while (remaining.length) {
      let bestI = 0, bestV = -Infinity;
      for (let i = 0; i < remaining.length; i++) {
        const it = remaining[i];
        const v = it.score - 7 * (perSite.get(it.site) || 0);
        if (v > bestV) { bestV = v; bestI = i; }
      }
      const [pick] = remaining.splice(bestI, 1);
      perSite.set(pick.site, (perSite.get(pick.site) || 0) + 1);
      out.push(pick);
    }
    return out;
  };
  return [...order(fresh), ...order(old)];
}

// ---------------------------------------------------------------- search stream
function search(q, { push = true } = {}) {
  q = q.trim();
  if (!q) return;
  document.body.classList.remove('is-home');
  els.stage.hidden = false;
  els.q.value = q;
  document.title = `${q} — meh`;
  if (push) history.pushState({ q }, '', `/?q=${encodeURIComponent(q)}`);
  stopPlayback();
  newSession(q);
  setOverlay(`Searching the web for “${q}”…`, true);
  clearNow();
  renderQueue();
  loadPage(0);
}

function loadPage(page) {
  const session = S;
  session.loading = true;
  session.page = page;
  setStatus(page ? 'Finding more videos…' : 'Searching the web…', false);
  const es = new EventSource(`/api/search?q=${encodeURIComponent(session.q)}&page=${page}${els.redgifs.checked ? '&redgifs=1' : ''}`);
  session.sources.push(es);

  es.addEventListener('status', (e) => {
    if (S !== session) return;
    const d = JSON.parse(e.data);
    setStatus(d.message, false);
    if (!session.current && !session.items.size) setOverlay(d.message, true);
  });
  es.addEventListener('item', (e) => {
    if (S !== session) return;
    const it = JSON.parse(e.data);
    if (session.items.has(it.key)) return;
    it.site = siteOf(it.pageUrl || it.src);
    session.items.set(it.key, it);
    renderQueue();
    // Start as soon as there's something — after a short beat so a better match
    // arriving in the same burst can win the first slot.
    if (!session.current && !session.startTimer) {
      session.startTimer = setTimeout(() => { session.startTimer = null; if (!session.current) playNext(); }, 900);
    }
  });
  const finish = (d) => {
    es.close();
    if (S !== session) return;
    session.loading = false;
    session.done = true;
    session.hasMore = !!(d && d.hasMore);
    const n = session.items.size;
    const sites = new Set([...session.items.values()].map((i) => i.site)).size;
    session.adsBlocked = (session.adsBlocked || 0) + ((d && d.adsBlocked) || 0);
    const ads = session.adsBlocked ? ` · ${session.adsBlocked} ad${session.adsBlocked === 1 ? '' : 's'} blocked` : '';
    setStatus((n ? `Found ${n} video${n === 1 ? '' : 's'} across ${sites} site${sites === 1 ? '' : 's'}` : 'No playable videos found') + ads, true);
    if (!session.current) {
      if (n && upcoming().length) playNext();
      else if (session.hasMore && page < 3) loadPage(page + 1);
      else setOverlay(n ? 'You have watched everything found for this search. Turn off “Skip watched” or try another search.' : 'No playable videos found. Try different words.', false);
    }
    maybeLoadMore();
  };
  es.addEventListener('done', (e) => finish(JSON.parse(e.data)));
  es.addEventListener('fail', (e) => { setStatus('Search failed: ' + JSON.parse(e.data).message, true); finish(null); });
  es.onerror = () => { if (es.readyState === EventSource.CLOSED || S !== session || session.loading) finish({ hasMore: session.hasMore }); };
}

function maybeLoadMore() {
  if (S && !S.loading && S.hasMore && upcoming().length < 4 && S.page < 10) loadPage(S.page + 1);
}

// ---------------------------------------------------------------- playback
let hls = null, vimeo = null, watchdog = null, endTimer = null, token = 0;

function stopPlayback() {
  token++;
  clearTimeout(watchdog); clearTimeout(endTimer);
  if (hls) { hls.destroy(); hls = null; }
  if (vimeo) { try { vimeo.destroy(); } catch {} vimeo = null; }
  els.video.pause();
  els.video.removeAttribute('src');
  els.video.load();
  els.embed.innerHTML = '';
  els.embed.hidden = true;
  els.video.hidden = false;
  els.unmute.hidden = true;
}

function playNext() {
  if (!S) return;
  const next = upcoming()[0];
  if (!next) {
    if (S.loading || S.hasMore) { setOverlay('Looking for more videos…', true); maybeLoadMore(); }
    else setOverlay('That’s everything found for this search. Try another search.', false);
    S.current = null;
    renderQueue();
    return;
  }
  if (S.current) S.history.push(S.current);
  play(next);
  maybeLoadMore();
}

function playPrev() {
  if (!S || !S.history.length) return;
  const key = S.history.pop();
  const it = S.items.get(key);
  if (!it) return;
  S.played.delete(key);
  if (S.current) S.played.delete(S.current); // current goes back into the queue
  play(it);
}

function play(it) {
  stopPlayback();
  const my = token;
  S.current = it.key;
  S.played.add(it.key);
  showNow(it);
  renderQueue();
  setOverlay('Loading video…', true);
  const fail = (why) => {
    if (my !== token) return;
    console.warn('skip', it.src, why);
    S.failed.add(it.key);
    S.current = null;
    S.skipped = (S.skipped || 0) + 1;
    const name = (it.title || 'that video').slice(0, 60);
    setOverlay(`Couldn’t play “${name}”: ${why}. Skipping…`, true);
    setStatus(`Skipped “${name}” (${it.site}): ${why} · ${S.skipped} skipped so far`, !S.loading);
    setTimeout(() => { if (my === token) playNext(); }, 1800);
  };
  const ended = () => { if (my === token && els.autoplay.checked) playNext(); };
  const started = () => { if (my !== token) return; clearTimeout(watchdog); setOverlay('', false, true); markWatched(it.key); };
  watchdog = setTimeout(() => fail('it never started (timed out)'), 20000);

  if (it.kind === 'embed') {
    els.video.hidden = true;
    els.embed.hidden = false;
    const host = document.createElement('div');
    els.embed.appendChild(host);
    if (it.provider === 'vimeo') return playVimeo(it, host, { started, ended, fail, my });
    return playIframe(it, { started, ended });
  }
  playFile(it, { started, ended, fail, my });
}

function playFile(it, { started, ended, fail, my }) {
  const v = els.video;
  v.muted = store.get('muted', false);
  // Too short for the slider? Skip it as soon as its real length is known.
  v.onloadedmetadata = () => {
    if (my === token && Number.isFinite(v.duration) && !longEnough(v.duration)) { it.duration = v.duration; fail(`shorter than your minimum length (${shortLen(minSeconds)})`); }
  };
  v.onplaying = started;
  v.onended = ended;
  // MediaError codes: 2 network, 3 decode, 4 not supported / refused by the server.
  v.onerror = () => fail({ 2: 'couldn’t load it from the video server', 3: 'the video file is damaged', 4: 'the site refused it or the format isn’t supported' }[v.error && v.error.code] || 'playback error');
  v.onvolumechange = () => { if (my === token && !els.unmute.hidden && !v.muted) els.unmute.hidden = true; store.set('muted', v.muted && els.unmute.hidden); };
  const go = () => {
    const p = v.play();
    if (p) p.catch((err) => {
      if (my !== token) return;
      if (err.name === 'NotAllowedError') { // browser blocked sound — play muted instead
        v.muted = true;
        els.unmute.hidden = false;
        v.play().catch(() => fail('autoplay blocked'));
      }
    });
  };
  if (it.kind === 'hls' && !v.canPlayType('application/vnd.apple.mpegurl')) {
    if (!window.Hls || !Hls.isSupported()) return fail('no hls support');
    hls = new Hls({ maxBufferLength: 30 });
    hls.on(Hls.Events.ERROR, (_, d) => { if (d.fatal) fail('hls ' + d.details); });
    hls.loadSource(it.playUrl);
    hls.attachMedia(v);
    hls.on(Hls.Events.MANIFEST_PARSED, go);
  } else {
    v.src = it.playUrl;
    go();
  }
}

function loadScript(src, ready) {
  return new Promise((resolve, reject) => {
    if (ready()) return resolve();
    let s = document.querySelector(`script[src="${src}"]`);
    if (!s) { s = document.createElement('script'); s.src = src; document.head.appendChild(s); }
    const t0 = Date.now();
    (function wait() {
      if (ready()) return resolve();
      if (Date.now() - t0 > 10000) return reject(new Error('load ' + src));
      setTimeout(wait, 100);
    })();
  });
}

function playVimeo(it, host, { started, ended, fail, my }) {
  loadScript('https://player.vimeo.com/api/player.js', () => window.Vimeo && Vimeo.Player).then(() => {
    if (my !== token) return;
    vimeo = new Vimeo.Player(host, { id: +it.id, autoplay: true, responsive: false, width: 640 });
    const iframe = host.querySelector('iframe') || host;
    iframe.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;border:0';
    vimeo.on('play', started);
    vimeo.on('ended', ended);
    vimeo.on('error', () => fail('vimeo error'));
    vimeo.play().catch(() => vimeo.setMuted(true).then(() => { els.unmute.hidden = false; return vimeo.play(); }).catch(() => fail('vimeo blocked')));
  }).catch(() => fail('vimeo api'));
}

// Players without an "ended" event: advance after the known duration.
function playIframe(it, { started, ended }) {
  const f = document.createElement('iframe');
  f.allow = 'autoplay; fullscreen; encrypted-media; picture-in-picture';
  f.allowFullscreen = true;
  f.src = it.provider === 'dailymotion' ? `https://www.dailymotion.com/embed/video/${encodeURIComponent(it.id)}?autoplay=1` : it.src;
  f.onload = started;
  els.embed.innerHTML = '';
  els.embed.appendChild(f);
  if (it.duration) endTimer = setTimeout(ended, (it.duration + 3) * 1000);
}

els.unmute.addEventListener('click', () => {
  els.unmute.hidden = true;
  if (vimeo) vimeo.setMuted(false);
  else { els.video.muted = false; store.set('muted', false); }
});

function togglePause() {
  if (vimeo) { vimeo.getPaused().then((p) => (p ? vimeo.play() : vimeo.pause())); return; }
  if (!els.video.hidden) els.video.paused ? els.video.play() : els.video.pause();
}

// ---------------------------------------------------------------- rendering
function siteOf(url) {
  try { return new URL(url).hostname.replace(/^www\./, ''); } catch { return ''; }
}

function fmtDur(s) {
  if (!s || !isFinite(s)) return '';
  s = Math.round(s);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return (h ? `${h}:${String(m).padStart(2, '0')}` : `${m}`) + `:${String(sec).padStart(2, '0')}`;
}

function kindLabel(it) {
  if (it.kind === 'embed') return it.provider;
  if (it.kind === 'hls') return 'HLS stream';
  const ext = (it.src.split('?')[0].match(/\.(\w{2,4})$/) || [])[1];
  return ext ? ext.toUpperCase() : 'video file';
}

function setOverlay(text, busy, hide = false) {
  els.overlay.classList.toggle('hide', hide);
  els.overlay.classList.toggle('idle', !busy);
  els.overlayText.textContent = text;
}

function setStatus(text, done) {
  els.status.textContent = text;
  els.dot.classList.toggle('done', !!done);
}

function clearNow() {
  els.title.textContent = '';
  els.source.textContent = '';
  els.source.removeAttribute('href');
  els.kind.textContent = '';
  els.score.textContent = '';
  els.desc.textContent = '';
}

function showNow(it) {
  els.title.textContent = it.title || it.pageTitle || 'Untitled video';
  els.source.textContent = `${it.siteName || it.site} ↗`;
  els.source.href = it.pageUrl || it.src;
  els.kind.textContent = kindLabel(it);
  els.score.textContent = `relevance ${Math.round(it.score)}`;
  els.desc.textContent = it.description || '';
}

function renderQueue() {
  if (!S) return;
  const list = upcoming();
  els.queue.replaceChildren();
  const cur = S.current && S.items.get(S.current);
  const rows = cur ? [cur, ...list] : list;
  for (const it of rows.slice(0, 60)) {
    const li = document.createElement('li');
    if (it === cur) li.className = 'current';
    else if (watched.has(it.key)) li.className = 'watched';
    const b = document.createElement('button');
    b.type = 'button';
    b.addEventListener('click', () => { if (it !== cur) { if (S.current) S.history.push(S.current); play(it); } });

    const th = document.createElement('div');
    th.className = 'thumb';
    if (it.thumbnail) {
      const img = document.createElement('img');
      img.loading = 'lazy';
      img.referrerPolicy = 'no-referrer';
      img.alt = '';
      img.src = it.thumbnail;
      img.onerror = () => { img.remove(); };
      th.appendChild(img);
    } else {
      const ph = document.createElement('span'); ph.className = 'ph'; ph.textContent = '▶'; th.appendChild(ph);
    }
    if (it.duration) { const d = document.createElement('span'); d.className = 'dur'; d.textContent = fmtDur(it.duration); th.appendChild(d); }

    const info = document.createElement('div');
    const t = document.createElement('div'); t.className = 'qi-title'; t.textContent = (it === cur ? '▶ ' : '') + (it.title || it.pageTitle || 'Untitled');
    const s = document.createElement('div'); s.className = 'qi-site'; s.textContent = `${it.siteName || it.site} · ${kindLabel(it)}`;
    const sc = document.createElement('div'); sc.className = 'qi-score'; sc.textContent = `relevance ${Math.round(it.score)}`;
    info.append(t, s, sc);
    b.append(th, info);
    li.appendChild(b);
    els.queue.appendChild(li);
  }
  if (!rows.length) {
    const li = document.createElement('li'); li.className = 'empty';
    li.textContent = S.loading ? 'Inspecting pages — videos will appear here as they’re found.' : 'Nothing queued.';
    els.queue.appendChild(li);
  }
  els.queueCount.textContent = list.length ? `${list.length} queued` : '';
}

// ---------------------------------------------------------------- input
els.form.addEventListener('submit', (e) => { e.preventDefault(); search(els.q.value); els.q.blur(); });
els.next.addEventListener('click', () => { if (S) { S.current && S.played.add(S.current); playNext(); } });
els.prev.addEventListener('click', playPrev);

document.addEventListener('keydown', (e) => {
  if (e.target.closest('input, textarea') || e.metaKey || e.ctrlKey || e.altKey) return;
  const k = e.key.toLowerCase();
  if (k === '/') { e.preventDefault(); els.q.focus(); els.q.select(); }
  else if (!S) return;
  else if (k === 'n') els.next.click();
  else if (k === 'p') playPrev();
  else if (k === 'k' || (k === ' ' && e.target === document.body)) { e.preventDefault(); togglePause(); }
  else if (k === 'm') {
    if (vimeo) vimeo.getMuted().then((m) => vimeo.setMuted(!m));
    else els.video.muted = !els.video.muted;
  }
});

window.addEventListener('popstate', () => {
  const q = new URLSearchParams(location.search).get('q');
  if (q) search(q, { push: false });
  else location.reload();
});

const initial = new URLSearchParams(location.search).get('q');
if (initial) search(initial, { push: false });

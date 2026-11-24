# meh: a video search engine that plays what it finds

Type anything. **meh** searches the web like Google does, opens the result pages, inspects each one to find the video on it, ranks the videos by how well they match your words, and plays them back to back.

No API keys and no accounts.

## Android app

**Install:** download [`meh.apk`](meh.apk) on your phone and open it. Android will ask you to allow installs from your browser or file manager. Allow it, then tap **Install**.

The app does everything on the phone, with no computer or server needed:
- It searches the same engines as the desktop version, and excludes the same sites (YouTube and TikTok).
- It inspects result pages with hidden Android WebViews. They open each page, press play, and record the video requests the page makes. Those requests are then blocked, so nothing plays out loud in the background.
- Video files and HLS streams play in Android's own video player (ExoPlayer), which sends the right `Referer` header. Vimeo and Dailymotion play in their own embedded players.
- Turn the phone sideways, or tap the corner button, for full screen. Back exits full screen and then goes home.
- You can share text from any app to **meh** to search for it.

**Build it yourself:** in `android/`, run `./gradlew assembleRelease`. You need the Android SDK (platform 37, build-tools 37.0.0) and JDK 17 or newer; the Gradle wrapper fetches Gradle 9.9 itself. The signing key `android/app/meh.keystore` is kept in the repo on purpose, so every build installs over the previous one.

## Run it on a computer

You need [Node.js](https://nodejs.org) 18 or newer.

```bash
npm install      # also downloads the headless Chromium used for page inspection
npm start
```

Then open **http://localhost:3000**, type something, and press Enter.

## How it works

```
 your words ─► 1. SEARCH ─► 2. INSPECT ─► 3. RANK ─► 4. PLAY
```

1. **Search** (`lib/search.js`). Reads the public result pages of Google (Videos tab), Bing, Bing Videos and DuckDuckGo in parallel, the same pages you see in a browser. Google only answers browsers that run JavaScript, so its searches always go through the headless browser. Their links are merged round-robin so no single engine or site dominates. If a plain request is blocked, that search page is loaded in the headless browser instead.
2. **Inspect** (`lib/inspector.js`). Each result page (up to 40, five at a time) is examined in two stages:
   - **Static HTML.** Reads what the page declares: `og:video` and Twitter player tags, JSON-LD `VideoObject` data, `<video>` and `<source>` tags, and embedded Vimeo or Dailymotion players. This is fast and needs no browser.
   - **Live browser.** If nothing was declared, the page is opened in headless Chromium. The browser scrolls, presses play, and watches the network for video files (`.mp4`, `.webm`) and HLS streams (`.m3u8`). It then reads the live DOM of every frame. This catches players that load their video with JavaScript.
   - Ad networks are blocked, and tiny hidden videos are discarded. See **Ads** below for how pre-roll ads are kept out.
3. **Match strictly.** Every word you type must appear in the video's title, its page's title, its description, or its tags/keywords. Words that only appear somewhere in the page's body text don't count. Searches of 4 or more words may miss one word; text in "quotes" must appear exactly. Word forms count ("volcano" matches "volcanoes"), different words don't ("dragon" doesn't match "dragonball"). The status line shows how many unrelated videos were hidden.
4. **Rank** (`lib/ranker.js`). Each video gets a relevance score from 0 to 100. The score depends on how much of your query appears in the video's title (weighted highest), the page title, the description and the text around the video. Phrase matches and the search engine's own ranking add bonuses. The player also spreads sites out so one domain doesn't take over the queue.
5. **Play** (`public/app.js`). Results stream to the page live, so the first video starts within seconds while the rest of the web is still being inspected.
   - Direct video files play in the built-in player.
   - HLS streams play through [hls.js](https://github.com/video-dev/hls.js).
   - Vimeo pages use their own embedded player, so the app knows when a video ends.
   - Video is streamed through the local server (`lib/proxy.js`) with the right `Referer` header, so sites that block other players still work.
   - When a video ends, the next one starts. A video that fails to load is skipped. When the queue runs low, the next page of search results is fetched.

## When a video won't play (Android)

Each video gets up to three tries before it's skipped:

1. **The app's own player** (ExoPlayer, with software-decoder fallback), sending the same cookies, browser identity and referring page the hidden browser used. If the site refuses or the link has expired, the page is reopened once for a fresh link.
2. **Chrome's player.** The video plays in a bare Chrome player that presents itself as the video's page. Chrome brings its own decoders (VP9/AV1/… in software where the phone's hardware can't), its own network stack (which bot-protected sites accept), and the page's cookies. HLS uses Chrome's native support, or the bundled hls.js.
3. **The site's own page.** The original page is opened, its biggest video started and enlarged to fill the screen.

Copy-protected (DRM) video is skipped straight away; no player can help there. When a video is skipped, the screen says why. `npm run test:players` runs the Android app's fallback players in real Chromium.

## Ads

**Ad blocker.** Both versions block about 43,000 ad and tracking domains: EasyList's ad-server list plus the main video-ad networks (Google IMA/DoubleClick, FreeWheel, SpotX, Teads, …). The list is `data/adblock-domains.txt`; refresh it with `node tools/update-adblock.js`. It applies to everything the hidden browser loads and, on Android, to the Vimeo/Dailymotion players. Blocking the ad *scripts* means most players never load an ad at all and go straight to the real video. The status line shows how many ads were blocked per search. Hosts the app needs for real video (Vimeo, Dailymotion, JW Player, Brightcove, big CDNs) are never blocked. EasyList is dual-licensed GPLv3 / CC BY-SA 3.0.

Players usually request their **pre-roll ad first** and the real video only after the ad. Without care, the app would take the ad for the video, play it, and move on. Several layers prevent that:

1. **Keep watching.** While inspecting a page, the app blocks every video download it sees. That makes most players give up on the ad and load the real video, so the app keeps watching after the first request instead of stopping there. Later requests are preferred.
2. **Ad containers.** Videos inside ad slots on the page (IMA, VAST/VPAID, `ad-container`, `preroll`, `advert`, …) are rejected, both in the live page and in the plain HTML, and so are frames served from ad networks.
3. **The page's own length.** The app reads each video's actual length from the start of the file (MP4 `mvhd` header, WebM `Duration`, or the HLS segment list) without downloading it (`lib/media.js`, `MediaProbe.kt`). If the page says its video is, say, 5 minutes long, a clip shorter than half that is rejected. There is no general length limit any more, so short clips play; only near-empty clips under 5 seconds are skipped.
4. **Known ad servers** are blocked outright. The page being inspected is never blocked itself, even if its address contains words like "preroll".

## Adult sites (opt-in, 18+)

RedGIFs, rule34video.com, rule34.paheal.net and rule34.xxx sit behind an **Adult sites (18+)** switch (on the results screen; remembered). While it's off, they aren't contacted and their videos found by other engines are hidden.

- **RedGIFs:** its website loads everything from its data service with a guest token every visitor gets automatically (no account or key — see RedGIFs' [temporary tokens](https://github.com/Redgifs/api/wiki/Temporary-tokens)); the app does the same. RedGIFs pages declare the *silent* copy of each clip, so the app always plays the copy with sound.
- **rule34video.com:** its search page lists each video with title and length; video pages declare their file in JSON-LD.
- **rule34.paheal.net:** search results carry each post's file type and tags, so only videos are opened.
- **rule34.xxx:** behind Cloudflare, so its pages are read through the hidden browser, which waits for the "checking your browser" step to clear. Its data service needs an account key, so it isn't used.

**Content depicting minors is always excluded** — this is not a setting. Queries asking for it are never sent to these sites; booru searches carry minus-tags for it (`-loli -shota …`); results whose tags, titles or text mark it are dropped; and every adult-site page's title, description, keywords and tag lists are checked again before its video can be queued. The terms live in `lib/util.js` / `Adult.kt`.

## Unwanted content

Always removed, not a setting:
- **Music videos everywhere:** HMV, PMV and AMV, and anything titled or tagged as a music video, official video or audio, lyric video or visualizer. vevo.com is an excluded site.
- **Male-focus content on adult sites:** tags such as male focus, male only, solo male, yaoi, gay and bara. Plain "male" doesn't count, since that tag appears on most mixed content.

rule34.xxx and paheal searches carry `-pmv -hmv -male_focus -yaoi` as minus-tags; everything else is filtered by titles, descriptions and tags. The word lists are in `lib/util.js` and `Adult.kt`.

## Excluded sites

**YouTube and TikTok never show up.** YouTube isn't searched. Any YouTube or TikTok links the other engines return are dropped, and YouTube or TikTok players embedded in articles are ignored (the article's own video plays instead, if it has one). A final check keeps them out of the queue. The list is `BLOCKED_SITES` in `lib/util.js` (desktop) and `android/app/src/main/java/app/meh/Util.kt` (Android); add a domain there to exclude another site.

## Controls

| Key | Action |
|---|---|
| `N` | Next video |
| `P` | Previous video |
| `K` / Space | Pause / play |
| `M` | Mute |
| `/` | Focus the search box |

Other controls:
- **Autoplay next.** Turn it off to stop after each video.
- **Skip watched.** Videos you've already seen are skipped. They are remembered in your browser only.
- **Minimum length** slider (Any, 5 s … 20 min). Hides shorter videos from the queue straight away, without a new search. A video whose length isn't known yet stays queued and is skipped once it loads if it's too short. The playing video is never interrupted. Remembered, on desktop and Android.
- **Search in the address.** The search is in the URL (`/?q=...`), so you can bookmark it or reload the page.

## Settings (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `3000` | Port to listen on |
| `HOST` | `127.0.0.1` | Interface to bind to. Keep it local; see the note below. |
| `MEH_CONCURRENCY` | `5` | Pages inspected at once |
| `MEH_MAX_PAGES` | `40` | Result pages inspected per search page |

## Limits

- **Can't play:** DRM-protected services (Netflix, Disney+ and similar), pages behind a login, and sites that block automated browsers. These are skipped.
- **Search pages change.** If a search engine changes its page layout, its parser in `lib/search.js` may need updating. Because several engines are used, one breaking doesn't stop the app.
- **Personal use only.** Run it on your own machine. Scraping search engines and re-streaming video goes against some sites' terms of service.

## Tests

```bash
npm test          # end-to-end: fixture site → inspection → ranking → proxy → real browser playback
npm run stress    # hostile web + fuzzing + concurrency (about 2 minutes)
cd android && ./gradlew testDebugUnitTest   # Android engine: unit + stress tests on the JVM
```

The tests need `ffmpeg`, which they use to generate sample videos.

- **`npm test`** starts a local fixture site whose pages hide video in different ways: a plain `<video>` tag, JSON-LD, a JavaScript player that loads an HLS stream, an embedded Vimeo iframe, YouTube-only and TikTok-only pages (which must yield nothing), pre-roll ad players (ad then video, ad only, IMA-style ad container, page declaring a longer video), an ad-only page and a text-only page. A browser check confirms an ad-length video is skipped at play time. It also parses **real saved result pages** from Bing, Bing Videos, and the Google and DuckDuckGo bot checks (`test/fixtures/real/`).
- **`npm run stress`** points the search at a deliberately hostile "web" and checks that every search still finishes, stays within memory, and finds the real videos. The hostile pages include:
  - servers that never answer, send data a byte at a time, or never stop sending,
  - redirect loops, error pages and binary junk,
  - scripts stuck in an infinite loop, and alert/popup spam,
  - 300-video pages, 25 MB pages and data nested 200,000 levels deep,
  - an endless video file, to check sniffing never downloads it.

  It also fuzzes every parser with 3,000 random pages, runs 8 searches at once, checks that closing the app mid-search stops the work, and abuses the proxy.
- **Android tests** run the same real search pages through the Kotlin engine and require the same results as the desktop version. They also cover pathological and fuzzed HTML and a hostile local server.

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

**Build it yourself:** in `android/`, run `./gradlew assembleRelease`. You need the Android SDK (platform 35, build-tools 35.0.0) and JDK 17 or newer. The signing key `android/app/meh.keystore` is kept in the repo on purpose, so every build installs over the previous one.

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
   - Ad networks are blocked, and pre-rolls, tiny hidden videos and clips under 20 seconds are discarded.
3. **Rank** (`lib/ranker.js`). Each video gets a relevance score from 0 to 100. The score depends on how much of your query appears in the video's title (weighted highest), the page title, the description and the text around the video. Phrase matches and the search engine's own ranking add bonuses. The player also spreads sites out so one domain doesn't take over the queue.
4. **Play** (`public/app.js`). Results stream to the page live, so the first video starts within seconds while the rest of the web is still being inspected.
   - Direct video files play in the built-in player.
   - HLS streams play through [hls.js](https://github.com/video-dev/hls.js).
   - Vimeo pages use their own embedded player, so the app knows when a video ends.
   - Video is streamed through the local server (`lib/proxy.js`) with the right `Referer` header, so sites that block other players still work.
   - When a video ends, the next one starts. A video that fails to load is skipped. When the queue runs low, the next page of search results is fetched.

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

- **`npm test`** starts a local fixture site whose pages hide video in different ways: a plain `<video>` tag, JSON-LD, a JavaScript player that loads an HLS stream, an embedded Vimeo iframe, YouTube-only and TikTok-only pages (which must yield nothing), an ad-only page and a text-only page. It also parses **real saved result pages** from Bing, Bing Videos, and the Google and DuckDuckGo bot checks (`test/fixtures/real/`).
- **`npm run stress`** points the search at a deliberately hostile "web" and checks that every search still finishes, stays within memory, and finds the real videos. The hostile pages include:
  - servers that never answer, send data a byte at a time, or never stop sending,
  - redirect loops, error pages and binary junk,
  - scripts stuck in an infinite loop, and alert/popup spam,
  - 300-video pages, 25 MB pages and data nested 200,000 levels deep,
  - an endless video file, to check sniffing never downloads it.

  It also fuzzes every parser with 3,000 random pages, runs 8 searches at once, checks that closing the app mid-search stops the work, and abuses the proxy.
- **Android tests** run the same real search pages through the Kotlin engine and require the same results as the desktop version. They also cover pathological and fuzzed HTML and a hostile local server.

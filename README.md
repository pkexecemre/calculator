# Calculator

An open-source Android client for adult video sites, disguised as a calculator.
Free software under the GPL-3.0, no ads, no telemetry, no accounts.

- **Package:** `com.simplecalculator.app`
- **Min SDK:** 26 · **Target:** 35
- **License:** GPL-3.0 (see [LICENSE](LICENSE))

## Why it looks like a calculator

The launcher name, icon and first screen are a working calculator. A 4-6 digit
code, chosen at first launch, opens the real app. The code is stored as
PBKDF2-SHA256 with a random salt; five wrong entries trigger an increasing
lockout. The app re-locks 90 seconds after it goes to the background.

## Features

**Playback**
- Native playback through Media3 (ExoPlayer). Nothing is proxied or re-hosted:
  the stream URL is resolved on the device and played directly.
- Background audio with a system media notification and lock-screen controls
  (`MediaSessionService`).
- Fullscreen (landscape, system bars hidden), picture-in-picture, double-tap to
  seek ±10 s, swipe-left for brightness / swipe-right for volume, autoplay next,
  remembered playback speed, and a quality picker with automatic fallback to the
  next stream on failure. Title/art are shown in the media notification and
  lock-screen controls.

**Library**
- Saved videos, watch history with the exact position you stopped at, and a
  "continue watching" rail on the home feed.
- Offline downloads: progressive MP4 when the site has one, otherwise HLS
  segments joined into a single transport stream and played from local storage.
  The quality selected in the player is the quality downloaded. Failed downloads
  show the reason and can be retried in place. Files live in app-specific storage
  with no MediaStore entry and a `.nomedia` marker, so they never show up in the
  gallery.

**Discovery**
- Four categories that never mix: **Live**, **JAV**, **Hentai** and **Archive**,
  shown as four compact chips. JAV and Hentai sit behind the 18+ gate.
- Source, tag and content-type filters live in one searchable **Filters** sheet
  instead of horizontal chip rows, so 30 sources do not turn the feed into a
  scroll marathon.
- Sources load in parallel (6 at a time, 12 s timeout), are deduplicated, and
  unreachable ones are named in the feed/search instead of silently returning
  an empty list. Three failures pause a source for 30 minutes; Settings has a
  live health dashboard with reset buttons.
- Hentai listings are grouped into **series**: the same title appears once, and
  opening it shows all episodes sorted by number, with the same episode mirrored
  across sites collapsed into one entry. The series screen re-searches the title
  to pull in episodes that only some sources list, marks watched episodes, shows
  per-episode progress and offers a **Next** button for the first unwatched one.
- Feed cards show a resume bar for anything partially watched, and the player
  announces “Resumed at mm:ss” with a one-tap **Start over**.
- Search remembers your last ten queries as tappable chips.
- Long-press any card for quick actions: **Save to library** or **Hide from
  feed**. Hidden titles are filtered from every feed/search and can be restored
  from Settings.
- **Uncensored** and **Subtitled** pills on the JAV tab (and **Subtitled** on
  Live) filter releases by their tags/slugs, e.g. MissAV's
  `…-english-subtitle` and `…-uncensored-leak` variants.
- **Live is Western by default**: 141tube and Avgle are selectable in Filters
  but are not mixed into the default Live feed, and CJK-titled cards are
  dropped. **JAV trends ordinary studio releases**: hidden-camera, voyeur and
  toilet titles are filtered out, releases with a product code and the studio
  sources rank higher, and TokyoMotion is opt-in through Filters.
- Japanese titles are machine-translated to the device language, cached on disk
  and shown above the original; toggle in Settings.
- The player attaches subtitle tracks that a site publishes through HLS
  (`EXT-X-MEDIA:TYPE=SUBTITLES`), offers Off / English / Türkçe, and translates
  the cues for Turkish on demand.
- **On-device captions** for videos that ship without subtitles: a tee in the
  audio sink feeds decoded PCM to whisper.cpp. It transcribes 4 s windows with
  1 s of lead-in, so the first line lands within a few seconds of playback
  starting and then updates continuously. Silence is skipped, words repeated
  across the overlap are removed, stale windows are dropped instead of falling
  behind, and pausing transcribes the tail instead of discarding it. A model
  that is too slow for the phone automatically falls back to the bundled one.
  The bundled model is multilingual: English speech is transcribed and Japanese
  (JAV/hentai) is translated to English, while Turkish goes through the cached
  translator. All inference happens on the phone — no audio leaves the device.
- The **Base model ships inside the APK** (57 MB), so captions work on a fresh
  install with no download. First-run offers larger models — Small (181 MB) or
  Large v3 Turbo (547 MB) — with a progress bar and a Skip button; the same
  picker lives in Settings.
- Hentai search filters out results whose titles do not match the query, and a
  **Studios / User uploads / VR-VTuber** chip row keeps studio anime separate
  from user-generated or VR slop.
- Feed ranking driven by the stars you follow: uploader affinity from watch
  events, a sighting penalty, diversity re-ranking, and a two-to-one mix so a
  follow biases the feed instead of owning it.
- Star pages with avatar, catalog and follow state.

**Privacy**
- Screenshot / recents blocking (FLAG_SECURE) toggle.
- Optional biometric unlock, lock-on-background (default on) and a one-tap
  “Lock now” action in Settings. Audio keeps playing while the vault is locked.
- No analytics, no crash reporting, no network calls other than the sources.

**Interface**
- Material 3, dark-first, English and Turkish.
- One visual language throughout: a segmented pill switcher for Live/JAV/Hentai/
  Archive (and for Library tabs and language), quiet filter chips, a featured
  hero card with a scrim, section headers with an accent bar, watch bars on
  partially watched cards, and pluralised episode badges.
- Artwork is never cropped away: covers are drawn fitted over a blurred bleed of
  themselves (portrait posters in a 3-up grid, stills in a 2-up grid), and every
  CDN gets its site Referer so hotlink-protected thumbnails actually load.
- Series screens show `Episode N` with the mirror tag (`sub-eng`, `uncensored`,
  `thai`) instead of repeating the whole title for every source.

## Sources

| Category | Sources |
|---|---|
| Live | Pornhub, XVideos, Eporner, SpankBang, xHamster, XNXX, 141tube, Avgle, YouJizz, AnaLdin, hdsex.org |
| JAV | MISSAV (missav123 mirror), KissJAV, 85po, JAV-NONI, JAVSB, TOKYO Motion, MRDOUGA, JAV-FUN |
| Hentai | aki-h, HentaiHaven, HentaiCloud, Iwara, Iwara.ai, OHentai, Hanime1, HentaiMama, Rule34Video |
| Archive | Internet Archive |

That is all 21 sites listed by [MISSPipe](https://codeberg.org/Kdroidwin/MISSPipe)
plus HentaiHaven/HentaiCloud, AnaLdin/hdsex.org and four extra sites —
**29 registered sources**. Pornhub/XVideos/Eporner, the two Iwara clients and Internet Archive
use dedicated parsers (plus OHentai, YouJizz and Avgle); the rest are described by `SiteSpec` entries in
`data/source/generic/GenericSources.kt` and share one HTML adapter. Endpoints
and selectors follow MISSPipe's public extractor definitions. Listing pages for
all 23 sources are now exercised against real or archived captures in
`LiveFixtureTest`; a few player-config branches are synthetic because reader
proxies strip JS. A site redesign can still break a source, but every adapter
is at least exercised against real markup. Nothing requiring a WebView is
registered.

Every source implements the same `Source` interface. Adding one is a single
class or spec plus a registry line; the feed, ranking, player, downloads and
library do not change.

## Build

```bash
export JAVA_HOME=/usr/lib64/jvm/java-21-openjdk    # AGP 8.7 does not accept JDK 25
export ANDROID_HOME=$HOME/android-sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A Gradle 8.14.3 wrapper is included, so `./gradlew` works without a system Gradle.

`tools/bench_stt.sh [adb-serial]` installs the debug APK, feeds the bundled
English and Japanese samples through the real caption pipeline, and prints the
inference time and RTF for each window — useful for checking a phone's
real-world caption speed.

Debug builds trust **user-installed CA certificates** as well as the system
store, so TLS interception by a VPN/ad-blocker does not break the sources; a
release build keeps the normal system-only trust store.

## Tests

`LIVE_PROBE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveProbeTest'` walks
every registered source against the live site and prints listing/stream results
per source. Last run from this machine: 24 of 28 sources returned cards and 24
resolved a playable stream or embed. The four remaining ones are blocked at the
network level here — Cloudflare 403 (spankbang, missav), a dead DNS record
(jav.sb) and a refused connection (hentaicloud) — not parser errors; the app
names them as unreachable and pauses them after three failures.

Native extraction tricks worth knowing: OHentai serves the MP4 as the sibling of
its poster (`…/Episode 2.jpg` → `…/Episode 2.mp4`), YouJizz embeds a signed
`data-clip` URL on every card, Avgle resolves through `video_source.php`, KVS
clones hide the file in base64 `video_url`, MissAV hides HLS in a JS packer, and
aki-h resolves `/watch → /video/<id> → v.aki-h.com → /e|f/<iden> → servers →
aki-h.stream` where the playlist sits under `/file/<code>/`. Google Drive hosted
aki-h episodes are played through `drive.usercontent.google.com` with Range.

`JsPacker` decodes the three packer layers used by these players (standard
`p,a,c,k,e,d`, `Cookie.Run(base64)`, and the custom charset cipher), so playlist
URLs come out without a WebView. Third-party players hosted on lplaygo /
lulustream are followed one level and unpacked the same way, which made JAV-NONI
and JAV-FUN native as well — these hosts serve a JS-only page when the request
carries a full browser `Accept` header, so embed fetches stay minimal.

`./gradlew :app:testDebugUnitTest` runs **101 tests**: saved real/archived pages
for all 23 sources (`LiveFixtureTest`), synthetic stream-extraction cases, the
original parser fixtures, source-health behavior, calculator expressions and
the feed mixer. A site change now fails a test instead of the UI.

## Changes in `calculator-v2`

This copy keeps the same architecture as the original folder and fixes the
issues found in review:

- player state is reset when opening another video, and the MediaController
  race/listener leak is fixed;
- lockout penalties now escalate, and the correct code works during cooldown;
- “clear history” clears both watch history and ranking events;
- source and tag filters reset cleanly when switching categories, tags are a
  single selection mapped to each source's search endpoint, and the documented
  2:1 follow mix is actually applied;
- notification permission is requested on Android 13+, media sessions are
  restricted to our UI and system surfaces, and playback errors offer the next
  quality variant instead of only replaying the same URL;
- source loads are parallel, time-limited and health-tracked with a Settings
  dashboard and one-click reset; downloads report errors and support retry;
- the session is observable process-wide, so locking from anywhere immediately
  returns to the calculator; PiP, autoplay, gesture volume/brightness, media
  metadata, remembered speed, selected-quality downloads, `.nomedia` and a Room
  3→4 migration are in place;
- downloads write to a `.part` file, honour cancellation, and clean up on
  cancel/failure; HentaiCloud's fallback regex is fixed;
- the embedded WebView only allows the exact host or its subdomains;
- all 21 MISSPipe sites are added through dedicated adapters or one shared
  `SiteSpec`-driven HTML source, categories expand from 2 to 4, and every
  listing adapter is fixture-tested against a real/archived page;
- the global HTTP interceptor no longer sends the Pornhub age cookie to every
  third-party host;
- calculator unary minus/trailing decimal edge cases, dead code and stale docs
  are cleaned up, and a Gradle wrapper plus `.gitignore` are added.

## Distribution

Google Play prohibits adult content, so this ships sideloaded. No Play Billing,
no in-app purchases, no tracking.

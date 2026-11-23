# Calculator

An open-source Android client for Pornhub, hidden behind a working calculator.
Free software under the GPL-3.0: no ads, no accounts, no telemetry.

- **Package:** `com.simplecalculator.app`
- **Min SDK:** 26 · **Target SDK:** 35
- **License:** GPL-3.0 (see [LICENSE](LICENSE))

## Why it looks like a calculator

The launcher name, icon and first screen are a real calculator. A 4-6 digit
code, chosen at first launch, opens the real app. It is stored as
PBKDF2-SHA256 with a random salt; five wrong entries trigger an increasing
lockout, and the app re-locks 90 seconds after it goes to the background.
Audio keeps playing while the vault is locked.

## One source

**Pornhub is the only source.** The multi-source scrapers are gone: no Live,
JAV, Hentai or Archive adapters, no shared HTML adapter, no source picker, no
cross-site merge or series grouping. Nothing in the app talks to another site.

`PhClient` fetches trending and search listings, model pages, video pages, the
comment endpoint and the related-video API. `PhParser` turns them into the same
`VideoSummary` / `VideoDetail` used by the feed, player, downloads and library.
No WebView is involved in extraction.

## Features

**Playback** — Native Media3 (ExoPlayer); the stream URL is resolved on the
device and played directly, nothing is proxied. Background audio with a system
media notification and lock-screen controls. Picture-in-picture, fullscreen,
double-tap seek, brightness/volume swipes, remembered speed and a quality
picker, with a one-tap switch to the next variant when a stream fails.

**Downloads** — Progressive MP4 when the page has one, otherwise HLS segments
joined into one transport stream, at the quality selected in the player. Files
stay in app-specific storage behind a `.nomedia` marker; failures report the
reason and can be retried.

**Library** — Saved videos, history with the exact stop position, and a
continue-watching rail. Followed stars have an avatar, catalog and follow
state. Ranking mixes uploader affinity, a sighting penalty and diversity so a
follow biases the feed instead of owning it. A single view is not a preference:
an uploader needs a second watch before it can push its catalogue into the feed.

**Feed language** — Pornhub's most-viewed feed mixes Western clips with Japanese
catalogue uploads. Japanese-script titles and JAV product codes are held back
from the default feed so watching one Japanese clip cannot flood it with more.
The toggle in Settings brings them back; search results and star catalogues
always show them because those are explicit requests.

**Privacy** — Screenshot/recents blocking (FLAG_SECURE), optional biometric
unlock, lock-on-background (default on) and a one-tap "Lock now".

**Language and captions** — English and Turkish. Japanese titles are
machine-translated on request and cached. HLS subtitle tracks are attached when
published, with Off / English / Türkçe and on-demand cue translation. Videos
without subtitles get on-device captions: a tee in the audio sink feeds decoded
PCM to whisper.cpp, with Base and Tiny models inside the APK and Small or Large
Turbo as optional downloads. No audio leaves the device.

## Reliability

The conversion concentrated on not showing a blank screen.

- **Retrying HTTP client.** Three attempts with exponential backoff (300 ms
  base) plus jitter. `Retry-After` is honoured and capped at 5 s.
- **Error classification.** 403 and 404 are final and are not retried; 408,
  425, 429 and 5xx are. A persistent age-verification page is reported as such
  instead of being rendered as an empty feed.
- **Disk cache with stale fallback.** HTML and JSON are kept in a 64 MiB
  OkHttp cache. Normal requests revalidate, while a failed request is served
  the cached copy (up to 7 days old) and flagged stale.
- **Last-good feed.** Video rows live in Room and the feed order is persisted
  alongside them, so a cold start paints the previous feed in the same order
  before the network answers.
- **Parser fallbacks.** Listings try the current container, the older one, then
  the whole document; thumbnails try `data-image` then `src`; titles try the
  card heading, link title, then link text. Detail, uploader, comments and
  related each have a selector chain, related JSON is read from `related`,
  `mostViewed` and `topRated`, and streams are filtered to real `.mp4` /
  `.m3u8` URLs so a `get_media` stub cannot replace a working variant.
- **Fixture tests.** Captured Pornhub pages in `app/src/test/resources/ph/`
  and `live/ph.html` run through the real parser; markup drift fails the suite.
- **Detail prefetch and caching.** A card press warms the detail; resolved
  streams are cached for ten minutes. If a refresh returns an unavailable
  page, the previous good resolution is kept.
- **Player variant fallback.** A failed stream offers Retry and, when other
  qualities exist, a switch to the next variant at the same position.
- **Downloads that finish or fail cleanly.** Transfers write to a `.part` file
  and are renamed into place only after a size check, so a killed worker cannot
  leave a half-playable video in the library; failures keep the reason and can
  be retried in place.
- **No silent blanks.** A failed refresh keeps the cached feed and shows a
  "showing saved videos" banner with a retry action instead of clearing the
  list.

## Build

```bash
export JAVA_HOME=/usr/lib64/jvm/java-21-openjdk    # AGP 8.7 does not accept JDK 25
export ANDROID_HOME=$HOME/android-sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A Gradle 8.14.3 wrapper is included. `tools/bench_stt.sh [adb-serial]` prints
caption inference time and RTF for the bundled samples; normal builds keep its
diagnostic activities unexported.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Offline and deterministic: the Pornhub parser against real and archived pages,
retry policy, the calculator engine, ranking, download arithmetic, captioning
and PCM decoding.

## Distribution

Google Play prohibits adult content, so this ships sideloaded. No Play Billing,
no in-app purchases, no tracking.

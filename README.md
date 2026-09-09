# Thunder Play

An Android music player for a personal library that lives in Google Drive, plus a public web
player so a playlist can be handed to anyone with a phone browser. No server, and nothing here
leaves the free tiers.

**Start with [docs/SETUP.md](docs/SETUP.md)** — the app needs a service-account key before it can
show anything.

The catalog is the WAV tree in `music\`. `tools/transcode/` can mirror it to smaller AAC in
`music-mobile\`, and Settings can point the app there instead, but nothing maintains that mirror
and the app does not need it.

## What it does

- **Syncs with Drive.** Drop a track into `Music-And-Fx-Generated-Library\music\` (or add it
  through Drive on any device) and it appears in the app. Refresh and download are separate
  toggles in Settings, each with its own interval.
- **Plays with the screen off**, with prev / play / next and a Like button on the lock screen.
- **Crossfades** between tracks on an equal-power curve, configurable from 0–12 seconds. Repeat,
  shuffle and an editable *Up next* queue sit on the Now Playing screen; repeat-one fades a track
  into itself, so an ambience bed loops with no seam.
- **Rates tracks 1–5 stars**; "liked" is simply a rating of 1 or more, so the lock-screen heart
  and the star control are the same underlying value.
- **Reads the generator's own metadata.** The prompt, genre, intensity and instrument list live in
  each source WAV's RIFF header, and a 4 KB range request per track is enough to read them — so
  search covers prompts and instruments rather than only the truncated slug in the filename, tracks
  can be filtered by genre, and Now Playing shows the sentence the track was generated from.
  Durations come from the same header.
- **Says which prompts earn stars.** History's *Prompts* tab averages your ratings across the words
  and instruments behind them, so "tribal percussive" beating "ambient drone" is a fact rather than
  a hunch. Unrated tracks are not counted as zero, and a term needs three rated tracks to be ranked.
- **Records every play**, not just a counter, so History can show most-played and listening time
  over a window.
- **Filters** by stars, category, intensity and genre — independent and combinable, so "Beast Hunt,
  level III, four stars or better" is one state rather than a mode — and **orders** by name, most
  played, highest rated, recently added, or a stable random shuffle.
- **Shares** a single track as a file through the Android share sheet, or any playlist *or
  filtered view* as a link that streams in any browser. Every link expires after 7 days.
- **Deletes safely.** Removing a track from Drive moves it — and its source WAV — into
  `_ThunderPlayTrash\`. Nothing is ever permanently deleted.
- **Judges rival takes.** Switch on *A/B testing* in Settings for a tab that groups the two to
  four renders of one cue, plays them without crossfading, and files the keeper into
  `_ThunderPlayAB\good\` with the rest in `_ThunderPlayAB\bad\`. The prompt is read out of the
  source WAV's RIFF tags and written onto each judged file's Drive description, so both batches
  stay readable. Winners stay in the library; nothing is deleted.

## Layout

```
app/                     Android app (Kotlin, Compose, Media3)
  drive/                 service-account auth + Drive REST
  data/                  Room entities and DAOs
  playback/              crossfade player, media session, cache, downloads
  stats/                 ratings and play history (Room + Firestore)
  playlist/              playlists, share links, track export
  sync/                  catalog refresh, WorkManager jobs, Drive trash
  ui/                    Library, Now Playing, Playlists, History, Settings
web/                     the public share player (Firebase Hosting)
tools/transcode/         ffmpeg WAV -> AAC mirror
```

## Why it is built this way

Three constraints shaped most of the design, and each ruled out something simpler:

1. **Drive's `drive` scope is restricted.** Using it in a real app needs Google verification and a
   security audit; staying in "Testing" expires the refresh token weekly. A service account shared
   into the folder avoids all of it, at the cost of shipping a key inside the APK.
2. **Google blocks Drive files from being embedded on other sites.** A web player cannot stream
   from Drive at all, so shared tracks are copied to Firebase Storage and deleted when the share
   lapses.
3. **Safari on iOS will not play Opus.** The mobile format is therefore AAC in `.m4a`, which both
   ExoPlayer and every browser handle — about 40 MB more across the library, in exchange for share
   links that are not silent on an iPhone.

## Development

```bash
./gradlew assembleDebug
```

```bash
./gradlew test
```

`app/src/main/assets/drive-service-account.json` is gitignored and must never be committed; treat
a built APK as a secret, since the key travels inside it.

# Ideas

A backlog, not a plan. Nothing here is committed to, and the tiers are about value per unit of
work rather than any order things must happen in. Written 2026-09-09 against the state of the tree
at that point; if an item cites a file, check it still says what it says here before starting.

Two observations shaped the ordering.

The library side is well served already — filters that combine, seeded shuffle, bulk selection,
share links, A/B judging, Drive trash, a Firestore mirror. **Playback** is the thinner half.

And the richest data in the library is read and then discarded.
[`WavInfo`](app/src/main/java/com/thunderplay/library/WavInfo.kt) parses the prompt, genre,
intensity, instruments and comment out of a WAV's RIFF header, but its only caller is
[`AbTestService.readPrompt`](app/src/main/java/com/thunderplay/sync/AbTestService.kt). Nothing
stores it and nothing searches it, so the app knows less about a track than the file does. Most of
tier 1 follows from that.

## Tier 1 — done

Built on 2026-09-09. Each item below keeps its original description, with a note on what actually
landed; anything the implementation decided differently is called out there.

### 1. Index the WAV INFO metadata for the whole library

**Done.** `MetadataIndexer` reads six headers at a time and stores prompt, genre, intensity and
instruments; a track is only re-read when its checksum changes, and a failed read stays pending
rather than being recorded as an empty one. Runs after the walk in both the worker and a manual
refresh.

A refresh pass that range-reads 4 KB per track — `DriveRepository.readHead` already does exactly
this — and persists prompt, genre, intensity and instruments onto `TrackEntity`. Keyed on
`md5Checksum` so a track is only ever read once.

One change, four features: search over prompts rather than filenames (today's search is titles
only, and a title is a 48-character lowercased slug); filter by instrument and genre, which the
folder tree cannot express; the prompt shown on Now Playing; and "more like this" from shared
style words.

### 2. Prompt insights — which prompts earn stars

**Done.** `PromptInsights`, shown under a Prompts tab on the History screen. Unrated tracks are
not counted as zero - that would drag every common word down in proportion to how much of the
library is still unheard - and a term needs three rated tracks before it is ranked. A/B verdicts
are tallied per term alongside the averages. Exporting prompts back to the PC is still to do.

Ratings, play counts and A/B verdicts already exist. Crossed against prompt words and instruments
they answer a question nothing else can: *"tribal" plus "percussive" averages 4.1 stars, "ambient
drone" averages 1.8.* That turns the app from a player into a feedback loop for the generator, and
it is only possible because the generator's metadata and the judgements live in the same place.
Depends on #1. Pairs naturally with exporting "prompts worth re-running" back to the PC.

### 3. Store track duration

**Done.** Computed from the header's byte rate and sample-chunk size, shown in the library rows,
and used as the fallback length for the play-qualifying rule when the decoder has not parsed the
stream yet.

`TrackEntity` has no duration field. `TrackDescriptors.encodedDurationMs` recovers one from the
filename, and the WAV header fetched for #1 carries the authoritative figure for free (data chunk
size over byte rate).

Gets durations into rows, total runtime onto playlists and filtered views, and "SFX under five
seconds" into the filter set. It also closes a real gap: `PlayQualifier.qualifies` takes a nullable
duration and abandons the halfway rule when it is null, so a track shorter than 30 seconds can
never qualify as a play.

### 4. Repeat and shuffle as playback modes

**Done.** Repeat-one fades a track into itself, so a bed loops with no seam. Shuffle reorders the
queue in place rather than keeping a hidden permutation, since the queue is now visible.

[`CrossfadePlayer`](app/src/main/java/com/thunderplay/playback/CrossfadePlayer.kt) stubs
`handleSetRepeatMode` and `handleSetShuffleModeEnabled` to no-ops. For a library of game ambience
"loop this bed" is table stakes, and looping *through the existing crossfade* gives seamless
infinite ambience, which is what much of this library is for. Repeat-one carries most of the value;
shuffle mode is secondary, since the seeded shuffle already covers browsing.

### 5. Queue UI — up next, reorder, play next

**Done.** An Up next sheet on Now Playing, with move up/down and remove per row, plus Play next
and Add to queue in the library. Reordering is by menu rather than by dragging.

`LibraryViewModel.play(index)` hands the whole filtered view to the player and there is no way to
see or change it afterwards. A queue sheet on Now Playing with drag-reorder, plus "play next" in
the row menu.

## Tier 2 — strong value, fits the grain of the project

### 6. Saved views / smart playlists

`LibraryView` is already a small serialisable data class. Persist named ones — "Beast Hunt, 4 stars
and up, most played" — that stay live as the library grows, and let them feed the existing share
flow. Cheap for how often it would be used.

### 7. Fast triage mode

`Stars.Unrated` finds the unjudged tracks; nothing makes judging them quick. A dedicated loop —
auto-play ten seconds from the middle, swipe up to rate, swipe down to trash, advance — would clear
a backlog of takes in minutes. It is the A/B tab's logic applied to singletons.

### 8. Drive trash browser with undo

[`TrashService`](app/src/main/java/com/thunderplay/sync/TrashService.kt) moves things safely but
nothing surfaces `_ThunderPlayTrash`. A "recently trashed, restore" list makes culling reversible,
and reversible culling actually gets done.

### 9. Free-form tags

Category and level come from the folder tree. Tags — "boss", "menu", "rain", "unused" — cut across
it, sit in Firestore beside the ratings, and cost nothing at refresh time.

### 10. Duplicate finder

`md5Checksum` is on every row, so exact duplicates are one query. The eight-character fingerprint
in `TrackDescriptors` catches re-transcodes of the same render.

### 11. Recipient feedback on share links

A share is one-way today. Letting the web player collect a star or a vote per track and write it
back to Firestore turns "which of these six for the boss fight?" into a link rather than a
conversation — the A/B tab, aimed at the people the cues are actually for. Needs thought in
[`firestore.rules`](firestore.rules), since it means unauthenticated writes; scope them to a
`votes` subcollection under a share that is still live.

## Tier 3 — playback quality

- **Loudness normalisation.** Generated renders vary wildly in level, and crossfading from a quiet
  pad into a trailer hit is jarring. Compute a per-track gain once, at download or first full play,
  and apply it through the volume path the crossfade already owns.
- **A better web player.** A seek bar, shuffle, per-track download, and a manifest so it installs
  to a home screen. Cheap, and it is the surface other people actually see.
- **History depth.** Listening split by category, a calendar heatmap, "not played in six months" as
  a cull signal. The `plays` table already holds everything this needs.

## Tier 4 — new surfaces, narrower payoff

- **Home-screen widget** (Glance, play/pause and like). Best value per unit of work in this tier.
- **Android Auto.** `COMMAND_SET_RATING` is already wired for it; the missing piece is a
  `MediaLibraryService` browse tree. Worth it only if the driving actually happens.
- **Cast to a speaker.** Media3 has a Cast extension; useful for auditioning cues on real speakers.
- **Wear OS.** Probably not.

## Tier 5 — unglamorous, worth it anyway

- **CI.** **Done.** `.github/workflows/ci.yml` runs `./gradlew testDebugUnitTest` and
  `assembleDebug` on every push and pull request, uploads the APK as an artifact, and publishes
  it as the `latest` GitHub Release on `main` so the phone can download it. The Drive key is
  injected only when the `DRIVE_SERVICE_ACCOUNT_JSON` secret is set; because the repo is public,
  that secret leaks via the APK unless the repository is made private first.
- **Ratings and history export to Drive.** The curation work is the irreplaceable asset here and it
  lives in one Firestore project. A periodic JSON dump into the library folder makes it survivable.
- **Free-tier usage readout in Settings.** The README promises nothing leaves the free tiers; a
  Storage and Firestore usage line, plus a warning before an oversized share, would demonstrate
  that rather than assert it.
- **Crash reporting.** Firebase is already configured. Diagnostics today need the log pulled off
  the phone by hand.
- **Service-account key hardening.** The key ships inside the APK, deliberately and for the reasons
  the README gives. If it ever leaks the fix is a Cloud Function proxy, at the cost of the "no
  server" constraint. Recorded here as a known trade, not as work.

## Deliberately not doing

- **EQ** — fiddly across two ExoPlayer instances, little payoff for this material.
- **Playback speed and pitch** — no use case here.
- **Audio fingerprinting for near-duplicates** — expensive, and the prompt metadata gets most of
  the way there.
- **Lyrics and artwork** — WAVs carry neither.
- **A permanent public portfolio page** — contradicts the everything-expires design of shares. That
  is a decision to make, not a feature to add.

## What is next

Tier 1 is built, so the top of the list is now tier 2. **#6 saved views** is the cheapest thing
left with daily value, and **#7 fast triage** is what turns the indexed prompts into a rated
library quickly - which is also what makes #2's averages mean anything, since a term needs three
rated tracks before it is ranked.

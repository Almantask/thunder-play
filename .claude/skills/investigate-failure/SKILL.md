---
name: investigate-failure
description: Diagnose a failure the user hit while testing Thunder Play on their phone. Use this whenever they report that something in the app broke, failed, errored, "doesn't work", shows a message they don't understand, or when they say they have sent or uploaded logs. It explains where the phone's diagnostics log lands, how to read it from the PC, and the failure signatures this project has already hit — so start here rather than guessing at causes.
---

# Diagnosing a failure on the phone

The app is installed by downloading an APK, so there is no `adb logcat`. Everything you learn comes
from the app's own diagnostics log or from reproducing the call against the live services.

## Get the actual error before forming a theory

The single most useful lesson from this project: reproducing the failing call takes about a minute
and answers the question outright, whereas reasoning about which line might be at fault burns turns
and has repeatedly landed on the wrong answer. Two real examples:

- A `400` from Drive was guessed at for several rounds. Replaying the HTTP call showed the query
  contained a literal, unevaluated Kotlin template.
- An empty diagnostics log looked like a logging bug. Replaying the upload returned
  `403 storageQuotaExceeded` — a hard platform limit, not a bug at all.

So: read the log, or replay the call. Then theorise.

## Where the log comes from

`DiagnosticsLog` writes to app-private storage on the phone. The user gets it out via
**Settings → Diagnostics**, which offers **View** (read it on the phone) and **Send** (Android
share sheet).

**The app cannot upload it to Drive itself.** A service account has no storage quota, so Drive
accepts an empty file record and rejects the bytes with `403 storageQuotaExceeded`. If you find a
0-byte log in Drive, that is the cause — not a truncation bug. Sending via the share sheet uploads
it as the user, against their own quota, which works.

The convention is that the user saves it into `_ThunderPlayLogs/` in the Drive library folder.

## Reading it

```bash
pwsh -File tools/fetch-log.ps1 -List
```

```bash
pwsh -File tools/fetch-log.ps1 -Tail 80
```

Fetches through the Drive API rather than the local mirror, because Drive for Desktop can leave a
placeholder that looks empty while the real content sits in the cloud. It takes the newest file in
the folder whatever its name, since the share sheet and the old uploader name it differently.

If there is no log yet, ask for one before speculating — one round trip beats three guesses.

## Checking the services directly

```bash
pwsh -File tools/check-drive.ps1
```

Walks the whole chain — key parses, token exchange, folder shared with the service account,
`music/` and `music-mobile/` populated — and names whichever step fails.

```bash
pwsh -File tools/check-drive-trash.ps1
```

Read-only listing of Drive's trash, for when files have gone missing.

To replay an arbitrary Drive call, copy the auth preamble from `tools/fetch-log.ps1`; it mints a
JWT with .NET's RSA and exchanges it in about five lines.

## Signatures already seen in this project

| Symptom | Cause |
|---|---|
| `403 storageQuotaExceeded` on any write of file *content* | Service accounts have no Drive storage quota. Listing, downloading and moving files are fine; writing bytes is not. Needs a different destination, not a retry. |
| `400 Invalid Value` from `files.list` | Malformed `q`. Check for an unevaluated string template — these compile fine and only fail at runtime. |
| Share fails with a permission error | Storage rules never deployed, or Anonymous auth not enabled. `npx firebase-tools deploy --only firestore:rules,storage`, then Firebase console → Authentication → Anonymous. |
| A fix "didn't work" | Check the APK's build time against the fix. Installing a cached download of the previous build looks exactly like a failed fix. |
| Track appears but will not play | Drive Desktop may still be uploading, or the catalog is stale — re-run Refresh. |
| Library empty after a refresh | `music-mobile/` missing or still uploading. Run `tools/transcode/transcode.ps1`. |

## Reporting back

Say what the error actually was, what caused it, and what you changed. If the log was empty or
absent, say that plainly and ask for one rather than presenting a guess as a diagnosis — a
confident wrong answer costs the user a build cycle to disprove.

When the cause is a platform limit rather than a bug, say so explicitly and describe the workaround.
Those are worth recording in `docs/SETUP.md`, because they will otherwise be rediscovered.

---
name: publish-apk
description: Build the Thunder Play debug APK and publish it to the Google Drive _apk folder so it can be downloaded and installed on the phone. Use this as the FINAL step of any task that changed app code, and whenever the user says "ship it", "publish the build", "put it on my phone", "make me an APK", "I want to test this", or otherwise signals they want to try the change on a real device. Do not run it mid-task or after documentation-only changes.
---

# Publishing a build to the phone

There is no USB cable in this setup. The phone gets new builds by downloading
`_apk/thunder-play.apk` from Google Drive, which Drive for Desktop uploads from
`E:\Music-And-Fx-Generated-Library\_apk\`.

## When to run this

Run it **once, at the very end of a task**, after the code is written, building and tested.

Publishing mid-task is actively unhelpful: each publish pushes ~29 MB through Drive, clutters the
music library folder with churn, and produces builds that do not correspond to a finished piece of
work. The user has asked specifically for this not to happen. If you find yourself wanting to
publish before the task is done, that is a sign the task is not done.

Skip it entirely when the change cannot affect the app: edits to `docs/`, `README.md`, the
transcode script, the web player, or the `tools/` scripts. A new APK for a documentation fix wastes
the user's bandwidth and their attention.

## Steps

### 1. Confirm the build is actually good

Never publish something you have not built and tested in this session. A broken APK on the phone
costs far more to discover than a failing build here.

```bash
./gradlew assembleDebug testDebugUnitTest
```

If either fails, stop and fix it. Do not publish.

### 2. Check the service-account key is present

```bash
ls app/src/main/assets/drive-service-account.json
```

Without it the app builds fine but shows "Drive is not connected" and lists nothing, which looks
exactly like a regression. If it is missing, say so and stop rather than shipping a build that
cannot work.

### 3. Publish, and wait for Drive to finish

```bash
pwsh -File tools/publish-apk.ps1 -SkipBuild -WaitForSync
```

`-SkipBuild` because step 1 already built it. `-WaitForSync` blocks until Drive's upload queue
drains — without it you can tell the user it is ready while Drive is still uploading, and they
install the *previous* build and report that your fix did not work.

The script prints size, build timestamp and a short SHA-256. Keep the timestamp: it is how the user
tells a fresh download from a cached one.

## What to tell the user

Report the build time and what changed, so they can confirm the phone got the right file:

> Published — built 20:41:07, 28.8 MB, upload complete.
> Drive → `_apk/thunder-play.apk` → download → install over the top.
> This build: fixed the share upload probe, added the track details panel.

Keep it to a few lines. They know how to install it; what they cannot see is whether this build
contains the thing they asked for.

## If something goes wrong

| Symptom | What it means |
|---|---|
| `gradlew` fails | Fix it. Do not publish a build you know is broken. |
| Key file missing | The APK will not talk to Drive. Point at `docs/SETUP.md` step 2 and stop. |
| Still uploading after the timeout | Drive is slow or paused. Tell the user to watch the tray icon; do not claim it is ready. |
| `_apk` folder missing | The script creates it. If creation fails, Drive Desktop is probably not running. |

## One thing to be careful about

The APK embeds the Drive service-account key, so **the built APK is a secret**. It is fine in the
user's own Drive, which is where this puts it. Never attach it to a message, upload it anywhere
public, or put it in a folder that is shared with anyone else. If asked to distribute it more
widely, flag the key first — rotating the key and shipping a keyless build is the safe answer.

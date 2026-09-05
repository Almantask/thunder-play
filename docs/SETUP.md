# Thunder Play — setup

One-time setup. Everything here is free; nothing needs a billing account except the optional
Firebase Storage step, which is only used for sharing playlists.

---

## 1. Transcode the library (do this first)

The app plays AAC, not WAV: 3.0 GB of WAV becomes ~280 MB of `.m4a`, and the same files are what
a shared playlist link streams in a browser. AAC rather than Opus because **Safari on iOS cannot
play Opus** — an iPhone opening your share link would get silence.

```powershell
pwsh -File tools\transcode\transcode.ps1
```

This mirrors `music\` into `music-mobile\`, preserving folder structure. Re-runs only convert what
changed. Add `-Watch` to leave it running and convert new tracks as they land.

Google Drive for Desktop then uploads `music-mobile\` automatically — that upload must finish
before the app can see anything.

---

## 2. Create a Google Cloud project and service account

The app authenticates as a **service account**, not as you. This avoids the `drive` scope, which
is *restricted*: using it in a real app requires Google verification plus a security audit, and
leaving the OAuth app in "Testing" expires its refresh token every 7 days — meaning you'd re-login
weekly, forever.

1. Go to <https://console.cloud.google.com/> and create a project (e.g. `thunder-play`).
2. **APIs & Services → Library** → enable **Google Drive API**.
3. **APIs & Services → Credentials → Create credentials → Service account**.
   - Name it `thunder-play-app`. No roles are needed — access comes from folder sharing.
4. Open the new service account → **Keys → Add key → Create new key → JSON**. A file downloads.
5. Save it as:

   ```
   app/src/main/assets/drive-service-account.json
   ```

   This file is gitignored. Treat the built APK as a secret, since the key travels inside it.

> Rotating the key later is one click in the console plus a rebuild.

---

## 3. Share your library folder with the service account

1. Open the JSON key and copy the `client_email` value. It looks like
   `thunder-play-app@your-project.iam.gserviceaccount.com`.
2. In Google Drive, right-click **Music-And-Fx-Generated-Library** → **Share**.
3. Paste that address, set it to **Editor**, and untick "Notify people".

Editor (not Viewer) is required: the app moves files into the trash folder, which is a write.

The app finds the folder by name on first refresh and remembers its id. It never touches anything
outside that folder.

---

## 4. Firebase — ratings, play history, playlists

Ratings and listening history live in Cloud Firestore so they survive a reinstall and follow you
between devices. The free tier (1 GiB, 50k reads / 20k writes per day) is permanent, not credit
based; one person and 185 tracks does not come close to it.

1. Go to <https://console.firebase.google.com/> → **Add project** → pick the **same Google Cloud
   project** from step 2.
2. **Build → Firestore Database → Create database** → production mode → pick a region near you.
3. **Build → Authentication → Get started → Anonymous → Enable.**
   There is no login screen; the app signs in silently. Data is stored at a fixed path rather than
   under the user id, so every device you install on shares one library.
4. **Project settings → General → Your apps → Add app → Android.**
   - Package name: `com.thunderplay`
   - Download `google-services.json` into `app/`.

   That file is committed on purpose — it is not a secret. Security comes from the rules below.
5. Deploy the rules (from the repo root, once you have the Firebase CLI):

   ```bash
   npx firebase-tools deploy --only firestore:rules,storage
   ```

---

## 5. Firebase Storage — only if you want to share playlists

A shared playlist is a link anyone can open in a phone browser, with no app installed. Drive
cannot serve that: Google blocks Drive files from being embedded on third-party sites, so the
audio has to live somewhere that sends CORS and range headers.

Only tracks in an **active** shared playlist are uploaded, and revoking a share deletes them again.

1. **Build → Storage → Get started.** Newer projects may require switching to the **Blaze** plan.
   At this volume it still bills ~$0, and your credits cover any overage — but set a budget alert:
   **Cloud Console → Billing → Budgets & alerts → Create budget → $1**.
2. Configure CORS on the bucket, or seeking will silently fail in the browser:

   ```bash
   gcloud storage buckets update gs://YOUR-BUCKET --cors-file=web/cors.json
   ```

3. Generate the web player's config from your Firebase project:

   ```powershell
   pwsh -File tools\gen-web-config.ps1
   ```

4. Deploy the rules and the player:

   ```bash
   npx firebase-tools deploy --only firestore:rules,storage,hosting
   ```

If you skip this section, everything else still works; only playlist sharing is unavailable.

### How sharing behaves

- **Every share expires after 7 days.** There is no permanent option. Expiry is enforced three
  times over: the Firestore rules refuse to serve a lapsed share, the web player refuses to render
  one, and a daily cleanup job deletes the uploaded audio so it stops costing storage.
- **Revoking is immediate** and deletes the audio, which is the real off switch.
- **A share link is genuinely public** while live - anyone with the URL can stream it, no account
  needed. That is the point, but it is worth knowing.
- **Any filtered view can be shared**, not just a playlist. Filter the library to Liked (or a
  category, or both) and use *Share these tracks* - no playlist to assemble first.
- **Sharing one track** uses the ordinary Android share sheet and sends the actual `.m4a` file, so
  it needs none of this Firebase setup.

---

## 6. Build and install

```bash
./gradlew assembleDebug
```

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Reading errors without a cable

Installing by downloading the APK means there is no `adb logcat`, so the app keeps its own error log.

- **Settings → Diagnostics → View** shows it in the app.
- **Send** puts it through the Android share sheet, so you can mail it to yourself or drop it into
  Drive from the Drive app.
- Drive failures record what Google actually said ("Invalid Value", "insufficientFilePermissions")
  rather than a bare status code.
- Bearer tokens and the service-account key are redacted before anything is written.

> **Why the app cannot upload the log to Drive itself:** a service account has no storage quota of
> its own. Drive accepts an empty file record from it but rejects any content with
> `403 storageQuotaExceeded`. Listing, downloading and moving files all work, because none of them
> consume quota; only writing bytes is blocked. Sending the log through the share sheet uploads it
> as *you*, against your quota, which is fine.

## Troubleshooting

| Symptom | Cause |
|---|---|
| "No Drive service-account key found" | Step 2.5 — the JSON is not in `app/src/main/assets/`. |
| "Could not see a folder named Music-And-Fx-Generated-Library" | Step 3 — not shared with the `client_email`, or shared as Viewer. |
| "No 'music-mobile' folder" | Step 1 has not run, or Drive Desktop has not finished uploading. |
| Tracks appear but will not play | Drive Desktop is still uploading; check its tray icon. |
| Share link plays but will not seek | Step 5.2 — bucket CORS is not configured. |
| Any Drive error you cannot explain | Settings → Diagnostics → View. |
| Sharing a playlist fails | Anonymous auth not enabled, or the Storage rules were never deployed. See step 4.3 and `npx firebase-tools deploy --only firestore:rules,storage`. |
| Periodic sync seems to skip | Android's floor is 15 minutes, and Samsung/Xiaomi kill background work. Exempt the app from battery optimisation in Settings. |

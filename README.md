# VideoDownloader

Android app (Android 10+) that downloads videos from Instagram, YouTube and TikTok.

- Paste a link, or use **Share → VideoDownloader** from the Instagram / YouTube / TikTok app
- Copied link is filled in automatically when you open the app
- Quality list with the **file size** for each option (≈ = estimate when the site doesn't give it)
- Audio only → MP3
- Downloads keep running in the background (notification with progress)
- Saved to **Downloads/VideoDownloader**, with Open / Share buttons
- Light (white) and Dark (pure black) theme: tap "Theme" at the bottom (Auto → Light → Dark)
- Download engine (yt-dlp) updates itself at every start; "Update engine" forces it

## Updates
The app checks the latest GitHub release every time it opens and offers to download and install the newer APK.
Releases are built by `.github/workflows/release.yml` (needs the secrets `VD_KEY_PEM_B64` and `VD_CERT_DER_B64`, the base64 of
`keys/videodownloader/key.pem` and `cert.der`, so updates install over the app already on the phone).

## Build locally (Windows)
Double-click `build.bat`. It downloads what's missing (JDK 17, Android SDK) into `.tools`, builds
`VideoDownloader-unsigned.apk`, and writes `build_log.txt`. Then sign it with the key in `keys/videodownloader`:

    python tools/apk_repack.py VideoDownloader-unsigned.apk VideoDownloader.apk keys/videodownloader --cn VideoDownloader

Keep `keys/` private (it is git-ignored): updates install only with the same key.

Engine: youtubedl-android 0.17.4 (yt-dlp + Python + ffmpeg bundled).

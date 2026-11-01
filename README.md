# Melody Trail 🎵

A musical imitation game for primary pupils. Listen, read the musical notes, then repeat the pattern using your voice, whistle, recorder, drum pads, or piano keys.

## Play

**Website (after GitHub Pages is enabled):** https://mohammaddhameri-blip.github.io/melody-trail/

**Android APK:** https://github.com/mohammaddhameri-blip/melody-trail/releases/latest/download/Melody_Trail.apk

## Lessons

Eight levels, five challenges per level: whistle, percussion, recorder (G A B C′ D′), sung solfège and piano. Your highest score is saved per challenge; complete all five and earn an average of 80% to unlock the next level. Individual tasks are replayable.

## Android build

`.github/workflows/build-android.yml` uses GitHub Actions to compile the offline Android WebView application from the same `index.html` source. It publishes the APK to the latest GitHub Release. Audio capture runs locally and is never uploaded. The app uses AndroidX WebViewAssetLoader to provide a secure HTTPS origin to its bundled HTML.

**Prototype note:** The release is a debug-signed Android APK. Build keys may change between Actions runs, so upgrading may require uninstalling the previous build. This app is not yet published on Google Play. Pitch detection performance depends on microphone, environment and instrument. Test the grading with actual pupils before formal assessment.

## Publish from Windows

Extract the GitHub-ready ZIP. If Git for Windows is installed and authenticated, double-click `PUBLISH_TO_GITHUB_WINDOWS.bat`. It pushes all source files to the existing empty `mohammaddhameri-blip/melody-trail` repository. The Android APK build starts under **Actions**. Enable **Settings → Pages → Build and deployment → Source: GitHub Actions** to make the web game publicly playable.

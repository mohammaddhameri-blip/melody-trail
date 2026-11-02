# Melody Trail 🎵

A musical imitation game for primary pupils. Listen, read the musical notes, then repeat the pattern using your voice, whistle, recorder, drum pads, or piano keys.

## Play

**Website (after GitHub Pages is enabled):** https://mohammaddhameri-blip.github.io/melody-trail/

**Android APK:** https://github.com/mohammaddhameri-blip/melody-trail/releases/latest/download/Melody_Trail.apk

## Lessons

Eight levels, five challenges per level: whistle, percussion, recorder (G A B C′ D′), sung solfège and piano. Your highest score is saved per challenge; complete all five and earn an average of 80% to unlock the next level. Individual tasks are replayable.

## Android build

`.github/workflows/build-android.yml` uses GitHub Actions to compile the offline Android WebView application from the same `index.html` source. It publishes the APK to the latest GitHub Release. Audio capture runs locally and is never uploaded. The app uses AndroidX WebViewAssetLoader to provide a secure HTTPS origin to its bundled HTML.

## Automatic Android updates

The installed Android app checks the official **GitHub Releases / latest** endpoint automatically (at startup, throttled to once every 12 hours). If it finds a higher release number, it downloads `Melody_Trail.apk` over HTTPS to its private app cache. Settings → Android updates shows download status, **Check now**, an on/off toggle and **Install update** after downloading. No user interaction is necessary for checking or downloading; Android **always requires user approval** before installing a sideloaded APK and may require allowing installation from Melody Trail first. The game itself still works offline.

GitHub Actions sets the Android `versionCode` to the workflow run number, matching the release tag `android-N`. This avoids comparing file timestamps or guessing version names.

### One-time release-signing setup for seamless in-place upgrades

Android requires each upgrade to have the **same app signing key** as the installed version. GitHub Actions' default debug signing may use a different key on each build, which prevents installing over the previous app. For reliable ongoing updates, create a private release keystore **once** and set these four secrets under **GitHub repository → Settings → Secrets and variables → Actions**:

- `MELODY_KEYSTORE_BASE64` — base64 encoding of your private release keystore file
- `MELODY_KEYSTORE_PASSWORD` — keystore password
- `MELODY_KEY_ALIAS` — key alias
- `MELODY_KEY_PASSWORD` — key password

Generate a keystore locally (retain it and its passwords securely; never commit it to the public repository):

```sh
keytool -genkeypair -v -keystore melody-release.jks -alias melodytrail -keyalg RSA -keysize 3072 -validity 10000
```

In PowerShell, encode the file for the first secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("melody-release.jks"))
```

When the four secrets exist, GitHub Actions uses a signed **release** build automatically. Otherwise it falls back to a debug-signed build and prints a workflow warning. **Changing from an earlier debug-signed installation to a new release key requires one uninstall/reinstall**, which can erase saved local progress. After that transition, keep the same release keystore for every version and upgrades can install over the previous version.

This project is not yet on Google Play. Pitch detection and updates should be tested on physical Android devices before relying on them for formal assessments.

## Publish from Windows

Extract the GitHub-ready ZIP. If Git for Windows is installed and authenticated, double-click `PUBLISH_TO_GITHUB_WINDOWS.bat`. It pushes all source files to the existing empty `mohammaddhameri-blip/melody-trail` repository. The Android APK build starts under **Actions**. Enable **Settings → Pages → Build and deployment → Source: GitHub Actions** to make the web game publicly playable.

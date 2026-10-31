# UK Waiting Lists for Android

An Android app and home screen widget that shows your VATSIM UK waiting list positions and
notifies you when they change. It's the Android companion to the Windows widget in this repository.

## Install

1. Open the repository's **Actions** tab → **Android app** → the latest green run.
2. Under **Artifacts**, download **UKWaitingLists-apk** and unzip it to get `UKWaitingLists.apk`.
3. Copy it to your phone and open it. Allow installing from that source when Android asks.

Requires Android 8.0 or later. Each build is signed with the same key (`app/debug.keystore`),
so a newer APK installs over the old one and keeps your sign-in and settings.

## First run

1. The app opens a VATSIM sign-in page. Log in, and it closes by itself once your waiting lists load.
   The app never sees your password; it keeps vatsim.uk's sign-in cookies on your phone.
2. Allow notifications when asked.
3. Long-press your home screen → **Widgets** → **UK Waiting Lists**, or use **Add** in the app's
   Home screen section.

## What it does

- Lists every ATC and pilot waiting list you're on: position, ▲/▼ for the last change, join date and theory exam status
- Notifies you when a position moves, you're added to or removed from a list, a theory exam passes, or you need to sign in again
- Position alerts pop up, vibrate and ring with your alarm sound, even on silent. Turn off **Alarm sound** in the app to use your normal notification sound instead; **Sound settings** opens Android's settings for that alert.
- Highlights lists that moved in the last 24 hours
- Checks in the background every 15 minutes to 6 hours (default 1 hour)
- Widget: small sizes show your best position; larger sizes list your waiting lists, with a refresh button
- Light and dark themes styled after [PanelUI](https://panelui.dev), following your phone's setting

## Build

Needs JDK 17 and the Android SDK (API 35).

```bash
cd android
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the same
build on every push that changes `android/` (see `.github/workflows/android.yml`).

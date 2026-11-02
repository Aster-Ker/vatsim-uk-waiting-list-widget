# UK Waiting Lists for Android

An Android app and home screen widget that shows your VATSIM UK waiting list positions and
notifies you when they change. It's the Android companion to the Windows widget in this repository.

## Install

1. On your phone, download [`UKWaitingLists.apk`](https://github.com/Aster-Ker/vatsim-uk-waiting-list-widget/releases/latest/download/UKWaitingLists.apk) from the latest release.
2. Open it and allow installing from your browser or Files app when Android asks.

Requires Android 8.0 or later. Each build is signed with the same key (`app/debug.keystore`),
so a newer APK installs over the old one and keeps your sign-in and settings.

## First run

1. The app opens a VATSIM sign-in page. Log in, and it closes by itself once your waiting lists load.
   The app never sees your password; it keeps vatsim.uk's sign-in cookies on your phone.
2. Allow notifications when asked.
3. Long-press your home screen → **Widgets** → **UK Waiting Lists** and pick a widget, or use **Add** next to
   a widget in the app's Settings page.

## What it does

The app has three pages. Swipe left or right to move between them, or tap the bar at the bottom:

- **Sessions**: your next session with a countdown, then every upcoming session by day and past ones with their report status
- **Lists**: your waiting list positions, with **Check now**
- **Settings**: alerts, session reminders, check interval, home screen widgets and your account


- Lists every ATC and pilot waiting list you're on: position, ▲/▼ for the last change, join date and theory exam status
- **Estimated time to #1** for each waiting list, from how fast your position has moved (see below)
- Notifies you when a position moves, you're added to or removed from a list, a theory exam passes, or you need to sign in again
- Position alerts pop up, vibrate and ring with your alarm sound, even on silent. Turn off **Alarm sound** in the app to use your normal notification sound instead; **Sound settings** opens Android's settings for that alert.
- Highlights lists that moved in the last 24 hours
- Checks in the background every 15 minutes to 6 hours (default 1 hour)
- **Sessions:** your ATC bookings, mentoring sessions and exams from vatsim.uk, found automatically from the
  dashboard's calendar link
  - A **Next session** card with a live countdown: upcoming, starting soon, in progress
  - Every upcoming event by day, and past ones with their report status. Tap an event to open it on
    vatsim.uk, read the feedback or add it to your phone's calendar
  - A **reminder alarm** before each session (10 minutes to 2 hours, default 30 minutes)
  - Alerts when a session is booked, moved or removed, and when your mentor files feedback; the report opens
    in the app, already signed in
- Four home screen widgets, all resizable:
  - **Next session**: your next booking with a countdown, from 2×1 to a 2×2 card
  - **Best position**: 1×1 shows just the number, 2×1 adds the list name, 2×2 is a card
  - **Positions strip**: a 4×1 row with 2–5 lists side by side, depending on width
  - **Waiting lists**: a full list with a refresh button, showing 1–6 rows depending on height
- Light and dark themes styled after [PanelUI](https://panelui.dev), following your phone's setting

## Estimated time to #1

VATSIM UK doesn't publish how fast each list moves, so the estimate comes from your own position history. The app
records your position each time it changes and works out your pace over the last six months, including weeks with no
movement. For example: "About 3 months to #1 · ~4 places a month". It needs about a week of tracking with some
movement before the first estimate appears, and it's only a guide: lists can speed up or slow down.

## Build

Needs JDK 17 and the Android SDK (API 35).

```bash
cd android
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the same
build on every push that changes `android/` (see `.github/workflows/android.yml`).

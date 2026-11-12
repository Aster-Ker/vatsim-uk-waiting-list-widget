# VATSIM UK Waiting List Widget

> **Downloads:** get the latest [Android app (APK)](https://github.com/Aster-Ker/vatsim-uk-waiting-list-widget/releases/latest/download/UKWaitingLists.apk) or [Windows widget (zip)](https://github.com/Aster-Ker/vatsim-uk-waiting-list-widget/releases/latest/download/WaitingListWidget-win-x64.zip), or see all [releases](https://github.com/Aster-Ker/vatsim-uk-waiting-list-widget/releases). The Android app is described in [`android/README.md`](android/README.md).

A small always-on-top Windows desktop widget that shows your positions on the
VATSIM UK waiting lists (https://www.vatsim.uk/mship/waiting-lists) and pops a
notification when a position changes.

VATSIM UK has no public waiting-list API, so the widget reads the same page you
see when you sign in. It uses an embedded Edge (WebView2) browser. You sign in
once with VATSIM SSO, and the cookies are kept in
`%LOCALAPPDATA%\VatsimUkWaitingListWidget\WebView2`. Your password is never
seen or stored by the widget.

![Widget preview in light and dark](preview.png)

## Install

1. Unzip `WaitingListWidget-win-x64.zip` anywhere, e.g. `%LOCALAPPDATA%\Programs\WaitingListWidget`.
2. Run `WaitingListWidget.exe`. Windows SmartScreen may warn because the exe is
   unsigned: choose **More info → Run anyway**.
3. A sign-in window opens. Log in with VATSIM. It closes itself once your
   waiting lists load.
4. Right-click the widget or the tray icon and tick **Start with Windows** if you want it at login.

Requires Windows 10 (version 1809 or later) or Windows 11, x64, with the Edge WebView2 Runtime (preinstalled on
current Windows). No .NET install is needed; the exe is self-contained.

## Look

The UI follows the default "Panel" theme of [PanelUI](https://panelui.dev): neutral card, 1px hairline
borders, tinted status badges, and KPI-style position numbers. It switches between light and dark
with Windows (right-click → Theme to force one). It uses Inter if you have it installed, otherwise Segoe UI.

## Estimated time to #1

Each waiting list shows an estimate of how long until you reach #1. VATSIM UK doesn't publish how fast each list moves, so the estimate comes from your own position history. The app
records your position each time it changes and works out your pace over the last six months, including weeks with no
movement. For example: "About 3 months to #1 · ~4 places a month". It needs about a week of tracking with some
movement before the first estimate appears, and it's only a guide: lists can speed up or slow down.

**Calibrate with your join position.** If you know what number you were on the day you joined a list, enter it and
the estimate measures your pace from your join date instead, so it's ready straight away. For example, joined on
3 Feb at #45 and now #11 is 34 places in about 9 months. On Windows: right-click → **Position when I joined**.

## Sessions

The widget also shows your next three bookings from vatsim.uk: ATC bookings, mentoring sessions and exams. It finds your
personal calendar link on the vatsim.uk dashboard (under **Calendar subscription**) by itself.

- Each session shows a badge: **in 2 days**, **in 25 min** (amber, inside your reminder window) or **Live** (green).
  Click a session to open it on vatsim.uk.
- A reminder pops up before each session with the alarm sound and stays on screen until you dismiss it.
  Right-click → **Sessions → Remind me before** sets how early: 10 minutes to 2 hours, default 30 minutes.
- You're notified when a session is booked, moved or removed, when your mentor files feedback, and when a new
  practical exam result appears on **My Exams**. That's read from
  **My Training History** on vatsim.uk; clicking the notification opens the report.
- Right-click → **Sessions** also turns these alerts off, and opens My Training History or the bookings calendar.

## Notifications

When a check finds a change, you get a Windows notification. The notification also stays in the notification centre. You're notified when:

- your position on any list moves up or down, e.g. "S2 Training: #14 → #11 (up 3)"
- you get a position for the first time on a list
- you're added to or removed from a list
- a theory exam starts showing as passed
- your vatsim.uk session expires and you need to sign in again

A list that moved in the last 24 hours is highlighted in the widget, green for up and red for down.
Right-click → **Notifications** turns them on or off and can send a test notification. If nothing appears,
check that Windows notifications are on and Do Not Disturb is off.

## Using it

- **Widget:** each list shows its position, ▲/▼ for the last change, the join date and,
  for ATC lists, whether the CTS theory exam shows as passed. A "—" means you're on
  the list but have no position yet (activity/hours criteria not met).
- **Tray icon:** shows your best position number. It turns amber with "!" when you need to sign in again.
- **Drag** the widget to move it. **Double-click** it to open the page in your browser,
  or to sign in when your session has expired.
- **Right-click menu:** Check now, Sign in, Always on top, Theme, Check every (15 min to 6 h,
  default 1 h), Notifications, Start with Windows, Sign out, Exit.

VATSIM UK recalculates waiting-list eligibility only every 24 hours, so checking
more often than hourly gains little. Please keep the interval reasonable.

## Files

- Settings and last-seen positions: `%LOCALAPPDATA%\VatsimUkWaitingListWidget\state.json`
- Browser profile (sign-in cookies): `%LOCALAPPDATA%\VatsimUkWaitingListWidget\WebView2`

To remove everything: Exit, untick Start with Windows first (or delete the
`VatsimUkWaitingListWidget` value under `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`),
then delete the exe and that folder.

## Build from source

Requires the .NET 8 SDK.

```bash
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -p:DebugType=none -o publish
```

If VATSIM UK changes the page layout, the extraction script is `ExtractScript` in `BrowserForm.cs`.

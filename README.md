# TV Home

Android TV launcher with a PlayStation-inspired Cross Media Bar, live TV guide, Multiview, and IPTV playback.

Version **1.0.0**. Built and tested on a Hisense 55U8QG (Google TV, Android 14); it targets any Android TV / Google TV
device on Android 8 (API 26) or newer.

## Screenshots

### Home

![Home — greeting, quote, weather, and XMB categories](docs/home.png)

### Favorites

![Favorites column with white streaming icons](docs/favorites.png)

### Live

![Live column — Watch now and TV Guide](docs/live.png)

### TV Guide

![Electronic program guide (live preview removed)](docs/guide.png)

### Multiview

![Multiview windows — apps, calendar, and weather](docs/multiview.png)

## What it does

- **Cross Media Bar home screen** — categories across the bar, items beneath. Layout, colour themes and ribbon
  background follow RetroArch's XMB menu. Categories: Favorites, Recent, Apps, Live, Calendar, Weather, Multiview,
  Inputs, Settings.
- **Live TV** — IPTV playlists (M3U), a TV guide, channel up/down, resume last channel.
- **Multiview** — one to four live windows at once: HDMI inputs, web pages, IPTV channels, or widgets (clock,
  weather, calendar). Select a window for quick actions (Sound, Source, Full screen) that fade away when idle.
  Full screen has a *Back to Multiview* button; the Back key also steps out.
- **Weather and calendar** — weather from Open-Meteo (no account needed); calendars from any public `.ics` link,
  including an Apple Calendar public link.
- **Inputs** — switch HDMI ports from the menu.
- **Ambient sleep clock** — after five idle minutes the screen shows a clock and dims the panel.
- **Everything is configurable on the TV** — Settings → Home screen (15 themes, background and speed, menu size, icon
  colour, shadows, frosted glass, animation speed, sounds, 24-hour clock), Weather, Calendar, Live TV playlists,
  Web pages.
- **Phone setup** — a small web page, served only while you switch it on, so long values (calendar links,
  playlists) can be typed on a phone or computer. Protected by a PIN shown on the TV; it turns itself off after
  15 minutes.

## Install on a TV

You need a computer with `adb`, and the TV on the same network.

1. **Enable debugging on the TV.** Settings → System → About, tap *Build* seven times. Then Developer options →
   turn on *Wireless debugging*, choose *Pair device with pairing code*, and run:

   ```bash
   adb pair  <tv-ip>:<pairing-port>   # enter the code shown on the TV
   adb connect <tv-ip>:<connect-port> # the port on the main Wireless debugging screen
   ```

2. **Install the app** — download `home.apk` from the Releases page, or build it (below):

   ```bash
   adb install -r home.apk
   ```

3. **Make it the home screen.** Google TV insists on its own launcher, and its setup wizard fights back if you
   disable the launcher first, so do it in this order:

   ```bash
   adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith   # first
   adb shell cmd package set-home-activity com.liam.homescreen/.LauncherActivity
   adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx      # then this
   ```

   Press Home: you should land on TV Home. Keep wireless debugging on, it is your way back.

4. **Optional — dim the panel for the sleep clock.** This needs the *Modify system settings* permission:

   ```bash
   adb shell appops set com.liam.homescreen WRITE_SETTINGS allow
   ```

### Going back to Google's home screen

```bash
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
adb shell pm enable com.google.android.tungsten.setupwraith
```

`DISABLED_PACKAGES.txt` records the packages this project has disabled on the developer's TV (including two Hisense
analytics services); nothing is uninstalled, and `adb shell pm enable <package>` undoes any of them.

## Using it

| Remote | Does |
|---|---|
| ◀ ▶ | Change category |
| ▲ ▼ | Move through items |
| OK | Open / change a setting |
| Hold OK (or Menu) | Options for the item (add to favorites, app info, …) |
| Back | Leave a submenu. Hold Back to jump to the main menu |

On a keyboard (or the Android emulator): arrows / WASD / IJKL move, Enter = OK, Esc / Backspace / B = Back, M = Menu.

**First things to set up** (Settings): your weather city, a calendar link, an IPTV playlist. For anything long, use
*Settings → Phone setup*: switch it on, open the address shown on the TV in a phone or computer browser, enter the
PIN, and change any setting there.

## Notes for Hisense TVs

The launcher hides Hisense's internal `com.vt.*` apps from the app list (keeping Live TV), and calls the HDMI port
`/HW3` "Apple TV". Both are defined near the top of `LauncherActivity.java` and `MultiviewActivity.java` if your
TV or setup differs.

## Build the APK

Requires JDK 17+ and Android SDK platform 34 / build-tools 34.0.0.

```bash
export ANDROID_HOME=/path/to/android-sdk   # optional if already set
cd launcher
./build.sh            # → launcher/build/home.apk
./build.sh install    # build + adb install (ADB_TARGET=serial optional)
```

## CI

GitHub Actions (`.github/workflows/android.yml`):

- **push / pull_request** — compile and package `home.apk`; fail the check if the build breaks
- **release** — same build, then attach `home.apk` to the published release

Create a release (e.g. tag `v1.0.0`) to get a downloadable APK on the Releases page.

## Homebridge plugin (optional)

`index.js`, `src/` and `scripts/` are a separate Homebridge plugin, `homebridge-hisense-googletv`, that exposes the
TV to Apple Home (power, input picker, remote, volume) over the local network, with no cloud or Google account. It
talks to the Android TV Remote service, pairs once with `node scripts/pair.js <tv-ip>`, and keeps its certificate
under `.pairing/` (git-ignored). Configure it in Homebridge using `config.schema.json`.

## Credits

- Layout, colour themes and the ribbon background are modelled on RetroArch's XMB menu
  ([libretro/RetroArch](https://github.com/libretro/RetroArch)). The icons are drawn in code; no RetroArch assets
  are bundled.
- Weather data: [Open-Meteo](https://open-meteo.com).
- The default IPTV playlist points at the community-maintained [iptv-org](https://github.com/iptv-org/iptv) index.

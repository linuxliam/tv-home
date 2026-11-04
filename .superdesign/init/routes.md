# Routes / screens — TV Home launcher

Android Activities (no URL router). Manifest: `launcher/AndroidManifest.xml`.

| Screen | Activity | Entry |
|--------|----------|-------|
| Home (XMB) | `LauncherActivity` | HOME / LEANBACK_LAUNCHER |
| Multiview | `MultiviewActivity` | From XMB Multiview → Open |
| TV Guide | `GuideActivity` | Live → TV Guide |
| IPTV Player | `PlayerActivity` | Live → Watch now / Guide Play |

## Home
Full-screen `XmbView` Canvas: ribbon background, greeting + quote left, weather + clock right, horizontal categories, vertical items under focus, floating hints, optional EmuPad (emulator).

## Multiview
2×2 (or 1–4) live windows: HDMI / IPTV / web / widgets. Focus ring around picture; chrome dock under focused cell; Windows count bar at bottom.

## Guide
Dark spectrum sheet over PIP: channel list + time grid, digit jump, footer hints.

## Player
Full-bleed video + Glass channel banner + status label + floating hints.

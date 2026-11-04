# Layouts — TV Home

There is no React shell. Each Activity owns its view tree.

## Home shell — LauncherActivity
- Root `FrameLayout`
- `XmbView` (full screen Canvas XMB)
- Ambient scrim + `WidgetView` (idle clock)
- Optional `EmuPad` (bottom-left, emulator only)

Status header, category bar, item column, and footer hints are all drawn inside `XmbView.onDraw` — not separate View children.

Key file: `launcher/src/com/liam/homescreen/LauncherActivity.java`
Key draw surface: `launcher/src/com/liam/homescreen/XmbView.java`

## Multiview shell — MultiviewActivity
- Backdrop `XmbView` (backdrop-only)
- Grid of cells: each cell = `stack(FrameLayout)` → holder (TvView/WebView/VideoView/WidgetView) + focus ring sibling, inset for stroke
- Per-cell action dock under picture
- Windows count bar bottom-center

Key file: `launcher/src/com/liam/homescreen/MultiviewActivity.java`

## Shared chrome
`Glass.java` — docks, cards, panels, sheets, focus washes.

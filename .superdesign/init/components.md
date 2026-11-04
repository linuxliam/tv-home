# Shared UI primitives — Android Canvas / View

## Glass
Path: `launcher/src/com/liam/homescreen/Glass.java`
Dark theme-tinted plates for docks, cards, panels, sheets, focus washes.

```java
// See full source in launcher/src/com/liam/homescreen/Glass.java
// Key APIs: dock(), card(), panel(), sheet(), field(), focusWash(), accentStroke()
```

Full source:

```java
package com.liam.homescreen;
// ALPHA_DOCK=0xCC000000, ALPHA_CARD=0xE6000000, ALPHA_SHEET=0xE0000000
// dock/card = theme-tinted GradientDrawable with thin edge stroke
// panel = Canvas roundRect + edge; sheet = fade into video; field = widget plate
```

(Complete file is 156 lines — pass `Glass.java` as `--context-file` when designing.)

## EmuPad
Path: `launcher/src/com/liam/homescreen/EmuPad.java`
Emulator-only on-screen D-pad + separate Back pill to the right of the circle.

## WidgetView
Path: `launcher/src/com/liam/homescreen/WidgetView.java`
Clock / weather / calendar / message widgets used in Multiview cells and ambient sleep.

## XmbDialog
Path: `launcher/src/com/liam/homescreen/XmbDialog.java`
Popup lists and text prompts using `Glass.card`.

## NavKeys
Path: `launcher/src/com/liam/homescreen/NavKeys.java`
Unified Esc/Backspace/WASD/Space → remote semantics across activities.

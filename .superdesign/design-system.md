# TV Home — Metro + XMB

Shared chrome helper: `launcher/src/com/liam/homescreen/Glass.java` (dark theme plates — not frosted glass).

## Intent

Two selectable home layouts (Settings → Home screen → Home layout):

1. **Metro (default)** — dark living-room plane, light hub pivots, flat focused tiles with accent leading edge. Xbox / early Windows Metro clarity without 2012 flip-tile circus.
2. **Cross media bar** — floating white icons on a ribbon / gradient (RetroArch XMB). Quiet dark translucent overlays for menus and banners.

## Principles

- Icons / tiles float — no dock shelf wrapping the category row
- Status / greeting are bare type with soft text shadow
- Overlays use dark theme-tinted plates (`Glass.dock` / `panel` / `card`)
- Focus = accent ticks / leading edge and simple washes, not neon frost bricks
- Button hints are floating text (bottom-right), not dark pills
- Same D-pad contract (`NavKeys`) for both layouts — Homebridge remotes keep working

## Metro specifics

- Dark base (~#05070C–#101820) with quiet theme accent bloom
- Horizontal hub titles; selected hub has accent underline
- Vertical flat tiles; focused tile brighter + accent left bar
- Frosted top edge off by default (still toggleable)

## TV guide

- Spectrum layout: 16:9 PIP in the top sky, dark panel below
- Thin typography; channel number in a fixed column
- Digit keys jump to a channel (same as the player); entry chip while typing
- Rewind / red key snaps the grid to **Now**
- Focused row: wash + accent leading edge
- Footer: position left, hints right (`OK` / `Play` / `Rew` / `0–9` / `Back`)

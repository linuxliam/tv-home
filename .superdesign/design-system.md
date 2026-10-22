# PlayStation XMB

Shared chrome helper: `launcher/src/com/liam/homescreen/Glass.java` (dark theme plates — not frosted glass).

## Intent

Cross Media Bar: floating white icons on a ribbon / gradient, quiet dark translucent overlays for menus and banners. No milky shelves or iOS sheets.

## Principles

- Icons float — no dock shelf wrapping the category row
- Columns float — no grouped glass sheet behind the list
- Status / greeting are bare type with soft text shadow
- Overlays use dark theme-tinted plates (`Glass.dock` / `panel` / `card`)
- Focus = accent ticks and simple washes, not neon frost bricks
- Button hints are floating text (bottom-right), not dark pills

## TV guide

- Spectrum layout: 16:9 PIP in the top sky, dark panel below
- Thin XMB typography; channel number in a fixed column
- Digit keys jump to a channel (same as the player); entry chip while typing
- Footer: position left, hints right (`OK` / `Play` / `0–9` / `Back`)

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

## Superdesign refinements (2026-10)

Canvas project `60e172ab…` — drafts: home refine, Multiview calm, Guide, stacked header.

- Header (stacked variant): quote under greeting; weather under clock — open middle sky
- Hints: 18px key/label pairs (home, guide, player)
- Multiview: thin focus stroke; quieter / lower-alpha docks and Windows bar; floating footer hints
- EmuPad: larger Back chip (60×44) beside the pad; Glass-tinted plates

## TV guide

- Spectrum layout: 16:9 PIP in the top sky, dark panel below
- Thin XMB typography; channel number in a fixed column
- Honest channel browser (no fake EPG schedule until real program data exists)
- Digit keys jump to a channel (same as the player); entry chip while typing
- Footer: position left, hints right (`OK` / `Play` / `Menu` / `0–9` / `Back`)

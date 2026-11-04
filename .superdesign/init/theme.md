# Theme — TV Home (PlayStation XMB)

Native Android Canvas UI (no CSS/Tailwind). Tokens live in `XmbView` + `Glass`.

## Compact token summary

### Default theme: electric_blue (4-corner gradient)
| Corner | RGB | Hex-ish |
|--------|-----|---------|
| TL | 1, 2, 67 | #010243 |
| TR | 1, 73, 183 | #0149B7 |
| BL | 1, 93, 194 | #015DC2 |
| BR | 3, 162, 254 | #03A2FE |

Ink on dark themes: `#FFFFFF`. Light themes flip to `#000000`.

### Type (1080p units × `scale = h/1080`)
| Role | Size |
|------|------|
| Greeting / list titles | 42 |
| Clock | 46 |
| Category labels | 32 |
| Subtitles | 28 |
| Setting values | 36 |
| Quote / date / weather meta | 20 |
| Hint keys | 18 |

### Layout (RetroArch PS3 at 1080p × scale)
- Category icon size: 128
- Horizontal spacing: 192
- Row height: 64
- Category bar Y: 272
- Column anchor X: 336
- Status margin: 56 from edges

### Chrome (`Glass`)
- Dock alpha: `0xD9000000` → `0xE6000000` theme-tinted gradient
- Card alpha: `0xF2000000`
- Sheet alpha: `0xE0000000`
- Focus wash: `0x40FFFFFF` (dark ink) / `0x30000000` (light)
- Edge stroke: `0x55FFFFFF` / `0x33000000`
- Corner radius: ~8–14dp docks, ~10dp chips

### Principles
- Floating white icons — no dock shelf under categories
- Dark translucent plates for overlays (not milky frost)
- Soft text shadow, not heavy drop shadows
- Focus = thin accent stroke / wash, not neon glow bricks
- Button hints = floating text bottom-right, not pills

## Raw source (Glass.java)
See `launcher/src/com/liam/homescreen/Glass.java` (full file in components.md).

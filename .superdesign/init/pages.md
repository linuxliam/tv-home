# Pages — dependency trees

## Home (XMB)
Entry: `launcher/src/com/liam/homescreen/LauncherActivity.java`
Dependencies:
- `launcher/src/com/liam/homescreen/XmbView.java` (drawGreeting, drawClock, drawScene, drawFooter, ribbon)
- `launcher/src/com/liam/homescreen/Glass.java`
- `launcher/src/com/liam/homescreen/EmuPad.java`
- `launcher/src/com/liam/homescreen/WidgetView.java` (ambient)
- `launcher/src/com/liam/homescreen/NavKeys.java`
- `launcher/src/com/liam/homescreen/XmbDialog.java`
- `.superdesign/design-system.md`

## Multiview
Entry: `launcher/src/com/liam/homescreen/MultiviewActivity.java`
Dependencies:
- `Glass.java`
- `WidgetView.java`
- `XmbView.java` (backdrop + accentColor)
- `XmbDialog.java`
- `NavKeys.java`

## Guide
Entry: `launcher/src/com/liam/homescreen/GuideActivity.java`
Dependencies:
- `Glass.java`
- `XmbView.java` (iconColor, accentColor)
- `NavKeys.java`

## Player
Entry: `launcher/src/com/liam/homescreen/PlayerActivity.java`
Dependencies:
- `Glass.java` (ChannelBanner panel)
- `XmbView.java` (ink)

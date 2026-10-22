# Gingerbread Launcher

The stock launcher of Android 2.3.7 Gingerbread (AOSP Launcher2, `android-2.3.7_r1`), ported to modern Android (11 and newer) and today's displays, with optional extras on top.

The goal is the original look and feel: the 3D app drawer, the dock, Droid Sans and the Gingerbread widgets. Everything that is not stock can be switched on or off in the launcher settings and stays off by default.

## Port

- Builds with current Gradle and SDK (minSdk 30, targetSdk 37); hidden and removed APIs replaced
- **3D app drawer**: the RenderScript drawer, ported to OpenGL ES, shown at its original physical size
- Edge-to-edge, predictive back, pinned shortcuts, widget binding and configuration on modern Android
- Follows the system animation speed
- Icon sizing backported from Launcher3, so modern adaptive icons fit
- Droid Sans from AOSP 2.3.7 for all launcher texts

## Bundled widgets

- Google Search bar (Gingerbread QSB)
- Analog clock (DeskClock)
- Power Control (AOSP Settings)
- Weather, from *News and Weather* 1.3.01: original layout and graphics, data from OpenWeatherMap with your own API key
- News and YouTube widgets

The analog clock can be resized freely, the others horizontally. Weather, News and YouTube are set up in the launcher settings.

## Optional settings

**Home screen**
- Grid: automatic (Launcher3's Android 11 grid choice) or 3–7 columns × 3–8 rows
- Number of pages
- Swipe down to open the notifications, swipe up to open the app drawer
- Google feed left of the first page (via the Google app or AIDL Bridge)

**App drawer**
- Style: 3D (Gingerbread) or 2D (simple grid)
- Columns and rows
- Hidden apps

**Dock**
- Order and visibility of the buttons
- Extra buttons: contacts, messaging, email, camera

**Appearance**
- Transparent status and navigation bar
- System font instead of Droid Sans
- Icon shape for adaptive icons
- Icon packs (ADW/Nova format), with their own colors
- Notification badges in the TouchWiz 3.0 style; needs notification access

## Build

```sh
./gradlew assembleRelease
```

## Live wallpapers

The Gingerbread live wallpapers (Nexus, Galaxy, Grass, Water, Polar clock, Magic Smoke and the music visualizations) are a separate project, one APK each.

## License

Based on the Android Open Source Project, Apache License 2.0 (see `NOTICE`).

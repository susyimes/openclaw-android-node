# OpenClaw Android Node (APK Build Project)

This repository contains a **minimal Android Node project** extracted from OpenClaw, intended for local APK builds.

## Project layout

- `apps/android` — Android Studio / Gradle project
- `apps/shared/OpenClawKit/Sources/OpenClawKit/Resources` — shared runtime resources used by the app

## Requirements

- Android Studio (latest stable)
- Android SDK installed
- JDK 17

## Build

```bash
cd apps/android
./gradlew :app:assembleDebug
```

Windows:

```powershell
cd apps/android
.\gradlew.bat :app:assembleDebug
```

APK output:

`apps/android/app/build/outputs/apk/debug/openclaw-<version>-debug.apk`

## Install to device

```bash
cd apps/android
./gradlew :app:installDebug
```

## Notes

- If SDK is not detected, create `apps/android/local.properties`:

```properties
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

## New node commands (Accessibility route)

This repo now includes four accessibility-route invoke commands:

- `app.launch` — launch an Android app by package name
- `screen.tap` — tap a screen coordinate via Android AccessibilityService gesture API
- `text.input` — set text into the currently focused editable field
- `ime.paste` — paste provided text into the focused editable field

### Required setup on phone

1. Install/open the APK.
2. Android Settings → Accessibility → enable **OpenClaw Accessibility Service**.
3. Keep OpenClaw connected to the gateway.

### Android 11+ package visibility note

`app.launch` relies on `PackageManager.getLaunchIntentForPackage()`.  
This repo now declares a launcher-intent `<queries>` block in `AndroidManifest.xml` so launcher apps can be resolved on Android 11+.

### Example invoke payloads

```json
{"packageName":"com.tencent.mm"}
```

```json
{"x":540,"y":1800}
```

```json
{"text":"咖喱"}
```

```json
{"text":"咖喱","targetQuery":"搜索"}
```

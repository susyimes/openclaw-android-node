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

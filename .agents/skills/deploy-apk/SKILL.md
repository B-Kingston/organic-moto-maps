---
name: deploy-apk
description: Publish OrganicMoto Maps APKs to a selected fdka (f-droideka) repository and update its local or SSH endpoint. Use when asked to deploy or publish this project's APK to F-Droid.
---

# Deploy this project's APK

Use `tools/publish-apk.sh INSTANCE APK [SSH_TARGET]` from the project root.
This command publishes the APK, updates the endpoint, and prints its URL and status.
It uses the installed `fdka` or `f-droideka` command. It does not build the APK.

1. Use the instance the user names. If none is named, inspect the instance descriptors under `~/.f-droideka-instances/`. Ask only if the intended instance is still unclear. Never create or replace a repository signing key.
2. Read the selected `fdroid.conf`. Check `FDROID_STORAGE`, `FDROID_TARGET`, `FDROID_REMOTE_HOST`, and `FDROID_REMOTE_DIR`, plus environment overrides. Do not print passwords. Omit SSH_TARGET to use the configured target; supply it only when the user wants that target.
3. If a build is needed, inspect the selected repository's current version code and version name. Choose a greater code and a greater semantic version name. Build with:
   `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug -Pmoto.versionCode=CODE -Pmoto.versionName=NAME`
   The APK is `app/build/outputs/apk/debug/app-debug.apk`. Keep the existing app signing identity. Do not substitute a debug-signed APK for an app previously signed with a different key. Generated graph, geocoder, glyph, and sprite assets must exist before the build.
4. Run `tools/publish-apk.sh INSTANCE APK [SSH_TARGET]`. Stop on any failure. Do not delete repository state or reset version counters to bypass version checks.
5. Verify the printed repository URL: read `index-v2.json`, check the expected package `com.organicmoto.maps`, version code, and version name, and check the APK and icon endpoints. Compare the APK's served size with the local file. Report the instance, version, URL, and checks. Command status alone does not prove the release is available.

Do not install the APK on an emulator unless the user also asks for device deployment.

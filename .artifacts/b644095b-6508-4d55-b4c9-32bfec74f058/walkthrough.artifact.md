# Walkthrough - Permanent Signing Mismatch Fix & Version Bump (v1.0.14)

Successfully resolved the signing mismatch issue on GitHub Actions and bumped the version code to `14` (`versionName = "1.0.14"`).

## Root Cause Identified & Fixed
- Previously, `app/build.gradle.kts` forced GitHub Actions builds to use `signingConfigs.getByName("debug")`, generating a random temporary debug keystore on every GitHub release. This caused every GitHub Release APK to have a different signature, resulting in the `"App not installed"` error when updating.
- Updated `app/build.gradle.kts` so both local and GitHub Actions builds use the persistent release keystore (`app/keystore.jks` / `SuperAdminReleaseKey.jks`).
- Bumped version to `versionCode = 14`, `versionName = "1.0.14"`.

## Verification Results

### Automated Build Check
- Executed `gradle_build("app:assembleDebug")` successfully with **0 errors**.
- Executed `gradle_build("app:assembleRelease")` successfully with **0 errors**.

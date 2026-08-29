# Fix AGP 9.3.2 Incompatibility and Modernize Build Setup

The project is currently experiencing "red code" and build configuration issues because it is using **Android Gradle Plugin (AGP) 9.3.2** while attempting to opt-out of modern features using legacy flags. AGP 9.3.2 strictly enforces the new DSL and built-in Kotlin support.

## User Review Required

> [!IMPORTANT]
> This plan will transition the project to the modern AGP 9.x DSL. This involves removing the `kotlin-android` plugin (as it is now built-in to AGP) and switching to the `compilerOptions` block for JVM target configuration.

## Proposed Changes

### Build Configuration

#### [MODIFY] [gradle.properties](file:///C:/Users/Heman/Downloads/ghost/gradle.properties)
- Remove `android.newDsl=false` and `android.builtInKotlin=false` as they are no longer supported in AGP 9.3.2.
- Clean up other legacy flags that may conflict with the modern build model.

#### [MODIFY] [libs.versions.toml](file:///C:/Users/Heman/Downloads/ghost/gradle/libs.versions.toml)
- Ensure Kotlin version is aligned with AGP 9.3.2 requirements (e.g., 2.1.0+).

#### [MODIFY] [build.gradle.kts](file:///C:/Users/Heman/Downloads/ghost/build.gradle.kts)
- Remove `libs.plugins.kotlin.android` from the root plugins block.

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/Heman/Downloads/ghost/app/build.gradle.kts)
- Remove `alias(libs.plugins.kotlin.android)`.
- Replace `compileOptions` and `kotlinOptions` with the new unified `android.kotlin.compilerOptions` DSL.

## Verification Plan

### Automated Tests
- Run `./gradlew app:assembleDebug` to ensure the project compiles with the new configuration.
- Run `gradle_sync` to verify that IDE "red code" is cleared.

### Manual Verification
- Verify that the `GhostAccessibilityService` is correctly registered and the app can be installed on a device.

# Lawnchair 15

[![Build debug APK](https://github.com/LawnchairLauncher/lawnchair/actions/workflows/ci.yml/badge.svg)](https://github.com/LawnchairLauncher/lawnchair/actions/workflows/ci.yml)
[![Build release APK](https://github.com/LawnchairLauncher/lawnchair/actions/workflows/release_update.yml/badge.svg)](https://github.com/LawnchairLauncher/lawnchair/actions/workflows/release_update.yml)
[![Crowdin](https://badges.crowdin.net/e/188ba69d884418987f0b7f1dd55e3a4e/localized.svg)](https://lawnchair.crowdin.com/lawnchair)
[![OpenCollective](https://img.shields.io/opencollective/all/lawnchair?label=financial%20contributors&logo=open-collective)](https://opencollective.com/lawnchair)
[![Telegram](https://img.shields.io/endpoint?url=https%3A%2F%2Ftg.sumanjay.workers.dev%2Flccommunity)](https://t.me/lccommunity)
[![Discord](https://img.shields.io/discord/803299970169700402?label=server&logo=discord)](https://discord.gg/3x8qNWxgGZ)
[![GitHub Downloads](https://img.shields.io/github/downloads/LawnchairLauncher/lawnchair/total.svg?label=GitHub%20Downloads&logo=github)](https://github.com/LawnchairLauncher/lawnchair/releases)
[![Play Store Installs](https://img.shields.io/endpoint?color=green&logo=googleplay&logoColor=green&url=https%3A%2F%2Fplay.cuzi.workers.dev%2Fplay%3Fi%3Dapp.lawnchair.play%26l%3DPlay%2520Store%2520Installs%26m%3D%24shortinstalls)](https://play.google.com/store/apps/details?id=app.lawnchair.play)

<picture>
    <!-- Avoid image being clickable with slight workaround -->
    <!-- ❤️ Credit to Raine for the original mockup on the Lawnchair Discord -->
    <!-- ❤️ Credit to Lawrence Kayku for the current mockup on Unsplash 
            https://unsplash.com/photos/photography-of-green-leaves-ZVKr8wADhpc 
    -->
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/device-frame.png" width="250px">
    <img alt="Google Pixel running Lawnchair Launcher with green wallpaper" src="docs/assets/device-frame.png" width="250px">
</picture>

Lawnchair is a free, open-source home app for Android. Taking Launcher3—Android’s default home app—as a starting point, it ports Pixel Launcher features and introduces rich customization options.

This branch houses the codebase of Lawnchair 15, which is currently in beta and is based on Launcher3 from Android 15. For Lawnchair 9 to 14, see the branches with the `9-` to `14-` prefixes, respectively.

## SDK Fork Overview

This repository is not a vanilla upstream Lawnchair checkout. The `feature/sdk-15` branch is an SDK-oriented fork used to expose selected launcher capabilities to host apps as reusable modules.

### Upstream baseline

| Item | Value |
| --- | --- |
| Upstream repository | `LawnchairLauncher/lawnchair` |
| Upstream branch family | `15-dev`, `15-beta` |
| Shared merge-base used by this SDK branch | `ae80519238` (`docs: Update changelog`) |
| First SDK-side customization checkpoint | `854f2c036a` (`裁剪系统权限、一般app无关权限`) |
| Current SDK release line | `v0.1.x` |

### Why this fork exists

The goal of this fork is to keep Lawnchair’s launcher runtime, but make it easier for a normal Android app to integrate only the needed launcher capabilities without having to understand the whole source tree first.

### What was changed relative to upstream

| Area | Summary | Key locations |
| --- | --- | --- |
| System feature trimming | Removed permissions, manifests, services, and feature entry points that are typically irrelevant or risky for a normal third-party app, such as root helpers, bugreport flow, accessibility/sleep related capabilities, and some SystemUI-coupled behavior. | `AndroidManifest-common.xml`, `lawnchair/AndroidManifest.xml`, `quickstep/AndroidManifest.xml`, `wmshell/AndroidManifest.xml`, `lawnchair/src/app/lawnchair/root/**`, `lawnchair/src/app/lawnchair/bugreport/**` |
| SDK modularization | Split the fork into reusable SDK-facing modules so host apps can depend on a stable API surface instead of the full launcher internals. | `launcher-core/**`, `launcher-sdk-api/**`, `sdk-local/**`, `settings.gradle`, `build.gradle` |
| Minus-one screen SDK | Replaced the original feed-oriented path with a local minus-one implementation that can render host-provided Compose content, support animated opening, optional auto-open on launcher start, and expose overlay state callbacks. | `lawnchair/src/app/lawnchair/nexuslauncher/LocalLauncherOverlay.kt`, `lawnchair/src/app/lawnchair/LawnchairLauncher.kt`, `launcher-sdk-api/src/main/java/app/lawnchair/sdk/api/MinusOneContentProvider.kt`, `launcher-sdk-api/src/main/java/app/lawnchair/sdk/api/MinusOneOverlayState.kt` |
| Desktop item injection | Added an SDK path for host apps to inject icons into workspace positions with stable persistence, conflict handling, and click dispatch through either explicit `Intent` launch or provider-defined actions. | `launcher-sdk-api/src/main/java/app/lawnchair/sdk/api/DesktopItemProvider.kt`, `lawnchair/src/app/lawnchair/sdk/SdkDesktopItemsSynchronizer.kt`, `lawnchair/src/app/lawnchair/sdk/SdkDesktopItemActionDispatcher.kt` |
| Public host operations | Exposed host-callable operations such as checking default launcher state, launching desktop, opening minus-one with animation, and opening launcher/home settings. | `launcher-sdk-api/src/main/java/app/lawnchair/sdk/LawnchairOperations.kt`, `lawnchair/src/app/lawnchair/sdk/PublicLawnchairOperations.kt` |
| Host `Application` compatibility | Added a runtime bridge so local `includeBuild` host apps no longer need to subclass `LawnchairApp`; they can initialize through a host-facing SDK entry while keeping most original `LawnchairApp` behavior intact. | `launcher-sdk-api/src/main/java/app/lawnchair/sdk/LawnchairSdkHost.kt`, `lawnchair/src/app/lawnchair/LawnchairHostRuntime.kt`, `lawnchair/src/app/lawnchair/LawnchairApp.kt` |
| Settings and UX trimming | Removed or simplified settings and debug surfaces that are not important for an embedded launcher SDK use case, including some about/experimental/search/feed related entries. | `lawnchair/src/app/lawnchair/ui/preferences/**`, `quickstep/src/com/android/launcher3/uioverrides/flags/DevOptionsUiHelper.kt`, `lawnchair/res/values/config.xml`, `lawnchair/res/values/strings.xml` |
| Distribution and publishing | Added Maven publishing, JitPack support, and local-source integration helpers so the SDK can be consumed either remotely or via `includeBuild`. | `launcher-core/build.gradle`, `launcher-sdk-api/build.gradle`, `jitpack.yml`, `sdk-local/build.gradle`, `sdk-local/settings.gradle` |

### Open capabilities exposed to host apps

| Capability | API surface |
| --- | --- |
| Provide minus-one Compose UI | `app.lawnchair.sdk.api.MinusOneContentProvider` |
| Listen to minus-one opened/closed/sliding state | `app.lawnchair.sdk.api.MinusOneOverlayStateListener` |
| Decide whether launcher cold start should land in minus-one | `LawnchairSdkEntry.openMinusOneOnLauncherLaunch` |
| Open minus-one with launcher animation | `app.lawnchair.sdk.LawnchairOperations.openMinusOneAnimated()` |
| Inject custom desktop icons | `app.lawnchair.sdk.api.DesktopItemProvider` |
| Handle injected desktop icon callbacks | `DesktopItemProvider.onDesktopItemAction()` |
| Launch desktop / request default launcher / open launcher settings | `app.lawnchair.sdk.LawnchairOperations` |
| Initialize SDK from a normal host `Application` | `app.lawnchair.sdk.LawnchairSdkHost.initialize()` |

### Recommended porting order for a new upstream branch

If this SDK fork needs to be adapted onto a newer upstream Lawnchair branch, apply changes in this order:

1. Restore module split and publishing setup: `launcher-core`, `launcher-sdk-api`, `sdk-local`.
2. Re-apply manifest, permission, and system-feature trimming.
3. Reconnect public SDK API surfaces and registry wiring.
4. Re-apply minus-one and desktop item business capabilities.
5. Re-apply host `Application` compatibility and verify `includeBuild` behaves like remote dependency usage.

### Files most likely to conflict during future rebases

- `lawnchair/src/app/lawnchair/LawnchairApp.kt`
- `lawnchair/src/app/lawnchair/LawnchairLauncher.kt`
- `lawnchair/src/app/lawnchair/nexuslauncher/LocalLauncherOverlay.kt`
- `quickstep/src/com/android/launcher3/uioverrides/QuickstepLauncher.java`
- `src/com/android/launcher3/LauncherAppState.java`
- `src/com/android/launcher3/model/ModelDbController.java`
- `src/com/android/launcher3/touch/ItemClickHandler.java`
- `src/com/android/launcher3/model/WorkspaceItemProcessor.kt`
- `lawnchair/res/xml/default_workspace_4x5.xml`
- `settings.gradle`
- `build.gradle`

### Detailed migration ledger

For a more detailed, file-oriented migration memo from the SDK fork history, see [`sdk-local/README.md`](sdk-local/README.md). That document is intended to be imported by AI tools or future maintainers when porting this fork onto a newer upstream branch.

## Features

-   **Material You Theming:** Adapts to your wallpaper and system theme.
-   **At a Glance Widget:** Displays information *at a glance* with support for [Smartspacer](https://github.com/KieronQuinn/Smartspacer).
-   **Global Search:** Allows quick access to apps, contacts, and web results from the home screen.
-   **Customization Options:** Provides options to tweak icons, fonts, and colors to your liking.
-   And more!

## Download

<p align="left">
  <a href="https://play.google.com/store/apps/details?id=app.lawnchair.play">
    <picture>
      <!-- Avoid image being clickable with slight workaround -->
      <source media="(prefers-color-scheme: dark)" srcset="docs/assets/badge-google-play.png" height="60">
      <img alt="Get it on Google Play" src="docs/assets/badge-google-play.png" height="60">
    </picture>
  </a>
  <a href="https://apt.izzysoft.de/fdroid/index/apk/app.lawnchair">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="docs/assets/badge-izzyondroid.png" height="60">
      <img alt="Get it on IzzyOnDroid" src="docs/assets/badge-izzyondroid.png" height="60">
    </picture>
  </a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/LawnchairLauncher/lawnchair/">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="docs/assets/badge-obtainium.png" height="60">
      <img alt="Get it on Obtainium" src="docs/assets/badge-obtainium.png" height="60">
    </picture>
  </a>
    <a href="https://github.com/LawnchairLauncher/lawnchair/releases">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="docs/assets/badge-github.png" height="60">
      <img alt="Get it on GitHub" src="docs/assets/badge-github.png" height="60">
    </picture>
  </a>
</p>

Lawnchair on Play Store will install as a different app from other sources. Some features may be restricted to comply with Google Play's publishing rules.

### Development builds

Interested in keeping yourself up-to-date with every Lawnchair development? Try our development builds!

These builds offer the latest features and bug fixes at a cost of being slower and introducing new bugs. Ensure that you make backups before installing.

**Download:** [Obtainium][Obtainium link] • [GitHub][GitHub link] • [nightly.link][Nightly link]

### Verification

Verify the integrity of your Lawnchair download using these SHA-256 hashes:

###### Google Play
```
47:AC:92:63:1C:60:35:13:CC:8D:26:DD:9C:FF:E0:71:9A:8B:36:55:44:DC:CE:C2:09:58:24:EC:25:61:20:A7
```

###### Elsewhere
```
74:7C:36:45:B3:57:25:8B:2E:23:E8:51:E5:3C:96:74:7F:E0:AD:D0:07:E5:BA:2C:D9:7E:8C:85:57:2E:4D:C5
```

## Contributing

Please visit the [Lawnchair Contributing Guidelines](CONTRIBUTING.md) for information and tips on contributing to Lawnchair.

## Supporting Lawnchair

If you love what we do, consider [supporting us on Open Collective](https://opencollective.com/lawnchair)! Your contributions help keep Lawnchair independent and enable us to develop faster.

A huge thank you to our **Core Backers ($5+)**:
*(These backers directly fund our Project Velocity Fund)*

[![Core Backers](https://opencollective.com/lawnchair/tiers/backer.svg?avatarHeight=64&width=890&button=false)](https://opencollective.com/lawnchair)

[Become a supporter](https://opencollective.com/lawnchair) to help us cover our operational costs, or become a Core Backer to be featured here!

## Quick links

-   [Website](https://lawnchair.app)
-   [News on Telegram](https://t.me/lawnchairci)
-   [Discord](https://discord.com/invite/3x8qNWxgGZ)
-   [Lawnchair on X (formerly Twitter)](https://x.com/lawnchairapp)
-   [_XDA_ thread](https://xdaforums.com/t/lawnchair-customizable-pixel-launcher.3627137/)

You can view all our links in the [Lawnchair Wiki](https://github.com/LawnchairLauncher/lawnchair/wiki).

<!-- Download link -->
[Nightly link]: https://nightly.link/LawnchairLauncher/lawnchair/workflows/ci/15-dev
[Obtainium link]: https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22app.lawnchair.nightly%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Flawnchairlauncher%2Flawnchair%22%2C%22author%22%3A%22Lawnchair%20Launcher%22%2C%22name%22%3A%22Lawnchair%20(Debug)%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Afalse%2C%5C%22filterReleaseTitlesByRegEx%5C%22%3A%5C%22Lawnchair%20Nightly%5C%22%2C%5C%22filterReleaseNotesByRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22verifyLatestTag%5C%22%3Afalse%2C%5C%22dontSortReleasesList%5C%22%3Afalse%2C%5C%22useLatestAssetDateAsReleaseDate%5C%22%3Afalse%2C%5C%22trackOnly%5C%22%3Afalse%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%22%5C%22%2C%5C%22versionDetection%5C%22%3Afalse%2C%5C%22releaseDateAsVersion%5C%22%3Atrue%2C%5C%22useVersionCodeAsOSVersion%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22appName%5C%22%3A%5C%22%5C%22%2C%5C%22shizukuPretendToBeGooglePlay%5C%22%3Afalse%2C%5C%22exemptFromBackgroundUpdates%5C%22%3Afalse%2C%5C%22skipUpdateNotifications%5C%22%3Afalse%2C%5C%22about%5C%22%3A%5C%22Lawnchair%20is%20a%20free%2C%20open-source%20home%20app%20for%20Android.%20(NOTE%3A%20This%20is%20the%20debug%20version%20of%20Lawnchair%2C%20for%20the%20beta%2Fstable%20versions%20see%20%5C%5C%5C%22Lawnchair%5C%5C%5C%22)%5C%22%7D%22%7D
[GitHub link]: https://github.com/LawnchairLauncher/lawnchair/releases/tag/nightly

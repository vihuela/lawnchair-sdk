# Launcher SDK architecture and platform porting

The SDK integration is split into four boundaries. Keep dependencies pointing down this list:

1. `launcher-sdk-api` — public commands, results, state/events, and extension DTOs.
2. `launcher-sdk-runtime` — launcher-independent controllers, reconciliation rules, and ports.
3. `lawnchair/.../sdk` — Android/Launcher3 adapters for the selected AOSP family.
4. Launcher3 hook sites — minimal calls into `LawnchairSdkHooks`; no registry, coroutine, or DB logic.

## Stable external contract

Hosts send `LauncherCommand` to the suspending `LawnchairOperations.execute` function and receive a
`LauncherCommandResult`. Long-lived state is a `StateFlow`; one-shot observations are a `Flow` of
`LauncherEvent`. Desktop extensions expose suspending input/output functions. Launcher lifecycle
hooks are explicitly fire-and-forget and do not pretend to return command completion.

`MinusOneController` and `DesktopItemsController` contain the business rules. Their ports are the
seams to replace when Launcher3 changes. They intentionally do not reference `Launcher`,
`LauncherModel`, SQLite cursors, Compose, lifecycle, or Android 15 framework classes.

## Android platform adapter

The current adapter family is reported in `LauncherStateSnapshot.platformAdapterVersion` and is
selected with `launcherPlatformVersion` in the root `gradle.properties`. Framework, SystemUI stats,
and WindowManager Shell prebuilt names derive from that one value.

Moving to 16-dev should start with:

1. Add `framework-16.jar`, `SystemUI-statsd-16.jar`, and `WindowManager-Shell-16.jar`.
2. Set `launcherPlatformVersion=16`.
   The SystemUI `iconloaderlib` submodule still declares an additional compile-only 15 jar; the
   root build puts the selected jar first, but remove that redundant declaration when updating the
   submodule itself.
3. Port `LocalLauncherOverlay`, `MinusOneCoordinator`, and `LauncherWorkspaceAdapter` to the new
   Launcher3 APIs while keeping their runtime port contracts unchanged.
4. Reconnect only the `LawnchairSdkHooks` calls in `ItemClickHandler`, `DeleteDropTarget`, and
   `DropTargetHandler`, plus the launcher lifecycle calls owned by `LawnchairLauncher`.
5. Run the launcher-independent runtime tests before device integration tests.

## Play compliance boundary

The removals introduced by `854f2c03` are compliance requirements, not optional runtime features.
The distribution source tree must not contain the listed sensitive implementation files,
permissions, components, resources, or dependencies. A disabled entry point or capability flag is
not a substitute for physical removal.

`play-compliance.gradle` is an enforcement list. It does not exclude sources from compilation. The
`verifyPlayCompliance` task fails as soon as a forbidden source, manifest declaration, or dependency
returns, and both the application and published launcher-core `preBuild` paths depend on it. This
makes an upstream merge fail visibly instead of silently packaging dormant capability code.

For a 16-dev migration:

1. Restore the module and platform adapter structure on a temporary integration branch.
2. Reapply the physical compliance deletions before the result enters the distribution branch.
3. Remove the corresponding manifest declarations, resources, dependencies, initialization, and
   references; do not merely hide their settings or entry points.
4. Update `play-compliance.gradle` when upstream renames or splits a prohibited implementation.
5. Run `verifyPlayCompliance`, compile the app, and inspect the merged manifest and final artifact.

Manifest overlays and capability descriptors remain useful for ordinary product variants. They
must not be used to retain Play-sensitive implementation code. A `tools:node="remove"` rule is only
defense in depth for a transitive manifest declaration and is not a replacement for removing the
source or dependency that supplies the prohibited capability.

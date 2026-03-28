# Lawnchair SDK publishing

This repository can be released through JitPack, and `sdk-local` is the local-source integration entry used by host apps during development.

## Published modules

- `launcher-sdk-api`
- `launcher-core`
- `sdk-local` is not published; it exists to support local `includeBuild` source integration.

## Local verification

From the repo root:

```bash
./gradlew :launcher-sdk-api:publishToMavenLocal :launcher-core:publishToMavenLocal
```

Optional custom coordinates:

```bash
./gradlew \
  -PPUBLISH_GROUP=com.github.vihuela.lawnchair-sdk \
  -PPUBLISH_VERSION=0.1.5-local \
  :launcher-sdk-api:publishToMavenLocal \
  :launcher-core:publishToMavenLocal
```

## JitPack release

1. Push your release branch to GitHub.
2. Create and push a tag:

```bash
git tag v0.1.5
git push origin v0.1.5
```

3. Wait for JitPack to finish building the tag.

Dependency coordinates:

```text
com.github.vihuela.lawnchair-sdk:launcher-sdk-api:v0.1.5
com.github.vihuela.lawnchair-sdk:launcher-core:v0.1.5
```

Consumer repositories:

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url "https://jitpack.io" }
    }
}
```

## SDK branch delta ledger

This section is the migration memo for rebasing this SDK branch onto a newer upstream Lawnchair branch later.

- Upstream baseline before SDK customization: `854f2c036a` (`裁剪系统权限、一般app无关权限`)
- Current release target: `v0.1.5`
- Migration goal: when moving to a new upstream branch, re-apply the capabilities below in order instead of rediscovering them from scratch.

### Commit milestones

| 阶段 | 提交 | 主题 | 说明 |
| --- | --- | --- | --- |
| 1 | `854f2c036a` | 权限与系统能力裁剪 | 去掉一般三方 app 不需要的系统权限、系统服务和能力入口。 |
| 2 | `10b10bcb73` | 提供外部 SDK | 引入 SDK 对外能力、负一屏/桌面注入主链路。 |
| 3 | `a2d314a63b` | 发布配置 | 增加 `launcher-core`、`launcher-sdk-api` 发布配置。 |
| 4 | `e3b02b152e` | JitPack 修复 | 修正 SystemUI 子模块在 JitPack 环境下的引用。 |
| 5 | `c7488b37e9` | 安装任务 | 配置 JitPack/本地安装任务。 |
| 6 | `0dc53a0c01` | 传递依赖发布 | 对外发布补齐 SDK 所需的传递依赖。 |
| 7 | `e1b64d1fcb` | 负一屏启动能力 | 增加动画打开负一屏等公开操作能力。 |
| 8 | `v0.1.5` 本次 | 宿主 `Application` 兼容 | 解决本地 `includeBuild` 时宿主不继承 `LawnchairApp` 也能初始化 SDK。 |

### Capability delta matrix

| 分类 | 已改内容 | 关键文件 | 后续迁移提示 |
| --- | --- | --- | --- |
| 权限与系统能力裁剪 | 删除一般三方应用不该保留的系统权限、系统组件、root/bugreport/accessibility/睡眠/状态栏等能力。 | `AndroidManifest-common.xml`, `lawnchair/AndroidManifest.xml`, `quickstep/AndroidManifest.xml`, `wmshell/AndroidManifest.xml`, `github/AndroidManifest.xml`, `res/xml/accessibility_service_config.xml`, `res/xml/sleep_policy.xml`, `lawnchair/src/app/lawnchair/root/**`, `lawnchair/src/app/lawnchair/bugreport/**`, `src/com/android/launcher3/model/SdCardAvailableReceiver.java`, `quickstep/src/com/android/launcher3/touchcontrollers/StatusBarTouchController.java` | 迁移到新 upstream 时先做这一层，最容易和清单、权限、系统特性代码产生冲突。 |
| SDK 模块化拆分 | 新增 `launcher-core`、`launcher-sdk-api`、`sdk-local`，把 launcher 运行时和对外 API 分层。 | `launcher-core/**`, `launcher-sdk-api/**`, `sdk-local/**`, `settings.gradle`, `build.gradle` | 这是整个 SDK 化的骨架，迁移时应优先恢复模块关系和 Gradle 配置。 |
| 发布与远程依赖 | 增加 Maven 发布、JitPack 构建、传递依赖发布、本地 `publishToMavenLocal` 验证链路。 | `launcher-core/build.gradle`, `launcher-sdk-api/build.gradle`, `jitpack.yml`, `sdk-local/build.gradle`, `sdk-local/settings.gradle`, `sdk-local/gradle.properties` | 这部分通常和 upstream 业务代码冲突较少，适合尽早恢复。 |
| 宿主接入入口 | 对外暴露 `LawnchairSdkEntry`、`LawnchairOperations`、`DesktopItemProvider`、`MinusOneContentProvider` 等 API，宿主可只接 SDK API 层。 | `launcher-sdk-api/src/main/java/app/lawnchair/sdk/**`, `launcher-sdk-api/src/main/java/app/lawnchair/sdk/api/**` | 未来如果继续扩展能力，优先放在 `launcher-sdk-api`，避免外部依赖 `launcher-core` 细节。 |
| 负一屏 SDK 化 | Google feed 入口改成本地负一屏，业务侧可注入纯 Compose 内容；支持启动时直接进入负一屏、动画打开负一屏、负一屏状态监听。 | `lawnchair/src/app/lawnchair/nexuslauncher/LocalLauncherOverlay.kt`, `lawnchair/src/app/lawnchair/nexuslauncher/OverlayCallbackImpl.kt`, `lawnchair/src/app/lawnchair/LawnchairLauncher.kt`, `launcher-sdk-api/src/main/java/app/lawnchair/sdk/api/MinusOneContentProvider.kt`, `launcher-sdk-api/src/main/java/app/lawnchair/sdk/api/MinusOneOverlayState.kt`, `launcher-sdk-api/src/main/java/app/lawnchair/sdk/api/LawnchairSdkEntry.kt`, `lawnchair/src/app/lawnchair/sdk/PublicLawnchairOperations.kt` | 这是和 upstream launcher 行为冲突最高的区域之一，新 upstream 如果又改了 overlay 手势/Workspace 左滑逻辑，需要优先对比这里。 |
| 桌面业务入口开放 | 业务侧可声明桌面图标数据和点击回调，支持显式 `Intent` 和 provider action 两类点击行为。 | `launcher-sdk-api/src/main/java/app/lawnchair/sdk/api/DesktopItemProvider.kt`, `lawnchair/src/app/lawnchair/sdk/SdkDesktopItemsSynchronizer.kt`, `lawnchair/src/app/lawnchair/sdk/SdkDesktopItemActionDispatcher.kt`, `src/com/android/launcher3/touch/ItemClickHandler.java` | 这里的核心是“稳定 ID + 首次落位后保留用户移动结果”，迁移时不要只恢复插入逻辑，持久化和点击分发也要一起带上。 |
| 默认桌面与布局定制 | 默认 `default_workspace_4x5` 被定制过，Google 文件夹、设置位置和 Demo 入口都做过调整。 | `lawnchair/res/xml/default_workspace_4x5.xml`, `src/com/android/launcher3/model/WorkspaceItemProcessor.kt`, `src/com/android/launcher3/model/BgDataModel.java` | 这类资源冲突简单，但容易被 upstream 覆盖，迁移后要重新核对默认布局是否符合 SDK 预期。 |
| 设置页与功能开关裁剪 | 去掉大量和一般宿主 app 无关的设置项与调试入口，例如关于页、实验功能、部分搜索/Quickstep/Google Feed 开关。 | `lawnchair/src/app/lawnchair/ui/preferences/**`, `lawnchair/res/values/strings.xml`, `lawnchair/res/values/config.xml`, `quickstep/src/com/android/launcher3/uioverrides/flags/DevOptionsUiHelper.kt` | 如果新 upstream 设置结构变化大，不必逐文件硬搬，按“只保留 SDK 必需入口”重新裁即可。 |
| 宿主 `Application` 兼容 | 解决 `includeBuild` 本地源码接入时，宿主 app 不再必须继承 `LawnchairApp`；新增反射初始化和 host runtime，尽量少改原有 `LawnchairApp` 逻辑。 | `launcher-sdk-api/src/main/java/app/lawnchair/sdk/LawnchairSdkHost.kt`, `lawnchair/src/app/lawnchair/LawnchairHostRuntime.kt`, `lawnchair/src/app/lawnchair/LawnchairApp.kt`, `src/com/android/launcher3/LauncherAppState.java`, `src/com/android/launcher3/model/ModelDbController.java`, `quickstep/src/com/android/launcher3/uioverrides/QuickstepLauncher.java`, `src/com/android/launcher3/dragndrop/DragController.java`, `lawnchair/src/app/lawnchair/util/FileExtensions.kt`, `lawnchair/src/app/lawnchair/util/LawnchairHostInfo.kt`, `launcher-core/consumer-rules.pro` | 这部分是为了让本地源码依赖和远程依赖行为保持一致。新 upstream 迁移时，如果宿主接入再次出现 `LawnchairApp` 强依赖崩溃，优先把这组改动整体带回。 |

### Recommended rebase order

| 顺序 | 先做什么 | 说明 |
| --- | --- | --- |
| 1 | 恢复模块拆分和发布链路 | 先让 `launcher-core`、`launcher-sdk-api`、`sdk-local` 能编译和发布。 |
| 2 | 恢复权限/Manifest 裁剪 | 把系统权限和不需要的系统组件先裁掉，避免后面能力迁移时混入无关问题。 |
| 3 | 恢复对外 API 和 SDK registry | 先把 `LawnchairSdkEntry`、`DesktopItemProvider`、`MinusOneContentProvider` 的主链路连通。 |
| 4 | 恢复负一屏和桌面同步能力 | 这是 SDK 的核心业务能力，优先确认左右滑、图标持久化、点击分发都正常。 |
| 5 | 恢复宿主 `Application` 兼容层 | 最后补 `LawnchairHostRuntime`、`LawnchairSdkHost` 等兼容逻辑，保证 `includeBuild` 和远程依赖行为一致。 |

### High-conflict files

These files are most likely to conflict when rebasing onto a new upstream branch:

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

### Local source integration notes

- Host apps using remote dependencies do not need to subclass `LawnchairApp`.
- Host apps using local `includeBuild` should keep calling `LawnchairSdkHost.initialize(application)` from their own `Application`.
- `LawnchairApp.application` is the safe global `Application` accessor for SDK code paths that only need `Context`.
- Methods that require launcher-specific behavior must still go through the compat bridge in `LawnchairApp`, not a blind cast back to `LawnchairApp`.

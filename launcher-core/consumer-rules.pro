# Host apps can initialize the runtime reflectively via launcher-sdk-api.
-keep class app.lawnchair.LawnchairHostRuntime {
    public static void initialize(android.app.Application);
}

# Launcher selects these implementations from resource class names and invokes their constructors
# reflectively. Preserve both names and constructors in the host application's R8 pass.
-keep class ** implements com.android.launcher3.util.ResourceBasedOverride {
    public <init>(...);
}

# Launcher is compiled against framework and SystemUI APIs that are not exposed by the public
# Android SDK jar used by a host application's R8 run. Runtime access is already SDK/capability
# guarded; suppress resolution warnings without keeping or packaging phantom framework classes.
-dontwarn android.app.ActivityClient
-dontwarn android.app.ActivityManager$RecentTaskInfo$PersistedTaskSnapshotData
-dontwarn android.app.ActivityOptions$OnAnimationFinishedListener
-dontwarn android.app.ActivityOptions$OnAnimationStartedListener
-dontwarn android.app.ActivityTaskManager$RootTaskInfo
-dontwarn android.app.ActivityTaskManager
-dontwarn android.app.ActivityThread$ApplicationThread
-dontwarn android.app.ActivityThread
-dontwarn android.app.AppGlobals
-dontwarn android.app.IActivityManager
-dontwarn android.app.IActivityTaskManager
-dontwarn android.app.IApplicationThread$Stub
-dontwarn android.app.IApplicationThread
-dontwarn android.app.IAssistDataReceiver
-dontwarn android.app.ITaskStackListener
-dontwarn android.app.TaskStackListener
-dontwarn android.app.WindowConfiguration
-dontwarn android.app.prediction.AppPredictor$Callback
-dontwarn android.app.prediction.AppTarget
-dontwarn android.app.search.Query
-dontwarn android.app.search.SearchAction
-dontwarn android.app.search.SearchContext
-dontwarn android.app.search.SearchSession
-dontwarn android.app.search.SearchTarget
-dontwarn android.app.search.SearchUiManager
-dontwarn android.content.pm.IPackageManager
-dontwarn android.content.pm.LauncherApps$AppUsageLimit
-dontwarn android.content.pm.ParceledListSlice
-dontwarn android.content.pm.SuspendDialogInfo$Builder
-dontwarn android.content.pm.SuspendDialogInfo
-dontwarn android.graphics.CanvasProperty
-dontwarn android.graphics.HardwareRenderer$FrameDrawingCallback
-dontwarn android.graphics.animation.RenderNodeAnimator
-dontwarn android.media.permission.SafeCloseable
-dontwarn android.os.IRemoteCallback$Stub
-dontwarn android.os.IRemoteCallback
-dontwarn android.os.SystemProperties
-dontwarn android.tracing.Flags
-dontwarn android.tracing.perfetto.DataSource
-dontwarn android.tracing.perfetto.DataSourceParams$Builder
-dontwarn android.tracing.perfetto.DataSourceParams
-dontwarn android.tracing.perfetto.InitArguments
-dontwarn android.tracing.perfetto.Producer
-dontwarn android.tracing.perfetto.TraceFunction
-dontwarn android.util.MathUtils
-dontwarn android.util.PathParser
-dontwarn android.util.RotationUtils
-dontwarn android.view.CrossWindowBlurListeners
-dontwarn android.view.GhostView
-dontwarn android.view.IAppTransitionAnimationSpecsFuture$Stub
-dontwarn android.view.IAppTransitionAnimationSpecsFuture
-dontwarn android.view.IRecentsAnimationRunner$Stub
-dontwarn android.view.IRecentsAnimationRunner
-dontwarn android.view.IRemoteAnimationFinishedCallback$Stub
-dontwarn android.view.IRemoteAnimationFinishedCallback
-dontwarn android.view.IRemoteAnimationRunner$Stub
-dontwarn android.view.IRemoteAnimationRunner
-dontwarn android.view.IWindowManager
-dontwarn android.view.RemoteAnimationAdapter
-dontwarn android.view.RemoteAnimationDefinition
-dontwarn android.view.RemoteAnimationTarget
-dontwarn android.view.RenderNodeAnimator
-dontwarn android.view.SyncRtSurfaceTransactionApplier$SurfaceParams$Builder
-dontwarn android.view.SyncRtSurfaceTransactionApplier$SurfaceParams
-dontwarn android.view.SyncRtSurfaceTransactionApplier
-dontwarn android.view.ThreadedRenderer
-dontwarn android.view.ViewRootImpl$SurfaceChangedCallback
-dontwarn android.view.ViewRootImpl
-dontwarn android.view.ViewTreeObserver$OnComputeInternalInsetsListener
-dontwarn android.view.WindowManagerGlobal
-dontwarn android.view.animation.BackGestureInterpolator
-dontwarn android.widget.RemoteViews$ColorResources
-dontwarn android.window.BackProgressAnimator
-dontwarn android.window.IDumpCallback$Stub
-dontwarn android.window.IDumpCallback
-dontwarn android.window.IOnBackInvokedCallback$Stub
-dontwarn android.window.IOnBackInvokedCallback
-dontwarn android.window.PictureInPictureSurfaceTransaction$Builder
-dontwarn android.window.PictureInPictureSurfaceTransaction
-dontwarn android.window.TaskSnapshot
-dontwarn android.window.WindowContainerToken
-dontwarn android.window.WindowContainerTransaction
-dontwarn com.android.internal.annotations.Keep
-dontwarn com.android.internal.annotations.VisibleForTesting
-dontwarn com.android.internal.colorextraction.ColorExtractor$GradientColors
-dontwarn com.android.internal.colorextraction.types.Tonal
-dontwarn com.android.internal.jank.InteractionJankMonitor
-dontwarn com.android.internal.logging.InstanceId
-dontwarn com.android.internal.logging.InstanceIdSequence
-dontwarn com.android.internal.os.SomeArgs
-dontwarn com.android.internal.policy.ScreenDecorationsUtils
-dontwarn com.android.internal.util.AnnotationValidations
-dontwarn com.android.internal.util.ArrayUtils
-dontwarn com.android.launcher3.icons.BitmapInfo$BitmapInfoFlags
-dontwarn com.android.window.flags.Flags

# Smartspacer's Balloon UI is explicitly excluded from launcher-core; those optional paths are not
# used by the embedded launcher integration.
-dontwarn com.skydoves.balloon.**

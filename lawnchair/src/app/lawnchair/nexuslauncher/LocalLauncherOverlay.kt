package app.lawnchair.nexuslauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lawnchair.LawnchairLauncher
import app.lawnchair.sdk.api.MinusOneState
import app.lawnchair.sdk.runtime.MinusOneGeometry
import app.lawnchair.sdk.runtime.MinusOneHorizontalGesturePolicy
import app.lawnchair.sdk.runtime.MinusOneProgressStateMachine
import com.android.launcher3.InsettableFrameLayout
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlay
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks

class LocalLauncherOverlay(
    private val launcher: LawnchairLauncher,
    private val content: @Composable (Modifier) -> Unit,
    private val onStateChanged: (MinusOneState) -> Unit,
) : LauncherOverlay,
    LauncherOverlayManager {

    private var overlayContainer: FrameLayout? = null
    private var launcherCallbacks: LauncherOverlayCallbacks? = null
    private var progressAnimator: ValueAnimator? = null

    private var currentProgress = 0f
    private var lastFlingVelocity = 0f
    private var isAdded = false
    private var isEnabled = true
    private var screenWidth = 0
    private val stateMachine = MinusOneProgressStateMachine()

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var swipeStartProgress = 0f
    private var isSwiping = false
    private var gestureTarget = HorizontalGestureTarget.Undecided

    private val touchSlop by lazy {
        ViewConfiguration.get(launcher).scaledTouchSlop
    }

    fun initialize() {
        onAttachedToWindow()
        launcher.setLauncherOverlay(if (isEnabled) this else null)
    }

    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return

        isEnabled = enabled
        if (enabled) {
            onAttachedToWindow()
            launcher.setLauncherOverlay(this)
            updateOverlayPosition(0f)
        } else {
            hideOverlay(animate = false)
            launcher.setLauncherOverlay(null)
        }
    }

    override fun onScrollInteractionBegin() {
        if (!isEnabled) return
        progressAnimator?.cancel()
        lastFlingVelocity = 0f
    }

    override fun onScrollChange(progress: Float, rtl: Boolean) {
        if (!isEnabled) return
        updateOverlayPosition(progress)
        launcherCallbacks?.onOverlayScrollChanged(currentProgress)
    }

    override fun onFlingVelocity(velocity: Float) {
        lastFlingVelocity = velocity
    }

    override fun onScrollInteractionEnd() {
        if (!isEnabled) return

        val targetProgress = when {
            currentProgress > (1f - SWITCH_THRESHOLD) -> 1f
            currentProgress < SWITCH_THRESHOLD -> 0f
            lastFlingVelocity > 0 -> 1f
            lastFlingVelocity < 0 -> 0f
            else -> if (currentProgress > 0.5f) 1f else 0f
        }

        animateToProgress(targetProgress)
        lastFlingVelocity = 0f
    }

    override fun setOverlayCallbacks(callbacks: LauncherOverlayCallbacks?) {
        launcherCallbacks = callbacks
    }

    override fun openOverlay() {
        if (!isEnabled) return
        animateToProgress(1f)
    }

    fun openOverlayImmediately() {
        if (!isEnabled) return
        progressAnimator?.cancel()
        updateOverlayPosition(1f)
        launcherCallbacks?.onOverlayScrollChanged(1f)
    }

    override fun onAttachedToWindow() {
        ensureScreenWidth()
        createOverlayContainer()
        addToLauncherRootView()
        updateOverlayPosition(currentProgress)
    }

    override fun hideOverlay(animate: Boolean) {
        hideOverlay(if (animate) ANIMATION_DURATION_MS.toInt() else 0)
    }

    override fun hideOverlay(duration: Int) {
        if (duration > 0) {
            animateToProgress(0f)
        } else {
            updateOverlayPosition(0f)
            launcherCallbacks?.onOverlayScrollChanged(0f)
        }
    }

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        cleanup()
    }

    private fun createOverlayContainer() {
        if (overlayContainer != null) return

        overlayContainer = object : FrameLayout(launcher) {
            private var lastTouchX = 0f

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (!isEnabled) return super.dispatchTouchEvent(ev)

                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        progressAnimator?.cancel()
                        touchDownX = ev.rawX
                        touchDownY = ev.rawY
                        lastTouchX = ev.rawX
                        swipeStartProgress = currentProgress
                        isSwiping = false
                        gestureTarget = HorizontalGestureTarget.Undecided
                        return super.dispatchTouchEvent(ev)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = ev.rawX - touchDownX
                        val deltaY = ev.rawY - touchDownY
                        if (gestureTarget == HorizontalGestureTarget.Undecided &&
                            kotlin.math.abs(deltaX) > touchSlop &&
                            kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)
                        ) {
                            val scrollDirection =
                                MinusOneHorizontalGesturePolicy.contentScrollDirection(deltaX)
                            val canContentScroll = scrollDirection != 0 &&
                                getChildAt(0)?.canScrollHorizontallyAt(
                                    rawX = ev.rawX.toInt(),
                                    rawY = ev.rawY.toInt(),
                                    direction = scrollDirection,
                                    bounds = Rect(),
                                ) == true
                            val deferToContent =
                                MinusOneHorizontalGesturePolicy.shouldDeferToContent(
                                    overlayProgress = currentProgress,
                                    canContentScrollInDragDirection = canContentScroll,
                                )
                            gestureTarget = if (deferToContent) {
                                HorizontalGestureTarget.Content
                            } else {
                                HorizontalGestureTarget.Overlay
                            }

                            if (deferToContent) {
                                // Keep this entire gesture with the child. If it reaches an edge
                                // mid-drag, Launcher may take over only on the next gesture.
                                parent?.requestDisallowInterceptTouchEvent(true)
                            } else {
                                isSwiping = true
                                MotionEvent.obtain(ev).apply {
                                    action = MotionEvent.ACTION_CANCEL
                                    super.dispatchTouchEvent(this)
                                    recycle()
                                }
                            }
                        }

                        if (gestureTarget == HorizontalGestureTarget.Content) {
                            return super.dispatchTouchEvent(ev)
                        }

                        if (isSwiping) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                            val width = ensureScreenWidth().toFloat()
                            if (width <= 0f) return true
                            val deltaFromLast = ev.rawX - lastTouchX
                            lastTouchX = ev.rawX
                            val progressDelta = MinusOneGeometry.dragProgressDelta(
                                deltaX = deltaFromLast,
                                width = width,
                                isRtl = Utilities.isRtl(resources),
                            )
                            val newProgress = (currentProgress + progressDelta).coerceIn(0f, 1f)
                            updateOverlayPosition(newProgress)
                            launcherCallbacks?.onOverlayScrollChanged(newProgress)
                            return true
                        }

                        return super.dispatchTouchEvent(ev)
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isSwiping) {
                            val swipeDelta = currentProgress - swipeStartProgress
                            val targetProgress = when {
                                swipeDelta > SWITCH_THRESHOLD -> 1f
                                swipeDelta < -SWITCH_THRESHOLD -> 0f
                                else -> if (swipeStartProgress > 0.5f) 1f else 0f
                            }
                            animateToProgress(targetProgress)
                            isSwiping = false
                            gestureTarget = HorizontalGestureTarget.Undecided
                            return true
                        }

                        isSwiping = false
                        val handled = super.dispatchTouchEvent(ev)
                        gestureTarget = HorizontalGestureTarget.Undecided
                        return handled
                    }
                }

                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            translationX = closedTranslationX()
            addView(MinusOneComposeHost.create(context, content))
        }
    }

    /**
     * Finds a horizontally scrollable view under the pointer. ComposeView is only a host; its
     * AndroidComposeView child exposes the actual scrollability of HorizontalPager/LazyRow.
     */
    private fun View.canScrollHorizontallyAt(
        rawX: Int,
        rawY: Int,
        direction: Int,
        bounds: Rect,
    ): Boolean {
        if (!isShown || !getGlobalVisibleRect(bounds) || !bounds.contains(rawX, rawY)) return false

        if (this is ViewGroup) {
            for (index in childCount - 1 downTo 0) {
                if (getChildAt(index).canScrollHorizontallyAt(rawX, rawY, direction, bounds)) {
                    return true
                }
            }
        }

        return canScrollHorizontally(direction)
    }

    private fun addToLauncherRootView() {
        if (isAdded) return

        val rootView = launcher.rootView ?: return
        val params = InsettableFrameLayout.LayoutParams(
            ensureScreenWidth(),
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            ignoreInsets = true
        }

        rootView.addView(overlayContainer, params)
        isAdded = true
    }

    private fun animateToProgress(targetProgress: Float) {
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(currentProgress, targetProgress).apply {
            var wasCancelled = false
            duration = ANIMATION_DURATION_MS
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                updateOverlayPosition(progress)
                launcherCallbacks?.onOverlayScrollChanged(progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    wasCancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!wasCancelled) {
                        updateOverlayPosition(targetProgress)
                        launcherCallbacks?.onOverlayScrollChanged(targetProgress)
                    }
                    if (progressAnimator === animation) progressAnimator = null
                }
            })
            start()
        }
    }

    private fun updateOverlayPosition(progress: Float) {
        currentProgress = progress.coerceIn(0f, 1f)
        val width = ensureScreenWidth().toFloat()
        overlayContainer?.translationX = MinusOneGeometry.translationX(
            progress = currentProgress,
            width = width,
            isRtl = Utilities.isRtl(launcher.resources),
        )
        onStateChanged(stateMachine.update(currentProgress))
    }

    private fun ensureScreenWidth(): Int {
        val rootWidth = launcher.rootView?.width ?: 0
        screenWidth = if (rootWidth > 0) rootWidth else launcher.resources.displayMetrics.widthPixels

        overlayContainer?.layoutParams?.let { params ->
            params.width = screenWidth
            overlayContainer?.layoutParams = params
        }

        return screenWidth
    }

    private fun cleanup() {
        progressAnimator?.cancel()
        progressAnimator = null
        launcherCallbacks = null
        val rootView = launcher.rootView
        if (isAdded && rootView != null) {
            rootView.removeView(overlayContainer)
        }

        overlayContainer?.removeAllViews()
        overlayContainer = null
        isAdded = false
    }

    private fun closedTranslationX(): Float {
        return MinusOneGeometry.translationX(
            progress = 0f,
            width = ensureScreenWidth().toFloat(),
            isRtl = Utilities.isRtl(launcher.resources),
        )
    }

    companion object {
        private const val ANIMATION_DURATION_MS = 250L
        private const val SWITCH_THRESHOLD = 0.3f
    }

    private enum class HorizontalGestureTarget {
        Undecided,
        Content,
        Overlay,
    }
}

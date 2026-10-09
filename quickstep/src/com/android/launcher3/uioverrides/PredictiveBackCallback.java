/*
 * Copyright (C) 2024 The Lawnchair Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.uioverrides;

import android.os.Build;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.launcher3.OnBackPressedHandler;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.testing.TestLogging;
import com.android.launcher3.testing.shared.TestProtocol;

/**
 * Holds every reference to the API-34 predictive-back types (OnBackAnimationCallback /
 * BackEvent) in a class that is only loaded on U+.
 *
 * Keeping the anonymous callback inline inside QuickstepLauncher made the whole launcher
 * hierarchy fail class verification with NoClassDefFoundError on Android 12/13 (API 31-33),
 * because the anonymous class extends a framework type that does not exist there.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
final class PredictiveBackCallback implements OnBackAnimationCallback {

    static void register(QuickstepLauncher launcher) {
        launcher.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                new PredictiveBackCallback(launcher));
    }

    private final QuickstepLauncher mLauncher;

    @Nullable
    private OnBackPressedHandler mActiveOnBackPressedHandler;

    private PredictiveBackCallback(QuickstepLauncher launcher) {
        mLauncher = launcher;
    }

    @Override
    public void onBackStarted(@NonNull BackEvent backEvent) {
        if (mActiveOnBackPressedHandler != null) {
            mActiveOnBackPressedHandler.onBackCancelled();
        }
        mActiveOnBackPressedHandler = mLauncher.getOnBackPressedHandlerCompat();
        mActiveOnBackPressedHandler.onBackStarted();
    }

    @Override
    public void onBackInvoked() {
        // Recreate mActiveOnBackPressedHandler if necessary to avoid NPE because:
        // 1. b/260636433: In 3-button-navigation mode, onBackStarted() is not called on
        //    ACTION_DOWN before onBackInvoked() is called in ACTION_UP.
        // 2. Launcher#onBackPressed() will call onBackInvoked() without calling
        //    onBackInvoked() beforehand.
        if (mActiveOnBackPressedHandler == null) {
            mActiveOnBackPressedHandler = mLauncher.getOnBackPressedHandlerCompat();
        }
        mActiveOnBackPressedHandler.onBackInvoked();
        mActiveOnBackPressedHandler = null;
        TestLogging.recordEvent(TestProtocol.SEQUENCE_MAIN, "onBackInvoked");
    }

    @Override
    public void onBackProgressed(@NonNull BackEvent backEvent) {
        if (!FeatureFlags.IS_STUDIO_BUILD && mActiveOnBackPressedHandler == null) {
            return;
        }
        mActiveOnBackPressedHandler.onBackProgressed(backEvent.getProgress());
    }

    @Override
    public void onBackCancelled() {
        if (!FeatureFlags.IS_STUDIO_BUILD && mActiveOnBackPressedHandler == null) {
            return;
        }
        mActiveOnBackPressedHandler.onBackCancelled();
        mActiveOnBackPressedHandler = null;
    }
}

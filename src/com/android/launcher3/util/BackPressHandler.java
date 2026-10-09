/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.launcher3.util;

import androidx.annotation.FloatRange;

/**
 * Back handler contract that mirrors the framework's OnBackAnimationCallback without
 * referencing it, so classes in the launcher hierarchy stay loadable below API 34
 * (android.window.OnBackAnimationCallback only exists on U+; extending it here made every
 * implementing/referencing class fail verification with NoClassDefFoundError on Android 12/13).
 */
public interface BackPressHandler {

    /** Whether this handler currently supports handling back. */
    boolean canHandleBack();

    /** Called when a back gesture has started. */
    default void onBackStarted() {}

    /** Called when back is committed. */
    void onBackInvoked();

    /** Called with the back gesture's progress. */
    default void onBackProgressed(@FloatRange(from = 0.0, to = 1.0) float backProgress) {}

    /** Called when the back gesture is cancelled. */
    default void onBackCancelled() {}
}

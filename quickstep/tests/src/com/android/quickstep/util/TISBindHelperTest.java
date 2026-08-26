/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.quickstep.util;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class TISBindHelperTest {

    @Mock private Context mContext;
    @Mock private PackageManager mPackageManager;
    @Mock private ResolveInfo mResolveInfo;

    private TISBindHelper mHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.resolveService(any(Intent.class), eq(0))).thenReturn(mResolveInfo);
        when(mContext.bindService(
                        any(Intent.class), any(ServiceConnection.class), eq(0)))
                .thenReturn(true);
        mHelper = new TISBindHelper(mContext, binder -> {});
    }

    @Test
    public void onDestroy_unbindsOnlyOnce() {
        mHelper.onDestroy();
        mHelper.onDestroy();

        verify(mContext, times(1)).unbindService(mHelper);
    }

    @Test
    public void onBindingDied_afterDestroy_doesNotRebind() {
        mHelper.onDestroy();
        mHelper.onBindingDied(new ComponentName("test.package", "TestService"));

        verify(mContext, times(1)).bindService(any(Intent.class), eq(mHelper), eq(0));
        verify(mContext, times(1)).unbindService(mHelper);
    }

    @Test
    public void onBindingDied_replacesExistingBinding() {
        mHelper.onBindingDied(new ComponentName("test.package", "TestService"));

        verify(mContext, times(2)).bindService(any(Intent.class), eq(mHelper), eq(0));
        verify(mContext, times(1)).unbindService(mHelper);
    }

    @Test
    public void serviceNotDeclared_doesNotAttemptBinding() {
        mHelper.onDestroy();
        when(mPackageManager.resolveService(any(Intent.class), eq(0))).thenReturn(null);

        TISBindHelper unavailableHelper = new TISBindHelper(mContext, binder -> {});
        unavailableHelper.runOnBindToTouchInteractionService(() -> {});
        unavailableHelper.onDestroy();

        verify(mContext, times(1))
                .bindService(any(Intent.class), any(ServiceConnection.class), eq(0));
    }

    @Test
    public void bindFailure_unregistersDispatcherBeforeRetry() {
        mHelper.onDestroy();
        when(mContext.bindService(
                        any(Intent.class), any(ServiceConnection.class), eq(0)))
                .thenReturn(false);

        TISBindHelper failedHelper = new TISBindHelper(mContext, binder -> {});
        failedHelper.onDestroy();

        verify(mContext, times(1)).unbindService(failedHelper);
    }
}

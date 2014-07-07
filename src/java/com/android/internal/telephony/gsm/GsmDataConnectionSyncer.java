/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;
import com.android.internal.telephony.dataconnection.DcTracker;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * {@hide}
 */
public final class GsmDataConnectionSyncer {
    protected final String TAG = "GsmDataConnectionSyncer";
    private boolean DBG  = true;

    private static Object mLock = new Object();
    private static GsmDataConnectionSyncer mInstance = null;

    private DcTracker[] mTrackers = null;
    private StateListener[] mListeners = null;

    /**
     * An interface for notifing between two Trackers the
     * disconnected event. Disconnected means totally disconnected.
     */
    public interface StateListener {
        public void onDisconnected();
    }

    //***** Constructor
    private GsmDataConnectionSyncer() {
        mTrackers = new DcTracker[2];
        mListeners = new StateListener[2];
    }

    public static GsmDataConnectionSyncer getInstance() {
        synchronized (mLock) {
            if (mInstance == null) {
                mInstance = new GsmDataConnectionSyncer();
            }
        }

        return mInstance;
    }

    public synchronized boolean registerTracker(DcTracker tracker,
            StateListener listener) {
        if(mTrackers[0] == tracker || mTrackers[1] == tracker) {
            Log.e(TAG, "Tracker " + tracker + " has already been registered. Reject this.");
            return false;
        } else if (mTrackers[0] == null) {
            mTrackers[0] = tracker;
            mListeners[0] = listener;
            return true;
        } else if (mTrackers[1] == null) {
            mTrackers[1] = tracker;
            mListeners[1] = listener;
            return true;
        } else {
            Log.e(TAG, "Tracker " + tracker + " has been fully registered. Reject this.");
            return false;
        }
    }

    /**
     * Get peer overall state
     */
    public boolean isPeerDisconnected(DcTracker tracker) {
        DcTracker peer = (mTrackers[0] == tracker) ? mTrackers[1] : mTrackers[0];
        if (peer != null) {
            return peer.isDisconnected();
        }

        return false;
    }

    public void notifyDisconnected(DcTracker tracker) {
        int index = (mTrackers[0] == tracker) ? 1 : 0;
        StateListener listener = mListeners[index];
        if (listener != null) {
            if (DBG) Log.d(TAG, "Notify peer: " + listener);
            listener.onDisconnected();
        } else {
            Log.e(TAG, "Null listener");
        }
    }
}

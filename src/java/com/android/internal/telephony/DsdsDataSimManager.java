/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony;

import android.content.pm.PackageManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import com.android.internal.telephony.gsm.OnlyOne3gSyncer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.android.internal.telephony.TelephonyConstants.SWITCH_SUCCESS;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_TIMEDOUT;

/**
 * {@hide}
 */
public class DsdsDataSimManager {

    private static final String LOG_TAG = "GSM";
    private static final boolean DEBUG = true;
    private static final String  DATA_SIM_SERVICE = "dsdsdatasim";
    private final Object mLock = new Object();
    private int     resultCode = -1;
    private boolean mSwitchDone = true;
    private int mRilState[] = {-1, -1};
    private int mPollRetries = 0;



    protected static final int EVENT_DSDS_SWTICH_DONE = 1;

    public DsdsDataSimManager() {
    }

    public boolean isDataDisconnected() {
        return OnlyOne3gSyncer.getInstance().isDataDisconnected();
    }

    public boolean isDataDisconnected(int slotId) {
        return OnlyOne3gSyncer.getInstance().isDataDisconnected(slotId);
    }

    public void setPrimarySim(int slotId, Message onComplete) {
        Message response = mHandler.obtainMessage(EVENT_DSDS_SWTICH_DONE, onComplete);
        PhoneFactory.setPrimarySim(slotId, onComplete);
    }

    protected void waitForResult(AtomicBoolean status) {
        while (!status.get()) {
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to setPrimarySim");
            }
        }
    }

    protected Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;
            switch (msg.what) {
                case EVENT_DSDS_SWTICH_DONE:
                    ar = (AsyncResult) msg.obj;
                    log("EVENT_DSDS_SWTICH_DONE");
                    Message waiter = (Message) ar.userObj;
                    AsyncResult.forMessage(waiter, ar.result, null);
                    waiter.sendToTarget();
                    break;
                default:
                    log("Unknown Event " + msg.what);
            }
        }
    };

    protected void log(String s) {
        Log.d(LOG_TAG, "[DsdsDataSimManager] " + s);
    }
}

/*
 * Copyright (C) 2013 Intel Corporation, All Rights Reserved
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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.ServiceState;
import android.util.Log;

import com.android.internal.telephony.dataconnection.ApnSetting;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.intel.internal.telephony.OemTelephony.OemTelephonyConstants;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * {@hide}
 */
public class GsmLteServiceStateTracker extends GsmServiceStateTracker {
    private static final String LOG_TAG = "GsmLte";

    protected RegistrantList mDataRegistrationStateRegistrants = new RegistrantList();

    public GsmLteServiceStateTracker(GSMPhone phone) {
        super(phone);
    }

    @Override
    public void handleMessage(Message msg) {

        if (!mPhone.mIsTheCurrentActivePhone) {
            loge("Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }

        switch (msg.what) {
            case EVENT_POLL_STATE_GPRS:
                super.handleMessage(msg);
                AsyncResult ar = (AsyncResult) msg.obj;
                mDataRegistrationStateRegistrants.notifyRegistrants(ar);
                break;
            // we do not need to handle any message here
            // the initial apn attach will be set by default
            // on onRecordsLoaded by DcTracker
            default:
                super.handleMessage(msg);
                break;
        }
    }

    @Override
    public void dispose() {
        super.dispose();
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[GsmLteSST] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[GsmLteSST] " + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("GsmLteServiceStateTracker extends:");
        super.dump(fd, pw, args);
    }

    public void registerForDataRegistrationStateUpdate(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDataRegistrationStateRegistrants.add(r);
    }

    public void unregisterForDataRegistrationStateUpdate(Handler h) {
        mDataRegistrationStateRegistrants.remove(h);
    }
}

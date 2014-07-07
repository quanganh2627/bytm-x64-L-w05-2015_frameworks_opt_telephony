/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.util.Log;
import android.telephony.ServiceState;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.OemHookConstants;
import com.android.internal.telephony.TelephonyConstants;

/**
 * {@hide}
 */
public class OemHookHandler extends Handler {
    static final String LOG_TAG = "OemHookHandler";
    private static final boolean DBG = false;


    private static final int EVENT_OEM_HOOK_RAW = 1;
    private static final int EVENT_SERVICE_STATE_CHANGED = 2;
    private static final int EVENT_RADIO_NOT_AVAILABLE   = 3;

    private CommandsInterface mCM;
    private GSMPhone mPhone;
    private boolean mToosState = false;

    // Constructor
    public OemHookHandler(GSMPhone phone) {
        mPhone = phone;
        mCM = phone.mCi;

        mCM.setOnUnsolOemHookRaw(this, EVENT_OEM_HOOK_RAW, null);
        if (TelephonyConstants.IS_DSDS) {
            //To clean up TOOS state if already in service
            mPhone.registerForServiceStateChanged(this, EVENT_SERVICE_STATE_CHANGED, null);
            mCM.registerForNotAvailable(this, EVENT_RADIO_NOT_AVAILABLE, null);
        }
    }

    public void dispose() {
        mCM.unSetOnUnsolOemHookRaw(this);
        if (TelephonyConstants.IS_DSDS) {
            mPhone.unregisterForServiceStateChanged(this);
            mCM.unregisterForNotAvailable(this);
        }
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        Message onComplete;

        switch (msg.what) {
            case EVENT_OEM_HOOK_RAW:
                ar = (AsyncResult)msg.obj;
                handleUnsolicitedOemHookRaw((byte[]) ar.result);
                break;

            case EVENT_SERVICE_STATE_CHANGED:
                onServiceStateChanged(msg);
                break;

            case EVENT_RADIO_NOT_AVAILABLE:
                Log.d(LOG_TAG, "EVENT_RADIO_NOT_AVAILABLE");
                onRadioNotAvailable();
                break;

            default:
                Log.e(LOG_TAG, "Unhandled message with number: " + msg.what);
                break;
        }
    }

    private boolean hasService(ServiceState ss) {
        if (ss == null) return false;
        int state = ss.getState();
        return (state != ServiceState.STATE_OUT_OF_SERVICE
                && state != ServiceState.STATE_POWER_OFF);
    }

    private void onServiceStateChanged(Message msg) {
        ServiceState state = (ServiceState) ((AsyncResult) msg.obj).result;
        if (hasService(state) && mToosState) {
            Log.i(LOG_TAG, "clear TOOS due to in-service");
            updateToosIndicator(false);
        }
    }

    private void onRadioNotAvailable() {
        reset();
    }
    private void reset() {
        updateToosIndicator(false);
    }

    public void updateToosIndicator(ServiceState ss) {
        if (hasService(ss) && mToosState) {
            Log.i(LOG_TAG, "clear TOOS due to in-service");
            updateToosIndicator(false);
        }
    }

    public void updateToosIndicator(boolean toos) {
        if (toos == mToosState) {
            return;
        }
        mToosState = toos;
        Intent intent =
            new Intent(TelephonyConstants.ACTION_MODEM_FAST_OOS_IND);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(TelephonyConstants.MODEM_PHONE_NAME_KEY, mPhone.getPhoneName());
        intent.putExtra(TelephonyConstants.EXTRA_TOOS_STATE, toos);
        mPhone.getContext().sendBroadcast(intent);
        SystemProperties.set(TelephonyConstants.PROPERTY_TEMPORARY_OOS
                + "." + mPhone.getPhoneName(), toos ? "1" : "0");
    }

    private void handleUnsolicitedOemHookRaw(byte[] rawData) {
        if (DBG) Log.i(LOG_TAG, "handleUnsolicitedOemHookRaw");
        if (rawData.length <= 0) return;

        ByteBuffer dataBuffer = ByteBuffer.wrap(rawData);
        final int MIN_OEM_HOOK_RAW_DATA_LENGTH = 4;

        if (dataBuffer == null || dataBuffer.limit() < MIN_OEM_HOOK_RAW_DATA_LENGTH) {
            Log.e(LOG_TAG, "handleUnsolicitedOemHookRaw length < MIN_OEM_HOOK_RAW_DATA_LENGTH");
            return;
        }

        final int MSG_ID_INDEX = 0;
        final int msgId = dataBuffer.getInt(MSG_ID_INDEX);

        switch(msgId) {
           case OemHookConstants.RIL_OEM_HOOK_RAW_UNSOL_FAST_OOS_IND:
                {
                    updateToosIndicator(true);
                }
            break;
            case OemHookConstants.RIL_OEM_HOOK_RAW_UNSOL_IN_SERVICE_IND:
                {
                    updateToosIndicator(false);
                }
            break;

            default:
                Log.i(LOG_TAG, "Unhandled oem hook raw event, msgId: " + msgId);
        }
    }
}

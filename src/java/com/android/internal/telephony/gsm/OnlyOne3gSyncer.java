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
import android.content.ContentResolver;
import android.os.Handler;
import android.os.Message;
import android.os.AsyncResult;
import android.os.SystemProperties;
import android.os.Registrant;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.BaseCommands;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.dataconnection.DcTrackerBase;

import static com.android.internal.telephony.RILConstants.NETWORK_MODE_GSM_ONLY;
import static com.android.internal.telephony.RILConstants.NETWORK_MODE_WCDMA_PREF;
import static com.android.internal.telephony.OemHookConstants.SWAP_PS_FLAG_NORMAL;
import static com.android.internal.telephony.TelephonyConstants.ACTION_RAT_SETTING;
import static com.android.internal.telephony.TelephonyConstants.DSDS_SLOT_1_ID;
import static com.android.internal.telephony.TelephonyConstants.DSDS_SLOT_2_ID;
import static com.android.internal.telephony.TelephonyConstants.ENABLED;
import static com.android.internal.telephony.TelephonyConstants.ACTION_DATA_SIM_SWITCH;
import static com.android.internal.telephony.TelephonyConstants.EXTRA_SWITCH_STAGE;
import static com.android.internal.telephony.TelephonyConstants.EXTRA_RESULT_CODE;
import static com.android.internal.telephony.TelephonyConstants.SIM_SWITCH_BEGIN;
import static com.android.internal.telephony.TelephonyConstants.SIM_SWITCH_END;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_SUCCESS;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_TIMEDOUT;
import static com.android.internal.telephony.TelephonyConstants.ACTION_RIL_SWITCHING;


/*
 *  This class is used to prevent two 3G setting, which is not allowed
 *  in this dual sim platform.
 */

public class OnlyOne3gSyncer extends Handler {
    private static final String     TAG = "OnlyOne3gSyncer";
    private static final boolean    DBG = true;
    private static OnlyOne3gSyncer    sMe = null;

    private static final int EVENT_RADIO_ON   = 1;
    private static final int EVENT_RADIO2_ON  = 2;
    private static final int EVENT_RADIO_OFF_OR_UNAVAILABLE   = 3;
    private static final int EVENT_RADIO2_OFF_OR_UNAVAILABLE  = 4;
    private static final int EVENT_GET_NETWORK_TYPE_DONE  = 5;
    private static final int EVENT_GET_NETWORK2_TYPE_DONE = 6;
    private static final int EVENT_SET_2G_FIRST_DONE      = 7;
    private static final int EVENT_SET_SECOND_TYPE_DONE   = 8;
    private static final int EVENT_PS_SWAP_DONE           = 9;
    private static final int EVENT_POLL_SWITCH_DONE      = 10;
    private static final int POLL_SWITCH_MILLIS = 5 * 1000;
    private static final int POLL_SWITCH_RETRY_MAX = 3;

    private static final int RADIO_PRIMARY_ON = 1;
    private static final int RADIO_SECONDARY_ON = 2;
    private static final int RADIO_BOTH_ON = 3;

    private GSMPhone mPhones[] = {null, null};
    private int mNetworkType;
    private int mNetwork2Type;
    private int mNetworkSetting;
    private int mNetwork2Setting;
    private int mRadios = 0;
    private boolean mSwitchDone = true;
    private boolean mWaitingForReconnected = false;
    private int mRilState[] = {-1, -1};
    private int mPollRetries = 0;
    private Message mUserResponse;
    private static int sId = 1;

    public static OnlyOne3gSyncer getInstance() {
        if (sMe == null) {
            sMe = new OnlyOne3gSyncer();
        }
        return sMe;
    }

    private OnlyOne3gSyncer() {
    }
    public void setRat(int nt, int nt2) {
        if (DBG) log("RATs settings: " + nt + ", " + nt2);
        mNetworkSetting = nt;
        mNetwork2Setting = nt2;
    }
    public void monitorRilSwitch(Phone phone, Phone phone2, Message onComplete) {
        mSwitchDone = false;
        mWaitingForReconnected = true;
        sId++;
        if (DBG) log("monitorRilSwitch for " + onComplete + ",sessionId:" + sId);
        resetRilState();
        mUserResponse = onComplete;
        unregisterHandler();
        registerRadios(phone, phone2);
    }

    public boolean isDataDisconnected() {
        boolean ret = getDataConnectionTracker(mPhones[0]).isDisconnected() &&
            getDataConnectionTracker(mPhones[1]).isDisconnected();
        if (!ret) {
            log("isDataDisconnected:" + getDataConnectionTracker(mPhones[0]).isDisconnected()
                    + "," + getDataConnectionTracker(mPhones[1]).isDisconnected());
        }
        return ret;
    }

    public boolean isDataDisconnected(int slotId) {
        return getDataConnectionTracker(mPhones[slotId]).isDisconnected();
    }

    private DcTrackerBase getDataConnectionTracker(Phone phone) {
        return ((PhoneBase)phone).mDcTracker;
    }

    void resetRilState() {
        mRadios = 0;
        mRilState[0] = mRilState[1] = -1;
    }
    private void unregisterHandler() {
        unregisterHandlerForPhone(0);
        unregisterHandlerForPhone(1);
    }
    private void unregisterHandlerForPhone(int phoneId) {
        if (mPhones[phoneId] != null) {
            mPhones[phoneId].mCi.unregisterForOn(this);
            ((BaseCommands)mPhones[phoneId].mCi).unregisterForOff(this);
            mPhones[phoneId].mCi.unregisterForNotAvailable(this);
        }
    }

    public void registerRadios(Phone phone, Phone phone2) {
        ((PhoneBase)phone).mCi.registerForOn(this, EVENT_RADIO_ON, null);
        ((BaseCommands)((PhoneBase)phone).mCi).registerForOff(this, EVENT_RADIO_OFF_OR_UNAVAILABLE, null);
        ((PhoneBase)phone).mCi.registerForNotAvailable(this, EVENT_RADIO_OFF_OR_UNAVAILABLE, null);

        ((PhoneBase)phone2).mCi.registerForOn(this, EVENT_RADIO2_ON, null);
        ((BaseCommands)(((PhoneBase)phone2).mCi)).registerForOff(this, EVENT_RADIO2_OFF_OR_UNAVAILABLE, null);
        ((PhoneBase)phone2).mCi.registerForNotAvailable(this, EVENT_RADIO2_OFF_OR_UNAVAILABLE, null);

        mPhones[0] = (GSMPhone)phone;
        mPhones[1] = (GSMPhone)phone2;
        mPollRetries = 0;
        removeMessages(EVENT_POLL_SWITCH_DONE);
        sendMessageDelayed(obtainMessage(EVENT_POLL_SWITCH_DONE, sId), POLL_SWITCH_MILLIS);

        if (DBG) log("RATs expected:" + mNetworkSetting + "," + mNetwork2Setting);
        if (DBG) log("expected radioPower" + getDesiredPowerState(0) + ","  + getDesiredPowerState(1));
    }

    private boolean getDesiredPowerState(int phoneId) {
        return mPhones[phoneId].getServiceStateTracker().getDesiredPowerState();
    }

    private boolean isRilAsExpected(int phoneId) {
        return (mRilState[phoneId] > 0 || (mRilState[phoneId] == 0 && !getDesiredPowerState(phoneId)));
    }

    private void pollRilSwitch() {
        if (checkRilSwitchDone()) {
            mPollRetries = 0;
        } else if (mPollRetries++ < POLL_SWITCH_RETRY_MAX) {
            sendMessageDelayed(obtainMessage(EVENT_POLL_SWITCH_DONE, sId), POLL_SWITCH_MILLIS);
        } else {
            onRilSwitchDone(SWITCH_FAILED_TIMEDOUT);
            mPollRetries = 0;
        }
    }
    boolean checkRilSwitchDone() {
        if (mSwitchDone) return true;
        if (DBG) log("checkRilSwitchDone:" + mRilState[0] + "," + mRilState[1]);
        if (isRilAsExpected(0) && isRilAsExpected(1)) {
            mSwitchDone = true;
            removeMessages(EVENT_POLL_SWITCH_DONE);
            onRilSwitchDone(SWITCH_SUCCESS);
        }
        return mSwitchDone;
    }
    private boolean checkRilSwitchDone(int phoneId) {
        updateRilState(phoneId);
        broadcastRilSwitching();
        return checkRilSwitchDone();
    }
    private void updateRilState(int phoneId) {
        switch (mPhones[phoneId].mCi.getRadioState()) {
            case RADIO_OFF:
                mRilState[phoneId] = 0;
                break;
            case RADIO_UNAVAILABLE:
                mRilState[phoneId] = -1;
                break;
            default:
                mRilState[phoneId] = 1;
                break;

        }
    }

    private void onRadioOn(int slot) {
        getNetworkTypes();
        checkRilSwitchDone(slot);
    }

    private void onRadioOffOrNotAvailable(int slot) {
        checkRilSwitchDone(slot);
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        Message onComplete;
        if (msg.arg1 > 0 && msg.arg1 != sId) {
            log("ignore old msg " + msg.what + " for sesssion:" + msg.arg1);
            return;
        }

        switch (msg.what) {
            case EVENT_RADIO_ON:
                mRadios |= RADIO_PRIMARY_ON;
                onRadioOn(0);
                break;

            case EVENT_RADIO_OFF_OR_UNAVAILABLE:
                onRadioOffOrNotAvailable(0);
                break;

            case EVENT_RADIO2_ON:
                mRadios |= RADIO_SECONDARY_ON;
                onRadioOn(1);
                break;

            case EVENT_RADIO2_OFF_OR_UNAVAILABLE:
                onRadioOffOrNotAvailable(1);
                break;

            case EVENT_GET_NETWORK_TYPE_DONE:
                handleGetNetworkTypeDone(msg);
                break;

            case EVENT_GET_NETWORK2_TYPE_DONE:
                handleGetNetwork2TypeDone(msg);
                break;

            case EVENT_SET_2G_FIRST_DONE:
                handleSet2gDone(msg);
                break;

            case EVENT_SET_SECOND_TYPE_DONE:
                broadcastRatSettingDone();
                break;

            case EVENT_PS_SWAP_DONE:
                handlePsSwapDone(msg);
                break;
            case EVENT_POLL_SWITCH_DONE:
                pollRilSwitch();
                break;
        }
    }

    private void getNetworkTypes() {
        if (mRadios == RADIO_BOTH_ON || (mRadios == RADIO_PRIMARY_ON && mPhones[1].isSimOff())) {
            mPhones[0].getPreferredNetworkType(obtainMessage(EVENT_GET_NETWORK_TYPE_DONE, sId));
        } else if (mRadios == RADIO_SECONDARY_ON && mPhones[0].isSimOff()) {
            mPhones[1].getPreferredNetworkType(obtainMessage(EVENT_GET_NETWORK2_TYPE_DONE, sId));
        }
    }

    private void handleGetNetworkTypeDone(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar.exception == null) {
            int type = ((int[])ar.result)[0];
            if (DBG) log("type=" + type);
            if (type != Phone.NT_MODE_GSM_ONLY) {
                // Allow only NT_MODE_GSM_ONLY or NT_MODE_WCDMA_PREF
                type = Phone.NT_MODE_WCDMA_PREF;
            }
            if (DBG) log("RATs got from phone GSM:" + type);
            mNetworkType = type;
            if (mRadios == RADIO_BOTH_ON) {
                mPhones[1].getPreferredNetworkType(obtainMessage(EVENT_GET_NETWORK2_TYPE_DONE, sId));
                return;
            } else if (mRadios == RADIO_PRIMARY_ON && mPhones[1].isSimOff()) {
                if (mNetworkSetting != mNetworkType) {
                    mPhones[0].requestProtocolStackSwap(
                              obtainMessage(EVENT_PS_SWAP_DONE, sId), SWAP_PS_FLAG_NORMAL) ;
                    return;
                }
            }
        } else {
            if (DBG) log("Get preferred network exception.");
        }
        broadcastRatSettingDone();
    }

    private void handleGetNetwork2TypeDone(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar.exception == null) {
            int type = ((int[])ar.result)[0];
            if (DBG) log("type=" + type);
            if (type != Phone.NT_MODE_GSM_ONLY) {
                // Allow only NT_MODE_GSM_ONLY or NT_MODE_WCDMA_PREF
                type = Phone.NT_MODE_WCDMA_PREF;
            }
            if (DBG) log("RATs got from phone GSM2:" + type);
            mNetwork2Type = type;
            if (mRadios == RADIO_BOTH_ON) {
                syncNetworkTypes();
                return;
            } else if (mRadios == RADIO_SECONDARY_ON && mPhones[0].isSimOff()) {
                if (mNetwork2Setting != mNetwork2Type) {
                    mPhones[1].requestProtocolStackSwap(
                              obtainMessage(EVENT_PS_SWAP_DONE, sId), SWAP_PS_FLAG_NORMAL) ;
                    return;
                }
            }
        } else {
            if (DBG) log("Get preferred network2 exception.");
        }
        broadcastRatSettingDone();
    }

    private void syncNetworkTypes() {
        if (mNetworkType == mNetworkSetting &&
                    mNetwork2Type == mNetwork2Setting) {
            if (DBG) log("RATs perfect match.");
            broadcastRatSettingDone();
            return;
        }

        if (DBG) log("Sync network types.");
        if (mNetworkSetting == NETWORK_MODE_GSM_ONLY) {
            Message smsg = obtainMessage(EVENT_SET_2G_FIRST_DONE, sId);
            mPhones[0].setPreferredNetworkType(mNetworkSetting, smsg);
        } else if (mNetwork2Setting == NETWORK_MODE_GSM_ONLY) {
            Message smsg = obtainMessage(EVENT_SET_2G_FIRST_DONE, sId);
            mPhones[1].setPreferredNetworkType(mNetwork2Setting, smsg);
        } else {
            broadcastRatSettingDone();
        }
    }

    private void handleSet2gDone(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (DBG) log("Set 2g Done.");
        if (ar.exception == null) {
            Message smsg = obtainMessage(EVENT_SET_SECOND_TYPE_DONE, sId);
            if (mNetworkSetting == NETWORK_MODE_GSM_ONLY) {
                mPhones[1].setPreferredNetworkType(mNetwork2Setting, smsg);
                return;
            } else if (mNetwork2Setting == NETWORK_MODE_GSM_ONLY) {
                mPhones[0].setPreferredNetworkType(mNetworkSetting, smsg);
                return;
            }
        } else {
            if (DBG) log("Set 2g failed.");
        }
        broadcastRatSettingDone();
    }

    private void handlePsSwapDone(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar.exception == null) {
            if (DBG) log("Protocol swap done.");
        } else {
            if (DBG) log("Protocol swap failed.");
        }
        broadcastRatSettingDone();
    }

    void broadcastRatSettingDone() {
         Intent intent = new Intent(ACTION_RAT_SETTING);
         mPhones[0].getContext().sendBroadcast(intent);
    }

    private void broadcastRilSwitching() {

        if (!mWaitingForReconnected) return;
        Intent intent = new Intent(ACTION_RIL_SWITCHING);
        mPhones[0].getContext().sendBroadcast(intent);

        mWaitingForReconnected = false;
    }
    private void onRilSwitchDone(int result) {
        if (DBG) log("onRilSwitchDone:" + result);
        broadcastRilSwitching();
        int[] results = new int[1];
        results[0] = result;
        if (mUserResponse != null) {
            AsyncResult.forMessage(mUserResponse, results, null);
            mUserResponse.sendToTarget();
            mUserResponse = null;
        }
        if (mRegistrant != null) {
            results[0] = result;
            mRegistrant.notifyRegistrant(new AsyncResult(null, result, null));
        }

    }

    protected Registrant mRegistrant;
    public void setOnSwitchDone(Handler h, int what, Object obj) {
        mRegistrant = new Registrant (h, what, obj);
    }

    public void unSetSwitchDone(Handler h) {
        mRegistrant.clear();
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }
}

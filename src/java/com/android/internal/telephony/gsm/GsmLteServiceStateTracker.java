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
import android.os.Message;
import android.util.Log;

import com.android.internal.telephony.ApnSetting;
import com.android.internal.telephony.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.IccRecords;
import com.android.internal.telephony.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.intel.internal.telephony.OemTelephony.OemTelephonyConstants;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * {@hide}
 */
public class GsmLteServiceStateTracker extends GsmServiceStateTracker {
    private static final String LOG_TAG = "GsmLte";

    // Should not overlap with the events defined in the ServiceStateTracker Base class.
    private static final int EVENT_IMSI_READY = 100;
    private static final int EVENT_SET_DEFAULT_APN_DONE = 101;

    private boolean mIsDefaultAPNSet;

    public GsmLteServiceStateTracker(GSMPhone phone) {
        super(phone);
    }

    private void setDefaultApn(ApnSetting apn) {
        String oemParams[] = new String[3];
        oemParams[0] = Integer.toString(
                OemTelephonyConstants.RIL_OEM_HOOK_STRING_SET_DEFAULT_APN);
        if (apn != null) {
            oemParams[1] = apn.apn;
            oemParams[2] = apn.protocol;
        } else {
            oemParams[1] = "";
            oemParams[2] = "IPV4V6";
        }

        if (DBG) log("setting default apn (" + oemParams[1] + ", " + oemParams[2] + ")");
        cm.invokeOemRilRequestStrings(oemParams, obtainMessage(EVENT_SET_DEFAULT_APN_DONE));
    }

    @Override
    protected void onUpdateIccAvailability() {
        if (mUiccController == null ) {
            return;
        }

        UiccCardApplication newUiccApplication =
                mUiccController.getUiccCardApplication(UiccController.APP_FAM_3GPP);

        if (mUiccApplcation != newUiccApplication) {
            mIsDefaultAPNSet = false;
            if (mUiccApplcation != null) {
                log("Removing stale icc objects.");
                mUiccApplcation.unregisterForReady(this);
                if (mIccRecords != null) {
                    mIccRecords.unregisterForImsiReady(this);
                    mIccRecords.unregisterForRecordsLoaded(this);
                }
                mIccRecords = null;
                mUiccApplcation = null;
            }
            if (newUiccApplication != null) {
                log("New card found");
                mUiccApplcation = newUiccApplication;
                mIccRecords = mUiccApplcation.getIccRecords();
                mUiccApplcation.registerForReady(this, EVENT_SIM_READY, null);
                if (mIccRecords != null) {
                    mIccRecords.registerForImsiReady(this, EVENT_IMSI_READY, null);
                    mIccRecords.registerForRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
                }
            }
        }
    }

    @Override
    public void handleMessage(Message msg) {

        if (!phone.mIsTheCurrentActivePhone) {
            loge("Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }

        switch (msg.what) {
            case EVENT_RADIO_ON:
                if (mUiccApplcation == null
                        || mUiccApplcation.getState() != AppState.APPSTATE_READY) {
                    break;
                }
                // Fall through to restore saved network selection if sim is ready
            case EVENT_SIM_READY:
                // Network selection is deferred until we can send the default APN to modem.
                if (cm.getRadioState().isOn()) {
                    pollState();
                    // Signal strength polling stops when radio is off
                    queueNextSignalStrengthPoll();

                    boolean skipRestoringSelection = phone.getContext().getResources().getBoolean(
                            com.android.internal.R.bool.skip_restoring_network_selection);

                    if (mIsDefaultAPNSet && !skipRestoringSelection) {
                        // restore the previous network selection.
                        phone.restoreSavedNetworkSelection(null);
                    }
                }
                break;

            case EVENT_IMSI_READY:
                if (DBG) log("EVENT_IMSI_READY");

                if (!mIsDefaultAPNSet) {
                    String operator = mIccRecords.getOperatorNumeric();
                    ApnSetting apn = ApnUtils.getDefaultApnSettings(phone, operator);
                    setDefaultApn(apn);
                    mIsDefaultAPNSet = true;
                }
                break;

            case EVENT_SET_DEFAULT_APN_DONE:
                if (DBG) log("EVENT_SET_DEFAULT_APN_DONE");

                AsyncResult ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    loge("Setting of Default APN failed");
                }

                // SIM can be accessible in radio off
                if (cm.getRadioState().isOn()) {
                    boolean skipRestoringSelection = phone.getContext().getResources().getBoolean(
                            com.android.internal.R.bool.skip_restoring_network_selection);

                    if (!skipRestoringSelection) {
                        // restore the previous network selection.
                        phone.restoreSavedNetworkSelection(null);
                    }
                }
                break;

            case EVENT_RADIO_STATE_CHANGED:
                if (!cm.getRadioState().isOn()) {
                    mIsDefaultAPNSet = false;
                }

                // Fall through so that base class can take further actions.
            default:
                super.handleMessage(msg);
                break;
        }
    }

    @Override
    public void dispose() {
        if (mIccRecords != null) mIccRecords.unregisterForImsiReady(this);
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
        pw.println(" mIsDefaultAPNSet=" + mIsDefaultAPNSet);
    }
}

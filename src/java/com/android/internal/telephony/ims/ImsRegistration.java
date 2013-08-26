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

package com.android.internal.telephony.ims;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.comneon.mas.gui.CRegister;
import com.comneon.mas.gui.ILogger;
import com.comneon.mas.gui.IRegister;
import com.comneon.mas.gui.IRegisterCreateAsyncCB;
import com.comneon.mas.gui.MasFactory;
import com.comneon.mas.gui.RCSLogger;

public final class ImsRegistration implements IRegisterCreateAsyncCB {

    /** Framework States */
    public static final int IMS_DISABLED = 0x1001;
    public static final int IMS_ONLINE = 0x1004;
    public static final int IMS_OFFLINE = 0x1002;

    public static final int VOIP_ONLINE = 0x1101;
    public static final int VT_ONLINE = 0x1102;
    public static final int VOIP_OFFLINE = 0x1104;
    public static final int VT_OFFLINE = 0x1108;

    private final String LOG_TAG = "ImsRegistration";
    private MasFactory mMasFactory = null;
    private CRegister mCReg = null;
    private Handler mHdlr = null;
    private Context mCtx = null;
    private boolean mRegistered = false;

    public ImsRegistration(Context c, Handler h) {
        mMasFactory = new MasFactory();
        mCReg = new CRegister();

        RCSLogger.getInstance().RegisteMASLogginCB(new ILogger() {
            @Override
            public void Log(int type, String method, String info) {
                Log.d("MAS_API", method + " " + info);
            }
        });
        mHdlr = h;
        mCtx = c;
    }

    public void finalize() {
        mRegistered = false;
        if (mCReg != null) {
            try {
                mCReg.RCS_Destroy(this, 0, iAppID, true);
            } catch (Throwable e) {
                Log.e(LOG_TAG, e.toString());
            }
        }

        if (mMasFactory != null) {
            try {
                mMasFactory.mas_gui_cleanup();
                mMasFactory.mas_gui_destroy();
            } catch (Throwable e) {
                Log.e(LOG_TAG, e.toString());
            }
        }

        try {
            super.finalize();
        } catch (Throwable e) {
            Log.e(LOG_TAG, e.toString());
        }
    }

    public void startImsService() {
        // Start IMS service by sending STARTIMS intent
        mRegistered = false;

        Intent stopIntent = new Intent("com.comneon.action.STARTIMS");
        mCtx.sendBroadcast(stopIntent);
        Log.i(LOG_TAG, "Starting IMS");

        new AsyncDispatcher().execute(this);
    }

    public void stopImsService() {
        // Stop IMS service by sending STOPIMS intent
        mRegistered = false;

        Intent stopIntent = new Intent("com.comneon.action.STOPIMS");
        mCtx.sendBroadcast(stopIntent);
        Log.i(LOG_TAG, "Stopping IMS");
    }

    public boolean isRegistered() {
        return mRegistered;
    }

    private void updateStackState(boolean online) {
        if ((mRegistered == false) && (online == true)) {
            // Create IMS client Instance. Application is expected to subscribe
            // to state
            // change events to be notified when IMS client state changes.
            mRegistered = true;
            try {
                Log.d(LOG_TAG, "Creating IMS client instance");
                mCReg.RCS_CreateAsync(this, true, true, null, null, null, true);

            } catch (Exception e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
        else if ((mRegistered == true) && (online == false)) {
            mRegistered = false;
            try {
                // mCReg.RCS_Destroy(this, 0, iAppID, true);
                mMasFactory.mas_gui_cleanup();
            } catch (Exception e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
    }

    private void updateServiceState(int serviceType, int eState) {
        int j = 1, i = 0;
        int iServiceState = 0;
        Message serviceMsg = null;

        for (i = 0; i < 15; i++) {
            if ((serviceType & j) == j) {
                iServiceState = ((iServiceState & (~j)) | (eState & j));

            }
            j = j << 1;
        }

        // Voip
        if ((iServiceState & IRegister.SERVICE_TYPE_IMS_VOIP) == IRegister.SERVICE_TYPE_IMS_VOIP) {
            Log.i(LOG_TAG, "VoIP service in ON-LINE");

            if (mHdlr != null) {
                serviceMsg = mHdlr.obtainMessage(VOIP_ONLINE, 0, 0);
                mHdlr.sendMessage(serviceMsg);
            }
        }

        // Video Telephony
        if ((iServiceState & IRegister.SERVICE_TYPE_IMS_VT) == IRegister.SERVICE_TYPE_IMS_VT) {
            Log.i(LOG_TAG, "VideoTelephony service in ON-LINE");

            if (mHdlr != null) {
                serviceMsg = mHdlr.obtainMessage(VT_ONLINE, 0, 0);
                mHdlr.sendMessage(serviceMsg);
            }
        }

    }

    /*
     * Implementing IRegisterCreateAsyncCB (non-Javadoc)
     * @see
     * com.comneon.mas.gui.IRegisterCreateAsyncCB#T_Ims_RegisterCallbackCB(int,
     * int)
     */
    @Override
    public void T_Ims_RegisterCallbackCB(int iAppID, int eErrorCode) {
        Log.d(LOG_TAG, "T_Ims_RegisterCallbackCB " + iAppID + " " + eErrorCode);
    }

    @Override
    public void T_Ims_StateChangedCallbackCB(int eState, int eReason) {
        Log.d(LOG_TAG, "T_Ims_StateChangedCallbackCB " + eState + "  " + eReason);
    }

    @Override
    public void T_Ims_ServiceStateChangedCallbackCB(int serviceType, int eState, int eReason) {
        Log.d(LOG_TAG, "T_Ims_ServiceStateChangedCallbackCB " + serviceType + "  " + eState + " "
                + eReason);

        updateServiceState(serviceType, eState);

        if (mHdlr != null) {
            Message message = mHdlr.obtainMessage(
                    IMS_ONLINE,
                    serviceType,
                    eState);

            mHdlr.sendMessage(message);
        }
    }

    @Override
    public void T_Ims_OnlinePUIDsCallbackCB(int lineId, int puidCount, String[] ppPuid) {
        Log.d(LOG_TAG, "T_Ims_OnlinePUIDsCallbackCB");
    }

    @Override
    public void T_Ims_OnlineServicesForPUIDCallbackCB(String pPuid, int serviceIds) {
        Log.d(LOG_TAG, "T_Ims_OnlineServicesForPUIDCallbackCB");
    }

    @Override
    public void T_Ims_StandardCallbackCB(int eErrorCode) {
        Log.d(LOG_TAG, "T_Ims_StandardCallbackCB " + eErrorCode);
    }

    @Override
    public void Ims_UniversalErrorCallbackCB(int eAPI, int eErrorCode, String hReason) {
        Log.d(LOG_TAG, "Ims_UniversalErrorCallbackCB " + eErrorCode);
    }

    @Override
    public void T_Ims_GetDomainName_CallbackCB(int userData, String phDomainName) {
        Log.d(LOG_TAG, "T_Ims_GetDomainName_CallbackCB " + userData + "  " + phDomainName);
    }

    @Override
    public void FrameWorkIsDisonnectedCB(int iState) {
        Log.d(LOG_TAG, "FrameWorkIsDisonnectedCB " + iState);

        switch (iState)
        {
            case IRegister.ESTATE_IMS_OFFLINE:
                Log.d(LOG_TAG, "FrameWorkIsDisonnectedCB : IMS_OFFLINE");
                updateStackState(false);
                iState = IMS_OFFLINE;
                break;
            case IRegister.ESTATE_IMS_ONLINE:
                Log.d(LOG_TAG, "FrameWorkIsDisonnectedCB : IMS_ONLINE");
                updateStackState(true);
                iState = IMS_ONLINE;
                break;
            case IRegister.ESTATE_IMS_DISABLED:
                Log.d(LOG_TAG, "FrameWorkIsDisonnectedCB : IMS_DISABLED");
                iState = IMS_DISABLED;
                break;
            default:
                break;
        }

        if (mHdlr != null) {
            Message message = mHdlr.obtainMessage(iState, 0, 0);
            mHdlr.sendMessage(message);
        }
    }

    /*
     * Class to spawn A ASync task for the so that the callback and forward
     * calls run in a different thread make the UI more responsive and efficient
     */
    public class AsyncDispatcher extends AsyncTask<Object, Void, Void> {

        protected Void doInBackground(Object... arg)
        {
            try {
                mMasFactory.mas_gui_init((IRegisterCreateAsyncCB) arg[0]);
                Log.d(LOG_TAG, "The mas gui_init method called");
            } catch (Exception e) {
                Log.e(LOG_TAG, e.toString());
            }

            return null;
        }

        protected void onProgressUpdate(Integer... progress) {

        }

        protected void onPostExecute(Long result) {

        }
    }
}

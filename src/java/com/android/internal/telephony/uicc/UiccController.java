/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

package com.android.internal.telephony.uicc;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.Rlog;
import android.provider.Settings;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.TelephonyConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneProxy;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * This class is responsible for keeping all knowledge about
 * Universal Integrated Circuit Card (UICC), also know as SIM's,
 * in the system. It is also used as API to get appropriate
 * applications to pass them to phone and service trackers.
 *
 * UiccController is created with the call to make() function.
 * UiccController is a singleton and make() must only be called once
 * and throws an exception if called multiple times.
 *
 * Once created UiccController registers with RIL for "on" and "unsol_sim_status_changed"
 * notifications. When such notification arrives UiccController will call
 * getIccCardStatus (GET_SIM_STATUS). Based on the response of GET_SIM_STATUS
 * request appropriate tree of uicc objects will be created.
 *
 * Following is class diagram for uicc classes:
 *
 *                       UiccController
 *                            #
 *                            |
 *                        UiccCard
 *                          #   #
 *                          |   ------------------
 *                    UiccCardApplication    CatService
 *                      #            #
 *                      |            |
 *                 IccRecords    IccFileHandler
 *                 ^ ^ ^           ^ ^ ^ ^ ^
 *    SIMRecords---- | |           | | | | ---SIMFileHandler
 *    RuimRecords----- |           | | | ----RuimFileHandler
 *    IsimUiccRecords---           | | -----UsimFileHandler
 *                                 | ------CsimFileHandler
 *                                 ----IsimFileHandler
 *
 * Legend: # stands for Composition
 *         ^ stands for Generalization
 *
 * See also {@link com.android.internal.telephony.IccCard}
 * and {@link com.android.internal.telephony.uicc.IccCardProxy}
 */
public class UiccController extends Handler {
    private static final boolean DBG = true;
    private static final String LOG_TAG = "UiccController";

    public static final int APP_FAM_3GPP =  1;
    public static final int APP_FAM_3GPP2 = 2;
    public static final int APP_FAM_IMS   = 3;

    private static final int EVENT_ICC_STATUS_CHANGED = 1;
    private static final int EVENT_GET_ICC_STATUS_DONE = 2;
    private static final int EVENT_RADIO_OFF_NOT_AVAILABLE = 3;
    private static final int EVENT_RADIO_NOT_AVAILABLE = 4;
    private static final int EVENT_RADIO_ON = 5;
    private static final int EVENT_ICC_STATUS_REPOLL = 6;
    private static final Object mLock = new Object();
    private static UiccController mInstance;
    private static UiccController mInstance2;
    private Context mContext;
    private CommandsInterface mCi;
    private UiccCard mUiccCard;

    private RegistrantList mIccChangedRegistrants = new RegistrantList();

    public static UiccController make(Context c, CommandsInterface ci) {
        synchronized (mLock) {
            if (mInstance != null) {
                throw new RuntimeException("UiccController.make() should only be called once");
            }
            mInstance = new UiccController(c, ci);
            return mInstance;
        }
    }

    public static UiccController make2(Context c, CommandsInterface ci) {
        synchronized (mLock) {
            if (mInstance2 != null) {
                throw new RuntimeException("UiccController.make2() should only be called once");
            }
            mInstance2 = new UiccController(c, ci);
            return mInstance2;
        }
    }

    private boolean isPrimary() {
        return (this != mInstance2);
    }

    public static UiccController getInstance() {
        synchronized (mLock) {
            if (mInstance == null) {
                throw new RuntimeException(
                        "UiccController.getInstance can't be called before make()");
            }
            return mInstance;
        }
    }
    public static UiccController getInstance2() {
        synchronized (mLock) {
            if (mInstance2 == null) {
                throw new RuntimeException(
                        "UiccController.getInstance2 can't be called before make()");
            }
            return mInstance2;
        }
    }

    public UiccCard getUiccCard() {
        synchronized (mLock) {
            return mUiccCard;
        }
    }

    // Easy to use API
    public UiccCardApplication getUiccCardApplication(int family) {
        synchronized (mLock) {
            if (mUiccCard != null) {
                return mUiccCard.getApplication(family);
            }
            return null;
        }
    }

    // Easy to use API
    public IccRecords getIccRecords(int family) {
        synchronized (mLock) {
            if (mUiccCard != null) {
                UiccCardApplication app = mUiccCard.getApplication(family);
                if (app != null) {
                    return app.getIccRecords();
                }
            }
            return null;
        }
    }

    // Easy to use API
    public IccFileHandler getIccFileHandler(int family) {
        synchronized (mLock) {
            if (mUiccCard != null) {
                UiccCardApplication app = mUiccCard.getApplication(family);
                if (app != null) {
                    return app.getIccFileHandler();
                }
            }
            return null;
        }
    }

    //Notifies when card status changes
    public void registerForIccChanged(Handler h, int what, Object obj) {
        synchronized (mLock) {
            Registrant r = new Registrant (h, what, obj);
            mIccChangedRegistrants.add(r);
            //Notify registrant right after registering, so that it will get the latest ICC status,
            //otherwise which may not happen until there is an actual change in ICC status.
            r.notifyRegistrant();
        }
    }

    public void unregisterForIccChanged(Handler h) {
        synchronized (mLock) {
            mIccChangedRegistrants.remove(h);
        }
    }
    private boolean mIccKnown = false;
    @Override
    public void handleMessage (Message msg) {
        synchronized (mLock) {
            switch (msg.what) {
                case EVENT_RADIO_ON:
                    //Delay to query SIM status in order to avoid the "SIM ABSENT"
                    //returned by GET_SIM immediately after SIM OFF->ON transition
                    if (DBG) log("GET_SIM_STATUS after a short delay");
                    sendMessageDelayed(obtainMessage(EVENT_ICC_STATUS_REPOLL), 1000);
                    break;
                case EVENT_ICC_STATUS_REPOLL:
                    if (DBG) log("Received EVENT_ICC_STATUS_REPOLL");
                    setSwitchingFinished(true);
                    mCi.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
                    break;
                case EVENT_ICC_STATUS_CHANGED:
                    if (DBG) log("Received EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus");
                    mCi.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
                    break;
                case EVENT_GET_ICC_STATUS_DONE:
                    if (DBG) log("Received EVENT_GET_ICC_STATUS_DONE");
                    AsyncResult ar = (AsyncResult)msg.obj;
                    onGetIccCardStatusDone(ar);
                    break;
                case EVENT_RADIO_OFF_NOT_AVAILABLE:
                    log("EVENT_RADIO_OFF_NOT_AVAILABLE");
                    if (TelephonyConstants.IS_DSDS) {
                        if (!mIccKnown && isAirplaneModeOn()) {
                            mCi.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
                            log("to retrieve SIM status in airplane mode for DSDS");
                            break;
                        }
                        if (mCi.getRadioState().isAvailable() && mUiccCard != null) {
                            log("Do not reset SIM status when radio is off");
                            break;
                        }
                    }
                    // Fall through
                case EVENT_RADIO_NOT_AVAILABLE:
                    log("reset SIM when NOT_AVAILABLE");
                    setSwitchingFinished(false);
                    IccCardStatus cardStatus = new IccCardStatus();
                    // CardState.CARDSTATE_ERROR
                    cardStatus.setCardState(2);
                    // PinState.PINSTATE_UNKNOWN
                    cardStatus.setUniversalPinState(0);
                    cardStatus.mGsmUmtsSubscriptionAppIndex = -1;
                    cardStatus.mCdmaSubscriptionAppIndex = -1;
                    cardStatus.mImsSubscriptionAppIndex = -1;
                    cardStatus.mApplications = new IccCardApplicationStatus[1];
                    IccCardApplicationStatus app = new IccCardApplicationStatus();
                    app.app_type = IccCardApplicationStatus.AppType.APPTYPE_UNKNOWN;
                    app.app_state = IccCardApplicationStatus.AppState.APPSTATE_UNKNOWN;
                    app.pin1 = IccCardStatus.PinState.PINSTATE_UNKNOWN;
                    app.pin2 = IccCardStatus.PinState.PINSTATE_UNKNOWN;
                    app.perso_substate =
                        IccCardApplicationStatus.PersoSubState.PERSOSUBSTATE_UNKNOWN;

                    cardStatus.mApplications[0] = app;
                    if (mUiccCard == null) {
                        // Create new card
                        mUiccCard = new UiccCard(mContext, mCi, cardStatus, isPrimary());
                    } else {
                        // Update already existing card
                        mUiccCard.update(mContext, mCi, cardStatus);
                    }

                    if (DBG) log("Notifying IccChangedRegistrants");
                    mIccChangedRegistrants.notifyRegistrants();
                    break;
                default:
                    Rlog.e(LOG_TAG, " Unknown Event " + msg.what);
            }
        }
    }

    private UiccController(Context c, CommandsInterface ci) {
        if (DBG) log("Creating UiccController");
        mContext = c;
        mCi = ci;
        mCi.registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, null);
        // TODO remove this once modem correctly notifies the unsols
        if (!TelephonyConstants.IS_DSDS ) {
            mCi.registerForOn(this, EVENT_ICC_STATUS_CHANGED, null);
        } else {
            mCi.registerForOn(this, EVENT_RADIO_ON, null);
        }
        mCi.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_NOT_AVAILABLE, null);
        mCi.registerForNotAvailable(this, EVENT_RADIO_NOT_AVAILABLE, null);
    }

    private synchronized void onGetIccCardStatusDone(AsyncResult ar) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG,"Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }
        mIccKnown = true;
        IccCardStatus status = (IccCardStatus)ar.result;

        if (mUiccCard == null) {
            //Create new card
            mUiccCard = new UiccCard(mContext, mCi, status, isPrimary());
        } else {
            //Update already existing card
            mUiccCard.update(mContext, mCi , status);
        }
        tryClearUserPin();
        if (DBG) log("Notifying IccChangedRegistrants");
        mIccChangedRegistrants.notifyRegistrants();
    }
    String getPhoneTag() {
        return isPrimary() ? "[GSM]" : "[GSM2]";
    }
    private int mIccSwitchingState = -1; //in on/off switching
    private void setSwitchingFinished(boolean success) {
        if (success && mIccSwitchingState == 0) {
            mIccSwitchingState = 1;
        } else {
            mIccSwitchingState = -1;
        }
    }

    private boolean isSwitchingFinished() {
        return mIccSwitchingState > 0;
    }

    public void setSwitching(boolean switching) {
        mIccSwitchingState = switching ? 0 : -1;
    }

    private void tryClearUserPin() {
        if (!TelephonyConstants.IS_DSDS) {
            return;
        }
        if (isSwitchingFinished() && !mUiccCard.isPinLocked()) {
            log("tryClearUserPin,PinState:" + mUiccCard.getUniversalPinState());
            getPhoneBase(isPrimary()).clearUserPin();
            setSwitching(false);
        }
    }

    private PhoneBase getPhoneBase(boolean isPrimary) {
        Phone phone = PhoneFactory.getDefaultPhoneById(isPrimary ? 0 : 1);
        return (PhoneBase)((PhoneProxy)phone).getActivePhone();
    }

    private boolean isAirplaneModeOn() {
        return (Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) > 0);
    }
    private void log(String string) {
        Rlog.d(LOG_TAG, getPhoneTag() + string);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("UiccController: " + this);
        pw.println(" mContext=" + mContext);
        pw.println(" mInstance=" + mInstance);
        pw.println(" mCi=" + mCi);
        pw.println(" mUiccCard=" + mUiccCard);
        pw.println(" mIccChangedRegistrants: size=" + mIccChangedRegistrants.size());
        for (int i = 0; i < mIccChangedRegistrants.size(); i++) {
            pw.println("  mIccChangedRegistrants[" + i + "]="
                    + ((Registrant)mIccChangedRegistrants.get(i)).getHandler());
        }
        pw.println();
        pw.flush();
        if (mUiccCard != null) {
            mUiccCard.dump(fd, pw, args);
        }
    }
}

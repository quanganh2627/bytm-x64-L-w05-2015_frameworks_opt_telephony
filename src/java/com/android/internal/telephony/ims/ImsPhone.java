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
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.telephony.CellLocation;
import android.telephony.ServiceState;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.IccPhoneBookInterfaceManager;
import com.android.internal.telephony.IccSmsInterfaceManager;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.OperatorInfo;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneSubInfo;
import com.android.internal.telephony.UUSInfo;

import java.util.List;

public class ImsPhone extends PhoneBase {
    private static final String LOG_TAG = "ImsPhone";
    private static final String PROPERTY_IMS_MSISDN = "persist.radio.ims.msisdn";
    private static final boolean DBG = true;

    private ImsCall mRingingCall = null;
    private ImsCall mForegroundCall = null;
    private ImsCall mBackgroundCall = null;
    private ImsCallTracker mImsCT = null;
    private ImsCommandsInterface mImsCM = null;
    private ServiceState mImsSS = null;
    private Phone mParentPhone = null;
    private PhoneSubInfo mSubInfo = null;

    ImsPhone(Context context, CommandsInterface ci, PhoneNotifier notifier,
            Phone parentPhone) {
        this(context, ci, notifier, parentPhone, false);
    }

    public ImsPhone(Context context, CommandsInterface ci, PhoneNotifier notifier,
            Phone parentPhone, boolean unitTestMode) {
        super("IMS", notifier, context, ci, unitTestMode);

        mImsCM = (ImsCommandsInterface) mCi;

        setParentPhone(parentPhone);

        mImsSS = new ServiceState();
        mImsSS.setState(ServiceState.STATE_OUT_OF_SERVICE);

        mImsCT = new ImsCallTracker(this);

        mRingingCall = new ImsCall(mImsCT);
        mForegroundCall = new ImsCall(mImsCT);
        mBackgroundCall = new ImsCall(mImsCT);

        mImsCM.setPhone(this);
        mImsCM.registerForServiceState(mImsCT);

        mSubInfo = new PhoneSubInfo(this);
    }

    public void dispose() {
        if (mImsCM != null) {
            mImsCM.initiateImsRegistration(false);
            mImsCM.finalize();
            mImsCM = null;
        }
        mSubInfo.dispose();
    }

    public void setParentPhone(Phone phone) {

        if (phone == null) {
            throw new IllegalArgumentException("phone");
        }
        mParentPhone = phone;
        Log.d(LOG_TAG, "Setting parent phone = " + phone.getPhoneName());

        Log.d(LOG_TAG, "Parent Phone IMEI " + mParentPhone.getImei() +
                " IMSI " + mParentPhone.getSubscriberId() +
                " MSISDN " + mParentPhone.getMsisdn());

        mImsCM.initiateImsRegistration(true);
    }

    public Phone getParentPhone() {
        return mParentPhone;
    }

    public void handleMessage(Message msg) {
        Log.d(LOG_TAG, "what:  " + msg.what);
        super.handleMessage(msg);
    }

    public int getPhoneType() {
        return PhoneConstants.PHONE_TYPE_IMS;
    }

    public String getPhoneName() {
        return "IMS";
    }

    public CallTracker getCallTracker() {
        return mImsCT;
    }

    @Override
    public DataActivityState getDataActivityState() {
        return null;
    }

    @Override
    public void acceptCall() throws CallStateException {
        mImsCT.acceptCall();
    }

    @Override
    public void rejectCall() throws CallStateException {
        mImsCT.rejectCall();
    }

    @Override
    public void switchHoldingAndActive() {
    }

    @Override
    public boolean canConference() {
        return false;
    }

    @Override
    public void conference() {
    }

    @Override
    public boolean canTransfer() {
        return false;
    }

    @Override
    public void explicitCallTransfer() {
    }

    @Override
    public void clearDisconnected() {
    }

    @Override
    public Call getForegroundCall() {
        return mImsCT.foregroundCall;
    }

    @Override
    public Call getBackgroundCall() {
        return mImsCT.backgroundCall;
    }

    @Override
    public Call getRingingCall() {
        return mImsCT.ringingCall;
    }

    @Override
    public void sendDtmf(char c) {
    }

    @Override
    public void startDtmf(char c) {
    }

    @Override
    public void stopDtmf() {
    }

    @Override
    public void setRadioPower(boolean power) {
    }

    @Override
    public void setMute(boolean muted) {
    }

    @Override
    public boolean getMute() {
        return false;
    }

    @Override
    public void updateServiceLocation() {
    }

    @Override
    public void enableLocationUpdates() {
    }

    @Override
    public void disableLocationUpdates() {
    }

    @Override
    public boolean getDataRoamingEnabled() {
        return false;
    }

    @Override
    public void setDataRoamingEnabled(boolean enable) {
    }

    @Override
    public PhoneSubInfo getPhoneSubInfo() {
        return mSubInfo;
    }

    @Override
    public IccSmsInterfaceManager getIccSmsInterfaceManager() {
        return null;
    }

    @Override
    public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager() {
        return null;
    }

    @Override
    protected void onUpdateIccAvailability() {
    }

    public PhoneConstants.State getState() {
        return mImsCT.state;
    }

    public ServiceState getServiceState() {
        Log.i(LOG_TAG, "Service state " + mImsSS.getState());
        return mImsSS;
    }

    public CellLocation getCellLocation() {
        if (mParentPhone != null) {
            mParentPhone.getCellLocation();
        }
        return null;
    }

    public PhoneConstants.DataState getDataConnectionState(String apnType) {
        if (mParentPhone != null) {
            return (mParentPhone.getDataConnectionState(apnType));
        }

        PhoneConstants.DataState ret = PhoneConstants.DataState.CONNECTED;
        return ret;
    }

    public List<? extends MmiCode> getPendingMmiCodes() {
        return null;
    }

    public void registerForSuppServiceNotification(
            Handler h, int what, Object obj) {
    }

    public void unregisterForSuppServiceNotification(Handler h) {
    }

    public Connection dial(String dialString) throws CallStateException {
        return dial(dialString, null);
    }

    public Connection dial(String dialString, UUSInfo uusInfo) throws CallStateException {
        Log.i(LOG_TAG, "dial ");
        Connection c = mImsCT.dial(dialString, uusInfo);
        return c;
    }

    public boolean handlePinMmi(String dialString) {

        return false;
    }

    public void sendUssdResponse(String ussdMessge) {
    }

    private boolean handleCcbsIncallSupplementaryService(String dialString)
            throws CallStateException {
        return false;
    }

    public boolean handleInCallMmiCommands(String dialString)
            throws CallStateException {

        return false;
    }

    public String getVoiceMailNumber() {
        return "0";
    }

    public String getVoiceMailAlphaTag() {
        return "0";
    }

    public void setVoiceMailNumber(String alphaTag,
            String voiceMailNumber,
            Message onComplete) {
    }

    public void getCallForwardingOption(int commandInterfaceCFReason, Message onComplete) {
    }

    public void setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,
            String dialingNumber,
            int timerSeconds,
            Message onComplete) {
    }

    public void getOutgoingCallerIdDisplay(Message onComplete) {
    }

    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode, Message onComplete) {
    }

    public void getCallWaiting(Message onComplete) {
    }

    public void setCallWaiting(boolean enable, Message onComplete) {
    }

    public void getAvailableNetworks(Message response) {
    }

    public void setNetworkSelectionModeAutomatic(Message response) {
    }

    public void selectNetworkManually(OperatorInfo network,
            Message response) {
    }

    public void getNeighboringCids(Message response) {
        mCi.getNeighboringCids(response);
    }

    public void setOnPostDialCharacter(Handler h, int what, Object obj) {
    }

    public void getDataCallList(Message response) {
        mCi.getDataCallList(response);
    }

    public String getDeviceId() {
        if (mParentPhone != null) {
            return mParentPhone.getDeviceId();
        }
        return "0";
    }

    public String getDeviceSvn() {
        if (mParentPhone != null) {
            return mParentPhone.getDeviceSvn();
        }
        return "0";
    }

    public String getImei() {
        if (mParentPhone != null) {
            return mParentPhone.getImei();
        }
        return "0";
    }

    public String getEsn() {
        if (mParentPhone != null) {
            return mParentPhone.getEsn();
        }
        return "0";
    }

    public String getMeid() {
        if (mParentPhone != null) {
            return mParentPhone.getMeid();
        }
        return "0";
    }

    public String getSubscriberId() {
        if (mParentPhone != null) {
            return mParentPhone.getSubscriberId();
        }
        return "0";
    }

    public String getLine1Number() {
        if (mParentPhone != null) {
            String line1 = mParentPhone.getLine1Number();
            // Workaround in case local MSISDN not present in SIM card: use
            // property to get it
            if (line1 == null || line1.length() == 0) {
                line1 = SystemProperties.get(PROPERTY_IMS_MSISDN);
            }
            return line1;
        }
        return "0";
    }

    public String getMsisdn() {
        if (mParentPhone != null) {
            String msisdn = mParentPhone.getMsisdn();
            // Workaround in case local MSISDN not present in SIM card: use
            // property to get it
            if (msisdn == null || msisdn.length() == 0) {
                msisdn = SystemProperties.get(PROPERTY_IMS_MSISDN);
            }
            return msisdn;
        }
        return "0";
    }

    public String getLine1AlphaTag() {
        if (mParentPhone != null) {
            return mParentPhone.getLine1AlphaTag();
        }
        return "0";
    }

    public void setLine1Number(String alphaTag, String number, Message onComplete) {
        if (mParentPhone != null) {
            mParentPhone.setLine1Number(alphaTag, number, onComplete);
        }
    }

    public void activateCellBroadcastSms(int activate, Message response) {
    }

    public void getCellBroadcastSmsConfig(Message response) {
    }

    public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response) {
    }

    /**
     * Notify any interested party of a Phone state change
     * {@link PhoneConstants.State}
     */
    void notifyPhoneStateChanged() {
        mNotifier.notifyPhoneState(this);
    }

    /**
     * Notify registrants of a change in the call state. This notifies changes
     * in {@link Call.State} Use this when changes in the precise call state are
     * needed, else use notifyPhoneStateChanged.
     */
    void notifyPreciseCallStateChanged() {
        /* we'd love it if this was package-scoped */
        super.notifyPreciseCallStateChangedP();
    }

    void notifyNewRingingConnection(Connection c) {
        /* we'd love it if this was package-scoped */
        super.notifyNewRingingConnectionP(c);
    }

    void notifyDisconnect(Connection cn) {
        mDisconnectRegistrants.notifyResult(cn);
    }

    void notifyUnknownConnection() {
        mUnknownConnectionRegistrants.notifyResult(this);
    }

    void notifySuppServiceFailed(SuppService code) {
        mSuppServiceFailedRegistrants.notifyResult(code);
    }

    void notifyServiceStateChanged(ServiceState ss) {
        super.notifyServiceStateChangedP(ss);
    }

    void notifyLocationChanged() {
        mNotifier.notifyCellLocation(this);
    }

    @Override
    public void notifyCallForwardingIndicator() {
        mNotifier.notifyCallForwardingChanged(this);
    }

    @Override
    public String getGroupIdLevel1() {
        return null;
    }
}

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
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.telephony.ServiceState;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.UUSInfo;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.intel.internal.telephony.OemTelephony.OemTelephonyConstants;

public class ImsCommandsInterface extends Handler implements CommandsInterface {
    /** Framework States */
    public static final int IMS_STATE = 0x2001;

    public static final int VOIP_STATE = 0x2002;
    public static final int VOIP_STATE_DIALING = ImsVoip.VOIP_STATE_CREATED;
    public static final int VOIP_STATE_ACTIVE = ImsVoip.VOIP_STATE_CONNECTED;
    public static final int VOIP_STATE_DISCONNECTED = ImsVoip.VOIP_STATE_DISCONNECTED;
    public static final int VOIP_STATE_DESTROYED = ImsVoip.VOIP_STATE_DESTROYED;
    public static final int VOIP_STATE_ALERTING = ImsVoip.VOIP_STATE_RINGING;

    public static final int VOIP_ACCEPT = 0x2004;

    private final String LOG_TAG = "ImsCommandsInterface";
    private final Context mContext;
    private ImsRegistration mImsReg = null;
    private ImsVoip mImsVoip = null;
    private Handler mHdlr = null;
    private Phone mPhone = null;

    public ImsCommandsInterface(Context c, Handler h) {
        mContext = c;
        mHdlr = h;

        mImsReg = new ImsRegistration(mContext, this);
        mImsVoip = new ImsVoip(this);
    }

    public void finalize() {
        mImsReg.finalize();
        mImsVoip = null;

        try {
            super.finalize();
        } catch (Throwable e) {
            Log.e(LOG_TAG, e.toString());
        }
    }

    private void updateImsIcon(boolean state) {
        // Update IMS registration icon in Status Bar
        Intent intent = new Intent(OemTelephonyConstants.ACTION_IMS_REGISTRATION_STATE_CHANGED);
        intent.putExtra(OemTelephonyConstants.IMS_STATUS_KEY, state);
        mContext.sendBroadcast(intent);
    }

    private void updateServiceState(boolean state) {
        int arg1 = ServiceState.STATE_OUT_OF_SERVICE;

        if (mHdlr != null) {
            if (state == true) {
                arg1 = ServiceState.STATE_IN_SERVICE;
            }
            Message message = mHdlr.obtainMessage(IMS_STATE, arg1, 0);
            mHdlr.sendMessage(message);
        }
    }

    private void updateVoipState(int state) {
        if (mHdlr != null) {
            Message message = mHdlr.obtainMessage(VOIP_STATE, state, 0);
            mHdlr.sendMessage(message);
        }
    }

    public void registerForServiceState(Handler h) {
        mHdlr = h;
    }

    public void initiateImsRegistration(boolean state) {
        if (state == true) {
            mImsReg.startImsService();
        }
        else {
            mImsReg.stopImsService();
            updateImsIcon(state);
            updateServiceState(state);
        }
    }

    private void handleImsRegStatus(Message msg) {
        boolean curr_ims_status = false;

        switch (msg.what)
        {
            case ImsRegistration.IMS_OFFLINE:
                Log.i(LOG_TAG, "IMS_OFFLINE");
                break;
            case ImsRegistration.IMS_ONLINE:
                Log.i(LOG_TAG, "IMS_ONLINE");
                curr_ims_status = true;
                break;
            case ImsRegistration.IMS_DISABLED:
                Log.i(LOG_TAG, "IMS_DISABLED");
                break;
            default:
                break;
        }

        updateImsIcon(curr_ims_status);
        updateServiceState(curr_ims_status);
    }

    private void handleImsVoipStatus(Message msg) {
        switch (msg.what)
        {
            case ImsRegistration.VOIP_OFFLINE:
                Log.i(LOG_TAG, "VOIP_OFFLINE");
                break;
            case ImsRegistration.VOIP_ONLINE:
                Log.i(LOG_TAG, "VOIP_ONLINE");
                // Get ready to receive incoming Voice call
                mImsVoip.RegisterVoipForIncomingCall();
                break;
            default:
                break;
        }
    }

    private void handleImsVoipUpdate(Message msg) {

        switch (msg.what) {
            case ImsVoip.VOIP_ACCEPT:
                if (mHdlr != null) {
                    Message message = mHdlr.obtainMessage(VOIP_ACCEPT, msg.arg1,
                            msg.arg2,
                            msg.obj);
                    mHdlr.sendMessage(message);
                }
                break;
            case ImsVoip.VOIP_STATE:
                switch (msg.arg1)
                {
                    case ImsVoip.VOIP_STATE_CREATED:
                        Log.d(LOG_TAG, "VOIP_STATE_CREATED");
                        updateVoipState(VOIP_STATE_DIALING);
                        break;
                    case ImsVoip.VOIP_STATE_RINGING:
                        Log.d(LOG_TAG, "VOIP_STATE_RINGING");
                        updateVoipState(VOIP_STATE_ALERTING);
                        break;
                    case ImsVoip.VOIP_STATE_CONNECTED:
                        Log.d(LOG_TAG, "VOIP_STATE_CONNECTED");
                        updateVoipState(VOIP_STATE_ACTIVE);
                        break;
                    case ImsVoip.VOIP_STATE_DISCONNECTED:
                        Log.d(LOG_TAG, "VOIP_STATE_DISCONNECTED");
                        updateVoipState(VOIP_STATE_DISCONNECTED);
                        break;
                    case ImsVoip.VOIP_STATE_DESTROYED:
                        Log.i(LOG_TAG, "VOIP_STATE_DESTROYED");
                        updateVoipState(VOIP_STATE_DESTROYED);
                        break;
                    default:
                        Log.i(LOG_TAG, "Unhandled VOIP_STATE");
                        break;
                }
                break;
            default:
                break;
        }
    }

    private void handleImsVtStatus(Message msg) {
        switch (msg.what)
        {
            case ImsRegistration.VT_OFFLINE:
                Log.i(LOG_TAG, "VT_OFFLINE");
                break;
            case ImsRegistration.VT_ONLINE:
                Log.i(LOG_TAG, "VT_ONLINE");
                break;
            default:
                break;
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what)
        {
            case ImsRegistration.IMS_OFFLINE:
            case ImsRegistration.IMS_ONLINE:
            case ImsRegistration.IMS_DISABLED:
                handleImsRegStatus(msg);
                break;

            case ImsRegistration.VOIP_OFFLINE:
            case ImsRegistration.VOIP_ONLINE:
                handleImsVoipStatus(msg);
                break;

            case ImsRegistration.VT_OFFLINE:
            case ImsRegistration.VT_ONLINE:
                handleImsVtStatus(msg);
                break;

            case ImsVoip.VOIP_ACCEPT:
            case ImsVoip.VOIP_STATE:
                handleImsVoipUpdate(msg);
                break;

            default:
                break;
        }
    }

    public RadioState getRadioState() {
        return null;
    }

    public void setCurrentPreferredNetworkType() {
    }

    public void setPhoneType(int phoneType) {
    }

    public void setPhone(Phone phone) {
        if (phone != null) {
            mPhone = phone;
        } else {
            throw new IllegalArgumentException("phone");
        }
    }

    public int getLteOnCdmaMode() {
        return 0;
    }

    public void testingEmergencyCall() {
    }

    public void getVoiceRadioTechnology(Message result) {
    }

    public void registerForRadioStateChanged(Handler h, int what, Object obj) {
    }

    public void unregisterForRadioStateChanged(Handler h) {
    }

    public void registerForVoiceRadioTechChanged(Handler h, int what, Object obj) {
    }

    public void unregisterForVoiceRadioTechChanged(Handler h) {
    }

    public void registerForOn(Handler h, int what, Object obj) {
    }

    public void unregisterForOn(Handler h) {
    }

    public void registerForAvailable(Handler h, int what, Object obj) {
    }

    public void unregisterForAvailable(Handler h) {
    }

    public void registerForNotAvailable(Handler h, int what, Object obj) {
    }

    public void unregisterForNotAvailable(Handler h) {
    }

    public void registerForOffOrNotAvailable(Handler h, int what, Object obj) {
    }

    public void unregisterForOffOrNotAvailable(Handler h) {
    }

    public void registerForIccStatusChanged(Handler h, int what, Object obj) {
    }

    public void unregisterForIccStatusChanged(Handler h) {
    }

    public void registerForCallStateChanged(Handler h, int what, Object obj) {
    }

    public void unregisterForCallStateChanged(Handler h) {
    }

    public void registerForVoiceNetworkStateChanged(Handler h, int what, Object obj) {
    }

    public void unregisterForVoiceNetworkStateChanged(Handler h) {
    }

    public void registerForDataNetworkStateChanged(Handler h, int what, Object obj) {
    }

    public void unregisterForDataNetworkStateChanged(Handler h) {
    }

    public void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj) {
    }

    public void unregisterForInCallVoicePrivacyOn(Handler h) {
    }

    public void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj) {
    }

    public void unregisterForInCallVoicePrivacyOff(Handler h) {
    }

    public void setOnNewGsmSms(Handler h, int what, Object obj) {
    }

    public void unSetOnNewGsmSms(Handler h) {
    }

    public void setOnNewCdmaSms(Handler h, int what, Object obj) {
    }

    public void unSetOnNewCdmaSms(Handler h) {
    }

    public void setOnNewGsmBroadcastSms(Handler h, int what, Object obj) {
    }

    public void unSetOnNewGsmBroadcastSms(Handler h) {
    }

    public void setOnSmsOnSim(Handler h, int what, Object obj) {
    }

    public void unSetOnSmsOnSim(Handler h) {
    }

    public void setOnSmsStatus(Handler h, int what, Object obj) {
    }

    public void unSetOnSmsStatus(Handler h) {
    }

    public void setOnNITZTime(Handler h, int what, Object obj) {
    }

    public void unSetOnNITZTime(Handler h) {
    }

    public void setOnUSSD(Handler h, int what, Object obj) {
    }

    public void unSetOnUSSD(Handler h) {
    }

    public void setOnSignalStrengthUpdate(Handler h, int what, Object obj) {
    }

    public void unSetOnSignalStrengthUpdate(Handler h) {
    }

    public void setOnIccSmsFull(Handler h, int what, Object obj) {
    }

    public void unSetOnIccSmsFull(Handler h) {
    }

    public void registerForIccRefresh(Handler h, int what, Object obj) {
    }

    public void unregisterForIccRefresh(Handler h) {
    }

    public void setOnIccRefresh(Handler h, int what, Object obj) {
    }

    public void unsetOnIccRefresh(Handler h) {
    }

    public void setOnCallRing(Handler h, int what, Object obj) {
    }

    public void unSetOnCallRing(Handler h) {
    }

    public void setOnRestrictedStateChanged(Handler h, int what, Object obj) {
    }

    public void unSetOnRestrictedStateChanged(Handler h) {
    }

    public void setOnSuppServiceNotification(Handler h, int what, Object obj) {
    }

    public void unSetOnSuppServiceNotification(Handler h) {
    }

    public void setOnCatSessionEnd(Handler h, int what, Object obj) {
    }

    public void unSetOnCatSessionEnd(Handler h) {
    }

    public void setOnCatProactiveCmd(Handler h, int what, Object obj) {
    }

    public void unSetOnCatProactiveCmd(Handler h) {
    }

    public void setOnCatEvent(Handler h, int what, Object obj) {
    }

    public void unSetOnCatEvent(Handler h) {
    }

    public void setOnCatCallSetUp(Handler h, int what, Object obj) {
    }

    public void unSetOnCatCallSetUp(Handler h) {
    }

    public void setSuppServiceNotifications(boolean enable, Message result) {
    }

    public void registerForDisplayInfo(Handler h, int what, Object obj) {
    }

    public void unregisterForDisplayInfo(Handler h) {
    }

    public void registerForCallWaitingInfo(Handler h, int what, Object obj) {
    }

    public void unregisterForCallWaitingInfo(Handler h) {
    }

    public void registerForSignalInfo(Handler h, int what, Object obj) {
    }

    public void unregisterForSignalInfo(Handler h) {
    }

    public void setOnUnsolOemHookRaw(Handler h, int what, Object obj) {
    }

    public void unSetOnUnsolOemHookRaw(Handler h) {
    }

    public void registerForNumberInfo(Handler h, int what, Object obj) {
    }

    public void unregisterForNumberInfo(Handler h) {
    }

    public void registerForRedirectedNumberInfo(Handler h, int what, Object obj) {
    }

    public void unregisterForRedirectedNumberInfo(Handler h) {
    }

    public void registerForLineControlInfo(Handler h, int what, Object obj) {
    }

    public void unregisterForLineControlInfo(Handler h) {
    }

    public void registerFoT53ClirlInfo(Handler h, int what, Object obj) {
    }

    public void unregisterForT53ClirInfo(Handler h) {
    }

    public void registerForT53AudioControlInfo(Handler h, int what, Object obj) {
    }

    public void unregisterForT53AudioControlInfo(Handler h) {
    }

    public void setEmergencyCallbackMode(Handler h, int what, Object obj) {
    }

    public void registerForCdmaOtaProvision(Handler h, int what, Object obj) {
    }

    public void unregisterForCdmaOtaProvision(Handler h) {
    }

    public void registerForRingbackTone(Handler h, int what, Object obj) {
    }

    public void unregisterForRingbackTone(Handler h) {
    }

    public void registerForResendIncallMute(Handler h, int what, Object obj) {
    }

    public void unregisterForResendIncallMute(Handler h) {
    }

    public void registerForCdmaSubscriptionChanged(Handler h, int what, Object obj) {
    }

    public void unregisterForCdmaSubscriptionChanged(Handler h) {
    }

    public void registerForCdmaPrlChanged(Handler h, int what, Object obj) {
    }

    public void unregisterForCdmaPrlChanged(Handler h) {
    }

    public void registerForExitEmergencyCallbackMode(Handler h, int what, Object obj) {
    }

    public void unregisterForExitEmergencyCallbackMode(Handler h) {
    }

    public void registerForRilConnected(Handler h, int what, Object obj) {
    }

    public void unregisterForRilConnected(Handler h) {
    }

    public void supplyIccPin(String pin, Message result) {
    }

    public void supplyIccPinForApp(String pin, String aid, Message result) {
    }

    public void supplyIccPuk(String puk, String newPin, Message result) {
    }

    public void supplyIccPukForApp(String puk, String newPin, String aid, Message result) {
    }

    public void supplyIccPin2(String pin2, Message result) {
    }

    public void supplyIccPin2ForApp(String pin2, String aid, Message result) {
    }

    public void supplyIccPuk2(String puk2, String newPin2, Message result) {
    }

    public void supplyIccPuk2ForApp(String puk2, String newPin2, String aid, Message result) {
    }

    public void changeIccPin(String oldPin, String newPin, Message result) {
    }

    public void changeIccPinForApp(String oldPin, String newPin, String aidPtr, Message result) {
    }

    public void changeIccPin2(String oldPin2, String newPin2, Message result) {
    }

    public void changeIccPin2ForApp(String oldPin2, String newPin2, String aidPtr, Message result) {
    }

    public void changeBarringPassword(String facility, String oldPwd, String newPwd, Message result) {
    }

    public void supplyNetworkDepersonalization(String netpin, Message result) {
    }

    public void getCurrentCalls(Message result) {
    }

    @Deprecated
    public void getPDPContextList(Message result) {
    }

    public void getDataCallList(Message result) {
    }

    public void dial(String address, int clirMode, Message result) {
        dial(address, clirMode, null, result);
    }

    public void dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        Log.i(LOG_TAG, "dial " + address);

        if (result != null) {
            AsyncResult.forMessage(result, 0, null);
            result.sendToTarget();
        }

        if ((mPhone != null) && (mImsVoip != null)) {
            mImsVoip.CreateVoipSession(mPhone.getMsisdn(), address);
        }
    }

    public void getIMSI(Message result) {
    }

    public void getIMSIForApp(String aid, Message result) {
    }

    public void getIMEI(Message result) {
    }

    public void getIMEISV(Message result) {
    }

    public void hangupConnection(int imsIndex, Message result) {
        Log.i(LOG_TAG, "hangupConnection " + imsIndex);

        if (result != null) {
            AsyncResult.forMessage(result, 0, null);
            result.sendToTarget();
        }

        if (mImsVoip != null) {
            mImsVoip.TerminateVoipSession();
        }
    }

    public void hangupWaitingOrBackground(Message result) {
    }

    public void hangupForegroundResumeBackground(Message result) {
    }

    public void switchWaitingOrHoldingAndActive(Message result) {
    }

    public void conference(Message result) {
    }

    public void setPreferredVoicePrivacy(boolean enable, Message result) {
    }

    public void getPreferredVoicePrivacy(Message result) {
    }

    public void separateConnection(int gsmIndex, Message result) {
    }

    public void acceptCall(Message result) {
        if (result != null) {
            AsyncResult.forMessage(result, 0, null);
            result.sendToTarget();
        }

        if (mImsVoip != null) {
            mImsVoip.AcceptVoipSession();
        }
    }

    public void rejectCall(Message result) {
        if (result != null) {
            AsyncResult.forMessage(result, 0, null);
            result.sendToTarget();
        }

        if (mImsVoip != null) {
            mImsVoip.RejectVoipSession();
        }
    }

    public void explicitCallTransfer(Message result) {
    }

    public void getLastCallFailCause(Message result) {
    }

    @Deprecated
    public void getLastPdpFailCause(Message result) {
    }

    public void getLastDataCallFailCause(Message result) {
    }

    public void setMute(boolean enableMute, Message response) {
    }

    public void getMute(Message response) {
    }

    public void getSignalStrength(Message response) {
    }

    public void getVoiceRegistrationState(Message response) {
    }

    public void getDataRegistrationState(Message response) {
    }

    public void getOperator(Message response) {
    }

    public void sendDtmf(char c, Message result) {
    }

    public void startDtmf(char c, Message result) {
    }

    public void stopDtmf(Message result) {
    }

    public void sendBurstDtmf(String dtmfString, int on, int off, Message result) {
    }

    public void sendSMS(String smscPDU, String pdu, Message response) {
    }

    public void sendCdmaSms(byte[] pdu, Message response) {
    }

    public void deleteSmsOnSim(int index, Message response) {
    }

    public void deleteSmsOnRuim(int index, Message response) {
    }

    public void writeSmsToSim(int status, String smsc, String pdu, Message response) {
    }

    public void writeSmsToRuim(int status, String pdu, Message response) {
    }

    public void setRadioPower(boolean on, Message response) {
    }

    public void acknowledgeLastIncomingGsmSms(boolean success, int cause, Message response) {
    }

    public void acknowledgeLastIncomingCdmaSms(boolean success, int cause, Message response) {
    }

    public void acknowledgeIncomingGsmSmsWithPdu(boolean success, String ackPdu, Message response) {
    }

    public void iccIO(int command, int fileid, String path, int p1, int p2, int p3,
            String data, String pin2, Message response) {
    }

    public void iccIOForApp(int command, int fileid, String path, int p1, int p2, int p3,
            String data, String pin2, String aid, Message response) {
    }

    public void queryCLIP(Message response) {
    }

    public void getCLIR(Message response) {
    }

    public void setCLIR(int clirMode, Message response) {
    }

    public void queryCallWaiting(int serviceClass, Message response) {
    }

    public void setCallWaiting(boolean enable, int serviceClass, Message response) {
    }

    public void setCallForward(int action, int cfReason, int serviceClass,
            String number, int timeSeconds, Message response) {
    }

    public void queryCallForwardStatus(int cfReason, int serviceClass,
            String number, Message response) {
    }

    public void setNetworkSelectionModeAutomatic(Message response) {
    }

    public void setNetworkSelectionModeManual(String operatorNumeric, Message response) {
    }

    public void getNetworkSelectionMode(Message response) {
    }

    public void getAvailableNetworks(Message response) {
    }

    public void getBasebandVersion(Message response) {
    }

    public void queryFacilityLock(String facility, String password, int serviceClass,
            Message response) {
    }

    public void queryFacilityLockForApp(String facility, String password, int serviceClass,
            String appId,
            Message response) {
    }

    public void setFacilityLock(String facility, boolean lockState, String password,
            int serviceClass, Message response) {
    }

    public void setFacilityLockForApp(String facility, boolean lockState, String password,
            int serviceClass, String appId, Message response) {
    }

    public void sendUSSD(String ussdString, Message response) {
    }

    public void cancelPendingUssd(Message response) {
    }

    public void resetRadio(Message result) {
    }

    public void setBandMode(int bandMode, Message response) {
    }

    public void queryAvailableBandMode(Message response) {
    }

    public void setPreferredNetworkType(int networkType, Message response) {
    }

    public void getPreferredNetworkType(Message response) {
    }

    public void getNeighboringCids(Message response) {
    }

    public void setLocationUpdates(boolean enable, Message response) {
    }

    public void getSmscAddress(Message result) {
    }

    public void setSmscAddress(String address, Message result) {
    }

    public void reportSmsMemoryStatus(boolean available, Message result) {
    }

    public void reportStkServiceIsRunning(Message result) {
    }

    public void invokeOemRilRequestRaw(byte[] data, Message response) {
    }

    public void invokeOemRilRequestStrings(String[] strings, Message response) {
    }

    public void sendTerminalResponse(String contents, Message response) {
    }

    public void sendEnvelope(String contents, Message response) {
    }

    public void sendEnvelopeWithStatus(String contents, Message response) {
    }

    public void handleCallSetupRequestFromSim(boolean accept, Message response) {
    }

    public void setGsmBroadcastActivation(boolean activate, Message result) {
    }

    public void setGsmBroadcastConfig(SmsBroadcastConfigInfo[] config, Message response) {
    }

    public void getGsmBroadcastConfig(Message response) {
    }

    public void getDeviceIdentity(Message response) {
    }

    public void getCDMASubscription(Message response) {
    }

    public void sendCDMAFeatureCode(String FeatureCode, Message response) {
    }

    public void queryCdmaRoamingPreference(Message response) {
    }

    public void setCdmaRoamingPreference(int cdmaRoamingType, Message response) {
    }

    public void setCdmaSubscriptionSource(int cdmaSubscriptionType, Message response) {
    }

    public void getCdmaSubscriptionSource(Message response) {
    }

    public void setTTYMode(int ttyMode, Message response) {
    }

    public void queryTTYMode(Message response) {
    }

    public void setupDataCall(String radioTechnology, String profile,
            String apn, String user, String password, String authType,
            String protocol, Message result) {
    }

    public void deactivateDataCall(int cid, int reason, Message result) {
    }

    public void setCdmaBroadcastActivation(boolean activate, Message result) {
    }

    public void setCdmaBroadcastConfig(int[] configValuesArray, Message result) {
    }

    public void getCdmaBroadcastConfig(Message result) {
    }

    public void exitEmergencyCallbackMode(Message response) {
    }

    public void getIccCardStatus(Message result) {
    }

    public void requestIsimAuthentication(String nonce, Message response) {
    }

    public void iccExchangeAPDU(int cla, int command, int channel, int p1, int p2,
            int p3, String data, Message response) {
    }

    public void iccOpenChannel(String AID, Message response) {
    }

    public void iccCloseChannel(int channel, Message response) {
    }
}

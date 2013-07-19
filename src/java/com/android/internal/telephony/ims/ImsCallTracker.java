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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.UUSInfo;

public final class ImsCallTracker extends CallTracker {

    private final String LOG_TAG = "ImsCallTracker";

    static final int MAX_CONNECTIONS = 7;
    static final int MAX_CONNECTIONS_PER_CALL = 5;

    private boolean mUsePollingForCallStatus = true;
    private Phone mDefPhone = null;
    private ImsConnection mPendingMO = null;

    boolean desiredMute = false; // false = mute off
    ImsConnection mConnections[] = new ImsConnection[MAX_CONNECTIONS];

    ImsCall ringingCall = new ImsCall(this);
    ImsCall foregroundCall = new ImsCall(this);
    ImsCall backgroundCall = new ImsCall(this);

    ImsPhone phone = null;

    ImsConnection pendingMT = null;

    PhoneConstants.State state = PhoneConstants.State.IDLE;

    ImsCallTracker(ImsPhone imsPhone) {
        if (imsPhone != null) {
            phone = imsPhone;
            cm = phone.mCM;
            mDefPhone = CallManager.getInstance().getDefaultPhone();
            if (mDefPhone == null) {
                Log.e(LOG_TAG, "No default phone");
            }
        } else {
            throw new IllegalArgumentException("imsPhone");
        }
    }

    public void dispose() {
        cm.unregisterForCallStateChanged(this);
        cm.unregisterForOn(this);
        cm.unregisterForNotAvailable(this);

        for (ImsConnection c : mConnections) {
            try {
                if (c != null) {
                    hangup(c);
                }
            } catch (CallStateException ex) {
                Log.e(LOG_TAG, "unexpected error on hangup during dispose");
            }
        }

        try {
            if (mPendingMO != null) {
                hangup(mPendingMO);
            }
        } catch (CallStateException ex) {
            Log.e(LOG_TAG, "unexpected error on hangup during dispose");
        }
        clearDisconnected();
    }

    protected void log(String msg) {
    }

    protected synchronized void handlePollCalls(AsyncResult ar) {
        Log.d(LOG_TAG, "handlePollCalls");
        updatePhoneState();
    }

    private void updatePhoneState() {
        PhoneConstants.State oldState = state;

        if (ringingCall.isRinging()) {
            state = PhoneConstants.State.RINGING;
        } else if (mPendingMO != null
                || !(foregroundCall.isIdle() && backgroundCall.isIdle())) {
            state = PhoneConstants.State.OFFHOOK;
        } else {
            state = PhoneConstants.State.IDLE;
        }

        if (state != oldState) {
            phone.notifyPhoneStateChanged();
        }
    }

    public void handleMessage(Message msg) {
        AsyncResult ar = null;

        Log.d(LOG_TAG, "what:  " + msg.what);
        switch (msg.what)
        {
            case ImsCommandsInterface.IMS_STATE:
                if (phone.getServiceState().getState() != msg.arg1) {
                    Log.i(LOG_TAG, "Service state " + msg.arg1);
                    phone.getServiceState().setState(msg.arg1);

                    if (msg.arg1 == ServiceState.STATE_IN_SERVICE) {
                        if (mDefPhone != null) {
                            Log.d(LOG_TAG, "Unregistering default phone");
                            CallManager.getInstance().unregisterPhone(mDefPhone);
                        } else {
                            Log.d(LOG_TAG, "No default phone to unregister");
                        }
                        // ImsPhone to be the default
                        CallManager.getInstance().registerPhone(phone);
                    }
                    else {
                        Log.d(LOG_TAG, "Unregistering phone");
                        CallManager.getInstance().unregisterPhone(phone);
                        if (mDefPhone != null) {
                            // GsmLtePhone to be the default
                            CallManager.getInstance().registerPhone(mDefPhone);
                        } else {
                            Log.d(LOG_TAG, "No phone to unregister");
                        }
                    }
                }
                break;

            case ImsCommandsInterface.VOIP_ACCEPT:
                String number = (String) msg.obj;

                Log.i(LOG_TAG, "VOIP_ACCEPT from number " + number);

                ringingCall.setState(ImsCall.State.INCOMING);

                pendingMT = new ImsConnection(phone.getContext(),
                        number,
                        this,
                        ringingCall,
                        true);
                updatePhoneState();
                phone.notifyNewRingingConnection(pendingMT);
                break;

            case ImsCommandsInterface.VOIP_STATE:
                switch (msg.arg1)
                {
                    case ImsCommandsInterface.VOIP_STATE_DIALING:
                        Log.i(LOG_TAG, "VOIP_STATE_DIALING");
                        if (mPendingMO != null) {
                            foregroundCall.setState(ImsCall.State.DIALING);
                            phone.notifyPreciseCallStateChanged();
                        }
                        break;
                    case ImsCommandsInterface.VOIP_STATE_ALERTING:
                        Log.i(LOG_TAG, "VOIP_STATE_ALERTING");
                        if (mPendingMO != null) {
                            mPendingMO.index = 0;
                            foregroundCall.setState(ImsCall.State.ALERTING);
                            phone.notifyPreciseCallStateChanged();
                        }
                        break;
                    case ImsCommandsInterface.VOIP_STATE_ACTIVE:
                        Log.i(LOG_TAG, "VOIP_STATE_ACTIVE");
                        if (mPendingMO != null) {
                            mPendingMO.index = 0;
                            mConnections[0] = mPendingMO;
                            mPendingMO = null;
                            foregroundCall.setState(ImsCall.State.ACTIVE);
                            phone.notifyPreciseCallStateChanged();
                        }
                        break;
                    case ImsCommandsInterface.VOIP_STATE_DISCONNECTED:
                        Log.i(LOG_TAG, "VOIP_STATE_DISCONNECTED");

                        if (mPendingMO != null) {
                            mPendingMO.onRemoteDisconnect(0);
                            cm.hangupConnection(0, obtainCompleteMessage());
                            mPendingMO = null;
                        }
                        else {
                            mConnections[0].onRemoteDisconnect(0);
                        }

                        foregroundCall.setState(ImsCall.State.DISCONNECTED);
                        phone.notifyPreciseCallStateChanged();

                        break;
                    default:
                        break;
                }
                break;

            case EVENT_POLL_CALLS_RESULT:
                ar = (AsyncResult) msg.obj;

                if (msg == lastRelevantPoll) {
                    Log.d(LOG_TAG, "EVENT_POLL_CALL_RESULT: set needsPoll=F");
                    needsPoll = false;
                    lastRelevantPoll = null;
                    handlePollCalls((AsyncResult) msg.obj);
                }
                break;

            case EVENT_OPERATION_COMPLETE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception != null) {
                    needsPoll = true;
                }
                Log.d(LOG_TAG, "EVENT_OPERATION_COMPLETE");
                operationComplete();
                break;

            case EVENT_REPOLL_AFTER_DELAY:
            case EVENT_CALL_STATE_CHANGE:
                pollCallsWhenSafe();
                break;

            case EVENT_RADIO_AVAILABLE:
                handleRadioAvailable();
                break;

            case EVENT_RADIO_NOT_AVAILABLE:
                pollCallsWhenSafe();
                break;
            default:
                break;
        }
    }

    public void registerForVoiceCallStarted(Handler h, int what, Object obj) {
    }

    public void unregisterForVoiceCallStarted(Handler h) {
    }

    public void registerForVoiceCallEnded(Handler h, int what, Object obj) {
    }

    public void unregisterForVoiceCallEnded(Handler h) {
    }

    synchronized Connection dial(String dialString, int clirMode, UUSInfo uusInfo)
            throws CallStateException {
        clearDisconnected();

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }

        foregroundCall.setState(ImsCall.State.DIALING);

        mPendingMO = new ImsConnection(phone.getContext(),
                checkForTestEmergencyNumber(dialString),
                this,
                foregroundCall,
                false);

        if (mPendingMO.address == null || mPendingMO.address.length() == 0
                || mPendingMO.address.indexOf(PhoneNumberUtils.WILD) >= 0) {
            // Phone number is invalid
            mPendingMO.cause = Connection.DisconnectCause.INVALID_NUMBER;

            // handlePollCalls() will notice this call not present
            // and will mark it as dropped.
            pollCallsWhenSafe();
        } else {
            cm.dial(mPendingMO.address, clirMode, uusInfo, obtainCompleteMessage());
        }

        Log.i(LOG_TAG, "dial " + mPendingMO);

        return mPendingMO;
    }

    Connection dial(String dialString) throws CallStateException {
        return dial(dialString, CommandsInterface.CLIR_DEFAULT, null);
    }

    Connection dial(String dialString, UUSInfo uusInfo) throws CallStateException {
        return dial(dialString, CommandsInterface.CLIR_DEFAULT, uusInfo);
    }

    Connection dial(String dialString, int clirMode) throws CallStateException {
        return dial(dialString, clirMode, null);
    }

    void acceptCall() throws CallStateException {
        if ((ringingCall.getState() == ImsCall.State.INCOMING) ||
                (ringingCall.getState() == ImsCall.State.WAITING)) {
            Log.i(LOG_TAG, "acceptCall");

            setMute(false);

            cm.acceptCall(obtainCompleteMessage());

        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    void rejectCall() throws CallStateException {
        if (ringingCall.getState().isRinging()) {
            Log.i(LOG_TAG, "rejectCall");

            cm.rejectCall(obtainCompleteMessage());
            pendingMT = null;

            ringingCall.onHangupLocal();

            updatePhoneState();
            phone.notifyPreciseCallStateChanged();

        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    void hangup(ImsConnection conn) throws CallStateException {
        if (conn.owner != this) {
            throw new CallStateException("ImsConnection " + conn
                    + "does not belong to ImsCallTracker " + this);
        }

        Log.i(LOG_TAG, "hangup " + conn);

        if (conn == mPendingMO) {
            // We're hanging up an outgoing call that doesn't have it's
            // IMS index assigned yet

            Log.d(LOG_TAG, "hangup: set hangupPendingMO to true");
        }
        // Close connection even if MO call has not been not connected yet
        try {
            cm.hangupConnection(conn.getIMSIndex(), obtainCompleteMessage());
        } catch (CallStateException ex) {
            // Ignore "connection not found"
            // Call may have hung up already
            Log.w(LOG_TAG, "ImsCallTracker WARN: hangup() on absent connection "
                    + conn);
        }
        conn.onHangupLocal();
    }

    void separate(ImsConnection conn) throws CallStateException {
        if (conn.owner != this) {
            throw new CallStateException("ImsConnection " + conn
                    + "does not belong to ImsCallTracker " + this);
        }
        try {
            cm.separateConnection(conn.getIMSIndex(),
                    obtainCompleteMessage(EVENT_SEPARATE_RESULT));
        } catch (CallStateException ex) {
            // Ignore "connection not found"
            // Call may have hung up already
            Log.w(LOG_TAG, "ImsCallTracker WARN: separate() on absent connection "
                    + conn);
        }
    }

    void hangup(ImsCall call) throws CallStateException {
        if (call.getConnections().size() == 0) {
            throw new CallStateException("no connections in call");
        }
        Log.i(LOG_TAG, "hangup " + call);

        if (call == ringingCall) {
            Log.i(LOG_TAG, "(ringing) hangup waiting or background");
            cm.hangupWaitingOrBackground(obtainCompleteMessage());
        } else if (call == foregroundCall) {
            if (call.isDialingOrAlerting()) {
                Log.i(LOG_TAG, "(foregnd) hangup dialing or alerting...");
                hangup((ImsConnection) (call.getConnections().get(0)));
            } else if (backgroundCall.isIdle()) {
                hangupAllConnections(call);
            } else {
                hangupForegroundResumeBackground();
            }
        } else if (call == backgroundCall) {
            if (ringingCall.isRinging()) {

                Log.i(LOG_TAG, "hangup all conns in background call");
                hangupAllConnections(call);
            } else {
                hangupWaitingOrBackground();
            }
        } else {
            throw new RuntimeException("ImsCall " + call +
                    "does not belong to ImsCallTracker " + this);
        }

        call.onHangupLocal();

        if (mPendingMO != null) {
            mPendingMO.onLocalDisconnect();
            mPendingMO = null;
        }
        else {
            mConnections[0].onLocalDisconnect();
        }

        updatePhoneState();
        phone.notifyPreciseCallStateChanged();
    }

    void hangupWaitingOrBackground() {
        if (Phone.DEBUG_PHONE)
            log("hangupWaitingOrBackground");
        cm.hangupWaitingOrBackground(obtainCompleteMessage());
    }

    void hangupForegroundResumeBackground() {
        if (Phone.DEBUG_PHONE)
            log("hangupForegroundResumeBackground");
        cm.hangupForegroundResumeBackground(obtainCompleteMessage());
    }

    void hangupAllConnections(ImsCall call) throws CallStateException {
        try {
            int count = call.getConnections().size();
            for (int i = 0; i < count; i++) {
                ImsConnection cn = (ImsConnection) call.getConnections().get(i);
                cm.hangupConnection(cn.getIMSIndex(), obtainCompleteMessage());
            }
        } catch (CallStateException ex) {
            Log.e(LOG_TAG, "hangupConnectionByIndex caught " + ex);
        }
    }

    ImsConnection getConnectionByIndex(ImsCall call, int index)
            throws CallStateException {
        int count = call.getConnections().size();
        for (int i = 0; i < count; i++) {
            ImsConnection cn = (ImsConnection) call.getConnections().get(i);
            if (cn.getIMSIndex() == index) {
                return cn;
            }
        }
        return null;
    }

    private boolean canDial() {
        boolean ret;
        int serviceState = phone.getServiceState().getState();

        Log.d(LOG_TAG, "canDial: Service state " + serviceState +
                " mPendingMO " + mPendingMO +
                " ringing " + ringingCall.isRinging() +
                " fgalive " + foregroundCall.getState().isAlive() +
                " bgalive " + backgroundCall.getState().isAlive());

        ret = (serviceState != ServiceState.STATE_OUT_OF_SERVICE)
                && mPendingMO == null
                && !ringingCall.isRinging()
                && (!foregroundCall.getState().isAlive()
                || !backgroundCall.getState().isAlive());
        return ret;
    }

    boolean canTransfer() {
        return foregroundCall.getState() == ImsCall.State.ACTIVE
                && backgroundCall.getState() == ImsCall.State.HOLDING;
    }

    private void clearDisconnected() {
        ringingCall.clearDisconnected();
        foregroundCall.clearDisconnected();
        backgroundCall.clearDisconnected();

        updatePhoneState();
        phone.notifyPreciseCallStateChanged();
    }

    /**
     * Obtain a message to use for signalling "invoke getCurrentCalls() when
     * this operation and all other pending operations are complete
     */
    private Message obtainCompleteMessage() {
        return obtainCompleteMessage(EVENT_OPERATION_COMPLETE);
    }

    /**
     * Obtain a message to use for signalling "invoke getCurrentCalls() when
     * this operation and all other pending operations are complete
     */
    private Message obtainCompleteMessage(int what) {
        pendingOperations++;
        if (mUsePollingForCallStatus) {
            lastRelevantPoll = null;
            needsPoll = true;

            Log.d(LOG_TAG, "obtainCompleteMessage: pendingOperations=" +
                    pendingOperations + ", needsPoll=" + needsPoll);
        }
        return obtainMessage(what);
    }

    private void operationComplete() {
        pendingOperations--;

        Log.d(LOG_TAG, "operationComplete: pendingOperations=" +
                pendingOperations + ", needsPoll=" + needsPoll);

        if (pendingOperations == 0 && needsPoll) {
            lastRelevantPoll = obtainMessage(EVENT_POLL_CALLS_RESULT);
            cm.getCurrentCalls(lastRelevantPoll);
        } else if (pendingOperations < 0) {
            // this should never happen
            Log.e(LOG_TAG, "GsmCallTracker.pendingOperations < 0");
            pendingOperations = 0;
        }
    }

    /**
     * Called from ImsPhone
     */
    void setMute(boolean mute) {
        if (desiredMute != mute && foregroundCall.getState().isAlive()) {
            desiredMute = mute;
            cm.setMute(desiredMute, null);
        }
    }

    boolean getMute() {
        return desiredMute;
    }
}

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

    static final int MAX_CONNECTIONS = 1;

    private boolean mUsePollingForCallStatus = true;
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
            mCi = phone.mCi;
        } else {
            throw new IllegalArgumentException("imsPhone");
        }
    }

    public void dispose() {
        mCi.unregisterForCallStateChanged(this);
        mCi.unregisterForOn(this);
        mCi.unregisterForNotAvailable(this);

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
                || !(foregroundCall.isIdle())) {
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

        Log.d(LOG_TAG, "what: " + msg.what);

        switch (msg.what)
        {
            case ImsCommandsInterface.VOIP_REG_STATE:
                if (phone.getServiceState().getState() != msg.arg1) {
                    Log.i(LOG_TAG, "Service state " + msg.arg1);
                    phone.getServiceState().setState(msg.arg1);

                    if (msg.arg1 == ServiceState.STATE_IN_SERVICE) {
                        CallManager.getInstance().unregisterPhone(phone.getParentPhone());
                        CallManager.getInstance().registerPhone(phone);
                    }
                    else {
                        CallManager.getInstance().unregisterPhone(phone);
                        CallManager.getInstance().registerPhone(phone.getParentPhone());
                    }
                }
                break;

            case ImsCommandsInterface.VOIP_STATE:
                switch (msg.arg1)
                {
                    case ImsCommandsInterface.VOIP_STATE_INCOMING: {
                        Log.i(LOG_TAG, "VOIP_STATE_INCOMING");
                        ringingCall.setState(ImsCall.State.INCOMING);
                        int callId = (int) msg.arg2;
                        Log.i(LOG_TAG, "VOIP_STATE_INCOMING, callId = " + callId);
                        // TODO: replace null by remote address in ImsConnection
                        // ctor
                        pendingMT = new ImsConnection(phone.getContext(), null, this, ringingCall,
                                true, callId);
                        updatePhoneState();
                        phone.notifyNewRingingConnection(pendingMT);
                    }
                        break;
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

                            // mPendingMO.setIMSIndex();
                            foregroundCall.setState(ImsCall.State.ALERTING);
                            phone.notifyPreciseCallStateChanged();
                        }
                        break;
                    case ImsCommandsInterface.VOIP_STATE_ACTIVE:
                        int callId = (int) msg.arg2;
                        if (mPendingMO != null) {
                            // Call has been accepted by peer
                            Log.i(LOG_TAG, "VOIP_STATE_ACTIVE MO, callId = " + callId);

                            mConnections[0] = mPendingMO;
                            mConnections[0].callId = callId;
                            mPendingMO = null;

                            mConnections[0].onConnectedInOrOut();

                            foregroundCall.setState(ImsCall.State.ACTIVE);

                            updatePhoneState();
                            phone.notifyPreciseCallStateChanged();
                        }
                        else if (pendingMT != null) {
                            Log.i(LOG_TAG, "VOIP_STATE_ACTIVE MT, callId = " + callId);
                            foregroundCall.clearDisconnected();

                            // Call accepted locally : enable foreground call
                            mConnections[0] = new ImsConnection(phone.getContext(),
                                    pendingMT.getAddress(),
                                    this,
                                    foregroundCall,
                                    true,
                                    callId);
                            mConnections[0].onConnectedInOrOut();

                            foregroundCall.setState(ImsCall.State.ACTIVE);

                            // Disable ringing call
                            ringingCall.detach(pendingMT);
                            pendingMT = null;

                            updatePhoneState();
                            phone.notifyPreciseCallStateChanged();
                        }

                        break;
                    case ImsCommandsInterface.VOIP_STATE_DISCONNECTED:
                        Log.i(LOG_TAG, "VOIP_STATE_DISCONNECTED");

                        if (mPendingMO != null) {
                            mPendingMO.onRemoteDisconnect();
                            mCi.hangupConnection(mPendingMO.callId, obtainCompleteMessage());
                            mPendingMO = null;
                        }
                        else {
                            mConnections[0].onRemoteDisconnect();
                        }

                        foregroundCall.setState(ImsCall.State.DISCONNECTED);

                        updatePhoneState();
                        phone.notifyPreciseCallStateChanged();

                        break;
                    case ImsCommandsInterface.VOIP_STATE_DESTROYED:
                        Log.i(LOG_TAG, "VOIP_STATE_DESTROYED");
                        // Incoming call has been rejected locally
                        if (pendingMT != null) {

                            pendingMT.onLocalDisconnect();

                            ringingCall.detach(pendingMT);
                            pendingMT = null;

                            updatePhoneState();
                            phone.notifyPreciseCallStateChanged();
                        }
                        else if (mPendingMO != null) {
                            mPendingMO.onRemoteDisconnect();
                            mCi.hangupConnection(mPendingMO.callId, obtainCompleteMessage());
                            mPendingMO = null;
                            updatePhoneState();
                            phone.notifyPreciseCallStateChanged();
                        }
                        break;
                    default:
                        break;
                }
                break;

            case EVENT_POLL_CALLS_RESULT:
                ar = (AsyncResult) msg.obj;

                if (msg == mLastRelevantPoll) {
                    Log.d(LOG_TAG, "EVENT_POLL_CALL_RESULT: set needsPoll=F");
                    mNeedsPoll = false;
                    mLastRelevantPoll = null;
                    handlePollCalls((AsyncResult) msg.obj);
                }
                break;

            case EVENT_OPERATION_COMPLETE:
                ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    mNeedsPoll = true;
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
            mCi.dial(mPendingMO.address, clirMode, uusInfo, obtainCompleteMessage());
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
            int callId = ringingCall.getCallId();
            Log.d(LOG_TAG, "acceptCall, callId = " + callId);

            setMute(false);

            Message msg = obtainCompleteMessage();

            msg.arg1 = callId;

            mCi.acceptCall(msg);

        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    void rejectCall() throws CallStateException {
        if (ringingCall.getState().isRinging()) {
            int callId = ringingCall.getCallId();
            Log.d(LOG_TAG, "rejectCall, callId = " + callId);

            Message msg = obtainCompleteMessage();

            msg.arg1 = callId;

            mCi.rejectCall(msg);
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

        // Close connection even if MO call has not been not connected yet
        mCi.hangupConnection(conn.callId, obtainCompleteMessage());

        conn.onHangupLocal();
    }

    void separate(ImsConnection conn) throws CallStateException {
        if (conn.owner != this) {
            throw new CallStateException("ImsConnection " + conn
                    + "does not belong to ImsCallTracker " + this);
        }
        mCi.separateConnection(conn.callId,
                    obtainCompleteMessage(EVENT_SEPARATE_RESULT));
    }

    void hangup(ImsCall call) throws CallStateException {
        if (call.getConnections().size() == 0) {
            throw new CallStateException("no connections in call");
        }
        Log.i(LOG_TAG, "hangup " + call);

        if (call == ringingCall) {
            Log.i(LOG_TAG, "(ringing) hangup waiting or background");
            mCi.hangupWaitingOrBackground(obtainCompleteMessage());
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
        mCi.hangupWaitingOrBackground(obtainCompleteMessage());
    }

    void hangupForegroundResumeBackground() {
        if (Phone.DEBUG_PHONE)
            log("hangupForegroundResumeBackground");
        mCi.hangupForegroundResumeBackground(obtainCompleteMessage());
    }

    void hangupAllConnections(ImsCall call) throws CallStateException {
        int count = call.getConnections().size();
        for (int i = 0; i < count; i++) {
            ImsConnection cn = (ImsConnection) call.getConnections().get(i);
            mCi.hangupConnection(cn.callId, obtainCompleteMessage());
        }
    }

    ImsConnection getConnectionByIndex(ImsCall call, int index)
            throws CallStateException {
        int count = call.getConnections().size();
        for (int i = 0; i < count; i++) {
            ImsConnection cn = (ImsConnection) call.getConnections().get(i);
            if (cn.callId == index) {
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

        ret = serviceState != ServiceState.STATE_OUT_OF_SERVICE
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
        mPendingOperations++;
        if (mUsePollingForCallStatus) {
            mLastRelevantPoll = null;
            mNeedsPoll = true;

            Log.d(LOG_TAG, "obtainCompleteMessage: pendingOperations=" +
                    mPendingOperations + ", needsPoll=" + mNeedsPoll);
        }
        return obtainMessage(what);
    }

    private void operationComplete() {
        mPendingOperations--;

        Log.d(LOG_TAG, "operationComplete: pendingOperations=" +
                mPendingOperations + ", needsPoll=" + mNeedsPoll);

        if (mPendingOperations == 0 && mNeedsPoll) {
            mLastRelevantPoll = obtainMessage(EVENT_POLL_CALLS_RESULT);
            mCi.getCurrentCalls(mLastRelevantPoll);
        } else if (mPendingOperations < 0) {
            // this should never happen
            Log.e(LOG_TAG, "GsmCallTracker.pendingOperations < 0");
            mPendingOperations = 0;
        }
    }

    /**
     * Called from ImsPhone
     */
    void setMute(boolean mute) {
        if (desiredMute != mute && foregroundCall.getState().isAlive()) {
            desiredMute = mute;
            mCi.setMute(desiredMute, null);
        }
    }

    boolean getMute() {
        return desiredMute;
    }
}

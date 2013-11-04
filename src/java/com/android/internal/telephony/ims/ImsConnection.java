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
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.UUSInfo;

public class ImsConnection extends Connection {
    private static final String LOG_TAG = "ImsConnection";

    ImsCallTracker owner = null;
    String address = null;
    String dialString = null; // outgoing calls only
    String postDialString = null; // outgoing calls only
    int index = 0;
    DisconnectCause cause = DisconnectCause.NOT_DISCONNECTED;

    private ImsCall mParent = null;
    private boolean mIsIncoming = false;
    private boolean mDisconnected = false;

    /*
     * These time/timespan values are based on System.currentTimeMillis(), i.e.,
     * "wall clock" time.
     */
    private long mCreateTime = 0;
    private long mConnectTime = 0;
    private long mDisconnectTime = 0;

    /*
     * These time/timespan values are based on SystemClock.elapsedRealTime(),
     * i.e., time since boot. They are appropriate for comparison and
     * calculating deltas.
     */
    private long mConnectTimeReal = 0;
    private long mDuration = 0;

    private PostDialState mPostDialState = PostDialState.NOT_STARTED;
    private int mNumberPresentation = PhoneConstants.PRESENTATION_ALLOWED;
    private UUSInfo mUusInfo = null;

    private PowerManager.WakeLock mPartialWakeLock;

    private Handler h = null;

    private static final int EVENT_DTMF_DONE = 1;
    private static final int EVENT_PAUSE_DONE = 2;
    private static final int EVENT_NEXT_POST_DIAL = 3;
    private static final int EVENT_WAKE_LOCK_TIMEOUT = 4;

    private class MyHandler extends Handler {
        MyHandler(Looper l) {
            super(l);
        }

        public void handleMessage(Message msg) {

            switch (msg.what) {
                case EVENT_NEXT_POST_DIAL:
                case EVENT_DTMF_DONE:
                case EVENT_PAUSE_DONE:
                    break;
                case EVENT_WAKE_LOCK_TIMEOUT:
                    releaseWakeLock();
                    break;
            }
        }
    }

    ImsConnection(Context context, String dialString, ImsCallTracker ct, ImsCall parent,
            boolean incoming) {
        createWakeLock(context);
        acquireWakeLock();

        owner = ct;
        h = new MyHandler(owner.getLooper());

        this.dialString = dialString;

        this.address = PhoneNumberUtils.extractNetworkPortionAlt(dialString);
        this.postDialString = PhoneNumberUtils.extractPostDialPortion(dialString);

        index = -1;

        mIsIncoming = incoming;
        mCreateTime = System.currentTimeMillis();

        mParent = parent;

        if (incoming == false) {
            mParent.attachFake(this, ImsCall.State.DIALING);
        }
        else {
            mParent.attachFake(this, ImsCall.State.INCOMING);
        }
    }

    @Override
    protected void finalize()
    {
        /**
         * It is understood that This finalizer is not guaranteed to be called
         * and the release lock call is here just in case there is some path
         * that doesn't call onDisconnect and or onConnectedInOrOut.
         */
        if (mPartialWakeLock.isHeld()) {
            Log.e(LOG_TAG, "UNEXPECTED; mPartialWakeLock is held when finalizing.");
        }
        releaseWakeLock();
    }

    private void createWakeLock(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
    }

    private void acquireWakeLock() {
        mPartialWakeLock.acquire();
    }

    private void releaseWakeLock() {
        synchronized (mPartialWakeLock) {
            if (mPartialWakeLock.isHeld()) {
                mPartialWakeLock.release();
            }
        }
    }

    @Override
    public Call getCall() {
        return mParent;
    }

    @Override
    public long getCreateTime() {
        return mCreateTime;
    }

    @Override
    public long getConnectTime() {
        return mConnectTime;
    }

    @Override
    public long getDisconnectTime() {
        return mDisconnectTime;
    }

    @Override
    public long getDurationMillis() {
        if (mConnectTimeReal == 0) {
            return 0;
        } else if (mDuration == 0) {
            return SystemClock.elapsedRealtime() - mConnectTimeReal;
        } else {
            return mDuration;
        }
    }

    @Override
    public long getHoldDurationMillis() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public DisconnectCause getDisconnectCause() {
        return cause;
    }

    @Override
    public boolean isIncoming() {
        return mIsIncoming;
    }

    public ImsCall.State getState() {
        if (mDisconnected) {
            return ImsCall.State.DISCONNECTED;
        } else {
            return super.getState();
        }
    }

    @Override
    public void hangup() throws CallStateException {
        if (!mDisconnected) {
            owner.hangup(this);
        } else {
            throw new CallStateException("mDisconnected");
        }
    }

    DisconnectCause disconnectCauseFromCode(int causeCode) {
        return DisconnectCause.ERROR_UNSPECIFIED;
    }

    void onLocalDisconnect() {
        onDisconnect(DisconnectCause.LOCAL);
    }

    void onRemoteDisconnect() {
        onDisconnect(DisconnectCause.NORMAL);
    }

    void onRemoteDisconnect(int causeCode) {
        onDisconnect(disconnectCauseFromCode(causeCode));
    }

    void onDisconnect(DisconnectCause cause) {
        this.cause = cause;

        if (!mDisconnected) {
            index = -1;

            mDisconnectTime = System.currentTimeMillis();
            mDuration = SystemClock.elapsedRealtime() - mConnectTimeReal;
            mDisconnected = true;

            Log.d(LOG_TAG, "onDisconnect: cause=" + cause);

            // Send EVENT_DISCONNECT to CallManager
            owner.phone.notifyDisconnect(this);

            if (mParent != null) {
                mParent.connectionDisconnected(this);
            }
        }
        releaseWakeLock();
    }

    @Override
    public void separate() throws CallStateException {
        if (!mDisconnected) {
            owner.separate(this);
        } else {
            throw new CallStateException("mDisconnected");
        }
    }

    @Override
    public PostDialState getPostDialState() {
        return mPostDialState;
    }

    @Override
    public void proceedAfterWaitChar() {
        // TODO Auto-generated method stub
    }

    @Override
    public void cancelPostDial() {
        // TODO Auto-generated method stub
    }

    int getIMSIndex() throws CallStateException {
        if (index >= 0) {
            return index + 1;
        } else {
            throw new CallStateException("IMS index not yet assigned");
        }
    }

    public void setIMSIndex() {
        index++;
    }

    void onConnectedInOrOut() {
        mConnectTime = System.currentTimeMillis();
        mConnectTimeReal = SystemClock.elapsedRealtime();
        mDuration = 0;

        Log.d(LOG_TAG, "onConnectedInOrOut: connectTime= " + mConnectTime);

        releaseWakeLock();
    }

    void onHangupLocal() {
        cause = DisconnectCause.LOCAL;
    }

    @Override
    public int getNumberPresentation() {
        return mNumberPresentation;
    }

    @Override
    public UUSInfo getUUSInfo() {
        return mUusInfo;
    }

    @Override
    public void proceedAfterWildChar(String str) {
    }

    @Override
    public String getRemainingPostDialString() {
        return null;
    }

    @Override
    public String getAddress() {
        return address;
    }
}

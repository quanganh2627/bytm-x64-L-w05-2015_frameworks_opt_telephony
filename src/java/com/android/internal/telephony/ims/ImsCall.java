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

import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;

import java.util.ArrayList;
import java.util.List;

public class ImsCall extends Call {
    private final String LOG_TAG = "ImsCall";

    private ArrayList<Connection> mConnections = new ArrayList<Connection>();
    private ImsCallTracker mOwner = null;

    ImsCall(ImsCallTracker owner) {
        if (owner != null) {
            mOwner = owner;
            mState = State.IDLE;
        } else {
            throw new IllegalArgumentException("owner");
        }
    }

    public void dispose() {
    }

    public List<Connection> getConnections() {
        return mConnections;
    }

    @Override
    public Phone getPhone() {
        return mOwner.phone;
    }

    @Override
    public boolean isMultiparty() {
        return mConnections.size() > 1;
    }

    public void hangup() throws CallStateException {
        Log.d(LOG_TAG, "hangup  " + mOwner);
        mOwner.hangup(this);
    }

    void attachFake(Connection conn, State mState) {
        mConnections.add(conn);
        this.mState = mState;
    }

    void setState(State newState) {
        this.mState = newState;
    }

    /**
     * Called when this Call is being hung up locally (eg, user pressed "end")
     * Note that at this point, the hangup request has been dispatched to the
     * radio but no response has yet been received so update() has not yet been
     * called
     */
    void onHangupLocal() {
        for (int i = 0, s = mConnections.size(); i < s; i++) {
            ImsConnection cn = (ImsConnection) mConnections.get(i);
            cn.onHangupLocal();
        }
        mState = State.DISCONNECTING;
    }

    /**
     * Called when it's time to clean up disconnected Connection objects
     */
    void clearDisconnected() {
        for (int i = mConnections.size() - 1; i >= 0; i--) {
            ImsConnection cn = (ImsConnection) mConnections.get(i);

            if (cn.getState() == State.DISCONNECTED) {
                mConnections.remove(i);
            }
        }
        if (mConnections.size() == 0) {
            mState = State.IDLE;
        }
    }

    /**
     * Called by ImsConnection when it has disconnected
     */
    void connectionDisconnected(ImsConnection conn) {
        if (mState != State.DISCONNECTED) {
            /* If only disconnected mConnections remain, we are disconnected */

            boolean hasOnlyDisconnectedConnections = true;

            for (int i = 0, s = mConnections.size(); i < s; i++) {
                if (mConnections.get(i).getState() != State.DISCONNECTED) {
                    hasOnlyDisconnectedConnections = false;
                    break;
                }
            }
            if (hasOnlyDisconnectedConnections) {
                mState = State.DISCONNECTED;
            }
        }
    }

    void detach(ImsConnection conn) {
        mConnections.remove(conn);

        if (mConnections.size() == 0) {
            mState = State.IDLE;
        }
    }
}

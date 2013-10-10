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

import android.os.RemoteException;
import android.util.Log;

import com.intel.imsservices.imsstackinterface.IImsLinkFwInterface;

public class ImsLinkFwImpl extends IImsLinkFwInterface.Stub {

    private static final String LOG_TAG = "ImsLinkFwImpl";
    /*
     * Valid call id range for the modem is [1 to 13].
     * We shall not exceed this range in any case otherwise
     * CS fallback will not be possible when LTE coverage is
     * lost.
     * In addition to that, we use only range [5 to 13] here
     * to easily distinguish a call id that was generated by
     * ImsLinkFwImpl or by the modem. This is just for debuggability
     * purpose.
     */
    private static final int MIN_CALL_ID = 5;
    private static final int MAX_CALL_ID = 13;

    private ImsPhone mPhone = null;
    /*
     * Bitmask storing used ids.
     * Ex: a 1 on bit 6 means call id 6 is used.
     */
    private int mCallIdMap = 0;
    private Object mCallIdMapLock = new Object();

    public ImsLinkFwImpl(ImsPhone phone) {
        if (phone == null) {
            throw new IllegalArgumentException("phone");
        }
        mPhone = phone;
    }

    @Override
    public int allocateCallId() {
        int ret = -1;
        synchronized (mCallIdMapLock) {
            for (int i = MIN_CALL_ID; i <= MAX_CALL_ID; i++) {
                if (0 == (mCallIdMap & (1 << i))) {
                    mCallIdMap |= (1 << i);
                    ret = i;
                    break;
                }
            }
        }
        if (ret == -1) {
            Log.e(LOG_TAG, "No call id available");
        }
        return ret;
    }

    @Override
    public boolean isCsCallOngoing() {
        return (!mPhone.getParentPhone().getBackgroundCall().isIdle() ||
                !mPhone.getParentPhone().getForegroundCall().isIdle() ||
                !mPhone.getParentPhone().getRingingCall().isIdle());
    }

    @Override
    public void releaseCallId(int callId) {
        if (callId >= MIN_CALL_ID && callId <= MAX_CALL_ID) {
            synchronized (mCallIdMapLock) {
                mCallIdMap &= ~(1 << callId);
            }
        } else {
            Log.e(LOG_TAG, "Invalid call id: " + callId);
        }
    }
}

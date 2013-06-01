/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneNotifier;

public abstract class ImsPhoneBase extends PhoneBase {

    private static final String LOG_TAG = "ImsPhoneBase";

    protected Phone mParentPhone = null;

    protected ImsPhoneBase(String name, PhoneNotifier notifier,
            Context context, CommandsInterface ci) {
        super(name, notifier, context, ci);
    }

    public void setParentPhone(Phone phone) {

        if (phone == null) {
            throw new IllegalArgumentException("phone");
        }
        mParentPhone = phone;
        Log.d(LOG_TAG, "Setting parent phone = " + phone.getPhoneName());

        Log.d(LOG_TAG,
                "Parent Phone IMEI " + mParentPhone.getImei() + " IMSI "
                        + mParentPhone.getSubscriberId() + " MSISDN "
                        + mParentPhone.getMsisdn());
    }

    public Phone getParentPhone() {
        return mParentPhone;
    }

    /**
     * Activate the emergency pdp
     * @param response to send back when the pdp is activated
     */
    public abstract void startEmergencyConnectivity(Message response);

    /**
     * Deactivate the emergency pdp
     */
    public abstract void stopEmergencyConnectivity(Message response);

    /**
     * To be notified or not about the changes on the IMS Registration status
     */
    public abstract void registerForImsRegStatusChanges(Handler h, int what, Object obj);
    public abstract void unregisterForImsRegStatusChanges(Handler h);

    /**
     * To know the actual IMS Registration status
     * Possible values are :
     *    - ServiceState.STATE_IN_SERVICE when IMS is connected
     *    - ServiceState.STATE_OUT_OF_SERVICE otherwise
     */
    public abstract int getImsRegStatus();

    /**
     * Query the Anonymous Call Reject state
     * @param response to send back when the ACR status is read
     */
    public abstract void getACR(Message onComplete);

    /**
     * Set the Anonymous Call Reject state
     * @param boolean to activate or deactivate ACR
     * @param response to send back when the ACR status is written
     */
    public abstract void setACR(boolean setState, Message onComplete);
}

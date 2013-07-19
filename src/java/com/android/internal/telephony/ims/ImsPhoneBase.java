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
}

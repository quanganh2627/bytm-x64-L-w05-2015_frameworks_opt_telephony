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

package com.android.internal.telephony.gsm;

import android.content.Context;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneNotifier;

/**
 * {@hide}
 */
public class GsmLtePhone extends GSMPhone {
    static final String LOG_TAG = "GsmLte";

    public GsmLtePhone(Context context, CommandsInterface ci, PhoneNotifier notifier) {
        this(context, ci, notifier, false);
    }

    public GsmLtePhone(Context context, CommandsInterface ci,
            PhoneNotifier notifier, boolean unitTestMode) {
        super(context, ci, notifier, unitTestMode);
    }

    @Override
    protected void initSst() {
        mSST = new GsmLteServiceStateTracker(this);
    }

    @Override
    public void log(String log) {
        Log.d(LOG_TAG, log);
    }
}

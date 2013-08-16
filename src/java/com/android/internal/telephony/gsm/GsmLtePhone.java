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
import android.os.Message;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.ims.ImsCommandsInterface;
import com.android.internal.telephony.ims.ImsPhone;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * {@hide}
 */
public class GsmLtePhone extends GSMPhone {
    static final String LOG_TAG = "GsmLte";
    static final String IMS_ENABLED_PROPERTY = "persist.ims_support";

    private ImsPhone mImsPhone = null;

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

    @Override
    public void dispose() {
        destroyImsPhone();
        super.dispose();
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_REGISTERED_TO_NETWORK:
                super.handleMessage(msg);
                createImsPhone();
                break;
            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                destroyImsPhone();
                super.handleMessage(msg);
                break;
            default:
                super.handleMessage(msg);
        }
    }

    private void createImsPhone() {
        if (SystemProperties.getInt(GsmLtePhone.IMS_ENABLED_PROPERTY, 0) != 0) {
            if (mImsPhone == null) {
                Log.d(LOG_TAG, "Creating ImsPhone");

                mImsPhone = new ImsPhone(getContext(),
                        new ImsCommandsInterface(getContext(), null),
                        mNotifier,
                        getUnitTestMode());

                mImsPhone.init(this);
            } else {
                Log.d(LOG_TAG, "ImsPhone already created");
            }
        }
    }

    private void destroyImsPhone() {
        if (SystemProperties.getInt(GsmLtePhone.IMS_ENABLED_PROPERTY, 0) != 0) {
            if (mImsPhone != null) {
                Log.d(LOG_TAG, "Destroying ImsPhone");

                mImsPhone.dispose();
                mImsPhone = null;
            } else {
                Log.d(LOG_TAG, "ImsPhone already destroyed");
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("GsmLtePhone extends:");
        super.dump(fd, pw, args);
        pw.println(" mImsPhone=" + mImsPhone);
    }
}

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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.intel.imsservices.imsstackinterface.IImsLinkCallbacks;
import com.intel.imsservices.imsstackinterface.IImsLinkInterface;

public final class ImsLinkManager extends IImsLinkCallbacks.Stub {
    private final String LOG_TAG = "ImsLinkManager";
    private final String START_IMS_ACTION = "com.intel.imsstack.action.STARTIMS";
    private final String STOP_IMS_ACTION = "com.intel.imsstack.action.STOPIMS";

    public static final int REG_STATE = 0x2001;

    private final Object mImsLinkInterfaceLock = new Object();
    private IImsLinkInterface mLinkInterface = null;
    private Handler mHdlr = null;
    private Context mCtx = null;
    private ImsPhone mPhone = null;

    private static ImsLinkManager sInstance = null;

    private ServiceConnection mLinkServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.v(LOG_TAG, "ImsLink service connected");
            synchronized (ImsLinkManager.this.mImsLinkInterfaceLock) {
                ImsLinkManager.this.mLinkInterface = IImsLinkInterface.Stub.asInterface(service);

                if (ImsLinkManager.this.mLinkInterface != null) {
                    initImsCallbacks();
                    initImsFwInterface();
                    initSimAccessApi();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.v(LOG_TAG, "ImsLink service disconnected");
            synchronized (ImsLinkManager.this.mImsLinkInterfaceLock) {
                ImsLinkManager.this.mLinkInterface = null;
            }
        }
    };

    private void initImsCallbacks() {
        try {
            Log.i(LOG_TAG, "Registering callbacks to link interface");
            this.mLinkInterface
                    .registerImsCallbacks(ImsLinkManager.this);
        } catch (RemoteException ex) {
            Log.e(LOG_TAG, "Could not register callbacks");
        }
    }

    private void initImsFwInterface() {
        try {
            Log.i(LOG_TAG, "Setting ImsLinkFwImpl interface to link interface");
            ImsLinkManager.this.mLinkInterface.setImsFwInterface(new ImsLinkFwImpl(mPhone));
        } catch (RemoteException ex) {
            Log.e(LOG_TAG, "Could not set ImsLinkFwImpl interface to link interface");
        }
    }

    private void initSimAccessApi() {
    }

    private ImsLinkManager(ImsPhone phone, Context context, Handler handler) {
        if (context == null) {
            throw new IllegalArgumentException("context");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler");
        }
        if (phone == null) {
            throw new IllegalArgumentException("phone");
        }

        mHdlr = handler;
        mCtx = context;
        mPhone = phone;
        Log.d(LOG_TAG, "binding " + IImsLinkInterface.class.getName());
        if (!context.bindService(new Intent(IImsLinkInterface.class.getName()),
                mLinkServiceConnection, Context.BIND_AUTO_CREATE)) {
            Log.e(LOG_TAG, "Could not bind to service");
        }
    }

    protected void finalize() {
        mCtx.unbindService(this.mLinkServiceConnection);
    }

    public void startImsStack() {
        // Start IMS service by sending STARTIMS intent

        Intent startIntent = new Intent(START_IMS_ACTION);
        mCtx.sendBroadcast(startIntent);
        Log.i(LOG_TAG, "Starting IMS stack");
    }

    public void stopImsStack() {
        // Stop IMS service by sending STOPIMS intent

        Intent stopIntent = new Intent(STOP_IMS_ACTION);
        mCtx.sendBroadcast(stopIntent);
        Log.i(LOG_TAG, "Stopping IMS stack");
    }

    public IImsLinkInterface getLinkInterface() {
        return mLinkInterface;
    }

    public static ImsLinkManager getInstance(ImsPhone phone, Context context, Handler handler) {
        if (sInstance == null)
            sInstance = new ImsLinkManager(phone, context, handler);
        return sInstance;
    }

    @Override
    public void onServiceRetryRequest(int callId) {
        Log.d(LOG_TAG, String.format("IMS service retry request for id %d", callId));
    }

    @Override
    public void onImsRegistrationStatus(int registrationStatus) {
        Log.d(LOG_TAG, String.format("IMS registration status = %d", registrationStatus));
        // TODO: interface to be removed from AIDLs
    }

    @Override
    public void onReceiveDtmf(char c) {
        Log.d(LOG_TAG, String.format("Received IMS DTMF %c", c));
    }
}

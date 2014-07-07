/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.CellInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.ITelephonyRegistry2;
import com.android.internal.telephony.TelephonyConstants;

import java.util.List;

/**
 * broadcast intents
 */
public class DefaultDualPhoneNotifier implements PhoneNotifier {

    static final String LOG_TAG = "GSM";
    private static final boolean DBG = false;
    private ITelephonyRegistry mRegistry;
    private ITelephonyRegistry2 mRegistry2;

    /*package*/
    DefaultDualPhoneNotifier() {
        mRegistry = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
        if (TelephonyConstants.IS_DSDS) {
            mRegistry2 = ITelephonyRegistry2.Stub.asInterface(ServiceManager.getService(
                        "telephony.registry2"));
        }
    }

    private boolean isPrimaryPhone(Phone phone) {
        /* In case there is phone which has no name. */
        return !phone.getPhoneName().equals("GSM2");
    }

    public void notifyPhoneState(Phone sender) {
        Call ringingCall = sender.getRingingCall();
        String incomingNumber = "";
        if (ringingCall != null && ringingCall.getEarliestConnection() != null) {
            incomingNumber = ringingCall.getEarliestConnection().getAddress();
        }
        try {
            if (isPrimaryPhone(sender)) {
                mRegistry.notifyCallState(DefaultPhoneNotifier.convertCallState(sender.getState()), incomingNumber);
            } else {
                mRegistry2.notifyCallState(DefaultPhoneNotifier.convertCallState(sender.getState()), incomingNumber);
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyServiceState(Phone sender) {
        ServiceState ss = sender.getServiceState();
        if (ss == null) {
            ss = new ServiceState();
            ss.setStateOutOfService();
        }
        try {
            if (isPrimaryPhone(sender)) {
                mRegistry.notifyServiceState(ss);
            } else {
                mRegistry2.notifyServiceState(ss);
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifySignalStrength(Phone sender) {
        try {
            if (isPrimaryPhone(sender)) {
                mRegistry.notifySignalStrength(sender.getSignalStrength());
            } else {
                mRegistry2.notifySignalStrength(sender.getSignalStrength());
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyMessageWaitingChanged(Phone sender) {
        try {
            if (isPrimaryPhone(sender)) {
                mRegistry.notifyMessageWaitingChanged(sender.getMessageWaitingIndicator());
            } else {
                mRegistry2.notifyMessageWaitingChanged(sender.getMessageWaitingIndicator());
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyCallForwardingChanged(Phone sender) {
        try {
            if (isPrimaryPhone(sender)) {
                mRegistry.notifyCallForwardingChanged(sender.getCallForwardingIndicator());
            } else {
                mRegistry2.notifyCallForwardingChanged(sender.getCallForwardingIndicator());
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyDataActivity(Phone sender) {
        try {
            if (isPrimaryPhone(sender)) {
                mRegistry.notifyDataActivity(DefaultPhoneNotifier.convertDataActivityState(sender.getDataActivityState()));
            } else {
                mRegistry2.notifyDataActivity(DefaultPhoneNotifier.convertDataActivityState(sender.getDataActivityState()));
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyDataConnection(Phone sender, String reason, String apnType,
            PhoneConstants.DataState state) {
        doNotifyDataConnection(sender, reason, apnType, state);
    }

    private void doNotifyDataConnection(Phone sender, String reason, String apnType,
            PhoneConstants.DataState state) {
        // TODO
        // use apnType as the key to which connection we're talking about.
        // pass apnType back up to fetch particular for this one.
        TelephonyManager telephony = TelephonyManager.getDefault();
        TelephonyManager telephony2 = TelephonyManager.get2ndTm();
        LinkProperties linkProperties = null;
        LinkCapabilities linkCapabilities = null;
        boolean roaming = false;

        if (state == PhoneConstants.DataState.CONNECTED) {
            linkProperties = sender.getLinkProperties(apnType);
            linkCapabilities = sender.getLinkCapabilities(apnType);
        }
        ServiceState ss = sender.getServiceState();
        if (ss != null) roaming = ss.getRoaming();

        try {
            if (isPrimaryPhone(sender)) {
                mRegistry.notifyDataConnection(
                        DefaultPhoneNotifier.convertDataState(state),
                        sender.isDataConnectivityPossible(apnType), reason,
                        sender.getActiveApnHost(apnType),
                        apnType,
                        linkProperties,
                        linkCapabilities,
                        ((telephony!=null) ? telephony.getNetworkType() :
                         TelephonyManager.NETWORK_TYPE_UNKNOWN),
                        roaming);
            } else {
                mRegistry2.notifyDataConnection(
                        DefaultPhoneNotifier.convertDataState(state),
                        sender.isDataConnectivityPossible(apnType), reason,
                        sender.getActiveApnHost(apnType),
                        apnType,
                        linkProperties,
                        linkCapabilities,
                        ((telephony2!=null) ? telephony2.getNetworkType() :
                         TelephonyManager.NETWORK_TYPE_UNKNOWN),
                        roaming);

            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyDataConnectionFailed(Phone sender, String reason, String apnType) {
        try {
            if (isPrimaryPhone(sender)) {
                mRegistry.notifyDataConnectionFailed(reason, apnType);
            } else {
                mRegistry2.notifyDataConnectionFailed(reason, apnType);
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyCellLocation(Phone sender) {
        Bundle data = new Bundle();
        sender.getCellLocation().fillInNotifierBundle(data);
        try {
            if (isPrimaryPhone(sender)) {
                mRegistry.notifyCellLocation(data);
            } else {
                mRegistry2.notifyCellLocation(data);
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyCellInfo(Phone sender, List<CellInfo> cellInfo) {
        try {
            if (isPrimaryPhone(sender)) {
                mRegistry.notifyCellInfo(cellInfo);
            } else {
                mRegistry2.notifyCellInfo(cellInfo);
            }
        } catch (RemoteException ex) {

        }
    }

    public void notifyOtaspChanged(Phone sender, int otaspMode) {
        try {
            if (isPrimaryPhone(sender)) {
                mRegistry.notifyOtaspChanged(otaspMode);
            } else {
                mRegistry2.notifyOtaspChanged(otaspMode);
            }
         } catch (RemoteException ex) {
            // system process is dead
        }
    }

    private void log(String s) {
        Log.d(LOG_TAG, "[DualPhoneNotifier] " + s);
    }
}

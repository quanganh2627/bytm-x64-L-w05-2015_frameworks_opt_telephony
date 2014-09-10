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

import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LocalServerSocket;
import android.os.Looper;
import android.os.Build;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.cdma.CDMALTEPhone;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.gsm.OnlyOne3gSyncer;
import com.android.internal.telephony.sip.SipPhone;
import com.android.internal.telephony.sip.SipPhoneFactory;
import com.android.internal.telephony.uicc.UiccController;

/**
 * {@hide}
 */
public class PhoneFactory {
    static final String LOG_TAG = "PhoneFactory";
    static final int SOCKET_OPEN_RETRY_MILLIS = 2 * 1000;
    static final int SOCKET_OPEN_MAX_RETRY = 3;

    //***** Class Variables

    static private Phone sProxyPhone = null;
    static private Phone sProxyPhone2 = null;	
    static private CommandsInterface sCommandsInterface = null;
    static private CommandsInterface sCommandsInterface2 = null;

    static private DsdsDataSimManager sDataSimManager = null;

    static private OnlyOne3gSyncer syncer = null;

    static private boolean sMadeDefaults = false;
    static private PhoneNotifier sPhoneNotifier;
    static private Looper sLooper;
    static private Context sContext;

    static private int sPrimarySimId = ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A;
    static private int sRats[] = {1, 1};
    //***** Class Methods

    public static void makeDefaultPhones(Context context) {
        makeDefaultPhone(context);
    }

    /**
     * FIXME replace this with some other way of making these
     * instances
     */
    public static void makeDefaultPhone(Context context) {
        synchronized(Phone.class) {
            if (!sMadeDefaults) {
                sLooper = Looper.myLooper();
                sContext = context;

                if (sLooper == null) {
                    throw new RuntimeException(
                        "PhoneFactory.makeDefaultPhone must be called from Looper thread");
                }

                int retryCount = 0;
                for(;;) {
                    boolean hasException = false;
                    retryCount ++;

                    try {
                        // use UNIX domain socket to
                        // prevent subsequent initialization
                        new LocalServerSocket("com.android.internal.telephony");
                    } catch (java.io.IOException ex) {
                        hasException = true;
                    }

                    if ( !hasException ) {
                        break;
                    } else if (retryCount > SOCKET_OPEN_MAX_RETRY) {
                        throw new RuntimeException("PhoneFactory probably already running");
                    } else {
                        try {
                            Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
                        } catch (InterruptedException er) {
                        }
                    }
                }

                if (!TelephonyConstants.IS_DSDS) {
                    sPhoneNotifier = new DefaultPhoneNotifier();
                } else {
                    sPhoneNotifier = new DefaultDualPhoneNotifier();
                }

                // Get preferred network mode
                int preferredNetworkMode = RILConstants.PREFERRED_NETWORK_MODE;
                if (TelephonyManager.getLteOnCdmaModeStatic() == PhoneConstants.LTE_ON_CDMA_TRUE) {
                    preferredNetworkMode = Phone.NT_MODE_GLOBAL;
                }
                int networkMode = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK_MODE, preferredNetworkMode);
                Rlog.i(LOG_TAG, "Network Mode set to " + Integer.toString(networkMode));

                int cdmaSubscription = CdmaSubscriptionSourceManager.getDefault(context);
                Rlog.i(LOG_TAG, "Cdma Subscription set to " + cdmaSubscription);

                //reads the system properties and makes commandsinterface
               // sCommandsInterface = new RIL(context, networkMode, cdmaSubscription);

                // Instantiate UiccController so that all other classes can just call getInstance()
                //UiccController.make(context, sCommandsInterface);

                int phoneType = TelephonyManager.getPhoneType(networkMode);
                if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    Rlog.i(LOG_TAG, "Creating GSMPhone");
                    if (TelephonyConstants.IS_DSDS) {
                        setSimOnOffProperties();

                        int preferredNetwork2Mode = RILConstants.PREFERRED_NETWORK_MODE;
                        int network2Mode = Settings.Global.getInt(context.getContentResolver(),
                                Settings.Global.PREFERRED_NETWORK2_MODE, preferredNetwork2Mode);

                        if (networkMode != RILConstants.NETWORK_MODE_GSM_ONLY &&
                                  network2Mode != RILConstants.NETWORK_MODE_GSM_ONLY) {
                            // only one 3g policy
                            network2Mode = RILConstants.NETWORK_MODE_GSM_ONLY;
                            Settings.Global.putInt(context.getContentResolver(),
                                          Settings.Global.PREFERRED_NETWORK2_MODE, network2Mode);
                        }
                        Rlog.i(LOG_TAG, "Network Mode is set to " + Integer.toString(network2Mode));

                        sPrimarySimId = Settings.Global.getInt(
                                context.getContentResolver(), Settings.Global.MOBILE_DATA_SIM,
                                ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A);
                        boolean primaryOnSimA = (sPrimarySimId == ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A);
                        retrieveRatSettings();

                        Rlog.i(LOG_TAG, "creating RIL");
                        sCommandsInterface = new RIL(context, sRats[0], cdmaSubscription,
                                !primaryOnSimA);

                        Rlog.i(LOG_TAG, "creating RIL2");
                        sCommandsInterface2 = new RIL(context, sRats[1], cdmaSubscription,
                                primaryOnSimA);

                        // Instantiate UiccController so that all other classes can just call getInstance()
                        UiccController.make(context, sCommandsInterface);
                        UiccController.make2(context, sCommandsInterface2);

                        sProxyPhone = new PhoneProxy(new GSMPhone(context, sCommandsInterface, sPhoneNotifier));
                        Rlog.i(LOG_TAG, "created phone :" + sProxyPhone.getPhoneName()
                                + ",on socket " + ((RIL)sCommandsInterface).getSocketName());

                        sProxyPhone2 = new PhoneProxy(new GSMPhone(context, sCommandsInterface2, sPhoneNotifier, "GSM2"));
                        Rlog.i(LOG_TAG, "created phone2 :" + sProxyPhone2.getPhoneName()
                                + ",on socket " + ((RIL)sCommandsInterface2).getSocketName());
 
                        updateDataSimProperty(getDataSimId(sContext));
                        syncer = OnlyOne3gSyncer.getInstance();
                        syncer.setRat(sRats[0], sRats[1]);
                        syncer.registerRadios(((PhoneProxy)sProxyPhone).getActivePhone(),
                                ((PhoneProxy)sProxyPhone2).getActivePhone());
                        // now we can let them know each other
                        ((PhoneProxy)sProxyPhone).bridgeTheOtherPhone((PhoneProxy)sProxyPhone2);
                        ((PhoneProxy)sProxyPhone2).bridgeTheOtherPhone((PhoneProxy)sProxyPhone);
                        sDataSimManager = new DsdsDataSimManager();

                    } else {
                        sCommandsInterface = new RIL(context, networkMode, cdmaSubscription, false);
                        UiccController.make(context, sCommandsInterface);
                        sProxyPhone = new PhoneProxy(new GSMPhone(context, sCommandsInterface, sPhoneNotifier));
                        Rlog.i(LOG_TAG, "created phone:" + sProxyPhone.getPhoneName());
                    }
                } else if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
				    sCommandsInterface = new RIL(context, networkMode, cdmaSubscription, false);
                    // Instantiate UiccController so that all other classes can just call getInstance()
                    UiccController.make(context, sCommandsInterface);
                    switch (TelephonyManager.getLteOnCdmaModeStatic()) {
                        case PhoneConstants.LTE_ON_CDMA_TRUE:
                            Rlog.i(LOG_TAG, "Creating CDMALTEPhone");
                            sProxyPhone = new PhoneProxy(new CDMALTEPhone(context,
                                sCommandsInterface, sPhoneNotifier));
                            break;
                        case PhoneConstants.LTE_ON_CDMA_FALSE:
                        default:
                            Rlog.i(LOG_TAG, "Creating CDMAPhone");
                            sProxyPhone = new PhoneProxy(new CDMAPhone(context,
                                    sCommandsInterface, sPhoneNotifier));
                            break;
                    }
                }

                // Ensure that we have a default SMS app. Requesting the app with
                // updateIfNeeded set to true is enough to configure a default SMS app.
                ComponentName componentName =
                        SmsApplication.getDefaultSmsApplication(context, true /* updateIfNeeded */);
                String packageName = "NONE";
                if (componentName != null) {
                    packageName = componentName.getPackageName();
                }
                Rlog.i(LOG_TAG, "defaultSmsApplication: " + packageName);

                // Set up monitor to watch for changes to SMS packages
                SmsApplication.initSmsPackageMonitor(context);

                sMadeDefaults = true;
            }
        }
    }

     private static int getDataSimId(Context ctx) {
           return Settings.Global.getInt(ctx.getContentResolver(), 
                   Settings.Global.MOBILE_DATA_SIM, TelephonyConstants.DSDS_SLOT_1_ID);
    }

    static void retrieveRatSettings() {
        int networkMode = Settings.Global.getInt(sContext.getContentResolver(),
                Settings.Global.PREFERRED_NETWORK_MODE, RILConstants.PREFERRED_NETWORK_MODE);
        int network2Mode = Settings.Global.getInt(sContext.getContentResolver(),
                Settings.Global.PREFERRED_NETWORK2_MODE, RILConstants.PREFERRED_NETWORK_MODE);

        Rlog.i(LOG_TAG, "RATs expected:" + networkMode + "," + network2Mode);

        final boolean primaryOnSimA = (sPrimarySimId == ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A);
        sRats[0] = primaryOnSimA ? networkMode : network2Mode;
        sRats[1] = !primaryOnSimA ? networkMode : network2Mode;

        if (sRats[0] != RILConstants.NETWORK_MODE_GSM_ONLY &&
                sRats[1] != RILConstants.NETWORK_MODE_GSM_ONLY) {
            // only one 3g policy, to ensure the secondary SIM is 2G only
            Rlog.e(LOG_TAG, "OOPS, wrong RATs,to restore 2G for the secondary SIM");
            sRats[1] = RILConstants.NETWORK_MODE_GSM_ONLY;
            Settings.Global.putInt(sContext.getContentResolver(),
                    getRatSettingsNameForPhone2(), sRats[1]);
        }

    }

    static private String getRatSettingsNameForPhone2() {
        return sPrimarySimId == ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A ?
            Settings.Global.PREFERRED_NETWORK2_MODE :
            Settings.Global.PREFERRED_NETWORK_MODE;
    }

    public static Phone getDefaultPhoneById(int phoneId) {
        return (phoneId == TelephonyConstants.DSDS_PRIMARY_PHONE_ID ?
                sProxyPhone : sProxyPhone2);
    }

    public static void setPrimarySim(int dataSim) {
        setPrimarySim(dataSim, null);
    }

    public static void setPrimarySim(int dataSim, Message msg) {
        sPrimarySimId = Settings.Global.getInt(
                sContext.getContentResolver(), Settings.Global.MOBILE_DATA_SIM,
                ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A);
        Rlog.d(LOG_TAG, "setPrimarySim,dataSim:" + dataSim + ","  + sPrimarySimId);
        switchRilSocket();
        updateDataSimProperty(sPrimarySimId);
        ensureOne3GPolicy(sContext, msg);
    }

    private static RIL getPrimaryRil() {
        return (RIL)(sPrimarySimId == ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A ?
                                sCommandsInterface : sCommandsInterface2);
    }

    private static RIL getSecondaryRil() {
        return (RIL)(sPrimarySimId != ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A ?
                                sCommandsInterface : sCommandsInterface2);
    }

    static void switchRilSocket() {
        RIL ril = getPrimaryRil();
        RIL ril2 = getSecondaryRil();

        ril.closeSocketAndWait();
        ril2.closeSocketAndWait();
        Rlog.d(LOG_TAG, "close socket done");

        ril.switchRil();
        ril2.switchRil();
        Rlog.d(LOG_TAG, "switchRil done");
    }

    static void ensureOne3GPolicy(Context context, Message msg) {
        retrieveRatSettings();
        syncer.setRat(sRats[0], sRats[1]);

        syncer.monitorRilSwitch(((PhoneProxy)sProxyPhone).getActivePhone(),
                ((PhoneProxy)sProxyPhone2).getActivePhone(), msg);
    }

    public static Phone getDefaultPhone() {
        if (sLooper != Looper.myLooper()) {
            throw new RuntimeException(
                "PhoneFactory.getDefaultPhone must be called from Looper thread");
        }

        if (!sMadeDefaults) {
            throw new IllegalStateException("Default phones haven't been made yet!");
        }
       return sProxyPhone;
    }

    public static Phone getCdmaPhone() {
        Phone phone;
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            switch (TelephonyManager.getLteOnCdmaModeStatic()) {
                case PhoneConstants.LTE_ON_CDMA_TRUE: {
                    phone = new CDMALTEPhone(sContext, sCommandsInterface, sPhoneNotifier);
                    break;
                }
                case PhoneConstants.LTE_ON_CDMA_FALSE:
                case PhoneConstants.LTE_ON_CDMA_UNKNOWN:
                default: {
                    phone = new CDMAPhone(sContext, sCommandsInterface, sPhoneNotifier);
                    break;
                }
            }
        }
        return phone;
    }

    public static Phone getGsmPhone() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            Phone phone = new GSMPhone(sContext, sCommandsInterface, sPhoneNotifier);
            return phone;
        }
    }

    public static void updateDataSimProperty(int simId) {
           Rlog.d(LOG_TAG, "updateDataSimProperty to:" + simId);
           SystemProperties.set("gsm.simmanager.data_sim_id", simId > 0 ? "1" : "0");
       }

    public static void setSimOnOffProperties() {
        setSimOnOffProperties(0);
        setSimOnOffProperties(1);
    }

    public static void setSimOnOffProperties(int slot) {
        final String prop = slot == 0 ?
            Settings.Global.DUAL_SLOT_1_ENABLED : Settings.Global.DUAL_SLOT_2_ENABLED;

        final boolean simOn =  (Settings.Global.getInt(
                    sContext.getContentResolver(),
                    prop, TelephonyConstants.ENABLED) == TelephonyConstants.ENABLED);
        final String simProp = (slot == 0 ?
                TelephonyConstants.PROP_ON_OFF_SIM1 : TelephonyConstants.PROP_ON_OFF_SIM2);
        SystemProperties.set(simProp, simOn ? "false" : "true");
    }

    /**
     * Makes a {@link SipPhone} object.
     * @param sipUri the local SIP URI the phone runs on
     * @return the {@code SipPhone} object or null if the SIP URI is not valid
     */
    public static SipPhone makeSipPhone(String sipUri) {
        return SipPhoneFactory.makePhone(sipUri, sContext, sPhoneNotifier);
    }
    public static boolean isPrimaryOnSim1() {
        return sPrimarySimId == ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A;
    }

    public static int getPrimarySimId() {
        return sPrimarySimId;
    }

    public static boolean isPrimaryPhone(Phone phone) {
        return !"GSM2".equals(phone.getPhoneName());
    }

    public static boolean isSim1Phone(Phone phone) {
        if (!TelephonyConstants.IS_DSDS) {
            return true;
        }

        if (isPrimaryOnSim1()) {
            return isPrimaryPhone(phone) ? true : false;
        }
        return isPrimaryPhone(phone) ? false : true;
    }

    public static Phone get2ndPhone() {
        if (sLooper != Looper.myLooper()) {
            throw new RuntimeException(
                "PhoneFactory.get2ndPhone must be called from Looper thread");
        }

        if (!sMadeDefaults) {
            throw new IllegalStateException("The second phones haven't been made yet!");
        }
        return sProxyPhone2;
    }

    public static DsdsDataSimManager getDsdsDataSimManager() {
        return sDataSimManager;
    }

}

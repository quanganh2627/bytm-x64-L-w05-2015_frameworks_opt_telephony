/*
 * Copyright (C) 2011 Giesecke & Devrient GmbH
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

package com.android.internal.telephony.cat;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.SQLException;
import android.net.ConnectivityManager;
import android.net.MobileDataStateTracker;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.ServiceManager;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.dataconnection.DataCallResponse;
import com.android.internal.telephony.cat.AppInterface.CommandType;
import com.android.internal.telephony.cat.BearerDescription.BearerType;
import com.android.internal.telephony.cat.CatCmdMessage.ChannelSettings;
import com.android.internal.telephony.cat.CatCmdMessage.DataSettings;
import com.android.internal.telephony.cat.InterfaceTransportLevel.TransportProtocol;
import com.android.internal.telephony.dataconnection.DcTrackerBase;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import java.net.UnknownHostException;
import java.util.ArrayList;


public class BipGateWay {
    // reserve 16k as Tx/Rx per Buffer for TCP
    public static final int TCP_CHANNEL_BUFFER_SIZE = 16384;
    // Restrict UDP packet size to 1500 bytes due to MTU restriction
    public static final int UDP_CHANNEL_BUFFER_SIZE = 1500;

    public static final Uri PREFERRED_APN_URI = Uri.
            parse("content://telephony/carriers/preferapn");

    public static final String BIP_APN_NAME = "BIP APN";
    public static final int BIP_MAX_APNTYPE = 1;
    static final String APN_ID = "apn_id";
    static final long TIME_TO_APN_ADD = 200;
    static final long MAX_WAIT_TIME_ADD_APN = 4*TIME_TO_APN_ADD;

    private String mToBeUsedApnType;
    private long mNotAvailableRetryTimeElapse;
    private static CatService mCatService;
    private static BipTransport mBipTransport;
    private DctConstants.State mApnStateBeforeEnabling;

    /*
     * Additional information for Bearer Independent Protocol.
     * See ETSI 102 223 - 8.12.11
     */
    public enum ResultAddInfoForBip {
        NO_SPECIFIC_CAUSE(0x00),
        NO_CHANNEL_AVAILABLE(0x01),
        CHANNEL_CLOSE(0x02),
        CHANNEL_IDENTIFIER_NOT_VALID(0x03),
        REQUESTED_BUFFER_SIZE_NOT_AVAILABLE(0x04),
        SECURITY_ERROR(0x05),
        REQUESTED_UICC_NOT_AVAILABLE(0x06),
        REMOTE_NOT_REACHABLE(0x07),
        SERVICE_ERROR(0x08),
        SERVICE_ID_UNKNOWN(0x09),
        PORT_NOT_AVAILABLE(0x10);

        private int mInfo;

        ResultAddInfoForBip(int info) {
            mInfo = info;
        }

        public int value() {
            return mInfo;
        }
    }

    public static BipTransport getBipTransport() {
        return mBipTransport;
    }

    public BipGateWay(CatService catService, CommandsInterface cmdIf, Context context) {
        mCatService = catService;
        mBipTransport = new BipTransport(cmdIf, context);
    }

    public boolean available() {
        return mBipTransport.canHandleNewChannel();
    }

    public void handleBipCommand(CatCmdMessage cmdMsg) {
        mBipTransport.handleBipCommand(cmdMsg);
    }

    public class BipTransport extends Handler {
        final int MAX_CHANNEL_NUM                       = 7; // Must match Terminal Profile
        static final int MSG_ID_SETUP_DATA_CALL         = 10;
        static final int MSG_ID_TEARDOWN_DATA_CALL      = 11;
        static final int MSG_ID_DISCONNECTED_DATA_CALL  = 12;
        static final int MSG_ID_APN_CREATION            = 13;
        final int DISCONNECT_REASON                     = 0;

        private CommandsInterface mCmdIf;
        private DcTrackerBase mDataConnectionTracker;
        private Context mContext;
        private DefaultBearerStateReceiver mDefaultBearerStateReceiver;
        private BipTransport mBipTransport;
        private InetAddress mNetAddr;
        private BipChannel mBipChannels[] = new BipChannel[MAX_CHANNEL_NUM];

        public BipTransport (CommandsInterface cmdIf, Context context) {
            mCmdIf = cmdIf;
            mContext = context;
            mDefaultBearerStateReceiver = new DefaultBearerStateReceiver(context);
        }

        public DcTrackerBase getDataConnectionTrackerInst() {
            Phone phone = ((PhoneProxy)PhoneFactory.getDefaultPhone()).getActivePhone();
            return ((PhoneBase)phone).mDcTracker;
        }

        /**
         * Common handler for BIP related proactive commands.
         *
         * User confirmation shall be handled before call to this function, but we
         * still have access to the result using cmdMsg.getTextMessage() if required
         * later for example when we try to establish a data connection.
         *
         * @param cmdMsg null indicates session end
         */
        public void handleBipCommand(CatCmdMessage cmdMsg) {

            // Handle session end
            if (cmdMsg == null) {
                for (int i = 0; i < mBipChannels.length; i++) {
                    if (mBipChannels[i] != null) {
                        mBipChannels[i].onSessionEnd();
                    }
                }
                return;
            }
            mDataConnectionTracker = getDataConnectionTrackerInst();
            CommandType curCmdType = cmdMsg.getCmdType();

            switch (curCmdType) {
                case OPEN_CHANNEL:
                    ChannelSettings channelSettings = cmdMsg.getChannelSettings();
                    if (channelSettings != null) {

                        if (allChannelsClosed()) {
                            // This is our first open channel request.
                            // Fire up the broadcast receiver
                            mDefaultBearerStateReceiver.startListening();
                        }

                        // Find next available channel identifier
                        for (int i = 0; i < mBipChannels.length; i++) {
                            if (mBipChannels[i] == null) {
                                channelSettings.channel = i + 1;
                                break;
                            }
                        }
                        if (channelSettings.channel == 0) {
                            // Send TR No channel available
                            mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                                    ResultCode.BIP_ERROR, true,
                                    ResultAddInfoForBip.NO_CHANNEL_AVAILABLE.value(), null);
                            return;
                        }

                        switch (channelSettings.protocol) {
                            case TCP_SERVER:
                                mBipChannels[channelSettings.channel -1] = new TcpServerChannel();
                                break;
                            case TCP_CLIENT_REMOTE:
                            case TCP_CLIENT_LOCAL:
                                mBipChannels[channelSettings.channel -1] = new TcpClientChannel();
                                break;
                            case UDP_CLIENT_REMOTE:
                            case UDP_CLIENT_LOCAL:
                                mBipChannels[channelSettings.channel -1] = new UdpClientChannel();
                                break;
                            default:
                                mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                                        ResultCode.CMD_DATA_NOT_UNDERSTOOD, false, 0, null);
                                return;
                        }

                        if (setupDataConnection(cmdMsg)) {
                            // Data connection available, or not needed continue open the channel
                            CatLog.d(this, "Continue processing open channel");
                            if (!mBipChannels[channelSettings.channel -1].open(cmdMsg)) {
                                cleanupBipChannel(channelSettings.channel);
                            }
                        }
                        return;
                    }
                    break;

                case SEND_DATA:
                case RECEIVE_DATA:
                case CLOSE_CHANNEL:
                    if (cmdMsg.getDataSettings() != null) {
                        try {
                            BipChannel curChannel =
                                    mBipChannels[cmdMsg.getDataSettings().channel - 1];
                            if (curChannel != null) {
                                if (curCmdType == CommandType.SEND_DATA) {
                                    curChannel.send(cmdMsg);
                                    return;
                                } else if (curCmdType == CommandType.RECEIVE_DATA) {
                                    curChannel.receive(cmdMsg);
                                    return;
                                } else if (curCmdType == CommandType.CLOSE_CHANNEL) {
                                    curChannel.close(cmdMsg);
                                    cleanupBipChannel(cmdMsg.getDataSettings().channel);
                                    return;
                                }
                            } else {
                                // Send TR Channel identifier not valid
                                mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                                        ResultCode.BIP_ERROR, true,
                                        ResultAddInfoForBip.CHANNEL_IDENTIFIER_NOT_VALID.value(),
                                        null);
                                return;
                            }
                        } catch (ArrayIndexOutOfBoundsException e) {
                            // Send TR Channel identifier not valid
                            mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                                    ResultCode.BIP_ERROR, true,
                                    ResultAddInfoForBip.CHANNEL_IDENTIFIER_NOT_VALID.value(),
                                    null);
                            return;
                        }
                    }
                    break;

                case GET_CHANNEL_STATUS:
                    int[] status = new int[MAX_CHANNEL_NUM];
                    for( int i = 0; i < MAX_CHANNEL_NUM; i++) {
                        if (mBipChannels[i] != null) {
                            status[i] = mBipChannels[i].getStatus();
                        } else {
                            status[i] = 0; // Not a valid channel
                            // (Should not be present in the terminal response)
                        }
                    }
                    ResponseData resp = new ChannelStatusResponseData(status);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                            ResultCode.OK, false, 0, resp);
                    return;

            }

            mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                    ResultCode.CMD_DATA_NOT_UNDERSTOOD, false, 0, null);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ID_APN_CREATION:
                    if (msg.obj != null) {
                        onApnCreation(AsyncResult.forMessage(msg));
                    }
                    break;
                case MSG_ID_SETUP_DATA_CALL:
                    if (msg.obj != null) {
                        onSetupConnectionCompleted((AsyncResult) msg.obj);
                    }
                    break;
                case MSG_ID_TEARDOWN_DATA_CALL:
                    if (msg.obj != null) {
                        onTeardownConnectionCompleted((AsyncResult) msg.obj);
                    }
                    break;
                case MSG_ID_DISCONNECTED_DATA_CALL:
                    if (msg.obj != null) {
                        onDataDisconnection((AsyncResult) msg.obj);
                    }
                    break;
                default:
                    throw new AssertionError("Unrecognized message: " + msg.what);
            }
        }

        class DefaultBearerStateReceiver extends BroadcastReceiver {

            Message mOngoingSetupMessage;
            final Object mSetupMessageLock = new Object();
            Context mContext;
            ConnectivityManager mCm;
            IntentFilter mFilter;
            boolean mIsRegistered;

            public DefaultBearerStateReceiver(Context context) {
                mContext = context;
                mCm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                mFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
                mIsRegistered = false;
            }

            public void startListening() {
                if (mIsRegistered)
                    return; /* already registered. */
                mContext.registerReceiver(this, mFilter);
                mIsRegistered = true;
            }

            public void stopListening() {
                if (!mIsRegistered)
                    return; /* already de-registered */
                mContext.unregisterReceiver(this);
                mOngoingSetupMessage = null;
                mIsRegistered = false;
            }

            public boolean isOnListening() {
                return mIsRegistered;
            }

            public void setOngoingSetupMessage(Message msg) {
                synchronized (mSetupMessageLock) {
                    mOngoingSetupMessage = msg;
                }
            }

            private void onDisconnected() {
                CatLog.d(this, "onDisconnected");
                Message msg = null;
                synchronized (mSetupMessageLock) {
                    if (mOngoingSetupMessage == null)
                        return;
                    msg = mOngoingSetupMessage;
                    mOngoingSetupMessage = null;
                }

                if (mApnStateBeforeEnabling == DctConstants.State.RETRYING
                        || mApnStateBeforeEnabling == DctConstants.State.CONNECTING) {
                    CatLog.d(this, "Ignoring as disconnect can be due to " +
                            "enableApnType issued in RETRYING or CONNECTING state");
                    AsyncResult.forMessage(msg, null, null);
                    msg.sendToTarget();
                } else {
                    ConnectionSetupFailedException csfe =
                            new ConnectionSetupFailedException("Default bearer failed to connect");
                    AsyncResult.forMessage(msg, null, csfe);
                    msg.sendToTarget();
                }
            }

            private void onConnected() {
                CatLog.d(this, "onConnected");
                Message msg = null;
                synchronized (mSetupMessageLock) {
                    if (mOngoingSetupMessage == null)
                        return;
                    msg = mOngoingSetupMessage;
                    mOngoingSetupMessage = null;
                }

                /*Result info set to null to indicate default bearer*/
                String[] info = new String[2];
                info[0] = null;
                info[1] = null;

                AsyncResult.forMessage(msg, info, null);
                msg.sendToTarget();
            }

            private void onStillConnecting() {
                CatLog.d(this, "onStillConnecting");
            }

            @Override
            public void onReceive(Context context, Intent intent) {
                if (!intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                    CatLog.d(this, "Received unexpected broadcast: " + intent.getAction());
                    return;
                }
                NetworkInfo netInfo;
                NetworkInfo otherNetInfo;
                boolean noConnection = intent.getBooleanExtra(
                        ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                netInfo = (NetworkInfo)intent.getParcelableExtra(
                        ConnectivityManager.EXTRA_NETWORK_INFO);
                otherNetInfo = (NetworkInfo)intent.getParcelableExtra(
                        ConnectivityManager.EXTRA_OTHER_NETWORK_INFO);

                if (!noConnection) {
                    if (netInfo != null) {
                        CatLog.d(this, "In onConnected case : Connected to correct Bip_gprsX");
                        String connectedApn =
                                MobileDataStateTracker.networkTypeToApnType(netInfo.getType());
                        if ((TextUtils.equals(connectedApn, mToBeUsedApnType))
                                && netInfo.getState() == NetworkInfo.State.CONNECTED) {
                            CatLog.d(this, "Connected to APN: " + connectedApn);
                            onConnected();
                        } else if ((TextUtils.equals(connectedApn, mToBeUsedApnType))
                                && netInfo.getState() == NetworkInfo.State.DISCONNECTED) {
                            CatLog.d(this, "DisConnected to APN: " + connectedApn);
                            onDisconnected();
                        }
                    } else {
                        CatLog.d(this, "Error: No netInfo received from CONNECTIVITY_ACTION");
                        onDisconnected();
                    }
                } else if (otherNetInfo != null) {
                    /* Failed to connect but retrying with a different network */
                    onStillConnecting();
                } else {
                    onDisconnected();
                }
            }
        }

        /*
         * If user confirmation should be handled in CatService then the CatService needs to
         * determine if we can handle more channels.
         * @return
         */
        public boolean canHandleNewChannel() {
            for (int i = 0; i < mBipChannels.length; i++) {
                if (mBipChannels[i] == null) {
                    return true;
                }
            }
            return false;
        }

        /*
         * Check to see if all BIP channels are closed
         * @return true if all channels are closed.
         */
        private boolean allChannelsClosed() {
            for (BipChannel channel : mBipChannels) {
                if (channel != null)
                    return false;
            }
            return true;
        }

        private void cleanupBipChannel(int channel) {
            mBipChannels[channel - 1] = null;
            mDataConnectionTracker.setApnRequestedForBip(null);
            if (allChannelsClosed())
                mDefaultBearerStateReceiver.stopListening();
            /* All channels are closed.  Stop the broadcast receiver. */
        }


        private class ConnectionSetupFailedException extends IOException {
            public ConnectionSetupFailedException(String message) {
                super(message);
            }
        };

        private boolean setupDefaultDataConnection(CatCmdMessage cmdMsg)
                throws ConnectionSetupFailedException {
            ConnectivityManager cm = (ConnectivityManager) mContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            ChannelSettings newChannel = cmdMsg.getChannelSettings();
            boolean result = false;
            NetworkInfo defaultBearerNetInfo = null;
            ConnectionSetupFailedException setupFailedException = null;

            mToBeUsedApnType = PhoneConstants.APN_TYPE_DEFAULT;

            /* Enable the default APN */
            if (handleDataConnection(cmdMsg)) {
                /* The default APN is ongoing - Check its state */
                defaultBearerNetInfo = cm.getActiveNetworkInfo();
                if (defaultBearerNetInfo == null) {
                    setupFailedException =
                            new ConnectionSetupFailedException("Default bearer is not active");
                    throw setupFailedException;
                }

                NetworkInfo.State state = defaultBearerNetInfo.getState();

                switch (state) {
                    case CONNECTED:
                        CatLog.d(this, "Default bearer is connected");
                        result = true;
                        break;
                    case CONNECTING:
                        CatLog.d(this, "Default bearer is connecting.  Waiting for connect");
                        Message resultMsg = obtainMessage(MSG_ID_SETUP_DATA_CALL, cmdMsg);
                        mDefaultBearerStateReceiver.setOngoingSetupMessage(resultMsg);
                        result = false;
                        break;
                    case SUSPENDED:
                        /*
                         * Suspended state is only possible for mobile data accounts
                         * during voice calls.
                         */
                        CatLog.d(this, "Default bearer not connected, busy on voice call");
                        ResponseData resp = new OpenChannelResponseData(newChannel.bufSize,
                                null, newChannel.bearerDescription);
                        mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                                ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS, true,
                                ResultAddInfoForMeProblem.TERMINAL_CURRENTLY_BUSY_ON_CALL.value(),
                                resp);
                        setupFailedException =
                                new ConnectionSetupFailedException("Default bearer suspended");
                        break;
                    default:
                        /* The default bearer is disconnected either due to error or user preference
                         * Either way, there's nothing we can do. */
                        CatLog.d(this, "Default bearer is Disconnected");
                        mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                                ResultCode.BEYOND_TERMINAL_CAPABILITY, false, 0, null);
                        setupFailedException =
                                new ConnectionSetupFailedException("Default bearer" +
                                        "is disconnected");
                        break;
                }
            }

            if (setupFailedException != null) {
                throw setupFailedException;
            }

            return result;
        }

        private boolean setupSpecificPdpConnection(CatCmdMessage cmdMsg)
                throws ConnectionSetupFailedException {
            ConnectivityManager cm = (ConnectivityManager) mContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            TelephonyManager tm = (TelephonyManager) mContext
                    .getSystemService(Context.TELEPHONY_SERVICE);
            ChannelSettings newChannel = cmdMsg.getChannelSettings();

            if (!cm.getMobileDataEnabled()) {
                CatLog.d(this, "User does not allow mobile data connections");
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                        ResultCode.BEYOND_TERMINAL_CAPABILITY, false, 0, null); // TODO: fix
                throw new ConnectionSetupFailedException("No mobile data connections allowed");
            }

            if (newChannel.networkAccessName == null) {
                CatLog.d(this, "no accessname for PS bearer req");
                return setupDefaultDataConnection(cmdMsg);
            }

            // TODO: Check for class A/B support
            if (tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                CatLog.d(this, "Bearer not setup, busy on voice call");
                ResponseData resp = new OpenChannelResponseData(newChannel.bufSize,
                        null, newChannel.bearerDescription);
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                            ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS, true,
                            ResultAddInfoForMeProblem.TERMINAL_CURRENTLY_BUSY_ON_CALL.value(),
                            resp);
                throw new ConnectionSetupFailedException("Busy on voice call");
            }

            // At this stage, the proactive command specified a specific APN
            // Check that that specified APN is already exit in the system, otherwise add it.
            if (!checkAndAddNewAPN(tm, cmdMsg)) {
                CatLog.d(this, "Couldn't add new apn in Apn list");
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                        ResultCode.BEYOND_TERMINAL_CAPABILITY, false, 0, null);
                throw new ConnectionSetupFailedException("No mobile data connections allowed");
            }
            mNotAvailableRetryTimeElapse = 0;
            return handleDataConnection(cmdMsg);
        }

        /*
         * Event manager to be used to handle new bip APN availability in apn list.
         * @param ar asynchronous result object.
         * @return void
         */
        private void onApnCreation(AsyncResult ar) {
            CatCmdMessage cmdMsg;
            if (ar == null) {
                CatLog.d(this, "ERROR: AsyncResult ar is null");
                return;
            }
            cmdMsg = (CatCmdMessage) ar.userObj;
            try {
                handleDataConnection(cmdMsg);
            } catch (ConnectionSetupFailedException csfe) {
                CatLog.d(this, "setupDataConnection Failed: " + csfe.getMessage());
                // Free resources since channel could not be opened
                cleanupBipChannel(cmdMsg.getChannelSettings().channel);
            }
        }

        /*
         * Handle bip data connection.
         * Post an delayed message if the new added apn is not available.
         * We guess that time to wait adding apn is at least 200 ms.
         * We can wait up to 4*200 ms (200ms each time) otherwise bip session will set to fail.
         * @param ar asynchronous result object.
         * @return result true if already active otherwise false.
         */
        private boolean handleDataConnection(CatCmdMessage cmdMsg)
                throws ConnectionSetupFailedException {
            boolean result = false;

            mApnStateBeforeEnabling = mDataConnectionTracker.getState(mToBeUsedApnType);
            CatLog.d(this, "apn State before enabling" + mApnStateBeforeEnabling);

            mDataConnectionTracker.setApnRequestedForBip(
                    cmdMsg.getChannelSettings().networkAccessName);

            int enableStatus = mDataConnectionTracker.enableApnType(mToBeUsedApnType);

            if (enableStatus == PhoneConstants.APN_ALREADY_ACTIVE) {
                CatLog.d(this, "apn type already connected");
                result = true;
            }
            else if (enableStatus == PhoneConstants.APN_TYPE_NOT_AVAILABLE) {
                // Case where the expected APN is still adding in APN list and not available yet.
                CatLog.d(this, "apn type  "+ mToBeUsedApnType +" is NOT AVAILABLE");

                // Stop listening connection event.
                if (mDefaultBearerStateReceiver.isOnListening()) {
                    mDefaultBearerStateReceiver.stopListening();
                }

                // Delaying to TIME_TO_APN_ADD ms before attempting new bip connection.
                // If the max time to wait is reached bip session will fail.
                if (mNotAvailableRetryTimeElapse <= MAX_WAIT_TIME_ADD_APN) {
                    CatLog.d(this, "mNotAvailableRetryTimeElapse = "
                            + mNotAvailableRetryTimeElapse);
                    Message resultMsg = obtainMessage(MSG_ID_APN_CREATION, cmdMsg);
                    this.sendMessageDelayed(resultMsg,TIME_TO_APN_ADD);
                    mNotAvailableRetryTimeElapse += TIME_TO_APN_ADD;
                } else {
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                           ResultCode.BEYOND_TERMINAL_CAPABILITY, false, 0, null);
                    throw new ConnectionSetupFailedException("Exepcted APN can't be added");
                }
            } else {
                CatLog.d(this, "apn type '" + mToBeUsedApnType
                        + "' enabled , but not connected yet, wait for connection to complete");

                // Here the the new is added or the connection is resquesting.
                // So re-start listening connection event.
                if (!mDefaultBearerStateReceiver.isOnListening()) {
                    mDefaultBearerStateReceiver.startListening();
                }

                Message resultMsg = obtainMessage(MSG_ID_SETUP_DATA_CALL, cmdMsg);
                mDefaultBearerStateReceiver.setOngoingSetupMessage(resultMsg);
                result = false;
            }
            return result;
        }

        /*
        * Insert a newly APN into system DB.
        * @param mcc operator mcc.
        * @param mnc operator mnc.
        * @param apnName apn name.
        * @param apnAddr the APN to be added.
        * @param userlog user login.
        * @param userPwd user password.
        * @return id  apn id or -1 if failed
        */
        private int insertAPN(String mcc, String mnc, String apnName, String apnAddr,
                String userLog, String userPwd, String apnType) {
            int id = -1;
            ContentResolver resolver = mContext.getContentResolver();
            ContentValues values = new ContentValues();
            String numeric = mcc + mnc;
            values.put(Telephony.Carriers.NUMERIC, numeric);
            values.put(Telephony.Carriers.MCC, mcc);
            values.put(Telephony.Carriers.MNC, mnc);
            values.put(Telephony.Carriers.NAME, apnName);
            values.put(Telephony.Carriers.APN, apnAddr);
            values.put(Telephony.Carriers.USER, userLog);
            values.put(Telephony.Carriers.PASSWORD, userPwd);
            values.put(Telephony.Carriers.TYPE, apnType);
            values.put(Telephony.Carriers.CARRIER_ENABLED, true);

            Cursor c = null;

            try {
                 Uri newRowUri = resolver.insert(Telephony.Carriers.CONTENT_URI,values);
                 if (newRowUri != null) {
                     CatLog.d(this, "New APN " + apnAddr + " is added in the system DB ");
                     c = resolver.query(newRowUri, null, null, null, null);
                     if (c != null) {
                         c.moveToFirst();
                         id = c.getShort(c.getColumnIndexOrThrow(Telephony.Carriers._ID));
                     } else {
                         CatLog.d(this, " Failed to get cursor from resolver query");
                         return id;
                     }
                 } else {
                     CatLog.d(this, "Failed to add new " + apnAddr + "APN added in the system DB");
                     return id;
                 }
            } catch (SQLException e) {
                CatLog.d(this, e.getMessage());
            }
            if (c != null)
                c.close();
            return id;
        }

        private ArrayList<String> getAvailableBipGprsType() {
            ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            ArrayList<String> bipApns = new ArrayList<String>();
            NetworkInfo[] networkInfos = cm.getAllNetworkInfo();
            if (networkInfos == null) {
                CatLog.d(this, "No network Info  from ConnectivityManager");
                return bipApns;
            }

            NetworkInfo.State state;
            for (NetworkInfo info : networkInfos) {
                if (info != null && info.isAvailable()) {
                    switch (info.getType()) {
                        case ConnectivityManager.TYPE_MOBILE_CBS:
                            state = info.getState();
                            if (state != NetworkInfo.State.CONNECTING
                                    && state != NetworkInfo.State.SUSPENDED
                                    && state != NetworkInfo.State.CONNECTED) {
                                    bipApns.add(PhoneConstants.APN_TYPE_CBS);
                            }
                            break;
                    }
                }
             }
             return bipApns;
        }

        boolean checkAndAddNewAPN(TelephonyManager tm, CatCmdMessage cmdMsg) {
            ChannelSettings newChannel;

            newChannel = cmdMsg.getChannelSettings();
            // get MCC (first 3 chars) & MNC (2-3 chars)
            String numeric = tm.getSimOperator();

            String mcc;
            String mnc;

            if (numeric != null && numeric.length() > 4) {
                mcc = numeric.substring(0, 3);
                mnc = numeric.substring(3); // String after 3rd index
            } else {
                CatLog.d(this, "Wrong Network operator numeric: " + numeric);
                return false;
            }

            ContentResolver resolver = mContext.getContentResolver();
            // Check if the new networkAccessName is already in APN list
            Cursor apnCursor = null;
            boolean result = true;
            try {
                apnCursor = resolver.query(Telephony.Carriers.CONTENT_URI,
                       new String[] {"_id", "mcc", "mnc", "apn", "type"},
                       "numeric = '" + numeric + "'" + " AND  apn = '" +
                       newChannel.networkAccessName + "'" +
                       " AND (type = '" + PhoneConstants.APN_TYPE_CBS + "')",

                       null,
                       null
                       );
                if (apnCursor == null) {
                    CatLog.d(this, "Error: Query to apn db returned null");
                    return false;
                }
                apnCursor.moveToFirst();
                if (apnCursor.getCount() == 0) {
                    // Case where APN received from Proactive Command don't exist in
                    // APN list yet and add it. Add this in apnlist and tag it as type bip_gprs1.
                    mToBeUsedApnType = PhoneConstants.APN_TYPE_CBS;

                    int id = insertAPN(mcc, mnc, BIP_APN_NAME, newChannel.networkAccessName,
                           newChannel.userLogin,
                           newChannel.userPassword,
                           PhoneConstants.APN_TYPE_CBS);
                }
                else if (apnCursor.getCount() > 0) {
                   // Case where APN received from Proactive Command exists in APN list.
                   CatLog.d(this, "apn " + newChannel.networkAccessName + " already exist");
                   ArrayList<String> apnAvailables = getAvailableBipGprsType();

                   if (apnAvailables.isEmpty()) {
                       CatLog.d(this, "Reached max bip apntype: " + BIP_MAX_APNTYPE);
                       result = false;
                   } else {
                       mToBeUsedApnType = apnAvailables.get(0);
                   }
               }
            } catch (SQLException e) {
                CatLog.d(this, "BIP new apntype handled error" + e.getMessage());
                result = false;
            }

            if (apnCursor != null) {
                apnCursor.close();
            }

            return result;
        }

        /*
         * Set apn to be preferred.
         * @param id apn id
         * @return void
         */
         private void setPreferredAPN(int id) {
             ContentResolver resolver = mContext.getContentResolver();
             ContentValues values = new ContentValues();
             values.put(APN_ID,id);
             resolver.insert(PREFERRED_APN_URI, values);
         }

        /*
         *
         * @param cmdMsg The Command Message that initiated the connection.
         * @return true if data connection is established, false if error
         * occurred or data connection is being established.
         */
        private boolean setupDataConnection(CatCmdMessage cmdMsg) {
            boolean result = false;
            ChannelSettings newChannel = cmdMsg.getChannelSettings();

            if (newChannel.protocol != TransportProtocol.TCP_CLIENT_REMOTE
                    && newChannel.protocol != TransportProtocol.UDP_CLIENT_REMOTE) {
                CatLog.d(this, "No data connection needed for this channel");
                return true;
            }

            BearerDescription bd = newChannel.bearerDescription;

            try {
                if (bd.type == BearerType.DEFAULT_BEARER) {
                    result = setupDefaultDataConnection(cmdMsg);
                } else if (bd.type == BearerType.MOBILE_PS
                        || bd.type == BearerType.MOBILE_PS_EXTENDED_QOS
                        || bd.type == BearerType.MOBILE_PS_EXTENDED_EPS_QOS) {
                    result = setupSpecificPdpConnection(cmdMsg);
                } else {
                    // send TR error
                    CatLog.d(this, "Unsupported bearer type");
                  mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                            ResultCode.BEYOND_TERMINAL_CAPABILITY, false, 0, null);
                }
            } catch (ConnectionSetupFailedException csfe) {
                CatLog.d(this, "setupDataConnection Failed: " + csfe.getMessage());
                // Free resources since channel could not be opened
                cleanupBipChannel(newChannel.channel);
            }
            return result;
        }

        void sendChannelStatusEvent(int channelStatus) {
            CatLog.d(this, "sendChannelStatusEvent with status= " + channelStatus);
            byte[] additionalInfo = {(byte)0xb8, 0x02, 0x00, 0x00};
            additionalInfo[2] = (byte) ((channelStatus >> 8) & 0xff);
            additionalInfo[3] = (byte) (channelStatus & 0xff);
            mCatService.onEventDownload(new CatEventMessage(EventCode.CHANNEL_STATUS.value(),
                    CatService.DEV_ID_TERMINAL,
                    CatService.DEV_ID_UICC,
                    additionalInfo,
                    true));
        }

        void sendDataAvailableEvent(int channelStatus, int dataAvailable) {
            byte[] additionalInfo = {(byte)0xb8, 0x02, 0x00, 0x00, (byte)0xb7, 0x01, 0x00};
            additionalInfo[2] = (byte) ((channelStatus >> 8) & 0xff);
            additionalInfo[3] = (byte) (channelStatus & 0xff);
            additionalInfo[6] = (byte) (dataAvailable & 0xff);
            mCatService.onEventDownload(new CatEventMessage(EventCode.DATA_AVAILABLE.value(),
                    CatService.DEV_ID_TERMINAL,
                    CatService.DEV_ID_UICC,
                    additionalInfo,
                    true));
        }

        /*
         *
         * @param cmdMsg
         * @param cid
         * @return true if teardown of data connection is pending
         */
        private boolean teardownDataConnection(CatCmdMessage cmdMsg, String networkAccessName ) {
            CatLog.d(this, "teardownDataConnection");
            boolean ret = false;
            Message resultMsg = obtainMessage(MSG_ID_TEARDOWN_DATA_CALL, cmdMsg);
            String[] apntypes = mDataConnectionTracker.getActiveApnTypes();
            for (String type: apntypes) {
                String name = mDataConnectionTracker.getActiveApnString(type);
                if (name != null && name.equals(networkAccessName)) {
                    if (mDataConnectionTracker.isApnTypeEnabled(type)) {
                        // TODO: Disconnect apn only if all the channels are in closed state.
                        mDataConnectionTracker.setApnRequestedForBip(null);
                        mDataConnectionTracker.disableApnType(type);
                        mDefaultBearerStateReceiver.setOngoingSetupMessage(resultMsg);
                        ret = true;
                    } else {
                        CatLog.d(this, "apn type already disabled");
                        ret = false;
                    }
                    break;
                } else {
                    CatLog.d(this, "this apn not present");
                }
            }
            return ret;
        }

        private void onSetupConnectionCompleted(AsyncResult ar) {
            CatCmdMessage cmdMsg;

            if (ar == null) {
                return;
            }
            cmdMsg = (CatCmdMessage) ar.userObj;
            // TODO: separate the error messages and causes for more clean error parsing
            if (ar.exception != null) {
                CatLog.d(this, "Failed to setup data connection for channel: "
                        + cmdMsg.getChannelSettings().channel);
                ResponseData resp = new OpenChannelResponseData(
                        cmdMsg.getChannelSettings().bufSize,
                        null, cmdMsg.getChannelSettings().bearerDescription);
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                        ResultCode.NETWORK_CRNTLY_UNABLE_TO_PROCESS, false, 0, resp);
                cleanupBipChannel(cmdMsg.getChannelSettings().channel);
            } else if (mDataConnectionTracker
                    .getState(mToBeUsedApnType) == DctConstants.State.CONNECTED) {
                ChannelSettings newChannel = cmdMsg.getChannelSettings();
                CatLog.d(this, "Succeeded to setup data connection for channel "
                        + cmdMsg.getChannelSettings().channel
                        + "apn=" + newChannel.networkAccessName);
                // Continue processing open channel;
                if (!mBipChannels[cmdMsg.getChannelSettings().channel-1].open(cmdMsg)) {
                    // Failed to open channel, free resources
                    cleanupBipChannel(cmdMsg.getChannelSettings().channel);
                }
                CatLog.d(this, "Listening for disconnect events....");
                Message resultMsg = obtainMessage(MSG_ID_DISCONNECTED_DATA_CALL, cmdMsg);
                mDefaultBearerStateReceiver.setOngoingSetupMessage(resultMsg);
                if (mDefaultBearerStateReceiver.isOnListening()) {
                    mDefaultBearerStateReceiver.startListening();
                }
            } else {
                CatLog.d(this, "enable data connection again");
                try {
                    handleDataConnection(cmdMsg);
                } catch (ConnectionSetupFailedException csfe) {
                    CatLog.d(this, "Failed to setup data connection for channel: "
                            + cmdMsg.getChannelSettings().channel);
                    ResponseData resp = new OpenChannelResponseData(
                            cmdMsg.getChannelSettings().bufSize,
                            null, cmdMsg.getChannelSettings().bearerDescription);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                            ResultCode.NETWORK_CRNTLY_UNABLE_TO_PROCESS, false, 0, resp);
                    cleanupBipChannel(cmdMsg.getChannelSettings().channel);
                }
            }
        }

        private void onTeardownConnectionCompleted(AsyncResult ar) {
            CatCmdMessage cmdMsg;
            int channel;

            if (ar == null) {
                return;
            }

            cmdMsg = (CatCmdMessage) ar.userObj;

            if (cmdMsg.getCmdType() == CommandType.OPEN_CHANNEL) {
                channel = cmdMsg.getChannelSettings().channel;
            } else if (cmdMsg.getCmdType() == CommandType.CLOSE_CHANNEL) {
                channel = cmdMsg.getDataSettings().channel;
            } else {
                return;
            }

            if (ar.exception != null) {
                CatLog.d(this, "Failed to teardown data connection for channel: " + channel
                        + " " + ar.exception.getMessage());
            } else {
                CatLog.d(this, "Succedded to teardown data connection for channel: " + channel);
            }

            cleanupBipChannel(channel);
        }

        private void onDataDisconnection(AsyncResult ar) {
            CatLog.d(this, "onDataDisconnection");
            CatCmdMessage cmdMsg;
            int channel;

            if (ar == null) {
                return;
            }

            cmdMsg = (CatCmdMessage) ar.userObj;
            ChannelSettings bipChannel = cmdMsg.getChannelSettings();
            if (cmdMsg.getCmdType() == CommandType.OPEN_CHANNEL) {
                channel = cmdMsg.getChannelSettings().channel;
            } else if (cmdMsg.getCmdType() == CommandType.CLOSE_CHANNEL) {
                channel = cmdMsg.getDataSettings().channel;
            } else {
                CatLog.d(this, "Unable to retreive BIP channel ID");
                return;
            }

            if (mBipChannels[channel - 1] != null) {
                BearerDescription bd = bipChannel.bearerDescription;
                // Close the channel
                mBipChannels[channel - 1].close(null);
                if (bd.type != BearerType.DEFAULT_BEARER) {
                    // Disable the bip apn if it is not the default apn
                    mDataConnectionTracker.setApnRequestedForBip(null);
                    int status = mDataConnectionTracker.disableApnType(mToBeUsedApnType);
                    CatLog.d(this, "APN=" + mToBeUsedApnType + " Disable state= " + status);
                }
            }
        }
    }

    interface BipChannel {

        /*
         * Process OPEN_CHANNEL command.
         *
         * Caller must free resources reserved if false is returned.
         *
         * @param cmdMsg
         * @return false if channel could not be established
         */
        public boolean open(CatCmdMessage cmdMsg);

        public void close(CatCmdMessage cmdMsg);

        public void send(CatCmdMessage cmdMsg);

        public void receive(CatCmdMessage cmdMsg);

        public int getStatus();

        public void onSessionEnd();
    }

    /*
     * UICC Server Mode
     *
     * Note: Terminal responses to the proactive commands are sent from the functions
     * (open/close etc.) and events are sent from the thread.
     */
    class TcpServerChannel implements BipChannel {

        ChannelSettings mChannelSettings = null;
        int mChannelStatus = 0;
        SCWSGateway mSCWSGateway = new SCWSGateway(mCatService);

        @Override
        public boolean open(CatCmdMessage cmdMsg) {
            return (mSCWSGateway.startProxy(cmdMsg));
        }

        @Override
        public void close(CatCmdMessage cmdMsg) {
            mSCWSGateway.closeClientConnection(cmdMsg);
        }

        @Override
        public void send(CatCmdMessage cmdMsg) {
            mSCWSGateway.send(cmdMsg);
        }

        @Override
        public void receive(CatCmdMessage cmdMsg) {
            mSCWSGateway.receive(cmdMsg);
        }

        @Override
        public int getStatus() {
            if (mChannelSettings.channel == 0) {
                mChannelStatus = mChannelSettings.channel << 8; // Closed
            }
            return mChannelStatus;
        }

        @Override
        public void onSessionEnd() {
            mSCWSGateway.stopProxy();
        }
    }


    /*
     * TCP Client channel for remote and local(Terminal Server Mode) connections
     *
     * Note: Terminal responses and channel status events are from the functions
     * (open/close etc.) and data available events are sent from the thread.
     */
    class TcpClientChannel implements BipChannel {

        ChannelSettings mChannelSettings = null;
        int mChannelStatus = 0;

        TcpClientThread mThread = null;
        TcpOpenThread mOpenThread = null;

        Socket mSocket;

        byte[] mRxBuf = new byte[TCP_CHANNEL_BUFFER_SIZE];
        int mRxPos = 0;
        int mRxLen = 0;

        byte[] mTxBuf = new byte[TCP_CHANNEL_BUFFER_SIZE];
        int mTxPos = 0;
        int mTxLen = 0;
        CatCmdMessage recvCmd;
        ResultCode result = ResultCode.OK;


        class TcpOpenThread extends Thread {
            @Override
            public void run() {
                // get server address and try to connect.
                try {
                    InetAddress addr = null;
                    if (mChannelSettings.protocol == TransportProtocol.TCP_CLIENT_REMOTE) {
                        addr = InetAddress.getByAddress(mChannelSettings.destinationAddress);
                    } else {
                        addr = InetAddress.getLocalHost();
                    }
                    mSocket = new Socket();
                    mSocket.connect(new InetSocketAddress(addr, mChannelSettings.port), 10000);

                    CatLog.d(this, "Connected client socket to "
                            + addr.getHostAddress() + ":"
                            + mChannelSettings.port +" for channel "
                            + mChannelSettings.channel );

                    // Update channel status to open before sending TR
                    mChannelStatus = 0x8000 + (mChannelSettings.channel << 8);
                    ResponseData resp = new OpenChannelResponseData(
                                mChannelSettings.bufSize, mChannelStatus,
                                mChannelSettings.bearerDescription);
                    mCatService.sendTerminalResponse(recvCmd.mCmdDet, result, false, 0, resp);

                    mThread = new TcpClientThread();
                    mThread.start();

                } catch (Exception e) {
                    CatLog.d(this, "OPEN_CHANNEL - Client connection failed: "
                                + e.getMessage() );
                    if (mSocket != null) {
                        if (!mSocket.isClosed()) {
                            try {
                                mSocket.close();
                            } catch (IOException ioEx) {
                            }
                        }
                    }

                    ResponseData resp = new OpenChannelResponseData(
                                mChannelSettings.bufSize, mChannelStatus,
                                mChannelSettings.bearerDescription);
                    mCatService.sendTerminalResponse(recvCmd.mCmdDet,
                                ResultCode.BIP_ERROR, true, 0x00, resp); // TODO correct?
                    CatLog.d(this, "IOException " + e.getMessage() );
                    mBipTransport.teardownDataConnection(recvCmd,
                            mChannelSettings.networkAccessName);
                }
            }
        }

        @Override
        public boolean open(CatCmdMessage cmdMsg) {
            recvCmd = cmdMsg;
            result = ResultCode.OK;
            mChannelSettings = cmdMsg.getChannelSettings();
            mChannelStatus = mChannelSettings.channel << 8; // Closed state

            if (mChannelSettings.bufSize > TCP_CHANNEL_BUFFER_SIZE) {
                result = ResultCode.PRFRMD_WITH_MODIFICATION;
                mChannelSettings.bufSize = TCP_CHANNEL_BUFFER_SIZE;
            } else {
                mRxBuf = new byte[mChannelSettings.bufSize];
                mTxBuf = new byte[mChannelSettings.bufSize];
            }
            mOpenThread = new TcpOpenThread();
            mOpenThread.start();
            return true;
        }

        @Override
        public void close(CatCmdMessage cmdMsg) {
            if (mSocket != null)  {
                if (!mSocket.isClosed()) {
                    try {
                        mSocket.close();
                    } catch (IOException e) {
                    }
                }
            } else {
                // channel already closed
                return;
            }

            mSocket = null;
            mRxPos = 0;
            mRxLen = 0;
            mTxPos = 0;
            mTxLen = 0;

            if (cmdMsg != null) {
                // The close has been initiated by a proactive command
                // Update channel status to closed before sending TR
                mChannelStatus = mChannelSettings.channel << 8;
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, null);
                mBipTransport.teardownDataConnection(cmdMsg, mChannelSettings.networkAccessName);
            } else {
                // The close has been initiated by an APN disconnection
                mChannelStatus = (mChannelSettings.channel << 8) + 0x05; // 05 = link dropped
                mBipTransport.sendChannelStatusEvent(mChannelStatus);
                mChannelStatus = mChannelSettings.channel << 8;
            }
        }
        @Override
        public void send(CatCmdMessage cmdMsg) {
            DataSettings dataSettings = cmdMsg.getDataSettings();
            CatLog.d(this, "SEND_DATA on channel no: " + dataSettings.channel);

            // transfer data into tx buffer.
            CatLog.d( this, "Transfer data into tx buffer" );
            for (int i = 0; i < dataSettings.data.length
                    && mTxPos < mTxBuf.length; i++ ) {
                mTxBuf[mTxPos++] = dataSettings.data[i]; // TODO why not use System.arraycopy
            }
            mTxLen += dataSettings.data.length;
            CatLog.d( this, "Tx buffer now contains " +  mTxLen + " bytes.");

            // check if data shall be sent immediately
            if (cmdMsg.getCommandQualifier() == 0x01 ) {
                // TODO: reset mTxlen/pos first when data successfully has been sent?
                mTxPos = 0;
                int len = mTxLen;
                mTxLen = 0;
                CatLog.d( this, "Sent data to socket " +  len + " bytes.");

                // check if client socket still exists.
                if (mSocket == null) {
                    CatLog.d( this, "Socket not available.");
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                            ResultCode.BIP_ERROR, true, 0x00, resp); // TODO correct?
                    return;
                }

                try {
                    mSocket.getOutputStream().write(mTxBuf, 0, len);
                } catch (IOException e) {
                    CatLog.d( this, "IOException " + e.getMessage() );
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet,
                            ResultCode.BIP_ERROR, true, 0x00, resp); // TODO correct?
                    return;
                }
            }

            int avail = 0xee;
            if (mChannelSettings != null ) {
                // estimate number of bytes left in tx buffer.
                // bufSize contains either the requested bufSize or
                // the max supported buffer size.
                avail = mChannelSettings.bufSize - mTxLen;
                if (avail > 0xff) {
                    avail = 0xff;
                }
            }
            CatLog.d(this, "TR with " + avail +
                    " bytes available in Tx Buffer on channel no: " + dataSettings.channel);

            ResponseData resp = new SendDataResponseData(avail);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, resp);
        }

        @Override
        public void receive(CatCmdMessage cmdMsg) {
            ResultCode result = ResultCode.OK;
            ResponseData resp = null;

            CatLog.d(this, "RECEIVE_DATA on channel no: " + cmdMsg.getDataSettings().channel);

            int requested = cmdMsg.getDataSettings().length;
            if (requested > 0xec) {
                /* The maximum length of Terminal Response APDU is 0xff bytes,
                 * so the maximum length of channel data is 0xec when length of
                 * other mandatory TLVS are subtracted.
                 * sch 2011-07-05
                 * But some (U)SIMs allow a maximum length of 256 bytes, then
                 * the max. allowed requested length is 0xed
                 * ste 2011-08-31
                 * Yes but then it would not work for 0xec cards!
                 */
                 result = ResultCode.PRFRMD_WITH_MODIFICATION;
                 requested = 0xec;
            }
            if (requested > mRxLen) {
                requested = mRxLen;
                result = ResultCode.PRFRMD_WITH_MISSING_INFO;
            }

            mRxLen -= requested;
            int available = 0xff;
            if (mRxLen < available) {
                available = mRxLen;
            }
            byte[] data = null;
            if (requested > 0) {
                data = new byte[requested];
                System.arraycopy(mRxBuf, mRxPos, data, 0, requested);
                mRxPos += requested;
            }

            resp = new ReceiveDataResponseData(data, available);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, result, false, 0, resp);
        }

        @Override
        public int getStatus() {
            if (mChannelSettings.channel == 0) {
                mChannelStatus = mChannelSettings.channel << 8; // Closed
            }
            return mChannelStatus;
        }

        @Override
        public void onSessionEnd() {
            if (mThread == null || !mThread.isAlive()) {
                mThread = new TcpClientThread();
                mThread.start();
            }
        }

        class TcpClientThread extends Thread {

            @Override
            public void run() {
                CatLog.d(this, "Client thread start on channel no: " + mChannelSettings.channel);
                if (mSocket != null) {
                    // client connected, wait until some data is ready
                    try {
                        mRxLen = mSocket.getInputStream().read(mRxBuf);
                    } catch(IOException e) {
                        CatLog.d(this, "Read on No: " + mChannelSettings.channel
                                + ", IOException " + e.getMessage());
                        mSocket = null; // throw client socket away.
                        // Invalidate data
                        mRxBuf = new byte[mChannelSettings.bufSize];
                        mTxBuf = new byte[mChannelSettings.bufSize];
                        mRxPos = 0;
                        mRxLen = 0;
                        mTxPos = 0;
                        mTxLen = 0;
                    }

                    // sanity check
                    if (mRxLen <= 0) {
                        CatLog.d(this, "No data read.");
                    } else {
                        mRxPos = 0;
                        int available = 0xff;
                        if (mRxLen < available) {
                            available = mRxLen;
                        }

                        // event download, data available
                        mBipTransport.sendDataAvailableEvent(mChannelStatus,
                                (byte) (available & 0xff));
                    }
                }
                CatLog.d(this, "Client thread end on channel no: " + mChannelSettings.channel);
            }
        }
    }

    /*
     * UDP Client channel for remote and local(Terminal Server Mode) connections
     */
    class UdpClientChannel implements BipChannel {

        ChannelSettings mChannelSettings = null;
        int mChannelStatus = 0;
        UdpClientThread mThread = null;
        UdpOpenThread mOpenThread = null;
        DatagramSocket mDatagramSocket;

        byte[] mRxBuf = new byte[UDP_CHANNEL_BUFFER_SIZE];
        int mRxPos = 0;
        int mRxLen = 0;

        byte[] mTxBuf = new byte[UDP_CHANNEL_BUFFER_SIZE];
        int mTxPos = 0;
        int mTxLen = 0;
        CatCmdMessage recvCmd;
        ResultCode result = ResultCode.OK;


        class UdpOpenThread extends Thread {
            @Override
            public void run() {
                try {
                    InetAddress addr = null;
                    if (mChannelSettings.protocol == TransportProtocol.UDP_CLIENT_REMOTE) {
                        addr = InetAddress.getByAddress(mChannelSettings.destinationAddress);
                    } else {
                        addr = InetAddress.getLocalHost();
                    }

                    CatLog.d(this, "UDP Open thread start on channel no: "
                            + mChannelSettings.channel);
                    CatLog.d(this, "Creating "
                            + ((mChannelSettings.protocol == TransportProtocol.UDP_CLIENT_REMOTE) ?
                            "remote" : "local") + " client socket to " + addr.getHostAddress()
                            + ":" +mChannelSettings.port
                            + " for channel " + mChannelSettings.channel);

                    mDatagramSocket = new DatagramSocket();
                    mDatagramSocket.connect(addr, mChannelSettings.port);

                    CatLog.d(this, "Connected UDP client socket to " + addr.getHostAddress()
                            + ":" +mChannelSettings.port
                            + " for channel " + mChannelSettings.channel);

                    // Update channel status to open before sending TR
                    mChannelStatus = 0x8000 + (mChannelSettings.channel << 8);
                    ResponseData resp = new OpenChannelResponseData(
                            mChannelSettings.bufSize, mChannelStatus,
                            mChannelSettings.bearerDescription);
                    mCatService.sendTerminalResponse(recvCmd.mCmdDet, result, false, 0, resp);
                    mThread = new UdpClientThread();
                    mThread.start();

                } catch (IOException e) {
                    CatLog.d(this, "OPEN_CHANNEL - UDP Client connection failed: "
                            + e.getMessage());
                    ResponseData resp = new OpenChannelResponseData(
                            mChannelSettings.bufSize, mChannelStatus,
                            mChannelSettings.bearerDescription);
                    mCatService.sendTerminalResponse(recvCmd.mCmdDet, ResultCode.BIP_ERROR,
                            true, 0x00, resp); // TODO correct?
                    mBipTransport.teardownDataConnection(recvCmd,
                            mChannelSettings.networkAccessName);
                 }
            }
        }

        @Override
        public boolean open(CatCmdMessage cmdMsg) {
            result = ResultCode.OK;
            recvCmd = cmdMsg;
            mChannelSettings = cmdMsg.getChannelSettings();
            mChannelStatus = mChannelSettings.channel << 8; // Closed state

            if (mChannelSettings.bufSize > UDP_CHANNEL_BUFFER_SIZE) {
                result = ResultCode.PRFRMD_WITH_MODIFICATION;
                mChannelSettings.bufSize = UDP_CHANNEL_BUFFER_SIZE;
            } else if (mChannelSettings.bufSize > 0) {
                mRxBuf = new byte[mChannelSettings.bufSize];
                mTxBuf = new byte[mChannelSettings.bufSize];
            } else {
                mChannelSettings.bufSize = UDP_CHANNEL_BUFFER_SIZE;
            }

            mOpenThread = new UdpOpenThread();
            mOpenThread.start();
            return true;
        }

        @Override
        public void close(CatCmdMessage cmdMsg) {

            if (mDatagramSocket != null) {
                if (!mDatagramSocket.isClosed()) {
                    mDatagramSocket.close();
                }
            } else {
                // channel already closed
                return;
            }

            mDatagramSocket = null;
            mRxPos = 0;
            mRxLen = 0;
            mTxPos = 0;
            mTxLen = 0;

            if (cmdMsg != null) {
                // The close has been initiated by a proactive command
                // Update channel status to closed before sending TR
                mChannelStatus = mChannelSettings.channel << 8;
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, null);
                mBipTransport.teardownDataConnection(cmdMsg, mChannelSettings.networkAccessName);
            } else {
                // The close has been initiated by an APN disconnection
                mChannelStatus = (mChannelSettings.channel << 8) + 0x05; // 05 = link dropped
                mBipTransport.sendChannelStatusEvent(mChannelStatus);
                mChannelStatus = mChannelSettings.channel << 8;
            }
        }

        @Override
        public void send(CatCmdMessage cmdMsg) {
            DataSettings dataSettings = cmdMsg.getDataSettings();
            CatLog.d(this, "SEND_DATA on channel no: " + dataSettings.channel);
            // transfer data into tx buffer.
            CatLog.d( this, "Transfer data into tx buffer" );
            for (int i = 0; i < dataSettings.data.length &&
                   mTxPos < mTxBuf.length; i++ ) {
               mTxBuf[mTxPos++] = dataSettings.data[i]; // TODO: why not use System.arraycopy
            }
            mTxLen += dataSettings.data.length;
            CatLog.d( this, "Tx buffer now contains " +  mTxLen + " bytes.");
            // check if data shall be sent immediately
            if (cmdMsg.getCommandQualifier() == 0x01 ) {
                // TODO: reset mTxlen/pos first when data successfully has been sent?
                mTxPos = 0;
                int len = mTxLen;
                mTxLen = 0;
                CatLog.d( this, "Sent data to socket " +  len + " bytes.");

                // check if client socket still exists.
                if (mDatagramSocket == null) {
                    CatLog.d( this, "Socket not available.");
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR,
                            true, 0x00, resp); // TODO: correct?
                    return;
                }

                try {
                    mDatagramSocket.send(new DatagramPacket(mTxBuf, len));
                    CatLog.d(this, "Data on channel no: " + dataSettings.channel
                            + " sent to socket.");
                } catch (IOException e) {
                    CatLog.d( this, "IOException " + e.getMessage() );
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR,
                            true, 0x00, resp);
                    return;
                } catch (IllegalArgumentException e) {
                    CatLog.d(this, "IllegalArgumentException " + e.getMessage());
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR,
                            true, 0x00, resp);
                    return;
                }
            }
            int avail = 0xee;
            if (mChannelSettings != null ) {
                // estimate number of bytes left in tx buffer.
                // bufSize contains either the requested bufSize or
                // the max supported buffer size.
                avail = mChannelSettings.bufSize - mTxLen;
                if (avail > 0xff) {
                    avail = 0xff;
                }
            }
            CatLog.d(this, "TR with " + avail + " bytes available in Tx Buffer on channel no: "
                    + dataSettings.channel);
            ResponseData resp = new SendDataResponseData(avail);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, resp);
        }

        @Override
        public void receive(CatCmdMessage cmdMsg) {
            ResultCode result = ResultCode.OK;
            ResponseData resp = null;

            CatLog.d(this, "RECEIVE_DATA on channel no: " + cmdMsg.getDataSettings().channel);

            int requested = cmdMsg.getDataSettings().length;
            if (requested > 0xec) {
                /* The maximum length of Terminal Response APDU is 0xff bytes,
                 * so the maximum length of channel data is 0xec when length of
                 * other mandatory TLVS are subtracted.
                 * sch 2011-07-05
                 * But some (U)SIMs allow a maximum length of 256 bytes, then
                 * the max. allowed requested length is 0xed
                 * ste 2011-08-31
                 * Yes but then it would not work for 0xec cards!
                 */
                result = ResultCode.PRFRMD_WITH_MODIFICATION;
                requested = 0xec;
            }
            if (requested > mRxLen) {
                requested = mRxLen;
                result = ResultCode.PRFRMD_WITH_MISSING_INFO;
            }

            mRxLen -= requested;
            int available = 0xff;
            if (mRxLen < available)
                available = mRxLen;

            byte[] data = null;
            if (requested > 0) {
                data = new byte[requested];
                System.arraycopy(mRxBuf, mRxPos, data, 0, requested);
                mRxPos += requested;
            }

            resp = new ReceiveDataResponseData(data, available);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, result, false, 0, resp);
        }

        @Override
        public int getStatus() {
            if (mChannelSettings.channel == 0) {
                mChannelStatus = mChannelSettings.channel << 8; // Closed
            }
            return mChannelStatus;
        }

        @Override
        public void onSessionEnd() {
            if (mThread == null || !mThread.isAlive()) {
                mThread = new UdpClientThread();
                mThread.start();
            }
        }

        class UdpClientThread extends Thread {

            @Override
            public void run() {
                CatLog.d(this, "UDP Client thread start on channel no: " +
                        mChannelSettings.channel);

                if (mDatagramSocket != null) {
                    // client connected, wait until some data is ready
                    DatagramPacket packet = null;
                    boolean success = false;

                    try {
                        CatLog.d(this, "UDP Client listening on port : "
                                + mDatagramSocket.getLocalPort());
                        packet = new DatagramPacket(mRxBuf, mRxBuf.length);
                        mDatagramSocket.receive(packet);
                        success = true;
                    } catch (IOException e) {
                        CatLog.d(this, "Read on No: " + mChannelSettings.channel
                                + ", IOException " + e.getMessage());
                    } catch (IllegalArgumentException e) {
                        CatLog.d(this, "IllegalArgumentException: " + e.getMessage());
                    }

                    if (success) {
                        mRxLen = packet.getLength();
                    } else {
                        mDatagramSocket = null; // throw client socket away.
                        //Invalidate data
                        mRxBuf = new byte[mChannelSettings.bufSize];
                        mTxBuf = new byte[mChannelSettings.bufSize];
                        mRxPos = 0;
                        mRxLen = 0;
                        mTxPos = 0;
                        mTxLen = 0;
                    }

                    // sanity check
                    if (mRxLen <= 0) {
                        CatLog.d(this, "No data read.");
                    } else {
                        CatLog.d(this, mRxLen + " data read.");
                        mRxPos = 0;
                        int available = 0xff;
                        if (mRxLen < available) {
                            available = mRxLen;
                        }

                        // event download, data available
                        mBipTransport.sendDataAvailableEvent(mChannelStatus,
                                (byte) (available & 0xff));
                    }
                }

                CatLog.d(this, "UDP Client thread end on channel no: " + mChannelSettings.channel);
            }
        }

    }
}

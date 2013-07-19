
package com.android.internal.telephony.ims;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.comneon.mas.gui.CInvalidParameterException;
import com.comneon.mas.gui.CMethodExecutionFailedException;
import com.comneon.mas.gui.CServiceInfo;
import com.comneon.mas.gui.CVoip;
import com.comneon.mas.gui.CUtils;
import com.comneon.mas.gui.Curi;
import com.comneon.mas.gui.IVoip;
import com.comneon.mas.gui.IVoipCB;

public class ImsVoip implements IVoipCB {
    private final String LOG_TAG = "ImsVoip";
    private final int IMS_SESSION_DISABLE_REUSE = 4;

    public static final int VOIP_STATE = 0x2001;
    public static final int VOIP_STATE_CREATED = IVoip.IMS_VOIP_SERVICESTATECREATED;
    public static final int VOIP_STATE_CONNECTED = IVoip.IMS_VOIP_SERVICESTATECONNECTED;
    public static final int VOIP_STATE_DISCONNECTED = IVoip.IMS_VOIP_SERVICESTATEDISCONNECTED;
    public static final int VOIP_STATE_DESTROYED = IVoip.IMS_VOIP_SERVICESTATEDESTROYED;
    public static final int VOIP_STATE_RINGING = IVoip.IMS_VOIPSERVICESTATEREMOTERINGING;

    public static final int VOIP_ACCEPT = 0x2002;

    private CVoip mCVoip = null;
    private CUtils mCUtils = null;
    String Encode_RemoteUri = null;
    String Encode_LocalUri = null;
    private Handler mHdlr = null;
    private int SessionState;
    private int SessionHandle;
    public static final int VoIPAcceptIncomingServiceCB = 8;
    public static final int VoIPCreateService = 9;
    public static final int VoIPAcceptIncomingService = 10;
    public static final int VoIPRegisterCallbacks = 11;

    public ImsVoip(Handler h) {
        mCVoip = new CVoip();
        mCUtils = new CUtils();
        mHdlr = h;
        SessionState = mCVoip.IMS_VOIP_SERVICEINVALID;
        SessionHandle = 0;
    }

    public int getSessionState() {
        return SessionState;
    }

    public int getSessionHandle() {
        return SessionHandle;
    }

    /*
     * This method is used to handle Incoming Voip Call
     */
    public void RegisterVoipForIncomingCall() {
        try {
            mCVoip.RCS_VoIPRegisterIncomingServiceCallback(this,
                    true,
                    true,
                    VoIPAcceptIncomingServiceCB,
                    0,
                    null);
        } catch (CInvalidParameterException e) {
            Log.e(LOG_TAG, e.toString());
        } catch (CMethodExecutionFailedException e) {
            Log.e(LOG_TAG, e.toString());
        }
    }

    /*
     * This method is used to create a VOIP SESSION
     */
    public void CreateVoipSession(String localNumber, String remoteNumber) {
        Log.d(LOG_TAG, "CreateVoipService() from " + localNumber + " to " + remoteNumber);

        try {
            mCUtils.Ims_UriCheckAndSetPuidGenerationFormat(localNumber);

            Encode_LocalUri = mCUtils.RCS_EncodeURL(null, localNumber);
            Encode_RemoteUri = mCUtils.RCS_EncodeURL(remoteNumber, remoteNumber);
        } catch (CInvalidParameterException e) {
            Log.e(LOG_TAG, e.toString());
        }

        /* Create the CServiceInfo and pass it to the MAS */
        CServiceInfo serviceinfo = new CServiceInfo();

        serviceinfo.hService = 0;

        // hServiceInfo->m_ServiceSpecificData = VoipData.
        serviceinfo.m_bUsePreferences = false;

        serviceinfo.m_CodecPreferenceList = null;

        serviceinfo.m_iCount = 0;

        // If 0 Random port will be used.
        serviceinfo.m_iLocalRTPPort = 0;

        // DTMF enabled.
        serviceinfo.m_bDTMFEnable = true;

        serviceinfo.m_bAutoStore = false;
        serviceinfo.m_hFullPathToFile = null;

        // Register for Service State CallBack.
        serviceinfo.m_fnServiceStateCallback = true;

        // pass the voip session handle.
        serviceinfo.m_ServiceStateCallbackUserData = 2;

        // Register for Media result call back.
        serviceinfo.m_fnMediaOpResultCallback = true;

        // pass the voip session handle.
        serviceinfo.m_MediaOpResultCallbackUserData = 2;

        // Register for DTMF received callback.
        serviceinfo.m_fnDtmfReceivedCallback = false;

        // pass the voip session handle.
        serviceinfo.m_DtmfReceivedCallbackUserData = 2;

        serviceinfo.m_fnCallTransferRequestReceivedCallback = false;
        serviceinfo.m_CallTransferRequestReceivedCallbackUserData = 4;

        serviceinfo.ePriorityNamespaceLevel = 0;

        Log.d(LOG_TAG, "RCS_VoIPCreateService() from " + Encode_LocalUri + " to "
                + Encode_RemoteUri);

        try {
            mCVoip.RCS_VoIPCreateService(this,
                    Encode_LocalUri,
                    Encode_RemoteUri,
                    1,
                    IMS_SESSION_DISABLE_REUSE,
                    serviceinfo,
                    true,
                    VoIPCreateService,
                    0);

        } catch (CInvalidParameterException e) {
            Log.e(LOG_TAG, e.toString());
        } catch (CMethodExecutionFailedException e) {
            Log.e(LOG_TAG, e.toString());
        }
    }

    /*
     * This method is used to register a VOIP SESSION
     */
    public void RegisterVoipSession() {
        if (SessionHandle != 0) {
            try {
                mCVoip.RCS_VoIPRegisterCallbacks(this,
                        SessionHandle,
                        true,
                        true,
                        true,
                        true,
                        VoIPRegisterCallbacks);
            } catch (CInvalidParameterException e) {
                Log.e(LOG_TAG, e.toString());
            } catch (CMethodExecutionFailedException e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
    }

    public void AcceptVoipSession() {
        if (SessionHandle != 0) {
            try {
                mCVoip.RCS_VoIPAcceptIncomingService(this,
                        SessionHandle,
                        false,
                        true,
                        null,
                        VoIPAcceptIncomingService,
                        true);
            } catch (CInvalidParameterException e) {
                Log.e(LOG_TAG, e.toString());
            } catch (CMethodExecutionFailedException e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
    }

    /*
     * This method is used to put a VOIP SESSION on Hold
     */
    public void OnHoldVoipSession(int SessionHandle) {
        if (SessionHandle != 0) {
            try {
                mCVoip.RCS_VoIPCallOnHoldAsync(SessionHandle);
            } catch (CInvalidParameterException e) {
                Log.e(LOG_TAG, e.toString());
            } catch (CMethodExecutionFailedException e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
    }

    /*
     * This method is used to resume a VOIP SESSION
     */
    public void ResumeVoipSession() {
        if (SessionHandle != 0) {
            try {
                mCVoip.RCS_VoIPCallResumeAsync(SessionHandle);
            } catch (CInvalidParameterException e) {
                Log.e(LOG_TAG, e.toString());
            } catch (CMethodExecutionFailedException e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
    }

    /*
     * This method is used to resume a VOIP SESSION
     */
    public void RejectVoipSession() {
        if (SessionHandle != 0) {
            try {
                mCVoip.RCS_VoIPRejectIncomingService(this,
                        SessionHandle,
                        SessionHandle,
                        false,
                        false,
                        null,
                        1,
                        486,
                        "User Busy");
            } catch (CInvalidParameterException e) {
                Log.e(LOG_TAG, e.toString());
            } catch (CMethodExecutionFailedException e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
    }

    /*
     * This method is used to delete a VOIP SESSION
     */
    public void TerminateVoipSession() {
        if (SessionHandle != 0) {
            try {
                mCVoip.RCS_VoIPDestroyService(this, SessionHandle);
                SessionHandle = 0;
            } catch (CInvalidParameterException e) {
                Log.e(LOG_TAG, e.toString());
            } catch (CMethodExecutionFailedException e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
    }

    /*
     * Implementing IVoipCB (non-Javadoc)
     * @see
     * com.comneon.mas.gui.IVoipCB#Ims_VoIPNotifyReceivedCB(java.lang.String,
     * java.lang.String, int, boolean, boolean, java.lang.String)
     */

    @Override
    public void Ims_VoIPNotifyReceivedCB(String pRemotePuid, String pLocalPuid,
            int mHService, boolean mBForwardAllowed, boolean mBAccepted,
            String mFileName) {
        Curi RemoteDecodedUri = null;
        Curi LocalDecodedUri = null;

        String localNumber = null;
        String remoteNumber = null;

        try {
            RemoteDecodedUri = new Curi();
            mCUtils.Ims_UriCheckAndSetPuidGenerationFormat(pRemotePuid);
            RemoteDecodedUri = mCUtils.RCS_DecodeURLW(pRemotePuid);
            remoteNumber = RemoteDecodedUri.Number.trim();
            Log.d(LOG_TAG, "Ims_VoIPNotifyReceivedCB decode from " + remoteNumber);
        } catch (CInvalidParameterException e) {
            Log.e(LOG_TAG, e.toString());
        }

        try {
            LocalDecodedUri = new Curi();
            mCUtils.Ims_UriCheckAndSetPuidGenerationFormat(pLocalPuid);
            LocalDecodedUri = mCUtils.RCS_DecodeURLW(pLocalPuid);
            localNumber = LocalDecodedUri.Number.trim();
            Log.d(LOG_TAG, "Ims_VoIPNotifyReceivedCB decode to " + localNumber);
        } catch (CInvalidParameterException e) {
            Log.e(LOG_TAG, e.toString());
        }

        Log.d(LOG_TAG, "Ims_VoIPNotifyReceivedCB service " + mHService + " from " + remoteNumber
                + " to " + localNumber);

        SessionHandle = mHService;
        SessionState = mCVoip.IMS_VOIP_SERVICESTATECREATED;

        RegisterVoipSession();

        if (remoteNumber != null) {
            if (mHdlr != null) {
                Message incomingMsg = mHdlr.obtainMessage(VOIP_ACCEPT,
                        SessionState,
                        SessionHandle);
                incomingMsg.obj = new String(remoteNumber);
                mHdlr.sendMessage(incomingMsg);
            }
        }
    }

    @Override
    public void Ims_AvmVoIPGetActiveCodecCB(int userdata, int iAudioCodecType) {

    }

    @Override
    public void Ims_AvmVoIPGetMediaStatisticsCB(int userdata,
            int sDownlinkParamsMIData, int sDownlinkParamsMIRate,
            int sDownlinkParamsMILoss, int sDownlinkParamsMIJitter) {

    }

    @Override
    public void Ims_EncodeAvmWaitingVoiceMessagesCB(int userdata, int ISubsID,
            int err) {

    }

    @Override
    public void Ims_EncodeAvmWaitingVoiceMessagesIndicationCB() {

    }

    @Override
    public void Ims_VoIPAcceptIncomingServiceCB(int userdata, int eError) {
        Log.d(LOG_TAG, "Ims_VoIPAcceptIncomingServiceCB");
    }

    @Override
    public void Ims_VoIPCallTransferReqCB(int userdata, int hService,
            String hTransferedBy, String hTransferedTo, boolean bProceed) {

    }

    @Override
    public void Ims_VoIPCreateServiceCB(int hSession, int eError) {
        if (hSession == 0) {
            Log.d(LOG_TAG, "Ims_VoIPCreateServiceCB: handle is NULL " + eError);
        }
        else {
            SessionHandle = hSession;
            Log.d(LOG_TAG, "Ims_VoIPCreateServiceCB: got handle :" + hSession);

            RegisterVoipSession();
        }
    }

    @Override
    public void Ims_VoIPDtmfReceivedCB(int userdata, int hService,
            int iDTMFCode, boolean bHandled) {

    }

    @Override
    public void Ims_VoIPGetRemotePuidCB(int userdata, int hService,
            String phRemotePuid) {

    }

    @Override
    public void Ims_VoIPGetStateCB(int userdata, int hService, int eServiceState) {
        Log.d(LOG_TAG, "Ims_VoIPGetStateCB service " + hService + " state " + eServiceState);
    }

    @Override
    public void Ims_VoIPGetTerminateReasonCB(int userdata, int eReason) {
        Log.d(LOG_TAG, "Ims_VoIPGetTerminateReasonCB " + eReason);
    }

    @Override
    public void Ims_VoIPMediaOpResultCB(int userdata, int hService,
            int eMediaControlResult) {

    }

    @Override
    public void Ims_VoIPNotifyCancelCB(String pRemotePuid, String pLocalPuid) {
        Log.d(LOG_TAG, "Ims_VoIPNotifyCancelCB from  " + pRemotePuid + " to " + pLocalPuid);
    }

    @Override
    public void Ims_VoIPServiceStateChangedCB(int userdata, int hService,
            int eServiceState, int eTerminationReason) {

        Log.d(LOG_TAG, "Ims_VoIPServiceStateChangedCB: service " + hService + " state "
                + eServiceState);

        if (eServiceState == mCVoip.IMS_VOIP_SERVICESTATECREATED) {
            /* Set the Voip Session State Here */
            SessionState = eServiceState;

            /* Service state created show session state as Created */
            Log.i(LOG_TAG, "Ims_VoIPServiceStateChanged(): Got Service state as CREATED");

            if (mHdlr != null) {
                // Update the session state
                Message Created = mHdlr.obtainMessage(VOIP_STATE,
                        eServiceState,
                        hService);
                mHdlr.sendMessage(Created);
            }
        }
        else if (eServiceState == mCVoip.IMS_VOIP_SERVICESTATECONNECTED) {
            // Service state connected Enable timer,Enable your supplementary
            // services and show the session state as connected
            Log.i(LOG_TAG, "Ims_VoIPServiceStateChanged(): Got Service state as CONNECTED");

            /* Set the Voip Session State Here */
            SessionState = eServiceState;

            if (mHdlr != null) {
                // Update the session state
                Message Connectedmsg = mHdlr.obtainMessage(VOIP_STATE,
                        eServiceState,
                        hService);
                mHdlr.sendMessage(Connectedmsg);
            }
        }
        else if ((eServiceState >= mCVoip.IMS_VOIP_SERVICESTATEDISCONNECTED) &&
                (eServiceState <= mCVoip.IMS_VOIP_SERVICESTATEDESTROYED)) {

            if (eServiceState == mCVoip.IMS_VOIPSERVICESTATEDISCONNECTED_REMOTEUSERREJECT) {
                eServiceState = mCVoip.IMS_VOIP_SERVICESTATEDISCONNECTED;
            }

            /*
             * If the Voip Service is terminated by remote end then close the
             * Voip dialog
             */

            if (eServiceState == mCVoip.IMS_VOIPSERVICESTATEDISCONNECTED_REMOTEUSERNOTREACHABLE
                    ||
                    eServiceState == mCVoip.IMS_VOIPSERVICESTATEDISCONNECTED_REMOTEUSERBUSY
                    ||
                    eServiceState == mCVoip.IMS_VOIPSERVICESTATEDISCONNECTED_REMOTESERVICENOTAVAILABLENOW
                    ||
                    eServiceState == mCVoip.IMS_VOIPSERVICESTATEDISCONNECTED_SERVICEFORBIDDEN ||
                    eServiceState == mCVoip.IMS_VOIPSERVICESTATEDISCONNECTED_SESSIONTIMEOUT ||
                    eServiceState == mCVoip.IMS_VOIPSERVICESTATEDISCONNECTED_USERDEREGISTERED ||
                    eServiceState == mCVoip.IMS_VOIP_SERVICESTATEDESTROYED) {

            }
            else {

            }

            Log.i(LOG_TAG, "Ims_VoIPServiceStateChanged(): Got Service state as TERMINATED");

            if (SessionHandle != 0) {

                TerminateVoipSession();

                /* Set the Voip Session State Here */
                SessionState = eServiceState;

                // Service was disconnected finish of the activity
                if (mHdlr != null) {
                    Message terminatedmsg = mHdlr.obtainMessage(VOIP_STATE,
                            eServiceState,
                            hService);
                    mHdlr.sendMessage(terminatedmsg);
                }
            }
        }
        /*
         * ! THE SERVICE IS ON LOCAL HOLD STATE, NO MEDIA TRAFFIC IS POSSIBLE IN
         * THIS STATE
         */
        else if (eServiceState == mCVoip.IMS_VOIP_SERVICESTATEONLOCALHOLD) {
            if (mHdlr != null) {
                Message terminatedmsg = mHdlr.obtainMessage(VOIP_STATE,
                        eServiceState,
                        hService);
                mHdlr.sendMessage(terminatedmsg);
            }
        }
        /*
         * ! THE SERVICE IS ON REMOTE HOLD STATE, NO MEDIA TRAFFIC IS POSSIBLE
         * IN THIS STATE
         */
        else if (eServiceState == mCVoip.IMS_VOIP_SERVICESTATEONREMOTEHOLD) {
            // set the Status text to remote hold
            if (mHdlr != null) {
                Message terminatedmsg = mHdlr.obtainMessage(VOIP_STATE,
                        eServiceState,
                        hService);
                mHdlr.sendMessage(terminatedmsg);
            }
        }
        /*
         * ! THE SERVICE IS RESUMED FROM ONHOLD STATE, MEDIA TRAFFIC IS ALSO
         * RESUMED
         */
        else if (eServiceState == mCVoip.IMS_VOIP_SERVICESTATERESUMED) {
            if (mHdlr != null) {
                Message terminatedmsg = mHdlr.obtainMessage(VOIP_STATE,
                        eServiceState,
                        hService);
                mHdlr.sendMessage(terminatedmsg);
            }
        }
        /* ! THE SERVICE ONHOLD OPERATION FAILED */
        else if (eServiceState == mCVoip.IMS_VOIP_SERVICESTATEONHOLDFAILED) {
            if (mHdlr != null) {
                Message terminatedmsg = mHdlr.obtainMessage(VOIP_STATE,
                        eServiceState,
                        hService);
                mHdlr.sendMessage(terminatedmsg);
            }
        }
        /* ! THE SERVICE RESUME OPERATON FAILED */
        else if (eServiceState == mCVoip.IMS_VOIP_SERVICESTATERESUMEFAILED) {
            if (mHdlr != null) {
                Message terminatedmsg = mHdlr.obtainMessage(VOIP_STATE,
                        eServiceState,
                        hService);
                mHdlr.sendMessage(terminatedmsg);
            }

        }
        else if ((eServiceState == mCVoip.IMS_VOIPSERVICESTATEREMOTERINGING)) {
            Log.i(LOG_TAG, "Ims_VoIPServiceStateChanged(): Got Service state as REMOTE RINGING:"
                    + eServiceState);

            if (mHdlr != null) {
                Message ringingmsg = mHdlr.obtainMessage(VOIP_STATE,
                        eServiceState,
                        hService);
                mHdlr.sendMessage(ringingmsg);
            }
        }
    }
}

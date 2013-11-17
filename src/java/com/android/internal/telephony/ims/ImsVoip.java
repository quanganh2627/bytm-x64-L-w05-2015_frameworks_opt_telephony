
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

import java.util.List;

import org.gsma.joyn.mmtel.multimediacall.IMultimediaCallSessionListener;
import org.gsma.joyn.mmtel.multimediacall.IMultimediaCommonCallSession.CallType;
import org.gsma.joyn.mmtel.multimediacall.IMultimediaConferenceCallSessionListener;
import org.gsma.joyn.mmtel.multimediacall.IMultimediaCallSessionService;
import org.gsma.joyn.mmtel.multimediacall.MultimediaCommonCallSessionObject;
import org.gsma.joyn.mmtel.multimediacall.VoiceCall;

public class ImsVoip {
    private final String LOG_TAG = "ImsVoip";

    public static final int VOIP_REG_STATE = 0x2001;
    public static final int VOIP_STATE = 0x2002;

    public static final int VOIP_REG_ONLINE = 4;
    public static final int VOIP_REG_OFFLINE = 2;

    public static final int VOIP_STATE_CREATED = 1;
    public static final int VOIP_STATE_RESUMED = 2;
    public static final int VOIP_STATE_CONNECTED = 3;
    public static final int VOIP_STATE_DISCONNECTED = 4;
    public static final int VOIP_STATE_DESTROYED = 5;
    public static final int VOIP_STATE_RINGING = 6;
    public static final int VOIP_STATE_HELD = 7;
    public static final int VOIP_STATE_INCOMING = 8;
    public static final int VOIP_STATE_ERROR = 9;

    private Object mCallSessionServiceLock = new Object();
    private IMultimediaCallSessionService mCallSessionService = null;
    private Handler mHdlr = null;
    private Context mCtx = null;

    private IMultimediaCallSessionListener mCallSessionListener =
            new IMultimediaCallSessionListener.Stub() {

        @Override
        public void onMMTelServiceStateChange(int state) {
            Log.d(LOG_TAG, String.format("VOIP registration status = %d", state));
            if (mHdlr != null) {
                Message message = mHdlr.obtainMessage(
                        VOIP_REG_STATE,
                        state,
                        0);

                mHdlr.sendMessage(message);
            }
        }

        @Override
        public void onMultimediaCall(int callId, String contact,
                MultimediaCommonCallSessionObject session)
                throws RemoteException {
            Log.v(LOG_TAG, "onMultimediaCall, callId = " + callId + " , contact = " + contact);

            Message msg = mHdlr.obtainMessage();
            msg.what = ImsVoip.VOIP_STATE;
            msg.arg1 = ImsVoip.VOIP_STATE_INCOMING;
            msg.arg2 = callId;

            try {
                synchronized (ImsVoip.this.mCallSessionServiceLock) {
                    msg.obj = ImsVoip.this.mCallSessionService.getMultimediaCall(callId);
                }
                mHdlr.sendMessage(msg);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }

        @Override
        public void onMultimediaCallAborted(int callId, int reason) throws RemoteException {
            Log.v(LOG_TAG, "onMultimediaCallAborted, reason: " + reason);
            onMultimediaCallDisconnected(callId);
            // TODO: are we sure this is what to do?
        }

        @Override
        public void onMultimediaCallConnected(int callId) throws RemoteException {
            Log.v(LOG_TAG, "onMultimediaCallConnected");
            Message msg = mHdlr.obtainMessage(ImsVoip.VOIP_STATE,
                    ImsVoip.VOIP_STATE_CONNECTED,
                    callId);
            try {
                synchronized (ImsVoip.this.mCallSessionServiceLock) {
                    msg.obj = ImsVoip.this.mCallSessionService.getMultimediaCall(callId);
                }
                mHdlr.sendMessage(msg);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }

        @Override
        public void onMultimediaCallDisconnected(int callId) throws RemoteException {
            Log.v(LOG_TAG, "onMultimediaCallDisconnected");
            Message msg = mHdlr.obtainMessage(ImsVoip.VOIP_STATE,
                    ImsVoip.VOIP_STATE_DISCONNECTED,
                    callId);
            try {
                synchronized (ImsVoip.this.mCallSessionServiceLock) {
                    msg.obj = ImsVoip.this.mCallSessionService.getMultimediaCall(callId);
                }
                mHdlr.sendMessage(msg);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }

        @Override
        public void onMultimediaCallError(int callId, int error) throws RemoteException {
            Log.v(LOG_TAG, "onMultimediaCallError, error: " + error);
            Message msg = mHdlr.obtainMessage(ImsVoip.VOIP_STATE,
                    ImsVoip.VOIP_STATE_ERROR,
                    callId);
            try {
                synchronized (ImsVoip.this.mCallSessionServiceLock) {
                    msg.obj = ImsVoip.this.mCallSessionService.getMultimediaCall(callId);
                }
                mHdlr.sendMessage(msg);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }

        @Override
        public void onMultimediaCallOnHold(int callId) throws RemoteException {
            Log.v(LOG_TAG, "onMultimediaCallOnHold");
            Message msg = mHdlr.obtainMessage(ImsVoip.VOIP_STATE,
                    ImsVoip.VOIP_STATE_HELD,
                    callId);
            try {
                synchronized (ImsVoip.this.mCallSessionServiceLock) {
                    msg.obj = ImsVoip.this.mCallSessionService.getMultimediaCall(callId);
                }
                mHdlr.sendMessage(msg);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }

        @Override
        public void onMultimediaCallOnResume(int callId) throws RemoteException {
            Log.v(LOG_TAG, "onMultimediaCallOnResume");
            Message msg = mHdlr.obtainMessage(ImsVoip.VOIP_STATE,
                    ImsVoip.VOIP_STATE_RESUMED,
                    callId);
            try {
                synchronized (ImsVoip.this.mCallSessionServiceLock) {
                    msg.obj = ImsVoip.this.mCallSessionService.getMultimediaCall(callId);
                }
                mHdlr.sendMessage(msg);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }

        @Override
        public void onMultimediaCallOnTransfer(int callId, String arg1) throws RemoteException {
            Log.v(LOG_TAG, "onMultimediaCallOnTransfer");
        }

        @Override
        public void onMultimediaCallRemoteRinging(int callId) throws RemoteException {
            Log.v(LOG_TAG, "onMultimediaCallRemoteRinging");
            Message msg = mHdlr.obtainMessage(ImsVoip.VOIP_STATE,
                    ImsVoip.VOIP_STATE_RINGING,
                    callId);
            try {
                synchronized (ImsVoip.this.mCallSessionServiceLock) {
                    msg.obj = ImsVoip.this.mCallSessionService.getMultimediaCall(callId);
                }
                mHdlr.sendMessage(msg);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }

        @Override
        public void onMultimediaCallStarted(int callId) throws RemoteException {
            Log.v(LOG_TAG, "onMultimediaCallStarted");
            Message msg = mHdlr.obtainMessage(ImsVoip.VOIP_STATE,
                    ImsVoip.VOIP_STATE_CREATED,
                    callId);
            try {
                synchronized (ImsVoip.this.mCallSessionServiceLock) {
                    msg.obj = ImsVoip.this.mCallSessionService.getMultimediaCall(callId);
                }
                mHdlr.sendMessage(msg);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }

        @Override
        public void onNetworkTonesAvailable(int callId) throws RemoteException {
            Log.v(LOG_TAG, "onNetworkTonesAvailable");
        }

        @Override
        public void onNetworkTonesNotAvailable(int callId) throws RemoteException {
            Log.v(LOG_TAG, "onNetworkTonesNotAvailable");
        }
    };

    private IMultimediaConferenceCallSessionListener mConfCallSessionListener =
            new IMultimediaConferenceCallSessionListener.Stub() {

        @Override
        public void onMultimediaConferenceCallToneAvailable(int callid) {
            Log.v(LOG_TAG, "onMultimediaConferenceCallToneAvailable");
        }

        @Override
        public void onMultimediaConferenceCallStarted() {
            Log.v(LOG_TAG, "onMultimediaConferenceCallStarted");
        }

        @Override
        public void onMultimediaConferenceCallAborted() {
            Log.v(LOG_TAG, "onMultimediaConferenceCallAborted");
        }

        @Override
        public void onMultimediaConferenceCallError(int error) {
            Log.v(LOG_TAG, "onMultimediaConferenceCallError");
        }

        @Override
        public void onMultimediaConferenceCall(int callId, String[] contacts,
                MultimediaCommonCallSessionObject session) {
            Log.v(LOG_TAG, "onMultimediaConferenceCall");
        }

        @Override
        public void onMultimediaConferenceCallOnHold(int callId) {
            Log.v(LOG_TAG, "onMultimediaConferenceCallOnHold");
        }

        @Override
        public void onMultimediaConferenceCallOnResume(int callId) {
            Log.v(LOG_TAG, "onMultimediaConferenceCallOnResume");
        }

        @Override
        public void onMultimediaConferenceCallParticipantAdded(int callId, String contact) {
            Log.v(LOG_TAG, "onMultimediaConferenceCallParticipantAdded");
        }

        @Override
        public void onMultimediaConferenceCallParticipantRemoved(int callId, String contact) {
            Log.v(LOG_TAG, "onMultimediaConferenceCallParticipantRemoved");
        }
    };

    private ServiceConnection mMmtelInterfaceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.v(LOG_TAG, "MMTEL service connected");

            synchronized (ImsVoip.this.mCallSessionServiceLock) {
                ImsVoip.this.mCallSessionService = IMultimediaCallSessionService.Stub
                        .asInterface(service);
                if (ImsVoip.this.mCallSessionService == null)
                {
                    Log.e(LOG_TAG,
                            "MMTEL service connected, ImsVoip.this.mCallSessionService is null");
                }
                else
                {
                    Log.v(LOG_TAG,
                            "MMTEL service connected, ImsVoip.this.mCallSessionService" +
                            " is non null");
                }
                if (ImsVoip.this.mCallSessionService != null) {
                    try {
                        ImsVoip.this.mCallSessionService
                                .addNewMultimediaCallSessionListener(
                                        ImsVoip.this.mCallSessionListener);
                        ImsVoip.this.mCallSessionService
                                .addNewMultimediaConfCallSessionListener(
                                        ImsVoip.this.mConfCallSessionListener);
                    } catch (RemoteException ex) {
                        Log.e(LOG_TAG, ex.toString());
                    }
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.v(LOG_TAG, "MMTEL service disconnected");

            synchronized (ImsVoip.this.mCallSessionServiceLock) {
                ImsVoip.this.mCallSessionService = null;
            }
        }
    };

    public ImsVoip(Context context, Handler handler) {

        if (context == null) {
            throw new IllegalArgumentException("context");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler");
        }
        mHdlr = handler;
        mCtx = context;

        Log.d(LOG_TAG, "binding " + IMultimediaCallSessionService.class.getName());
        if (!context.bindService(new Intent(IMultimediaCallSessionService.class.getName()),
                mMmtelInterfaceConnection, Context.BIND_AUTO_CREATE)) {
            Log.e(LOG_TAG, "Could not bind to service");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (mCtx != null) {
            mCtx.unbindService(mMmtelInterfaceConnection);
        }
        super.finalize();
    }

    /*
     * This method is used to create a VOIP SESSION
     */
    public void createVoipSession(String localNumber, String remoteNumber) {
        Log.d(LOG_TAG, "CreateVoipService() from " + localNumber + " to " + remoteNumber);

        MultimediaCommonCallSessionObject callSession = new MultimediaCommonCallSessionObject();
        callSession.setVoicecallobj(new VoiceCall());
        callSession.getVoicecallobj().setContact(remoteNumber);

        synchronized (ImsVoip.this.mCallSessionServiceLock) {
            try {
                ImsVoip.this.mCallSessionService.createMultimediaCall(callSession);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }
    }

    public void acceptVoipSession(int callId) {
        synchronized (ImsVoip.this.mCallSessionServiceLock) {
            try {
                // TODO: retrieve desired calltype from upper layers
                ImsVoip.this.mCallSessionService.acceptInvitation(callId, CallType.VOICE);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }
    }

    /*
     * This method is used to put a VOIP SESSION on Hold
     */
    public void holdVoipSession(int callId) {
        synchronized (ImsVoip.this.mCallSessionServiceLock) {
            try {
                ImsVoip.this.mCallSessionService.putCallOnHold(callId);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }
    }

    /*
     * This method is used to resume a VOIP SESSION
     */
    public void resumeVoipSession(int callId) {
        synchronized (ImsVoip.this.mCallSessionServiceLock) {
            try {
                ImsVoip.this.mCallSessionService.resumeCall(callId);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }
    }

    /*
     * This method is used to resume a VOIP SESSION
     */
    public void rejectVoipSession(int callId) {
        synchronized (ImsVoip.this.mCallSessionServiceLock) {
            try {
                ImsVoip.this.mCallSessionService.rejectInvitation(callId);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }
    }

    /*
     * This method is used to delete a VOIP SESSION
     */
    public void terminateVoipSession(int callId) {
        synchronized (ImsVoip.this.mCallSessionServiceLock) {
            try {
                ImsVoip.this.mCallSessionService.endCall(callId);
            } catch (RemoteException ex) {
                Log.e(LOG_TAG, ex.toString());
            }
        }
    }
}

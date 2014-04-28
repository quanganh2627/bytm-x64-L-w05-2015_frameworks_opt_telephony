
package com.android.internal.telephony.cat;

import android.os.Handler;
import android.os.Message;
import com.android.internal.telephony.cat.BipGateWay.BipTransport;
import com.android.internal.telephony.cat.CatCmdMessage.ChannelSettings;
import com.android.internal.telephony.cat.CatCmdMessage.DataSettings;

import java.io.InputStream;
import java.io.IOException;
import java.lang.NullPointerException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

class SCWSGateway {
    private volatile ChannelSettings mChannelSettings;
    private volatile int mChannelStatus;
    private volatile ServerSocket mServerSocket;
    private Socket mSocket;
    private volatile int mCloseQualifierMsg = 0;
    private byte[] mRxBuf = new byte[BipGateWay.PREFERRED_BUFFER_SIZE];
    private int mRxPos = 0;
    private int mRxLen = 0;

    private byte[] mTxBuf = new byte[BipGateWay.PREFERRED_BUFFER_SIZE];
    private int mTxPos = 0;
    private int mTxLen = 0;

    /* UICC Server mode channel status states
     * Channel status value is coded in 2 bytes (see ETSI TS 102 223 section 8.56)
     * The MSB holds actual STATE + channel identifier.
     * The LSB holds additional information.
     * The following first three definitions represent possible value of the MSB without
     * channel identifier.
     * The last two definitions represent possible values of LSB (addition info).
     * Note that to ease the actual channel status calculation we use 2 bytes integer to
     * code each state relative information.
     */
    public static final int TCP_IN_CLOSED_STATE       = 0x0000;
    public static final int TCP_IN_LISTEN_STATE       = 0x4000;
    public static final int TCP_IN_ESTABLISHED_STATE  = 0x8000;
    public static final int TCP_STATE_LINK_DROPPED    = 0x0005;
    public static final int TCP_STATE_NO_FURTHER_INFO = 0x0000;

    static final int MSG_ID_UICC_SERVER_CLOSE_LISTEN_STATE           = 1;
    static final int MSG_ID_UICC_SERVER_CLOSE_CLOSE_STATE            = 2;
    static final int MSG_ID_UICC_SERVER_ESTABLISHED_TO_LISTEN_STATE  = 3;

    private CatService mCatService = null;

    private final Object mLock = new Object();

    Handler mServerHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            CatLog.d(this, "Handle message " + msg);
            switch (msg.what) {
                case MSG_ID_UICC_SERVER_ESTABLISHED_TO_LISTEN_STATE:
                    mCloseQualifierMsg = 0;
                    createListenThread(true);
                    break;
                case MSG_ID_UICC_SERVER_CLOSE_LISTEN_STATE:
                    mCloseQualifierMsg = 0;
                    createListenThread(false);
                    break;
                case MSG_ID_UICC_SERVER_CLOSE_CLOSE_STATE:
                    mCloseQualifierMsg = 0;
                    closeServerSocket();
                default:
                    throw new AssertionError("Unrecognized message: " + msg.what);
            }
        }
    };

    public SCWSGateway(CatService mCatService) {
        this.mCatService = mCatService;
    }

    public boolean openChannel(CatCmdMessage cmdMsg) {
        CatLog.d(this, "Opening channel... ");
        ResultCode resultCode = ResultCode.OK;

        mChannelSettings = cmdMsg.getChannelSettings();
        mChannelStatus = mChannelSettings.channel << 8; // Closed state

        if (mChannelSettings.bufSize > BipGateWay.PREFERRED_BUFFER_SIZE) {
            resultCode = ResultCode.PRFRMD_WITH_MODIFICATION;
            mChannelSettings.bufSize = BipGateWay.PREFERRED_BUFFER_SIZE;
        }

        mRxBuf = new byte[mChannelSettings.bufSize];
        mTxBuf = new byte[mChannelSettings.bufSize];

        try {
            mServerSocket = new ServerSocket(mChannelSettings.port);
        } catch(IOException e) {
            setChannelStatus(TCP_IN_CLOSED_STATE, TCP_STATE_LINK_DROPPED,
                    mChannelSettings.channel);
            ResponseData resp = new OpenChannelResponseData(
                    mChannelSettings.bufSize, mChannelStatus, mChannelSettings.bearerDescription);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR,
                    true, 0x00, resp);
            return false;
        }
        CatLog.d(this, "Open channel server socket on port "
                    + mChannelSettings.port + " for channel "
                    + mChannelSettings.channel + " Buffer size "
                    + mChannelSettings.bufSize);

        createListenThread(false);

        // Update channel status to listening before sending TR
        setChannelStatus(TCP_IN_LISTEN_STATE, TCP_STATE_NO_FURTHER_INFO,
                mChannelSettings.channel);
        ResponseData resp = new OpenChannelResponseData(
                mChannelSettings.bufSize, mChannelStatus, mChannelSettings.bearerDescription);
        mCatService.sendTerminalResponse(cmdMsg.mCmdDet, resultCode, false, 0, resp);

        return true;
    }

    private synchronized void setChannelStatus(int state, int info, int channel) {
        mChannelStatus = (state + info) + (channel << 8);
    }

    public int getStatus() {
        return mChannelStatus;
    }

    private void initRxAndTxData() {
        mRxBuf = new byte[mChannelSettings.bufSize];
        mTxBuf = new byte[mChannelSettings.bufSize];
        mRxPos = 0;
        mRxLen = 0;
        mTxPos = 0;
        mTxLen = 0;
    }

    private void closeServerSocket() {

        if (mServerSocket != null && !mServerSocket.isClosed()) {
             try {
                 mServerSocket.close();
             } catch (IOException e) {
                 CatLog.d(this, "Server Socket is null or already closed");
             }
             mServerSocket = null;
         }
    }

    private void closeClientSocket() {
        if (mSocket != null && !mSocket.isClosed()) {
            try {
                mSocket.close();
            } catch (IOException e) {
                 CatLog.d(this, "Socket is null or already closed");
            }
            mSocket = null;
         }
    }

    private int getCurrentState() {
        return mChannelStatus & 0xC000;
    }

    public void closeChannel(CatCmdMessage cmdMsg) {

        CatLog.d(this, "Closing Channel : " + mChannelSettings.channel
                + "in state: " + mChannelStatus);

        if ((cmdMsg.getCommandQualifier() & 0x01) == 0x01) {
            // Close Channel, "TCP in LISTEN state" case

            if (getCurrentState() == TCP_IN_LISTEN_STATE) {
                // TR to be sent
            } else if (getCurrentState() == TCP_IN_ESTABLISHED_STATE) {
                mCloseQualifierMsg = MSG_ID_UICC_SERVER_CLOSE_LISTEN_STATE;
                closeClientSocket();
            }
        } else if ((cmdMsg.getCommandQualifier() & 0x00) == 0x00) {
            // Close Channel, "TCP in CLOSED state" case
            if (getCurrentState() == TCP_IN_LISTEN_STATE) {
                closeServerSocket();
            } else if (getCurrentState() == TCP_IN_ESTABLISHED_STATE) {
                mCloseQualifierMsg = MSG_ID_UICC_SERVER_CLOSE_CLOSE_STATE;
                closeClientSocket();
            }
        } else {
            CatLog.d(this, "No supported Command Qualifier: " + cmdMsg.getCommandQualifier());
        }
        mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, null);
    }

    public void send(CatCmdMessage cmdMsg) {
        DataSettings dataSettings = cmdMsg.getDataSettings();
        CatLog.d(this, "SEND_DATA on channel no: " + dataSettings.channel);

        // transfer data into tx buffer.
        for (int i = 0; i < dataSettings.data.length && mTxPos < mTxBuf.length; i++) {
            mTxBuf[mTxPos++] = dataSettings.data[i];
        }
        mTxLen += dataSettings.data.length;
        CatLog.d(this, "Tx buffer now contains " +  mTxLen + " bytes.");

        int result = 0;
        // check if data shall be sent immediately
        if (cmdMsg.getCommandQualifier() == 0x01) {
            // TODO: reset mTxlen/pos first when data successfully has been sent?
            mTxPos = 0;
            int len = mTxLen;
            mTxLen = 0;
            CatLog.d(this, "Sent data to socket " +  len + " bytes.");

            try {
                mSocket.getOutputStream().write(mTxBuf, 0, len);
                result = 0xee;
                if (mChannelSettings != null) {
                    // estimate number of bytes left in tx buffer.
                    // bufSize contains either the requested bufSize or
                    // the max supported buffer size.
                    result = mChannelSettings.bufSize - mTxLen;
                    if (result > 0xff) {
                        result = 0xff;
                    }
                }
            } catch (IOException e) {
                CatLog.d(this, "IOException " + e.getMessage());
                result = 0;
            } catch (NullPointerException e) {
                CatLog.d(this, "NullPointerException " + e.getMessage());
                result = 0;
            }
        }
        ResponseData resp = new SendDataResponseData(result);
        if (result != 0) {
            mCatService.sendTerminalResponse(
                    cmdMsg.mCmdDet, ResultCode.OK, false, 0, resp);
        } else {
            mCatService.sendTerminalResponse(
                    cmdMsg.mCmdDet, ResultCode.BIP_ERROR,
                    true, 0x00, resp);
        }
    }

    public void receive(CatCmdMessage cmdMsg) {
        ResultCode resultCode = ResultCode.OK;
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
            resultCode = ResultCode.PRFRMD_WITH_MODIFICATION;
            requested = 0xec;
        }
        if (requested > mRxLen) {
            requested = mRxLen;
            resultCode = ResultCode.PRFRMD_WITH_MISSING_INFO;
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
        mCatService.sendTerminalResponse(cmdMsg.mCmdDet, resultCode, false, 0, resp);

        if (mRxLen == 0) {
            synchronized (mLock) {
                mLock.notify();
            }
        }
    }

    private void sendMessage(int msgType) {
        Message msg = mServerHandler.obtainMessage(msgType);
        mServerHandler.sendMessage(msg);
    }

    private void createListenThread(boolean isSendEvent) {
        new ListenThread().start();
        if (isSendEvent) {
            sendEventStatus(TCP_IN_LISTEN_STATE,
                    TCP_STATE_LINK_DROPPED, mChannelSettings.channel);
        }
    }

    private void sendEventStatus(int state, int info, int channel) {
        setChannelStatus(state, info, channel);
        BipGateWay.getBipTransport().sendChannelStatusEvent(mChannelStatus);
    }

    // Handle accepting connection
    class ListenThread extends Thread {

        @Override
        public void run() {

            try {
                mSocket = mServerSocket.accept();
                new ClientThread().start();
                sendEventStatus(TCP_IN_ESTABLISHED_STATE,
                        TCP_STATE_NO_FURTHER_INFO, mChannelSettings.channel);
            } catch(IOException ie) {
                CatLog.d(this, "IOException while accepting" + ie.getMessage());
                closeServerSocket();
                // TODO handling properly exception
            }
        }
    }

    // Handling client connection
    class ClientThread extends Thread {

        @Override
        public void run() {

            CatLog.d(this, "New Client connection for channel: #"
                    + mChannelSettings.channel
                    + " on port: #"
                    + mChannelSettings.port
                    + " at socket: "
                    + mSocket);
            initRxAndTxData();
            InputStream inStream = null;

            try {
                inStream = mSocket.getInputStream();

                while (mSocket != null && !mSocket.isClosed()) {
                    // client connected, wait until some data is ready
                    mRxLen = inStream.read(mRxBuf);

                    // sanity check
                    if (mRxLen <= 0) {
                        CatLog.d(this, "No data read.");
                        sendMessage(MSG_ID_UICC_SERVER_ESTABLISHED_TO_LISTEN_STATE);
                        break;
                    } else {

                        mRxPos = 0;
                        int available = 0xff;
                        if (mRxLen < available) {
                            available = mRxLen;
                        }

                        // event download, data available
                        BipGateWay.getBipTransport().sendDataAvailableEvent(
                                mChannelStatus, (byte) (available & 0xff));
                    }

                    synchronized (mLock) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException e) {
                            CatLog.d(this, "Exception in waiting for data read notification");
                        }
                    }
                }
            } catch (IOException e) {
                CatLog.d(this, "Read on No: " + mChannelSettings.channel +
                        ", IOException " + e.getMessage());
                if (mCloseQualifierMsg != 0) {
                    sendMessage(mCloseQualifierMsg);
                } else {
                 sendMessage(MSG_ID_UICC_SERVER_ESTABLISHED_TO_LISTEN_STATE);
                }
            } finally {
                synchronized (mLock) {
                    mLock.notify();
                }
                closeClientSocket();
                try {
                    if (inStream != null) {
                        inStream.close();
                    }
                } catch(IOException ie) {
                    CatLog.d(this, "IOException " + ie.getMessage());
                }
            }
        }
    }
}

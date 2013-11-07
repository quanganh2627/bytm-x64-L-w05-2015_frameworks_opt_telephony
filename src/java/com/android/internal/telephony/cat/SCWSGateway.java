package com.android.internal.telephony.cat;

import com.android.internal.telephony.cat.BipGateWay.BipTransport;
import com.android.internal.telephony.cat.CatCmdMessage.ChannelSettings;
import com.android.internal.telephony.cat.CatCmdMessage.DataSettings;

import java.io.IOException;
import java.lang.NullPointerException;
import java.net.ServerSocket;
import java.net.Socket;

class SCWSGateway {
    private ServerThread mThread;
    private ChannelSettings mChannelSettings;
    private int mChannelStatus;

    private ServerSocket mServerSocket;
    private Socket mSocket;

    private byte[] mRxBuf = new byte[BipGateWay.TCP_CHANNEL_BUFFER_SIZE];
    private int mRxPos = 0;
    private int mRxLen = 0;

    private byte[] mTxBuf = new byte[BipGateWay.TCP_CHANNEL_BUFFER_SIZE];
    private int mTxPos = 0;
    private int mTxLen = 0;

    private CatService mCatService = null;

    public SCWSGateway(CatService mCatService) {
        this.mCatService = mCatService;
    }

    public boolean startProxy (CatCmdMessage cmdMsg) {
        ResultCode resultCode = ResultCode.OK;

        mChannelSettings = cmdMsg.getChannelSettings();
        mChannelStatus = mChannelSettings.channel << 8; // Closed state

        if (mChannelSettings.bufSize > BipGateWay.TCP_CHANNEL_BUFFER_SIZE) {
            resultCode = ResultCode.PRFRMD_WITH_MODIFICATION;
            mChannelSettings.bufSize = BipGateWay.TCP_CHANNEL_BUFFER_SIZE;
        } else {
            mChannelSettings.bufSize = BipGateWay.TCP_CHANNEL_BUFFER_SIZE;
        }

        if (mChannelSettings.bufSize > 0) {
            mRxBuf = new byte[mChannelSettings.bufSize];
            mTxBuf = new byte[mChannelSettings.bufSize];
        }
        boolean resultFlag = true;
        try {
            mServerSocket = new ServerSocket(mChannelSettings.port);
            new ServerThread().start();
        } catch(IOException e) {
            resultFlag = false;
        }
        if (resultFlag) {
            ResponseData resp = new OpenChannelResponseData(
                    mChannelSettings.bufSize, mChannelStatus, mChannelSettings.bearerDescription);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR,
                    true, 0x00, resp);
        } else {
            CatLog.d(this, "Open server socket on port "
                    + mChannelSettings.port + " for channel " + mChannelSettings.channel);
            CatLog.d(this, "Server thread start on channel no: " + mChannelSettings.channel);

            // Update channel status to listening before sending TR
            mChannelStatus = 0x4000 + (mChannelSettings.channel << 8);
            ResponseData resp = new OpenChannelResponseData(
                    mChannelSettings.bufSize, mChannelStatus, mChannelSettings.bearerDescription);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, resultCode, false, 0, resp);
        }
        return resultFlag;
    }

    public boolean closeClientConnection(CatCmdMessage cmdMsg) {
        boolean resultFlag = true;

        if ((cmdMsg.getCommandQualifier() & 0x01) == 0x01) {
            if (mSocket != null && !mSocket.isClosed()) {
                try {
                    mSocket.close();
                } catch (IOException e) {
                    resultFlag = false;
                }
            }
            mSocket = null;
        } else {
            if (mSocket != null && !mSocket.isClosed()) {
                try {
                    mSocket.close();
                } catch (IOException e) {
                    CatLog.d(this, "Socket is null or already closed");
                }
            }
            mSocket = null;

            if (mServerSocket != null && !mServerSocket.isClosed()) {
                try {
                    mServerSocket.close();
                } catch (IOException e) {
                    resultFlag = false;
                }
            }
            mServerSocket = null;

            mRxPos = 0;
            mRxLen = 0;
            mTxPos = 0;
            mTxLen = 0;
            mChannelStatus = mChannelSettings.channel << 8;
            BipGateWay.getBipTransport().sendChannelStatusEvent(mChannelStatus);

            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, null);
            return true;
        }
        return resultFlag;
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
    }

    public void stopProxy() {
        // close any existing client connection
        // so that we can handle the next waiting client request.
        if (mSocket != null) {
            if (!mSocket.isClosed()) {
                try {
                    mSocket.close();
                } catch (IOException ioex) {
                    // nothing to do, since we don't need this socket
                    // any longer.
                    CatLog.d(this, "Socket is null or already closed");
                }
            }
            mSocket=null;
        }

        // restart server thread.
        if (mThread == null || !mThread.isAlive()) {
            mThread = new ServerThread();
            mThread.start();
        }
    }

    class ServerThread extends Thread {
        @Override
        public void run() {
            CatLog.d(this, "Server thread start on channel no: "
                    + mChannelSettings.channel);

            if (mSocket == null || mSocket.isClosed()) {

                // event download - channel listen
                mChannelStatus = 0x4000 + (mChannelSettings.channel << 8);
                BipGateWay.getBipTransport().sendChannelStatusEvent(mChannelStatus);

                try {
                    CatLog.d(this, "Wait for connection");
                    mSocket = mServerSocket.accept();
                } catch (IOException e) {
                    CatLog.d(this, "IOException " + e.getMessage());
                    // TODO: find out if serverSocket is OK else we will end up in a loop
                }

                // event download, channel established
                if (mSocket != null && mSocket.isConnected()) {
                    mChannelStatus = 0x8000 + (mChannelSettings.channel << 8);
                    BipGateWay.getBipTransport().sendChannelStatusEvent(mChannelStatus);
                }
            }

            if (mSocket != null) {
                // client connected, wait until some data is ready
                try {
                    mRxLen = mSocket.getInputStream().read(mRxBuf);
                } catch (IOException e) {
                    CatLog.d(this, "Read on No: " + mChannelSettings.channel +
                            ", IOException " + e.getMessage());
                    mSocket = null; // throw client socket away.
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
                    BipGateWay.getBipTransport().sendDataAvailableEvent(
                            mChannelStatus, (byte) (available & 0xff));
                }
            } else {
                CatLog.d(this, "No Socket connection for server thread on channel no: "
                        + mChannelSettings.channel);
            }

            CatLog.d(this, "Server thread end on channel no: " + mChannelSettings.channel);
        }
    }
}

/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.telephony.Rlog;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/**
 * Class for representing COMPREHENSION-TLV objects.
 *
 * @see "ETSI TS 101 220 subsection 7.1.1"
 *
 * {@hide}
 */
public class ComprehensionTlv {
    private static final String LOG_TAG = "ComprehensionTlv";
    private int mTag;
    private boolean mCr;
    private int mLength;
    private int mValueIndex;
    private byte[] mRawValue;
    private ArrayList<ComprehensionTlv> mChilds = new ArrayList<ComprehensionTlv>();

    /**
     * Constructor. Private on purpose. Use
     * {@link #decodeMany(byte[], int) decodeMany} or
     * {@link #decode(byte[], int) decode} method.
     *
     * @param tag The tag for this object
     * @param cr Comprehension Required flag
     * @param length Length of the value
     * @param data Byte array containing the value
     * @param valueIndex Index in data at which the value starts
     */
    protected ComprehensionTlv(int tag, boolean cr, int length, byte[] data,
            int valueIndex) {
        mTag = tag;
        mCr = cr;
        mLength = length;
        mValueIndex = valueIndex;
        mRawValue = data == null ? new byte[0] : data;
    }

    public int getTag() {
        return mTag;
    }

    public boolean isComprehensionRequired() {
        return mCr;
    }

    public int getLength() {
        return mLength;
    }

    public int getValueIndex() {
        return mValueIndex;
    }

    public byte[] getRawValue() {
        return mRawValue;
    }

    /**
     * Parses a list of COMPREHENSION-TLV objects from a byte array.
     *
     * @param data A byte array containing data to be parsed
     * @param startIndex Index in data at which to start parsing
     * @return A list of COMPREHENSION-TLV objects parsed
     * @throws ResultException
     */
    public static List<ComprehensionTlv> decodeMany(byte[] data, int startIndex)
            throws ResultException {
        ArrayList<ComprehensionTlv> items = new ArrayList<ComprehensionTlv>();
        int endIndex = data.length;
        while (startIndex < endIndex) {
            ComprehensionTlv ctlv = ComprehensionTlv.decode(data, startIndex);
            if (ctlv != null) {
                items.add(ctlv);
                startIndex = ctlv.mValueIndex + ctlv.mLength;
            } else {
                CatLog.d(LOG_TAG, "decodeMany: ctlv is null, stop decoding");
                break;
            }
        }

        return items;
    }

    /**
     * Parses an COMPREHENSION-TLV object from a byte array.
     *
     * @param data A byte array containing data to be parsed
     * @param startIndex Index in data at which to start parsing
     * @return A COMPREHENSION-TLV object parsed
     * @throws ResultException
     */
    public static ComprehensionTlv decode(byte[] data, int startIndex)
            throws ResultException {
        int curIndex = startIndex;
        int endIndex = data.length;

        try {
            /* tag */
            int tag;
            boolean cr; // Comprehension required flag
            int temp = data[curIndex++] & 0xff;
            switch (temp) {
            case 0:
            case 0xff:
            case 0x80:
                Rlog.d("CAT     ", "decode: unexpected first tag byte=" + Integer.toHexString(temp) +
                        ", startIndex=" + startIndex + " curIndex=" + curIndex +
                        " endIndex=" + endIndex);
                // Return null which will stop decoding, this has occurred
                // with Ghana MTN simcard and JDI simcard.
                return null;

            case 0x7f: // tag is in three-byte format
                tag = ((data[curIndex] & 0xff) << 8)
                        | (data[curIndex + 1] & 0xff);
                cr = (tag & 0x8000) != 0;
                tag &= ~0x8000;
                curIndex += 2;
                break;

            default: // tag is in single-byte format
                tag = temp;
                cr = (tag & 0x80) != 0;
                tag &= ~0x80;
                break;
            }

            /* length */
            int length;
            temp = data[curIndex++] & 0xff;
            if (temp < 0x80) {
                length = temp;
            } else if (temp == 0x81) {
                length = data[curIndex++] & 0xff;
                if (length < 0x80) {
                    throw new ResultException(
                            ResultCode.CMD_DATA_NOT_UNDERSTOOD,
                            "length < 0x80 length=" + Integer.toHexString(length) +
                            " startIndex=" + startIndex + " curIndex=" + curIndex +
                            " endIndex=" + endIndex);
                }
            } else if (temp == 0x82) {
                length = ((data[curIndex] & 0xff) << 8)
                        | (data[curIndex + 1] & 0xff);
                curIndex += 2;
                if (length < 0x100) {
                    throw new ResultException(
                            ResultCode.CMD_DATA_NOT_UNDERSTOOD,
                            "two byte length < 0x100 length=" + Integer.toHexString(length) +
                            " startIndex=" + startIndex + " curIndex=" + curIndex +
                            " endIndex=" + endIndex);
                }
            } else if (temp == 0x83) {
                length = ((data[curIndex] & 0xff) << 16)
                        | ((data[curIndex + 1] & 0xff) << 8)
                        | (data[curIndex + 2] & 0xff);
                curIndex += 3;
                if (length < 0x10000) {
                    throw new ResultException(
                            ResultCode.CMD_DATA_NOT_UNDERSTOOD,
                            "three byte length < 0x10000 length=0x" + Integer.toHexString(length) +
                            " startIndex=" + startIndex + " curIndex=" + curIndex +
                            " endIndex=" + endIndex);
                }
            } else {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD,
                        "Bad length modifer=" + temp +
                        " startIndex=" + startIndex + " curIndex=" + curIndex +
                        " endIndex=" + endIndex);

            }

            return new ComprehensionTlv(tag, cr, length, data, curIndex);

        } catch (IndexOutOfBoundsException e) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD,
                    "IndexOutOfBoundsException" + " startIndex=" + startIndex +
                    " curIndex=" + curIndex + " endIndex=" + endIndex);
        }
    }

    /**
     * Add a child element to the element.
     * This allows to build a tree of TLV for encoding.
     *
     * @param child the child element to add.
     */
    public void addChild(ComprehensionTlv child) {
        mChilds.add(child);
    }

    /**
     * Encode TLV data.
     *
     * @return the encoded byte array.
     */
    public byte[] encode() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        encode(buf);
        return buf.toByteArray();
    }

    /**
     * Encode TLV data into a Byte Array stream.
     *
     * @param buf the ByteArrayOutputStream to encode into.
     *
     * @return the encoded length.
     */
    public int encode(ByteArrayOutputStream buf) {
        int ret = 0;
        try {
            if (mChilds.isEmpty()) {
                buf.write(mTag);
                ret += encodeLength(buf, mLength);
                buf.write(mRawValue);
                ret += mLength;
            } else {
                ByteArrayOutputStream innerBuf = new ByteArrayOutputStream();
                int innerLength = 0;
                for (ComprehensionTlv tlv : mChilds) {
                    innerLength += 1; // Take tag into account.
                    innerLength += tlv.encode(innerBuf);
                }
                buf.write(mTag);
                ret += encodeLength(buf, innerLength);
                buf.write(innerBuf.toByteArray());
                ret += innerLength;
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error encoding TLV " + e);
        }
        return ret;
    }

    /**
     * Encode the length into an output byte stream
     *
     * @param buf the output stream on which to encode the length.
     * @param length the length value to encode.
     * @return the number of bytes the encoded length field occupies.
     */
    private int encodeLength(ByteArrayOutputStream buf, int length) {
        int ret = 0;
        if (length < 127) {
            buf.write(length);
            ret = 1;
        } else if (length < 255) {
            buf.write((byte) 0x81);
            buf.write((byte) length);
            ret = 2;
        } else if (length < 65535) {
            buf.write((byte) 0x82);
            buf.write((byte) ((length >> 8) & 0xff));
            buf.write((byte) (length & 0xff));
            ret = 3;
        } else if (length < 16777215) {
            buf.write((byte) 0x83);
            buf.write((byte) ((length >> 16) & 0xff));
            buf.write((byte) ((length >> 8) & 0xff));
            buf.write((byte) (length & 0xff));
            ret = 4;
        }
        return ret;
    }

    public static ComprehensionTlv createLocationInfoTlv(byte[] operBytes, int cellId, int lac,
            boolean extendedCellId) {

        byte[] locData = new byte[7 + (extendedCellId ? 2 : 0)];
        int i;
        for (i = 0; i < 3; i++) {
            locData[i] = operBytes[i];
        }
        locData[i++] = (byte) (lac >> 8);
        locData[i++] = (byte) (lac);
        if (extendedCellId) {
            locData[i++] = (byte) (cellId >> 24);
            locData[i++] = (byte) (cellId >> 16);
            locData[i++] = (byte) (cellId >> 8);
            locData[i++] = (byte) (cellId);
        } else {
            locData[i++] = (byte) (cellId >> 8);
            locData[i++] = (byte) (cellId);
        }

        return new ComprehensionTlv(
            ComprehensionTlvTag.LOCATION_INFORMATION.value(),
            true, locData.length, locData, 0);
    }
}

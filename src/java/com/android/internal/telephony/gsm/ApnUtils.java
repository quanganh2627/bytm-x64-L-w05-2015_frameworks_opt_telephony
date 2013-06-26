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

import android.database.Cursor;
import android.net.NetworkUtils;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.util.Log;


import com.android.internal.telephony.dataconnection.ApnSetting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;

public class ApnUtils {
    private final static String LOG_TAG = "ApnUtils";
    private final static boolean DBG =  false;

    private static final Uri PREFERAPN_NO_UPDATE_URI =
            Uri.parse("content://telephony/carriers/preferapn_no_update");
    private static final Uri CONTENT_URI =
            Uri.parse("content://telephony/carriers");

    private static void log(String text) {
        Log.d(LOG_TAG, text);
    }

    private static String[] parseTypes(String types) {
        String[] result;
        // If unset, set to DEFAULT.
        if (types == null || types.equals("")) {
            result = new String[1];
            result[0] = PhoneConstants.APN_TYPE_ALL;
        } else {
            result = types.split(",");
        }
        return result;
    }

    private static ApnSetting findDefaultApn(Cursor cursor) {
        if (cursor != null && cursor.moveToFirst()) {
            do {
                String[] types = parseTypes(
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.TYPE)));
                ApnSetting apn = new ApnSetting(
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NUMERIC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NAME)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN)),
                        NetworkUtils.trimV4AddrZeros(
                                cursor.getString(
                                cursor.getColumnIndexOrThrow(Telephony.Carriers.PROXY))),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PORT)),
                        NetworkUtils.trimV4AddrZeros(
                                cursor.getString(
                                cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSC))),
                        NetworkUtils.trimV4AddrZeros(
                                cursor.getString(
                                cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPROXY))),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.USER)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PASSWORD)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.AUTH_TYPE)),
                        types,
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROTOCOL)),
                        cursor.getString(cursor.getColumnIndexOrThrow(
                                Telephony.Carriers.ROAMING_PROTOCOL)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(
                                Telephony.Carriers.CARRIER_ENABLED)) == 1,
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.BEARER)));
                if (apn.canHandleType(PhoneConstants.APN_TYPE_DEFAULT) ||
                        apn.bearer == TelephonyManager.NETWORK_TYPE_LTE) {
                    return apn;
                }
            } while (cursor.moveToNext());
        }
        if (cursor != null) {
            cursor.close();
        }
        return null;
    }

    public static ApnSetting getDefaultApnSettings(Phone phone, String operator) {
        // query only enabled apn.
        // carrier_enabled: 1 means enabled apn, 0 disabled apn.
        String selection = "numeric = '" + operator + "' and carrier_enabled = 1";
        if (DBG) log("getDefaultApnSettings: selection=" + selection);

        // First, find the preferred APN that matches the current operator.
        Cursor cursor = phone.getContext().getContentResolver().query(
                PREFERAPN_NO_UPDATE_URI, null, selection, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                return findDefaultApn(cursor);
            }
            cursor.close();
        }

        /*
         * If not found, find the first APN that matches the operator and handles
         * default or bearer set to LTE.
         */
        cursor = phone.getContext().getContentResolver().query(
                Telephony.Carriers.CONTENT_URI, null, selection, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                return findDefaultApn(cursor);
            }
            cursor.close();
        }

        return null;
    }
}

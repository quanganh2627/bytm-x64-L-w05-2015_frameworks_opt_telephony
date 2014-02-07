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

package com.android.internal.telephony.cat;

import android.os.Message;

public interface ImsStkInterface {
    /**
     * Request MO SMS Control by SIM.
     *
     * @param address the called party address
     * @param smsc the SMSC address
     * @param operator the current operator (plmn)
     * @param lac the current lac
     * @param cellid the current cid
     * @param extendedCellId true if cid is coded on more than 2 bytes
     * @param response The response message that will be sent along with the sim response.
     */
    public void onSmsControl(String address, String smsc, String operator, int lac, int cellid,
            boolean extendedCellId, Message response);

    /**
     * Request Call Control by SIM.
     *
     * @param address the called party address
     * @param operator the current operator (plmn)
     * @param lac the current lac
     * @param cellid the current cid
     * @param extendedCellId true if cid is coded on more than 2 bytes
     * @param response The response message that will be sent along with the sim response.
     */
    public void onCallControl(String address, String operator, int lac, int cellid,
            boolean extendedCellId, Message response);

    /**
     * Send the MT CALL event to the SIM card.
     *
     * @param address the calling party address if available.
     * @param tid the transaction identifier ( or cid in case of IMS.)
     */
    public void onMtCall(String address, int tid);

    /**
     * Send the CALL CONNECTED event to the SIM card.
     *
     * @param tid the Transaction identifier ( or cid in case of IMS.)
     * @param isMt true if the call was mobile terminated.
     */
    public void onCallConnected(int tid, boolean isMt);

    /**
     * Send the CALL DISCONNECTED event to the SIM card.
     * @param impuList
     * @param statusCode
     */
    public void onCallDisconnected(int tid, boolean isLocal);

    /**
     * Send the IMS REGISTERED event to the SIM Card.
     *
     * @param impuList the list of IMPU received from IMS Server
     * @param statusCode the IMS status code in case of error.
     */
    public void onImsRegistered(String[] impuList, String statusCode);

    /**
     * Get SIM Call Control status.
     *
     * @return true if SIM Call control is enabled.
     */
    public boolean isCallControlEnabled();
}

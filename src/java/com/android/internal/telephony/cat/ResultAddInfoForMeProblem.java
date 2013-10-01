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

/**
 * Enumeration for the additionnal information for ME problem
 * See ETSI 102 223 - 8.12.2 and 131.111 8.12.2
 * To get the actual return code for each enum value, call {@link #value}
 * method.
 *
 * {@hide}
 */
public enum ResultAddInfoForMeProblem {
    NO_CAUSE(0x00),
    SCREEN_IS_BUSY(0x01),
    TERMINAL_CURRENTLY_BUSY_ON_CALL(0x02),
    ME_CURRENTLY_BUSY_ON_SS_TRANSACTION(0x03),
    NO_SERVICE(0x04),
    ACCESS_CONTROL_CLASS_BAR(0x05),
    RADIO_RESOURCE_NOT_GRANTED(0x06),
    NOT_IN_SPEECH_CALL(0x07),
    ME_CURRENTLY_BUSY_ON_USSD_TRANSACTION(0x08),
    ME_CURRENTLY_BUSY_ON_SEND_DTMF(0x09),
    NO_NAA_ACTIVE(0x0A);

    private int mInfo;

    ResultAddInfoForMeProblem(int info) {
        mInfo = info;
    }

    /**
     * Retrieves the actual code that this object represents.
     * @return Actual code
     */
    public int value() {
        return mInfo;
    }
}

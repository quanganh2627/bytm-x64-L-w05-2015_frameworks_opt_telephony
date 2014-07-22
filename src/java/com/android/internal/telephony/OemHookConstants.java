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

package com.android.internal.telephony;

public interface OemHookConstants {
    /* OEM hook specific to DSDS for swapping protocol,
     * parameter flag take one of the two values below:
     * SWAP_PS_NORMAL: to just swap two protocal stack,
     *     used for mobile network settings to change RAT.
     * SWAP_PS_RESET_RADIO_STATE: to swap PS as well as to force modem into unknown state,
     *    force network,re-registering, used for dynamic switch */
    public static final int RIL_OEM_HOOK_STRING_SWAP_PS     = 0x000000B2;
    public static final int SWAP_PS_FLAG_NORMAL             = 0x01;
    public static final int SWAP_PS_FLAG_RESET_RADIO_STATE  = 0x02;
    //DVP:
    /* OEM hook specific to get DvP status
     * "response" is a "char **"
     * ((const char **)response)[0] is "0" or "1"
     *   "0" --- disable DVP
     *   "1" --- enable DVP */
    public static final int RIL_OEM_HOOK_STRING_GET_DVP_STATE = 0x000000B3;
    /* OEM hook specific to set DvP status,"0" means dsiable, "1" means enable*/
    public static final int RIL_OEM_HOOK_STRING_SET_DVP_ENABLED = 0x000000B4;
    //TOOS
    /* OEM hook specific to DSDS for catching out of service URC
     *
     * "data" is NULL
     */
    public static final int RIL_OEM_HOOK_RAW_UNSOL_FAST_OOS_IND     = 0x000000D1;

    /* OEM hook specific to DSDS for catching in service URC
     *
     * "data" is NULL
     */
    public static final int RIL_OEM_HOOK_RAW_UNSOL_IN_SERVICE_IND   = 0x000000D2;

    /* DvP State */
    public static final int DVP_STATE_INVALID = -1;
    public static final int DVP_STATE_DISABLED = 0;
    public static final int DVP_STATE_ENABLED = 1;
}

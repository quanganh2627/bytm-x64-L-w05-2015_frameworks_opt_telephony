/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Message;

import com.android.internal.telephony.cat.BearerDescription.BearerType;
import com.android.internal.telephony.cat.InterfaceTransportLevel.TransportProtocol;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.IccFileHandler;

import java.net.InetAddress;
import java.util.Iterator;
import java.util.List;

/**
 * Factory class, used for decoding raw byte arrays, received from baseband,
 * into a CommandParams object.
 *
 */
class CommandParamsFactory extends Handler {
    private static CommandParamsFactory sInstance = null;
    private IconLoader mIconLoader;
    private CommandParams mCmdParams = null;
    private int mIconLoadState = LOAD_NO_ICON;
    private RilMessageDecoder mCaller = null;

    // constants
    static final int MSG_ID_LOAD_ICON_DONE = 1;

    // loading icons state parameters.
    static final int LOAD_NO_ICON           = 0;
    static final int LOAD_SINGLE_ICON       = 1;
    static final int LOAD_MULTI_ICONS       = 2;

    // Command Qualifier values for refresh command
    static final int REFRESH_NAA_INIT_AND_FULL_FILE_CHANGE  = 0x00;
    static final int REFRESH_NAA_INIT_AND_FILE_CHANGE       = 0x02;
    static final int REFRESH_NAA_INIT                       = 0x03;
    static final int REFRESH_UICC_RESET                     = 0x04;

    // Command Qualifier values for PLI command
    static final int DTTZ_SETTING                           = 0x03;
    static final int LANGUAGE_SETTING                       = 0x04;
    static final int SEARCH_MODE_SETTING                    = 0x09;

    static synchronized CommandParamsFactory getInstance(RilMessageDecoder caller,
            IccFileHandler fh) {
        if (sInstance != null) {
            return sInstance;
        }
        if (fh != null) {
            return new CommandParamsFactory(caller, fh);
        }
        return null;
    }

    private CommandParamsFactory(RilMessageDecoder caller, IccFileHandler fh) {
        mCaller = caller;
        mIconLoader = IconLoader.getInstance(this, fh);
    }

    private CommandDetails processCommandDetails(List<ComprehensionTlv> ctlvs) {
        CommandDetails cmdDet = null;

        if (ctlvs != null) {
            // Search for the Command Details object.
            ComprehensionTlv ctlvCmdDet = searchForTag(
                    ComprehensionTlvTag.COMMAND_DETAILS, ctlvs);
            if (ctlvCmdDet != null) {
                try {
                    cmdDet = ValueParser.retrieveCommandDetails(ctlvCmdDet);
                } catch (ResultException e) {
                    CatLog.d(this,
                            "processCommandDetails: Failed to procees command details e=" + e);
                }
            }
        }
        return cmdDet;
    }

    void make(BerTlv berTlv) {
        if (berTlv == null) {
            return;
        }
        // reset global state parameters.
        mCmdParams = null;
        mIconLoadState = LOAD_NO_ICON;
        // only proactive command messages are processed.
        if (berTlv.getTag() != BerTlv.BER_PROACTIVE_COMMAND_TAG) {
            sendCmdParams(ResultCode.CMD_TYPE_NOT_UNDERSTOOD);
            return;
        }
        boolean cmdPending = false;
        List<ComprehensionTlv> ctlvs = berTlv.getComprehensionTlvs();
        // process command dtails from the tlv list.
        CommandDetails cmdDet = processCommandDetails(ctlvs);
        if (cmdDet == null) {
            sendCmdParams(ResultCode.CMD_TYPE_NOT_UNDERSTOOD);
            return;
        }

        // extract command type enumeration from the raw value stored inside
        // the Command Details object.
        AppInterface.CommandType cmdType = AppInterface.CommandType
                .fromInt(cmdDet.typeOfCommand);
        if (cmdType == null) {
            // This PROACTIVE COMMAND is presently not handled. Hence set
            // result code as BEYOND_TERMINAL_CAPABILITY in TR.
            mCmdParams = new CommandParams(cmdDet);
            sendCmdParams(ResultCode.BEYOND_TERMINAL_CAPABILITY);
            return;
        }

        try {
            switch (cmdType) {
            case SET_UP_EVENT_LIST:
                cmdPending = processSetUpEventList(cmdDet, ctlvs);
                break;
            case SET_UP_MENU:
                cmdPending = processSelectItem(cmdDet, ctlvs);
                break;
            case SELECT_ITEM:
                cmdPending = processSelectItem(cmdDet, ctlvs);
                break;
            case DISPLAY_TEXT:
                cmdPending = processDisplayText(cmdDet, ctlvs);
                break;
             case SET_UP_IDLE_MODE_TEXT:
                 cmdPending = processSetUpIdleModeText(cmdDet, ctlvs);
                 break;
             case GET_INKEY:
                cmdPending = processGetInkey(cmdDet, ctlvs);
                break;
             case GET_INPUT:
                 cmdPending = processGetInput(cmdDet, ctlvs);
                 break;
             case SEND_DTMF:
             case SEND_SMS:
             case SEND_SS:
             case SEND_USSD:
                 cmdPending = processEventNotify(cmdDet, ctlvs);
                 break;
             case SET_UP_CALL:
                 cmdPending = processSetupCall(cmdDet, ctlvs);
                 break;
             case REFRESH:
                processRefresh(cmdDet, ctlvs);
                cmdPending = false;
                break;
             case LAUNCH_BROWSER:
                 cmdPending = processLaunchBrowser(cmdDet, ctlvs);
                 break;
             case PLAY_TONE:
                cmdPending = processPlayTone(cmdDet, ctlvs);
                break;
             case PROVIDE_LOCAL_INFORMATION:
                cmdPending = processProvideLocalInfo(cmdDet, ctlvs);
                break;
             case LANGUAGE_NOTIFICATION:
                cmdPending = processLanguageNotification(cmdDet, ctlvs);
                break;
            case OPEN_CHANNEL:
                cmdPending = processOpenChannel(cmdDet, ctlvs);
                break;
            case CLOSE_CHANNEL:
                cmdPending = processCloseChannel(cmdDet, ctlvs);
                break;
            case RECEIVE_DATA:
                cmdPending = processReceiveData(cmdDet, ctlvs);
                break;
            case SEND_DATA:
                cmdPending = processSendData(cmdDet, ctlvs);
                break;
            case GET_CHANNEL_STATUS:
                cmdPending = processGetChannelStatus(cmdDet, ctlvs);
                break;
             case ACTIVATE:
                cmdPending = processActivate(cmdDet, ctlvs);
                break;
            default:
                // unsupported proactive commands
                mCmdParams = new CommandParams(cmdDet);
                sendCmdParams(ResultCode.BEYOND_TERMINAL_CAPABILITY);
                return;
            }
        } catch (ResultException e) {
            CatLog.d(this, "make: caught ResultException e=" + e);
            mCmdParams = new CommandParams(cmdDet);
            sendCmdParams(e.result());
            return;
        }
        if (!cmdPending) {
            sendCmdParams(ResultCode.OK);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
        case MSG_ID_LOAD_ICON_DONE:
            sendCmdParams(setIcons(msg.obj));
            break;
        }
    }

    private ResultCode setIcons(Object data) {
        Bitmap[] icons = null;
        int iconIndex = 0;

        if (data == null) {
            return ResultCode.OK;
        }
        switch(mIconLoadState) {
        case LOAD_SINGLE_ICON:
            mCmdParams.setIcon((Bitmap) data);
            break;
        case LOAD_MULTI_ICONS:
            icons = (Bitmap[]) data;
            // set each item icon.
            for (Bitmap icon : icons) {
                mCmdParams.setIcon(icon);
            }
            break;
        }
        return ResultCode.OK;
    }

    private void sendCmdParams(ResultCode resCode) {
        mCaller.sendMsgParamsDecoded(resCode, mCmdParams);
    }

    /**
     * Search for a COMPREHENSION-TLV object with the given tag from a list
     *
     * @param tag A tag to search for
     * @param ctlvs List of ComprehensionTlv objects used to search in
     *
     * @return A ComprehensionTlv object that has the tag value of {@code tag}.
     *         If no object is found with the tag, null is returned.
     */
    private ComprehensionTlv searchForTag(ComprehensionTlvTag tag,
            List<ComprehensionTlv> ctlvs) {
        Iterator<ComprehensionTlv> iter = ctlvs.iterator();
        return searchForNextTag(tag, iter);
    }

    /**
     * Search for the next COMPREHENSION-TLV object with the given tag from a
     * list iterated by {@code iter}. {@code iter} points to the object next to
     * the found object when this method returns. Used for searching the same
     * list for similar tags, usually item id.
     *
     * @param tag A tag to search for
     * @param iter Iterator for ComprehensionTlv objects used for search
     *
     * @return A ComprehensionTlv object that has the tag value of {@code tag}.
     *         If no object is found with the tag, null is returned.
     */
    private ComprehensionTlv searchForNextTag(ComprehensionTlvTag tag,
            Iterator<ComprehensionTlv> iter) {
        int tagValue = tag.value();
        while (iter.hasNext()) {
            ComprehensionTlv ctlv = iter.next();
            if (ctlv.getTag() == tagValue) {
                return ctlv;
            }
        }
        return null;
    }

    /**
     * Processes DISPLAY_TEXT proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processDisplayText(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs)
            throws ResultException {

        CatLog.d(this, "process DisplayText");

        TextMessage textMsg = new TextMessage();
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            textMsg.text = ValueParser.retrieveTextString(ctlv);
        }
        // If the tlv object doesn't exist or the it is a null object reply
        // with command not understood.
        if (textMsg.text == null) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }

        ctlv = searchForTag(ComprehensionTlvTag.IMMEDIATE_RESPONSE, ctlvs);
        if (ctlv != null) {
            textMsg.responseNeeded = false;
        }
        // parse icon identifier
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = ValueParser.retrieveIconId(ctlv);
            textMsg.iconSelfExplanatory = iconId.selfExplanatory;
        }
        // parse tone duration
        ctlv = searchForTag(ComprehensionTlvTag.DURATION, ctlvs);
        if (ctlv != null) {
            textMsg.duration = ValueParser.retrieveDuration(ctlv);
        }

        // Parse command qualifier parameters.
        textMsg.isHighPriority = (cmdDet.commandQualifier & 0x01) != 0;
        textMsg.userClear = (cmdDet.commandQualifier & 0x80) != 0;

        // According to 3GPP 31.111 chap 6.5.4 (ETSI TS 102 223 clause 6.5.4):
        // If the terminal receives an icon and either an empty or no alpha
        // text string is given by UICC, than the terminal shall reject the
        // command with general result "Command not understood by terminal".
        if ((iconId != null) && (textMsg.text == null)) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }

        mCmdParams = new DisplayTextParams(cmdDet, textMsg);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes SET_UP_IDLE_MODE_TEXT proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processSetUpIdleModeText(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process SetUpIdleModeText");

        TextMessage textMsg = new TextMessage();
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            textMsg.text = ValueParser.retrieveTextString(ctlv);
        }

        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = ValueParser.retrieveIconId(ctlv);
            textMsg.iconSelfExplanatory = iconId.selfExplanatory;
        }

        // According to 3GPP 31.111 chap 6.5.4 (ETSI TS 102 223 clause 6.5.4):
        // If the terminal receives an icon and either an empty or no alpha
        // text string is given by UICC, than the terminal shall reject the
        // command with general result "Command not understood by terminal".
        if ((iconId != null) && (textMsg.text == null)) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }
        mCmdParams = new DisplayTextParams(cmdDet, textMsg);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes GET_INKEY proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processGetInkey(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process GetInkey");

        Input input = new Input();
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            input.text = ValueParser.retrieveTextString(ctlv);
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }
        // parse icon identifier
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = ValueParser.retrieveIconId(ctlv);
        }

        // parse duration
        ctlv = searchForTag(ComprehensionTlvTag.DURATION, ctlvs);
        if (ctlv != null) {
            input.duration = ValueParser.retrieveDuration(ctlv);
        }

        input.minLen = 1;
        input.maxLen = 1;

        input.digitOnly = (cmdDet.commandQualifier & 0x01) == 0;
        input.ucs2 = (cmdDet.commandQualifier & 0x02) != 0;
        input.yesNo = (cmdDet.commandQualifier & 0x04) != 0;
        input.helpAvailable = (cmdDet.commandQualifier & 0x80) != 0;
        input.echo = true;

        mCmdParams = new GetInputParams(cmdDet, input);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes GET_INPUT proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processGetInput(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process GetInput");

        Input input = new Input();
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            input.text = ValueParser.retrieveTextString(ctlv);
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        ctlv = searchForTag(ComprehensionTlvTag.RESPONSE_LENGTH, ctlvs);
        if (ctlv != null) {
            try {
                byte[] rawValue = ctlv.getRawValue();
                int valueIndex = ctlv.getValueIndex();
                input.minLen = rawValue[valueIndex] & 0xff;
                input.maxLen = rawValue[valueIndex + 1] & 0xff;
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        ctlv = searchForTag(ComprehensionTlvTag.DEFAULT_TEXT, ctlvs);
        if (ctlv != null) {
            input.defaultText = ValueParser.retrieveTextString(ctlv);
        }
        // parse icon identifier
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = ValueParser.retrieveIconId(ctlv);
        }

        input.digitOnly = (cmdDet.commandQualifier & 0x01) == 0;
        input.ucs2 = (cmdDet.commandQualifier & 0x02) != 0;
        input.echo = (cmdDet.commandQualifier & 0x04) == 0;
        input.packed = (cmdDet.commandQualifier & 0x08) != 0;
        input.helpAvailable = (cmdDet.commandQualifier & 0x80) != 0;

        mCmdParams = new GetInputParams(cmdDet, input);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes REFRESH proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     */
    private boolean processRefresh(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) {

        CatLog.d(this, "process Refresh");

        // REFRESH proactive command is rerouted by the baseband and handled by
        // the telephony layer. IDLE TEXT should be removed for a REFRESH command
        // with "initialization" or "reset"
        switch (cmdDet.commandQualifier) {
        case REFRESH_NAA_INIT_AND_FULL_FILE_CHANGE:
        case REFRESH_NAA_INIT_AND_FILE_CHANGE:
        case REFRESH_NAA_INIT:
        case REFRESH_UICC_RESET:
            mCmdParams = new DisplayTextParams(cmdDet, null);
            break;
        }
        return false;
    }

    /**
     * Processes SELECT_ITEM proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processSelectItem(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process SelectItem");

        Menu menu = new Menu();
        IconId titleIconId = null;
        ItemsIconId itemsIconId = null;
        Iterator<ComprehensionTlv> iter = ctlvs.iterator();

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID,
                ctlvs);
        if (ctlv != null) {
            menu.title = ValueParser.retrieveAlphaId(ctlv);
        }

        while (true) {
            ctlv = searchForNextTag(ComprehensionTlvTag.ITEM, iter);
            if (ctlv != null) {
                menu.items.add(ValueParser.retrieveItem(ctlv));
            } else {
                break;
            }
        }

        // We must have at least one menu item.
        if (menu.items.size() == 0) {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        ctlv = searchForTag(ComprehensionTlvTag.ITEM_ID, ctlvs);
        if (ctlv != null) {
            // CAT items are listed 1...n while list start at 0, need to
            // subtract one.
            menu.defaultItem = ValueParser.retrieveItemId(ctlv) - 1;
        }

        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            titleIconId = ValueParser.retrieveIconId(ctlv);
            menu.titleIconSelfExplanatory = titleIconId.selfExplanatory;
        }

        ctlv = searchForTag(ComprehensionTlvTag.ITEM_ICON_ID_LIST, ctlvs);
        if (ctlv != null) {
            mIconLoadState = LOAD_MULTI_ICONS;
            itemsIconId = ValueParser.retrieveItemsIconId(ctlv);
            menu.itemsIconSelfExplanatory = itemsIconId.selfExplanatory;
        }

        boolean presentTypeSpecified = (cmdDet.commandQualifier & 0x01) != 0;
        if (presentTypeSpecified) {
            if ((cmdDet.commandQualifier & 0x02) == 0) {
                menu.presentationType = PresentationType.DATA_VALUES;
            } else {
                menu.presentationType = PresentationType.NAVIGATION_OPTIONS;
            }
        }
        menu.softKeyPreferred = (cmdDet.commandQualifier & 0x04) != 0;
        menu.helpAvailable = (cmdDet.commandQualifier & 0x80) != 0;

        mCmdParams = new SelectItemParams(cmdDet, menu, titleIconId != null);

        // Load icons data if needed.
        switch(mIconLoadState) {
        case LOAD_NO_ICON:
            return false;
        case LOAD_SINGLE_ICON:
            mIconLoader.loadIcon(titleIconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            break;
        case LOAD_MULTI_ICONS:
            int[] recordNumbers = itemsIconId.recordNumbers;
            if (titleIconId != null) {
                // Create a new array for all the icons (title and items).
                recordNumbers = new int[itemsIconId.recordNumbers.length + 1];
                recordNumbers[0] = titleIconId.recordNumber;
                System.arraycopy(itemsIconId.recordNumbers, 0, recordNumbers,
                        1, itemsIconId.recordNumbers.length);
            }
            mIconLoader.loadIcons(recordNumbers, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            break;
        }
        return true;
    }

    /**
     * Processes EVENT_NOTIFY message from baseband.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     */
    private boolean processEventNotify(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process EventNotify");

        TextMessage textMsg = new TextMessage();
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID,
                ctlvs);
        textMsg.text = ValueParser.retrieveAlphaId(ctlv);

        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = ValueParser.retrieveIconId(ctlv);
            textMsg.iconSelfExplanatory = iconId.selfExplanatory;
        }

        textMsg.responseNeeded = false;
        mCmdParams = new DisplayTextParams(cmdDet, textMsg);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes SET_UP_EVENT_LIST proactive command from the SIM card.
     *
     * @param cmdDet Command Details object retrieved.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     */
    private boolean processSetUpEventList(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) {

        CatLog.d(this, "process SetUpEventList");
        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.EVENT_LIST,ctlvs);
        if (ctlv != null) {
            byte[] rawValue = ctlv.getRawValue();
            int valueIndex = ctlv.getValueIndex();
            int valueLength = ctlv.getLength();
            byte[] eventList;
            if (valueLength > 0 && rawValue.length >= (valueIndex + valueLength)) {
                eventList = new byte[valueLength];
                if (eventList != null) {
                    System.arraycopy(rawValue, valueIndex, eventList, 0, valueLength);
                }
            } else {
                eventList = null;
            }
            mCmdParams = new EventListParams(cmdDet, eventList);
        }
        return false;
    }

    /**
     * Processes LAUNCH_BROWSER proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processLaunchBrowser(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process LaunchBrowser");

        TextMessage confirmMsg = new TextMessage();
        IconId iconId = null;
        String url = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.URL, ctlvs);
        if (ctlv != null) {
            try {
                byte[] rawValue = ctlv.getRawValue();
                int valueIndex = ctlv.getValueIndex();
                int valueLen = ctlv.getLength();
                if (valueLen > 0) {
                    url = GsmAlphabet.gsm8BitUnpackedToString(rawValue,
                            valueIndex, valueLen);
                } else {
                    url = null;
                }
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        }

        // parse alpha identifier.
        ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID, ctlvs);
        confirmMsg.text = ValueParser.retrieveAlphaId(ctlv);

        // parse icon identifier
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = ValueParser.retrieveIconId(ctlv);
            confirmMsg.iconSelfExplanatory = iconId.selfExplanatory;
        }

        // parse command qualifier value.
        LaunchBrowserMode mode;
        switch (cmdDet.commandQualifier) {
        case 0x00:
        default:
            mode = LaunchBrowserMode.LAUNCH_IF_NOT_ALREADY_LAUNCHED;
            break;
        case 0x02:
            mode = LaunchBrowserMode.USE_EXISTING_BROWSER;
            break;
        case 0x03:
            mode = LaunchBrowserMode.LAUNCH_NEW_BROWSER;
            break;
        }

        mCmdParams = new LaunchBrowserParams(cmdDet, confirmMsg, url, mode);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

     /**
     * Processes PLAY_TONE proactive command from the SIM card.
     *
     * @param cmdDet Command Details container object.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.t
     * @throws ResultException
     */
    private boolean processPlayTone(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {

        CatLog.d(this, "process PlayTone");

        Tone tone = null;
        TextMessage textMsg = new TextMessage();
        Duration duration = null;
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TONE, ctlvs);
        if (ctlv != null) {
            // Nothing to do for null objects.
            if (ctlv.getLength() > 0) {
                try {
                    byte[] rawValue = ctlv.getRawValue();
                    int valueIndex = ctlv.getValueIndex();
                    int toneVal = rawValue[valueIndex];
                    tone = Tone.fromInt(toneVal);
                } catch (IndexOutOfBoundsException e) {
                    throw new ResultException(
                            ResultCode.CMD_DATA_NOT_UNDERSTOOD);
                }
            }
        }
        // parse alpha identifier
        ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID, ctlvs);
        if (ctlv != null) {
            textMsg.text = ValueParser.retrieveAlphaId(ctlv);
        }
        // parse tone duration
        ctlv = searchForTag(ComprehensionTlvTag.DURATION, ctlvs);
        if (ctlv != null) {
            duration = ValueParser.retrieveDuration(ctlv);
        }
        // parse icon identifier
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = ValueParser.retrieveIconId(ctlv);
            textMsg.iconSelfExplanatory = iconId.selfExplanatory;
        }

        boolean vibrate = (cmdDet.commandQualifier & 0x01) != 0x00;

        textMsg.responseNeeded = false;
        mCmdParams = new PlayToneParams(cmdDet, textMsg, tone, duration, vibrate);

        if (iconId != null) {
            mIconLoadState = LOAD_SINGLE_ICON;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    /**
     * Processes SETUP_CALL proactive command from the SIM card.
     *
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     */
    private boolean processSetupCall(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {
        CatLog.d(this, "process SetupCall");

        Iterator<ComprehensionTlv> iter = ctlvs.iterator();
        ComprehensionTlv ctlv = null;
        // User confirmation phase message.
        TextMessage confirmMsg = new TextMessage();
        // Call set up phase message.
        TextMessage callMsg = new TextMessage();
        IconId confirmIconId = null;
        IconId callIconId = null;

        // get confirmation message string.
        ctlv = searchForNextTag(ComprehensionTlvTag.ALPHA_ID, iter);
        confirmMsg.text = ValueParser.retrieveAlphaId(ctlv);

        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            confirmIconId = ValueParser.retrieveIconId(ctlv);
            confirmMsg.iconSelfExplanatory = confirmIconId.selfExplanatory;
        }

        // get call set up message string.
        ctlv = searchForNextTag(ComprehensionTlvTag.ALPHA_ID, iter);
        if (ctlv != null) {
            callMsg.text = ValueParser.retrieveAlphaId(ctlv);
        }

        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            callIconId = ValueParser.retrieveIconId(ctlv);
            callMsg.iconSelfExplanatory = callIconId.selfExplanatory;
        }

        mCmdParams = new CallSetupParams(cmdDet, confirmMsg, callMsg);

        if (confirmIconId != null || callIconId != null) {
            mIconLoadState = LOAD_MULTI_ICONS;
            int[] recordNumbers = new int[2];
            recordNumbers[0] = confirmIconId != null
                    ? confirmIconId.recordNumber : -1;
            recordNumbers[1] = callIconId != null ? callIconId.recordNumber
                    : -1;

            mIconLoader.loadIcons(recordNumbers, this
                    .obtainMessage(MSG_ID_LOAD_ICON_DONE));
            return true;
        }
        return false;
    }

    private boolean processProvideLocalInfo(CommandDetails cmdDet, List<ComprehensionTlv> ctlvs)
            throws ResultException {
        CatLog.d(this, "process ProvideLocalInfo");
        switch (cmdDet.commandQualifier) {
            case DTTZ_SETTING:
                CatLog.d(this, "PLI [DTTZ_SETTING]");
                mCmdParams = new CommandParams(cmdDet);
                break;
            case LANGUAGE_SETTING:
                CatLog.d(this, "PLI [LANGUAGE_SETTING]");
                mCmdParams = new CommandParams(cmdDet);
                break;
            case SEARCH_MODE_SETTING:
                CatLog.d(this, "PLI [SEARCH_MODE_SETTING]");
                mCmdParams = new CommandParams(cmdDet);
                break;
            default:
                CatLog.d(this, "PLI[" + cmdDet.commandQualifier + "] Command Not Supported");
                mCmdParams = new CommandParams(cmdDet);
                throw new ResultException(ResultCode.BEYOND_TERMINAL_CAPABILITY);
        }
        return false;
    }

    private boolean processLanguageNotification(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {
        CatLog.d(this, "process LanguageNotification");
        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.LANGUAGE,
                ctlvs);
        String lang = "";
        if (ctlv != null) {
            try {
                byte[] rawValue = ctlv.getRawValue();
                int valueIndex = ctlv.getValueIndex();
                int valueLen = ctlv.getLength();
                /*
                 * As per ETSI TS 102 223 section 8.45, Each langugae code is
                 * a pair of alpha-numeric characters defined in ISO 639. Each
                 * alpha-numeric character shall be coded on one byte using the
                 * SMS default -bit coded alphabet as defined in TS 23.038 [3] with
                 * with bit 8 set to 0.
                 */
                if (valueLen > 0) {
                    lang = GsmAlphabet.gsm8BitUnpackedToString(rawValue, valueIndex, valueLen);
                }
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        }
        mCmdParams = new LanguageParams(cmdDet, lang);
        return false;
    }

    private boolean processOpenChannel(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {
        CatLog.d(this, "process OpenChannel");
        TextMessage confirmMsg = new TextMessage();
        int bufSize = 0;
        byte[] destinationAddress = null;
        String networkAccessName = null;
        String userLogin = null;
        String userPassword = null;

        confirmMsg.responseNeeded = false;
        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID,ctlvs);
        if (ctlv != null) {
            confirmMsg.text = ValueParser.retrieveAlphaId(ctlv);
            if (confirmMsg.text != null) {
                confirmMsg.responseNeeded = true;
            }
        }

        // BUFFER_SIZE TLV is mandatory for all supported OPEN_CHANNEL PCs
        ctlv = searchForTag(ComprehensionTlvTag.BUFFER_SIZE, ctlvs);
        if (ctlv != null) {
            bufSize = ValueParser.retrieveBufferSize(ctlv);
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        Iterator<ComprehensionTlv> iter = ctlvs.iterator();
        InterfaceTransportLevel itl = null;
        ctlv = searchForNextTag(ComprehensionTlvTag.IF_TRANS_LEVEL, iter);
        if (ctlv != null) {
            itl = ValueParser.retrieveInterfaceTransportLevel(ctlv);
            // Check for conditional/optional destination address located after itl TLV.
            // Note: This is NOT the local address TLV.
            if (iter != null) {
                ctlv = searchForNextTag(ComprehensionTlvTag.OTHER_ADDRESS, iter);
            }
            if (ctlv != null) {
                destinationAddress = ValueParser.retrieveOtherAddress(ctlv);
                if (destinationAddress.length != 4) {
                    // Currently only IPV4 support
                    throw new ResultException(ResultCode.CMD_TYPE_NOT_UNDERSTOOD);
                }
            }
        }

        BearerDescription bearerDescription = null;
        ctlv = searchForTag(ComprehensionTlvTag.BEARER_DESC, ctlvs);
        if (ctlv != null) {
            bearerDescription = ValueParser.retrieveBearerDescription(ctlv);
            CatLog.d(this, "processOpenChannel bearer: " + bearerDescription.type.value()
                    + " param.len: " + bearerDescription.parameters.length);
        }

        iter = ctlvs.iterator();
        // network access name
        if (iter != null) {
            ctlv = searchForNextTag(ComprehensionTlvTag.NETWORK_ACCESS_NAME, iter);
            if (ctlv != null) {
                networkAccessName = ValueParser.retrieveNetworkAccessName(ctlv);
                ctlv = searchForNextTag(ComprehensionTlvTag.TEXT_STRING, iter);
                if (ctlv != null) {
                    userLogin = ValueParser.retrieveTextString(ctlv);
                }
                ctlv = searchForNextTag(ComprehensionTlvTag.TEXT_STRING, iter);
                if (ctlv != null) {
                    userPassword = ValueParser.retrieveTextString(ctlv);
                }
            }
        }

        if (itl != null && bearerDescription == null) {
            if (itl.protocol == TransportProtocol.TCP_SERVER) {
                // OPEN CHANNEL related to UICC Server Mode
            } else if (itl.protocol == TransportProtocol.TCP_CLIENT_LOCAL
                    || itl.protocol == TransportProtocol.UDP_CLIENT_LOCAL) {
                // OPEN CHANNEL related to Terminal Server Mode
            } else {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        } else if (bearerDescription != null) {
            if (bearerDescription.type == BearerType.DEFAULT_BEARER) {
                // OPEN CHANNEL related to Default (network) Bearer
            } else if (bearerDescription.type == BearerType.MOBILE_PS
                    || bearerDescription.type == BearerType.MOBILE_PS_EXTENDED_QOS
                    || bearerDescription.type == BearerType.MOBILE_PS_EXTENDED_EPS_QOS) {
                // OPEN CHANNEL related to packet data service bearer
            } else {
                throw new ResultException(ResultCode.BEYOND_TERMINAL_CAPABILITY);
            }

            if (itl != null) {
                if (itl.protocol != TransportProtocol.TCP_CLIENT_REMOTE
                        && itl.protocol != TransportProtocol.UDP_CLIENT_REMOTE) {
                    throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
                }
                if (destinationAddress == null) {
                    throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
                }
            } else {
                // It is not mandatory to have IF_TRANS_LEVEL with conditional destination address
                // but how should we behave if we don't know if we should use UDP/TCP and more
                // interesting what server IP to connect to?
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        String addressString;
        try {
             addressString = InetAddress.getByAddress(destinationAddress).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            addressString = "unknown";
        }

        String msg = "processOpenChannel bufSize=" + bufSize
                + " protocol=" + (itl != null ? itl.protocol : "undefined")
                + " port=" + (itl != null ? itl.port : "undefined")
                + " destination=" + addressString
                + " APN=" + (networkAccessName != null ? networkAccessName : "undefined")
                + " user/password=" + (userLogin != null ? userLogin : "---")
                + "/" + (userPassword != null ? userPassword : "---");

        CatLog.d(this, msg);

        mCmdParams = new OpenChannelParams(cmdDet, confirmMsg, bufSize,
                itl, destinationAddress, bearerDescription, networkAccessName,
                userLogin, userPassword);
        return false;
    }

    private boolean processCloseChannel(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {
        CatLog.d(this, "process CloseChannel");
        /*
         * Check device identities.
         * Destination device id has to be between 0x21 and 0x27.
         */
        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.DEVICE_IDENTITIES, ctlvs);
        int channel = 0;
        if (ctlv != null) {
            channel = ValueParser.retrieveDeviceIdentities(ctlv).destinationId;
            if ((channel < 0x21) || (channel > 0x27)) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            } else {
                channel &= 0x0f;
            }
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        mCmdParams = new CloseChannelParams(cmdDet, channel);
        return false;
    }

    private boolean processReceiveData(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {
        CatLog.d(this, "process ReceiveData");
        /*
         * Check device identities.
         * Destination device id has to be between 0x21 and 0x27.
         */
        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.DEVICE_IDENTITIES, ctlvs);
        int channel = 0;
        if (ctlv != null) {
            channel = ValueParser.retrieveDeviceIdentities(ctlv).destinationId;
            if ((channel < 0x21) || (channel > 0x27)) {
                CatLog.d( this, "Invalid Channel number given: " + channel);
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            } else {
                channel &= 0x0f;
            }
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        /*
         * Get requested data length.
         */
        ctlv = searchForTag(ComprehensionTlvTag.CHANNEL_DATA_LENGTH, ctlvs);
        int datLen = 0;
        if (ctlv != null) {
            datLen = ValueParser.retrieveChannelDataLength(ctlv);
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        mCmdParams = new ReceiveDataParams(cmdDet, channel, datLen);
        return false;
    }

    private boolean processSendData(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {
        CatLog.d(this, "process SendData");
        /*
         * Check device identities.
         * Destination device id has to be between 0x21 and 0x27.
         */
        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.DEVICE_IDENTITIES, ctlvs);
        int channel = 0;
        if (ctlv != null) {
            channel = ValueParser.retrieveDeviceIdentities(ctlv).destinationId;

            if ((channel < 0x21) || (channel > 0x27)) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            } else {
                channel &= 0x0f;
            }
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        /*
         * Get submitted data
         */
        ctlv = searchForTag(ComprehensionTlvTag.CHANNEL_DATA, ctlvs);
        byte[] data = null;
        if (ctlv != null) {
            data = ValueParser.retrieveChannelData(ctlv);
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        mCmdParams = new SendDataParams(cmdDet, channel, data);
        return false;
    }

    private boolean processGetChannelStatus(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {
        CatLog.d(this, "process GetChannelStatus");

        mCmdParams = new GetChannelStatusParams(cmdDet);
        return false;
    }

    /**
     * Processes ACTIVATE proactive command from the SIM card.
     *
     * @param cmdDet Command Details object retrieved.
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional
     *         asynchronous processing is required.
     */
    private boolean processActivate(CommandDetails cmdDet,
            List<ComprehensionTlv> ctlvs) throws ResultException {
        CatLog.d(this, "process Activate");

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.ACTIVATE_DESCRIPTOR, ctlvs);
        if (ctlv != null) {
            try {
                if (ctlv.getLength() != 1) {
                    throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
                }
                mCmdParams = new ActivateParams(cmdDet,
                        (ctlv.getRawValue())[ctlv.getValueIndex()]);
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        }
        return false;
    }
}

/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony.ims;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.R;

import dalvik.system.DexClassLoader;

public class ImsPhoneFactory {

    private static final String LOG_TAG = "ImsPhoneFactory";

    private static ImsPhoneBase sImsPhone = null;

    /**
     * Makes an {@link ImsPhone} object.
     *
     * @param context {@code Context} needed to create a Phone object
     * @param phoneNotifier {@code PhoneNotifier} needed to create a Phone
     *            object
     * @param parentPhone {@code PhoneBase} needed to create a Phone
     * @return the {@code ImsPhone} object or null if parentPhone is null
     */
    public static ImsPhoneBase makePhone(Context context,
            PhoneNotifier notifier, PhoneBase parentPhone, boolean unitTestMode) {
        ImsPhoneCreator creator = null;

        if (parentPhone != null) {
            Log.d(LOG_TAG, "Loading IMS phone");
            try {
                String jarPath = context.getResources().getString(
                        R.string.ims_creator_jar_file_property);
                String className = context.getResources().getString(
                        R.string.ims_creator_class_property);

                Log.d(LOG_TAG, "Loading JAR from: " + jarPath);
                DexClassLoader classLoader = new DexClassLoader(jarPath,
                        new ContextWrapper(parentPhone.getContext())
                                .getCacheDir().getAbsolutePath(), null,
                        ClassLoader.getSystemClassLoader());

                Log.d(LOG_TAG, "Loading class: " + className);
                creator = (ImsPhoneCreator) classLoader.loadClass(className)
                        .getConstructor().newInstance();

                sImsPhone = creator.createImsPhone(context, notifier, parentPhone);
            } catch (Exception ex) {
                Log.e(LOG_TAG, "Could not instantiate IMS phone: "
                        + ex);
            }
        } else {
            Log.e(LOG_TAG, "parentPhone is null");
        }
        return sImsPhone;
    }

    public static ImsPhoneBase getImsPhone() {
        return sImsPhone;
    }
}

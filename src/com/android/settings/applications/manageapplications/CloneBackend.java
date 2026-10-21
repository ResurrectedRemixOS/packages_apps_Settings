/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settings.applications.manageapplications;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.content.pm.PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_URI;
import static android.content.pm.PackageManager.INSTALL_REASON_USER;
import static android.os.UserManager.USER_TYPE_PROFILE_CLONE;

import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.fragment.app.FragmentActivity;

import com.android.settings.Utils;

import java.util.HashSet;

/**
 * Handles clone user creation and clone app install/uninstall.
 */
public class CloneBackend {

    public static final String TAG = "CloneBackend";
    public static final int SUCCESS = 0;
    private static final int ERROR_CREATING_CLONE_USER = 1;
    private static final int ERROR_STARTING_CLONE_USER = 2;
    private static final int ERROR_CLONING_PACKAGE = 3;
    private static CloneBackend sInstance;
    private Context mContext;
    private int mCloneUserId;

    private CloneBackend(Context context) {
        mContext = context;
        mCloneUserId = Utils.getCloneUserId(context);
    }

    /**
     * @param context
     * @return a CloneBackend object
     */
    public static CloneBackend getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new CloneBackend(context);
        }
        return sInstance;
    }

    /**
     * Starts activity to uninstall cloned app.
     *
     * <p> Invokes {@link com.android.packageinstaller.UninstallerActivity} which then displays the
     * dialog to the user and handles actual uninstall.
     */
    void uninstallClonedApp(String packageName, boolean allUsers, FragmentActivity activity) {
        // Create new intent to launch Uninstaller activity.
        Uri packageUri = Uri.parse("package:" + packageName);
        Intent uninstallIntent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri);
        uninstallIntent.putExtra(Intent.EXTRA_UNINSTALL_ALL_USERS, allUsers);
        uninstallIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(mCloneUserId));
        // Trigger uninstall as clone user.
        activity.startActivityAsUser(uninstallIntent, UserHandle.of(mCloneUserId));
    }

    /**
     * Installs another instance of given package in clone user.
     *
     * <p> Creates clone user if doesn't exist and starts the new user before installing app.
     * @param packageName
     * @return error/success code
     */
    public int installCloneApp(String packageName) {
        String userName = "cloneUser";
        UserHandle cloneUserHandle = null;
        boolean newlyCreated = false;

        // Create clone user if not already exists.
        if (mCloneUserId == -1) {
            UserManager um = mContext.getSystemService(UserManager.class);
            try {
                cloneUserHandle = um.createProfile(userName, USER_TYPE_PROFILE_CLONE,
                        new HashSet<>());
            } catch (Exception e) {
                if (ManageApplications.DEBUG) {
                    Log.e(TAG, "Error occurred creating clone user" + e.getMessage());
                }
                return ERROR_CREATING_CLONE_USER;
            }

            if (cloneUserHandle != null) {
                mCloneUserId = cloneUserHandle.getIdentifier();
                newlyCreated = true;
                if (ManageApplications.DEBUG) {
                    Log.d(TAG, "Created clone user " + mCloneUserId);
                }
            } else {
                mCloneUserId = -1;
            }
        }

        if (mCloneUserId > 0) {
            // If clone user is newly created for the first time, then start this user.
            if (newlyCreated) {
                IActivityManager am = ActivityManagerNative.getDefault();
                try {
                    am.startProfile(mCloneUserId);
                } catch (RemoteException e) {
                    if (ManageApplications.DEBUG) {
                        Log.e(TAG, "Error starting clone user " + e.getMessage());
                    }
                    return ERROR_STARTING_CLONE_USER;
                }
            }

            // A package we previously hid (it came with the profile, not a clone) has to be
            // enabled again before it can be installed for this user.
            setClonePackageState(packageName, COMPONENT_ENABLED_STATE_ENABLED, 0);

            // Install given app in clone user
            int res = 0;
            try {
                res = AppGlobals.getPackageManager().installExistingPackageAsUser(
                        packageName, mCloneUserId,
                        INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS, INSTALL_REASON_USER, null);
            } catch (RemoteException e) {
                if (ManageApplications.DEBUG) {
                    Log.e(TAG, "Error installing package" + packageName + " in clone user."
                            + e.getMessage());
                }
                hideAutoInstalledLaunchers(null);
                return ERROR_CLONING_PACKAGE;
            }

            if (res == INSTALL_FAILED_INVALID_URI) {
                if (ManageApplications.DEBUG) {
                    Log.e(TAG, "Package " + packageName + " doesn't exist.");
                }
                hideAutoInstalledLaunchers(null);
                return ERROR_CLONING_PACKAGE;
            }
        }

        if (ManageApplications.DEBUG) {
            Log.i(TAG, "Package " + packageName + " cloned successfully.");
        }
        hideAutoInstalledLaunchers(packageName);
        return SUCCESS;
    }

    /**
     * A new clone profile is handed every system package, and the ones with a launcher
     * show up in the drawer with a clone badge. Those are not apps the user cloned.
     * Input methods stay installed; only their launcher activity is hidden.
     *
     * @param keep package just cloned, skipped even if its install reason is not visible yet
     */
    public void hideAutoInstalledLaunchers(String keep) {
        if (mCloneUserId <= 0) {
            mCloneUserId = Utils.getCloneUserId(mContext);
        }
        if (mCloneUserId <= 0) {
            return;
        }
        PackageManager pm = mContext.getPackageManager();
        String caller = mContext.getPackageName();
        UserHandle cloneUser = UserHandle.of(mCloneUserId);
        HashSet<String> imes = new HashSet<>();
        Intent ime = new Intent("android.view.InputMethod");
        for (ResolveInfo info : pm.queryIntentServicesAsUser(ime, 0, mCloneUserId)) {
            if (info.serviceInfo != null) {
                imes.add(info.serviceInfo.packageName);
            }
        }
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        HashSet<String> seen = new HashSet<>();
        for (ResolveInfo info : pm.queryIntentActivitiesAsUser(launcher, 0, mCloneUserId)) {
            if (info.activityInfo == null) {
                continue;
            }
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(keep)) {
                continue;
            }
            ApplicationInfo ai;
            try {
                ai = pm.getApplicationInfoAsUser(pkg, 0, mCloneUserId);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                continue;
            }
            try {
                if (pm.getInstallReason(pkg, cloneUser) == INSTALL_REASON_USER) {
                    continue;
                }
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (imes.contains(pkg)) {
                if (seen.add(pkg + "/" + info.activityInfo.name)) {
                    setCloneComponentState(new ComponentName(pkg, info.activityInfo.name),
                            COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP, caller);
                }
                continue;
            }
            if (seen.add(pkg)) {
                setClonePackageState(pkg, COMPONENT_ENABLED_STATE_DISABLED_USER, 0);
            }
        }
    }

    private void setClonePackageState(String packageName, int state, int flags) {
        try {
            AppGlobals.getPackageManager().setApplicationEnabledSetting(
                    packageName, state, flags, mCloneUserId, mContext.getPackageName());
        } catch (RemoteException | SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Couldn't change " + packageName + " on the clone profile", e);
        }
    }

    private void setCloneComponentState(ComponentName component, int state, int flags,
            String caller) {
        try {
            AppGlobals.getPackageManager().setComponentEnabledSetting(
                    component, state, flags, mCloneUserId, caller);
        } catch (RemoteException | SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Couldn't change " + component + " on the clone profile", e);
        }
    }

    public int getCloneUserId() {
        return mCloneUserId;
    }

    /**
     * Resets {@link #mCloneUserId} to -1.
     * Typically called after the cloneUser is removed, so that the obsolete clonedUserId present
     * with the CloneBackend instance can be cleared.
     */
    public void resetCloneUserId() {
        mCloneUserId = -1;
    }
}

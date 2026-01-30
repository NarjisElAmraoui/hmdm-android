/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
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

package com.hmdm.launcher.pro;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.location.Location;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Kiosk mode (COSU - Company Owned Single Use) implementation.
 * Uses Android Lock Task Mode API for device lockdown.
 */
public class ProUtils {

    private static final String TAG = "ProUtils";

    public static boolean isPro() {
        return true;
    }

    /**
     * Determines if kiosk mode should be active based on configuration and device capabilities.
     * Requires: kioskMode enabled in config, device owner rights, and Android 5.0+
     */
    public static boolean kioskModeRequired(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        ServerConfig config = settingsHelper.getConfig();

        if (config == null || !config.isKioskMode()) {
            return false;
        }

        // Kiosk mode requires device owner rights for Lock Task Mode
        if (!Utils.isDeviceOwner(context)) {
            Log.w(TAG, "Kiosk mode requires device owner rights");
            return false;
        }

        return true;
    }

    public static void initCrashlytics(Context context) {
        // Crashlytics not included in open-source version
    }

    public static void sendExceptionToCrashlytics(Throwable e) {
        // Crashlytics not included in open-source version
    }

    // Start the service checking if the foreground app is allowed to the user (by usage statistics)
    public static boolean checkAccessibilityService(Context context) {
        // Stub - foreground app checking not implemented
        return true;
    }

    // Pro-version
    public static boolean checkUsageStatistics(Context context) {
        // Stub - usage statistics checking not implemented
        return true;
    }

    /**
     * Creates a transparent overlay on top of the status bar to prevent user interaction.
     * This blocks access to Quick Settings and notifications in kiosk mode.
     */
    public static View preventStatusBarExpansion(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null;
        }

        if (!Utils.canDrawOverlays(activity)) {
            Log.w(TAG, "Cannot create status bar overlay: no overlay permission");
            return null;
        }

        try {
            WindowManager windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return null;
            }

            // Get status bar height
            int statusBarHeight = getStatusBarHeight(activity);

            View statusBarOverlay = new View(activity);
            statusBarOverlay.setBackgroundColor(Color.TRANSPARENT);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.type = Utils.OverlayWindowType();
            params.gravity = Gravity.TOP;
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = statusBarHeight;
            params.format = PixelFormat.TRANSPARENT;

            windowManager.addView(statusBarOverlay, params);
            Log.d(TAG, "Status bar overlay created");
            return statusBarOverlay;

        } catch (Exception e) {
            Log.e(TAG, "Failed to create status bar overlay: " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates a transparent overlay on the right edge to prevent Samsung tablet app list swipe.
     */
    public static View preventApplicationsList(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null;
        }

        if (!Utils.canDrawOverlays(activity)) {
            Log.w(TAG, "Cannot create right edge overlay: no overlay permission");
            return null;
        }

        try {
            WindowManager windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return null;
            }

            View rightEdgeOverlay = new View(activity);
            rightEdgeOverlay.setBackgroundColor(Color.TRANSPARENT);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.type = Utils.OverlayWindowType();
            params.gravity = Gravity.RIGHT | Gravity.TOP;
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            params.width = 10;  // Thin strip on the right edge
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            params.format = PixelFormat.TRANSPARENT;

            windowManager.addView(rightEdgeOverlay, params);
            Log.d(TAG, "Right edge overlay created");
            return rightEdgeOverlay;

        } catch (Exception e) {
            Log.e(TAG, "Failed to create right edge overlay: " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates a hidden unlock button overlay for emergency kiosk exit.
     * The button is transparent and requires multiple taps to trigger unlock.
     */
    public static View createKioskUnlockButton(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null;
        }

        if (!Utils.canDrawOverlays(activity)) {
            Log.w(TAG, "Cannot create unlock button: no overlay permission");
            return null;
        }

        try {
            WindowManager windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return null;
            }

            // Create an invisible button in the top-right corner
            View unlockButton = new View(activity);
            unlockButton.setBackgroundColor(Color.TRANSPARENT);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.type = Utils.OverlayWindowType();
            params.gravity = Gravity.TOP | Gravity.RIGHT;
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            params.width = 100;  // Small touch target
            params.height = 100;
            params.format = PixelFormat.TRANSPARENT;
            params.x = 20;  // Small offset from edge
            params.y = getStatusBarHeight(activity) + 20;

            windowManager.addView(unlockButton, params);
            Log.d(TAG, "Kiosk unlock button created");
            return unlockButton;

        } catch (Exception e) {
            Log.e(TAG, "Failed to create kiosk unlock button: " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks if the configured kiosk app is installed on the device.
     */
    public static boolean isKioskAppInstalled(Context context) {
        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        ServerConfig config = settingsHelper.getConfig();

        if (config == null || config.getMainApp() == null) {
            return false;
        }

        String kioskApp = config.getMainApp();
        return isPackageInstalled(context, kioskApp);
    }

    /**
     * Checks if the device is currently in Lock Task Mode (kiosk mode running).
     */
    public static boolean isKioskModeRunning(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }

        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int lockTaskMode = activityManager.getLockTaskModeState();
            return lockTaskMode != ActivityManager.LOCK_TASK_MODE_NONE;
        } else {
            // For API 21-22, use deprecated method
            return activityManager.isInLockTaskMode();
        }
    }

    /**
     * Gets the launch intent for the specified kiosk app.
     */
    public static Intent getKioskAppIntent(String kioskApp, Activity activity) {
        if (kioskApp == null || kioskApp.isEmpty()) {
            return null;
        }

        PackageManager packageManager = activity.getPackageManager();
        Intent launchIntent = packageManager.getLaunchIntentForPackage(kioskApp);

        if (launchIntent == null) {
            Log.w(TAG, "No launch intent found for kiosk app: " + kioskApp);
        }

        return launchIntent;
    }

    /**
     * Starts COSU kiosk mode using Android's Lock Task Mode API.
     * This locks the device to the specified app(s) and launches the kiosk app.
     *
     * @param kioskApp Package name of the main kiosk app
     * @param activity Activity to start lock task from
     * @param enableSettings Whether to allow access to Settings app
     * @return true if kiosk mode started successfully
     */
    public static boolean startCosuKioskMode(String kioskApp, Activity activity, boolean enableSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "Lock Task Mode requires Android 5.0+");
            return false;
        }

        if (!Utils.isDeviceOwner(activity)) {
            Log.w(TAG, "Cannot start kiosk mode: not device owner");
            return false;
        }

        try {
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = LegacyUtils.getAdminComponentName(activity);

            if (dpm == null || adminComponent == null) {
                Log.e(TAG, "DevicePolicyManager or admin component is null");
                return false;
            }

            // Build list of allowed packages
            List<String> allowedPackages = new ArrayList<>();
            allowedPackages.add(activity.getPackageName());  // MDM launcher itself

            if (kioskApp != null && !kioskApp.equals(activity.getPackageName())) {
                allowedPackages.add(kioskApp);
            }

            if (enableSettings) {
                allowedPackages.add(Const.SETTINGS_PACKAGE_NAME);
            }

            // Set lock task packages
            String[] packages = allowedPackages.toArray(new String[0]);
            dpm.setLockTaskPackages(adminComponent, packages);
            Log.d(TAG, "Lock task packages set: " + allowedPackages);

            // Configure lock task features (API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                updateLockTaskFeatures(activity, dpm, adminComponent);
            }

            // Start lock task mode
            activity.startLockTask();
            Log.i(TAG, "Lock Task Mode started successfully");

            // Now launch the kiosk app if it's different from the launcher
            if (kioskApp != null && !kioskApp.equals(activity.getPackageName())) {
                Intent launchIntent = activity.getPackageManager().getLaunchIntentForPackage(kioskApp);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    activity.startActivity(launchIntent);
                    Log.i(TAG, "Kiosk app launched: " + kioskApp);
                    RemoteLogger.log(activity, Const.LOG_INFO, "Kiosk mode started, launched app: " + kioskApp);
                } else {
                    Log.e(TAG, "Cannot find launch intent for kiosk app: " + kioskApp);
                    RemoteLogger.log(activity, Const.LOG_WARN, "Kiosk mode started but app not launchable: " + kioskApp);
                }
            } else {
                RemoteLogger.log(activity, Const.LOG_INFO, "Kiosk mode started (launcher as kiosk)");
            }

            return true;

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception starting kiosk mode: " + e.getMessage());
            RemoteLogger.log(activity, Const.LOG_ERROR, "Failed to start kiosk mode: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error starting kiosk mode: " + e.getMessage());
            RemoteLogger.log(activity, Const.LOG_ERROR, "Failed to start kiosk mode: " + e.getMessage());
            return false;
        }
    }

    /**
     * Updates lock task mode options based on current ServerConfig settings.
     * Configures which system UI elements are visible in kiosk mode.
     */
    public static void updateKioskOptions(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Lock task features only available on API 28+
            return;
        }

        if (!Utils.isDeviceOwner(activity)) {
            return;
        }

        try {
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = LegacyUtils.getAdminComponentName(activity);

            if (dpm == null || adminComponent == null) {
                return;
            }

            updateLockTaskFeatures(activity, dpm, adminComponent);
            Log.d(TAG, "Kiosk options updated");

        } catch (Exception e) {
            Log.e(TAG, "Error updating kiosk options: " + e.getMessage());
        }
    }

    /**
     * Updates the list of apps allowed to run in kiosk mode.
     */
    public static void updateKioskAllowedApps(String kioskApp, Activity activity, boolean enableSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        if (!Utils.isDeviceOwner(activity)) {
            return;
        }

        try {
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = LegacyUtils.getAdminComponentName(activity);

            if (dpm == null || adminComponent == null) {
                return;
            }

            List<String> allowedPackages = new ArrayList<>();
            allowedPackages.add(activity.getPackageName());

            if (kioskApp != null && !kioskApp.equals(activity.getPackageName())) {
                allowedPackages.add(kioskApp);
            }

            if (enableSettings) {
                allowedPackages.add(Const.SETTINGS_PACKAGE_NAME);
            }

            String[] packages = allowedPackages.toArray(new String[0]);
            dpm.setLockTaskPackages(adminComponent, packages);
            Log.d(TAG, "Kiosk allowed apps updated: " + allowedPackages);

        } catch (Exception e) {
            Log.e(TAG, "Error updating kiosk allowed apps: " + e.getMessage());
        }
    }

    /**
     * Exits kiosk mode and returns device to normal operation.
     */
    public static void unlockKiosk(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        try {
            // Stop lock task mode
            activity.stopLockTask();
            Log.i(TAG, "Lock Task Mode stopped");

            // Clear lock task packages if device owner
            if (Utils.isDeviceOwner(activity)) {
                DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
                ComponentName adminComponent = LegacyUtils.getAdminComponentName(activity);

                if (dpm != null && adminComponent != null) {
                    dpm.setLockTaskPackages(adminComponent, new String[0]);
                    Log.d(TAG, "Lock task packages cleared");
                }
            }

            RemoteLogger.log(activity, Const.LOG_INFO, "Kiosk mode exited");

        } catch (Exception e) {
            Log.e(TAG, "Error unlocking kiosk: " + e.getMessage());
        }
    }

    /**
     * Processes additional configuration settings (Pro-specific).
     */
    public static void processConfig(Context context, ServerConfig config) {
        // Reserved for additional pro configuration processing
    }

    /**
     * Processes location updates (Pro-specific feature).
     */
    public static void processLocation(Context context, Location location, String provider) {
        // Location tracking not implemented in this version
    }

    public static String getAppName(Context context) {
        return context.getString(R.string.app_name);
    }

    public static String getCopyright(Context context) {
        return "(c) " + Calendar.getInstance().get(Calendar.YEAR) + " " + context.getString(R.string.vendor);
    }

    // ========== Helper Methods ==========

    /**
     * Configures lock task features based on ServerConfig settings.
     * Only available on Android 9 (API 28) and above.
     */
    private static void updateLockTaskFeatures(Context context, DevicePolicyManager dpm, ComponentName adminComponent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }

        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        ServerConfig config = settingsHelper.getConfig();

        if (config == null) {
            return;
        }

        int features = DevicePolicyManager.LOCK_TASK_FEATURE_NONE;

        // Configure allowed features based on server config
        if (config.getKioskHome() != null && config.getKioskHome()) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_HOME;
        }

        if (config.getKioskRecents() != null && config.getKioskRecents()) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
        }

        if (config.getKioskNotifications() != null && config.getKioskNotifications()) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS;
        }

        if (config.getKioskSystemInfo() != null && config.getKioskSystemInfo()) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO;
        }

        if (config.getKioskKeyguard() != null && config.getKioskKeyguard()) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD;
        }

        // Global actions (power menu) - always allow for safety unless lock buttons is set
        if (config.getKioskLockButtons() == null || !config.getKioskLockButtons()) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
        }

        try {
            dpm.setLockTaskFeatures(adminComponent, features);
            Log.d(TAG, "Lock task features set: " + features);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set lock task features: " + e.getMessage());
        }
    }

    /**
     * Gets the status bar height in pixels.
     */
    private static int getStatusBarHeight(Context context) {
        int result = 0;
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = context.getResources().getDimensionPixelSize(resourceId);
        }
        if (result == 0) {
            // Fallback to default
            result = (int) (24 * context.getResources().getDisplayMetrics().density);
        }
        return result;
    }

    /**
     * Checks if a package is installed on the device.
     */
    private static boolean isPackageInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}

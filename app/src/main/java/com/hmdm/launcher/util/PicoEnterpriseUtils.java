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

package com.hmdm.launcher.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.hmdm.launcher.Const;
import android.os.RemoteException;

import com.pvr.tobservice.ToBServiceHelper;
import com.pvr.tobservice.enums.PBS_SystemFunctionSwitchEnum;
import com.pvr.tobservice.interfaces.IIntCallback;
import com.pvr.tobservice.interfaces.IToBServiceProxy;

/**
 * Utility class for Pico Enterprise (ToB Service) integration.
 * Provides methods to configure Pico VR device settings programmatically.
 */
public class PicoEnterpriseUtils {

    private static final String TAG = "PicoEnterpriseUtils";

    // Cached binding state
    private static boolean isServiceBound = false;
    private static boolean bindingInProgress = false;

    /**
     * Checks if the current device is a Pico VR device.
     */
    public static boolean isPicoDevice() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        return manufacturer.contains("pico") || brand.contains("pico");
    }

    /**
     * Binds to the Pico ToB Service asynchronously.
     *
     * @param context Application context
     * @param onBound Callback executed when service is bound (may be null)
     */
    public static void bindService(Context context, Runnable onBound) {
        if (isServiceBound) {
            Log.d(TAG, "ToB Service already bound");
            if (onBound != null) {
                onBound.run();
            }
            return;
        }

        if (bindingInProgress) {
            Log.d(TAG, "ToB Service binding already in progress");
            return;
        }

        bindingInProgress = true;
        Log.d(TAG, "Binding to Pico ToB Service...");

        try {
            ToBServiceHelper.getInstance().bindTobService(context, status -> {
                bindingInProgress = false;
                isServiceBound = status;
                Log.d(TAG, "ToB Service bind callback: " + status);

                if (status && onBound != null) {
                    onBound.run();
                }
            });
        } catch (Exception e) {
            bindingInProgress = false;
            Log.e(TAG, "Failed to bind ToB Service: " + e.getMessage());
        }
    }

    /**
     * Unbinds from the Pico ToB Service.
     *
     * @param context Application context
     */
    public static void unbindService(Context context) {
        if (!isServiceBound) {
            return;
        }

        try {
            ToBServiceHelper.getInstance().unBindTobService(context);
            isServiceBound = false;
            Log.d(TAG, "ToB Service unbound");
        } catch (Exception e) {
            Log.e(TAG, "Failed to unbind ToB Service: " + e.getMessage());
        }
    }

    /**
     * Sets the default launcher/home app on Pico device.
     * Must be called after service is bound.
     *
     * @param packageName Package name of the launcher app
     * @return 0 on success, error code otherwise, Integer.MAX_VALUE if not bound
     */
    public static int setLauncher(String packageName) {
        IToBServiceProxy binder = (IToBServiceProxy) ToBServiceHelper.getInstance().getServiceBinder();
        if (binder != null) {
            try {
                int result = binder.setLauncher(packageName);
                Log.i(TAG, "setLauncher(" + packageName + ") result: " + result);
                RemoteLogger.log(null, Const.LOG_INFO, "Pico launcher set to: " + packageName + ", result: " + result);
                return result;
            } catch (Exception e) {
                Log.e(TAG, "setLauncher failed: " + e.getMessage());
                return -1;
            }
        } else {
            Log.w(TAG, "setLauncher: ToB Service not bound");
            return Integer.MAX_VALUE;
        }
    }



    /**
     * Generic method to enforce a Pico system function switch.
     * Checks the current state and only switches if there's a mismatch.
     *
     * @param context Application context
     * @param switchEnum The PBS_SystemFunctionSwitchEnum value
     * @param switchOrdinal The ordinal int for pbsSwitchSystemFunction
     * @param enabled true to enable, false to disable
     * @param label Human-readable label for logging
     */
    private static void enforceSystemSwitch(Context context, PBS_SystemFunctionSwitchEnum switchEnum,
                                            int switchOrdinal, boolean enabled, String label) {
        IToBServiceProxy binder = (IToBServiceProxy) ToBServiceHelper.getInstance().getServiceBinder();
        if (binder != null) {
            try {
                // PBS_SwitchEnum: S_ON = 0, S_OFF = 1 but CurrentState: ON = 1, OFF = 0
                int pbsSwitchValue = enabled ? 0 : 1;
                int desiredState = enabled ? 1 : 0;
                binder.pbsGetSwitchSystemFunctionStatus(switchEnum, new IIntCallback.Stub() {
                    @Override
                    public void callback(int currentState) throws RemoteException {
                        if (currentState != desiredState) {
                            Log.i(TAG, label + " state mismatch: current=" + currentState + ", desired=" + desiredState + ". Switching.");
                            try {
                                binder.pbsSwitchSystemFunction(switchOrdinal, pbsSwitchValue, result -> {
                                    Log.i(TAG, label + " set to " + enabled + ", result: " + result);
                                    RemoteLogger.log(context, Const.LOG_INFO, "Pico " + label + " set to: " + enabled + ", result: " + result);
                                }, 0);
                            } catch (Exception e) {
                                Log.e(TAG, label + " switch failed: " + e.getMessage());
                            }
                        }
                    }
                }, 0);
            } catch (Exception e) {
                Log.e(TAG, label + " status check failed: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "enforce " + label + ": ToB Service not bound");
        }
    }

    /**
     * Binds to the ToB Service and then sets 6DoF state.
     *
     * @param context Application context
     * @param enabled true to enable 6DoF, false to disable
     */
    public static void bindAndSetSixDof(Context context, boolean enabled) {
        bindService(context, () -> enforceSystemSwitch(context,
                PBS_SystemFunctionSwitchEnum.SFS_SIX_DOF_SWITCH, 30, enabled, "6DOF"));

    }

    /**
     * Binds to the ToB Service and enforces eye tracking state.
     */
    public static void bindAndSetEyeTracking(Context context, boolean enabled) {
        bindService(context, () -> enforceSystemSwitch(context,
                PBS_SystemFunctionSwitchEnum.SFS_EYE_TRACK, 79, enabled, "EyeTracking"));
    }

    /**
     * Binds to the ToB Service and enforces hand tracking state.
     */
    public static void bindAndSetHandTracking(Context context, boolean enabled) {
        bindService(context, () -> enforceSystemSwitch(context,
                PBS_SystemFunctionSwitchEnum.SFS_GESTURE_RECOGNITION, 67, enabled, "HandTracking"));
    }

    /**
     * Binds to the ToB Service and enforces dock bar (navigation) state.
     */
    public static void bindAndSetDockBar(Context context, boolean enabled) {
        bindService(context, () -> enforceSystemSwitch(context,
                PBS_SystemFunctionSwitchEnum.SFS_NAVGATION_SWITCH, 16, enabled, "DockBar"));
    }

    /**
     * Convenience method to bind service and set launcher in one call.
     * Handles the async binding automatically.
     *
     * @param context Application context
     * @param packageName Package name to set as launcher
     */
    public static void bindAndSetLauncher(Context context, String packageName) {
        bindService(context, () -> {
            setLauncher(packageName);
        });
    }

    /**
     * Resets the launcher to Pico's default home.
     * Call this when exiting kiosk mode.
     *
     * @param context Application context
     */
    public static void resetLauncherToDefault(Context context) {
        // Pico's default launcher package
        final String picoDefaultLauncher = "com.pvr.home";

        if (isServiceBound) {
            setLauncher(picoDefaultLauncher);
        } else {
            bindService(context, () -> {
                setLauncher(picoDefaultLauncher);
            });
        }
    }

    /**
     * Checks if the ToB Service is currently bound.
     */
    public static boolean isServiceBound() {
        return isServiceBound;
    }

    /**
     * Gets device information from Pico ToB Service.
     *
     * @param infoType Type of info to retrieve (see PBS_SystemInfoEnum)
     * @return Device info string, or empty if not available
     */
    public static String getDeviceInfo(int infoType) {
        IToBServiceProxy binder = (IToBServiceProxy) ToBServiceHelper.getInstance().getServiceBinder();
        if (binder != null) {
            try {
                // Note: This uses the IToBService interface, not IToBServiceProxy
                // May need adjustment based on actual SDK API
                return "";  // Placeholder - implement if needed
            } catch (Exception e) {
                Log.e(TAG, "getDeviceInfo failed: " + e.getMessage());
            }
        }
        return "";
    }
}

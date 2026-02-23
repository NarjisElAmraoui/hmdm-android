package com.hmdm.launcher.p2p;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.RemoteException;
import android.util.Log;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.util.PicoEnterpriseUtils;
import com.pvr.tobservice.ToBServiceHelper;
import com.pvr.tobservice.interfaces.IBoolCallback;
import com.pvr.tobservice.interfaces.IToBServiceProxy;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Handles WiFi connect/disconnect for the P2P command channel.
 *
 * On PICO devices: uses the ToB Service (pbsConfigWifi + pbsControlSetAutoConnectWIFI).
 * On other devices: uses WifiManager.addNetwork() with WifiNetworkSuggestion fallback.
 */
public class WiFiConnector {

    private static final String TAG = "WiFiConnector";

    // Generic path cleanup state
    private static volatile int addedNetworkId = -1;
    private static volatile WifiNetworkSuggestion addedSuggestion = null;

    // Tracks the last SSID connected via connectPico() so disconnectPico() can remove it
    private static volatile String lastConnectedSsid = null;

    /**
     * Initiates a connection to the given WPA2 network.
     * Returns true if the attempt was successfully initiated.
     * Actual connection is async; use waitForConnection() to confirm.
     */
    public static boolean connect(Context context, String ssid, String password) {
        WifiManager wm = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        if (wm != null && !wm.isWifiEnabled()) {
            Log.i(Const.LOG_TAG, TAG + ": WiFi was off, enabling");
            wm.setWifiEnabled(true);
            try { Thread.sleep(1500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (PicoEnterpriseUtils.isPicoDevice()) {
            return connectPico(context, ssid, password);
        } else {
            return connectGeneric(context, ssid, password);
        }
    }

    /**
     * Polls every 2s for up to 30s until the device is connected to targetSsid.
     * Returns true when connected, false on timeout.
     */
    public static boolean waitForConnection(Context context, String targetSsid) {
        WifiManager wm = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return false;

        String expected = "\"" + targetSsid + "\""; // Android wraps SSIDs in quotes
        for (int i = 0; i < 15; i++) {
            String current = wm.getConnectionInfo().getSSID();
            Log.d(Const.LOG_TAG, TAG + ": waitForConnection " + (i + 1) + "/15, current=" + current);
            if (expected.equals(current)) {
                Log.i(Const.LOG_TAG, TAG + ": Connected to " + targetSsid);
                return true;
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.w(Const.LOG_TAG, TAG + ": Timed out waiting for connection to " + targetSsid);
        return false;
    }

    /**
     * Disconnects from WiFi and removes the network added by connect().
     */
    public static void disconnect(Context context) {
        if (PicoEnterpriseUtils.isPicoDevice()) {
            disconnectPico(context);
        } else {
            disconnectGeneric(context);
        }
    }

    // -------------------------------------------------------------------------
    // PICO paths
    // -------------------------------------------------------------------------

    private static boolean connectPico(Context context, String ssid, String password) {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] success = {false};

        PicoEnterpriseUtils.bindService(context, () -> {
            IToBServiceProxy binder = (IToBServiceProxy) ToBServiceHelper.getInstance().getServiceBinder();
            if (binder == null) {
                Log.e(Const.LOG_TAG, TAG + ": PICO binder is null after bind");
                latch.countDown();
                return;
            }
            try {
                // Connect immediately — no SetAutoConnect so the network is not persisted
                // and the PICO will not auto-reconnect after we call disconnect()
                int configResult = binder.pbsConfigWifi(ssid, password, 0);
                Log.i(Const.LOG_TAG, TAG + ": pbsConfigWifi result=" + configResult
                        + " (0=success)");
                if (configResult == 0) {
                    success[0] = true;
                    lastConnectedSsid = ssid;
                }
            } catch (Exception e) {
                Log.e(Const.LOG_TAG, TAG + ": PICO connectWifi error", e);
            }
            latch.countDown();
        });

        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                Log.w(Const.LOG_TAG, TAG + ": Timed out waiting for ToB Service bind");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return success[0];
    }

    private static void disconnectPico(Context context) {
        CountDownLatch latch = new CountDownLatch(1);

        PicoEnterpriseUtils.bindService(context, () -> {
            IToBServiceProxy binder = (IToBServiceProxy) ToBServiceHelper.getInstance().getServiceBinder();
            if (binder != null) {
                try {
                    binder.pbsControlClearAutoConnectWIFI(new IBoolCallback.Stub() {
                        @Override
                        public void callBack(boolean result) throws RemoteException {
                            Log.i(Const.LOG_TAG, TAG
                                    + ": ClearAutoConnectWIFI result=" + result
                                    + " (true=success)");
                        }
                    });
                } catch (Exception e) {
                    Log.e(Const.LOG_TAG, TAG + ": PICO disconnectWifi error", e);
                }
            } else {
                Log.e(Const.LOG_TAG, TAG + ": PICO binder is null on disconnect");
            }
            latch.countDown();
        });

        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                Log.w(Const.LOG_TAG, TAG + ": Timed out waiting for ToB Service bind (disconnect)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Drop the active connection and remove the saved network so the supplicant
        // does not auto-reconnect. HMDM has device owner rights so removeNetwork() works
        // even on API 29+ for networks we added via pbsConfigWifi.
        WifiManager wm = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wm.disconnect();
            Log.i(Const.LOG_TAG, TAG + ": WifiManager.disconnect() called");
            removeSavedNetwork(wm, lastConnectedSsid);
            lastConnectedSsid = null;
        }
    }

    /**
     * Finds the saved WifiConfiguration matching the given SSID and removes it,
     * preventing the Android WiFi supplicant from auto-reconnecting.
     */
    @SuppressWarnings("deprecation")
    private static void removeSavedNetwork(WifiManager wm, String ssid) {
        if (ssid == null) return;
        try {
            java.util.List<android.net.wifi.WifiConfiguration> configs = wm.getConfiguredNetworks();
            if (configs == null) {
                Log.w(Const.LOG_TAG, TAG + ": getConfiguredNetworks() returned null");
                return;
            }
            String quoted = "\"" + ssid + "\"";
            for (android.net.wifi.WifiConfiguration config : configs) {
                if (quoted.equals(config.SSID)) {
                    boolean removed = wm.removeNetwork(config.networkId);
                    wm.saveConfiguration();
                    Log.i(Const.LOG_TAG, TAG + ": removeNetwork id=" + config.networkId
                            + " ssid=" + ssid + " result=" + removed);
                    return;
                }
            }
            Log.w(Const.LOG_TAG, TAG + ": saved network not found for ssid=" + ssid);
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, TAG + ": removeSavedNetwork error", e);
        }
    }

    // -------------------------------------------------------------------------
    // Generic (non-PICO) paths
    // -------------------------------------------------------------------------

    private static boolean connectGeneric(Context context, String ssid, String password) {
        WifiManager wm = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return false;

        if (tryAddNetwork(wm, ssid, password)) {
            Log.i(Const.LOG_TAG, TAG + ": Using addNetwork() path for SSID=" + ssid);
            return true;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            trySuggestion(wm, ssid, password);
            Log.i(Const.LOG_TAG, TAG + ": Using WifiNetworkSuggestion path for SSID=" + ssid);
            return true;
        }

        Log.e(Const.LOG_TAG, TAG + ": All generic connection paths failed for SSID=" + ssid);
        return false;
    }

    private static void disconnectGeneric(Context context) {
        WifiManager wm = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;

        if (addedNetworkId != -1) {
            Log.i(Const.LOG_TAG, TAG + ": Removing network id=" + addedNetworkId);
            wm.disconnect();
            wm.removeNetwork(addedNetworkId);
            wm.saveConfiguration();
            addedNetworkId = -1;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && addedSuggestion != null) {
            Log.i(Const.LOG_TAG, TAG + ": Removing WifiNetworkSuggestion");
            wm.removeNetworkSuggestions(Collections.singletonList(addedSuggestion));
            addedSuggestion = null;
        }
    }

    private static boolean tryAddNetwork(WifiManager wm, String ssid, String password) {
        try {
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = "\"" + ssid + "\"";
            config.preSharedKey = "\"" + password + "\"";
            int netId = wm.addNetwork(config);
            if (netId == -1) {
                Log.w(Const.LOG_TAG, TAG + ": addNetwork() returned -1 (blocked on this ROM)");
                return false;
            }
            addedNetworkId = netId;
            wm.disconnect();
            wm.enableNetwork(netId, true);
            wm.reconnect();
            Log.i(Const.LOG_TAG, TAG + ": addNetwork() succeeded, netId=" + netId);
            return true;
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, TAG + ": addNetwork() threw: " + e.getMessage());
            return false;
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private static void trySuggestion(WifiManager wm, String ssid, String password) {
        try {
            WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build();
            addedSuggestion = suggestion;
            int result = wm.addNetworkSuggestions(Collections.singletonList(suggestion));
            Log.i(Const.LOG_TAG, TAG + ": addNetworkSuggestions result=" + result
                    + " (0=success)");
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, TAG + ": addNetworkSuggestions threw: " + e.getMessage());
        }
    }
}

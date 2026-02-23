package com.hmdm.launcher.p2p;

import android.content.Context;
import android.util.Log;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.ConfigUpdater;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.RemoteFile;

import org.json.JSONException;
import org.json.JSONObject;

public class P2PCommandDispatcher {

    public static String handle(Context context, JSONObject request) {
        String type = request.optString(P2PCommand.FIELD_TYPE, "");
        String id   = request.optString(P2PCommand.FIELD_ID, null);

        Log.d(Const.LOG_TAG, "P2PCommandDispatcher: handling command type=" + type);

        switch (type) {
            case P2PCommand.TYPE_PING:
                return buildOk(id);

            case P2PCommand.TYPE_GET_STATUS:
                return buildStatusResponse(id);

            case P2PCommand.TYPE_WIFI_SYNC: {
                JSONObject payload = request.optJSONObject(P2PCommand.FIELD_PAYLOAD);
                if (payload == null) return buildError("MISSING_PAYLOAD", id);
                String ssid     = payload.optString("ssid", "");
                String password = payload.optString("password", "");
                if (ssid.isEmpty()) return buildError("MISSING_SSID", id);

                OperationStatus.set(OperationStatus.STATE_WIFI_CONNECTING, null, "Connecting to " + ssid);

                new Thread(() -> {
                    boolean initiated = WiFiConnector.connect(context, ssid, password);
                    if (!initiated) {
                        OperationStatus.set(OperationStatus.STATE_WIFI_BIND_TIMEOUT, null,
                                "ToB Service bind timed out or failed");
                        return;
                    }
                    if (!WiFiConnector.waitForConnection(context, ssid)) {
                        OperationStatus.set(OperationStatus.STATE_WIFI_TIMEOUT, null,
                                "Timed out waiting for connection to " + ssid);
                        return;
                    }
                    OperationStatus.set(OperationStatus.STATE_WIFI_CONNECTED, null,
                            "Connected to " + ssid);
                    OperationStatus.set(OperationStatus.STATE_SYNC_PENDING, null, null);
                    // WifiSyncNotifier handles both sync outcome and the disconnect step
                    ConfigUpdater.forceConfigUpdate(context, new WifiSyncNotifier(context), false);
                }, "P2PWifiSync").start();

                return buildOk(id);
            }

            case P2PCommand.TYPE_DISCONNECT_WIFI:
                new Thread(() -> WiFiConnector.disconnect(context), "P2PWifiDisconnect").start();
                return buildOk(id);

            case P2PCommand.TYPE_SYNC_CONFIG:
                OperationStatus.set(OperationStatus.STATE_SYNC_PENDING, null, null);
                new Thread(() ->
                    ConfigUpdater.forceConfigUpdate(context, new SyncStatusNotifier(), false),
                    "P2PSyncConfig").start();
                return buildOk(id);

            default:
                Log.w(Const.LOG_TAG, "P2PCommandDispatcher: unknown command type=" + type);
                return buildError(P2PCommand.ERROR_UNKNOWN_COMMAND, id);
        }
    }

    private static String buildStatusResponse(String id) {
        try {
            JSONObject resp = new JSONObject();
            resp.put(P2PCommand.FIELD_STATUS, P2PCommand.STATUS_OK);
            if (id != null) resp.put(P2PCommand.FIELD_ID, id);
            JSONObject s = OperationStatus.toJson();
            resp.put("state", s.getString("state"));
            if (s.has("detail"))  resp.put("detail",  s.getString("detail"));
            if (s.has("message")) resp.put("message", s.getString("message"));
            resp.put("timestamp", s.getLong("timestamp"));
            return resp.toString();
        } catch (JSONException e) {
            return "{\"status\":\"OK\",\"state\":\"idle\"}";
        }
    }

    /**
     * Used by wifiSync: updates OperationStatus for sync outcome, then always
     * disconnects WiFi (whether sync succeeded or failed) to keep P2P stable.
     * On success: sync_ok → wifi_disconnecting → complete
     * On error:   sync_error (preserved) → wifi_disconnecting → (stays sync_error)
     */
    private static class WifiSyncNotifier implements ConfigUpdater.UINotifier {
        private final Context context;

        WifiSyncNotifier(Context context) { this.context = context; }

        @Override public void onConfigUpdateStart() {}
        @Override public void onConfigLoaded() {}
        @Override public void onPoliciesUpdated() {}
        @Override public void onFileDownloading(RemoteFile f) {}
        @Override public void onDownloadProgress(int p, long t, long c) {}
        @Override public void onFileDownloadError(RemoteFile f) {}
        @Override public void onFileInstallError(RemoteFile f) {}
        @Override public void onAppUpdateStart() {}
        @Override public void onAppRemoving(Application a) {}
        @Override public void onAppDownloading(Application a) {}
        @Override public void onAppInstalling(Application a) {}
        @Override public void onAppDownloadError(Application a) {}
        @Override public void onAppInstallError(String pkg) {}
        @Override public void onAppInstallComplete(String pkg) {}
        @Override public void onAllAppInstallComplete() {}

        @Override
        public void onConfigUpdateServerError(String errorText) {
            OperationStatus.set(OperationStatus.STATE_SYNC_ERROR,
                    OperationStatus.DETAIL_SERVER_ERROR, errorText);
            disconnectAsync(false);
        }

        @Override
        public void onConfigUpdateNetworkError(String errorText) {
            OperationStatus.set(OperationStatus.STATE_SYNC_ERROR,
                    OperationStatus.DETAIL_NETWORK_ERROR, errorText);
            disconnectAsync(false);
        }

        @Override
        public void onConfigUpdateComplete() {
            OperationStatus.set(OperationStatus.STATE_SYNC_OK, null, null);
            disconnectAsync(true);
        }

        private void disconnectAsync(boolean setComplete) {
            OperationStatus.set(OperationStatus.STATE_WIFI_DISCONNECTING, null, null);
            new Thread(() -> {
                WiFiConnector.disconnect(context);
                if (setComplete) {
                    OperationStatus.set(OperationStatus.STATE_COMPLETE, null, null);
                }
                // On error: keep the error state as the terminal state, not "complete"
            }, "P2PWifiDisconnect").start();
        }
    }

    /**
     * Used by syncConfig: updates OperationStatus for sync outcome only.
     * No WiFi disconnect — syncConfig is used when already connected.
     */
    private static class SyncStatusNotifier implements ConfigUpdater.UINotifier {
        @Override public void onConfigUpdateStart() {}
        @Override public void onConfigLoaded() {}
        @Override public void onPoliciesUpdated() {}
        @Override public void onFileDownloading(RemoteFile f) {}
        @Override public void onDownloadProgress(int p, long t, long c) {}
        @Override public void onFileDownloadError(RemoteFile f) {}
        @Override public void onFileInstallError(RemoteFile f) {}
        @Override public void onAppUpdateStart() {}
        @Override public void onAppRemoving(Application a) {}
        @Override public void onAppDownloading(Application a) {}
        @Override public void onAppInstalling(Application a) {}
        @Override public void onAppDownloadError(Application a) {}
        @Override public void onAppInstallError(String pkg) {}
        @Override public void onAppInstallComplete(String pkg) {}
        @Override public void onAllAppInstallComplete() {}

        @Override
        public void onConfigUpdateServerError(String errorText) {
            OperationStatus.set(OperationStatus.STATE_SYNC_ERROR,
                    OperationStatus.DETAIL_SERVER_ERROR, errorText);
        }

        @Override
        public void onConfigUpdateNetworkError(String errorText) {
            OperationStatus.set(OperationStatus.STATE_SYNC_ERROR,
                    OperationStatus.DETAIL_NETWORK_ERROR, errorText);
        }

        @Override
        public void onConfigUpdateComplete() {
            OperationStatus.set(OperationStatus.STATE_SYNC_OK, null, null);
        }
    }

    static String buildOk(String id) {
        try {
            JSONObject resp = new JSONObject();
            resp.put(P2PCommand.FIELD_STATUS, P2PCommand.STATUS_OK);
            if (id != null) resp.put(P2PCommand.FIELD_ID, id);
            return resp.toString();
        } catch (JSONException e) {
            return "{\"status\":\"OK\"}";
        }
    }

    static String buildError(String errorCode, String id) {
        try {
            JSONObject resp = new JSONObject();
            resp.put(P2PCommand.FIELD_STATUS, P2PCommand.STATUS_ERROR);
            resp.put(P2PCommand.FIELD_ERROR, errorCode);
            if (id != null) resp.put(P2PCommand.FIELD_ID, id);
            return resp.toString();
        } catch (JSONException e) {
            return "{\"status\":\"ERROR\",\"error\":\"" + errorCode + "\"}";
        }
    }
}

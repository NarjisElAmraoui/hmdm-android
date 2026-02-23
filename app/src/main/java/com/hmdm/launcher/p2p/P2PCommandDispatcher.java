package com.hmdm.launcher.p2p;

import android.content.Context;
import android.util.Log;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.ConfigUpdater;

import org.json.JSONException;
import org.json.JSONObject;

public class P2PCommandDispatcher {

    public static String handle(Context context, JSONObject request) {
        String type = request.optString(P2PCommand.FIELD_TYPE, "");
        String id = request.optString(P2PCommand.FIELD_ID, null);

        Log.d(Const.LOG_TAG, "P2PCommandDispatcher: handling command type=" + type);

        switch (type) {
            case P2PCommand.TYPE_PING:
                return buildOk(id);

            case P2PCommand.TYPE_CONNECT_WIFI: {
                JSONObject payload = request.optJSONObject(P2PCommand.FIELD_PAYLOAD);
                if (payload == null) return buildError("MISSING_PAYLOAD", id);
                String ssid = payload.optString("ssid", "");
                String password = payload.optString("password", "");
                if (ssid.isEmpty()) return buildError("MISSING_SSID", id);
                // Respond OK immediately; execute async so P2P drop doesn't block response
                new Thread(() -> {
                    boolean initiated = WiFiConnector.connect(context, ssid, password);
                    if (initiated && WiFiConnector.waitForConnection(context, ssid)) {
                        Log.i(Const.LOG_TAG, "P2PCommandDispatcher: connected to " + ssid + ", triggering MDM sync");
                        ConfigUpdater.forceConfigUpdate(context);
                    } else {
                        Log.w(Const.LOG_TAG, "P2PCommandDispatcher: failed to connect to " + ssid);
                    }
                }, "P2PWifiConnect").start();
                return buildOk(id);
            }

            case P2PCommand.TYPE_DISCONNECT_WIFI:
                new Thread(() -> WiFiConnector.disconnect(context), "P2PWifiDisconnect").start();
                return buildOk(id);

            case P2PCommand.TYPE_SYNC_CONFIG:
                new Thread(() -> ConfigUpdater.forceConfigUpdate(context), "P2PSyncConfig").start();
                return buildOk(id);

            default:
                Log.w(Const.LOG_TAG, "P2PCommandDispatcher: unknown command type=" + type);
                return buildError(P2PCommand.ERROR_UNKNOWN_COMMAND, id);
        }
    }

    static String buildOk(String id) {
        try {
            JSONObject resp = new JSONObject();
            resp.put(P2PCommand.FIELD_STATUS, P2PCommand.STATUS_OK);
            if (id != null) {
                resp.put(P2PCommand.FIELD_ID, id);
            }
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
            if (id != null) {
                resp.put(P2PCommand.FIELD_ID, id);
            }
            return resp.toString();
        } catch (JSONException e) {
            return "{\"status\":\"ERROR\",\"error\":\"" + errorCode + "\"}";
        }
    }
}

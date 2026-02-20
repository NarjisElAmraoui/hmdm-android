package com.hmdm.launcher.p2p;

import android.content.Context;
import android.util.Log;

import com.hmdm.launcher.Const;

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

            // Future handlers will be added here:
            // case P2PCommand.TYPE_CONNECT_WIFI:    return handleConnectWifi(context, request, id);
            // case P2PCommand.TYPE_DISCONNECT_WIFI: return handleDisconnectWifi(context, id);
            // case P2PCommand.TYPE_SYNC_CONFIG:     return handleSyncConfig(context, id);

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

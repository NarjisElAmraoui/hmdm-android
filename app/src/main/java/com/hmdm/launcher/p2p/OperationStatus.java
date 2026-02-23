package com.hmdm.launcher.p2p;

import android.util.Log;

import com.hmdm.launcher.Const;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Singleton holding the current state of the last P2P-initiated operation.
 * Updated by P2PCommandDispatcher as the async work progresses.
 * Queried by the getStatus command so the tablet can poll for results.
 */
public class OperationStatus {

    // State constants — ordered by progression
    public static final String STATE_IDLE               = "idle";
    public static final String STATE_WIFI_CONNECTING    = "wifi_connecting";
    public static final String STATE_WIFI_CONNECTED     = "wifi_connected";
    public static final String STATE_SYNC_PENDING       = "sync_pending";
    public static final String STATE_SYNC_OK            = "sync_ok";
    public static final String STATE_WIFI_DISCONNECTING = "wifi_disconnecting";
    // Terminal success
    public static final String STATE_COMPLETE           = "complete";
    // Terminal errors
    public static final String STATE_WIFI_BIND_TIMEOUT  = "wifi_bind_timeout";  // ToB Service didn't respond
    public static final String STATE_WIFI_TIMEOUT       = "wifi_timeout";        // SSID not joined in 30s
    public static final String STATE_SYNC_ERROR         = "sync_error";

    // Detail codes used with STATE_SYNC_ERROR
    public static final String DETAIL_NETWORK_ERROR = "NETWORK_ERROR";
    public static final String DETAIL_SERVER_ERROR  = "SERVER_ERROR";

    private static volatile String state     = STATE_IDLE;
    private static volatile String detail    = null;
    private static volatile String message   = null;
    private static volatile long   timestamp = 0;

    public static synchronized void set(String newState, String newDetail, String newMessage) {
        state     = newState;
        detail    = newDetail;
        message   = newMessage;
        timestamp = System.currentTimeMillis();
        Log.i(Const.LOG_TAG, "OperationStatus: " + newState
                + (newDetail  != null ? " detail="  + newDetail  : "")
                + (newMessage != null ? " message=" + newMessage : ""));
    }

    public static synchronized JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("state", state);
        if (detail  != null) obj.put("detail",  detail);
        if (message != null) obj.put("message", message);
        obj.put("timestamp", timestamp);
        return obj;
    }
}

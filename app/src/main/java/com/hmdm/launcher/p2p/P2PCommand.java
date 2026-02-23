package com.hmdm.launcher.p2p;

public class P2PCommand {
    public static final String TYPE_PING            = "ping";
    public static final String TYPE_WIFI_SYNC       = "wifiSync";       // connect WiFi → sync MDM config → disconnect WiFi
    public static final String TYPE_DISCONNECT_WIFI = "disconnectWifi";
    public static final String TYPE_SYNC_CONFIG     = "syncConfig";
    public static final String TYPE_GET_STATUS      = "getStatus";

    // Protocol field names
    public static final String FIELD_SECRET  = "secret";
    public static final String FIELD_TYPE    = "type";
    public static final String FIELD_ID      = "id";
    public static final String FIELD_PAYLOAD = "payload";
    public static final String FIELD_STATUS  = "status";
    public static final String FIELD_ERROR   = "error";

    // Response status values
    public static final String STATUS_OK    = "OK";
    public static final String STATUS_ERROR = "ERROR";

    // Error codes
    public static final String ERROR_AUTH_FAILED              = "AUTH_FAILED";
    public static final String ERROR_UNKNOWN_COMMAND          = "UNKNOWN_COMMAND";
    public static final String ERROR_P2P_NOT_CONNECTED        = "P2P_NOT_CONNECTED";
    public static final String ERROR_P2P_IS_GROUP_OWNER       = "P2P_IS_GROUP_OWNER_CANNOT_SEND";
    public static final String ERROR_IO_TIMEOUT               = "IO_TIMEOUT";
    public static final String ERROR_IO_ERROR                 = "IO_ERROR";
    public static final String ERROR_P2P_INFO_TIMEOUT         = "P2P_INFO_TIMEOUT";
}

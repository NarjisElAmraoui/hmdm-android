package com.hmdm.launcher.p2p;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Looper;
import android.util.Log;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sends a JSON command to the peer HMDM instance over the existing WiFi Direct P2P connection.
 *
 * Topology assumption: PICO is always the Group Owner (GO, IP 192.168.49.1).
 * The tablet is always the client. This class must be called from a background thread.
 */
public class P2PCommandClient {

    private static final String TAG = "P2PCommandClient";

    /**
     * Sends commandJson to the peer HMDM and returns the response JSON string.
     * This method blocks — call it from a background thread only.
     */
    public static String sendCommand(Context context, String commandJson) {
        try {
            InetAddress peerAddress = resolvePeerAddress(context);
            if (peerAddress == null) {
                // resolvePeerAddress already logged the reason; build an error from the JSON
                return P2PCommandDispatcher.buildError(P2PCommand.ERROR_P2P_NOT_CONNECTED, extractId(commandJson));
            }
            return doSocketCall(peerAddress, commandJson, extractId(commandJson));
        } catch (GroupOwnerException e) {
            Log.w(Const.LOG_TAG, TAG + ": This device is the P2P Group Owner — cannot determine client IP");
            return P2PCommandDispatcher.buildError(P2PCommand.ERROR_P2P_IS_GROUP_OWNER, extractId(commandJson));
        } catch (P2PInfoTimeoutException e) {
            Log.w(Const.LOG_TAG, TAG + ": Timed out waiting for P2P connection info");
            return P2PCommandDispatcher.buildError(P2PCommand.ERROR_P2P_INFO_TIMEOUT, extractId(commandJson));
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, TAG + ": Unexpected error", e);
            return P2PCommandDispatcher.buildError(P2PCommand.ERROR_IO_ERROR, extractId(commandJson));
        }
    }

    /**
     * Queries WifiP2pManager for the current connection info to get the GO's IP address.
     * Returns null if no group is formed (i.e. P2P is not connected).
     * Throws GroupOwnerException if this device is the GO (can't determine client IP).
     * Throws P2PInfoTimeoutException if the callback doesn't fire in time.
     */
    private static InetAddress resolvePeerAddress(Context context)
            throws GroupOwnerException, P2PInfoTimeoutException {

        WifiP2pManager manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            Log.w(Const.LOG_TAG, TAG + ": WifiP2pManager not available");
            return null;
        }

        WifiP2pManager.Channel channel = manager.initialize(context, Looper.getMainLooper(), null);

        final CountDownLatch latch = new CountDownLatch(1);
        final WifiP2pInfo[] result = new WifiP2pInfo[1];

        manager.requestConnectionInfo(channel, info -> {
            result[0] = info;
            latch.countDown();
        });

        try {
            if (!latch.await(Const.P2P_TCP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new P2PInfoTimeoutException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        WifiP2pInfo info = result[0];
        if (info == null || !info.groupFormed) {
            Log.w(Const.LOG_TAG, TAG + ": P2P group not formed");
            return null;
        }

        if (info.isGroupOwner) {
            // This device is the GO. We don't know the client's dynamic IP from the API.
            // Expected configuration: PICO is always GO, tablet is always client.
            // If this triggers, the device roles are reversed — investigate P2P group setup.
            throw new GroupOwnerException();
        }

        // We are the client. The GO's IP is always 192.168.49.1 on Android WiFi Direct.
        Log.d(Const.LOG_TAG, TAG + ": P2P GO address = " + info.groupOwnerAddress.getHostAddress());
        return info.groupOwnerAddress;
    }

    private static String doSocketCall(InetAddress peerAddress, String commandJson, String id) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(peerAddress, Const.P2P_TCP_PORT),
                    Const.P2P_TCP_TIMEOUT_MS);
            socket.setSoTimeout(Const.P2P_TCP_TIMEOUT_MS);

            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            writer.println(commandJson);
            String response = reader.readLine();

            if (response == null) {
                Log.w(Const.LOG_TAG, TAG + ": Empty response from peer");
                return P2PCommandDispatcher.buildError(P2PCommand.ERROR_IO_ERROR, id);
            }

            Log.d(Const.LOG_TAG, TAG + ": Response from peer: " + response);
            return response;

        } catch (java.net.SocketTimeoutException e) {
            Log.w(Const.LOG_TAG, TAG + ": Connection or read timed out");
            return P2PCommandDispatcher.buildError(P2PCommand.ERROR_IO_TIMEOUT, id);
        } catch (IOException e) {
            Log.w(Const.LOG_TAG, TAG + ": IO error during socket call", e);
            return P2PCommandDispatcher.buildError(P2PCommand.ERROR_IO_ERROR, id);
        }
    }

    private static String extractId(String commandJson) {
        try {
            return new JSONObject(commandJson).optString(P2PCommand.FIELD_ID, null);
        } catch (JSONException e) {
            return null;
        }
    }

    private static class GroupOwnerException extends Exception {}
    private static class P2PInfoTimeoutException extends Exception {}
}

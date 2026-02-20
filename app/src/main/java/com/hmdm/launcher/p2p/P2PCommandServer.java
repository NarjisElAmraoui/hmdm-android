package com.hmdm.launcher.p2p;

import android.content.Context;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class P2PCommandServer {

    private static final String TAG = "P2PCommandServer";

    private final Context context;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running = false;

    // Bounded thread pool for handling concurrent connections (max 2)
    private final ExecutorService connectionPool = Executors.newFixedThreadPool(2);

    public P2PCommandServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            serverSocket = new ServerSocket(Const.P2P_TCP_PORT);
            running = true;
            serverThread = new Thread(this::acceptLoop, "P2PAcceptLoop");
            serverThread.setDaemon(true);
            serverThread.start();
            Log.i(Const.LOG_TAG, TAG + ": TCP server started on port " + Const.P2P_TCP_PORT);
        } catch (IOException e) {
            Log.e(Const.LOG_TAG, TAG + ": Failed to start TCP server", e);
            running = false;
        }
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close(); // unblocks accept()
            }
        } catch (IOException e) {
            Log.w(Const.LOG_TAG, TAG + ": Error closing server socket", e);
        }
        Log.i(Const.LOG_TAG, TAG + ": TCP server stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                Log.d(Const.LOG_TAG, TAG + ": Accepted connection from " + clientSocket.getInetAddress());
                connectionPool.submit(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (running) {
                    Log.w(Const.LOG_TAG, TAG + ": Accept error", e);
                }
                // If !running, the socket was closed intentionally — exit loop silently
            }
        }
    }

    private void handleConnection(Socket clientSocket) {
        try {
            clientSocket.setSoTimeout(Const.P2P_TCP_TIMEOUT_MS);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true);

            String line = reader.readLine();
            if (line == null || line.isEmpty()) {
                writer.println(P2PCommandDispatcher.buildError(P2PCommand.ERROR_IO_ERROR, null));
                return;
            }

            JSONObject request;
            try {
                request = new JSONObject(line);
            } catch (JSONException e) {
                Log.w(Const.LOG_TAG, TAG + ": Malformed JSON request");
                writer.println(P2PCommandDispatcher.buildError(P2PCommand.ERROR_IO_ERROR, null));
                return;
            }

            // Validate shared secret
            String secret = request.optString(P2PCommand.FIELD_SECRET, "");
            if (!BuildConfig.REQUEST_SIGNATURE.equals(secret)) {
                Log.w(Const.LOG_TAG, TAG + ": Auth failed — wrong secret");
                writer.println(P2PCommandDispatcher.buildError(P2PCommand.ERROR_AUTH_FAILED,
                        request.optString(P2PCommand.FIELD_ID, null)));
                return;
            }

            String response = P2PCommandDispatcher.handle(context, request);
            writer.println(response);
            Log.d(Const.LOG_TAG, TAG + ": Response sent: " + response);

        } catch (IOException e) {
            Log.w(Const.LOG_TAG, TAG + ": IO error handling connection", e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }
}

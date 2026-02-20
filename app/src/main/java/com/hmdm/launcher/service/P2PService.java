package com.hmdm.launcher.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.p2p.P2PCommandServer;

/**
 * Background service that owns the P2P TCP command server lifecycle.
 * Started at boot via Initializer; uses START_STICKY to survive OS restarts.
 */
public class P2PService extends Service {

    private P2PCommandServer commandServer;

    @Override
    public void onCreate() {
        super.onCreate();
        commandServer = new P2PCommandServer(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        commandServer.start();
        Log.i(Const.LOG_TAG, "P2PService started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        commandServer.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

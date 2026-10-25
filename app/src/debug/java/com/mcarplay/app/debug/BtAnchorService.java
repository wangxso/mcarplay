package com.mcarplay.app.debug;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;
import com.mcarplay.app.R;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/** Holds a genuine RFCOMM connection during tablet head-unit diagnostics. Never used in release. */
public final class BtAnchorService extends Service {
    public static final String ACTION_SERVER = "com.mcarplay.app.debug.BT_ANCHOR_SERVER";
    public static final String ACTION_CLIENT = "com.mcarplay.app.debug.BT_ANCHOR_CLIENT";
    public static final String EXTRA_PEER_NAME = "peerName";

    private static final String TAG = "MCarPlayBtAnchor";
    private static final String CHANNEL_ID = "mcarplay_bt_anchor";
    private static final UUID UUID_ANCHOR =
            UUID.fromString("f90394a3-8edc-42d5-90be-3eb136fc3c6d");
    private static final int NOTIFICATION_ID = 19740;

    private final Object lock = new Object();
    private volatile boolean running;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket socket;
    private Thread worker;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (!ACTION_SERVER.equals(action) && !ACTION_CLIENT.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT not granted");
            stopSelf();
            return START_NOT_STICKY;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "Bluetooth adapter unavailable");
            stopSelf();
            return START_NOT_STICKY;
        }
        String peerName = intent.getStringExtra(EXTRA_PEER_NAME);
        if (peerName == null || peerName.isEmpty()) peerName = "Test-HU";

        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "mcarplay 蓝牙诊断", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, notification("蓝牙诊断链路启动中"));

        if (running) {
            Log.i(TAG, "RFCOMM anchor already running");
            return START_NOT_STICKY;
        }
        running = true;
        String selectedPeer = peerName;
        worker = new Thread(() -> runLink(adapter, ACTION_SERVER.equals(action), selectedPeer), "bt-anchor");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        closeLink();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("MissingPermission") // Permission is checked in onStartCommand before the worker starts.
    private void runLink(BluetoothAdapter adapter, boolean serverMode, String peerName) {
        while (running) {
            try {
                if (serverMode) {
                    update("等待小米手机蓝牙连接");
                    BluetoothServerSocket listener =
                            adapter.listenUsingRfcommWithServiceRecord("mcarplay-test-anchor", UUID_ANCHOR);
                    synchronized (lock) {
                        serverSocket = listener;
                    }
                    BluetoothSocket accepted = listener.accept();
                    synchronized (lock) {
                        socket = accepted;
                    }
                    listener.close();
                    synchronized (lock) {
                        serverSocket = null;
                    }
                } else {
                    Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                    BluetoothDevice peer = null;
                    for (BluetoothDevice device : bonded) {
                        if (peerName.equals(device.getName())) {
                            peer = device;
                            break;
                        }
                    }
                    if (peer == null) throw new IOException("Paired device not found: " + peerName);
                    update("连接 " + peerName);
                    BluetoothSocket connection = peer.createRfcommSocketToServiceRecord(UUID_ANCHOR);
                    synchronized (lock) {
                        socket = connection;
                    }
                    connection.connect();
                }
                Log.i(TAG, "RFCOMM anchor connected; server=" + serverMode);
                update("蓝牙诊断链路已连接");
                BluetoothSocket active;
                synchronized (lock) {
                    active = socket;
                }
                while (running && active.getInputStream().read() != -1) {
                    // Only hold the link; no CarLife, CarPlay, or private data travels here.
                }
            } catch (IOException | SecurityException error) {
                if (running) Log.w(TAG, "RFCOMM anchor unavailable: " + error.getMessage());
            } finally {
                closeSockets();
            }
            if (running) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("mcarplay 蓝牙诊断")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
    }

    private void update(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    private void closeLink() {
        running = false;
        closeSockets();
        Thread current = worker;
        if (current != null) current.interrupt();
        worker = null;
    }

    private void closeSockets() {
        synchronized (lock) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Already closed.
                }
                socket = null;
            }
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                    // Already closed.
                }
                serverSocket = null;
            }
        }
    }
}

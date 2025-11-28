package com.example.quizapp.p2p;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;

import com.example.quizapp.QuizDatabaseHelper;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * BluetoothManager — менеджер P2P по Bluetooth.
 *
 * Особенности:
 * - все listener callbacks постятся в main Looper
 * - при connected() отправляется PLAYER_NAME:<nick>
 * - обрабатывает строковые запросы REQUEST_PLAYER_NAME и PLAYER_NAME:...
 * - передаёт Serializable объекты (GameDataModel и т.п.) напрямую слушателю
 */
public class BluetoothManager implements P2PManager {

    private static final String TAG = "BluetoothManager";
    private static final String SERVICE_NAME = "QuizAppP2P";
    private static final UUID APP_UUID = UUID.fromString("f4204d80-5a39-4467-8495-92718105d15c");

    private final BluetoothAdapter bluetoothAdapter;
    private ConnectionListener listener;
    private Context context;

    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;

    private final List<DiscoveredDevice> discoveredDevices = new ArrayList<>();

    public BluetoothManager() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public void initialize(Context context, ConnectionListener listener) {
        this.context = context;
        this.listener = listener;
        Log.d(TAG, "BluetoothManager initialized.");
    }

    @Override
    public ConnectionType getConnectionType() {
        return ConnectionType.BLUETOOTH;
    }

    @Override
    public boolean isEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @Override
    public void cleanup() {
        stop();
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    @Override
    public void connectTo(String deviceAddress) {
        // помечаем себя клиентом
        P2PConnectionSingleton.getInstance().setGroupOwner(false);
        connect(deviceAddress);
    }

    @Override
    public void stopDiscovery() {
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "BLUETOOTH_SCAN permission not granted for stopDiscovery.");
                    return;
                }
            }
            bluetoothAdapter.cancelDiscovery();
            Log.d(TAG, "Bluetooth discovery stopped.");
        }
    }

    @Override
    public void startDiscovery() {
        if (!isEnabled()) {
            postConnectionFailed("Bluetooth не включен.");
            return;
        }

        discoveredDevices.clear();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for getBondedDevices.");
            }
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices != null && !pairedDevices.isEmpty()) {
            for (BluetoothDevice d : pairedDevices) {
                String name = d.getName() != null ? d.getName() : d.getAddress();
                discoveredDevices.add(new DiscoveredDevice(name, d.getAddress()));
                postDeviceFound(name, d.getAddress());
            }
        }

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_SCAN permission not granted for startDiscovery.");
                return;
            }
        } else if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "ACCESS_FINE_LOCATION permission not granted for startDiscovery (Legacy).");
            return;
        }

        bluetoothAdapter.startDiscovery();
        Log.d(TAG, "Bluetooth discovery started.");
    }

    @Override
    public void connect(String deviceAddress) {
        // Make this public to match P2PManager interface visibility
        if (bluetoothAdapter.isDiscovering()) {
            stopDiscovery();
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        connectThread = new ConnectThread(device);
        connectThread.start();
        Log.d(TAG, "Attempting to connect to " + deviceAddress);
    }

    @Override
    public void sendMessage(Serializable data) {
        if (connectedThread != null) {
            connectedThread.write(data);
        } else {
            Log.e(TAG, "Cannot send message: not connected.");
            postConnectionFailed("Cannot send message: not connected.");
        }
    }

    @Override
    public void stop() {
        Log.d(TAG, "Stopping all Bluetooth threads.");
        stopListening();

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
    }

    @Override
    public List<DiscoveredDevice> getDiscoveredDevices() {
        return discoveredDevices;
    }

    // Host controls
    public synchronized boolean startListening() {
        if (context == null) {
            Log.e(TAG, "startListening: context is null");
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "startListening: missing BLUETOOTH_CONNECT or BLUETOOTH_ADVERTISE");
                return false;
            }
        } else {
            if (!isEnabled()) {
                Log.e(TAG, "startListening: bluetooth not enabled");
                return false;
            }
        }

        P2PConnectionSingleton.getInstance().setGroupOwner(true);
        startAcceptThread();
        return true;
    }

    public synchronized void stopListening() {
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
            Log.d(TAG, "stopListening(): acceptThread stopped.");
        }
    }

    public synchronized boolean isListening() {
        return acceptThread != null;
    }

    // Helpers to post callbacks on UI thread
    private void postConnectionFailed(final String message) {
        if (listener == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try { listener.onConnectionFailed(message); } catch (Exception e) { Log.e(TAG, "Error delivering onConnectionFailed", e); }
        });
    }

    private void postDisconnected(final String reason) {
        if (listener == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try { listener.onDisconnected(reason); } catch (Exception e) { Log.e(TAG, "Error delivering onDisconnected", e); }
        });
    }

    private void postConnected(final String deviceName, final ConnectionType type) {
        if (listener == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try { listener.onConnected(deviceName, type); } catch (Exception e) { Log.e(TAG, "Error delivering onConnected", e); }
        });
    }

    private void postDeviceFound(final String name, final String address) {
        if (listener == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try { listener.onDeviceFound(name, address); } catch (Exception e) { Log.e(TAG, "Error delivering onDeviceFound", e); }
        });
    }

    // Connection lifecycle
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        Log.d(TAG, "Connected to " + device.getName());

        // This side accepted -> treat as group owner
        P2PConnectionSingleton.getInstance().setGroupOwner(true);

        if (connectThread != null) { connectThread.cancel(); connectThread = null; }
        if (connectedThread != null) { connectedThread.cancel(); connectedThread = null; }
        if (acceptThread != null) { acceptThread.cancel(); acceptThread = null; }

        connectedThread = new ConnectedThread(socket, listener);
        connectedThread.start();

        postConnected(device.getName() != null ? device.getName() : device.getAddress(), ConnectionType.BLUETOOTH);

        // try send local player name right away (if peer listening)
        sendLocalPlayerNameIfPossible();
    }

    private void sendLocalPlayerNameIfPossible() {
        String localPlayerName = readLocalPlayerName();
        if (localPlayerName == null) localPlayerName = "noname";
        if (connectedThread != null) {
            try {
                connectedThread.write("PLAYER_NAME:" + localPlayerName);
                Log.d(TAG, "Sent PLAYER_NAME:" + localPlayerName);
            } catch (Exception e) {
                Log.w(TAG, "Failed to send PLAYER_NAME at connected()", e);
            }
        }
    }

    private String readLocalPlayerName() {
        try {
            if (context == null) return "noname";
            QuizDatabaseHelper dbHelper = QuizDatabaseHelper.getInstance(context);
            return dbHelper.getPlayerName();
        } catch (Exception e) {
            Log.w(TAG, "readLocalPlayerName failed", e);
            return "noname";
        }
    }

    public synchronized void connectionFailed(String message) {
        P2PConnectionSingleton.getInstance().setGroupOwner(false);
        postConnectionFailed(message);
    }

    public synchronized void connectionLost(String reason) {
        postDisconnected(reason);
        startAcceptThread();
    }

    public synchronized void startAcceptThread() {
        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            acceptThread.start();
            Log.d(TAG, "AcceptThread started by startAcceptThread()");
        } else {
            Log.d(TAG, "startAcceptThread(): acceptThread already running");
        }
    }

    // AcceptThread
    private class AcceptThread extends Thread {
        private BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "BLUETOOTH_CONNECT permission missing for AcceptThread.");
                        mmServerSocket = null;
                        return;
                    }
                }
                tmp = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID);
            } catch (IOException e) {
                Log.e(TAG, "AcceptThread: Server socket creation failed", e);
            }
            mmServerSocket = tmp;
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void run() {
            Log.d(TAG, "AcceptThread running. Waiting for connections...");
            BluetoothSocket socket = null;
            try {
                while (!isInterrupted() && mmServerSocket != null) {
                    try {
                        socket = mmServerSocket.accept();
                    } catch (IOException e) {
                        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                        if (!bluetoothAdapter.isEnabled() || isInterrupted() || mmServerSocket == null) {
                            Log.i(TAG, "AcceptThread: accept() interrupted/adapter disabled — stopping listener.");
                            break;
                        }
                        Log.w(TAG, "AcceptThread: accept() failed (will attempt restart if adapter enabled): " + msg);
                        try { if (mmServerSocket != null) mmServerSocket.close(); } catch (IOException closeEx) { Log.w(TAG, "AcceptThread: failed closing server socket after accept() failure", closeEx); } finally { mmServerSocket = null; }
                        synchronized (BluetoothManager.this) { if (acceptThread == this) acceptThread = null; }
                        final int RESTART_DELAY_MS = 1500;
                        if (bluetoothAdapter.isEnabled() && !isInterrupted()) {
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                Log.d(TAG, "Attempting to restart AcceptThread after failure...");
                                startAcceptThread();
                            }, RESTART_DELAY_MS);
                        } else {
                            Log.i(TAG, "Not restarting AcceptThread because adapter disabled or thread interrupted.");
                        }
                        return;
                    }

                    if (socket != null) {
                        Log.d(TAG, "AcceptThread: connection accepted from " + socket.getRemoteDevice());
                        synchronized (BluetoothManager.this) {
                            connected(socket, socket.getRemoteDevice());
                            return;
                        }
                    }
                }
            } finally {
                try { if (mmServerSocket != null) mmServerSocket.close(); } catch (IOException e) { Log.w(TAG, "AcceptThread: error while closing server socket in finally", e); }
                synchronized (BluetoothManager.this) { if (acceptThread == this) acceptThread = null; }
                Log.d(TAG, "AcceptThread finished.");
            }
        }

        public void cancel() {
            try { if (mmServerSocket != null) mmServerSocket.close(); } catch (IOException e) { Log.e(TAG, "AcceptThread: Close of server socket failed", e); } finally { synchronized (BluetoothManager.this) { if (acceptThread == this) acceptThread = null; } }
        }
    }

    // ConnectThread
    private class ConnectThread extends Thread {
        private BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "BLUETOOTH_CONNECT permission missing for ConnectThread.");
                        mmSocket = null;
                        return;
                    }
                }
                tmp = device.createInsecureRfcommSocketToServiceRecord(APP_UUID);
            } catch (IOException e) {
                Log.e(TAG, "ConnectThread: Socket create failed", e);
            }
            mmSocket = tmp;
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        public void run() {
            Log.d(TAG, "ConnectThread started.");
            if (mmSocket == null) {
                connectionFailed("Ошибка сокета: Отсутствует разрешение или сокет не создан.");
                return;
            }
            stopDiscovery();
            try {
                mmSocket.connect();
            } catch (IOException e) {
                connectionFailed("Не удалось подключиться: " + e.getMessage());
                try { mmSocket.close(); } catch (IOException e2) { Log.e(TAG, "ConnectThread: unable to close() socket", e2); }
                return;
            }
            synchronized (BluetoothManager.this) { connectThread = null; }
            connected(mmSocket, mmDevice);
        }

        public void cancel() {
            try { if (mmSocket != null) mmSocket.close(); } catch (IOException e) { Log.e(TAG, "ConnectThread: Close of socket failed", e); }
        }
    }

    // ConnectedThread — читает объекты и обрабатывает простые строковые запросы
    private class ConnectedThread extends Thread {
        private final P2PManager.ConnectionListener mmListener;
        private final ObjectOutputStream mmOutStream;
        private final ObjectInputStream mmInStream;

        public ConnectedThread(BluetoothSocket socket, P2PManager.ConnectionListener listener) {
            mmListener = listener;
            ObjectInputStream tmpIn = null;
            ObjectOutputStream tmpOut = null;
            try {
                tmpOut = new ObjectOutputStream(socket.getOutputStream());
                tmpIn = new ObjectInputStream(socket.getInputStream());
            } catch (IOException e) {
                Log.e(TAG, "ConnectedThread: temp sockets not created", e);
            }
            mmOutStream = tmpOut;
            mmInStream = tmpIn;
        }

        public void run() {
            Log.d(TAG, "ConnectedThread started. Ready for I/O.");
            while (true) {
                try {
                    Serializable receivedData = (Serializable) mmInStream.readObject();

                    // handle simple string protocol
                    if (receivedData instanceof String) {
                        String s = (String) receivedData;
                        if (s.equals("REQUEST_PLAYER_NAME")) {
                            String name = readLocalPlayerName();
                            write("PLAYER_NAME:" + name);
                            continue;
                        }
                        // forward PLAYER_NAME and other strings to listener
                        if (mmListener != null) mmListener.onDataReceived(s);
                        continue;
                    }

                    // forward other serializable objects (GameDataModel etc.)
                    if (mmListener != null) mmListener.onDataReceived(receivedData);

                } catch (IOException e) {
                    connectionLost("Соединение потеряно: " + e.getMessage());
                    break;
                } catch (ClassNotFoundException e) {
                    Log.e(TAG, "Received object of unknown class", e);
                }
            }
        }

        public void write(Serializable data) {
            try {
                if (mmOutStream != null) {
                    mmOutStream.writeObject(data);
                    mmOutStream.flush();
                } else {
                    Log.w(TAG, "write(): output stream is null");
                }
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
                connectionLost("Ошибка при отправке данных: " + e.getMessage());
            }
        }

        public void cancel() {
            try {
                if (mmOutStream != null) mmOutStream.close();
                if (mmInStream != null) mmInStream.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}
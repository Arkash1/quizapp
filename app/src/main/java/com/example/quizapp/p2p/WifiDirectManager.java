package com.example.quizapp.p2p;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.quizapp.QuizApplication;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class WifiDirectManager implements P2PManager {

    private static final String TAG = "WifiDirectManager";
    private static final int SERVER_PORT = 8888;

    private Context context;
    private ConnectionListener listener;

    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private final IntentFilter intentFilter;

    private List<DiscoveredDevice> discoveredDevicesUI = new ArrayList<>();
    private List<WifiP2pDevice> wifiP2pDevices = new ArrayList<>();

    private WifiP2pDevice connectedDevice;
    private String localDeviceAddress;
    private ConnectionType type = ConnectionType.WIFI_DIRECT;

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private ServerThread serverThread;
    private ClientThread clientThread;
    private DataTransferThread dataTransferThread;


    public WifiDirectManager() {
        context = null; // Будет установлено в initialize
        manager = (WifiP2pManager) QuizApplication.getInstance().getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(QuizApplication.getInstance(), QuizApplication.getInstance().getMainLooper(), null);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    @Override
    public void initialize(Context context, ConnectionListener listener) {
        this.context = context;
        this.listener = listener;
        Log.d(TAG, "WifiDirectManager initialized.");

        // Регистрация BroadcastReceiver
        if (receiver == null) {
            receiver = new WiFiDirectBroadcastReceiver();
            context.registerReceiver(receiver, intentFilter);
        }
    }

    @Override
    public ConnectionType getConnectionType() {
        return type;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void cleanup() {
        stop();
        disconnect();
        try {
            if (receiver != null) {
                context.unregisterReceiver(receiver);
                receiver = null;
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver was already unregistered.", e);
        }
    }

    @Override
    public void connectTo(String deviceAddress) {
        connect(deviceAddress);
    }

    @Override
    public void stopDiscovery() {
        if (!checkPermissions(context)) {
            listener.onConnectionFailed("Нет необходимых разрешений для остановки поиска.");
            return;
        }

        manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Peer discovery stopped successfully.");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Failed to stop peer discovery: " + reason);
            }
        });
    }

    // --- Основные методы P2PManager ---

    @Override
    public void startDiscovery() {
        if (!checkPermissions(context)) {
            listener.onConnectionFailed("Нет необходимых разрешений для Wi-Fi Direct.");
            return;
        }

        discoveredDevicesUI.clear();
        wifiP2pDevices.clear(); // Очищаем и список реальных устройств

        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Peer discovery started successfully.");
            }

            @Override
            public void onFailure(int reasonCode) {
                listener.onConnectionFailed("Не удалось начать поиск пиров: " + reasonCode);
            }
        });
    }

    @Override
    public void connect(String deviceAddress) {
        if (!checkPermissions(context)) {
            listener.onConnectionFailed("Нет необходимых разрешений для подключения.");
            return;
        }

        final WifiP2pDevice deviceToConnect = findWifiP2pDevice(deviceAddress);
        if (deviceToConnect == null) {
            listener.onConnectionFailed("Устройство не найдено в списке пиров.");
            return;
        }

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = deviceAddress;

        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Connection attempt initiated to " + deviceToConnect.deviceName);
            }

            @Override
            public void onFailure(int reason) {
                listener.onConnectionFailed("Ошибка подключения: " + reason);
            }
        });
    }

    @Override
    public void sendMessage(Serializable data) {
        if (dataTransferThread != null) {
            dataTransferThread.write(data);
        } else {
            Log.e(TAG, "Cannot send message: DataTransferThread is not running.");
        }
    }

    @Override
    public void stop() {
        Log.d(TAG, "Stopping all Wifi Direct threads.");
        if (serverThread != null) {
            serverThread.cancel();
            serverThread = null;
        }
        if (clientThread != null) {
            clientThread.cancel();
            clientThread = null;
        }
        if (dataTransferThread != null) {
            dataTransferThread.cancel();
            dataTransferThread = null;
        }
        executor.shutdownNow();
        executor = Executors.newSingleThreadExecutor();
        disconnect();
    }

    @Override
    public List<DiscoveredDevice> getDiscoveredDevices() {
        return discoveredDevicesUI;
    }

    private void disconnect() {
        if (manager != null && channel != null) {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "Disconnected/Group removed successfully.");
                    listener.onDisconnected("Группа Wi-Fi Direct удалена.");
                    connectedDevice = null;
                    P2PConnectionSingleton.getInstance().setGroupOwner(false);
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Failed to remove group: " + reason);
                }
            });
        }
    }

    // --- Utilities ---

    private boolean checkPermissions(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Permissions check failed: NEARBY_WIFI_DEVICES is missing.");
                return false;
            }
        }

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permissions check failed: ACCESS_FINE_LOCATION is missing.");
            return false;
        }

        return true;
    }

    private WifiP2pDevice findWifiP2pDevice(String address) {
        for (WifiP2pDevice device : wifiP2pDevices) {
            if (device.deviceAddress.equals(address)) {
                return device;
            }
        }
        return null;
    }

    // --- BroadcastReceiver and listeners ---

    private final WifiP2pManager.PeerListListener peerListListener = new WifiP2pManager.PeerListListener() {
        @Override
        public void onPeersAvailable(WifiP2pDeviceList peerList) {
            List<WifiP2pDevice> refreshedPeers = new ArrayList<>(peerList.getDeviceList());

            discoveredDevicesUI.clear();
            wifiP2pDevices.clear();
            wifiP2pDevices.addAll(refreshedPeers);

            for (WifiP2pDevice device : refreshedPeers) {
                DiscoveredDevice discovered = new DiscoveredDevice(device.deviceName, device.deviceAddress);
                discoveredDevicesUI.add(discovered);
                listener.onDeviceFound(discovered.getName(), discovered.getAddress());
                Log.d(TAG, "Found peer: " + device.deviceName + " (" + device.deviceAddress + ")");
            }

            if (discoveredDevicesUI.isEmpty()) {
                Log.d(TAG, "No devices found.");
            }
        }
    };

    private final WifiP2pManager.ConnectionInfoListener connectionInfoListener = new WifiP2pManager.ConnectionInfoListener() {
        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo info) {
            Log.d(TAG, "Connection info available: " + info.isGroupOwner + ", " + info.groupFormed);

            final InetAddress groupOwnerAddress = info.groupOwnerAddress;

            if (info.groupFormed && info.isGroupOwner) {
                Log.d(TAG, "Device is Group Owner (Server).");
                if (serverThread == null) {
                    serverThread = new ServerThread(groupOwnerAddress);
                    executor.execute(serverThread);
                }
                // Устанавливаем флаг groupOwner в синглтоне
                P2PConnectionSingleton.getInstance().setGroupOwner(true);
                listener.onConnected("Хост", type);
            } else if (info.groupFormed) {
                Log.d(TAG, "Device is Client.");
                if (clientThread == null) {
                    clientThread = new ClientThread(groupOwnerAddress);
                    executor.execute(clientThread);
                }
                P2PConnectionSingleton.getInstance().setGroupOwner(false);
                listener.onConnected("Клиент", type);
            }
        }
    };


    private class WiFiDirectBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Log.d(TAG, "Wi-Fi Direct is enabled.");
                } else {
                    Log.d(TAG, "Wi-Fi Direct is not enabled.");
                }
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                if (manager != null) {
                    if (checkPermissions(context)) {
                        manager.requestPeers(channel, peerListListener);
                    } else {
                        Log.e(TAG, "Permissions missing for requesting peers. Cannot call requestPeers.");
                        listener.onConnectionFailed("Ошибка: Необходимые Wi-Fi Direct разрешения отсутствуют.");
                    }
                }
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                if (manager == null) return;

                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

                if (networkInfo.isConnected()) {
                    Log.d(TAG, "Device connected. Requesting connection info.");
                    if (checkPermissions(context)) {
                        manager.requestConnectionInfo(channel, connectionInfoListener);
                    } else {
                        Log.e(TAG, "Permissions missing for requesting connection info.");
                        listener.onConnectionFailed("Ошибка: Необходимые Wi-Fi Direct разрешения отсутствуют.");
                    }
                } else {
                    Log.d(TAG, "Connection lost or disconnected.");
                    if (connectedDevice != null) {
                        listener.onDisconnected(connectedDevice.deviceName);
                    } else {
                        listener.onDisconnected("Соединение Wi-Fi Direct потеряно.");
                    }
                    stop();
                }
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (device != null) {
                    localDeviceAddress = device.deviceAddress;
                    Log.d(TAG, "Local device changed. Address: " + localDeviceAddress);
                }
            }
        }
    }

    // --- Data exchange threads (unchanged) ---
    private void dataExchangeConnected(Socket socket) {
        Log.d(TAG, "Socket connected. Starting DataTransferThread.");
        if (dataTransferThread != null) {
            dataTransferThread.cancel();
        }
        dataTransferThread = new DataTransferThread(socket, listener);
        dataTransferThread.start();
    }

    private class ServerThread implements Runnable {
        private final InetAddress address;
        private ServerSocket serverSocket;

        public ServerThread(InetAddress address) {
            this.address = address;
        }

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(SERVER_PORT);
                Log.d(TAG, "Server socket created on port " + SERVER_PORT);

                Socket clientSocket = serverSocket.accept();
                Log.d(TAG, "Client connected to server.");
                dataExchangeConnected(clientSocket);

            } catch (IOException e) {
                Log.e(TAG, "ServerThread failed: " + e.getMessage());
                listener.onConnectionFailed("Ошибка при старте сервера: " + e.getMessage());
            } finally {
                cancel();
            }
        }

        public void cancel() {
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Server socket close failed.", e);
            }
        }
    }

    private class ClientThread implements Runnable {
        private final InetAddress hostAddress;
        private Socket socket;

        public ClientThread(InetAddress hostAddress) {
            this.hostAddress = hostAddress;
        }

        @Override
        public void run() {
            socket = new Socket();
            try {
                socket.connect(new java.net.InetSocketAddress(hostAddress, SERVER_PORT), 5000);
                Log.d(TAG, "Successfully connected to Group Owner (Server).");
                dataExchangeConnected(socket);

            } catch (IOException e) {
                Log.e(TAG, "ClientThread failed: " + e.getMessage());
                listener.onConnectionFailed("Ошибка подключения клиента: " + e.getMessage());
            }
        }

        public void cancel() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Client socket close failed.", e);
            }
        }
    }

    private class DataTransferThread extends Thread {
        private final Socket mmSocket;
        private final ConnectionListener mmListener;
        private final ObjectOutputStream mmOutStream;
        private final ObjectInputStream mmInStream;

        public DataTransferThread(Socket socket, ConnectionListener listener) {
            mmSocket = socket;
            mmListener = listener;
            ObjectInputStream tmpIn = null;
            ObjectOutputStream tmpOut = null;

            try {
                tmpOut = new ObjectOutputStream(mmSocket.getOutputStream());
                tmpIn = new ObjectInputStream(mmSocket.getInputStream());
            } catch (IOException e) {
                Log.e(TAG, "DataTransferThread: stream setup failed", e);
            }

            mmOutStream = tmpOut;
            mmInStream = tmpIn;
        }

        public void run() {
            Log.d(TAG, "DataTransferThread started. Ready for I/O.");

            while (true) {
                try {
                    Serializable receivedData = (Serializable) mmInStream.readObject();
                    mmListener.onDataReceived(receivedData);

                } catch (IOException e) {
                    Log.e(TAG, "DataTransferThread: read failed", e);
                    mmListener.onDisconnected("Потеряно соединение данных Wi-Fi Direct.");
                    break;
                } catch (ClassNotFoundException e) {
                    Log.e(TAG, "Received object of unknown class", e);
                }
            }
        }

        public void write(Serializable data) {
            try {
                mmOutStream.writeObject(data);
                mmOutStream.flush();
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
                mmListener.onConnectionFailed("Ошибка при отправке данных по Wi-Fi Direct.");
            }
        }

        public void cancel() {
            try {
                if (mmOutStream != null) mmOutStream.close();
                if (mmInStream != null) mmInStream.close();
                if (mmSocket != null) mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "DataTransferThread: close failed", e);
            }
        }
    }
}
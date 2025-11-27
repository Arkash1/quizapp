package com.example.quizapp.p2p;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.quizapp.p2p.P2PManager.ConnectionListener;

public class BluetoothManager implements P2PManager {

    private static final String TAG = "BluetoothManager";
    private static final String SERVICE_NAME = "QuizAppP2P";
    // ИСПРАВЛЕНИЕ: Используем уникальный UUID вместо стандартного SPP для большей надежности
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

    // --- Методы из P2PManager ---

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
        connect(deviceAddress);
    }

    @Override
    public void stopDiscovery() {
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            // Проверка разрешений BLUETOOTH_SCAN для API 31+
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

    // --- Основные методы P2PManager ---

    @Override
    public void startDiscovery() {
        if (!isEnabled()) {
            listener.onConnectionFailed("Bluetooth не включен.");
            return;
        }

        discoveredDevices.clear();

        // Получение сопряженных устройств
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for getBondedDevices.");
                return;
            }
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                discoveredDevices.add(new DiscoveredDevice(device.getName(), device.getAddress()));
                listener.onDeviceFound(device.getName(), device.getAddress());
            }
        }

        // Запуск поиска новых устройств
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        // Проверка разрешений BLUETOOTH_SCAN и ACCESS_FINE_LOCATION
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    @Override
    public void connect(String deviceAddress) {
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
        }
    }

    @Override
    public void stop() {
        Log.d(TAG, "Stopping all Bluetooth threads.");

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }
    }

    @Override
    public List<DiscoveredDevice> getDiscoveredDevices() {
        return discoveredDevices;
    }

    // --- Внутренние методы Bluetooth ---

    /**
     * Вызывается, когда соединение установлено. Запускает поток обмена данными.
     * @param socket Сокет Bluetooth.
     * @param device Устройство, с которым установлено соединение.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        Log.d(TAG, "Connected to " + device.getName());

        // Отменяем потоки, которые больше не нужны
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        // Запуск потока обмена данными
        connectedThread = new ConnectedThread(socket, listener);
        connectedThread.start();

        // Уведомление слушателя
        listener.onConnected(device.getName(), ConnectionType.BLUETOOTH);
    }

    public synchronized void connectionFailed(String message) {
        listener.onConnectionFailed(message);
    }

    public synchronized void connectionLost(String reason) {
        listener.onDisconnected(reason);
        // Повторный запуск прослушивания, чтобы можно было принимать новые соединения
        startAcceptThread();
    }

    /**
     * Запуск потока прослушивания входящих соединений.
     */
    public synchronized void startAcceptThread() {
        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            acceptThread.start();
        }
    }


    // --- Внутренние потоки ---

    private class AcceptThread extends Thread {
        private BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                // Проверка разрешений BLUETOOTH_CONNECT для API 31+
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
            Log.d(TAG, "AcceptThread started. Waiting for connection...");
            BluetoothSocket socket = null;

            while (mmServerSocket != null) {
                try {
                    // Это блокирующий вызов
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "AcceptThread: accept() failed", e);
                    break;
                }

                // Если соединение принято
                if (socket != null) {
                    synchronized (BluetoothManager.this) {
                        connected(socket, socket.getRemoteDevice());
                        // Выходим из цикла, т.к. нас интересует только одно соединение
                        break;
                    }
                }
            }
            Log.d(TAG, "AcceptThread finished.");
        }

        public void cancel() {
            try {
                if (mmServerSocket != null) {
                    mmServerSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "AcceptThread: Close of server socket failed", e);
            }
        }
    }

    private class ConnectThread extends Thread {
        private BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;

            try {
                // Проверка разрешений BLUETOOTH_CONNECT для API 31+
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

            // ИСПРАВЛЕНИЕ: Предотвращаем NullPointerException, если сокет не был создан
            if (mmSocket == null) {
                // Вызываем колбэк с ошибкой, вместо того чтобы падать
                connectionFailed("Ошибка сокета: Отсутствует разрешение или сокет не создан.");
                return;
            }

            // Отменяем поиск, если он активен, перед соединением
            stopDiscovery();

            // Соединение
            try {
                mmSocket.connect();
            } catch (IOException e) {
                connectionFailed("Не удалось подключиться: " + e.getMessage());
                // Закрытие сокета
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "ConnectThread: unable to close() socket during connection failure", e2);
                }
                return;
            }

            // Сброс ConnectThread
            synchronized (BluetoothManager.this) {
                connectThread = null;
            }

            // Запуск ConnectedThread
            connected(mmSocket, mmDevice);
        }

        public void cancel() {
            try {
                if (mmSocket != null) {
                    mmSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "ConnectThread: Close of socket failed", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final P2PManager.ConnectionListener mmListener;
        private final ObjectOutputStream mmOutStream;
        private final ObjectInputStream mmInStream;

        public ConnectedThread(BluetoothSocket socket, P2PManager.ConnectionListener listener) {
            mmListener = listener;
            ObjectInputStream tmpIn = null;
            ObjectOutputStream tmpOut = null;

            try {
                // ПОРЯДОК ВАЖЕН: сначала Out, потом In
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

            // Слушать входящие данные
            while (true) {
                try {
                    // Блокирующий вызов - чтение объекта
                    Serializable receivedData = (Serializable) mmInStream.readObject();

                    // Передача данных слушателю
                    mmListener.onDataReceived(receivedData);

                } catch (IOException e) {
                    connectionLost("Соединение потеряно: " + e.getMessage());
                    break;
                } catch (ClassNotFoundException e) {
                    Log.e(TAG, "Received object of unknown class", e);
                    // Продолжаем слушать, игнорируя этот объект
                }
            }
        }

        public void write(Serializable data) {
            try {
                mmOutStream.writeObject(data);
                mmOutStream.flush();
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
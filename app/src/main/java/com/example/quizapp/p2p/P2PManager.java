package com.example.quizapp.p2p;

import android.content.Context;
import java.io.Serializable;
import java.util.List;

public interface P2PManager {

    // Интерфейс для обратных вызовов активности
    interface ConnectionListener {
        void onDeviceFound(String deviceName, String deviceAddress);
        void onConnected(String deviceName, ConnectionType type);
        void onConnectionFailed(String message);
        void onDisconnected(String reason);
        void onDataReceived(Serializable data);
        void onDeviceLost(String deviceAddress); // Добавлено, чтобы исправить ошибку
    }

    void initialize(Context context, ConnectionListener listener);
    void startDiscovery();
    void connect(String deviceAddress);
    void sendMessage(Serializable data);
    void stop();

    List<DiscoveredDevice> getDiscoveredDevices();
    ConnectionType getConnectionType(); // ERROR: BluetoothManager is not abstract and does not override...
    boolean isEnabled(); // ERROR: connectionManager.isEnabled()
    void cleanup(); // ERROR: activeManager.cleanup()
    void connectTo(String deviceAddress); // ERROR: connectionManager.connectTo(device.getAddress())
    void stopDiscovery(); // ERROR: connectionManager.stopDiscovery()
}
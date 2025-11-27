package com.example.quizapp.p2p;

import com.example.quizapp.p2p.P2PManager;
import com.example.quizapp.p2p.BluetoothManager;
import com.example.quizapp.p2p.WifiDirectManager;
/**
 * Синглтон для хранения активного P2PManager между Activity.
 */
public class P2PConnectionSingleton {
    private static P2PConnectionSingleton instance;
    private P2PManager activeManager;
    private boolean isGroupOwner = false; // НОВОЕ ПОЛЕ
    private P2PConnectionSingleton() {
        // Приватный конструктор
    }

    public static synchronized P2PConnectionSingleton getInstance() {
        if (instance == null) {
            instance = new P2PConnectionSingleton();
        }
        return instance;
    }

    public void setActiveManager(P2PManager manager) {
        if (activeManager != null) {
            activeManager.cleanup();
        }
        this.activeManager = manager;
    }

    public P2PManager getActiveManager() {
        return activeManager;
    }

    // НОВЫЙ МЕТОД: Геттер для P2PDiscoveryActivity
    public boolean isGroupOwner() {
        return isGroupOwner;
    }

    // НОВЫЙ МЕТОД: Сеттер для WifiDirectManager
    public void setGroupOwner(boolean groupOwner) {
        isGroupOwner = groupOwner;
    }

    // Метод для очистки после завершения игры
    public void clear() {
        if (activeManager != null) {
            activeManager.cleanup();
            activeManager = null;
        }
    }
}
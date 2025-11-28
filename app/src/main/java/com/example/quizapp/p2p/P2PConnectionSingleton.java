package com.example.quizapp.p2p;

import com.example.quizapp.p2p.P2PManager;
import com.example.quizapp.p2p.BluetoothManager;
import com.example.quizapp.p2p.WifiDirectManager;

public class P2PConnectionSingleton {
    private static P2PConnectionSingleton instance;
    private P2PManager activeManager;
    private boolean isGroupOwner = false;

    private P2PConnectionSingleton() {
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

    public boolean isGroupOwner() {
        return isGroupOwner;
    }

    public void setGroupOwner(boolean groupOwner) {
        isGroupOwner = groupOwner;
    }

    public void clear() {
        if (activeManager != null) {
            activeManager.cleanup();
            activeManager = null;
        }
        // Сбрасываем флаг, чтобы состояние не "утекало"
        isGroupOwner = false;
    }
}
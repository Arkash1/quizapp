package com.example.quizapp.p2p;

public class DiscoveredDevice {
    public final String name;
    public final String address;

    public DiscoveredDevice(String name, String address) {
        this.name = name;
        this.address = address;
    }

    public String getName() { // Добавляем геттеры, чтобы исправить P2PDiscoveryActivity
        return name;
    }

    public String getAddress() { // Добавляем геттеры, чтобы исправить P2PDiscoveryActivity
        return address;
    }

    @Override
    public String toString() {
        return name + " (" + address + ")";
    }
}
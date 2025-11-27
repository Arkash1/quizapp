package com.example.quizapp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.quizapp.p2p.ConnectionType;
import com.example.quizapp.p2p.DiscoveredDevice;
import com.example.quizapp.p2p.P2PConnectionSingleton;
import com.example.quizapp.p2p.P2PManager;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class P2PDiscoveryActivity extends AppCompatActivity implements P2PManager.ConnectionListener {

    private static final String TAG = "P2PDiscoveryActivity";

    private P2PManager connectionManager;
    private ConnectionType connectionType;

    private ListView devicesListView;
    private TextView statusTextView;
    private Button startDiscoveryButton;

    private ArrayAdapter<DiscoveredDevice> devicesAdapter;
    private final List<DiscoveredDevice> discoveredDeviceList = new ArrayList<>();

    // BroadcastReceiver для Bluetooth (для Wi-Fi Direct он находится внутри WifiDirectManager)
    private BroadcastReceiver bluetoothReceiver;
    private IntentFilter bluetoothIntentFilter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_p2p_discovery);

        connectionManager = P2PConnectionSingleton.getInstance().getActiveManager();
        if (connectionManager == null) {
            Toast.makeText(this, "Ошибка: не выбран тип соединения.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        connectionType = connectionManager.getConnectionType();

        // Инициализация UI элементов
        devicesListView = findViewById(R.id.devices_list_view);
        statusTextView = findViewById(R.id.status_text_view);
        startDiscoveryButton = findViewById(R.id.btn_start_discovery);

        // Установка заголовка активности
        String title = (connectionType == ConnectionType.BLUETOOTH) ? "Поиск Bluetooth-устройств" : "Поиск Wi-Fi Direct пиров";
        setTitle(title);

        // Инициализация менеджера и списка
        connectionManager.initialize(this, this);

        devicesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredDeviceList);
        devicesListView.setAdapter(devicesAdapter);

        // Обработчик нажатия на кнопку "Начать поиск"
        startDiscoveryButton.setOnClickListener(v -> startDiscovery());

        // Обработчик нажатия на элемент списка для подключения
        devicesListView.setOnItemClickListener((parent, view, position, id) -> {
            DiscoveredDevice device = discoveredDeviceList.get(position);
            attemptConnect(device);
        });

        // Если это Bluetooth, инициализируем BroadcastReceiver
        if (connectionType == ConnectionType.BLUETOOTH) {
            setupBluetoothReceiver();
        }

        // Проверяем, включен ли адаптер, и начинаем поиск
        if (connectionManager.isEnabled()) {
            startDiscovery();
        } else {
            statusTextView.setText(connectionType.toString() + " отключен. Включите его для поиска.");
            // При необходимости, здесь можно запросить включение
        }
    }

    // --- Логика проверки разрешений ---

    /**
     * Проверяет, предоставлены ли необходимые разрешения для P2P (Wi-Fi Direct или Bluetooth).
     */
    private boolean checkP2PPermissions() {
        boolean fineLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (connectionType == ConnectionType.WIFI_DIRECT) {
            // Для Android 12+ (API 31+) требуется NEARBY_WIFI_DEVICES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                boolean nearbyWifiGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
                if (!nearbyWifiGranted) {
                    Log.e(TAG, "Permissions check failed: NEARBY_WIFI_DEVICES is missing.");
                    return false;
                }
            }
            // Для всех версий Wi-Fi Direct требуется ACCESS_FINE_LOCATION
            if (!fineLocationGranted) {
                Log.e(TAG, "Permissions check failed: ACCESS_FINE_LOCATION is missing for Wi-Fi Direct.");
                return false;
            }
        }

        // Логика разрешений для Bluetooth
        if (connectionType == ConnectionType.BLUETOOTH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // BLUETOOTH_SCAN (для поиска) и BLUETOOTH_CONNECT (для соединения)
                boolean bluetoothScanGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
                boolean bluetoothConnectGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
                if (!bluetoothScanGranted || !bluetoothConnectGranted) {
                    Log.e(TAG, "Permissions check failed: BLUETOOTH_SCAN or BLUETOOTH_CONNECT is missing.");
                    return false;
                }
            }
            // Bluetooth на старых API (до S) требует разрешения на местоположение
            else if (!fineLocationGranted) {
                Log.e(TAG, "Permissions check failed: ACCESS_FINE_LOCATION is missing for Bluetooth (legacy).");
                return false;
            }
        }

        return true;
    }


    // --- Логика поиска и подключения ---

    /**
     * Начинает процесс обнаружения пиров.
     */
    private void startDiscovery() {
        if (!connectionManager.isEnabled()) {
            Toast.makeText(this, connectionType.toString() + " не включен.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Проверка разрешений перед началом P2P-действий
        if (!checkP2PPermissions()) {
            String message = "Не предоставлены необходимые разрешения для поиска. Перезапустите приложение и предоставьте их.";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            statusTextView.setText("Ошибка: " + message);
            startDiscoveryButton.setEnabled(true);
            Log.e(TAG, "Cannot start discovery due to missing permissions.");
            return;
        }

        discoveredDeviceList.clear();
        devicesAdapter.notifyDataSetChanged();
        statusTextView.setText("Поиск устройств...");

        connectionManager.startDiscovery();

        // Блокируем кнопку во время поиска
        startDiscoveryButton.setEnabled(false);
    }

    /**
     * Инициирует попытку подключения к выбранному устройству.
     */
    private void attemptConnect(DiscoveredDevice device) {
        if (!connectionManager.isEnabled()) {
            Toast.makeText(this, connectionType.toString() + " не включен.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Проверка разрешений перед началом P2P-действий
        if (!checkP2PPermissions()) {
            String message = "Не предоставлены необходимые разрешения для подключения. Перезапустите приложение.";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            statusTextView.setText("Ошибка: " + message);
            startDiscoveryButton.setEnabled(true);
            devicesListView.setEnabled(true);
            Log.e(TAG, "Cannot connect due to missing permissions.");
            return;
        }

        // Остановка поиска, чтобы избежать конфликтов при подключении
        connectionManager.stopDiscovery();

        statusTextView.setText("Попытка подключения к " + device.getName() + "...");
        connectionManager.connectTo(device.getAddress()); // Запускаем реальное подключение

        // Блокируем список, пока идет подключение
        devicesListView.setEnabled(false);
    }

    // --- Обработка событий Bluetooth Discovery ---

    private void setupBluetoothReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {

                    // 1. БЕЗОПАСНОЕ ПОЛУЧЕНИЕ BluetoothDevice
                    BluetoothDevice device;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // Android 13+ (API 33)
                        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    } else {
                        // До Android 13
                        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    }

                    if (device != null) {
                        // НОВАЯ ПРОВЕРКА: Проверка разрешений для Bluetooth API.
                        // Это требование анализатора Lint для безопасного вызова getName().
                        boolean hasPermission = true;

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                hasPermission = false;
                            }
                        } else {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                hasPermission = false;
                            }
                        }

                        // 2. Вызываем getName() только если есть разрешение и имя не null
                        if (hasPermission && device.getName() != null) {
                            P2PDiscoveryActivity.this.onDeviceFound(device.getName(), device.getAddress());
                        } else {
                            // Если нет имени или нет разрешения, используем адрес
                            String deviceName = (device.getName() != null) ? device.getName() : "Неизвестное устройство";
                            Log.d(TAG, "Found device, but name not accessible or is null. Name: " + deviceName);
                        }
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    onDiscoveryFinished();
                }
            }
        };

        bluetoothIntentFilter = new IntentFilter();
        bluetoothIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        bluetoothIntentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
    }

    private void onDiscoveryFinished() {
        statusTextView.setText("Поиск завершен. Найдено устройств: " + discoveredDeviceList.size());
        startDiscoveryButton.setEnabled(true);
        Log.d(TAG, "Discovery finished.");
    }

    // --- Реализация ConnectionListener ---

    @Override
    public void onDeviceFound(String deviceName, String deviceAddress) {
        // Мы уже убедились, что deviceName != null перед вызовом этого метода.
        DiscoveredDevice newDevice = new DiscoveredDevice(deviceName, deviceAddress);

        // Проверяем, чтобы не добавлять одно и то же устройство дважды
        if (!discoveredDeviceList.contains(newDevice)) {
            discoveredDeviceList.add(newDevice);
            devicesAdapter.notifyDataSetChanged();
            statusTextView.setText("Найдено: " + deviceName);
            Log.d(TAG, "Device found: " + deviceName);
        }
    }

    @Override
    public void onDeviceLost(String deviceAddress) {
        // Логика для удаления потерянного устройства из списка, если это необходимо
        Log.d(TAG, "Device lost: " + deviceAddress);
    }

    @Override
    public void onConnected(String deviceName, ConnectionType type) {
        Toast.makeText(this, "Успешное подключение к " + deviceName + " через " + type, Toast.LENGTH_LONG).show();
        statusTextView.setText("Подключено к: " + deviceName);

        // Переход к GameActivity
        Intent intent = new Intent(P2PDiscoveryActivity.this, GameActivity.class);
        intent.putExtra("CONNECTION_TYPE", type.name());

        // isGroupOwner() должен быть вызван только после того, как GroupOwner (хост) определен
        boolean isHost = (type == ConnectionType.WIFI_DIRECT && P2PConnectionSingleton.getInstance().isGroupOwner());
        intent.putExtra("IS_HOST", isHost);
        startActivity(intent);
        finish();
    }

    @Override
    public void onConnectionFailed(String message) {
        Toast.makeText(this, "Ошибка подключения: " + message, Toast.LENGTH_LONG).show();
        statusTextView.setText("Ошибка: " + message);

        // Восстанавливаем возможность поиска и подключения
        startDiscoveryButton.setEnabled(true);
        devicesListView.setEnabled(true);
        startDiscovery(); // Перезапускаем поиск после неудачи
    }

    @Override
    public void onDisconnected(String reason) {
        Toast.makeText(this, "Соединение потеряно: " + reason, Toast.LENGTH_LONG).show();
        statusTextView.setText("Соединение потеряно: " + reason);

        // Восстанавливаем UI
        startDiscoveryButton.setEnabled(true);
        devicesListView.setEnabled(true);

        // Перезапускаем поиск
        startDiscovery();
    }

    @Override
    public void onDataReceived(Serializable data) {
        // Данные будут обрабатываться в GameActivity.
    }

    // --- Жизненный цикл активности ---

    @Override
    protected void onResume() {
        super.onResume();
        if (connectionType == ConnectionType.BLUETOOTH && bluetoothReceiver != null) {
            registerReceiver(bluetoothReceiver, bluetoothIntentFilter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (connectionType == ConnectionType.BLUETOOTH && bluetoothReceiver != null) {
            unregisterReceiver(bluetoothReceiver);
        }
        // Остановка всех P2P операций при уходе с экрана
        if (connectionManager != null) {
            connectionManager.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Полная очистка менеджера
        if (P2PConnectionSingleton.getInstance().getActiveManager() != null) {
            P2PConnectionSingleton.getInstance().getActiveManager().cleanup();
        }
    }
}
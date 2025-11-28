package com.example.quizapp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Color;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.quizapp.p2p.BluetoothManager;
import com.example.quizapp.p2p.ConnectionType;
import com.example.quizapp.p2p.DiscoveredDevice;
import com.example.quizapp.p2p.P2PConnectionSingleton;
import com.example.quizapp.p2p.P2PManager;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Исправленный P2PDiscoveryActivity:
 * - НЕ вызывает connectionManager.stop() в onPause()
 * - НЕ вызывает activeManager.cleanup() в onDestroy() — менеджер сохранится для GameActivity
 * - BroadcastReceiver по-прежнему корректно регистрируется/отписывается
 * - Бар "Стать хостом" вставляется после statusTextView, чтобы не перекрывать верхние элементы
 */
public class P2PDiscoveryActivity extends AppCompatActivity implements P2PManager.ConnectionListener {

    private static final String TAG = "P2PDiscoveryActivity";

    private static final int PERMISSIONS_REQUEST_HOST = 2001;
    private static final int DISCOVERABLE_REQUEST_CODE = 2002;
    private static final String PREFS_NAME = "quiz_p2p_prefs";
    private static final String PREF_HOST_MODE = "pref_host_mode";

    private P2PManager connectionManager;
    private ConnectionType connectionType;

    private ListView devicesListView;
    private TextView statusTextView;
    private Button startDiscoveryButton;

    private ArrayAdapter<DiscoveredDevice> devicesAdapter;
    private final List<DiscoveredDevice> discoveredDeviceList = new ArrayList<>();

    private BroadcastReceiver bluetoothReceiver;
    private IntentFilter bluetoothIntentFilter;
    private boolean isReceiverRegistered = false;

    // Host UI controls
    private Button btnHostToggle;
    private TextView tvListeningStatus;
    private boolean isHostMode = false;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_p2p_discovery);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        connectionManager = P2PConnectionSingleton.getInstance().getActiveManager();
        if (connectionManager == null) {
            Toast.makeText(this, "Ошибка: не выбран тип соединения.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        connectionType = connectionManager.getConnectionType();

        devicesListView = findViewById(R.id.devices_list_view);
        statusTextView = findViewById(R.id.status_text_view);
        startDiscoveryButton = findViewById(R.id.btn_start_discovery);

        String title = (connectionType == ConnectionType.BLUETOOTH) ? "Поиск Bluetooth-устройств" : "Поиск Wi-Fi Direct пиров";
        setTitle(title);

        // Initialize manager with application context to avoid Activity leaks
        connectionManager.initialize(getApplicationContext(), this);

        // Add host toggle controls programmatically (insert AFTER statusTextView to avoid overlap)
        addHostControls();

        devicesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredDeviceList);
        devicesListView.setAdapter(devicesAdapter);

        startDiscoveryButton.setOnClickListener(v -> startDiscovery());

        devicesListView.setOnItemClickListener((parent, view, position, id) -> {
            DiscoveredDevice device = discoveredDeviceList.get(position);
            attemptConnect(device);
        });

        if (connectionType == ConnectionType.BLUETOOTH) {
            setupBluetoothReceiver();
            try {
                getApplicationContext().registerReceiver(bluetoothReceiver, bluetoothIntentFilter);
                isReceiverRegistered = true;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Bluetooth receiver already registered or failed to register.", e);
            }
        }

        if (connectionManager.isEnabled()) {
            startDiscovery();
        } else {
            statusTextView.setText(connectionType.toString() + " отключен. Включите его для поиска.");
        }

        // Restore saved host-mode
        boolean savedHost = prefs.getBoolean(PREF_HOST_MODE, false);
        if (savedHost) {
            // Request permissions and ensure discoverable/listening started
            requestHostPermissionsAndEnable();
        }

        // Update UI
        updateListeningStatus();
    }

    private void addHostControls() {
        View parentOfStatus = (View) statusTextView.getParent();
        if (!(parentOfStatus instanceof ViewGroup)) {
            View root = findViewById(android.R.id.content);
            if (!(root instanceof ViewGroup)) return;
            ViewGroup rootGroup = (ViewGroup) root;
            insertHostBarInto(rootGroup, 0);
            return;
        }

        ViewGroup parent = (ViewGroup) parentOfStatus;
        int index = parent.indexOfChild(statusTextView);
        if (index < 0) index = 0;
        insertHostBarInto(parent, index + 1);
    }

    private void insertHostBarInto(ViewGroup container, int index) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(12, 12, 12, 12);
        bar.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bar.setLayoutParams(barParams);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        btnHostToggle = new Button(this);
        btnHostToggle.setText("Стать хостом");
        btnHostToggle.setAllCaps(false);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnHostToggle.setLayoutParams(btnParams);

        tvListeningStatus = new TextView(this);
        tvListeningStatus.setText("Состояние: неизвестно");
        tvListeningStatus.setPadding(16, 0, 0, 0);
        LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvListeningStatus.setLayoutParams(tvParams);
        tvListeningStatus.setGravity(Gravity.CENTER_VERTICAL);

        bar.addView(btnHostToggle);
        bar.addView(tvListeningStatus);

        try {
            container.addView(bar, index);
        } catch (Exception e) {
            container.addView(bar);
        }

        btnHostToggle.setOnClickListener(v -> {
            if (!isHostMode) {
                requestHostPermissionsAndEnable();
            } else {
                disableHostMode();
            }
        });
    }

    private void requestHostPermissionsAndEnable() {
        if (connectionType == ConnectionType.BLUETOOTH) {
            List<String> perms = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(android.Manifest.permission.BLUETOOTH_CONNECT);
                }
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(android.Manifest.permission.BLUETOOTH_ADVERTISE);
                }
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(android.Manifest.permission.BLUETOOTH_SCAN);
                }
            } else {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
                }
            }

            if (!perms.isEmpty()) {
                ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERMISSIONS_REQUEST_HOST);
                return;
            }

            makeDeviceDiscoverableAndListen();
        } else if (connectionType == ConnectionType.WIFI_DIRECT) {
            P2PConnectionSingleton.getInstance().setGroupOwner(true);
            isHostMode = true;
            prefs.edit().putBoolean(PREF_HOST_MODE, true).apply();
            Toast.makeText(this, "Постараемся стать Group Owner при подключении.", Toast.LENGTH_SHORT).show();
            updateListeningStatus();
        } else {
            Toast.makeText(this, "Режим не поддерживается для этого типа соединения.", Toast.LENGTH_SHORT).show();
        }
    }

    private void makeDeviceDiscoverableAndListen() {
        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivityForResult(discoverableIntent, DISCOVERABLE_REQUEST_CODE);
    }

    private void finishEnableHostAfterDiscoverable() {
        if (connectionManager instanceof BluetoothManager) {
            BluetoothManager btManager = (BluetoothManager) connectionManager;
            boolean started = btManager.startListening();
            if (started) {
                isHostMode = true;
                prefs.edit().putBoolean(PREF_HOST_MODE, true).apply();
                Toast.makeText(this, "Устройство слушает входящие (хост).", Toast.LENGTH_SHORT).show();
            } else {
                isHostMode = false;
                prefs.edit().putBoolean(PREF_HOST_MODE, false).apply();
                Toast.makeText(this, "Не удалось запустить слушание. Проверьте разрешения и включён ли Bluetooth.", Toast.LENGTH_LONG).show();
            }
            updateListeningStatus();
        }
    }

    private void disableHostMode() {
        if (connectionType == ConnectionType.BLUETOOTH) {
            if (connectionManager instanceof BluetoothManager) {
                BluetoothManager btManager = (BluetoothManager) connectionManager;
                btManager.stopListening();
                isHostMode = false;
                prefs.edit().putBoolean(PREF_HOST_MODE, false).apply();
                Toast.makeText(this, "Прослушивание остановлено.", Toast.LENGTH_SHORT).show();
            }
        } else if (connectionType == ConnectionType.WIFI_DIRECT) {
            P2PConnectionSingleton.getInstance().setGroupOwner(false);
            isHostMode = false;
            prefs.edit().putBoolean(PREF_HOST_MODE, false).apply();
            Toast.makeText(this, "Хост-режим отключён.", Toast.LENGTH_SHORT).show();
        }
        updateListeningStatus();
    }

    private void updateListeningStatus() {
        runOnUiThread(() -> {
            if (connectionType == ConnectionType.BLUETOOTH) {
                if (connectionManager instanceof BluetoothManager) {
                    boolean listening = ((BluetoothManager) connectionManager).isListening();
                    tvListeningStatus.setText(listening ? "Слушает входящие (хост)" : "Не слушает (клиент)");
                    tvListeningStatus.setTextColor(listening ? Color.GREEN : Color.DKGRAY);
                    btnHostToggle.setText(listening ? "Остановить хост" : "Стать хостом");
                } else {
                    tvListeningStatus.setText("Bluetooth менеджер недоступен");
                    tvListeningStatus.setTextColor(Color.DKGRAY);
                }
            } else if (connectionType == ConnectionType.WIFI_DIRECT) {
                boolean owner = P2PConnectionSingleton.getInstance().isGroupOwner();
                tvListeningStatus.setText(owner ? "Режим: Group Owner (запрошен)" : "Режим: Клиент");
                tvListeningStatus.setTextColor(owner ? Color.GREEN : Color.DKGRAY);
                btnHostToggle.setText(owner ? "Остановить хост" : "Стать хостом");
            } else {
                tvListeningStatus.setText("Состояние: неизвестно");
                tvListeningStatus.setTextColor(Color.DKGRAY);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_HOST) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                makeDeviceDiscoverableAndListen();
            } else {
                Toast.makeText(this, "Нужны разрешения для работы в хост-режиме.", Toast.LENGTH_LONG).show();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == DISCOVERABLE_REQUEST_CODE) {
            if (resultCode > 0) {
                finishEnableHostAfterDiscoverable();
            } else {
                Toast.makeText(this, "Не сделано discoverable — без этого некоторые устройства не увидят вас.", Toast.LENGTH_LONG).show();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private boolean checkP2PPermissions() {
        boolean fineLocationGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (connectionType == ConnectionType.WIFI_DIRECT) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                boolean nearbyWifiGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
                if (!nearbyWifiGranted) {
                    Log.e(TAG, "Permissions check failed: NEARBY_WIFI_DEVICES is missing.");
                    return false;
                }
            }
            if (!fineLocationGranted) {
                Log.e(TAG, "Permissions check failed: ACCESS_FINE_LOCATION is missing for Wi-Fi Direct.");
                return false;
            }
        }

        if (connectionType == ConnectionType.BLUETOOTH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                boolean bluetoothScanGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
                boolean bluetoothConnectGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
                if (!bluetoothScanGranted || !bluetoothConnectGranted) {
                    Log.e(TAG, "Permissions check failed: BLUETOOTH_SCAN or BLUETOOTH_CONNECT is missing.");
                    return false;
                }
            } else if (!fineLocationGranted) {
                Log.e(TAG, "Permissions check failed: ACCESS_FINE_LOCATION is missing for Bluetooth (legacy).");
                return false;
            }
        }

        return true;
    }

    private void startDiscovery() {
        if (!connectionManager.isEnabled()) {
            Toast.makeText(this, connectionType.toString() + " не включен.", Toast.LENGTH_SHORT).show();
            return;
        }

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

        startDiscoveryButton.setEnabled(false);
    }

    private void attemptConnect(DiscoveredDevice device) {
        if (!connectionManager.isEnabled()) {
            Toast.makeText(this, connectionType.toString() + " не включен.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!checkP2PPermissions()) {
            String message = "Не предоставлены необходимые разрешения для подключения. Перезапустите приложение.";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            statusTextView.setText("Ошибка: " + message);
            startDiscoveryButton.setEnabled(true);
            devicesListView.setEnabled(true);
            Log.e(TAG, "Cannot connect due to missing permissions.");
            return;
        }

        connectionManager.stopDiscovery();

        statusTextView.setText("Попытка подключения к " + device.getName() + "...");
        connectionManager.connectTo(device.getAddress());

        devicesListView.setEnabled(false);
    }

    private void setupBluetoothReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {

                    BluetoothDevice device;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    } else {
                        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    }

                    if (device != null) {
                        String name = device.getName();
                        if (name == null || name.isEmpty()) name = device.getAddress();
                        P2PDiscoveryActivity.this.onDeviceFound(name, device.getAddress());
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

    @Override
    public void onDeviceFound(String deviceName, String deviceAddress) {
        runOnUiThread(() -> {
            DiscoveredDevice newDevice = new DiscoveredDevice(deviceName, deviceAddress);
            if (!discoveredDeviceList.contains(newDevice)) {
                discoveredDeviceList.add(newDevice);
                devicesAdapter.notifyDataSetChanged();
                statusTextView.setText("Найдено: " + deviceName);
                Log.d(TAG, "Device found: " + deviceName + " / " + deviceAddress);
            }
        });
    }

    @Override
    public void onDeviceLost(String deviceAddress) {
        Log.d(TAG, "Device lost: " + deviceAddress);
    }

    @Override
    public void onConnected(String deviceName, ConnectionType type) {
        runOnUiThread(() -> {
            boolean isHost = P2PConnectionSingleton.getInstance().isGroupOwner();

            Toast.makeText(P2PDiscoveryActivity.this, "Успешное подключение к " + deviceName + " через " + type + (isHost ? " (хост)" : " (клиент)"), Toast.LENGTH_LONG).show();
            statusTextView.setText("Подключено к: " + deviceName);

            Intent intent = new Intent(P2PDiscoveryActivity.this, GameActivity.class);
            intent.putExtra("IS_PVP_MODE", true);
            intent.putExtra("OPPONENT_NAME", deviceName);
            intent.putExtra("CONNECTION_TYPE", type.name());
            intent.putExtra("IS_HOST", isHost);

            startActivity(intent);
            finish();
        });
    }

    @Override
    public void onConnectionFailed(String message) {
        runOnUiThread(() -> {
            Toast.makeText(P2PDiscoveryActivity.this, "Ошибка подключения: " + message, Toast.LENGTH_LONG).show();
            statusTextView.setText("Ошибка: " + message);
            startDiscoveryButton.setEnabled(true);
            devicesListView.setEnabled(true);
            startDiscovery();
            updateListeningStatus();
        });
    }

    @Override
    public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            Toast.makeText(P2PDiscoveryActivity.this, "Соединение потеряно: " + reason, Toast.LENGTH_LONG).show();
            statusTextView.setText("Соединение потеряно: " + reason);
            startDiscoveryButton.setEnabled(true);
            devicesListView.setEnabled(true);
            startDiscovery();
            updateListeningStatus();
        });
    }

    @Override
    public void onDataReceived(Serializable data) {
        // данные обрабатывает GameActivity
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (connectionType == ConnectionType.BLUETOOTH && bluetoothReceiver != null && !isReceiverRegistered) {
            try {
                getApplicationContext().registerReceiver(bluetoothReceiver, bluetoothIntentFilter);
                isReceiverRegistered = true;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Bluetooth receiver already registered or failed to register.", e);
            }
        }
        updateListeningStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ВАЖНО: не останавливаем менеджер при паузе, иначе соединение закроется перед запуском GameActivity.
        if (connectionType == ConnectionType.BLUETOOTH && bluetoothReceiver != null && isReceiverRegistered) {
            try {
                getApplicationContext().unregisterReceiver(bluetoothReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Bluetooth receiver not registered or already unregistered.");
            } finally {
                isReceiverRegistered = false;
            }
        }
        // Не вызываем connectionManager.stop() здесь — менеджер нужен дальше в GameActivity
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Отписываем ресивер, но не очищаем активный менеджер — GameActivity может его использовать
        if (connectionType == ConnectionType.BLUETOOTH && bluetoothReceiver != null && isReceiverRegistered) {
            try { getApplicationContext().unregisterReceiver(bluetoothReceiver); } catch (IllegalArgumentException ignored) {}
            isReceiverRegistered = false;
        }
        // НЕВЫЗЫВАТЬ: P2PConnectionSingleton.getInstance().getActiveManager().cleanup();
    }
}
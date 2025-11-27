package com.example.quizapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.quizapp.p2p.BluetoothManager;
import com.example.quizapp.p2p.ConnectionType;
import com.example.quizapp.p2p.P2PConnectionSingleton;
import com.example.quizapp.p2p.P2PManager;
import com.example.quizapp.p2p.WifiDirectManager;

public class P2PConnectActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private ConnectionType selectedType = null;

    // Определяем разрешения, необходимые для P2P-соединений
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION, // Нужно для поиска устройств (ранее BLUETOOTH_SCAN)
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            // Разрешения для Android 12 (API 31) и выше:
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_p2p_connect);

        Button btnBluetooth = findViewById(R.id.btn_bluetooth_mode);
        Button btnWifiDirect = findViewById(R.id.btn_wifi_direct_mode);

        btnBluetooth.setOnClickListener(v -> {
            QuizApplication.getInstance().playClickSound();
            selectedType = ConnectionType.BLUETOOTH;
            handleConnectionSetup(selectedType);
        });

        btnWifiDirect.setOnClickListener(v -> {
            QuizApplication.getInstance().playClickSound();
            selectedType = ConnectionType.WIFI_DIRECT;
            handleConnectionSetup(selectedType);
        });
    }

    /**
     * Обрабатывает проверку разрешений и, если они предоставлены,
     * устанавливает P2PManager и переходит к Discovery Activity.
     */
    private void handleConnectionSetup(ConnectionType type) {
        if (!checkPermissions()) {
            // Если разрешения не предоставлены, запросить их
            requestPermissions();
            return; // Ждем результата запроса
        }

        // Если разрешения предоставлены, продолжить настройку
        P2PManager manager;

        if (type == ConnectionType.BLUETOOTH) {
            manager = new BluetoothManager();
        } else if (type == ConnectionType.WIFI_DIRECT) {
            manager = new WifiDirectManager();
        } else {
            Toast.makeText(this, "Не выбран тип соединения.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Установка активного менеджера в синглтоне
        P2PConnectionSingleton.getInstance().setActiveManager(manager);

        // Переход к экрану поиска устройств
        Intent intent = new Intent(P2PConnectActivity.this, P2PDiscoveryActivity.class);
        intent.putExtra("CONNECTION_TYPE", type); // Передать выбранный тип
        startActivity(intent);
        // finish(); // Не закрываем эту активность, чтобы можно было вернуться и выбрать другой тип
    }

    // --- Логика проверки и запроса разрешений ---

    /**
     * Проверяет, предоставлены ли все необходимые разрешения.
     */
    private boolean checkPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            // Разрешения для старых API не проверяются, если API >= 31, и наоборот
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // На API 31+ проверяем BLUETOOTH_CONNECT и BLUETOOTH_SCAN
                if ((permission.equals(Manifest.permission.BLUETOOTH) || permission.equals(Manifest.permission.BLUETOOTH_ADMIN))
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    continue; // Пропускаем старые, если используем новые
                }
            } else {
                // На API < 31 проверяем старые разрешения и ACCESS_FINE_LOCATION
                if (permission.equals(Manifest.permission.BLUETOOTH_CONNECT) || permission.equals(Manifest.permission.BLUETOOTH_SCAN)) {
                    continue; // Пропускаем новые, если используем старые
                }
            }

            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Запрашивает необходимые разрешения у пользователя.
     */
    private void requestPermissions() {
        // Фильтруем список разрешений, которые действительно нужно запросить
        String[] permissionsToRequest = new String[REQUIRED_PERMISSIONS.length];
        int count = 0;

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest[count++] = permission;
            }
        }

        // Копируем только те, что нужно запросить
        String[] finalPermissions = new String[count];
        System.arraycopy(permissionsToRequest, 0, finalPermissions, 0, count);

        if (finalPermissions.length > 0) {
            ActivityCompat.requestPermissions(this, finalPermissions, PERMISSION_REQUEST_CODE);
        }
    }

    /**
     * Обрабатывает результат запроса разрешений.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted && selectedType != null) {
                // Если все предоставлено, продолжаем настройку соединения
                handleConnectionSetup(selectedType);
            } else {
                Toast.makeText(this, "Для P2P-игры необходимы разрешения.", Toast.LENGTH_LONG).show();
            }
        }
    }
}